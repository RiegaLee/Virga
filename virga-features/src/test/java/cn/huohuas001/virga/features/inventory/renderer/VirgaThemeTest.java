package cn.huohuas001.virga.features.inventory.renderer;

import cn.huohuas001.virga.features.inventory.armor.ArmorItemIconRenderer;
import cn.huohuas001.virga.features.inventory.armor.ArmorEquipmentSet;
import cn.huohuas001.virga.features.inventory.armor.ArmorVisualDescriptor;
import cn.huohuas001.virga.features.inventory.armor.EquipmentAssetResolver;
import cn.huohuas001.virga.features.inventory.asset.VanillaImportedAssetProvider;
import cn.huohuas001.virga.features.inventory.datasource.MockInventoryDataSource;
import cn.huohuas001.virga.features.inventory.model.InventorySlot;
import cn.huohuas001.virga.features.inventory.model.InventorySnapshot;
import cn.huohuas001.virga.features.inventory.model.ItemSnapshot;
import cn.huohuas001.virga.features.inventory.model.SlotType;
import cn.huohuas001.virga.features.inventory.skin.DefaultPlayerSkinProvider;
import cn.huohuas001.virga.features.inventory.skin.PlayerModelRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirgaThemeTest {
    private static final Path PACK =
        Paths.get("build", "resources", "main", "bundled-assets", "pack");
    private static final Path THEME = PACK.resolve("themes/virga");

    @Test
    void componentAwareItemHintsResolveToLazyFaithfulOverrides() {
        Theme theme = ThemeLoader.load(THEME, VanillaImportedAssetProvider.open(PACK.resolve("vanilla")));
        ItemSnapshot book = new ItemSnapshot(
            "minecraft:enchanted_book", 1, 0, 0, null, null, true,
            "variants/minecraft/enchanted_book/sharpness5"
        );
        ItemSnapshot stew = new ItemSnapshot(
            "minecraft:suspicious_stew", 1, 0, 0, null, null, false,
            "variants/minecraft/suspicious_stew/suspicious_stew_poison"
        );

        TextureResolver.ResolvedTexture bookTexture = theme.getTextures().resolve(book);
        TextureResolver.ResolvedTexture stewTexture = theme.getTextures().resolve(stew);
        assertEquals(TextureResolver.Source.EXPLICIT_OVERRIDE, bookTexture.getSource());
        assertEquals(TextureResolver.Source.EXPLICIT_OVERRIDE, stewTexture.getSource());
        assertEquals(32, bookTexture.getImage().getWidth());
        assertEquals(32, bookTexture.getImage().getHeight());
        assertEquals(32, stewTexture.getImage().getWidth());
        assertEquals(32, stewTexture.getImage().getHeight());
    }

    @Test
    void unknownFallbackUsesTransparentThreeDimensionalMissingTextureCube() {
        Theme theme = ThemeLoader.load(THEME, VanillaImportedAssetProvider.open(PACK.resolve("vanilla")));
        TextureResolver.ResolvedTexture texture = theme.getTextures().resolve(
            ItemSnapshot.basic("minecraft:not_a_real_item", 1)
        );
        BufferedImage image = texture.getImage();

        assertEquals(TextureResolver.Source.UNKNOWN, texture.getSource());
        assertTrue(texture.isFallback());
        assertEquals(64, image.getWidth());
        assertEquals(64, image.getHeight());
        assertEquals(0, image.getRGB(0, 0) >>> 24);
        assertEquals(0, image.getRGB(63, 63) >>> 24);

        int visible = 0;
        java.util.Set<Integer> opaqueColors = new java.util.HashSet<Integer>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int color = image.getRGB(x, y);
                if ((color >>> 24) == 0) continue;
                visible++;
                opaqueColors.add(Integer.valueOf(color));
            }
        }
        assertTrue(visible > 2000 && visible < 2600, "fallback must retain a bounded cube silhouette");
        assertTrue(opaqueColors.size() >= 4, "three visible faces must retain Minecraft-style shading");
    }

    @Test
    void rc21SpecialItemsUseRecognizableFixedIconsInsteadOfUnknownFallback() {
        Theme theme = ThemeLoader.load(THEME, VanillaImportedAssetProvider.open(PACK.resolve("vanilla")));
        String[] materials = {
            "minecraft:clock", "minecraft:compass", "minecraft:recovery_compass",
            "minecraft:player_head", "minecraft:creeper_head", "minecraft:dragon_head",
            "minecraft:piglin_head", "minecraft:skeleton_skull",
            "minecraft:wither_skeleton_skull", "minecraft:zombie_head"
        };
        for (String material : materials) {
            TextureResolver.ResolvedTexture texture = theme.getTextures().resolve(ItemSnapshot.basic(material, 1));
            assertEquals(TextureResolver.Source.GENERATED_SPECIAL_STATIC, texture.getSource(), material);
            assertFalse(texture.isFallback(), material);
            assertTrue(texture.getImage().getWidth() >= 16, material);
            assertTrue(texture.getImage().getHeight() >= 16, material);
        }
    }

    @Test
    void conduitAndEveryCopperGolemStatuePoseUsePrebuiltSpecialIcons() {
        Theme theme = ThemeLoader.load(THEME, VanillaImportedAssetProvider.open(PACK.resolve("vanilla")));
        TextureResolver.ResolvedTexture conduit = theme.getTextures().resolve(
            ItemSnapshot.basic("minecraft:conduit", 1)
        );
        assertEquals(TextureResolver.Source.GENERATED_SPECIAL_STATIC, conduit.getSource());
        assertFalse(conduit.isFallback());
        assertEquals(64, conduit.getImage().getWidth());
        assertEquals(64, conduit.getImage().getHeight());

        String[] materials = {
            "copper_golem_statue", "exposed_copper_golem_statue",
            "weathered_copper_golem_statue", "oxidized_copper_golem_statue",
            "waxed_copper_golem_statue", "waxed_exposed_copper_golem_statue",
            "waxed_weathered_copper_golem_statue", "waxed_oxidized_copper_golem_statue"
        };
        String[] poses = {"standing", "sitting", "running", "star"};
        for (String material : materials) {
            TextureResolver.ResolvedTexture defaultTexture = theme.getTextures().resolve(
                ItemSnapshot.basic("minecraft:" + material, 1)
            );
            assertEquals(TextureResolver.Source.GENERATED_SPECIAL_STATIC, defaultTexture.getSource(), material);
            assertFalse(defaultTexture.isFallback(), material);
            for (String pose : poses) {
                ItemSnapshot statue = new ItemSnapshot(
                    "minecraft:" + material, 1, 0, 0, null, null, false,
                    "minecraft/" + material + "/" + pose
                );
                TextureResolver.ResolvedTexture texture = theme.getTextures().resolve(statue);
                assertEquals(TextureResolver.Source.GENERATED_SPECIAL_STATIC, texture.getSource(), material + '/' + pose);
                assertFalse(texture.isFallback(), material + '/' + pose);
                assertEquals(64, texture.getImage().getWidth(), material + '/' + pose);
                assertEquals(64, texture.getImage().getHeight(), material + '/' + pose);
            }
        }
    }

    @Test
    void rc21HeadIconsAreTransparentInventoryObjectsInsteadOfFlatFaces() {
        Theme theme = ThemeLoader.load(THEME, VanillaImportedAssetProvider.open(PACK.resolve("vanilla")));
        String[] heads = {
            "minecraft:player_head", "minecraft:creeper_head", "minecraft:dragon_head",
            "minecraft:piglin_head", "minecraft:skeleton_skull",
            "minecraft:wither_skeleton_skull", "minecraft:zombie_head"
        };
        for (String material : heads) {
            BufferedImage image = theme.getTextures().resolve(ItemSnapshot.basic(material, 1)).getImage();
            assertEquals(64, image.getWidth(), material);
            assertEquals(64, image.getHeight(), material);
            assertEquals(0, image.getRGB(0, 0) >>> 24, material + " top-left corner");
            assertEquals(0, image.getRGB(63, 63) >>> 24, material + " bottom-right corner");
            int visible = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) >>> 24) != 0) visible++;
                }
            }
            assertTrue(visible > 500, material + " must contain a recognizable head object");
            assertTrue(visible < 3000, material + " must preserve transparent object contours");
        }
    }

    @Test
    void loadsAndRendersTheSingleVirgaTheme(@TempDir Path cache) throws Exception {
        Theme theme = ThemeLoader.load(THEME, VanillaImportedAssetProvider.open(PACK.resolve("vanilla")));
        EquipmentAssetResolver equipmentAssets = new EquipmentAssetResolver(PACK.resolve("armor"));
        theme.getTextures().setArmorItemRenderer(new ArmorItemIconRenderer(
            equipmentAssets, cache.resolve("armor-items")
        ));
        assertEquals("virga", theme.getId());
        assertFalse(theme.isDrawTitle());
        assertFalse(theme.isDrawSlotBackgrounds());
        assertFalse(theme.isDrawPlayerPreviewMatte());
        assertTrue(theme.isNearestNeighborTextures());
        assertEquals(26, Java2DInventoryRenderer.ITEM_COUNT_FONT_SIZE);
        assertEquals(26, EnderChestRenderer.ITEM_COUNT_FONT_SIZE);

        BufferedImage inventoryBackground = theme.getBackground();
        assertEquals(0, inventoryBackground.getRGB(0, 0) >>> 24);
        assertEquals(0, inventoryBackground.getRGB(inventoryBackground.getWidth() - 1, 0) >>> 24);

        Layout layout = theme.getLayout();
        assertEquals(1359, layout.getWidth());
        assertEquals(1017, layout.getHeight());
        assertEquals(new Rectangle(151, 485, 104, 104),
            layout.slotBounds(InventorySlot.empty(SlotType.STORAGE, 0)));
        assertEquals(new Rectangle(1103, 713, 104, 104),
            layout.slotBounds(InventorySlot.empty(SlotType.STORAGE, 26)));
        assertEquals(new Rectangle(151, 861, 104, 104),
            layout.slotBounds(InventorySlot.empty(SlotType.HOTBAR, 0)));
        assertEquals(new Rectangle(399, 143, 104, 104),
            layout.slotBounds(InventorySlot.empty(SlotType.ARMOR_HEAD, 0)));
        assertEquals(new Rectangle(210, 221, 104, 104),
            layout.slotBounds(InventorySlot.empty(SlotType.OFFHAND, 0)));

        EnderChestLayout ender = theme.getEnderChestLayout();
        assertEquals(1620, ender.getWidth());
        assertEquals(694, ender.getHeight());
        assertEquals(175, ender.getStartX());
        assertEquals(138, ender.getStartY());
        assertEquals(143, ender.getStepX());
        assertEquals(149, ender.getStepY());

        InventorySnapshot snapshot = visualSnapshot(
            new MockInventoryDataSource("virga-test").createSnapshot("Virga")
        );
        Rectangle playerArea = layout.getPlayerPreview();
        BufferedImage player = new PlayerModelRenderer(playerArea.width, playerArea.height)
            .render(
                new DefaultPlayerSkinProvider().getFallback(),
                ArmorEquipmentSet.from(snapshot),
                equipmentAssets
            );
        assertEquals(playerArea.width, player.getWidth());
        assertEquals(playerArea.height, player.getHeight());
        RenderResult inventoryResult = new Java2DInventoryRenderer(theme).render(snapshot, player);
        RenderResult offlineInventory = new Java2DInventoryRenderer(theme).render(
            snapshot, player, InventoryRenderMetadata.offline(snapshot.getCapturedAt())
        );
        assertArrayEquals(inventoryResult.getBytes(), offlineInventory.getBytes(),
            "offline inventory must not add a badge or change the model/item layout");
        BufferedImage inventory = ImageIO.read(new ByteArrayInputStream(inventoryResult.getBytes()));
        assertEquals(1359, inventory.getWidth());
        assertEquals(1017, inventory.getHeight());

        EnderChestRenderer renderer = new EnderChestRenderer(
            theme, THEME.resolve("ender-chest-background.png")
        );
        BufferedImage enderBackground = ImageIO.read(
            THEME.resolve("ender-chest-background.png").toFile()
        );
        assertEquals(0, enderBackground.getRGB(0, 0) >>> 24);
        assertEquals(0, enderBackground.getRGB(enderBackground.getWidth() - 1, 0) >>> 24);
        RenderResult realtime = renderer.render(
            snapshot, null, InventoryRenderMetadata.realtime(snapshot.getCapturedAt())
        );
        RenderResult offline = renderer.render(
            snapshot, null, InventoryRenderMetadata.offline(snapshot.getCapturedAt())
        );
        assertEquals(1620, realtime.getWidth());
        assertEquals(694, realtime.getHeight());
        assertArrayEquals(realtime.getBytes(), offline.getBytes(),
            "offline ender chest must use the same badge-free rendering");

        Path output = Paths.get("build", "rendered-test-output");
        Files.createDirectories(output);
        Files.write(output.resolve("virga-inventory.png"), inventoryResult.getBytes());
        Files.write(output.resolve("virga-inventory-offline.png"), offlineInventory.getBytes());
        Files.write(output.resolve("virga-ender-chest.png"), realtime.getBytes());
        Files.write(output.resolve("virga-ender-chest-offline.png"), offline.getBytes());
    }

    private static InventorySnapshot visualSnapshot(InventorySnapshot source) {
        List<InventorySlot> storage = new ArrayList<InventorySlot>(source.getStorage());
        storage.set(0, InventorySlot.of(
            SlotType.STORAGE, 0,
            new ItemSnapshot(
                "minecraft:enchanted_book", 1, 0, 0, null, null, true,
                "variants/minecraft/enchanted_book/sharpness5"
            )
        ));
        storage.set(1, InventorySlot.of(
            SlotType.STORAGE, 1,
            new ItemSnapshot(
                "minecraft:suspicious_stew", 1, 0, 0, null, null, false,
                "variants/minecraft/suspicious_stew/suspicious_stew_poison"
            )
        ));
        storage.set(2, InventorySlot.of(
            SlotType.STORAGE, 2, ItemSnapshot.basic("minecraft:not_a_real_item", 1)
        ));
        storage.set(3, InventorySlot.of(
            SlotType.STORAGE, 3, ItemSnapshot.basic("minecraft:conduit", 1)
        ));
        String[] poses = {"standing", "sitting", "running", "star"};
        for (int index = 0; index < poses.length; index++) {
            storage.set(4 + index, InventorySlot.of(
                SlotType.STORAGE, 4 + index,
                variantItem("copper_golem_statue", poses[index])
            ));
        }
        String[] copperMaterials = {
            "exposed_copper_golem_statue", "weathered_copper_golem_statue",
            "oxidized_copper_golem_statue", "waxed_copper_golem_statue",
            "waxed_exposed_copper_golem_statue", "waxed_weathered_copper_golem_statue",
            "waxed_oxidized_copper_golem_statue"
        };
        for (int index = 0; index < copperMaterials.length; index++) {
            storage.set(8 + index, InventorySlot.of(
                SlotType.STORAGE, 8 + index,
                variantItem(copperMaterials[index], "standing")
            ));
        }
        List<InventorySlot> hotbar = new ArrayList<InventorySlot>(source.getHotbar());
        hotbar.set(4, InventorySlot.of(
            SlotType.HOTBAR, 4, ItemSnapshot.basic("minecraft:honey_block", 64)
        ));
        List<InventorySlot> armor = new ArrayList<InventorySlot>(source.getArmor());
        for (int index = 0; index < armor.size(); index++) {
            SlotType slotType = armor.get(index).getSlotType();
            ArmorVisualDescriptor.Slot armorSlot;
            String material;
            if (slotType == SlotType.ARMOR_HEAD) {
                armorSlot = ArmorVisualDescriptor.Slot.HEAD;
                material = "minecraft:netherite_helmet";
            } else if (slotType == SlotType.ARMOR_CHEST) {
                armorSlot = ArmorVisualDescriptor.Slot.CHEST;
                material = "minecraft:netherite_chestplate";
            } else if (slotType == SlotType.ARMOR_LEGS) {
                armorSlot = ArmorVisualDescriptor.Slot.LEGS;
                material = "minecraft:netherite_leggings";
            } else if (slotType == SlotType.ARMOR_FEET) {
                armorSlot = ArmorVisualDescriptor.Slot.FEET;
                material = "minecraft:netherite_boots";
            } else {
                continue;
            }
            boolean chest = armorSlot == ArmorVisualDescriptor.Slot.CHEST;
            ArmorVisualDescriptor enchantedArmor = new ArmorVisualDescriptor(
                armorSlot,
                material,
                "minecraft:netherite",
                chest ? "minecraft:spire" : null,
                chest ? "minecraft:gold" : null,
                null,
                true
            );
            armor.set(index, InventorySlot.of(
                slotType,
                0,
                new ItemSnapshot(
                    material, 1, 0, 592,
                    null, null, true, null, enchantedArmor
                )
            ));
        }
        return new InventorySnapshot(
            source.getSchemaVersion(), source.getPlayerUuid(), source.getPlayerName(),
            source.getCapturedAt(), source.getSourceServer(), source.getContentRevision(),
            storage, hotbar, armor, source.getOffhand()
        );
    }

    private static ItemSnapshot variantItem(String material, String pose) {
        return new ItemSnapshot(
            "minecraft:" + material, 1, 0, 0, null, null, false,
            "minecraft/" + material + '/' + pose
        );
    }
}
