package cn.huohuas001.virga.features.inventory.skin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Optional;

/** Verified vanilla Steve fallback extracted from the user's Minecraft 26.1.2 client. */
public final class DefaultPlayerSkinProvider implements PlayerSkinProvider {
    static final String RESOURCE_PATH = "/assets/virga/default-steve.png";
    static final String EXPECTED_SHA256 = "06628F9CEF88520DA742AB280A01677CE17C99717F964ED89ED791673121094C";

    private final PlayerSkin fallback = loadVanillaSteve();

    @Override
    public Optional<PlayerSkin> findSkin(PlayerIdentity player) {
        return Optional.of(fallback);
    }

    public PlayerSkin getFallback() { return fallback; }

    private static PlayerSkin loadVanillaSteve() {
        try (InputStream input = DefaultPlayerSkinProvider.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) throw new IllegalStateException("Missing bundled vanilla Steve skin: " + RESOURCE_PATH);
            byte[] bytes = readBytes(input);
            String digest = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!EXPECTED_SHA256.equals(digest)) {
                throw new IllegalStateException("Bundled vanilla Steve skin failed integrity verification");
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() != 64 || image.getHeight() != 64) {
                throw new IllegalStateException("Bundled vanilla Steve skin must be a 64x64 PNG");
            }
            return new PlayerSkin(image, digest, "MINECRAFT_CLIENT_26.1.2", false);
        } catch (IOException error) {
            throw new IllegalStateException("Could not load bundled vanilla Steve skin", error);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02X", value & 0xff));
        return result.toString();
    }
}
