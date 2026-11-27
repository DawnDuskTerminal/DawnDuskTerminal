# 向日葵、月葵与鬼花

晨昏线维度昼侧长向日葵、夜侧长月葵，两者外观不同、行为相同。
顶段另有 2% 的概率长成鬼花：外观与所在侧的花相同，会观察玩家并发射射弹。

## 方块与物品

| 侧 | 茎（无物品） | 花盘 | 物品 |
|---|---|---|---|
| 昼 | `sunflower_stem` | `sunflower` | `sunflower` |
| 昼 | `sunflower_stem` | `ghoflower` | `ghoflower`（武器） |
| 夜 | `moonflower_stem` | `moonflower` | `moonflower` |
| 夜 | `moonflower_stem` | `lunarghoflower` | `lunarghoflower`（武器） |

花盘与茎各带 `segment`（0~2）与 `head` 两个属性。
一株由 1~3 格叠成，底段与中段是茎，顶段是花盘；放置与生成时花盘的 `head` 恒为真，茎恒为假。

花盘与茎都只向下依附：底段下面必须是 `#minecraft:dirt`，其余段下面必须是同一侧、段号小一的方块。
任意一段被移除时，上面各段依次失去支撑并消失，下面各段留在原地。

方块模型没有 element，几何全部由 `client/SunflowerRenderer` 按株底坐标现算。
贴图由方块类型决定：昼侧用 `block/sunflower`，夜侧用 `block/sunflower_night`，破坏粒子取同一套。

## 生成

`sunflower_plains` 的 `dawnduskterminal:patch_sunflower` 使用 `configured_feature/sunflower.json`，
`radius` 7、`count` 40。株心在昼侧长向日葵，在夜侧长月葵。
玩家放下的花盘按同一掷法抽 1~3 格，上方放不下就截断，最少一格。

## 掉落

掉落由方块代码发出，`getDrops` 返回空，因此倒塌带走的段不会额外掉一件。
玩家破坏任意一段时，`AbstractSunflowerBlock#onDestroyedByPlayer` 沿这一列找到顶段，
再由 `#dropPlant` 掉一件；顶段已经不在了就不掉。创造模式不掉。
爆炸只让顶段掉一件。

鬼花的掉落与传染写在 `GhoflowerBlock#dropPlant`：攻击期有 15% 掉本侧鬼花物品，
其余情况掉本侧的花；传染概率攻击期 40%，观察期 100%（范围内有候选花盘时）。
挖掉它的非创造玩家会让传染出的新株立刻锁定该玩家。

## 鬼花的行为

行为只由顶段的方块实体执行，观察期与攻击期两个阶段写在 `SunflowerBlockEntity`。

观察期每 4~20 秒尝试一次：

1. 取 24 格内唯一一个非创造、非旁观的玩家；没有玩家或有两个以上，本次跳过并清零连续计数。
2. 把花盘转向该玩家，再用 `GhoflowerSight#isSeen` 判定他看不看得见。
   判据为距离 24 格内、视线夹角不超过 60 度，且眼睛到花盘的射线只被有碰撞箱的方块挡住。
3. 看得见则计数加一，第一、二次各播一声洞穴音；连续第三次连同转向一起进入攻击期。
   看不见则立即进入攻击期。

攻击期不再做视野判定，每 2~6 秒转向目标并发射一枚射弹，进入攻击期时也立刻发射一枚。
目标死亡、离线、切创造或旁观，或者离开 32 格（`SunflowerBlockEntity.DISENGAGE_RANGE`），则脱战回到观察期。
观察期的检测距离是 24 格，脱战距离比它多 8 格；玩家在 24 格边缘来回移动时不会反复进出攻击期。

## 射弹

昼、夜是两个实体类型（`ghoflower_projectile` 与 `ghoflower_projectile_night`），贴图与粒子各不相同。
射弹无重力，速度 0.8 格/tick，存活超过 100 tick 后消失。
命中伤害按命中点离晨昏线的远近折算，参数见 [架构总览](architecture.md#维度与晨昏线)：
晨昏线上为 10 点，昼版朝昼侧每单位参数加 20 点、到端点 30 点，朝夜侧每单位参数减 10 点、到端点 0 点。
夜版方向相反，朝夜侧升、朝昼侧降，三条锚点与昼版相同。
命中伤害从 `ghoflower_fertiliser`、`ghoflower_beauty`、`ghoflower_ghost` 三条中随机取一条，
三条都进 `data/minecraft/tags/damage_type/no_knockback.json`。

外观由 `client/GhoflowerProjectileRenderer` 画成一个面向镜头的四边形，
贴图为 `textures/entity/ghoflower_projectile.png` 与 `textures/entity/ghoflower_projectile_night.png`。

## 武器

`ghoflower` 与 `lunarghoflower` 是武器物品，右键打一发，不放置方块。
射弹从眼睛沿视线飞出 64 格，昼夜版本由物品本身决定；冷却 2 秒。
弹药为 `minecraft:sunflower`、`sunflower` 或 `moonflower`，每发消耗一个，创造模式不消耗。
没有弹药或处在冷却中时返回 `PASS`。

## 音效与死亡信息

观察期那一声是三个独立事件，分别指向原版 `minecraft:ambient/cave/cave1`、`cave2`、`cave10`，
只发给被观察的那个玩家（`Player#playNotifySound`），不使用会按距离衰减的位置音效。

| 伤害类型 | 死亡信息 |
|---|---|
| `ghoflower_fertiliser` | `%1$s 变成了太阳花肥` |
| `ghoflower_beauty` | `%1$s 见识了太阳花的美` |
| `ghoflower_ghost` | `%1$s 见鬼了` |

方块贴图、方块模型与方块状态、叶片模板由 `tools/build_sunflower_assets.py` 生成；
`tools/check_sunflower_assets.py` 校验这些产物以及物品图、射弹贴图与四份战利品表资源。
鬼花物品的图标与模型在 resources 下单独维护。
