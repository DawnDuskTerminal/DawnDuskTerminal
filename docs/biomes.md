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

石岸 `stony_shore` 的地表规则沿用原版形态，只把砾石斑块换成橙大理石 `orange_marble`。
详见 [世界生成](worldgen.md) 的「石岸的砾石」一节。

`sunflower_plains` 的 `dawnduskterminal:patch_sunflower` 已改为指向本模组的 `dawnduskterminal:sunflower`，
不再生成原版向日葵。详见 [世界生成](worldgen.md#向日葵与月葵)。

## 刷怪

原版生物只保留蝙蝠。
其余 `minecraft:*` 的刷怪条目已从全部群系文件里删除，这一步由 `tools/port_tf_spawns.py` 完成。

暮色森林的自然生成按上一节的对应关系并入，脚本用的是同一张表：

| 模组群系 | 并入的暮色森林群系 |
|---|---|
| `forest` | `forest` |
| `birch_forest` | `firefly_forest` |
| `flower_forest` | `dense_forest` |
| `jungle` | `enchanted_forest` |
| `plains` | `clearing` |
| `savanna` | `oak_savannah` |
| `windswept_hills` | `highlands` |
| `dark_forest` | `dark_forest_center` |
| `swamp` | `swamp` 与 `fire_swamp` |
| `snowy_plains` | `glacier` 与 `snowy_forest` |

沼泽与冰川在项目里只有中心群系，因此中心与外围的刷怪表一并并入。
沼泽拿到外围的僵尸与蚊群，冰川拿到外围的冬狼与雪怪。

表里那些暮色森林的动物表自带 `minecraft:chicken` 与 `minecraft:wolf`，并入它们的群系会连这两条一起拿到。
不在这张表里的群系只剩蝙蝠，`deep_dark` 与 `wild_void` 什么都不刷。

### 共享表

两份表铺给不在映射表里的同类群系，属于共享表，不是映射：

| 表 | 来源 | 铺给 |
|---|---|---|
| 通用动物表 | 暮色森林 `forest` 那一份 | `taiga`、`old_growth_pine_taiga`、`old_growth_spruce_taiga`、`meadow`、`sunflower_plains`、`savanna_plateau`、`windswept_savanna`、`windswept_forest`、`sparse_jungle`、`bamboo_jungle` |
| 雪地一族 | `glacier` 与 `snowy_forest` 两份合起来 | `frozen_peaks`、`jagged_peaks` |

雪地一族只给温度 −0.7 的两个群系，这是本项目数据里最冷的一档。
其余雪地群系不铺：`snowy_taiga` 为 −0.5、`snowy_slopes` 为 −0.3、`grove` 为 −0.2、`ice_spikes` 为 0.0、`snowy_beach` 为 0.05。
`cherry_grove` 与 `windswept_gravelly_hills` 同样不铺，保持只有蝙蝠。

### 刷得出来的前提

条目写进群系只是一半，另一半是落点判定读的方块标签：

| 标签 | 需要的方块 | 管谁 |
|---|---|---|
| `#minecraft:animals_spawnable_on` | `rosy_grass` | 暮色森林的动物与鸡、狼 |
| `#twilightforest:penguins_spawnable_on` | `#minecraft:ice` 中的 `frozen_canopy` | 企鹅 |

`rosy_grass` 由 `data/minecraft/tags/block/animals_spawnable_on.json` 追加，
`frozen_canopy` 已在 `data/minecraft/tags/block/ice.json` 里。
缺任何一条，条目写对了也是一只不刷，`ChronoSpawnTests` 把这两条钉在测试里。

### 区块生成用哪一份表

群系表管的是运行时自然刷怪。
区块刚生成时落下的那一包动物走另一条路径，用哪一份表由 `NoiseBasedChunkGenerator#spawnOriginalMobs` 决定。

原版在那一处问的是世界顶端那一格的群系（`getMaxBuildHeight() - 1`，本维度为 319），
依据是主世界顶端与地表同群系。
本维度不成立：顶端各列的采样深度都低于 −1.6，群系源直接返回 `floatrees`。
若照抄原版，河海、海岸与其余空表群系的区块在生成阶段会拿到天门那份动物表，
于是鹿、羊、兔落在河海群系的霞草地上。

落点判定只读方块标签与亮度，不复核落点那一格的群系，因此这一条从群系文件上看不出来。
`mixin/ChunkSpawnBiomeMixin` 把取样点改成该区块中心列的地表群系（`world/ChunkSpawnBiome`），其余照原版。
`gametest/ChunkSpawnBiomeTests` 钉住「顶端是天门、地表不是」这一对条件与取样点的换算。

### 虚像

`virtual_image` 铺满河海类群系，含全部温度档：
`river`、`frozen_river`、`ocean`、`deep_ocean`、`warm_ocean`、`lukewarm_ocean`、
`deep_lukewarm_ocean`、`cold_ocean`、`deep_cold_ocean`、`frozen_ocean`、`deep_frozen_ocean`。

它的落点判定比别的敌对生物多一条：脚下必须是 `rosy_grass`。
群系表只能表达「哪个群系、多少权重」，方块条件写在
`VirtualImageEntity#checkVirtualImageSpawnRules` 里。
低温的河海群系地表同样由霞草与霞土铺成，因此它们也在列表之内；
落点判定不读亮度，这只生物白天与夜里都刷。
条目、落点判定与数值由 `gametest/VirtualImageTests` 钉住。

运行时另有天气与群系的自定义规则，见 [雨天](weather.md#露天的自然生成被压掉)。

### 夜侧才刷怪

敌对生物的落点判定还要读亮度，本维度这一项由坐标决定：

- 天光上限决定画面亮度，默认昼侧 15、晨昏线 14、夜侧 12，动物与作物读的是它；
- 刷怪判定读「天光减去压暗量」，压暗量按坐标取值，昼侧 0、晨昏线 5、夜侧 11。

两者的差别来自 `fixed_time`：它锁住的是服务端的时刻，服务端算出的压暗量晴天恒为 0，
原版判定因此读到天光本身（12 至 15），高于维度类型给出的抽样上限 7，露天地表一只敌对生物也刷不出来。
`mixin/MonsterSpawnLightMixin` 把这一步的压暗量换成按坐标取的值：昼侧照旧不刷，夜侧约三分之一以外可以刷。
天光上限不动，所以画面亮度、作物生长与动物判定都不受影响。

`ChronoSpawnTests` 的 `theNightSideIsDarkEnoughForMonsters` 钉住昼侧不刷、夜侧能刷这两条，
`theSkyLightCapStillFeedsAnimalsAndCrops` 钉住天光上限必须留在 8 以上。

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

