package cn.huohuas001.virga.features.inventory.head;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlayerHeadTextureUrlTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void normalizesOnlyOfficialTexturePaths() throws Exception {
        assertEquals(HASH, PlayerHeadTextureUrl.textureHash(
            new URL("https://textures.minecraft.net/texture/" + HASH.toUpperCase())
        ).orElseThrow());
        assertEquals(URI.create("https://textures.minecraft.net/texture/" + HASH),
            PlayerHeadTextureUrl.secureTextureUri(HASH));
    }

    @Test
    void rejectsCredentialsPortsQueriesFragmentsAndOtherHosts() throws Exception {
        String[] invalid = {
            "https://example.com/texture/" + HASH,
            "https://evil.textures.minecraft.net/texture/" + HASH,
            "https://textures.minecraft.net.evil.test/texture/" + HASH,
            "https://user@textures.minecraft.net/texture/" + HASH,
            "https://textures.minecraft.net:443/texture/" + HASH,
            "https://textures.minecraft.net/texture/" + HASH + "?x=1",
            "https://textures.minecraft.net/texture/" + HASH + "#x",
            "https://textures.minecraft.net/other/" + HASH,
            "ftp://textures.minecraft.net/texture/" + HASH,
            "http://textures.minecraft.net/texture/" + HASH,
            "https://textures.minecraft.net/texture/not-hex"
        };
        for (String value : invalid) {
            assertFalse(PlayerHeadTextureUrl.textureHash(new URL(value)).isPresent(), value);
        }
    }
}
