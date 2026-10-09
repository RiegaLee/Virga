package io.github.kloping.qqbot.network.hookauth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.kloping.io.ReadUtils;
import io.github.kloping.qqbot.Starter;
import io.github.kloping.qqbot.entities.Pack;
import io.github.kloping.qqbot.impl.BaseConnectedEvent;
import io.github.kloping.qqbot.network.AuthAndHeartbeat;
import io.github.kloping.qqbot.network.WssWorker;
import io.github.kloping.spt.annotations.AutoStand;
import io.github.kloping.spt.annotations.Entity;
import io.github.kloping.spt.interfaces.Logger;
import lombok.Getter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static io.github.kloping.qqbot.Resource.GSON;
import static io.github.kloping.spt.PartUtils.getExceptionLine;

/**
 * @author github kloping
 * @date 2025/4/18-10:02
 */
@Entity
public class HookAuth {

    /** X.509 SubjectPublicKeyInfo prefix for a raw 32-byte Ed25519 public key (RFC 8410). */
    private static final byte[] ED25519_X509_PREFIX = hexDecode("302a300506032b6570032100");

    @AutoStand
    private Logger logger;

    @AutoStand
    WssWorker wssWorker;

    @AutoStand
    Starter.Config config;

    @Getter
    private HttpServer httpServer;

    public void webhookServerStart() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(config.getWebhookport()), 0);
            // 显式设置线程池 避免默认单线程调度在高并发/慢处理时被占满导致"打不开"
            httpServer.setExecutor(Executors.newFixedThreadPool(
                    Math.max(4, Runtime.getRuntime().availableProcessors() * 2)));
            httpServer.createContext(config.getWebhookpath(), this::handleWebhook);
            // 健康检查端点 便于区分"端口死了"还是"业务path挂了"
            httpServer.createContext("/health", exchange -> writeResponse(exchange, 200, "ok"));
            httpServer.start();
            logger.info(String.format(BaseConnectedEvent.FORMAT_SERVER, config.getAppid()));
        } catch (IOException e) {
            logger.error("在WebHook服务启动时失败\n" + getExceptionLine(e));
        }
    }

    /**
     * webhook 请求处理 保证:
     * <br/>1. 无论成功失败 finally 中都会写响应并 close(exchange) 避免连接/线程泄漏
     * <br/>2. resp 永不为 null 避免 resp.length() NPE
     * <br/>3. body 非法 JSON / pack 为 null / op 为 null 时直接 400 不再 NPE
     */
    private void handleWebhook(HttpExchange exchange) {
        String resp = "{}";
        int status = 200;
        try {
            String body = ReadUtils.readAll(exchange.getRequestBody(), "UTF-8");
            logger.log(String.format("webhook-r: %s", body));
            Pack pack = null;
            try {
                if (body != null && !body.trim().isEmpty()) {
                    pack = GSON.fromJson(body, Pack.class);
                }
            } catch (Exception e) {
                logger.waring("WebHook请求体解析失败: " + getExceptionLine(e));
            }
            if (pack == null || pack.getOp() == null) {
                // 空body / 浏览器直接GET / 非法JSON 直接返回 400 避免后续 NPE
                status = 400;
                resp = "{}";
            } else if (pack.getOp() == 13) {
                resp = auth(body, pack, exchange);
            } else {
                try {
                    List<String> sigs = exchange.getRequestHeaders().get("X-Signature-Ed25519");
                    List<String> ts = exchange.getRequestHeaders().get("X-Signature-Timestamp");
                    if (sigs != null && !sigs.isEmpty() && ts != null && !ts.isEmpty()) {
                        String sig = sigs.get(0);
                        String timestamp = ts.get(0);
                        KeyPair keyPair = getKeyPair();
                        boolean isValid = verifySignature(sig, timestamp, body.getBytes(StandardCharsets.UTF_8), keyPair.getPublic().getEncoded());
                        resp = String.valueOf(isValid);
                    }
                } catch (Exception e) {
                    logger.error("验证签名报错(不影响接收和发送)：\n" + getExceptionLine(e));
                }
                final Pack fpack = pack;
                wssWorker.getOnPackReceives().stream().filter(o -> !(o instanceof AuthAndHeartbeat))
                        .forEach(p -> p.onReceive(fpack));
            }
        } catch (Exception e) {
            logger.error("WebHook服务处理请求异常：\n" + getExceptionLine(e));
            status = 500;
            resp = "{}";
        } finally {
            logger.log("WebHook服务响应: " + resp);
            writeResponse(exchange, status, resp);
        }
    }

    /**
     * 统一写响应 使用 byte[] 长度 并在 finally 中无条件 close(exchange)
     */
    private void writeResponse(HttpExchange exchange, int status, String resp) {
        if (resp == null) resp = "{}";
        try {
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            logger.error("WebHook服务写响应失败：\n" + getExceptionLine(e));
        } finally {
            exchange.close();
        }
    }

    private KeyPair getKeyPair() {
        return generateEd25519KeyPair(prepareSeed(config.getSecret()).getBytes(StandardCharsets.UTF_8));
    }

    private String auth(String body, Pack pack, HttpExchange exchange) {
        try {
            logger.info("验证有效性...");
            KeyPair keyPair = getKeyPair();
            Map<String, String> packD = (Map<String, String>) pack.getD();
            String plain_token = packD.get("plain_token");
            String event_ts = packD.get("event_ts");
            byte[] message = (event_ts + plain_token).getBytes(StandardCharsets.UTF_8);
            byte[] signature = signMessage(keyPair.getPrivate(), message);
            return String.format("{\"plain_token\": \"%s\", \"signature\": \"%s\"}", plain_token, bytesToHex(signature));
        } catch (Exception e) {
            logger.error("验证失败：\n" + getExceptionLine(e));
            return "{}";
        }
    }


    public boolean verifySignature(String signatureHex, String timestamp, byte[] httpBody, byte[] publicKeyBytes) {
        try {
            byte[] sig = hexDecode(signatureHex);
            if (sig.length != 64 || (sig[63] & 0xE0) != 0) {
                logger.waring("Invalid signature format");
                return false;
            }
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            msg.write(timestamp.getBytes(StandardCharsets.UTF_8));
            msg.write(httpBody);
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(ed25519PublicKey(publicKeyBytes));
            verifier.update(msg.toByteArray());
            return verifier.verify(sig);
        } catch (Exception e) {
            logger.error("验证签名报错：\n" + getExceptionLine(e));
            return false;
        }
    }

    private String prepareSeed(String seed) {
        if (seed.length() < 32) seed = repeat(seed, 2);
        return seed.substring(0, 32);
    }

    /**
     * Deterministic Ed25519 key pair from a 32-byte seed, using the JDK's own EdDSA (Java 15+).
     * The JDK generator takes the private key straight from its random source, so a source that
     * only yields the seed reproduces the RFC 8032 key for that seed.
     */
    static KeyPair generateEd25519KeyPair(byte[] seed) {
        if (seed.length != 32) throw new IllegalArgumentException("Ed25519 seed must be 32 bytes");
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            generator.initialize(NamedParameterSpec.ED25519, new SeedRandom(seed));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is unavailable", e);
        }
    }

    static byte[] signMessage(PrivateKey privateKey, byte[] message) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(message);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /** Accepts a raw 32-byte public key or an X.509-encoded one. */
    static PublicKey ed25519PublicKey(byte[] publicKeyBytes) throws GeneralSecurityException {
        byte[] encoded = publicKeyBytes;
        if (publicKeyBytes.length == 32) {
            encoded = new byte[ED25519_X509_PREFIX.length + 32];
            System.arraycopy(ED25519_X509_PREFIX, 0, encoded, 0, ED25519_X509_PREFIX.length);
            System.arraycopy(publicKeyBytes, 0, encoded, ED25519_X509_PREFIX.length, 32);
        }
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }

    static byte[] hexDecode(String hex) {
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(hex.charAt(2 * i), 16);
            int low = Character.digit(hex.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("invalid hex");
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    /** Yields the fixed seed exactly once; any further request is a programming error. */
    private static final class SeedRandom extends SecureRandom {
        private byte[] seed;

        SeedRandom(byte[] seed) {
            this.seed = seed.clone();
        }

        @Override
        public void nextBytes(byte[] bytes) {
            if (seed == null || bytes.length != seed.length) throw new IllegalStateException("unexpected random request");
            System.arraycopy(seed, 0, bytes, 0, bytes.length);
            seed = null;
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static String repeat(String str, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) sb.append(str);
        return sb.toString();
    }
}
