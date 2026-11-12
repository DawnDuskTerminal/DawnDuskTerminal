# 雨天

这个维度会下雨。

雨天是一整套天气效果：雨丝与水花换成传送门流体的色相，露天的步行生物持续掉血并躲进屋檐，
露天的自然刷怪被压掉，改由「弦」的飞行型在雨里刷出，飞行型在雨天也不再被流体区域拴住。

## 天气从哪来

原版天气是全服共享的：非主世界维度的天气数据由 `DerivedLevelData` 转发主世界的 `ServerLevelData`，
读的是同一份 `isRaining()`。

写入那一步由每个维度自己的 `ServerLevel#advanceWeatherCycle` 做：维度类型 `has_skylight` 为真
（`dimension_type/chrono.json` 里是 `true`）才推进 `rainLevel`，这个维度正好满足。

于是无需另做一套天气：主世界下雨，这个维度就下雨，`/weather rain` 也照样生效
（命令写的是主世界的天气，其余维度跟着读）。

雨势是渐入渐出的，`rainLevel` 每秒涨落 0.2，`Level#isRaining()` 在它超过 0.2 时才成立。

## 什么算淋雨

只有一条判据，写在 `world/ChronoRain`。

三个条件同时成立才算：维度是 `dawnduskterminal:chrono`、`Level#isRaining()` 为真、
这一格头顶是露天的、所在群系的降水类型是雨（`Biome#getPrecipitationAt`）。

**不能直接用 `Level#isRainingAt`。** 它先查 `canSeeSky`，而 `canSeeSky` 判的是「天光 ≥ 15」；
这个维度的天光被 `LightEngineSkyLightMixin` 按晨昏线压到 12~15（默认中心 14、夜侧 12），
所以露天处 `canSeeSky` 也是假——拿它当判据，雨天会永远不生效（这一条踩过，见下）。

露天改用 `MOTION_BLOCKING` 高度图：`getHeightmapPos(...).getY() <= pos.getY()` 就是「头顶没有方块」。

雪原与沙漠因此天然不算：前者下的是雪，后者没有降水。

这两种情况的判据分别叫 `ChronoRain#isOpenSky` 与 `ChronoRain#rainsAt`，
游戏测试 `ChronoRainTests#chronoRainIgnoresSkyLightCap` 真的进这个维度断言「露天处 `canSeeSky` 是假、
但淋雨判定成立」。

## 谁挨这一下

玩家一律挨。滑翔、飞行、坐船都不豁免：玩家自己会看天气，豁免玩家等于给玩家开挂。

会走地面的生物也挨，按寻路方式认 `GroundPathNavigation`。
不用 `SpawnPlacementTypes.ON_GROUND` 当判据：它登记在实体类型上，恶魂、蝙蝠、蜜蜂都是 `ON_GROUND`
出生却常年飘着。

飞行的与水生的不挨：它们本来就能自己离开雨区，而给它们写「躲雨」是另一套复杂的行为逻辑，
这一版不做，所以它们也不吃这一下。

唯一合法的豁免是稳定效果（`dawnduskterminal:stability`，也就是传送门流体的那条）：
带它的玩家与生物都免疫雨天露天伤害。

## 淋雨伤害

露天的目标每 40 tick 掉 1 点，伤害类型是 `dawnduskterminal:stability`，
也就是传送门流体的接触伤害，落地时补一声 `dawnduskterminal:portal_hurt`
（与 `PortalBlock#entityInside` 同一个声音、同一套音量音高随机）。
那个音频是单声道的，才会按 `attenuation_distance`（缺省 16 格）随距离衰减；
立体声在 OpenAL 里不参与空间化，会变成「远近一样响、出半径就消失」（见 `portal.md` 的音效一节）。

这一下不产生击退：没有攻击者的伤害在 `LivingEntity#hurt` 里仍然会走那段 `knockback(0.4, 0, 0)`，
把竖直速度抬一下，所以结算完把速度原样放回去，见 `ChronoRain#hurtInRain`。

伤害仍按原版走护甲与保护减伤：僵尸自带 2 点护甲，实际会掉 0.94；想精确测「1 点」要挑没有护甲的目标。

「弦」的几个变种免疫 `stability`（`StringEntity#isInvulnerableTo`），所以站进雨里也不掉血。

## 躲雨

本模组自己会走地面的生物挂了 `entity/SeekRainShelterGoal`：活盔甲与高塔恶魂。

它排在受伤与攻击之前：淋着雨还要打，等于站在原地掉血。

行为在「自己露天又在下雨、并且没有稳定效果」时按切比雪夫距离一圈一圈往外找避雨点，
找到就寻路过去；头顶一有遮挡行为就自然结束，攻击与闲逛那一套接着跑。

「有顶」的判定是三条：这一格与上面一格都是空气、脚下方块有碰撞箱、并且 `ChronoRain#isOpenSky` 为假
（同样是高度图，不是 `canSeeSky`，原因见上一节）。
扫描是纯几何，不读天气，所以能在游戏测试里直接断言。

## 露天的自然生成被压掉

`world/ChronoRainSpawns` 挂在 `MobSpawnEvent.PositionCheck` 上：雨天里露天的格子直接判 `FAIL`。

只拦 `NATURAL` 与 `CHUNK_GENERATION` 两条自然的路，刷怪蛋、`/summon`、刷怪笼不受影响。

洞穴、树冠下与屋里照常刷：判据与淋雨伤害是同一个「露天」。

## 飞行型在雨里刷出

`StringSpawner` 每 5 秒一轮，雨天再加一趟：在玩家周围 12 到 44 格任意一列的地表之上 6 到 16 格，
必须连着 `ChronoRain#isExposed` 判定（露天、所在群系会下雨、此刻在下雨）的位置刷低空飞行型。

它不要求脚边有传送门流体——雨天的飞行型本来就不受流体区域约束。

每轮每个玩家最多试十列，命中的列有半概率生成，附近已有五只就不再刷。

## 飞行型放开流体约束

`StringEntity#isRainFreeFlight` 只看天气：这个维度在不在下雨。

成立时两件事一起变：巡航点改成任意一处空气（`randomFreeAirPoint`），回家行为
`ReturnToFluidGoal` 整个不触发，于是它可以一直飘在露天的雨里。

只看天气而不要求它自己淋着雨：一只飞进屋檐的飞行型不该立刻掉头回湖里。

滑行型与游动型不受影响，它们的活动范围本来就是流体本身。

## 视觉：贴图转色相，不是乘法着色

颜色只能来自贴图：原版雨丝的顶点色写死白色，水花是 `ParticleTypes.RAIN` 那四张。

原版雨丝是 224° 的冷蓝，传送门流体是 286° 的紫。**色相是 HSV 里的环形比例，乘法改不了它**：
正片叠底、`ColorModulator`、顶点色改的都是亮度与彩度，蓝乘什么都不会变成紫。
所以只能把贴图复制一份、逐像素转色相。

`tools/recolor_rain.py` 负责这件事：从原版资源 jar 里读出 `rain.png` 与 `splash_0..3.png`，
每个像素换成 HSV、把 h 设为 `0x7F159E` 的色相、S 与 V 与 alpha 原样带回，写进
`assets/dawnduskterminal/textures/` 下（`chrono_rain.png`、`chrono_splash_0..3.png`）。
脚本自带校验：彩度够的像素色相偏差不超过 0.5°，S/V 逐像素不变。

雨丝：`mixin/LevelRendererRainTextureMixin` 对原版 `renderSnowAndRain` 里换贴图那两处做一次
`@Redirect`，只有「原版雨丝路径 + 当前维度是晨昏线」才把参数换成 `chrono_rain`，雪与别的维度原样放行。

贴图路径带 `textures/` 与 `.png`：贴图这一路拿到的就是资源包里那条完整路径（原版自己的 `RAIN_LOCATION`
也是这么写的），粒子贴图表里的条目才是图集 sprite 名、不带 `.png`。
`ChronoRainTextures` 第一次用到时会查一次资源表，打包漏了这张图就退回原版贴图并告警，而不是拿紫黑格子顶上去。

地面水花：`dimension_type/chrono.json` 的 `effects` 指向 `dawnduskterminal:chrono`，
客户端在 `RegisterDimensionSpecialEffectsEvent` 里注册 `client/ChronoDimensionEffects`。
它的 `tickRain` 接管原版的落地水花，刷 `dawnduskterminal:particle_portal_rain`
（`client/PortalRainParticle`，物理照抄原版 `WaterDropParticle`，贴图是转好色相的那四张）。

## 相关文件

| 文件 | 作用 |
|---|---|
| `world/ChronoRain` | 淋雨判定、谁挨这一下、那一下伤害 |
| `world/ChronoRainSpawns` | 雨天压制露天的自然刷怪 |
| `world/StringSpawner` | 雨天在露天刷飞行型 |
| `entity/SeekRainShelterGoal` | 躲雨的行为 |
| `entity/StringEntity` | 雨天的自由飞行 |
| `client/ChronoDimensionEffects` | 雨天的落地水花 |
| `client/PortalRainParticle` | 雨点粒子的物理 |
| `client/ChronoRainTextures` | 雨丝贴图的替换 |
| `mixin/LevelRendererRainTextureMixin` | 在换贴图处换成转好色相的那张 |
| `tools/recolor_rain.py` | 从原版贴图生成转色相的雨丝与水花 |

## 测试

`gametest/ChronoRainTests` 覆盖：雨丝与水花贴图的色相等于传送门流体的色相、谁挨这一下
（含稳定效果免疫与玩家不豁免）、非本维度下雨时一切都不生效、雨滴伤害是一点 `stability` 且没有击退、
免疫者挡得住、避雨点的搜索找得到屋顶下的落脚点。

游戏测试跑在 `minecraft:overworld` 这个测试维度里，所以「这个维度下雨」只能反向断言。
