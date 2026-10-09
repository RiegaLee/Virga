package cn.huohuas001.virga.features.inventory.head;

import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict parser for already-present Minecraft profile texture URLs. */
public final class PlayerHeadTextureUrl {
    private static final Pattern TEXTURE_PATH = Pattern.compile("/texture/([A-Fa-f0-9]{32,128})");
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{32,128}");

    private PlayerHeadTextureUrl() { }

    /** No non-HTTPS schemes, credentials, ports, query strings, fragments or sibling/sub-domains are accepted. */
    public static Optional<String> textureHash(URL value) {
        if (value == null) return Optional.empty();
        try {
            URI uri = value.toURI();
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme)) return Optional.empty();
            if (!"textures.minecraft.net".equalsIgnoreCase(uri.getHost())) return Optional.empty();
            if (uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                return Optional.empty();
            }
            Matcher matcher = TEXTURE_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
            if (!matcher.matches()) return Optional.empty();
            return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    /** The only URI ever used for a player-head download. */
    public static URI secureTextureUri(String hash) {
        String normalized = hash == null ? "" : hash.trim().toLowerCase(Locale.ROOT);
        if (!HASH.matcher(normalized).matches()) throw new IllegalArgumentException("Invalid player-head texture hash");
        return URI.create("https://textures.minecraft.net/texture/" + normalized);
    }
}
