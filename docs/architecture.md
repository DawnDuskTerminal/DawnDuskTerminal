# 架构总览

本模组基于 NeoForge，新增没有昼夜更替的「晨昏线」维度。
太阳高度由玩家坐标在晨昏线法向上的投影决定。
地形采用移植的主世界生成流程，方块由本模组调色板替换。
Java 路径均相对于 `src/main/java/com/dawnduskterminal/`。
构建、测试与故障恢复见 [开发与构建](development.md)，专题入口见 [文档索引](README.md)。

## 模块职责

包名统一为 `com.dawnduskterminal`。
每个注册类别对应一个 `registry/ModXxx`，使用 `DeferredRegister` 并在主类构造函数中注册。
资源 ID 使用 `DawnDuskTerminal.id(path)`。

| 目录 | 职责 |
|---|---|
| `advancement/`、`progression/` | 进度判定与 Boss 击杀记录 |
| `block/`、`item/`、`inventory/` | 方块、物品、方块实体与菜单 |
| `entity/` | 生物、投掷物与实体 AI |
| `client/`、`geckolib3/`、`molang/` | 客户端渲染与自带动画运行时 |
| `command/`、`network/`、`server/` | 管理命令、网络负载与服务端事件 |
| `compat/`、`crafting/`、`config/` | 兼容处理、自定义配方与配置 |
| `fluid/`、`portal/` | 流体、传送门建造、触发与回程状态 |
| `structure/`、`world/` | 结构规划、群系源、世界生成及环境规则 |
| `registry/`、`mixin/`、`gametest/` | 注册入口、硬编码补丁与游戏测试 |

## 入口

`DawnDuskTerminal` 注册各类注册表、`ModNetwork::registerPayloads` 与 COMMON 配置 `DdtConfig.SPEC`。
`FMLCommonSetupEvent` 注册 `FluidReactions` 和服务端事件，客户端额外调用 `DdtClient.init`。
配置定义、默认值与兼容键统一见 [配置项](configuration.md)。

## 生物

`registry/ModEntities` 注册实体，属性通过 `EntityAttributeCreationEvent` 注册。
刷怪蛋由 `ModItems` 注册，使用 NeoForge 的 `DeferredSpawnEggItem`，避免在物品注册时提前取得实体类型。
该类还负责公共初始化时的发射器行为与客户端两层颜色登记。
模型使用 `minecraft:item/template_spawn_egg`；`gametest/SpawnEggTests` 检查 `SpawnEggItem#byId` 反向映射。

### 弦

`entity/StringEntity` 共用模型、贴图与属性，`Kind` 序号写入同步字段 `DATA_KIND` 与存档键 `Kind`。
`applyKind` 为三种形态配置移动与索敌系统。

| 形态 | 移动与活动范围 | 攻击对象 |
|---|---|---|
| 滑行型 | `StringMoveControl` 水平推进，脚部固定在不稳定流体液面 | 脚边一格存在流体的玩家 |
| 低空飞行型 | `FlyingMoveControl(this,20,true)`、`FlyingPathNavigation` 与 `MeleeAttackGoal` | 无流体限制的玩家 |
| 游动型 | `FluidMoveControl` 与鱼类水下运动 | 浸在传送门流体中的玩家 |

滑行型每 8 至 17 tick 选取液面目标，控制器必须每 tick 推进，距离小于 0.35 格时停止。
不能沿用只推进一次就回到 `WAIT` 的原版 `MoveControl`。
空中推进由 `getFlyingSpeed` 将移动速度属性乘 0.045，不能使用非玩家恒定的 0.02。
小型巡航约 9.2 格/秒、追击约 12.4 格/秒。
大型基准为小型的 1.35 倍，分别约 12.4 与 16.7 格/秒，再统一乘 0.85 至 1.15 的随机系数。
离开液面时由 `keepOnFluidSurface` 返回上一个合法点。

低空飞行型在导航结束且 `nextInt(10)==0` 时选择液面上方两格的游荡点。
失去目标后由 `ReturnToFluidGoal` 返回液面，近距离每 tick 以 0.35 的目标距离悬停。
`travel` 与 `checkFallDamage` 沿用 `FlyingMob` 的无重力、无摔伤语义。
仅设置 `setNoGravity(true)` 不足以覆盖读档改写和俯冲积累的摔落距离。
传送门流体不属于原版水标签，不能依赖入水清零摔落距离。

游动型眼睛浸液时每 tick 增加 0.005 浮力，速度按 0.125 比例渐变并补充垂直推力。
`travel` 使用 `moveRelative(0.01)`，移动后整体速度乘 0.9，无目标时缓慢下沉。
原版水判定替换为传送门流体判定，脚部范围限制为池底上方 0.2 至液面下方 0.9 格。

三种形态只主动索敌玩家。
`world/StringSpawner` 每 5 秒扫描列，自然生成仅产生滑行型，玩家周围 48 格内上限为 5 只。
滑行型每次承受高于 2 点的伤害时，有 10% 概率分裂为低空飞行型与游动型各一只。
飞行型抬高 1.5 格；原实体使用 `discard`，不掉落、不计击杀、不播放倒地动画。
分裂播放末影人传送音并生成 60 颗传送粒子，新形态不再次分裂。

`StringSpawnEggItem` 的对方块、对源流体与发射器路径共用 `canSpawnAt`。
落点本身或正下方必须为传送门流体，否则不消耗刷怪蛋。
默认发射器实现为私有方法，物品单独实现该入口。

`client/StringRenderer#glowRenderType` 使用自发光着色器与 `COLOR_DEPTH_WRITE`，保留 alpha 230 的半透明。
实体先于半透明区块和云绘制，因此不能换用仅有 `COLOR_WRITE` 的 `entityTranslucentEmissive`。
竖条贴图为 16×512 与 32×1024，各 32 帧，由 `AnimatedStripTexture` 上传至单帧动态贴图。
播放与资源释放规则见 [动画运行时](ysm.md)。

待机音由 `tickAmbientSound` 连续调度，`getAmbientSound` 返回 null。
三个变体等长，413192 采样、44100 Hz，约 9.37 秒或 187 tick；剩余 8 tick 时启动下一段。
音高必须固定为 1.0，以免实际音长偏离调度时长。
运动回归入口为 `gametest/StringEntityTests`。

### 高塔恶魂

`entity/TowerGhastEntity` 使用目录式原件包 `assets/dawnduskterminal/ysm/tower_ghast/`。
主片段优先级为死亡、受击、移动，缩放取 `ysm.json` 的 `properties.height_scale`。
该包对应测试实体「测试恶魂 591」，仍计划整体删除；在删除前保持现有资源布局。
运行时来源、分层顺序与绑定表见 [Bedrock / YSM 动画运行时](ysm.md)。

### 大恶魂

`entity/GhastEliteEntity` 继承 `Ghast`，不实现暮色恶魂的天气控制与减伤机制。
碰撞箱取模型 `thehead` 的 128 单位立方体，即 8×8×8 格，眼高为 4 格。
生命 250、索敌范围 100、护甲 10、击退抗性 0.9、击杀经验 317。
`NearestAttackableTargetGoal<Player>` 周期选择玩家，不限制高度差，也不在受击时立即反击。

`GhastElitePopulation` 为自然生成预留维度容量，加载范围内最多一只。
群系 `old_growth_birch_forest` 的 monster 表权重为 1，单批上限沿用恶魂的 1。
刷怪蛋与刷怪笼不受该容量限制；会话清理要求见 [服务器会话隔离](development.md#服务器会话隔离)。
掉落为砷铅铁 1 至 4、恶魂之泪 1 至 3、高塔恶魂皮 1 至 4、生恶魂肉 1 至 4、火药 0 至 3，均带抢夺加成。

存在目标时 `requiresCustomPersistence` 为真，避免不主动接近目标而触发原版无动作消失。
脱战后目标超过 100 格被释放，恢复原版消失规则，不长期占用名额。
实体必须保留碰撞；`noPhysics` 会使 `AbstractHurtingProjectile` 无法命中，破坏拨弹机制。
移动沿用原版 `GhastMoveControl` 的直线碰撞预检。
目标距离连续 30 tick 未缩短时，`RandomFloatAroundGoal` 重新选择航点。

| 优先级 | Goal | 职责 |
|---|---|---|
| 4 | `AvoidHazardousFluidGoal` | 避开危险流体，详见 [生物躲避](portal.md#生物躲避) |
| 5 | `RandomFloatAroundGoal` | 在 16 格内选择航点 |
| 7 | `EliteLookGoal` | 无目标时朝向航线，有目标且距离小于 64 格时朝向目标 |
| 7 | `GhastShootGoal` | 蓄力、开火与冷却 |
| 索敌 1 | `NearestAttackableTargetGoal` | 选择范围内玩家 |

`EliteLookGoal` 等价于无法直接引用的原版包私有 `GhastLookGoal`，不可省略朝向更新。
音效使用 `ghast_universal_*`，`GhastVoice` 负责蓄力与喷吐，拨弹另播 `ghast_universal_deflect`。

### 射击与拨弹

大恶魂与恶果共用 `entity/GhastShootGoal` 和 `entity/GhastDeflect`。
目标须在 64 格内且视线可达；蓄力超过 10 tick 时标记喷火，满 20 tick 时发射。
发射后计时置为 −40，经过 40 tick 冷却再蓄力。
`GhastVoice` 播放 `ghast_universal_charge` 与 `ghast_universal_shoot`。
出膛点与每轮发数由调用方传入。

大恶魂每轮同 tick 发射三发，同一出膛点、同一高度。
首发瞄准目标包围盒中心，其余两发仅对瞄准向量的 x、z 分量加 `(rand-rand)×8` 抖动。
`mouthPosition` 位于脚上方 1.9375 格、朝向前方 4.125 格，对应模型 `(0,31,-66)` 像素。
头骨没有俯仰通道，因此竖直出膛高度固定。

`GhastEliteFireball` 不命中其他 `AbstractHurtingProjectile`，不施加原版 6 点直接命中伤害，爆炸威力为 2。
爆炸自行结算 `ghast_elite_explosion` 伤害，该类型加入 `minecraft:bypasses_cooldown`，保证三发均有效。
原版爆炸只处理方块、粒子、音效与击退，实体伤害通过 `shouldDamageEntity` 关闭。
伤害沿用原版公式，但遮挡比为 0 时不结算，也不调用 `Entity#ignoreExplosion`。

火球必须列入 `data/minecraft/tags/entity_type/redirectable_projectile.json`，玩家攻击才会触发 `AIM_DEFLECT`。
`ProjectileImpactEvent` 中取消原命中，避免直接伤害和爆炸，并保留火球。
火球移至本体中心外 7.5 格，再瞄准当前目标；无目标时原路折返。
8 格碰撞箱的半对角线约 6.93 格，偏移必须使火球脱离本体。
同步字段 `DATA_FACE` 使用模型原名 `annoyed` 与 `atack`，分别表示有目标与正在蓄力，不修正拼写。

### 泪雨、召唤与小鬼

每 tick 向 512 格内玩家发送一颗 `TFParticleType.BOSS_TEAR`，使用 `longDistance`。
每 10 tick 对可见天光处的玩家结算 3 点 `twilightforest:ghast_tear` 伤害。
范围为本体包围盒向下 16 格，再竖直扩张 16 格，水平方向不扩张；小鬼不受伤害，本体不回血。

首次取得目标时，在本体下方水平半径 3 格的方形区域排入六只 `carminite_ghastling`。
各条目随机延迟 0 至 59 tick，生成时按本体当前位置计算；碰撞时每次抬高 2 格，最多八次。
依次调用 `makeBossMinion`、`finalizeMobSpawn`、`checkSpawnRules(MOB_SUMMONED)`。
随后写入 `MINION_TAG`、持久标记与跟随 goal，再加入世界并播放出生动画。
本体存活期间小鬼不自然消失，本体死亡后由跟随 goal 的 `stop` 解散。

每 20 tick 为 48 格内没有目标的小鬼补发本体目标。
`MinionFollowGoal` 以本体为中心、半径 6 格环绕，优先级 4 须高于暮色森林飞行 goal 的 5。
暮色森林 `CarminiteGhastling#shouldAttack` 默认拒绝戴雕刻南瓜的玩家。
3.5 格内只要求视线可达，更远还要求玩家注视小鬼，点积阈值为 `1-0.025/距离`。
召唤小鬼带 `dawnduskterminal:elite_minion` 持久数据标记。
`GhastlingShouldAttackMixin` 为该标记跳过注视限制，但仍保留视线要求；自然生成的小鬼不受影响。
Mixin 直接引用暮色森林类型，升级造成签名变化时应在编译期失败。

### 大恶魂的模型

原件为 `geo/ghast_elite.geo.json`、`animations/ghast_elite.animation.json` 与 `textures/entity/ghast_elite.png`。
`parallel0` 驱动触手和四肢的柏林噪声摆动，`parallel1` 是布条与触手的逐帧 `timeline` 程序，无主动画。
滤波器按名称跨帧存储状态，骨骼旋转读取上一帧，绝对枢轴按当前旋转计算，不能替换为无状态函数。
物理按渲染帧而非 tick 积分，模型中的 `first_order(...,0.02)` 与 `0.94×上次目标` 依赖该步长。
`math.sin/cos` 接受角度，欧拉转换采用 ZYX 序，`bone_pivot_abs` 返回 x 不取反的 Bedrock 坐标。
模型表达式已自行处理 x 符号，重复取反会使辅助骨骼矩阵成为反射。
`FnBinding` 用 JOML 重建模型缺失的五个四元数函数，绑定名按词法规则转为小写。
其他绑定与验证流程见 [动画校验](ysm.md#校验)。

### 活盔甲

`entity/LivingArmorEntity` 是本体不可见的 `Monster`，随机穿戴整套护甲并持随机武器。
目标存活、位于正面且距离不超过 3 格，或 60 tick 内受击时举盾；其他情况收盾。
逃跑会清空目标，不能背对对手举盾。

`isBlocking` 要求副手有盾、未被斧头封锁、且不在出手后的 8 tick 窗口。
它不依赖会被原版逐 tick 校验清除的物品使用状态。
正面格挡、穿透箭及 `bypasses_shield` 判断仍交给 `LivingEntity#isDamageSourceBlocked`。
成功格挡窗口内改播 `item.shield.block`，吞掉实体事件 29 防止重复音效，并取消击退。
至少 3 点伤害时按「1+伤害取整」消耗盾耐久；可破盾攻击封锁盾 100 tick。

举盾不能疾跑，移动通过将 `getSpeed()` 乘 0.3 实现，不直接修改移动速度属性。
位移与属性并非线性关系，`gametest/LivingArmorTests#guardSpeedIsAboutTwentyPercentOfWalk` 检查实际比例。
近战、斧头与链锤攻击后 8 tick 内不格挡、不减速，也不显示举盾姿势。
客户端按 `PlayerRenderer#getArmPose` 设置 `ArmPose.BLOCK`。
测试入口为 `gametest/LivingArmorTests`。

## 装备与武器

### 材质与套装

`ModArmorMaterials` 注册绯色钼、苍穹、砷铅铁的钻石级护甲材质与天门松的铁级材质。
试验护甲的材质与维修规格统一见 [047 至 050](experiments.md#047-至-050护甲)。
`ModTiers` 注册四份工具分级，绯色钼仅有护甲。
`ModEquipment` 以 `Map<String,Set>` 管理六套共 54 件装备。
绯色钼套装拼接 `ModItems` 中已有 holder，四件齐全时由 `ScarletMolybdArmorItem#inventoryTick` 续期生命恢复。
装备占位贴图可在 `textures/item/` 与 `textures/models/armor/` 原路径替换，不能顺带改注册 ID。

### 绯色钼的穿戴外观

`client/model/ScarletMolybdenumArmorModel` 使用 twilightextra 的四份专用模型。
四个 `ModelLayerLocation` 按槽位组合成 `HumanoidModel`，非对应部位为空。
`IClientItemExtensions#getHumanoidArmorModel` 将模型交给原版护甲层，姿势由 `copyFrom` 同步。
基准位置为头身 `(0,0,0)`、臂 `(±5,2,0)`、腿 `(±1.9,12,0)`。
`getArmorTexture` 返回 `textures/entities/carmarmort1.png`，尺寸为 64×64，不分内外层。

本套不进入 `#minecraft:trimmable_armor`，加载、网络同步及穿戴时移除外部写入的 `TRIM` 组件。
专用 UV 与原版纹饰布局不兼容，不能只禁止锻造而保留旧组件。
`gametest/EquipmentTests` 验证贴图、不可纹饰与 NBT 往返后的清理。

### 砷铅铁钼反应权杖

`item/CarminiteStaffItem` 按单手近战武器计算，使用时消耗砷铅铁球并发射 `CarminiteBallEntity`。
力量、火矢、冲击、多重、穿透、快速装填沿用来源模组换算，物品加入原版 `bow` 与 `crossbow` 附魔标签。
`CarminiteBallEntity` 继承 `AbstractArrow`，命中后在上方一格生成 `CarminiteFieldEntity`。
反应场是普通实体，存活 75 tick，每 20 tick 对半径 4、高度 ±1.5 的圆柱范围结算伤害。
每 tick 在边缘绘制 12 颗粒子，伤害类型为 `carminite_field`。
`ModParticles` 注册 `particle_carminite_ball`、`particle_carminite_trace` 与 `particle_carminite_effect`。
客户端 provider 位于 `client/CarminiteParticle`。

## 试验物品

`ModExperimentItems` 管理序号和普通物品，具有方块或特殊食物行为的条目由 `ModBlocks`、`ModItems` 配合注册。
`ModCreativeTabs` 负责栏目排序，不以注册先后作为界面顺序。
详细规格统一见 [试验物品与衍生机器](experiments.md)。
加工入口为 `UncraftingResolver`。
持续施放入口为 `FireJetProcess`、`GhastTrapProcess`、`BeetrootProcess` 与 `RequiemProcess`。
065 与 069 共用 `CubeField` 算法及 `CubeFieldMarker` 可视化，保持独立场心集合。

## 火箭机弩

`item/FireworkMachineBowItem` 注册为 `firework_machinebow`，主栏目位于棱镜权杖之后。
按住期间每 `10-2×快速装填等级` tick 发射一轮，最快为 4 tick。
使用原版弩的七参数 `FireworkRocketEntity`，带 `shot_at_angle`，从眼睛下方 0.15 格以速度 1.6 发射。
每轮随机生成烟花形状、颜色、淡出色、拖尾和闪烁，飞行时长为 1 至 3，烟火之心为 1 至 7。
消耗的火箭内容不影响随机结果。

本类不继承 `CrossbowItem`，使用 `UseAnim.NONE`，避免第一人称持续拉弦姿势。
`client/FireworkMachineBowBob` 按出弹节拍调用 `ItemInHandRenderer#itemUsed`，不使用挥手动画或网络包。
弹药先取副手，再查背包；无弹药时拒绝启动，使用中耗尽则 `stopUsingItem`。
多重射击仍只消耗一枚弹药，多出的两发独立偏转 `7度+0至3度随机值`。
耐久为 465，每轮消耗 3 点，先发射再扣耐久；创造模式不扣弹药或耐久。

发射与装填使用 `firework_machinebow_shoot`、`firework_machinebow_draw`。
下轮前向 64 格内发送 `ClientboundStopSoundPacket`，只停止这两条残响。
`firework_machinebow_launch` 不停止，火箭设为静音以防原版发射音重复。
每轮将 `CUSTOM_MODEL_DATA` 在 1 与 2 间切换，选择两张专用贴图。
`releaseUsing` 与 `inventoryTick` 结束时清除组件，`shouldCauseReequipAnimation` 只比较物品与槽位。
不能让贴图变化触发换手动画，否则快速发射会使装备回弹持续叠加。

附魔标签包括 `bow`、`crossbow` 与 `durability`。
`world/MachinebowFireworks` 读取火箭 `NeoForgeData` 中的 `DdtMachinebowRocket`、星数及附魔等级。
原版私有爆炸流程仍负责 5 格距离衰减与视线检测，事件只补充以下修正。

- 外观固定为一颗烟火之心，伤害乘 `(5+2×星数)/7`。
- 力量每级使伤害乘 `1+0.25×等级`，火矢点燃非火焰免疫目标 5 秒。
- 命中前清除 `invulnerableTime`，保证多重射击同 tick 命中均全额生效。
- 冲击通过 `PENDING_PUNCH` 与 `LivingKnockBackEvent` 削减击退，每级减少一半，II 级起为 0。

## 反建器二型（反建场）

`block/AntibuilderType2Block` 与 `block/entity/AntibuilderType2BlockEntity` 提供机器和客户端框线。
`world/AntibuilderType2Field` 保存场心，`mixin/LevelSetBlockReactionMixin` 在 `Level#setBlock` 统一处理变更。
不保存方块快照，只比较原状态与拟写入状态。

`RANGE` 为 1 至 8，边长为 `2×range+1`，默认 17，最小 3。
创造模式右键增大、潜行右键减小，两端封顶；其他模式将交互交给物品放置。
范围调整通过 `AntibuilderType2Block#nextRadius` 计算，actionbar 键为 `block.dawnduskterminal.antibuilder_type2.range`。

`POWERED=true` 时场停用、框线关闭、光照归零，但场心与范围保留。
放置时与邻居更新时都须读取信号，断电当 tick 恢复。
关机机器也不计入相邻机器限制。

### 反应与例外

原状态非空气且非液体、新状态为空气或液体时，视为破坏并替换为 `antibuildblock_type2`。
原状态可替换、新状态为实心方块时，视为放置并恢复原状态。
水流进退及方块自身状态变化不触发反应。
在 `setBlock` 的 HEAD 接管写入并取消原调用，不能只改参数后继续原流程。
反应写入使用 `ThreadLocal` 防重入；其邻居更新仍会发生，但闸内不再次触发反建反应。
仅在服务端判定。

- 破坏反建器家族时恢复原状，家族包括暮色森林反建器与本模组二型。
- 家族成员允许放置，但二型不能紧邻另一台开启的二型，切比雪夫距离不大于 1，包含斜角。
  暮色森林反建器和关机二型不计入相邻限制。
- 每个场只豁免自己的场心，其他场仍可保护该机器。

相距至少两格、互相覆盖的机器可能无法正常拆除，须用命令移走；这是现有规则，不擅自取消。
撤销放置前发送 `LevelEvent.PARTICLES_DESTROY_BLOCK`（2001），使用被放方块状态产生碎片与破坏音。

### 登记、资源与测试

场心按维度、区块分桶，只检查目标区块及相邻区块的 3×3 桶。
最大半径为 8，小于区块边长 16，不能遗漏跨区块候选。
登记覆盖方块放置和移除、方块实体加载和卸载，停服时清空。
查询先检查区块已加载，再核对中心方块与状态，清除幽灵记录，不能主动加载区块。
范围和开关直接读取方块状态，不缓存第二份数值。
框线使用带半径参数的 `CubeFieldMarker`，绘制约定见 [共用登记与可视化](experiments.md#共用登记与可视化)。

机器保留木头音效、10 硬度、6 抗性、10 级光、`SAND` 地图色、`BLOCK` 推动反应与正确工具要求。
机器无战利品，进入 `#minecraft:mineable/pickaxe`。
产物方块无战利品、不遮挡、木头音效、0.3 硬度、2000 抗性，不进入镐子标签。
两个方块都有物品形态，位于主栏目。

机器模型分底座与满亮 cutout 发光层，关机模型只有底座，状态为八档范围与两档电源的 16 个变体。
反建方块模型同构，发光层为 10 级。
面引用的 `#bottom` 等贴图键必须逐项定义，不能用不同名称的键代替。
`gametest/AntibuilderType2Tests` 覆盖反应、家族例外、开关、跨区块范围和模型引用。

## 砷铅铁建造器二型（构建方块）

`block/TowerdevBuilderType2Block` 与 `block/entity/TowerdevBuilderType2BlockEntity` 实现建造器。
产物为 `block/TowerdevBuiltType2Block`，注册 ID 为 `towerdev_built_type2`。
机器为木头音效、10 硬度、6 抗性、`SAND` 地图色、`BLOCK` 推动反应与正确工具要求。
进入镐子标签，运行时发出 4 级光，沿用暮色森林建造器开关音与通电红石粉粒子。

### 生成与进度

状态只有 `active`，由放置时信号与后续邻居更新共同决定，没有超时或格数上限。
每 10 tick 按 16 格内最近的非创造、非观察者玩家视线生成一格。
俯仰角按 90 度一档映射为上、下或水平朝向，目标格必须为空气。
无人或目标被占用时等待，机器仍保持开启。
游标保存上次位置，玩家返回后继续。

再次开机时沿当前方向跳过已有构建方块，两档均计入，从末端继续。
每 tick 最多扫描 `SCAN_PER_TICK` 格，不能一次扫描任意长的旧线路。
未加载区块边界停止，不得由 `setBlock` 隐式加载区块。
断电清空计数与游标；进度不写入存档，重载后从机器位置重新扫描。
生产 ticker 使用四参数入口查询玩家，五参数入口仅供不在世界玩家表中的 GameTest 模拟玩家使用。

### 构建方块

单个方块以 `ON`（序列化名 `on`）区分两档，默认暗档，两档均稳定存在。
硬度 0.3、抗性 2000，挖掘掉落自身，不进入镐子标签。
右键通过六面广度优先搜索翻转相连且同档的区域，一次最多 64 格，斜角不连通。
手持同种方块时返回 `SKIP_DEFAULT_BLOCK_INTERACTION`，让 `BlockItem#useOn` 在点击面邻格放置。
不能返回会继续调用 `useWithoutItem` 的 `PASS_TO_DEFAULT_BLOCK_INTERACTION`，否则会先换档并消耗交互。

绿档无碰撞且可攀爬，暗档正常碰撞。
轮廓始终为完整方块，保证绿档仍能被点击。
攀爬走状态相关的 NeoForge `isLadder`，不能用无法区分状态的标签替代。
原版梯子逻辑使跳跃竖向速度为 +0.2、下落限制为 −0.15。
`skipRendering` 剔除任意两档之间的内部面。

暗转绿使用 `TFSounds.REAPPEAR_BLOCK`，音量 0.3、音高 0.6。
绿转暗使用 `TFSounds.REAPPEAR_POOF`，音量 0.3、音高 0.5。
音源为 `BLOCKS`，每次交互仅在起点播放一声。

机器的开关模型均有满亮 cutout 发光层，物品模型指向关闭状态。
构建方块两档共用 128×128 的 8×8 连接图集，由 `MountedTowerWoodModel` 按 47 格算法重映射。
`DdtClient#wrapTowerdevBuilt` 包装两个变体键。
粒子与物品使用 16×16 的未连接单图，物品为整方块，不能直接引用需要邻居信息的图集模型。
模型六面写入 `block_light=15`、`sky_light=15` 并关闭 `shade`，但方块本身光照为 0。
手持物品透明层还须在客户端设置 `ItemBlockRenderTypes` 的 cutout。
测试入口为 `gametest/TowerdevType2Tests`。

## 装饰画

`data/dawnduskterminal/painting_variant/` 是变体数据的唯一尺寸来源，贴图每 32 像素对应一格。
`ModPaintingVariants.Kind` 将资源键与物品绑定，`GhastPaintingEntity`、`GhastPaintingItem` 分别提供实体与物品。
每件物品只放置对应变体，没有配方；实体类型与变体同名，破坏和中键取得自身物品。

| 变体 | 格数 |
|---|---|
| `painting_ghast_1`、`painting_ghast_3` | 3×2 |
| `painting_ghast_2`、`painting_ghast_4` 至 `painting_ghast_8` | 2×3 |
| `painting_ghast_2_sub` | 1×2 |
| `painting_ghast_9` | 6×4 |

画名仅由语言文件决定，不影响注册 ID。
贴图放在 `textures/painting/`，由原版 `minecraft:paintings` 图集收录并使用原版画渲染器。
物品图标须另存到 `textures/item/`，按长边缩放至 96×96 透明画布。
物品使用 blocks 图集，不能直接引用画图集或 `textures/entity/` 下的贴图。

变体不进入 `#minecraft:painting_variant/placeable`，原版物品不能选中它们。
本模组实体的 `setVariant` 固定自身变体。
`PaintingVariantGuardMixin` 使原版画拒绝本模组变体，覆盖命令或存档手动写入的旁路。
测试入口为 `gametest/PaintingTests`。

## 红篝火

`block/CampCarminiteFireBlock` 使用 `FACING`、`LIT` 与 `WATERLOGGED`，不使用 `SIGNAL_FIRE`。
不能继承会在 codec 中构造回原版类型的 `CampfireBlock`。
点燃使用 NeoForge `FIRESTARTER_LIGHT`，熄灭单独处理 `SHOVEL_DOUSE`。
标签为 `#minecraft:campfires` 与 `#minecraft:mineable/axe`。

`CampCarminiteFireBlockEntity` 独立保存四格食材、逐格进度与时长，支持容器组件、配方查询及冷却。
原版方块实体构造器固定 `BlockEntityType.CAMPFIRE`，不能直接复用。
`client/CampCarminiteFireRenderer` 按原版朝向显示食材。
模型沿用 `template_campfire` 与 `campfire_off`，客户端还须登记 cutout，防止火舌透明像素变黑。

接触伤害与粒子沿用 [砷铅铁火焰](experiments.md#火焰与余烬)，不点燃实体。
余烬竖向初速为 0.2 至 0.25，每 tick 有 10% 概率播放 `block.campfire.crackle`。
战利品表 `campcarminitefire.json` 不限制工具，挖掘掉落自身与未烤完的食材。
测试入口为 `gametest/CampCarminiteFireTests`。

### 消耗度倒转

`world/CampCarminiteFireAura` 按维度登记场心，覆盖方块放置、移除与区块加载、卸载。
查询只检查登记的篝火并核实仍为点亮的本方块，不扫描整个范围，停服时清理。
有效范围为方块中心到玩家的 16 格距离。

`CampCarminiteFireExhaustionMixin` 在 `Player#causeFoodExhaustion` 拦截不大于 1 的正增量并取反。
大于 1 的增量保持原样，直接通过 `FoodData#addExhaustion` 的自然回血消耗不拦截。
消耗度降至 0 时先补 1 点饥饿值，饥饿值已满才补饱和度，再将消耗度设为 4。
必须优先恢复饥饿值，否则无法重新满足原版慢速回血的 18 点与快速回血的 20 点门槛。
`absorb` 在取反时即时结算，`DdtServerEvents#onPlayerTick` 覆盖进入范围时已为 0 的情况。

## 恶魂皮方块

`ModBlocks` 注册两种整方块，`ModItems` 注册同名方块物品，名称使用 `block.` 翻译键。
两者沿用羊毛的 0.8 硬度与音效，无工具要求，战利品表在爆炸存活条件下掉落自身。
物品模型保持 `item/generated` 与皮革图标，不使用方块模型。

`carminite_ghast_leather` 的方块状态模型按位置随机选择六个旋转，不将朝向写入状态或存档。
`carminited_leather` 使用 `block/carminite_leather_connected` 的 8×8 图集。
粒子使用 16×16 的 `block/carminite_leather`，邻居 UV 重映射由 `MountedTowerWoodModel` 提供。
`DdtClient#onModifyBakingResult` 负责包装模型，测试入口为 `gametest/BlockStyleTests`。

## 维度与晨昏线

`ModDimensions.CHRONO` 保存维度资源键。
`world/ChronoLine` 是包含原点与单位法向的 record，拒绝非有限坐标及非单位法向。
配置、存档与网络共用该验证，不能在信任边界省略。
`distance(x,z)` 返回有符号距离，`param(x,z)` 除以最大距离并钳制至 -1 至 1。
`create(random)` 以朝东为基准抖动 ±30 度，再随机翻转 180 度朝西，`normal_override` 可固定法向。
同一世界只生成一条线，玩家共享。

`ChronoLineState` 是存于主世界的 `SavedData`，键为 `dawnduskterminal_chrono_line`。
仅确认文件不存在时创建新数据；文件读取或解码失败时中止启动，不替换方向或覆盖原文件。
`serverLine`、`clientLine` 分别为两端快照，`ChronoLinePayload` 在登录时同步。
正常与异常停服的释放边界见 [服务器会话隔离](development.md#服务器会话隔离)。

## 群系源

`world/PaletteBiomeSource` 注册为 `dawnduskterminal:palette`。
编解码必填 `preset`、`palette`，允许可选的 `extra` 气候点。
当前使用 `minecraft:overworld` 参数预设和 `palette_biomes` 群系标签，追加点位于原参数表末尾。

`getNoiseBiome` 先通过 `ChronoTemperature` 映射温度，其余气候维度保持原采样。
深度低于阈值时直接使用 `floatrees`；其余调用扩展表的 `ParameterList.findValue`。
原版结果按路径映射为本模组群系，其中 `desert` 特别映射为 `white_land`；非原版命名空间直接返回。
替换表延迟构建，避免在数据包解析时访问尚无 key 的标签 holder。

原版温度范围为 `[-1,1]`，极带延伸至 `±2`。
维度 `extra` 包含极昼 `polar_dawn`，以及按湿度分选的极夜 `polar_dusk` 与 `blue_desert`。
占位内容不限制最终地形或生态设计，但替换时优先保留群系 ID。
删除或重命名 ID 须单独评估存档兼容和引用迁移。
气候分布、移植特征与内容对照见 [世界生成](worldgen.md#调色板群系源) 及 [群系对照](biomes.md)。

## 世界生成补丁

数据包无法覆盖的原版硬编码由以下 mixin 处理。
识别维度依赖噪声设置的 `default_fluid=portal_fluid`，不得替换该标记。

| Mixin | 职责 |
|---|---|
| `LavaBandPaletteMixin` | 将 `createFluidPicker` 的底部岩浆带替换为热情的暮光 |
| `LavaAquiferPaletteMixin` | 将深部岩浆含水层替换为热情的暮光 |
| `PillarsFluidBlockMixin` | 使石柱洞穴保持干燥 |
| `NoiseChunkVeinPaletteMixin` | 通过 `ChronoVeinPalette` 将矿脉花岗岩与凝灰岩替换为赭石与暮光石 |

温度偏移由群系源与 `ChronoTemperature` 完成，不在这些补丁中实现。
废弃矿井由复制的结构实现，不另叠加原版矿井 mixin，详见 [结构与废弃矿井](#结构与废弃矿井)。

## 传送门

| 类 | 职责 |
|---|---|
| `PortalShape`、`PortalBuilder` | 识别水池并建造传送门 |
| `PortalTrigger`、`PortalBlock` | 判断完整门、处理实体接触与孤立方块自愈 |
| `PortalTeleporter` | 即时传送、安全落点与到达门 |
| `PortalState` | 玩家回程维度与坐标附件 |

数值、投料规则、防重入、回程搜索和稳定效果统一见 [传送门与流体](portal.md)。

## 苍穹船

`entity/CanopyBoatEntity` 继承 `Boat`，`item/CanopyBoatItem` 负责放置。
固定 `Boat.Type.BAMBOO`，使用 `RaftModel` 与 `textures/entity/boat/canopy_boat.png` 的 128×64 贴图。
破坏掉落自身，不掉竹筏。
`world/BoatFloatingFluid` 经 NeoForge `canBoatInFluid` 允许水、岩浆与 `portal_fluid` 承载，不包含热情的暮光。
只影响本船，不改变原版或其他模组船。

实体类型须 `fireImmune()`，避免原版岩浆每 tick 点燃 15 秒并造成 4 点伤害而迅速破坏 40 耐久的船。
`PortalBlock#entityInside` 豁免苍穹船乘客，避免包围盒接触液面时仍受伤或被传送。
测试入口为 `gametest/CanopyBoatTests`，配方见 [合成与标签](crafting.md#苍穹船)。

## 流体

`ModFluids` 注册 `portal_fluid`，`ModScorchingTwilight` 注册 `enthu_fluid` 及其方块与容器。
`FluidReactions` 在公共初始化时调用 NeoForge 的 `FluidInteractionRegistry`。
`ScorchingTwilightBlock` 控制热情的暮光接触行为。
`item/CanocanItem` 处理苍穹罐取液及暮色森林绿色状态方块的回收。
绿色窗口分别读取重现方块的 `vanished && active` 与建造方块的 `active`，不等待额外 tick。
成功时发送 `PARTICLES_DESTROY_BLOCK`，以 `removeBlock` 移除且不产生方块掉落，返还一件 `experiment_028`。
其他目标返回 `PASS`，保留普通取液路径；测试入口为 `gametest/CanocanPickupTests`。
反应表、伤害、桶损坏与回收窗口时长统一见 [传送门与流体](portal.md#两种流体)。

## 进度与效果

`BossKind` 定义三个暮色森林 Boss，`BossProgress` 保存击败标记，`BossProgressTracker` 在击杀时写入附件。
`ModAttachments` 的 `boss_progress` 会序列化且 `copyOnDeath`。
Boss 门槛的设计声明与执行链路仍存在未决分歧，不得以删除文档的方式取消要求。
须先击败九头蛇、暮色恶魂或冰雪女王之一的设计要求仍保留，当前建门与传送未读取解锁状态。
恢复或取消门槛仍属 D01 未决决策，见 [使用前必读](../README.md#使用前必读)。

`ModEffects` 注册 `stability`、`compound_erosion` 与兼容保留的 `portal_sickness`。
`StabilityEffect` 是纯标记，由传送门决定作用方向；`CompoundErosionEffect` 执行周期接触伤害。
效果行为见 [稳定效果](portal.md#稳定效果)，药水类型与配方见 [合成与标签](crafting.md#十三-稳定药水的炼药)。
`advancement/LineParamTrigger` 按晨昏线投影参数判定进度。

## 客户端

`DdtClient` 注册模型、颜色、渲染层与事件，流体扩展为 `PortalFluidExtensions`、`ScorchingTwilightFluidExtensions`。
`ChronoFoliageColors` 解析群系 ID，`ChronoPlantTints` 负责纯函数着色。
`HueBandModel` 将坐标色相映射为图集 UV，避免用乘法顶点色覆盖目标色。
`AbstractFairyDianthusBlock`、`FairyDianthusItem` 与 `FairyDianthusFeature` 管理仙灵石竹形状及生成。
`ChronoFreezeFeature` 放置霜层与凝固的苍穹。
上述几何、着色、掉落与环境例外统一见 [世界生成](worldgen.md#仙灵石竹)。

`MountedTowerWoodModel` 提供塔木和衍生方块的连接模型，47 格公式位于 `ConnectedTexture47`。
`CountertopModel` 只重映射黑木台面顶面 8×8 图集，任意台面邻居均可连接。
动画接线及 native 网格所有权分别见 [动画运行时](ysm.md) 与 [native 网格内核](development.md#native-网格内核)。

## Mixin

`dawnduskterminal.mixins.json` 分别声明公共和客户端补丁。
世界生成补丁见 [对应列表](#世界生成补丁)，压制场补丁见 [立方压制场](experiments.md#立方压制场)。
`FluidStatusAccessor` 暴露 `Aquifer.FluidStatus` 的流体类型。
`FeatureSorterMixin` 允许配置控制的特征环，`StructureExclusionMixin` 执行结构白名单。
`LightEngineSkyLightMixin` 将天光上限设为 `ChronoSkyLight.maxSkyLight`。
`BiomeColorsPlantMixin` 将草与树叶取色接入 `ChronoFoliageColors`。
装饰画和红篝火的专用补丁见各自章节。

## 天空

晨昏线复用 `minecraft:overworld` 维度特效，由原版 `LevelRenderer.renderSky` 绘制日月、星空、云与日落光晕。
`ClientLevelTimeOfDayMixin` 按配置将投影参数转换为原版 `timeOfDay`，公式见 [lighting 配置](configuration.md#lighting)。
`LevelRendererSkyAlignMixin` 在两处 `pushPose` 后追加绕世界 Y 轴旋转，使日月轨道和光晕对齐晨昏线法向。
维度类型中的 `fixed_time` 仅影响服务端，客户端天空不读取它。
`ClientLevelSkyColorMixin` 将天空颜色乘 0.75，避免极昼浅色天空上的加色太阳不可辨识，并降低雾色过曝。
常数位于该 mixin，减小会使画面变暗。

## 事件

`server/DdtServerEvents` 挂载 `NeoForge.EVENT_BUS`。
启动时加载晨昏线并检查结构白名单，登录时确保快照可用并发送 `ChronoLinePayload`。
玩家 tick 每 10 tick 扫描附近投料，并执行维度进度与红篝火结算。
实体 tick 接入 `ChronoRain`，规则见 [雨天](weather.md)。
服务端 tick 推进持续物品过程与弦生成，击杀事件记录 Boss 进度，物品交互拦截原版桶。
停服时清理过程与场心，晨昏线和容量快照的释放须满足 [会话隔离](development.md#服务器会话隔离) 条件。

## 结构与废弃矿井

`StructureExclusions` 是唯一结构白名单，`UNMAPPED` 与 `LANDMARKS` 只供日志使用。
启动时校验引用，缺失时记录日志。
`ModStructures` 注册 `dawnduskterminal:grid` 与 `dawnduskterminal:mineshaft`。
矿井件类型为 `mineshaft_room`、`mineshaft_corridor`、`mineshaft_crossing` 与 `mineshaft_stairs`。
必须注册件类型，否则既有区块中的结构无法反序列化。

`GridStructure` 生成屋群，`GridFrame` 规划布局，模板与资源代次的关系见 [模板与结构检查](development.md#模板与结构检查)。
`ChronoMineshaftStructure` 与 `ChronoMineshaftPieces` 对应原版 1.21.1 矿井实现。
`ChronoMineshaftWoods` 决定四套暮色森林木材及地表群系选择。
布局、模板尺寸、支撑边界、处理器序列化和存档兼容要求统一见 [世界生成](worldgen.md#结构)。

## 已知遗留

`portal_sickness` 保留注册但不再施加，方向由 `stability` 决定。
`portal.cooldown_ticks` 与 `portal.entity_debuff` 保留用于兼容既有配置，当前没有读取方。
语言键 `death.attack.dawnduskterminal.portal` 与 `message.dawnduskterminal.searching` 当前未使用。
删除注册、配置键或存档字段须单独评估兼容性，不随文档精简实施。
