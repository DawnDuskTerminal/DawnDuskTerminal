# 群系占位对照

## 约定：占位不改注册名

群系副本的 ID 始终等于它所顶替的原版群系。
若需了解某一格原本是什么群系，查看 ID 即可得知；
玩家看到的名字由语言文件决定，待定稿之后再统一修改。

因此移植暮色森林群系时，实际做法是在 `birch_forest.json` 中装入 `firefly_forest` 的内容，
而不是新建 `firefly_forest.json`。
只有完全没有同名原版格子的自建群系才使用新 ID：
`floatrees`、`white_land`、`blue_desert`、`polar_dawn` 与 `polar_dusk`。

## 选格

普通温区使用原版主世界气候表，选格后按同名路径映射到模组群系。
沙漠与恶地的选格参数沿用原版。
温度高于 1 或低于 −1 时，群系源使用相应的极温表。
极温表保留原版河海的非温度参数，同时在陆地上配置自建群系。

| 温区 | 模组群系 | 原版气候模板 |
|---|---|---|
| 高于 1 | 白地 | 平原 |
| 高于 1 | 极昼平原 | 恶地 |
| 低于 −1 | 极夜平原 | 恶地 |
| 低于 −1 | 蓝沙沙漠 | 沙漠 |

详见 [温度映射与极温气候表](worldgen.md#温度映射与极温气候表)。

## 装了暮色森林内容的副本

这些副本的来源脚本是 `tools/port_tf_biomes.py`。
该脚本保留副本原有的 `carvers`，把 `minecraft:*` 放置特征改指为 `dawnduskterminal:*`，而 `twilightforest:*` 则直接引用。

| 原版 ID | 装的暮色群系 | 当前显示名 | 设定稿名 |
|---|---|---|---|
| `dark_forest` | `dark_forest_center` | 黑森林 | 黑森林腹地 |
| `birch_forest` | `firefly_forest` | 萤火虫森林 | 萤火虫森林 |
| `flower_forest` | `dense_forest` | 繁花森林 | 繁密暮色森林 |
| `jungle` | `enchanted_forest` | 丛林 | 魔法森林 |
| `swamp` | `fire_swamp` | 沼泽 | 火焰沼泽 |
| `plains` | `clearing` | 平原 | 暮光平原 |
| `savanna` | `oak_savannah` | 热带草原 | 橡木热带草原 |
| `snowy_plains` | `glacier` | 雪原 | 暮色冰川 |
| `windswept_hills` | `highlands` | 风袭丘陵 | 荆棘高地 |
| `forest` | `twilightforest:forest` | 森林 | 暮色森林 |

表格最后一行的 `forest` 不在该脚本的对照表之内，是手工移植的结果。
设定稿一列中的名字都还没有写入语言文件。

## 已换内容但未安装暮色群系

| 原版 ID | 内容 | 显示名 |
|---|---|---|
| `old_growth_birch_forest` | 萤火虫森林那份移植再染红 | 红草树林 |

染色方式是把 `grass_color` 与 `foliage_color` 取红色，并把 `temperature` 提到 0.8。

## 自建群系

这些群系没有同名的原版格子，只能使用新 ID。

| 模组 ID | 显示名 | 位置 | 说明 |
|---|---|---|---|
| `floatrees` | 天门 | 全维度 | 不经过替换表，采样深度低于 -1.6 时直接返回，早于 delegate |
| `white_land` | 白地 | 高温，平原气候点 | 裸地，地表 `siltstone`，恶魂支线 T2 |
| `blue_desert` | 蓝色沙漠 | 低温，沙漠气候点 | 蓝沙地，地表 `road_sand`；不铺苍穹霜片；长仙灵石竹 |
| `polar_dawn` | 极昼平原 | 高温，恶地气候点 | 占位，内容依照白地 |
| `polar_dusk` | 极夜平原 | 低温，恶地气候点 | 占位，内容依照白地 |

上述自建群系由仓库直接维护。
重新运行世界生成移植脚本后，应保留极温群系的调色板标签和维度现有地表规则。

## 换皮

其余副本沿用同名原版群系的内容，并更换地表方块与矿石特征。

- 沙漠 `desert`
- 恶地族 `badlands` `eroded_badlands` `wooded_badlands`
- 雪地族 `snowy_beach` `snowy_slopes` `snowy_taiga` `ice_spikes` `frozen_peaks` `jagged_peaks` `grove`
- 海洋族：`ocean`、`deep_ocean`、`cold_ocean`、`deep_cold_ocean`、`lukewarm_ocean`、
  `deep_lukewarm_ocean`、`frozen_ocean`、`deep_frozen_ocean`、`warm_ocean`
- 河流与岸 `river` `frozen_river` `beach` `stony_shore`
- 其余：`sunflower_plains`、`meadow`、`cherry_grove`、`mushroom_fields`、`stony_peaks`、
  `windswept_forest`、`windswept_gravelly_hills`、`windswept_savanna`、`bamboo_jungle`、`sparse_jungle`、
  `mangrove_swamp`、`taiga`、`old_growth_pine_taiga`、`old_growth_spruce_taiga`、`savanna_plateau`、
  `deep_dark`、`dripstone_caves`、`lush_caves`

蓝沙沙漠保留独立群系 ID，并沿用低温沙漠模板。
其生态配置移除了仙人掌、枯灌木与沙漠水井。

## 尚未落地的名字

以下名称在语言文件中已有对应的键，但 `worldgen/biome/` 中尚无同名的群系文件，因此当前没有任何气候格子会选中它们：

- `day`：昼区平原。
- `normal`：晨昏平原。
- `night`：夜区平原。
- `refusal_ocean`：拒绝之海。
- `dawn_zone`：晨区。
- `dusk_zone`：昏区。
- `solidcanopy_ocean`：凝固苍穹的海洋。
- `earth_light`：地光。
- `sky_island`：空岛。

`polar_dawn`（极昼平原）与 `polar_dusk`（极夜平原）虽然同样在语言文件里设了键，但已具备对应的群系文件，
属于自建群系，见前文的自建群系表。

## 附录：原版方块到模组方块

以下为地表调色板的命名对照，所列方块均定义在 `ModTerrainBlocks` 中：

| 原版 | 模组 | id |
|---|---|---|
| 草方块 | 霞草 | `rosy_grass` |
| 泥土 | 霞土 | `rosy_dirt` |
| 石头 | 苍穹石 | `canopy_stone` |
| 花岗岩 | 赭石 | `ochre_block` |
| 闪长岩 | 绿片岩 | `greenschist` |
| 安山岩 | 榴辉岩 | `eclogite` |
| 深板岩 | 紫苏岩 | `charnockite` |
| 凝灰岩 | 暮光石 | `twilight_stone` |
| 沙子 | 路之沙 | `road_sand` |
| 红沙 | 不醒沙 | `unconscious_sand` |
| 砂岩 | 粉砂岩 | `siltstone` |
| 水 | 不稳定液体 | `portal_fluid` |
| 岩浆 | 热情的暮光 | `enthu_fluid` |
| 冰 | 凝固的苍穹 | `frozen_canopy` |
| 雪 | 苍穹霜 | `canopy_frost` |

苍穹霜片（`canopy_frost`，最多 8 层的雪层）的掉落：

| 手里的工具 | 掉落 |
|---|---|
| 精准采集 | 自身，几层掉几个 |
| 铲子（没有精准采集） | 苍穹霜球 `canopy_frost_ball`，几层掉几个 |
| 其它（含空手、爆炸、活塞等非玩家来源） | 什么都不掉 |

改这张表之前它没有任何工具条件，于是被非玩家来源破坏时也会掉出霜层本身。

