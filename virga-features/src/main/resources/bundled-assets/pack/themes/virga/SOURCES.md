# Virga theme sources

## Interface artwork

The inventory and Ender Chest backgrounds are Virga artwork drawn entirely in code
(light aurora palette and frosted glass, no mascots).
No external image is used. Slot rectangles follow `layout.yml` and `theme.yml` exactly:

- `background.png`: 1359x1017, SHA-256 `E9E04C9B312F5FB78ADC4456421279E285E2F9C4BCF9A819B54B0CFB69C1000D`.
- `ender-chest-background.png`: 1620x694, SHA-256 `964A377C4BDF5813E903A781A03DC23851E4D1BBD390E5B87ED55D394AF737F3`.

The asset manifest is rebuilt whenever either image changes.

## Faithful item artwork

Item, block, shield, chest, shulker box, potion and related inventory textures use the
user-supplied Faithful 32x 26.3 pack. The bundled generated icon cache was produced offline
from the official Minecraft Java Edition 26.3 client with those textures. This is an art-source
version, not a minimum server version: 1.21.11 servers use the same pack and ignore newer item
IDs that cannot occur there. The runtime never opens the source client JAR or resource pack.

- Project: Faithful 32x
- Website: https://faithfulpack.net/
- License: `LICENSE.txt` in this directory and `THIRD_PARTY_LICENSES/Faithful-LICENSE.txt`
- Faithful package SHA-256: `596035002E8C369CA673B5B922CD532A38A2AAABE80F317F5AC4D5C7680C7BF2`
- Minecraft 26.3 client SHA-256: `4508D006323F24FA02876310C192D739AF56516EB259000AC50F0909A68C9A2D`
- Static icon coverage: 1582 of 1658 item definitions. Dynamic and special-renderer items
  continue through the existing component-aware or explicit-icon runtime paths.
- The 17 closed shulker-box icons were re-baked with overlapping face edges to eliminate the
  transparent diagonal seam visible where the lid meets the side.

The Faithful resources remain subject to the Faithful License and are not relicensed under MIT.
The theme is not an official Faithful product or endorsement.

### Component-aware Faithful add-ons

The component-aware enchanted-book and suspicious-stew icons are copied unmodified from two
Faithful 32x add-on packs supplied for the Virga inventory renderer. Only textures referenced
by each pack's item-model selector are bundled; their JSON selectors are implemented as small,
precomputed server-side lookups so the plugin does not parse resource packs at runtime.

- `Vanilla CIT Enchanted Books.zip`, SHA-256
  `53EBD41FD5E7A3F8D204C9B5C8E27B2BB8594D3959985DE81A62740B99C8F6E0`.
  Artwork credits: Zelario12, Fireon12064, and BellPepperBrian.
- `Unsuspicious Stew Faithful 32x.zip`, SHA-256
  `C1D573FF1184A1235011A32F8AC6E4B0B762F2F80142046C6B4890BBEF8FDF40`.
  Pack credits: Klona and Ethanwu0608.

Both add-ons identify themselves as Faithful 32x add-ons. Their artwork remains subject to the
Faithful License cited above. Unsupported, multi-component, or non-exact values intentionally
fall back to the normal Faithful icon instead of guessing a misleading variant.

## Missing-texture fallback

`fallback/unknown.png` is a transparent 64x64 three-dimensional inventory icon pre-rendered
with the same dependency-free block-model rasterizer used by the bundled vanilla item cache. All six cube faces use Minecraft Java Edition's generated
`minecraft:missingno` texture: a 16x16 black (`#000000`) and magenta (`#F800F8`) diagonal
four-quadrant pattern. The model is baked before packaging; the server only decodes and caches the
finished PNG when an unknown item is actually rendered.

- Specification: https://zh.minecraft.wiki/w/%E7%BA%B9%E7%90%86#%E6%97%A0%E6%95%88%E7%BA%B9%E7%90%86
- Output SHA-256: `6E2702803C29B327D0C844D201DE91F13FFF572176967587B6A52BB87C6AA985`.

## RC21 fixed special-item icons

Items whose client model requires live state or a special entity renderer use deterministic
representative icons so an inventory query never displays an unknown-question-mark tile:

- Source: official `Faithful-Resource-Pack/Faithful-32x-Java` repository, branch `1.21.11`,
  commit `f2a1de2113920e4daf9b24c5465dd66cc61ad064`.
- `clock`, `compass`, and `recovery_compass` copy Faithful frame `00` unmodified.
- Creeper, skeleton, wither-skeleton and zombie textures use the standard skull cube without a
  false player-hat layer. Dragon and piglin use their separate Minecraft 1.21.11 model cuboids
  (dragon jaw/horns/nostrils and piglin snout/tusks/ears), rendered as transparent 64x64 GUI
  objects instead of flat face tiles or a shared player-head approximation. All head objects use
  Minecraft's GUI orientation (front plane on the right) and the corresponding side-face UVs;
  completed icons are never reflected because that would move rear texture edges onto the face.
- A player head with no captured texture uses Faithful's wide-arm Steve texture rendered with
  the same object geometry. Embedded textures and owner-only UUID/name profiles are prepared
  asynchronously (the latter through the public Mojang profile API); the accepted textures for a
  request finish before that image is rendered. They remain the player's own identity texture
  rather than pack art.

The exact input and output SHA-256 values are recorded in `ASSET_MANIFEST.tsv`. Faithful and Minecraft-related
rights remain with their respective rights holders.

## Conduit and copper golem statue icons

The conduit shell and copper golem statue icons are pre-rendered at the final 64x64 slot size
from the model geometry and textures in the pinned, legally obtained Minecraft Java Edition
26.1.2 client JAR. The conduit uses the inventory shell only. Copper statues cover all four
oxidation textures, waxed aliases, and the `standing`, `sitting`, `running`, and `star` block-state
poses. Runtime rendering only selects and lazily decodes the finished PNG, so no model baking is
performed on the server.

Exact input and output hashes are recorded in `ASSET_MANIFEST.tsv`. Minecraft-related rights remain
with Mojang Studios and their respective rights holders.
