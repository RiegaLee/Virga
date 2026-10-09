package cn.huohuas001.virga.features.inventory.armor;

import cn.huohuas001.virga.features.inventory.asset.VanillaImportedAssetProvider;
import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ArmorItemIconRendererTest {
    private static final Path PACK = Paths.get(
        "build", "resources", "main", "bundled-assets", "pack"
    );

    @Test
    void keepsArmorTrimOnSlotIcon(@TempDir Path cache) {
        VanillaImportedAssetProvider vanilla = VanillaImportedAssetProvider.open(PACK.resolve("vanilla"));
        EquipmentAssetResolver equipment = new EquipmentAssetResolver(PACK.resolve("armor"));
        ArmorItemIconRenderer renderer = new ArmorItemIconRenderer(equipment, cache);

        ItemSnapshot plain = item(null, null);
        ItemSnapshot trimmed = item("minecraft:spire", "minecraft:gold");
        BufferedImage base = vanilla.resolve("minecraft:netherite_chestplate")
            .orElseThrow(AssertionError::new);
        BufferedImage plainIcon = renderer.render(plain, base);
        BufferedImage trimmedIcon = renderer.render(trimmed, base);

        assertEquals(base, plainIcon);
        assertNotEquals(pixelHash(plainIcon), pixelHash(trimmedIcon));
    }

    private static ItemSnapshot item(String pattern, String material) {
        ArmorVisualDescriptor armor = new ArmorVisualDescriptor(
            ArmorVisualDescriptor.Slot.CHEST,
            "minecraft:netherite_chestplate",
            "minecraft:netherite",
            pattern,
            material,
            null,
            false
        );
        return new ItemSnapshot(
            "minecraft:netherite_chestplate", 1, 0, 592,
            null, null, false, null, armor
        );
    }

    private static long pixelHash(BufferedImage image) {
        long value = 1125899906842597L;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) value = value * 31 + image.getRGB(x, y);
        }
        return value;
    }
}
