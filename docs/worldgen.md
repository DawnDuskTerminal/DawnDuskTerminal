# 世界生成

维度的 id 为 `dawnduskterminal:chrono`，生成类型为 `minecraft:noise`。
群系源为 `dawnduskterminal:palette`，地形设置为 `dawnduskterminal:chrono`。

该维度的实现思路是：将原版主世界的世界生成整份移植进本模组的命名空间，再将其中的方块替换为本模组的调色板方块。
相关的数据文件均位于 `src/main/resources/data/dawnduskterminal/` 目录下。

## 维度类型

维度类型的定义写在文件 `dimension_type/chrono.json` 中。

其中 `fixed_time` 为 12000，即将时间锁定在黄昏，维度内不存在昼夜变化；
`bed_works` 为 true，即允许睡觉，但睡眠只重置重生点。
`coordinate_scale` 为 1.0，`has_skylight` 为 true，而 `has_ceiling` 与 `has_raids` 为 false。
`ambient_light` 为 0.0。
`height` 与 `logical_height` 都是 384，`min_y` 为 -64。
`effects` 指向 `minecraft:overworld`，因此天空与雾色沿用原版特效。
`monster_spawn_light_level` 是 0 到 7 的 uniform。

## 地形设置

地形设置的定义写在文件 `worldgen/noise_settings/chrono.json` 中。

其中 `default_block` 是 `dawnduskterminal:canopy_stone`，`default_fluid` 是 `dawnduskterminal:portal_fluid`，等级为 0。
`aquifers_enabled` 是 true，`sea_level` 是 63，`ore_veins_enabled` 是 true。
`lava` 噪声指向 `dawnduskterminal:aquifer_lava`。
`noise` 的 `height` 为 384，`min_y` 为 -64。
`noise_router` 取自移植后的原版主世界路由。
`surface_rule` 是一张按群系分支的大表，其默认产物是 `canopy_stone`。
`spawn_target` 沿用原版主世界。

## 移植文件

`tools/port_overworld_worldgen.py` 从 Gradle 缓存中的反编译客户端 jar 里抽取原版的
`density_function`、`noise` 与 `noise_settings`，并在改写命名空间后写入本模组。

`density_function/` 目录包含 `overworld/` 子目录。

`noise/` 目录存放移植过来的噪声参数。
其中只有 `noise/temperature.json` 的 `firstOctave` 经过改动，详见「温度噪声频率经过手工调整」。

`multi_noise_biome_source_parameter_list/` 目录存放原版主世界与下界的多噪声参数表副本。

`configured_carver/` 目录存放洞穴与峡谷的配置。

`configured_feature/` 与 `placed_feature/` 目录存放移植过来的特征。

移植脚本把群系中的放置特征引用，以及放置特征中的配置特征引用，都改写到 `dawnduskterminal:` 命名空间。

`configured_feature` 内部嵌套的引用不在改写范围之内。
因此 `random_selector` 中 `feature` 与 `default` 两个键指向原版的部分需要手工补齐，详见「已知问题」。

## 调色板群系源

调色板群系源由 `world/PaletteBiomeSource` 实现，注册为 `dawnduskterminal:palette`。

维度 JSON 中的 `biome_source` 有三个字段：

- `preset`：原版主世界的参数表 `minecraft:overworld`；
- `palette`：`#dawnduskterminal:palette_biomes`；
- `extra`：追加的气候点，可以省略。

选择气候格时沿用原版的算法：取出 `preset` 的参数表，在末尾接上 `extra` 中的若干条目，
再一并交给 `Climate.ParameterList.findValue`。
该算法相对原版未作任何改动。

`extra` 的条目形状参照内联 `multi_noise` 的写法：
`temperature`、`humidity`、`continentalness`、`erosion`、`weirdness` 五段跨度，外加一个 `biome`；
深度由代码按点值 0 与 1 各插入一份，与 `OverworldBiomeBuilder.addSurfaceBiome` 的做法一致。
若把深度写成跨度，群系就会随 y 跳变。

`getNoiseBiome` 取得群系之后，再把 `minecraft:` 命名空间下的结果替换为同名副本。

换名按路径匹配，例如 `minecraft:plains` 变为 `dawnduskterminal:plains`。

只有一处例外改指了其他格子：热档的 `minecraft:desert` 被换成 `dawnduskterminal:white_land`（白地）。
详见下文「白地接管昼区」；
蓝沙沙漠则位于 `extra` 中，详见「蓝沙沙漠在极夜」。

群系落在哪一带由温度决定，群系源中不存在任何按坐标判定群系的特例，详见「温带分带与温度映射」。

群系副本携带本模组的方块特征表，因此维度在生成期即铺设本模组的方块，而不是在事后进行修补。

上述陈述只对一部分特征成立。
57 个 `configured_feature` 中有 46 个引用了模组方块或模组方块标签，例如
`ore_*`、`glow_lichen`、`ice_patch`、`patch_berry_bush`、`swamp_oak`，
其余 11 个是树，只放置原版原木与树叶。

地形的方块替换不依赖特征表，而是依靠 `noise_settings` 中的地表规则。

`worldgen/biome/` 目录中除 `floatrees` `white_land` 之外，其余都是与主世界群系同名的副本。

副本中的内容并不等于同名的原版群系：暮色森林的群系直接写入对应的原版位置，ID 不作改动，显示名依靠 lang 文件设置。
每个位置当前装载的内容，记录在 `docs/biomes.md` 中。

`floatrees` 不经过替换表：当采样深度低于 -1.6 时直接返回，这一判断早于选格阶段。

替换表在首次使用时构建，其原因是 biome source 在数据包解析期即被创建，而在那一刻标签 holder 尚未持有 key。

### 蓝沙沙漠在极夜

`dawnduskterminal:blue_desert` 是自建群系，地表为蓝色的 `road_sand`。
气温按雪地设定：`temperature` 为 `-0.45`，`has_precipitation` 为 `true`；
该群系的特征表中没有仙人掌、枯灌木与沙漠水井（原版沙漠的特征表中带有这三项），只保留绿洲的甘蔗与南瓜。

该群系的气候格由一条自建的 `extra` 条目给出：温度为 `[-2, -1]`（极夜），湿度为 `[-1, -0.35]`（偏干的那一半）。
极夜带的另一半湿度留给极夜平原 `polar_dusk`。
所以在极夜区里「干的地方是蓝沙沙漠，湿的地方是平原」；两者都是自建群系，各自拥有自己的格子。

`floatrees` 的判断仍在选格之前（依据 `depth`），因此极夜区的空岛仍然保持空岛形态。

夜区（`-0.8 ~ -0.6`）不含蓝沙，该带全部属于雪地族，即雪原、冰刺、雪峰与冰川。

判定门槛并不在 `-0.45` 这个数值上，而在 `0.15` 上：`Biome#warmEnoughToRain` 的判断式为 `getTemperature(pos) >= 0.15`，
`coldEnoughToSnow` 即取其反，`shouldSnow` 与 `shouldFreeze` 也使用同一判断。
`ChronoFreezeFeature` 依靠这些判断铺设 `canopy_frost` 与 `frozen_canopy`，因此任何小于 `0.15` 的温度都可以满足条件；
原版沙漠所用的 `2.0` 与 `false` 才使得蓝沙沙漠完全没有霜。

`getTemperature` 只在 y>80 时继续变冷，不会把 `-0.45` 抬升回 `0.15` 以上。

### 恶地只在昼区

`dawnduskterminal:badlands` 一族沿用原版气候表中恶地所在的那几格，而恶地要求温度落入热档（`0.55 ~ 1.0`）。
因此它只出现在昼区（`0.6 ~ 0.8`）的台地侵蚀档中。

温带、寒带与热带的温度都由 `ChronoTemperature` 的分段映射固定，无法达到热档，因此这三个带内不会出现恶地。

### 白地接管昼区

`dawnduskterminal:white_land` 占据昼区（`0.6 ~ 0.8`），是恶魂支线 T2 群系所在的位置。

该带的温度被映射固定在 `0.55 ~ 1.0`，而原版气候表在这一档中的五档湿度全部对应 `desert`，因此昼区默认落地的群系就是沙漠。
白地通过替换表把该格改指过去：在 `replacements()` 中有 `minecraft:desert → dawnduskterminal:white_land`。

台地侵蚀档中的恶地族不受该替换影响，因此昼区的构成是「白地为主，恶地成片点缀」。

白地是裸地，其地表规则铺设 `siltstone`；
其特征是恶地副本去掉地表装饰之后的结果，矿石与洞穴保持不变。

`temperature` 是 `2.0`，`has_precipitation` 是 `false`，因此该群系既不积雪也不长霜。

### 萤火虫森林

`dawnduskterminal:birch_forest` 装载的是暮色森林 `firefly_forest` 的移植结果。
其中的树、结构、灌木、浆果与萤火虫粒子都保持原样；
该群系的 `temperature` 为 0.5，`has_precipitation` 为 `true`。

其 ID 仍为 `birch_forest`，因为占位并不改动注册名，从 ID 即可看出它替代的是哪一格。
显示名在 lang 文件中改为「萤火虫森林」。

### 红草树林

`dawnduskterminal:old_growth_birch_forest` 装载的是上文那份萤火虫森林内容，只把草地与树叶染成红色。
`grass_color` 与 `foliage_color` 给出的是红色，`temperature` 提高到 `0.8`。

该群系同样不改动 ID，显示名在 lang 文件中改为「红草树林」。
这样一来，`birch_tall` 这个特征就没有任何引用者，详见下文「特征移植」。

### 黑森林腹地

`dawnduskterminal:dark_forest` 对应的就是暮色森林的 `dark_forest_center`，详见 `tools/port_tf_biomes.py` 中的对照表。

树叶由 `ChronoFoliageColors` 固定染成 `0xE94E14`，草方块则使用暮色森林 `dark_forest_center` 的调色器。

### 天门松

`configured_feature/tree/skygate_pine.json` 描述的是文竹式分层的树。
树干为 20~30 格的直筒，4~8 根枝分布在树干 28% 到 90% 的高度上，每根枝的顶端托着一团压扁的樱花叶团。

树干由模组自有的 `dawnduskterminal:skygate_branching`（`world/SkygateBranchTrunkPlacer`）生成。
原版 `cherry_trunk_placer` 最多只生成三根枝，而且贴附于顶部；
暮色森林的 `branching_trunk_placer` 其分枝则是逐格向上叠加的连排。
两者都无法铺满树干的中下部，因此树干高度在代码中计算。
枝的方向按方位角逐根随机确定，落点取在圆周上，再沿三维 Bresenham 直线走过去。
每步只落一块原木，主轴每步一格、副轴按比例进位。
因此 45 度、30 度这类斜枝也会生长，而且一根枝只占一条线宽。
这套打法与暮色森林苍穹木的 `twilightforest:branching_trunk_placer` 一致（其 `VoxelBresenhamIterator` 同理）；
不应用逐格单轴步进——斜向时每前进一格要落两块原木，枝会显得粗。
抬升量只在起枝时掷一次，枝上不再补竖木。
可调项如下：

| 字段 | 作用 |
|---|---|
| `branches.provider` | 枝使用的方块，`BlockStateProvider`。取 `dawnduskterminal:skygate_pine_wood` |
| `branches.count` | 分枝根数，`IntProvider` |
| `branches.length` | 每根枝在圆周上的伸出半径，`IntProvider` |
| `branches.band` | `{min, max}`，分枝高度带占树干高度的比例 |
| `branches.rise_chance` | 每根枝抬起一段的概率，抬起的高度为 1 到 `steps / 3 + 1` 格，不超过树干顶 |
| `radius_offsets.branch` | 每根枝的树冠水平尺寸偏移，`IntProvider`，逐枝抖动 |
| `radius_offsets.crown` | 树干顶上那团的水平尺寸偏移，固定值，比枝顶大一档用来封顶 |

分枝参数收在 `branches` 一个对象里，不平铺在 `trunk_placer` 下。
1.21.1 的 `RecordCodecBuilder` 最多只能拼到八项，加上 `branches.provider` 之后平铺字段是九项，编不出来。

枝用的不是原木而是天门松木：原木两端的横截面贴图会露在斜枝的侧面上，木用的是原木侧面贴图铺满六面，与苍穹木的做法一致。

树形的断言在 `SkygatePineTests`：树高为 20~30，分枝至少覆盖四个不同的高度，且这些高度同时落在树干的下半部与上三分之一；
离干的位置只准出现天门松木、不得出现原木，也不得竖直叠块，用来钉住「侧枝只占一块厚」。

叶团使用 `minecraft:cherry_foliage_placer`：`height` 取到该 placer 允许的下限 4（原版樱花取 5），垂直尺寸至此确定；
`radius` 随树浮动，再叠加 `radius_offsets`，因此横向尺寸比原版樱花大一圈，并且每团的尺寸并不相同。
需要注意的是，placer 内部的计算为 `i = radius + radiusOffset - 1`，最宽的一层在自下往上第二层，其半径即 `i`。
`radius` 取 3、`branch` 取 -1 时 `i` 只剩 1，叶团会缩成 3x3 的薄片，所以不应继续调低。

挂藤使用原版的 `minecraft:leave_vine`：它在树叶侧面的空位挂出藤，再向下垂落一到四格，恰好形成「藤长在树叶边上」的效果。
该 placer 只有一个 `probability`，含义是**每片树叶的每个侧面各掷一次**，因此密度随树冠大小线性增长。
`0.25`（原版丛林树的取值）在这棵树上会生成几百个藤方块；
`0.01` 实测生成 16 个藤方块、7 条藤，叶约 450 片。
调整密度时应按线性关系换算。

不应使用 `minecraft:trunk_vine`：它挂附于树干，每根原木的每一面都有 2/3 的挂藤概率，二十多格的树干会被挂成藤柱。
本模组曾自行实现过 `sparse_leaf_vine`：按「条数」随机挑树叶再向旁边挂藤。
结果绝大多数随机数落在叶团内部，其旁边并非空气，实际挂出的条数远少于配置值，该实现已被删除。

`minimum_size` 是 `limit 1` / `lower_size 0` / `upper_size 2`，与原版樱花一致；
旧配置的 `limit 20` 加 `upper_size 5` 等于要求树干高处 11x11 的范围全部为空地，树几乎无法生长。

## 温带分带与温度映射

温度偏移施加在 `PaletteBiomeSource#getNoiseBiome` 中。
该处照原样复制原版 `Climate.Sampler#sample` 的六项，只把温度替换为 `ChronoTemperature.shift`。

因此区块落盘、结构判定、`/locate biome`、刷怪与雕刻读取的是同一个群系函数。
`RandomState` 与 `NoiseChunk` 未作任何改动，`router` 不会进入包装器，`locate structure` 也不会再因插值层而变慢。

采样器只提供原版温度，偏移不依赖该温度来自哪一个采样器，详见「偏移仅在一处施加」。

### 七带与断点

`ChronoTemperature` 用六个断点把 `param` 切成七个带，每个带线性映射到原版气候表中的一段温度：

| 带 | `param` | 温度映射 | 实宽（`maxDistance 5000`） |
|---|---|---|---|
| 极夜 | `<= -0.8` | `-1.00 ~ -2.00`（冻结档再往外，`extra` 的极夜平原与蓝沙沙漠） | 1000+ |
| 夜区 | `-0.8 ~ -0.6` | `-1.00 ~ -0.45`（冻结档：雪原 冰刺 雪峰 冰川） | 1000 |
| 寒带 | `-0.6 ~ -0.3` | `-0.45 ~ -0.15`（针叶林 平原 云杉） | 1500 |
| 温带 | `-0.3 ~ 0.3` | `-0.15 ~ 0.20`（平原 森林 桦树 黑森林） | 3000 |
| 热带 | `0.3 ~ 0.6` | `0.20 ~ 0.55`（热带草原 丛林） | 1500 |
| 昼区 | `0.6 ~ 0.8` | `0.55 ~ 1.00`（热档：白地 恶地） | 1000 |
| 极昼 | `>= 0.8` | `1.00 ~ 2.00`（热档再往外，`extra` 的极昼群系） | 1000+ |

断点取用温度档自身给出的数值：`-1.0 -0.45 -0.15 0.20 0.55 1.0`。
各段首尾相接，因此整条映射在 `param` 上连续，不需要 clamp。

因此每一个带只对应原版的一档温度。
针叶林要求温度 `<= -0.15`，而热带的温度下界是 `0.20`；两者之间隔着一整个温带，所以针叶林不会生成于热带。

### 带内噪声

在映射之上叠加的是原版温度噪声乘以 `ChronoTemperature.SPREAD`（`0.08`）的结果。

噪声不决定分带，只让分带边界发生弯折。
带内温度已被映射固定在一档之内，噪声再大也只在该档内摆动，仅在边界两侧各形成约 `SPREAD` 宽的过渡。

温带即使计入噪声，也达不到沙漠与雪原的门槛：上界为 `0.20 + 0.08 < 0.55`，下界为 `-0.15 - 0.08 > -0.45`。
`WorldgenTests` 对这两条界线作出断言。

若希望边界趋于平直，可将 `SPREAD` 设为 0；
若将其增大到 `0.15` 以上，夜区就会开始向温带渗透雪原。

### 温度噪声频率经过手工调整

`noise/temperature.json` 是从原版 `minecraft:temperature` 逐字复制而来的，但**频率经过改动**。
`firstOctave` 从 `-10` 提高到 `-7`，`amplitudes` 未作改动。

原版主世界并不存在「带边界」这一概念，温度噪声本身就是气候，因此它使用 `firstOctave -10`，即波长 `4096 / 1024` 格。
该尺度比七带中的任何一个带（`1000 ~ 3000` 格）都宽。
若用于晨昏线维度，只会让边界在数公里的尺度上缓慢漂移，无论带内还是带边都看不出结构。

提高到 `-7` 之后，波长为 `512 / 128` 格，即 `2^7 / xz_scale 0.25` 与 `2^5 / xz_scale 0.25`。
其中较宽的一档让边界在带内起伏，较窄的一档把「与晨昏线平行」的群系边界打散。
幅度未作任何改动，因此抖动幅度、`±4.4444` 的值域与泄漏上界全都不变。

重新运行 `tools/port_overworld_worldgen.py` 会写回原版的 `-10`，因为该脚本执行的是忠实拷贝，没有例外表。
`WorldgenTests.temperatureNoiseRunsAtTheBandScale` 就是用来在此时报警的。

### 交界抖动

仅靠上述噪声不足以支撑交界处的形态。
`512` 格对于它自身要打散的边界而言仍然偏宽，在交界处观察仍能看出一条弯曲很缓的线。

因此在断点两侧再叠一层 `ChronoTemperature.seamDither`。
它是三个八度的值噪声，波长是 `SEAM_WIDTH` 的 `1 / 0.3 / 0.1`，在默认 `5000` 下即 `400 / 120 / 40` 格，
权重为 `1 / 0.5 / 0.25`，幅度为 `SEAM_DITHER`，即 `0.09`。
再乘以一个按到最近断点的距离算出的权重：断点上为 1，
离开 `SEAM_WIDTH` 即 `0.08` 个 `param`、默认约 400 格后归 0，中间用 smoothstep 过渡。

交界于是呈现为两侧群系互相侵入所形成的楔形与小片飞地；
带心不参与抖动，该带内的群系分布与只叠加 `SPREAD` 时一致。

`SEAM_WIDTH` 和 `SEAM_DITHER` 的取舍关系如下：`SEAM_DITHER` 越大，楔形越深，但同时留出的余量越小（详见下一条）；
`SEAM_WIDTH` 决定的是「交界有多宽」。
若调到 `0.15` 以上，就会从中间侵蚀掉窄带，因为夜区、昼区与极带的宽度都只有 `0.2`，整个带会变成抖动区。

带宽与抖动波长都以 `param` 尺度表达，与七带的宽度相同，因此两者都随 `maxDistance` 一同缩放。
`maxDistance` 从 `5000` 改为 `2500` 时带宽减半，抖动波长随之减半，交界不会相对变粗。
代码中不存在任何以格为单位的抖动常数。
`seamDither` 先把坐标除回 `param` 尺度，除数与 `ChronoLine.param` 所用的一致，再在 `param` 空间中计算值噪声。

抖动是 `param` 与 `(x, z)` 的纯函数：它不读取注册表，也不读取配置，因此跨区块、跨线程以及重开存档时都是同一张图；
值噪声采用 smoothstep 插值，4 格内的变化不到 `0.01`，不会在森林中切出格子状的断层。

交界抖动与 `SPREAD` 可能同时达到满幅，所以泄漏的上界是两者之和，即 `0.17`。
温带上界为 `0.20 + 0.17 < 0.55`，下界为 `-0.15 - 0.17 > -0.45`，仍然达不到沙漠与雪原的门槛。
`WorldgenTests` 对这两条界线作出断言。

### 值域

温度噪声原本是 `shifted_noise`，其噪声为 `dawnduskterminal:temperature`，
`amplitudes` 为 `[1.5, 0, 1, 0, 0, 0]`，`firstOctave` 为 `-7`（经过手工调整，详见「温度噪声频率经过手工调整」），
声明的值域为 `±4.4444`。

中间五个带的输出落在 `[-1.17, 1.17]` 之内（带端点加减 `SPREAD` 与 `SEAM_DITHER`）；
两个极带继续向外推移。
`param` 被钳制到 `±1` 时温度达到 `ChronoTemperature.POLAR_TEMPERATURE`，即 `2.0`。
因此 `minValue` / `maxValue` 为 `±2.0`。
极带内抖动的余量足够大。
在 `param` 为 `0.9` 处温度为 `1.5`，减去满幅抖动后仍有 `1.41`，稳定地留在 `extra` 的 `[1, 2]` 区间之内。

满足 `|param| >= 0.8` 的两个极带不叠加 `SPREAD` 对应的那条低频噪声。
该噪声一旦压低，就可能把极带边界推出 `extra` 的 `±1 ~ ±2` 跨度，使边界变成毛边。
交界抖动不受这一限制，极带边界（`±0.8`）仍然受其影响，因此极昼、极夜与昼区、夜区之间也会互相咬合。

### 新增群系的两种途径

**一条是 `extra`**。原版参数表只覆盖 `[-1, 1]` 的温度范围，越界地带是空的，
所以在 `extra` 中写入一条温度跨度越界的条目，它就必定胜过原版最外侧那一行。
极昼与极夜就是这样实现的。
`ChronoTemperature` 把 `param` 推到 `±1` 时温度达到 `±2`，恰好落入 `[1, 2]` 与 `[-2, -1]` 两条。

**另一条是替换表**。温度带内部没有空白地带，要在其中新增群系只能替代掉某一格。
白地采用的是这一条，详见 `docs/biomes.md` 中的替换链。

湿度、大陆性、侵蚀与怪异度在原版中都已铺满 `-1 ~ 1`，大陆性到 `-1.2 ~ 1.0`。
在这些方向上追加条目必定处于劣势，因此 `extra` 实际上只能用于温度的越界情形。

若希望完全不按六个参数排布（例如像暮色森林那样自行计算布局），则必须重写整个 `getNoiseBiome`，本模组不采用这种做法。

### 超界温度仍然可以选出群系

`Climate.ParameterList.findValue` 以六个参数的平方距离之和最小者为选取依据（对应 `Climate.ParameterPoint.fitness`），
其中单项距离由 `Climate.Parameter.distance(long)` 给出：数值落在 `[min, max]` 之内时距离为 0，
落在范围之外时则为到最近端点的差值。

因此 `1.5` 或 `-1.4` 这类温度既不会出错，也不会退回默认值。
表中没有对应跨度时选最近的那一行；`extra` 中有条目时则选中该条目。
极昼与极夜正是依靠这一机制得以生效。

## 标签

`tags/worldgen/biome/palette_biomes.json` 列出全部群系副本。

`tags/block/` 下是本模组的方块标签，供移植过的特征引用。

`data/minecraft/tags/worldgen/biome/` 把副本挂入结构生成与刷怪规则所用的标签。

`data/minecraft/tags/block/` 把模组方块加入原版的石类、土类、沙类与树叶等标签。

`data/twilightforest/tags/worldgen/biome/` 把副本挂入暮色森林结构的群系标签，目前仅有 `valid_hollow_tree_biomes`。

## 世界生成补丁

数据包无法触及的原版硬编码由 mixin 接管，逐条说明见 `architecture.md`。

`LavaBandPaletteMixin` 负责处理 y 以下的整条岩浆带。

`LavaAquiferPaletteMixin` 负责处理深部岩浆含水层。

`PillarsFluidBlockMixin` 使石柱洞穴保持干燥。

`NoiseChunkVeinPaletteMixin` 负责处理大矿脉的花岗岩与凝灰岩。

温度的晨昏线偏移不在此处实现，而是由群系源处理，详见「温带分带与温度映射」。

`MineshaftHeightMixin` 与 `MineshaftChestLootMixin` 用于限制废弃矿井的高度并替换其战利品表。

识别维度统一依靠噪声设置的 `default_fluid` 是否为 `portal_fluid`。

## 结构

`structure/StructureExclusions` 是白名单，只放行这些结构：

- 原版：废弃传送门、沉船、前哨站、沙漠神殿、雪屋、废弃矿井；
- 暮色森林：树洞、倒下空心木、蘑菇城堡、营地、树篱迷宫、空心矿山。

`StructureExclusionMixin` 负责排除不在白名单之内的结构。

同一文件中的 `UNMAPPED` 与 `LANDMARKS` 记录未放行的暮色森林结构与地标，它们仅服务于日志，不影响行为。

### 高大暮色橡树

高大暮色橡树（Robust Twilight Oak）不是 placed_feature。
它就是 `twilightforest:hollow_tree` 结构，即由 `hollow_oak_sapling` 长出的那一棵；
不经过群系特征表，因此仅有白名单放行还不够：它的群系选择器是 `#twilightforest:valid_hollow_tree_biomes`，
而该标签原本只列出暮色森林自身的群系。

`data/twilightforest/tags/worldgen/biome/valid_hollow_tree_biomes.json` 以 `replace: false` 追加七份副本：
forest、birch_forest、old_growth_birch_forest、flower_forest、dark_forest、mushroom_fields 与 savanna。
结构在 `findValidGenerationPoint` 中查询的正是这个 holder set。
一旦标签缺失，巨树就会静默消失，所以 `WorldgenTests.hollowTreesAcceptTheChronoBiomes` 对这一点作出断言。

结构集 `twilightforest:hollow_tree` 的 spacing 为 7，separation 为 5，
其池子中一半的权重属于 `twilightforest:swamp_hollow_tree`，而该结构把群系固定为 `twilightforest:swamp`，
该群系在晨昏线维度中并不存在，因此实际密度大约再减半。

### 偏移仅在一处施加

早先温度偏移包装在 `NoiseChunk.cachedClimateSampler` 之中，只有区块落盘的群系带有偏移；
树洞判定所读取的 `context.randomState().sampler()` 不含偏移，于是两处判出不同的群系，巨树因此生成于沙漠区域内。
当时改用 `HollowTreeSurfaceBiomeMixin` 仅为树洞重新计算一次，其他白名单结构与 `/locate biome` 的结果仍然是错的。

也曾尝试把偏移整体迁移到 `RandomState`，同时包装 `router` 与 `sampler`。
该方案能跑，但性能严重下降：包装器进入了 `NoiseChunk` 的噪声图，
`locate structure` 的耗时从 0.1 秒变成单刻超过 60 秒，直接触发看门狗。

目前偏移只在 `PaletteBiomeSource#getNoiseBiome` 中执行一次。
两个采样器在同一坐标给出的原版温度数值相同（仅仅是缓存包装不同），偏移又只存在于这一处，因此最终群系不可能再出现分叉。
`NoiseChunkTemperatureMixin` 与 `HollowTreeSurfaceBiomeMixin` 已被删除。
`ChronoTemperature` 也不再包装任何 `DensityFunction`。

### 网格屋群

`dawnduskterminal:grid_rooms` 是模组唯一的自定义结构，由 Java 生成而不是 jigsaw。
拼图要求件之间包围盒不相交，而这里的相邻房间共用同一列原木，天然重叠；
另外「墙只在外圈」「凹角两面自己带墙」这类判断依赖整张图，拼图的池子只能按件预枚举。
1.21.1 的 `JigsawPlacement` 确实会把所有已摆件的空间累积成 `VoxelShape` 再判重叠（兄弟分支也查），
但上面这些全局条件它表达不了。

结构类型 `dawnduskterminal:grid` 见 `structure/GridStructure`，布局规划见 `structure/GridFrame`。
去皮原木是房间的框架，相邻两个房间共用同一条框架，所以格子步进是「边长减一」。
`GridFrame` 里的 S=5、L=7 都是框架到框架的净跨度；首末行列固定 S，中间一律 L，
相邻两侧的边长因此必然相等。

一层的形状是**大矩形减四个角的切块**：每个角取两个 0–1 的随机数，分别乘该矩形在 x、y 方向的边长，
就是这两条边各缩进多少（0 表示这一角不挖，两边都收得动才落下这一角）；四个角互不相干。
每个切块按阶梯语法落地：内角件 + 一条摆 `rect` 的臂 + 最外一格 `small`，臂外那一整块切掉。

房间件本身只存地板、四角原木与家具，没有墙：墙全部由 `wall/` 件长在真正露在外面的那一面。
地板与天花板共用 `ceiling/<体型>` 那一行平面，带开口的格子另用 `floor/<房间件名>`。

### 件

件按**家族目录**放：一个家族就是一整套件（分类目录齐全），件 id 是
`dawnduskterminal:<家族>/<分类>/<件名>`，比如 `dawnduskterminal:grid_rooms/room/rect/furn_04`。
家族名写在结构 JSON 的 `templates` 字段里（必填），由 `GridStructure#piece` 拼出来。

```
data/dawnduskterminal/structure/
  ddt_empty.nbt                 测试共享模板，不属于任何结构家族
  grid_rooms/                   家族：本结构这一套件
    air/ base/ ceiling/ climb/ edge/ parapet/ post/ wall/
    floor/  楼板，按房间件的相对路径镜像：floor/rect/stairtop、floor/small/rope_01
    shaft/  梯井，同上：shaft/small/rope_01
    room/   按体型分目录：large/ rect/ small/ inner_corner/
```

`room/` 再按体型分一层（`large` / `rect` / `small` / `inner_corner`），件名就是角色，
**只有重名的角色才带序号**：`room/rect/furn_01..07`、`room/small/rope_01..02`，
独一件的直接叫 `empty`、`stair`、`stairtop`、`chest`、`corner`。
`floor/` 与 `shaft/` 的路径由房间件的相对路径派生（`room/rect/stairtop` → `floor/rect/stairtop`），
所以三处必须同名。

`grid_rooms` 与 `grid_rooms_upper` 是同一个家族的两段高度，共用这一套件；换材质的变体另开一个家族目录、
只改 `templates` 字段。**家族目录名会跟着结构 start 存进区块**（件路径是字符串），改造目录等于改名，
老存档里未探索区块的那些房子会掉件（`place` 只记一行 warn）。

每个 y 段各成一件，件里不拖自己用不到的行走。落笔点相对**该楼层的地板行**（层高 5：地板 `+3`、天花 `+8`）。

下表里的路径都省略了家族前缀。

原来的 `foot/*`（墙脚）已经并进 `base/<体型>`：露出边数是确定的（`rect` 1 条、`small` 2 条相邻、
`large` 与内角件 0 条），所以 `base` 用和房间同一个朝向摆放，本地东那一列（`small` 还有本地南）
就是脚。`base/rect` 因此是 6×3×7、`base/small` 是 6×3×6。

| 目录 | 内容 | 件高 | 落笔点 |
|---|---|---|---|
| `room/<体型>/<角色>` | 四角原木 + 家具（内空由模板的 air 清） | 5 | `+4` |
| `room/inner_corner/corner` | 凹角整件：两面墙、檐口、凹口 | 5 | `+4` |
| `room/rect/stair`、`room/rect/stairtop` | 楼梯下段 / 上层的地板与楼梯口 | 5 | `+4` |
| `room/small/rope_01..02` | 绳梯房间（绳本身在 `shaft/` 里） | 5 | `+4` |
| `shaft/<房间相对路径>` | 绳梯接下去的那三格（只有绳） | 3 | `+0` |
| `base/<体型>` | 地基，外墙脚也画在这件里 | 3 | `+0`，只在下面没有房间时摆 |
| `ceiling/<体型>`、`ceiling/inner_corner` | 天花板平面（含横梁），只有一行 | 1 | `+8` |
| `floor/<房间相对路径>` | 带开口的地板：楼梯口、绳梯口、洞口 | 1 | `+3` |
| `air/<体型>` | 5×3×5 / 5×3×7 / 7×3×7 的纯空气，用来清天花板上面、地板下面 | 3 | 按世界 y 裁 |
| `air/notch` | 1×7×1 的纯空气，最后一遍擦掉凹角凹口那一列 | 7 | `+4` |
| `wall/<large 或 small>_<door 或 plain>_<n>` | 两列：压在框架线上的墙身 / 外装饰 | 5 | `+4` |
| `parapet/<5 或 7>` | 女儿墙，同长度的变体共用一份；两列（框列 + 外沿） | 2 | `+9` |
| `edge/<5 或 7>` | 墙底下那一条地板外伸；只剩最外那一列 | 1 | `+3` |
| `post/column` | 1×1×1 去皮原木柱的锚点，往下的部分由 `afterPlace` 接 | — | — |
| `climb/rope` | 一格绳，接在绳梯件底下一格 | 1 | — |

件名里的角色段是给装配看的，不需要解析模板内容：

| 角色 | 含义 | 受什么约束 |
|---|---|---|
| `empty` | 空地，只有四角原木与地板 | 门后、楼梯顶、楼梯两端必空；其余按权重 |
| `furn` | 普通家具房间 | 按权重 |
| `chest` | 宝箱房，箱子是件里自带的方块 | 按权重，只有 `rect` 有这件 |
| `rope` | 绳梯，从下层上到本层（占下层室内最上面三格） | 下层重叠格必须全是空地房，或下方没有建筑 |
| `drop` | 地板上有洞，直接掉到下层 | 有下层，且下层重叠格全是空地房 |
| `stair` | 楼梯下段：本层 `+4..+8` 斜上去 | 有上一层，且能找到位置、体型、朝向都对得上的格 |

**哪个体型能出哪个角色，只由「那个体型目录下有没有这个角色的件」决定**，代码里没有硬编码矩阵：
每个角色都有 `roomsOf(体型, 角色).isEmpty()` 的守卫，放一件 `room/rect/rope_01`，`rect` 就立刻能出绳梯房。

角色的抽取概率按体型各一套（`GridStructure.ROOM_WEIGHTS`），顺序是空地 / 家具 / 宝箱 / 掉落口 / 绳梯 / 楼梯：

| 体型 | 空地 | 家具 | 宝箱 | 掉落口 | 绳梯 | 楼梯 |
|---|---|---|---|---|---|---|
| `large` | 45 | 42 | — | 6 | 3 | 4 |
| `rect` | 15 | 70 | 3 | 3 | 4 | 5 |
| `small` | 20 | 65 | — | 3 | 9 | 3 |

宝箱房比三种跨层件更稀；空房间在 `large` 最多、`rect` 最少。
每层仍有一部**强制**的上下楼（底层一部绳梯、有上一层时一部楼梯），否则多层房子会接不上；
强制的那部不看权重，权重只管「额外还能不能再挂一部」

**凹角件的凹口**（局部 `6,6`）分三处处理：

- `room/inner_corner` 存空气：这一段本来就不该有东西。
- `floor/inner_corner` 存框架原木：它铺在楼板平面上，留空就是洞，而那个位置正是下层天花板。
- `ceiling/inner_corner` 是 `ceiling/large` 把凹口那一角挖掉：否则天花板会在凹口补回一根原木。

写得到这一列的只有这一格自己的天花板件与女儿墙件，它们比房间件落笔晚，会把凹角件里存的空气盖回去。
所以整个结构摆完之后还有一遍 `placeNotches`，用 `air/notch` 把那一列从楼面擦到女儿墙顶；
它排在件列表最后，同一个区块里谁后摆都一样。

件的来源是建造存档里的整件，导出时按上表的 y 段一段段另存（切分脚本是一次性的，不留在仓库里）。
改件之后照上表重新截一段、拷回 `structure/grid_rooms/` 即可；凹角那三处按本节说明处理。

件文件必须是 **gzip 压缩的 NBT**。`StructureTemplateManager.readStructure` 走的是
`NbtIo.readCompressed`，裸 NBT 会抛 `ZipException: Not in GZIP format`；而 `get` 把异常吞成空的
`Optional`，`place` 只记一行日志就跳过那一件，症状是这类件在整个维度里都不出现，存档本身不报错。
结构方块的 SAVE 写出来的本来就是 gzip（`save` 用 `NbtIo.writeCompressed`），用脚本另存时也要压缩。
`StructureTemplateTests` 把所有件过一遍，见 `docs/development.md`。

#### BandProcessor

`structure/BandProcessor` 管三件事：

1. 按世界 y 裁区间：`air/` 清空件只有 3 行，要清 4 行就按件高分段（`clearBand`），每段裁到自己那一段。
2. 一件分两遍落笔：先只落空气（`Phase.AIR`）把地形挖开，再只落实体方块（`Phase.SOLID`）。
   顺序反过来的话，后摆那件的空气会把前一件摆在框架线上的家具（树皮、栏杆、门）擦掉。
3. 结构空位（`minecraft:structure_void`）一律不落笔。原版只在拼图里认这个块，
   本模组自己往 `StructurePiecesBuilder` 里塞件，所以在这里补上同一条语义。

**这三个参数必须进编解码器。** 件是跟着结构 start 一起写进区块的，玩家分次探索时 start 从磁盘读回来再落笔；
只写 `processor_type`、把 `min_y` / `max_y` / `phase` 丢掉的写法，读回来就全变成「无窗口 + ALL」——
清空件会多写两行空气、把上一层的天花板擦掉，同一份种子在不同探索顺序下长出的房子就不一样
（一次性生成与分次探索的结果对不上，找起来极其费劲）。`StructureTemplateTests`
用原版 `StructureProcessorType.SINGLE_CODEC` 来回编解码钉住这条；旧存档缺这几个字段时按默认值读回，
不会把整座结构丢掉。

### 装配

一层的顺序是「先定摆什么，再落笔」：

1. 算这一层所有露在外面的边（`wallRuns`）。贴着邻居的边不摆墙，房间之间因此敞开；
   被挡住一段时只在最长的那段露边上补一件，不足 5 格留空并报一次告警。
2. 把上一层的楼梯口记成这一层的到达格（`arrivals`），那几格整间换成 `stairtop` 件。
3. 挑跨层格：先楼梯、再绳/梯（只有底层必修绳梯），详见「上下楼」。
4. 挑门：数量 `max(1, 格数 / 10)`（`DOOR_PER_CELLS`），取不同的格子，**门后的那一格必须是空地**。
5. 剩下的格子里四分之三是空地、四分之一是家具房（`EMPTY_SHARE = 0.75`）。

然后按遍落笔：**全部层的清空（纯空气）先做完**，再逐层「空气 → 实体方块」，最后是柱子、绳梯、边框家具、凹口。

- 清空每格两段，各 `ROOF_CLEAR`（4）行：天花板上方 `+9..+12`、地板下方 `-4..-1`。
  提前做完就绝不会擦到已经摆好的东西，也不必给清空算「哪些列要放过」——下层自己的件随后会原样摆回来。
  地板下方那一段**每格都清**：挑空、离房间 3 格以内的格子不长地基与柱子，下面却照样塞着环境方块。
  **只有两段落在房子外面，不清**：顶层天花板以上那一段、底层地板以下那一段。
  清掉它们就是在地里给房子掏出一圈空腔；这栋房子是**埋在地里**的，顶上与底下都该是实心岩层。
  其余各层两段清空都在室内（上一层的室内就是这一层的 `+9..+12`），照清。
- 支撑柱与往下接的柱、绳梯在 `afterPlace` 里延伸到第一个实心方块：先探再落笔，探到实心才接；
  中途遇到液体、或者探满 24 格（`SUPPORT_MAX_DROP`）还没落地，都不接（不在水面上立柱子，
  也不往水里、岩浆里、虚空里吊绳子）。绳/梯自身算能走，否则第一格就撞上 `placeShafts` 补的那一格绳，
  绳子再也铺不下去。
  `afterPlace` 每覆盖一个区块就调一次，交进来的 `box` 只是当前区块那一块（整根柱高），
  **所以每根柱子、每条绳梯由它自己所在的那个区块负责接**：整件包围盒会跨好几个区块，
  照着扫就会读到区块外，而世界生成期手里的 `WorldGenRegion` 只认自己那一圈，
  读到圈外直接抛 `Requested chunk unavailable during world generation`，整个区块的生成被打断，
  单人游戏里表现为被集成服务端踢回主菜单。原版的 `WoodlandMansionStructure` 与
  `DesertPyramidStructure` 同样只扫 `box`。
- 这一段只是收尾，件本身早就落完了，所以 `afterPlace` 里每个件各包一层 `try/catch`：
  一个件出意外只让它自己短一截，记一条 warn，不连累同一区块里其余柱子，更不让整个区块的生成作废
  （作废的区块不但踢人，还会在崩溃/紧急保存那条路上留下半成品的结构）。
- 同一件每遍都用同一个朝向，朝向在规划阶段算好存进 `Plan`。

### 上下楼

跨层件的件名带跨度，能落的位置由跨度决定：`drop` 三种跨度都有，`rope` / `ladder` 只有 5×5，
楼梯只有 5×7。每层至少一个：底层必有绳/梯，顶层以外的每层必有楼梯，其余名额只补楼梯与洞口。

| 角色 | 约束 |
|---|---|
| `rope` / `drop` | 下层重叠的格子全是 `empty` 房间（底层没有下层，天然满足） |
| `ladder` | 同上，另外要求下层同跨度（梯子靠墙，不是同一间房就贴不住） |
| `stair` | 上一层必须有一格位置、`kind`、宽、深全一样（`aligned`），那一格整间换成 `stairtop`，朝向沿用楼梯那一格 |

**配对**：`alignLevel` 把上一层整层平移一段，让某一对同跨度的 `rect` 正好对齐，楼梯因此一定找得到位置。
只比 `kind` 不行：5 宽 7 深与 7 宽 5 深是同一种 `rect`、可以同 `x/z`，但楼梯口与卡口地板转不到一块。

**楼梯两端**：楼梯件沿长边从一头走到另一头，玩家从一端的邻格踏上第一级、从另一端的邻格走出楼梯口。
楼梯格长边两侧的两格在本层、到达格长边两侧的两格在上一层，都由 `markStairEnds` 记成「必须是空地房」。

**互斥**：某一格定了跨层件之后，**同一层**贴边的格子不许再放跨层件（`noClimb`）——
两个洞口挨着会在同一块楼板上连成一片窟窿。上下层不互相挡：洞口开在不同的楼板平面上。

### 朝向

件里的家具都靠着外墙，而外墙在件里固定在同一侧：`rect` 在 east，`small` 在 south 与 east
（`PIECE_WALL_RECT` / `PIECE_WALL_SMALL`，第二面是它的逆时针邻面）。
生成时把这一侧转到格子真正露在外面的那面墙上（`orient`）：

| 形状 | 怎样定 |
|---|---|
| `rect` | 把主面转到露得最长的那条边上；5 宽 7 深的格子只能取 0 / 180，7 宽 5 深的只能取 90 / 270 |
| `small` | 两面墙是个角，先按成对转，凑不出这样的角再退回只看主面 |
| `large` | 没有外墙，家具四向都成立，在规划给的朝向上再随机转一个方向 |
| `inner_corner` | 自带两面墙，照规划给的朝向 |

「哪一面有墙」直接看 `plan.runs`：墙段是 `wallRuns` 先算好的，`orient` 取这一格最长的一段做主墙
（`directionOf` 把边的旋转反推成朝向），不按邻居自己推一遍。

### 多层

层数按权重挑（`STOREY_WEIGHTS`：2 层 50%、3 层 33%、4 层 17%），下限 2，上限是配置里的 `storeys`。
每层各自跑一次 `GridFrame.plan`（种子 `seed + 131k`），矩形长宽、四个角各自挖多深都独立计算，
所以层与层形状不同。矩形大小则逐层只微调：列数与行数各按 `N(0, STOREY_SPREAD)` 取整后累加，再收进 `3..上限`。

层与层之间只有三条规则：

- **地基**：正下方连同外面 3 格的一圈里没有任何房间才长（`roomNear`，查所有更低层），
  柱子也用同一条判据，于是柱子不会贴着邻居的墙面。`large` 本来没有地基与角柱。
- **女儿墙**：每个墙段都摆（`parapet/<长度>`），位置由墙段自己决定，不按「这一格上方有没有房间」判。

**这几族件是按列摆的，列数决定了落笔角。** 一条边要摆四种件，它们的列数各不相同：

| 件 | 列数 | 本地 x=0 是哪一列 |
|---|---|---|
| `wall/*` | 2 | 框架线（墙身） |
| `parapet/*` | 2 | 框架线（框列） |
| `edge/*` | 1 | 框架线外一格（外伸） |


所以 `placeWall` 里给它们三个不同的落笔角，差在沿外侧方向挪 0/1/2 格；
而且只有「本地 x 与世界坐标同向」的东、南两侧要挪（`Side#forward`），西、北两侧转过 180 度、正好不用挪。
早先这三族件都是三列，最里那一列是纯空气，本来想把室内贴墙那一列的家具擦掉——实测擦不到：
墙的 AIR 遍跑在同层房间的 SOLID 遍之前，而边框家具最后还有一遍 `placeBorder` 重摆。
那一列于是全程是空的，删掉后 `wall` 与 `parapet` 收成两列、`edge` 收成一列。
外墙脚随后并进了 `base/<体型>`（原来那族 `foot/*` 已删）：露出边数是确定的，
`base` 用和房间同一个朝向摆放，本地东（`rect`）与本地南+东（`small`）那两列就是脚。
- **往下接**：有地基的格子把四个角用 `post/column_N` 接到地面；差 3 格以内交给地形适配。
  凹角格的凹口那一角本来就没有地基柱，按件的旋转映射过去后跳过。同一根柱按方块坐标跨层去重。

### 生成高度

`grid_rooms.json` 的 `terrain_adaptation` 是 `none`：结构只摆自己的方块，不在底下或四周补地形。
`beard_thin` 之类会让游戏在建筑轮廓下填一层环境方块，把一个悬空的房子垫成实心。
需要往下接的东西由柱子与绳梯自己延伸（`afterPlace`，只在当前区块那一截里扫，见「装配」）。

这栋房子是**地下**结构，不是地表建筑：设定里基岩层在 y=0，下面是地下区域（`docs/设定.md` 的「地下」一节）。
高度用**两个结构**表达，各自一套结构集、各自的间距与盐，所以两段互不抢丰度：

| 结构 | y 段 | 结构集 |
|---|---|---|
| `dawnduskterminal:grid_rooms` | `-60 .. -30`（y<0 的地下浮岛区） | `random_spread`，salt `8417213`，spacing 32 |
| `dawnduskterminal:grid_rooms_upper` | `8 .. 40`（y>0 的地表以下） | `random_spread`，salt `8417214`，spacing 32 |

两者共用同一份 `type`、同一张群系标签与同一套件；差别只在 `y_min` / `y_max`。

### 破损与宝箱

- **破损**：每个实体方块按 `WEATHER_DECAY_PERMILLE`（35‰）的概率随机缺失；
  每座房子再掷一次「要不要长蜘蛛网」（`WEBBED_CHANCE` = 一半），掷中时缺失位置的约四分之一换成蜘蛛网。
  判定用「世界位置哈希 + 这一座房子的盐」，所以同一份存档里反复进出同一区块得到同一副面孔。
  带 nbt 的方块（宝箱）不参与缺失。
- **箱子是 `room/rect/chest` 这间「宝箱房」自带的方块**，不是另外摆的件：
  它的 `LootTable` 写在件里（`dawnduskterminal:chests/grid_rooms`，暂时与晨昏矿井同款，转 `twilightforest:hill_3`）。
  宝箱房是一个普通角色，按 `rect` 那 3% 的权重抽，所以**单座房子不保证有箱子**，
  也不再有任何「至少一间」的保底（上一版的 `ensureChestRoom` 已删）。
  一层 8~13 个 `rect` 格，通常每层仍会出 1 间；多层、格子多的房子可能出多间，不封顶。
  箱子带 nbt，所以不参与破损。
- **柱子只在安全的地方接**：照原版废弃矿井的做法「先探再落笔」——一路往下探到第一个实心方块为止，
  中途遇到液体就整根不接，探到 24 格或基岩还没落地同样不接。
- **绳梯比柱子宽松**：液体算能走，一路铺到第一个实心方块或基岩。这个维度地下铺满含水层，
  悬空房子的梯井下面十有八九先是水再是石；照柱子那套「遇水不接」的话，绳子会停在半空。

### 改件流程

`/ddt pieces` 把件摆成一张网格：每件占一格 14，格子底下铺满红羊毛（画在原点**下面一格**，
不压件最底那一层），旁边一块告示牌（中文名 / 件路径 / 尺寸）。**同一组排在同一段行里，换组另起一行**。

分块按「看的方便」而不是按目录：

- `ceiling/*` 与 `floor/*` 合成一组（`ceiling+floor/<体型>`）——天花板和楼板本来就是同一层的两面；
- `room/` 默认按**体型**分组（`room/rect`、`room/small`、`room/large`），家具房排在一起才好比；
- 空地房、宝箱房、以及上下楼的几种房间（绳梯、楼梯、楼梯口、掉落口）件少，单独各占一块
  （`room/empty`、`room/chest`、`room/rope`…），不再按体型细分；
- `wall/…` 按**长边/短边**分组；`shaft/` 按角色；其余目录照旧。

三种用法：

| 命令 | 摆什么 |
|---|---|
| `/ddt pieces` | 整个命名空间的全部件（含测试模板 `ddt_empty`） |
| `/ddt pieces <结构>` | 这个结构家族的那一套件，按分类分块 |
| `/ddt pieces <结构> <目录>` | 只摆一个分类，例如 `/ddt pieces grid_rooms room` |

`<结构>` 只接受 `dawnduskterminal:grid` 类型的结构（`grid_rooms`、`grid_rooms_upper`、
`towerwood_rooms`、`towerwood_rooms_upper`），
因为件是代码按目录取名的；jigsaw 结构要从模板池递归推导件，这里不做。
`<目录>` 取 `GridStructure.PIECE_DIRS` 那 12 个名字，补全里有。
摆位的行/列只是坐标计算（`GridPlacer.layout`），不碰世界，所以有两条用例直接问它。

**每件两个结构方块，躺在件对角线两端、各出格一格（y 也一样）**：

| 位置 | 模式 | 用途 |
|---|---|---|
| `min - (1,1,1)` | SAVE | 名字就是件的 id，原点与尺寸都按件的包围盒填好，点 SAVE 直接写对 |
| `max + (1,1,1)` | CORNER | 同一对角的另一端，点 DETECT 时复核盒子 |

两个方块都在件的外面，不会被存进件里；结构方块里的原点与角点都是**相对自己**的坐标。
点 SAVE 写到 `run/<端>/generated/dawnduskterminal/structures/<路径>.nbt`，
拷回 `src/main/resources/data/dawnduskterminal/structure/<家族>/` 即可。件的原点由结构方块的范围保持；
改动超出包围盒时先改大结构方块的范围。

`GridFrame` 的不变量由 `src/test/java/.../GridFrameTest` 检查，跑 `./gradlew frameCheck`，
已挂进 `check`，因此 `./gradlew build` 会一并跑。检查项：边长只取 5 或 7、格子不重叠、
相邻两侧边长相等、`large` 不贴轮廓、内角件落在 7×7 格上、同种子可复现。
`./gradlew frameCheck -PframeShow` 会把前几个种子的平面图打出来。

### 温度缓存
`PaletteBiomeSource` 中有一份每线程八槽的缓存，键为 `(quartX, quartZ)`。
原因是晨昏线维度的温度与 y 无关，`noise_settings/chrono.json` 的 `y_scale` 与 `shift_y` 都是 0。

原版 `LevelChunkSection#fillBiomesFromNoise` 的循环顺序是 x 在外层、y 在中层、z 在内层。
同一个 `(x, z)` 在区块填充过程中会被查询 96 次，命中率超过 95%。
当采样器更换（即换区块）或晨昏线的 `max_distance` 变化时，整个缓存表失效。
因此不会跨区块混用数值，也不会持有旧 `NoiseChunk` 的函数图。

## 高度

地形高度范围为 -64 到 320。

原版主世界的密度函数被整份移植，因此地形形态与原版一致。

空岛与地下浮岛仍然保留：`sloped_cheese` 中叠加的 `sky_island_density` 与 `subterranean` 分别负责 y 的高处与 y<0 的部分。

早期的四带设计（大陆、空置域、空岛与地下岛）在接管地形噪声的那一次改动中被整份替换，`VoidRegionFeature` 一并被删除。

### 空岛判据与云层

`sky_island_density` 的两段 `y_clamped_gradient` 在 140~240 与 260~310 之外不再发生变化；
当噪声整体偏高或偏低时，岛体只剩噪声零点附近的一薄片。

判据只有一条：格点的 `sky_island_density` 大于 0 且大于 `overworld/base_3d_noise`，就认为这一点的实心来自空岛。
`sloped_cheese` 是两者的 `max`。
所以山体在 140~196 与空岛带重叠的那一段靠 `base_3d_noise` 胜出被排除，不需要额外的高度、厚度或邻接扫描。
实现见 `world/SkyIslandDensity`。

这两个函数实例从 `RandomState` 已经换过种子的函数树里抓。
`RandomState` 会把 `HolderHolder` 换成直接持有者，引用键随之消失，所以按名字在生成期找不到它们；
两个 JSON 各套一层值直通的 `dawnduskterminal:sky_island_marker`，按类型认。
抓取挂在 `NoiseBasedChunkGenerator.createBiomes` 的 `HEAD`，不能挪到构造函数，那一刻注册表还没绑定。
抓不到时判据一律返回 false，整个模组退化成「没有空岛」。

`SkyIslandClouds` 在 `FEATURES` 末尾给每个空岛列挂云，即 `ChunkGenerator.applyBiomeDecoration` 的 `TAIL`。
范围是岛底下 1 到 9 格、横向 ±4 格，
逐格算「距离衰减 + 三维噪声」，超过门槛就放 `twilightforest:wispy_cloud`。
它不看这一列在岛上的位置：边缘、平直与向下鼓出来的一律同一条判据。
形状完全由世界坐标上的噪声决定，相邻区块各算各的也不会错位。
厚度达到 3 的岛列另有概率把最底一层换成云，保证岛体至少留两格。

列顶读取的是 `WORLD_SURFACE_WG` 高度图。
这套高度图在 `CARVERS` 之后不再被 `ProtoChunk.setBlockState` 更新，因此停留在纯地形的高度上，树叶与草不会把列顶撑高；
若换成 `WORLD_SURFACE`，则会把树计算在内。

该处理不看群系，只看判据：晨昏线维度中任何群系的空岛都走同一条路径。

## 已知问题

`data/dawnduskterminal/tags/worldgen/biome/` 下的旧标签只保留 `palette_biomes` 一项。

### 引用指向哪个注册表

`random_selector` 的两个位置看起来相似，但指向的注册表不同：

| 形态 | 指向 |
|---|---|
| `{"feature": "X", "placement": [...]}` 里的 `X` | `configured_feature` |
| 裸字符串 `"default": "X"` 或不在内联对象里的 `"feature": "X"` | `placed_feature` |

移植脚本只改动了「群系 → 放置特征」与「放置特征 → 配置特征」两层，`configured_feature` 内部的引用未被改动。

后果不是报错，而是静默出现。
模组副本把树下的土换成了 `dawnduskterminal:rosy_dirt`，原版那一份则不会替换，
所以同一片林子里一半的树长在霞土上，另一半长在原版泥土上。

该问题已经补齐，具体有两项：

- 内联对象中指向原版的配置特征引用已改为模组副本。
- 裸字符串所指向的原版放置特征在模组中补上了对应的 `placed_feature` 副本，再把引用指向该副本。
  这类特征有 11 个，例如 `birch_bees_0002`、`oak_bees_002`、`jungle_tree`。

模组目前剩下 `swamp_oak` 一个 `configured_feature` 副本没有任何引用者。

`birch_tall` 也应当计入，但它有一份属于自己的 `placed_feature` 引用着。
那份 `placed_feature` 已经没有群系需要，工具的字符串匹配识别不出这种间接孤儿。

`tools/check_worldgen_refs.py --list-dead` 会打印上述判定结果。

该命令同时会打印 `swamp_oak` 与 `birch_tall` 这两个名字。

### 没人引用的放置特征

有 18 个放置特征副本没有任何群系引用：
`ore_coal_lower`、`ore_coal_upper`、`ore_copper`、`ore_copper_large`、`ore_gold`、`ore_gold_extra`、
`ore_gold_lower`、`ore_iron_middle`、`ore_iron_small`、`ore_iron_upper`、`bamboo_light`、
`dark_forest_vegetation`、`flower_flower_forest`、`flower_swamp`、`red_mushroom_swamp`、
`trees_swamp`、`forest_flowers`、`patch_grass_forest`。

这些特征位于原版主世界的群系中，而本维度的群系特征表已经整份换成暮色森林内容，脚本为 `tools/port_tf_biomes.py`。
因此煤、铜、金、铁四种矿在本维度不会生成，改由暮色森林的浆果矿与本模组自有的矿替代。

这是设计上的选择，并非 bug；
但副本本身属于移植残留，是否删除尚未确定。

## 修改地形的注意事项

改动世界生成后，建议删除 `run/<端>/world` 并重新生成。

天光会被烘焙进区块，因此改动天光后，旧存档需要重新计算。

`AquiferTests` 与 `WorldgenTests` 会对调色板与流体作出断言，改动之前应先查看相关测试。
