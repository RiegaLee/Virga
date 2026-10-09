# Third-party notices

## Virga artwork

The aurora orb mark and status badges, the online-list, status-card, inventory and Ender Chest
background layers, the panel backdrop and the icons are drawn in code for this project;
no third-party artwork is involved.

## Faithful 32x

Virga-Inventory 使用 Faithful 32x 26.2 的物品、方块及部分实体贴图来生成背包图标。

- 项目：https://faithfulpack.net/
- 许可：`THIRD_PARTY_LICENSES/Faithful-LICENSE.txt`
- 主题来源记录：`bundled-assets/pack/themes/virga/SOURCES.md`

Faithful 素材继续受 Faithful License 约束，不因本项目代码采用 AGPL-3.0 而被重新许可。
本项目不是 Faithful 官方产品，也不代表 Faithful 官方认可。

附魔书和迷之炖菜的组件可视化使用两套 Faithful 32x 附加包中的未修改贴图：

- Vanilla CIT Enchanted Books：Zelario12、Fireon12064、BellPepperBrian。
- Unsuspicious Stew Faithful 32x：Klona、Ethanwu0608。

压缩包校验值、选择规则和更完整的来源记录见内置 Virga 主题的 `SOURCES.md`。

## Minecraft

内置模型缓存基于用户合法取得的 Minecraft Java Edition 26.1.2 客户端资源生成，并结合
Faithful 32x 26.2 贴图用于物品图标渲染；默认玩家外观直接使用同一客户端中的原版宽臂
Steve 皮肤。Minecraft 相关权利归 Mojang Studios 与其权利人所有。

RC21 的时钟、指南针、追溯指针固定代表帧，以及苦力怕、末影龙、猪灵、骷髅、凋零骷髅、
僵尸和通用 Steve 头颅图标，来自 Faithful 32x Java 官方仓库 `1.21.11` 分支提交
`f2a1de2113920e4daf9b24c5465dd66cc61ad064`。头颅保留 Faithful 像素并渲染成立体物品，
而非截取原版平面头像；输入与派生文件校验值记录于主题 `ASSET_MANIFEST.tsv`。

潮涌核心与铜傀儡雕像图标使用合法取得的 Minecraft Java Edition 26.1.2 客户端中的模型
尺寸及原版实体贴图，离线预生成到最终 64×64 尺寸。铜傀儡覆盖四种氧化程度、涂蜡版本与
站立、坐下、奔跑、星形四种方块状态；运行中的服务器只选择并缓存成品 PNG。

## ZXing

The QQ connect QR code drawn on in-game maps is encoded with ZXing core 3.5.3
(https://github.com/zxing/zxing), licensed under the Apache License 2.0
(https://www.apache.org/licenses/LICENSE-2.0). It is bundled relocated under
`cn.huohuas001.virga.libs.zxing`.
