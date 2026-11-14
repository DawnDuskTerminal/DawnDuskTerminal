# 架构总览

本模组是一个 NeoForge 模组，新增「晨昏线」维度。

该维度内没有昼夜更替，太阳高度由玩家坐标在晨昏线法向上的投影决定。

维度的地形与群系来自原版主世界世界生成的移植版本，其中的方块被替换为本模组自己的调色板。

## 模块职责

包名统一为 `com.dawnduskterminal`，每个目录负责一类内容：

| 目录 | 职责 |
|---|---|
| `advancement/` | 自定义进度触发器与判定逻辑 |
| `block/` | 单独的自定义方块 |
| `client/` | 客户端渲染、天空、染色、屏幕效果与光影检测 |
| `command/` | `/ddt` 指令 |
| `compat/` | 特征排序等的兼容处理 |
| `config/` | 配置定义 |
| `crafting/` | 自定义 Ingredient |
| `entity/` | 自定义生物 |
| `fluid/` | 自定义流体 |
| `gametest/` | 游戏内测试 |
| `item/` | 自定义物品 |
| `mixin/` | 针对原版的补丁 |
| `network/` | 网络包 |
| `portal/` | 传送门的建造、触发、传送与状态管理 |
| `progression/` | Boss 进度追踪 |
| `registry/` | 全部 `DeferredRegister` |
| `server/` | 服务端事件处理 |
| `structure/` | 结构白名单与摆放表 |
| `world/` | 群系源、晨昏线、密度包装、自定义 Feature 与方块、流体反应、状态管理 |

## 入口

主类是 `com.dawnduskterminal.DawnDuskTerminal`。构造函数按顺序注册各类 `DeferredRegister`，并做三件事：

- 注册网络负载 `ModNetwork::registerPayloads`；
- 在 `FMLCommonSetupEvent` 上注册流体反应（`FluidReactions::register`）与服务端事件，详见下方「事件」一节；
- 注册 COMMON 配置 `DdtConfig.SPEC`。

客户端环境下额外调用 `DdtClient.init`。静态方法 `id(path)` 生成 `dawnduskterminal:` 命名空间的 `ResourceLocation`。

## 生物

`registry/ModEntities` 注册 `tower_ghast`（高塔恶魂）与 `string`（弦）。
两者的属性通过 `EntityAttributeCreationEvent` 注册。
刷怪蛋在 `ModItems` 中，即 `tower_ghast_spawn_egg` 与 `string_spawn_egg`，使用的是 NeoForge 的 `DeferredSpawnEggItem`。

用 `DeferredSpawnEggItem` 而不是原版 `SpawnEggItem`，是因为原版构造器要求一个现成的 `EntityType`。
物品注册早于实体类型注册，所以那时拿不到。
该子类接收的是 `Supplier`，并在 `FMLCommonSetupEvent` 中补上发射器行为，在 `RegisterColorHandlersEvent` 中登记两层染色。
客户端因此只需要一个 `minecraft:item/template_spawn_egg` 模型，贴图与原版刷怪蛋共用，颜色由染色层提供。
`gametest/SpawnEggTests` 通过 `SpawnEggItem#byId` 反向查询，断言这张表确实被填充。

### 弦

`entity/StringEntity` 的默认形态（滑行型）只在不稳定流体的液面上活动。
每 tick 把脚底固定回液面，一旦离开流体就弹回上一个合法点，既不会飞出去也不会沉下去。

水平移动由 `StringMoveControl` 驱动，不走地面寻路。
行为层每 8 到 17 tick 给出一个落在液面上的目标点，控制器**每 tick 都朝它推一次**，目标点进入 0.35 格时才回到 `WAIT`。
这里不能照搬原版 `MoveControl`：它一进入 `MOVE_TO` 就把自己改回 `WAIT`，
并假设行为层每 tick 重新给目标点（`PathNavigation` 正是这样做的）。
照搬的结果是「推一 tick 滑一程」，实测 20 tick 只移动 0.2 格。

空中每 tick 的推进量由 `getFlyingSpeed` 从移动速度属性折算成 `速度 × 0.045`。
原版在这一点上对非玩家生物返回恒定值 0.02，速度属性完全不参与，大小变种与「追击比巡航快」那一档都会因此失效。
折算后小型个体巡航约 9.2 格每秒、追击约 12.4 格每秒（行走的玩家 4.3，冲刺 5.6）。
移速随体型变化：大型个体以小型的 1.35 倍为基准（巡航 12.4、追击 16.7），
再乘上 0.85~1.15 的随机偏移，巡航与追击共用同一套系数。

该生物全身自发光。渲染层自己配置了一个材质，使用自发光着色器 `RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER`。
它不挂光图，保留半透明（流体贴图 alpha 230），写掩码保持 `COLOR_DEPTH_WRITE`。
实现位于 `client/StringRenderer` 的 `glowRenderType`。
贴图是竖向动画条（16x512 与 32x1024，各 32 帧）。
它由 `client/animation/AnimatedStripTexture` 按 `.mcmeta` 的 `frametime` 逐帧上传。
上传结果是一张一帧大小的动态贴图，UV 的 0..1 才正好覆盖一帧。

**不能直接用原版 `RenderType.entityTranslucentEmissive`**：它的写掩码是 `COLOR_WRITE`，不写深度。
1.21 的绘制顺序是「实体 → 半透明区块层（流体在这一层）→ 云」，后两者在实体之后按深度测试混合。
所以不写深度的实体会被这两层整片盖住，看起来像躲在流体与云后面。

待机音要持续播放，因此不走原版那条「每隔一段随机间隔掷一次」的路径。
`getAmbientSound` 返回 null，改由 `tickAmbientSound` 按音效长度排期。
`string_ambient` 的三个变体取自同一段素材且长度一致（413192 采样 / 44100 Hz = 9.37 秒 = 187 tick）。
当前这条还剩 8 tick 时开始下一条，互相叠着听不出断点。
音高固定为 1.0，因为变调会改变实际时长，排期就对不上了。

`gametest/StringEntityTests` 手动执行「控制器写速度 → 原版 air 分支」这两个动作。
它断言每 tick 都推进、到位即停、推进量随速度属性变化。

### 弦的三个变种

`StringEntity.Kind` 有三个值，序号写入同步数据 `DATA_KIND` 与存档 `Kind`。
三种变种共用模型、贴图与属性，区别只在运动系统与索敌范围；运动系统由 `applyKind` 整套装配，能沿用原版实现的一律沿用。

**滑行型**最简：贴附液面，`StringMoveControl` 只提供水平推进（每 tick 推一次，到达 0.35 格即视为到达）。
垂直位置由 `keepOnFluidSurface` 固定。
只攻击脚边一格存在流体的玩家。

**低空飞行型**整套沿用蜜蜂：

- 移动：`moveControl = new FlyingMoveControl(this, 20, true)`（原版类，蜜蜂用的就是它）与 `FlyingPathNavigation`。
- 追击：原版 `MeleeAttackGoal`。它自己算三维寻路路径，空中与岸上的玩家都追得到，索敌不判流体。
- 游荡：`FlyerWanderGoal` 照 `Bee.BeeWanderGoal` 的写法，当 `navigation.isDone() && nextInt(10)==0` 时
  选一个液面上方两格的盘旋点，再调 `navigation.moveTo(navigation.createPath(...), 1.0)`。
- 回家：目标消失（被击杀或逃离）后由 `ReturnToFluidGoal` 带回液体上空。
  距离远时寻路飞回，距离近时按蜜蜂在巢口悬停的方式每 tick 给一次 0.35 的目标点。
- 飞行：`travel` 沿用 `FlyingMob#travel`（恶魂与幻翼的父类）的写法，**整段不扣重力**。
  `checkFallDamage` 沿用 `FlyingMob` 写成空实现。

**仅靠 `setNoGravity(true)` 保持漂浮不够**：重力标记一旦被读档或其他代码改写，这个生物就会自由落体。
而且俯冲追击时 `fallDistance` 会照常累计，落地时结算摔伤。
传送门流体不在 `#minecraft:water` 里，所以进入传送门流体也不会像原版水那样把 `fallDistance` 清零。

**游动型**整套沿用鱼类：

- 移动：`moveControl = FluidMoveControl`，参照 `AbstractFish.FishMoveControl` 编写。
  眼睛浸在流体里时每 tick 补 0.005 浮力，速度按 0.125 的比重渐变，按目标点补垂直推力，且只改变朝向。
- 游动：`travel` 参照 `AbstractFish#travel` 的水下分支，以 `moveRelative(0.01)` 提供推力。
  `move` 执行移动，整体乘 0.9，没目标时缓慢下沉。
- 判定：原版那套 `isInWater()`（`#minecraft:water` 标签）换成本模组的传送门流体判定，这是唯一的改动。
- 范围：`keepSubmerged` 把脚的位置限在「池底往上 0.2」与「液面往下 0.9」之间。

该变种只攻击浸在流体中的玩家。

### 索敌、生成与分裂

三种变种**只主动索敌玩家**（`NearestAttackableTargetGoal<Player>`，`isValidPrey` 里再判一次类型）。
自然生成只产生滑行型：`world/StringSpawner` 每 5 秒扫描列，玩家周围 48 格内以 5 只为上限。
低空飞行型与游动型只由滑行型分裂产生。

滑行型每次受到**高于 2 点**的伤害时有 **10%** 概率裂成一对：
一只低空飞行型（抬高 1.5 格悬空）与一只游动型，同时自身 `discard`。
用 `discard` 而不是 `die`，因此不掉落战利品、不计入击杀、不播放倒地动画，只伴随末影人传送音与 60 颗传送粒子。
两种新变种不会二次分裂。

`item/StringSpawnEggItem` 只能放在传送门流体旁边。
三条发放路径都先经过 `StringEntity.canSpawnAt` 判定，即落点那一格是流体或其正下方是流体，否则不消耗刷怪蛋：

- `useOn`：对方块使用；
- `use`：对流体源格使用；
- `createDispenseBehavior`：发射器。默认实现在 `DeferredSpawnEggItem` 里是私有的，因此重写了一份。

### 高塔恶魂

`entity/TowerGhastEntity` 的模型、动画与贴图都是 Yes Steve Model 的 Bedrock 文件。
它们按模型包原样放在 `assets/dawnduskterminal/ysm/tower_ghast/` 下，运行时解析，不做离线转换。

运行时是仓库自带的 Molang 版 GeckoLib 分支（`geckolib3/` 与 `molang/`，来源 TouhouLittleMaid，MIT）。
`ysm.*` 与 `fn.*` 两张表分别在 `client/animation/YsmBinding` 与 `FnBinding`。

主动画选哪个片段由实体自己决定（`TowerGhastEntity.mainAnimation`：死亡压过受击，受击压过移动）。
分层由 `client/animation/YsmAnimatable` 按 YSM 的顺序注册：`pre_parallel0..7` 最先，主动画居中，`parallel0..7` 最后。
最后一批是**叠加**在主动画之上的加法混合。

缩放取自包内 `ysm.json` 的 `properties.height_scale`。

### 大恶魂

`entity/GhastEliteEntity` 是暮色恶魂的精英形态，继承 `Ghast`。
碰撞箱取几何中的主立方体 `thehead`：128 单位见方，即 8×8×8 格，眼高 4 格。
生命 250，索敌范围 100 格，护甲 10，击退抗性 0.9，击杀经验 317（与暮色森林 `UrGhast` 取值相同）。
本类不实现暮色恶魂的「改变天气」与「伤害减免」两项机制。

索敌使用常规 `NearestAttackableTargetGoal<Player>`，范围取自 `FOLLOW_RANGE` 的 100 格，不限定高度差。
受击之后不再立即反击，索敌对象的更新完全由该 goal 周期执行。

自然生成时向 `GhastElitePopulation` 申请名额：该类以维度为键保存快照，加载范围内已有一只即拒绝申请。
因此同一维度的加载范围内最多一只。
群系表只在 `old_growth_birch_forest` 的 monster 表登记该实体，权重 1。
单批数量沿用原版 `Ghast#getMaxSpawnClusterSize` 返回的 1；刷怪蛋与刷怪笼不受容量限制。
掉落为砷铅铁 1~4、恶魂之泪 1~3、高塔恶魂皮 1~4、生恶魂肉 1~4 与火药 0~3，均带抢夺加成。

**存在索敌对象期间按持久处理**（`requiresCustomPersistence`）。
该实体不主动接近目标，`noActionTime` 因而无法归零。
原版「离玩家超过 32 格且 30 秒无动作即掷骰删除」的规则会使其在交战后半分钟内消失。
脱战之后索敌对象在 100 格外被 `TargetGoal` 释放，随即回到原版消失规则，不会长期占用容量名额。

**该实体必须参与碰撞**。`AbstractHurtingProjectile#canHitEntity` 要求目标不是 `noPhysics`。
不参与碰撞时火球无法命中本体，拨开火球机制整体失效，同时本体可能穿入地面。

移动控制器沿用父类构造函数装入的原版 `GhastMoveControl`，其整条直线碰撞预检保持不变。
`RandomFloatAroundGoal` 补充了进度判定：目标距离连续 30 tick 未缩短时视为该点不可达并重新选点，
避免碰撞预检失败之后本体停在原地。

AI 由下列 goal 组成。

- 优先级 `4`：`entity/AvoidHazardousFluidGoal`，发现脚下的危险流体即退开，见 `portal.md`。
- 优先级 `5`：`RandomFloatAroundGoal`，以 16 格范围内的随机点作为下一段航线终点。
- 优先级 `7`：`EliteLookGoal`，设定本体的 `yRot`。
- 优先级 `7`：`entity/GhastShootGoal`，负责蓄力、开火与冷却。
- 索敌选择器优先级 `1`：`NearestAttackableTargetGoal`，周期性寻找 100 格以内的玩家。

`EliteLookGoal` 是原版 `GhastLookGoal` 的等价实现，原类为包私有内部类，无法直接引用。
该 goal 在无索敌对象时使本体朝向飞行方向，存在索敌对象且距离在 64 格以内时朝向该对象。
缺少该 goal 时本体在出生后不再转向，出膛点与火球朝向将固定为出生时的取值。

音效全部取自项目自有的 `ghast_universal_*` 一组。
常驻音、受伤音与死亡音分别覆写 `getAmbientSound`、`getHurtSound` 与 `getDeathSound`。
蓄力声与喷吐声由 `entity/GhastVoice` 播放，见下一节。
拨开火球时另播 `ghast_universal_deflect`。

### 射击与拨弹

射击与拨弹与「恶果」共用同一份实现。

`entity/GhastShootGoal` 是原版 `GhastShootFireballGoal` 的骨架。
蓄力满 20 tick 之后开火；蓄力计时超过 10 时视为正在喷火，开火时该计时置为 −40，即冷却 40 tick 之后重新蓄力。
仅在 64 格以内且视线可达时开火。
蓄力声与喷吐声由 `entity/GhastVoice` 播放，分别为 `ghast_universal_charge` 与 `ghast_universal_shoot`。
出膛点与单轮发数通过两个函数式接口传入。

大恶魂单轮三发：三发在同一 tick 内出膛，出膛点同为 `mouthPosition` 返回的那一点，三发等高。
第一发直接瞄准目标碰撞箱的中心；其余两发仅对瞄准向量的 x、z 分量施加 `(rand - rand) * 8` 的抖动。
瞄准向量自嘴部起算，而非自本体中心起算。

它的火球是 `entity/GhastEliteFireball`，与原版火球存在四处差异。

- `canHitEntity` 排除一切 `AbstractHurtingProjectile`，避免同一轮齐射在途中相互抵消。
- `onHitEntity` 为空实现，不造成 6 点直接命中伤害。
- 爆炸威力为 2，原版恶魂为 1。
- 爆炸对实体的伤害由该实体自行结算。
  所用伤害类型 `dawnduskterminal:ghast_elite_explosion` 列入 `minecraft:bypasses_cooldown`。

自行结算的原因是原版爆炸伤害经 `LivingEntity#hurt` 结算，目标处于 10 tick 受击冷却时该次伤害被拒绝，
一轮三发将只有第一发生效。
原版爆炸仍负责破坏方块、粒子、音效与击退，其对实体的伤害经 `shouldDamageEntity` 关闭。
自行结算取原版公式，与原版存在两处差异：遮挡比为 0 时不结算伤害；不调用 `Entity#ignoreExplosion`。

出膛点取 `mouthPosition`：位于脚部上方 1.9375 格、水平朝向前方 4.125 格。
该数值取自几何：嘴部模型坐标为 `(0, 31, -66)` 像素，`thehead` 的脸面位于 z=−64 单位，嘴再向前 2 单位。
竖直方向固定，因为头骨没有俯仰通道。

火球登记于 `data/minecraft/tags/entity_type/redirectable_projectile.json`。
原版 `Player#attack` 仅对 `#minecraft:redirectable_projectile` 内的弹体执行 `AIM_DEFLECT`。
缺少该标签时，持剑攻击只会命中空气，火球仍按原轨迹飞行。

拨开火球挂于 `ProjectileImpactEvent`，实现见 `entity/GhastDeflect`。
事件取消之后，`onHitEntity` 的 6 点伤害与爆炸均不发生，火球本身仍然存活。
随后火球被移至本体中心外 7.5 格（8 格碰撞箱的半对角线约 6.93 格），并按当前索敌对象改向。
不存在索敌对象时沿来路折返。该过程播放一声 `ghast_universal_deflect`。

两张脸的薄片由同步数据 `DATA_FACE` 决定：`annoyed` 表示存在索敌对象，`atack` 表示正在蓄力。
服务端每 tick 将 `isCharging()` 折算进去，客户端只读取该字段。
薄片名称取自模型包，拼写即为 `annoyed` 与 `atack`。

### 泪滴与召唤

常驻技能为泪雨：每 tick 生成一颗 `TFParticleType.BOSS_TEAR` 粒子，以 `longDistance` 方式发给 512 格内的玩家。
伤害每 10 tick 结算一次，判定范围为本体碰撞箱向下 16 格、竖直方向再放大 16 格、水平方向不放大。
仅位于可见天光处的玩家会被命中，每次 3 点，伤害类型为暮色森林的 `twilightforest:ghast_tear`。
该判定只针对玩家：小鬼不受影响，本体也不会因此恢复生命。

一次性技能为召唤：第一次取得索敌对象时，于本体下方水平半径 3 格的方形区域内排入 6 只
`twilightforest:carminite_ghastling`，并播放 `ghast_universal_charge`。
每个条目携带 0~59 tick 的随机延迟，各只依次出现，不会在同一 tick 内同时生成。
生成位置在落地那一刻按本体当前位置计算；落点若与方块相交，则每次抬高 2 格，最多尝试 8 次。
生成流程依次执行 `makeBossMinion`、`EventHooks.finalizeMobSpawn`、`checkSpawnRules(MOB_SUMMONED)`，
随后写入 `MINION_TAG` 与持久标记，加入跟随 goal，最后执行 `addFreshEntity` 与 `spawnAnim`。

小鬼带有持久标记，本体存活期间不会自然消失。
本体死亡之后，跟随 goal 在 `stop()` 中将小鬼解散，避免其永久滞留于该区域。

每 20 tick 照看一次 48 格内的小鬼：对没有索敌对象的小鬼补发本体的索敌对象。
暮色森林的 `NearestAttackableTargetGoal` 在失去视线时会清空 `target`，因此需要周期性补发。
移动由每只小鬼自己的 `MinionFollowGoal` 负责：以本体中心为圆心、半径 6 格环绕飞行。
该 goal 的优先级为 4，小于暮色森林两个飞行 goal 的 5；否则在 `MOVE` 标志上无法取得控制权。
小鬼的挑人规则见下一节。

### 小鬼的挑人规则

是否攻击目标由暮色森林自身的 `CarminiteGhastling#shouldAttack` 决定：

- 玩家头顶戴着雕刻南瓜：不攻击；
- 距离在 3.5 格以内：只要视线可达即攻击；
- 更远：要求**玩家的准星对准该小鬼**（`dot(玩家视线, 指向它的方向) > 1 − 0.025/距离`）且视线可达。

按原规则，侧身经过的玩家不会被追击。
因此召唤时会为小鬼写入 `persistentData` 标记 `dawnduskterminal:elite_minion`。
`mixin/GhastlingShouldAttackMixin` 对带有该标记的小鬼在 `shouldAttack` 开头直接放行，但仍要求视线可达，
以免隔着树木开火。
自然生成的砷铅铁恶魂守卫不带该标记，仍遵循原规则。
`@Mixin` 以暮色森林的类字面量作为目标，类名变更时编译期即报错。

### 大恶魂的模型

模型按统一布局放在 `geo/ghast_elite.geo.json`、`animations/ghast_elite.animation.json`、
`textures/entity/ghast_elite.png`，三份都是模型作者的原件，逐字节未改写。
动画只有两条片段：`parallel0` 是七根触手与四肢的柏林噪声摆动。
`parallel1` 是一段每帧执行的 `timeline` 程序（布条与触手的链式物理）。
两条都由并行动画控制器逐帧求值，没有主动画。

`parallel1` 里的布条物理是这套模型里唯一「必须执行代码才能得到」的部分，也是这套运行时存在的理由。
`ysm.first_order` / `second_order` 是按名字记忆状态的递归滤波器。
`ysm.bone_rot` 读上一帧的骨骼变换，`ysm.bone_pivot_abs` 要按当前旋转重算链条的绝对 pivot。
三者都跨帧，没有无状态的等价物。

物理按**渲染帧**推进（步长取帧间隔，与 YSM 的 `PhysicsManager.update` 一致），不是按 tick。
源程序里的 `first_order(…, 0.02)` 与 `0.94·上次目标` 都按帧积分，按 1/20 秒推进会自激、幅度翻倍。

有三条语义与 YSM 对齐：

- `math.sin/cos` 的入参是**度**；
- 欧拉角按 ZYX 序。换成 `getEulerAnglesXYZ` 会让 40 度以上的角度在轴间错位；
- `ysm.bone_pivot_abs` 返回的是 Bedrock 坐标（x 不取负）。
  源表达式自己写了 `-ysm.bone_pivot_abs(...).x`，这里再取负会让辅助骨骼的基矩阵变成反射，触手翻到 90~150 度。

`fn.*` 五个四元数函数用 JOML 按标准语义重建：包里没有 `functions/` 目录，定义无从取得。
名字按小写登记，因为词法层会把标识符转成小写。

### 活盔甲

`entity/LivingArmorEntity` 是一只本体不可见的 Monster，穿一整套随机护甲、拿一件随机武器。

**举盾只在两种时候发生**，其余时间一律收着：

- 对手活着、距离不超过 `MELEE_REACH`（近战 3 格）、而且在自己头朝向的正面（点积 > 0）；
- 或者 60 tick 内挨过打（远处射来的箭也算）。

逃跑时目标被清空、正面也朝着逃跑方向，所以不会出现「背对着人举盾」。

挡不挡得住交给原版 `LivingEntity#isDamageSourceBlocked`：只有正面来、不在
`#minecraft:bypasses_shield` 里、且不是穿透箭的伤害挡得住。`isBlocking()` 被覆写成
「副手有盾 + 没被斧头封 + 不在出手后的 8 tick 窗口里」，与物品使用状态解耦
（那个状态每 tick 会被 `updatingUsingItem` 校验一次，中途被清掉时盾会静悄悄不挡）。

挡下之后的反馈：`playHurtSound` 里按 `blockedThisHit`（这一下真的被盾挡下）改播 `item.shield.block`
（原版 `hurt` 里那句 `playHurtSound` 是无条件的，会照样播受伤音；背后挨打、破盾那一下、斧头破盾之后
都是「有盾但没挡住」，照旧播受伤音），`handleEntityEvent` 吞掉 entity event 29
防止重复；`knockback` 在 `blockedThisHit` 窗口内直接返回；`hurtCurrentlyUsedShield` 按
`Player` 那条磨耐久（≥3 伤害时 `1 + 伤害取整`）；`blockUsingShield` 按 `Player` 那条问
`attacker.canDisableShield()`，斧头就把盾封 100 tick。

移动上有两条限制：不能疾跑（`LocalPlayer` 730 行的疾跑条件里有 `!isUsingItem()`）；
举盾时把 `getSpeed()` 乘 `GUARD_SPEED_FACTOR`，位置与玩家的 `input * 0.2F`
（`LocalPlayer` 695–699）对应。**不能拿移动速度属性做这件事**：实测属性 0.3 → 4.73 格每秒、
属性 0.06 只剩 0.05 格每秒，属性到实际位移之间不是线性的；改成缩 `getSpeed()` 之后，
实测寻路追击时举盾速度是走速的 0.178（`gametest/LivingArmorTests#guardSpeedIsAboutTwentyPercentOfWalk`）。
系数取 0.3 而不是 0.2，是因为「输入 → 位移」这一段本身也不是线性的。

出手（近战、斧头、链锤）会先把盾收起来 `SHIELD_SWING_LOWER_TICKS` 个 tick：这一段既不挡、
不减速、也不摆举盾姿势。

客户端照着 `PlayerRenderer#getArmPose` 给 `ArmPose.BLOCK`，盾是立在胸前的。
`gametest/LivingArmorTests` 钉住：有盾就算挡着（正面挡住、背面挡不住、盾牌掉耐久、
斧头破盾、`falling_anvil` 穿盾、空手一拳也被挡）、出手 8 tick 内不挡、举盾掉速、
以及 AI 全开时照样挡得住。


## 装备与武器

### 材质与套装

`registry/ModArmorMaterials` 注册护甲材质：绯色钼、苍穹与砷铅铁为钻石级，天门松为铁级。

另有一份 `experiment_armor`，供试验物品 047~050 那一套使用：护甲值与铁同级（2 / 6 / 5 / 2，合计 15）。
每件提供 2 点韧性，附魔值为 9，耐久倍率为 15。
维修材料为砷铅铁锭、绯红钼锭与䤧锭中的任意一样（`Ingredient.of` 接收候选清单，命中其中一个即成立）。

`registry/ModTiers` 注册工具分级，同样是四份（绯色钼只有护甲，没有工具）。

`registry/ModEquipment` 按套装注册工具与护甲，六套共 54 件，按套装放在 `Map<String, Set>` 中，不逐件声明字段。
绯色钼的护甲带有套装效果，其物品位于 `ModItems` 中，该套装只把已有的 holder 拼接进来。

`item/ScarletMolybdArmorItem` 只为绯色钼这一套使用：四件全部穿戴时，每件在 `inventoryTick` 中自行续期生命恢复效果。

### 绯色钼的穿戴外观

这一套不用原版通用护甲层，而是用源模组（twilightextra）的四件专用模型。
`client/model/ScarletMolybdenumArmorModel` 把四份 `LayerDefinition` 逐颗方块照搬：
`Modelcarmhelmett1`、`Modelcarmchestplatet1`、`Modelcarmleggingst1`、`Modelcarmbootst1`。

它们注册为四个 `ModelLayerLocation`，再按槽位拼进 `HumanoidModel`。
对应部位换成专用部件（头 / 身 双臂 / 身 双腿 / 双腿），其余部位填空的 `ModelPart`。
拼好的模型经 `RegisterClientExtensionsEvent` 的 `IClientItemExtensions#getHumanoidArmorModel`
交给原版 `HumanoidArmorLayer`。

护甲层每帧先通过 `copyFrom` 把玩家模型的姿势复制到那个 `HumanoidModel` 的同名部位上。
所以专用部件的基准位置必须与原版对齐：头、身 0,0,0，臂 ±5,2,0，腿 ±1.9,12,0。
源模型的数值本来就按这套基准编写。

贴图是 `textures/entities/carmarmort1.png`（64×64，一件套一张，不分内外层）。
由 `ScarletMolybdArmorItem#getArmorTexture` 返回，因此不走 `ArmorMaterial.Layer` 拼出的 `textures/models/armor/...`。
原来那两张通用层贴图已经删掉：它们的 UV 布局与这套几何不匹配，先前通用模型看起来错位正是这个原因。

这一套也不接受护甲纹饰，两条路径都堵住了：

- 锻造台：本套不在 `#minecraft:trimmable_armor` 中，纹饰配方的 base 匹配不上。
- 已带 `TRIM` 组件的物品栈（来自命令、其他模组或旧存档）：由 `ScarletMolybdArmorItem#verifyComponentsAfterLoad` 移除。
  移除的时机是加载、网络同步与穿戴时。纹饰层按原版方块贴图排列，叠在 64×64 UV 的专用几何上只会错位。

`gametest/EquipmentTests` 断言：四件在内外两种层上都返回那张专用图，且图本身在 jar 里。
以模板、红石与本套部件查不出任何锻造配方；带纹饰的物品栈经 NBT 往返一次后组件消失。

### 砷铅铁钼反应权杖

`item/CarminiteStaffItem` 的近战行为按单手武器计算，右键使用消耗一颗砷铅铁球并发射一颗 `entity/CarminiteBallEntity`。

六种附魔加成沿用源模组的换算（力量、火矢、冲击、多重、穿透、快速装填）。
该物品挂在 `data/minecraft/tags/item/enchantable/` 的 `bow` 与 `crossbow` 两个原版标签上，否则无法附魔。

`entity/CarminiteBallEntity` 继承 `AbstractArrow`，命中后在其上方一格留下 `entity/CarminiteFieldEntity`。

`entity/CarminiteFieldEntity` 是普通的 `Entity`（不是生物），存活 75 tick。
它每 20 tick 对半径 4、高 ±1.5 的圆柱范围内的生物结算一次伤害，并每 tick 在边缘绘制一圈 12 个粒子。
源模组将其实现为一只无 AI 的飞行生物，作用范围只能使用方盒，这里均不需要。

`registry/ModParticles` 注册三种粒子：`particle_carminite_ball`、`_trace`、`_effect`。
客户端 provider 位于 `client/CarminiteParticle`。

`registry/ModDamageTypes` 的 `carminite_field` 是反应场伤害，其条目位于 `data/dawnduskterminal/damage_type/`。

这些内容从 `twilightextra`（由 MCreator 生成）迁移而来，原代码中的过程式实现已替换为上述实现。

装备贴图目前是原版铁、钻石贴图叠加色阶得到的占位图；
待正式美术资源到位后，替换 `textures/item/` 与 `textures/models/armor/` 中对应前缀的文件即可。

## 试验物品

序号表在 `registry/ModExperimentItems`，`TAKEN` 列出已经占用序号的条目：

- 001：可以当作合成台使用的方块，位于 `ModBlocks`。
- 007 / 017：两件裸武器，近战伤害 4、攻速 5，共用 `meleeAttributes` 属性表，只挂属性不挂 tool tier。
- 008：食物，5 点饥饿与 5 点饱和度。
- 011 / 012：一只盘子与一只杯子，见下面「试验物品 011」与「试验物品 012」。
- 019：一丛植物，见下面「试验物品 019」。
- 020：食物，效果表在使用的那一刻随机生成，见下面「试验物品 020」。
- 032 / 033：两支突刺长矛，行为在 `item/ThrustSpearItem`。
- 037~042：钻石五件套与一件普通物品。
- 043：喷火器（`item/Experiment043Item`）。
- 045：盾（`item/Experiment045Item`，盾面在 `client/Experiment045ShieldRenderer`）。
- 046：砷铅铁钼反应权杖。
- 047~050：护甲（`item/ExperimentArmorItem`，见下面「试验物品 047~050」）。
- 051：拆解台，方块在 `ModBlocks`、物品在 `ModItems`。
- 060：金探针（`item/Experiment060Item`），右键往下探石头与沙土层下面第一块别的东西，见下面「试验物品 060」。
- 061 / 070 / 074：三块不明肉块（`item/UnknownMeatItem`，共用一份规格）。
- 066：恶魂陷阱书（`item/Experiment066Item`）。
- 104：食物，位于 `ModItems`。
- 107：整块红丝绒蛋糕（`item/Experiment107Item`，方块仍是 `sus_redvelvet_cake`）。
- 114：既能食用又能摆放的那一块，方块在 `ModBlocks`、物品在 `ModItems`。
- 115：由暮色森林自带，本模组不注册。

007 与 017 的修饰符 ID 按物品 ID 区分，同一属性上两个同 ID 修饰符会互相覆盖。
玩家底数为攻击力 1、攻速 4，所以面板 4/5 对应 +3/+1。

008 的系数为 0.5。`FoodProperties` 存的是饱和度系数，点数 = 系数 × 饥饿 × 2。

剩余空号分两档。
已经有自己那张图标的不算占位，登记在 `ModExperimentItems.TEXTURED`，各自注册成普通物品。
判据是 `models/item/experiment_%03d.json` 的 `layer0` 指向自己的贴图。
真正还借 `experiment_temp.png` 的才是占位，由 `ModExperimentItems.PLACEHOLDERS` 补出。
两张表加起来一共 72 个号，即 32 个有贴图的加 40 个占位，用 `simpleItems()` 取。
`gametest/ExperimentItemTests#placeholderTableMatchesTheIcons` 把两张表与 115 份模型逐号对上，
换了某个号的图标却忘了写进 `TEXTURED` 会直接红。

创造栏按序号升序排列，其中 115 从暮色森林的注册表取，未安装时跳过。
试验物品栏通过 `withTabsBefore(dawnduskterminal:main)` 固定在主栏目之后。
NeoForge 的 `CreativeModeTabRegistry` 对未声明 before/after 的栏目按注册名字母序排序，注册先后不参与，
否则其他模组的栏目会插进本模组两个栏目之间。

### 试验物品 011（盘子）

盘子是一张 14 像素见方、1 像素厚的薄片，不是方块：

- 实体 `entity/Experiment011PlateEntity`，碰撞箱 `14/16 x 1/16 x 14/16`，无重力、不会动。
- 物品 `item/Experiment011Item` 只管摆：只认朝上的面，高度取脚下方块碰撞箱的 `bounds().maxY`，
  所以半砖上摆 0.5、满方块上摆 1.0；那 1 像素的空间被别的方块占住时不摆。
- 外形在 `client/Experiment011PlateRenderer`：贴图是本模组的 `textures/block/experment_011.png`
  （文件名沿用源文件，比 experiment 少一个 i），盘面截中间 14x14，四个侧面只取一行像素。
- 盘上摆的那一件存在同步数据 `DATA_DISPLAYED` 里，存档键为 `displayed`，只占一格。
- 展示尺度统一为 12 像素，而且画的是**真模型**：`BlockItem` 交给方块渲染器，摆成坐在盘子中央的小方块；
  其余物品交给 `ItemRenderer#renderStatic`（`ItemDisplayContext.FIXED`），
  并且照原版**朝上的物品展示框**摆：`XP(-90)` + `YP(180)`，于是物品正面朝上、平躺在盘面上。
  所以盾（`builtin/entity` 的自定义渲染器）、剑、弓、旗帜都按自己的模型画，
  不是拿一张 `particle` 贴图糊在盘子上。
- 两条路的原点约定不同：方块模型占 `[0,1]`、方块渲染器不自己挪，所以要 `translate(-0.5, 0, -0.5)`；
  物品模型在 `ItemRenderer#render` 内部已经 `translate(-0.5, -0.5, -0.5)` 过，
  这里再挪一次就会偏半个方块、还埋进盘子里一半。
- 右键：手上有东西就摆上去，空手取回那一件，潜行空手收回盘子本身；左键砸掉连展示物一起掉。

### 试验物品 012（杯子）

`block/Experiment012Block` 继承 `BushBlock`，一格最多三只，多出来的是**横着错开摆**的：

- 方块状态只有一个 `cups`，1~3。
- 摆位照原版海泡菜那三份模型（`sea_pickle` / `two_sea_pickles` / `three_sea_pickles`）的 from 角：
  一只在正中，两只走对角 (3,3) 与 (8,8)，三只是 (6,9)、(2,2)、(8,4)。
  杯子都是 4x6x4、都坐在格子底面上、杯口一样高，三只脚印互不重叠，不往上摞。
- 碰撞箱按摆位取并集，每只 4x4 高 6 像素。
- 拿着杯子点已经摆下的杯子就是再加一只，靠 `canBeReplaced` 放开「同物品点自己」这条，
  再由 `getStateForPlacement` 把 `cups` 加一。少了 `canBeReplaced`，点自己不会有任何反应。
- 模型 `block/experiment_012_1/2/3` 就是同一只杯子按上面那几组坐标平移出来的，
  三份都写 `"render_type": "minecraft:cutout"`。
- 三份模型的贴图都是 `textures/block/experiment_012.png`（杯子的展开图：边带 5~11 行、顶面 1~5 行，
  与海泡菜那份 UV 布局一致，所以坐标不用改）；物品图标另是 `textures/item/experiment_012.png` 一张。
- 掉落按 `cups` 掉 1~3 个，由 `loot_table/blocks/experiment_012.json` 的 `block_state_property` 分档。

### 试验物品 019

`block/Experiment019Block` 继承 `BushBlock`，只可立于 `#minecraft:dirt` 与耕地上，下方的土消失后自身随之消失。

方块模型 `block/experiment_019` 使用 `minecraft:block/cross`，并加 `"render_type": "minecraft:cutout"`。
遗漏这一行，透明像素会被涂成黑底，整丛会变成一块黑方块。
原版花用的 `ItemBlockRenderTypes.setRenderLayer` 自 1.19 起废弃，模组方块一律在模型里写 `render_type`。

贴图直接引用 `item/experiment_019` 那张，物品图标仍用 `item/generated`，与原版花一致。
掉落自身由 `loot_table/blocks/experiment_019.json` 定义。

### 试验物品 020

食物，提供 20 点饥饿与 20 点饱和度。效果表在使用的那一刻随机生成，实现位于 `item/Experiment020Item#rollEffects`：

- 0~3 个效果，互不相同，各持续 10~300 秒，等级 I；
- 候选池剔除瞬发的三个；
- 候选池另剔除那些在非原版中不存在药水瓶的技术性效果，判据是扫描 `BuiltInRegistries.POTION`。
  药水瓶显示的效果就取自这张表，原版不套这条判据，`bad_omen` 这类效果照旧保留。

随机源作为参数传入，以便测试。
`FoodProperties#effects` 无法承载随机表，也放不了随机表，所以只能在物品里现摇。

### 试验物品 032 / 033（突刺长矛）

两个注册共用同一份 `item/ThrustSpearItem`。

`getUseAnimation` 返回 `UseAnim.BOW`，第一人称的拉满过程与第三人称的双臂拉弓动作都交给原版播放。
因此物品模型必须配同一套变换：两张贴图是按弓的朝向绘制的。
所以 `models/item/experiment_032.json` 与 `experiment_033.json` 参照原版 `item/bow` 写了一份 `display`。
thirdperson 为 [-80,260,-40] / [-80,-280,40] 与 -1,-2,2.5 的位移，firstperson 沿用 generated 那一套。
挂 `item/generated` 而不提供变换，模型会变成一张平片；挂 `item/handheld` 则会把矛按工具的方式拿反。

视野不缩放。原版压低 FOV 的条件在 `AbstractClientPlayer#getFieldOfViewModifier` 里，
即 `getUseItem().is(Items.BOW)`，它识别的是物品本体，与非弓物品无关。

`getUseDuration` 为 72000，按住右键不会自行结束。
`onUseTick` 每 10 tick 选一次目标，四个条件同时成立：
`entity_interaction_range` 属性范围内、视线两侧各 30 度锥体中、有视线连通、与视线的垂直距离最小。
命中后调用 `hurt` 造成 1 点伤害，并播放 `entity.player.hurt_sweet_berry_bush`。

伤害类型直接用原版的 `minecraft:sweet_berry_bush`：属于普通伤害，护甲照常减伤；
它本身就在 `minecraft:no_knockback` 标签里，所以只有伤害没有击退。
死亡信息与原版一致，本模组不另写条目，物品也不带描述行。
无敌帧没有改动：两次伤害相隔 10 tick，由原版既有逻辑自行计算。

### 试验物品 104（两段式食物）

`ModItems` 里的一件普通 `Item`，只挂了 `Item.Properties().stacksTo(1).food(...)`，可以吃两次。

第一次使用时 `usingConvertsTo` 把剩下的一半转成 `experiment_104_half`。
它沿用原版蜂蜜瓶转玻璃瓶的路径，即在 `Player#eat` 中替换物品栈；第二次使用时消耗那半块。

每次提供 10 点营养与 0.6 的饱和度系数（10 × 0.6 × 2 = 12.0，相当于两块烤马铃薯），两次合计 20 与 24.0。

半块同样是 `stacksTo(1)` 的食物，但没有 `usingConvertsTo`。
它不进试验物品栏（那一栏只排 001~115），只能靠食用产生。
半块必须注册在 104 之前：104 的 `usingConvertsTo` 在注册回调里就要拿到半块的物品栈。
而 `DeferredRegister` 按插入顺序注册。

### 试验物品 061 / 070 / 074（不明肉块）

三块共用 `item/UnknownMeatItem.FOOD` 这一份 `FoodProperties`，只差贴图。
数值是 2 点饥饿与 0.3 的饱和度系数，即 2 点饥饿、1.2 点饱和度，与生鸡肉、生羊肉逐项相同。

吃下时以三成概率给 30 秒饥饿（照原版生鸡肉那一条），另有一次独立的三成概率给 5 秒中毒（时长照原版毒马铃薯）。

两条效果都写在 `FoodProperties#effects()` 里，由原版 `LivingEntity#addEatEffect` 在服务端逐个判定。
判据是 `random.nextFloat() < probability`，因此不另写物品类去摇效果。
020 之所以要现摇，是因为那张效果表本身要随机；肉块这张是固定表。

效果用 NeoForge 的 `Supplier` 重载登记。
原版的 `effect(MobEffectInstance, float)` 在 NeoForge 上已标为废弃，而 `MobEffectInstance` 到吃下去那一刻才需要存在。

描述行是「不明肉块」，键由物品自己的描述 ID 加 `.desc` 拼出，与 066 那套写法一致。

### 试验物品 114（暮色森林 115 的复制）

114 的方块是暮色森林试验物品 115 方块（`twilightforest:experiment_115`）的完整复制。
两者状态相同，都是 `omnomnom` 0~7 与 `regenerate`（名称未改）；碰撞形状、比较器与红石输出公式也一样。
模型以及侧、底、切口三张贴图直接用 115 的：`twilightforest:block/experiment115/*` 与 `twilightforest:block/blank`。
顶面换成本模组的 `dawnduskterminal:block/experiment114_top`。

不同之处有五条：

- 摆放后即为满的 8 片（115 的默认态是 `omnomnom=7`，只剩 1 片）；
- 使用 114 时点一次缺口就补满（115 一次只补一片）；
- 潜行收走只对全满的方块生效（115 在吃到一半时也能收走）；
- 每消耗一片，额外随机播放一声恶魂受伤音效；
- 撒过红石粉的那一版不补片，改为在收到一次红石信号时随机播放恶魂受伤音效。

`item/Experiment114Item` 是 `BlockItem`，作为食物提供 32 点营养与 0.3 的饱和度系数，相当于 8 份 115（每份 4 与 0.3）。

`useOn` 参照 115：对自身那一块使用时把交互让给方块。
非潜行状态下执行摆放，摆不下时才转为食用；食用完成时在服务端以随机音高与音量播放 `entity.ghast.hurt`。

红石去重不靠额外的 `powered` 状态，而靠一次方块刻。
一次信号会引发多轮邻居更新，`neighborChanged` 发现这一格已经排入方块刻就不再发声。
这样状态集与 115 完全一致。

该方块同样没有 `randomTicks()`：115 的随机刻只用于给 `regenerate` 那一版补片，本模组那一版改成播放音效，随机刻无事可做。

资源是 `blockstates/experiment_114.json` 的 16 个变体（`omnomnom` × `regenerate`），
外加 16 个 `models/block/experiment_114_<剩几片>_8[_regenerating].json`。
模型内容从 115 的模型原样复制，只改两处：`_regenerating` 那八个的 `parent` 指向本命名空间，
八个基础模型的 `top` 指向 `dawnduskterminal:block/experiment114_top`。
`_regenerating` 只覆盖 `top_2`，顶面从父模型继承。

### 七种可疑蛋糕

`sus_<口味>_cake` 是 115 方块那一套的第二个衍生物。
口味有七种：`bloodorange`、`cheese`、`cherrycheese`、`lamington`、`pavlova`、`pineapple`、`redvelvet`。
方块在 `block/SusCakeBlock`，物品在 `item/SusCakeItem`，两者都按 `ModBlocks#SUS_CAKE_FLAVORS` 生成，ID 一一对应。

**一个物品就是一片**：摆下来是 `omnomnom=7`（只剩 1 片，与 115 的默认态相同），一块满的蛋糕为 8 片。

沿用 115 的部分：

- `omnomnom` 0~7 那套切口状态与四段碰撞形状；
- 比较器公式 `8 - omnomnom`；
- `noLootTable` / `pushReaction(DESTROY)` / `WOOL` / `strength(0.5)` 那组方块属性；
- 三条交互：
  - 拿同一块蛋糕点缺口一次补一片，满片时返回 `FAIL`；
  - 潜行点击一次从缺口取回一片。`omnomnom=7` 时整块取走，播放 `item.pickup` 并返还一件物品；
  - 空手点击食用一片，经由 `useWithoutItem`。

与 115 不同的有两条。

第一，一片提供的量写作 `SusCakeBlock.Serving`，点数 = 系数 × 饥饿 × 2。
默认的 `DEFAULT_SERVING` 是 6 点饥饿与 4 点饱和度（系数 1/3）。
血橙与红丝绒那一档是 `EIGHT_SERVING`：8 点饥饿与 8 点饱和度，系数 0.5。
这张表在 `ModBlocks#SUS_CAKE_SERVINGS`，而 115 是一片 4 与 0.3。

第二，**没有 `regenerate` 这个状态，也不需要撒红石粉**。
方块带 `randomTicks()`，`randomTick` 中只要 `omnomnom` 不为 0 就把片数减一；被随机刻抽中一次即补一片，不再做第二次判定。
速度与 115 撒粉那一版相同，默认 `randomTickSpeed=3` 下平均约 68 秒一片。
因此该方块没有 `isSignalSource` / `getSignal`，比较器公式里也没有 114 那个 `+7`。

资源为每种口味的 `blockstates/sus_<口味>_cake.json` 八个变体，外加八个 `models/block/sus_<口味>_cake_<剩几片>_8.json`。
模型从 115 那八个原样复制，只把顶、侧、底、切口四张贴图换成
`dawnduskterminal:block/sus_<口味>_cake_{top,side,bottom,inner}`。
其中 `top_2` 仍指向暮色森林的 `blank`，与 114 一致。
物品模型走 `item/generated`；红丝绒那五张贴图是临时占位图，由血橙那套按红色调生成，正式贴图到位后直接替换文件即可。

七种都进主栏目的食物那一档，没有战利品表。

配方是 `data/dawnduskterminal/recipe/sus_<口味>_cake.json` 六条无序配方（红丝绒暂无）。
原料都含 `twilightforest:experiment_115`：
血橙配试验物品 008，奶酪配牛奶，樱桃奶酪配牛奶与甜浆果，林明顿配可可豆与骨粉，帕夫洛娃配鸡蛋，反转菠萝配仙人掌。

`gametest/SusCakeTests` 断言：

- 七种一片默认提供 6 点饥饿与 4 点饱和度，血橙与红丝绒为 8 与 8；
- 摆放下来是最后一片；拿物品一次补一片，而满片时拒绝；
- 潜行一次取回一片，且最后一片时整块取走；
- 食用一片增加 6 点饥饿与 4 点饱和度；
- 随机刻补一片，而满片时不变；
- 比较器读取 `8 - omnomnom`，且不发出红石信号；
- 六条无序配方都能在 `RecipeManager` 中按 ID 取到，且原料与产出相符。

### 试验物品 107（整块红丝绒蛋糕）

物品 ID `experiment_107`，位于 `item/Experiment107Item`，继承 `SusCakeItem`；方块仍是 `sus_redvelvet_cake`。
状态、补片、取片与空手吃片与一片那件完全共用，差别只有两条：

- 摆下来就是满的 8 片：覆写 `getPlacementState` 把 `BITES_TAKEN` 置 0，而一片那件摆下来是 7；
- 不带食物组件：方块物品只负责摆放，要吃就照方块那一套一片一片吃。

名字走自己的键 `item.dawnduskterminal.experiment_107`（覆写 `getDescriptionId`）。
`BlockItem` 的默认名字取自方块，不换就会和那件一片的重名。

`registerBlocks` 被覆写成空实现。
`Item.BY_BLOCK` 由 `BlockItem#registerBlocks` 用 `put` 填，一块方块两件物品时后注册的会顶掉先注册的。
整块那件晚于七种一片注册，不拦住就会抢走方块自己那个 `asItem()`，而补片、取片、比较器几处全都指向整块。

物品模型直接以 `block/sus_redvelvet_cake_8_8` 为父，该模型挂 `block/block`、自带 GUI 变换。
因此图标是整块蛋糕而不是一片。
它排在试验物品栏的 107 号，不进主栏目。

### 115 再生机

`dawnduskterminal:e_115_regenerator` 是一台按 115 自身的补片速度生产 115 的机器。
方块在 `block/E115RegeneratorBlock`，方块实体在 `block/entity/E115RegeneratorBlockEntity`。

两个条件同时成立时才做随机判定：

- **侧面存在红石信号**；
- **顶上是一块完整并撒过红石粉的 115**（`omnomnom=0` 且 `regenerate=true`）。
产物格已满时停止工作。

顶面被故意排除在「侧面」之外。
满片且撒过粉的 115 自身就是信号源，`getSignal` 恒返回 `15 - omnomnom × 2` 并向四个方向发信号，
所以它压在机器上时 `Level#hasNeighborSignal` 永远为真。
判定只能逐面查询四个水平方向的 `Level#getSignal(邻居, 朝我)`。
红石粉、红石块、正对着它的中继器与比较器、贴在侧面的拉杆都计算在内，顶面与底面的读数一概不看。

顶上那一块不会被消耗，也不需要它处于「吃到一半、正在补片」的状态。
满片加撒粉就是触发条件，随机判定命中的那一次产出的是**新的** 115 物品。

状态名按字符串查找（`omnomnom`、`regenerate`）。
它们是暮色森林方块的状态属性名，没有可引用的编译期常量，方块本身按注册项引用。

速度按随机刻的机制推算，不靠主观估计。
原版随机刻对每个 16³ 区块段每 tick 抽取 `randomTickSpeed`（默认 3）个方块位置，任意一格每 tick 有 3/4096 的概率被抽中；
而 115 在 `regenerate=true` 且未满片时被抽中一次即补一片，不再做第二次判定。

因此方块实体每 tick 执行一次 `random.nextInt(4096) < 3`，命中即产出一份。
期望间隔 4096/3 ≈ 1365.3 tick，即 20 tps 下约 68.3 秒，与 115 自身补片完全等价。
该机器没有进度条，也没有可保存的进度，格子里有没有物品就是全部状态。

每次产出播放一声顶上那块 115 的放置音效（115 用羊毛音效，即 `block.wool.place`，与暮色森林给 115 补片时那一声同源）。
工作时 `animateTick` 在顶上那一格的随机位置生成一颗 `particle_carminite_ember`，每次 `animateTick` 一颗，不叠加概率。
客户端的 `animateTick` 本来就不是每 tick 都轮到某个方块，单个方块平均一秒只被选中两三次。

玩家手持 115 点击过来时，方块不抢占交互（`useItemOn` 返回 `SKIP_DEFAULT_BLOCK_INTERACTION`）。
原版的执行顺序本就是「物品的 `useOn` 先执行，摆放成功即结束」，所以顶面照常摆放物品。
走到方块这一层只剩「摆放不下」的情形，此时也不会同时打开界面。
只有空手点击才打开界面。

容器只有一格产物格。
漏斗**只从底面抽取**（`getSlotsForFace` 对其余五个面返回空表）。
任何面都无法放入物品（`canPlaceItem` 恒为 `false`），比较器按填充度输出。

界面是 `inventory/E115RegeneratorMenu` 与 `client/E115RegeneratorScreen`。
面板用本模组那张 `textures/gui/e_115_regenerator.png`：256x256 画布，176x166 的面板画在左上角。
不注册 sprite，也不在屏幕中另画其他内容。

格子坐标全部参照原版 `DispenserMenu`：机器那一格取原版 3x3 的正中 (80, 35)。
背包从 (8, 84) 起，快捷栏从 (8, 142) 起，面板上的凹槽就按这几个数值绘制。
打开界面通过 `IMenuProviderExtension#writeClientSideData` 传坐标。
类型用 `IMenuTypeExtension.create` 创建，与 051 同一套做法。

资源为 `blockstates/e_115_regenerator.json` 一个变体（方块没有状态），
外加 `models/block/e_115_regenerator.json`（`cube_bottom_top`，顶、侧、底三张贴图）。
战利品表为 `loot_table/blocks/e_115_regenerator.json`。
物品模型指向方块模型，方块进入 `#minecraft:mineable/pickaxe`，并在创造栏中排在主栏目。

`gametest/E115RegeneratorTests` 摆好机器后直接调用 `serverTick`，断言：

- 随机判定的分母与每 tick 抽取次数均与随机刻那一档一致；
- 条件满足且运行足够久时必然产出，且产出的是 115 物品；
- 顶面 115 自身发出的信号不计作侧面信号（运行足够久也不产出）；
- 顶上的条件缺任意一条都不成立（吃到一半 / 未撒粉 / 空着）；
- 产物格已满时不再放入；
- 漏斗只从底面抽取，且任何面都无法放入；
- 手持 115 时返回 `SKIP_DEFAULT_BLOCK_INTERACTION`，其他物品走默认流程；
- 产出音效即羊毛放置音效。

### 试验物品 051（拆解台）

方块属性参照暮色森林 `twilightforest:uncrafting_table` 的注册参数。
包括 `ignitedByLava`、`mapColor(FIRE)`、`sound(WOOD)` 与 `strength(2.5)`，
同时也一并进入 `#minecraft:mineable/axe`。

状态只有一个 `active`：有燃料燃烧时切成 `_active` 那套贴图，`Properties.lightLevel` 据此给 13 级光照。
数值与原版熔炉的 `litBlockEmission(13)` 相同，但原版那个辅助方法写死了 `BlockStateProperties.LIT`，只能自行写一行。

`animateTick` 参照 `FurnaceBlock` 的音效部分，以 10% 概率播放 `block.furnace.fire_crackle`。
烟与火粒子没有沿用：原版是从 `FACING` 那一面喷出的，而本方块四面对称且没有朝向状态。

与拆解台的功能差别写在 `block/entity/Experiment051BlockEntity` 的类注释里。
差别有五条：面板只有拆解、不收取经验、不更换配方、燃烧燃料、直接产出。
机器刻的骨架参照原版熔炉的 `AbstractFurnaceBlockEntity#serverTick`。
只把「是否有活可干」从一格结果改成 3x3 出料格（`canStore`）：任何一格放不下即停工，并将进度清零。

#### 产物与拆解台同源

靠三样东西：

- `crafting/UncraftingResolver` 按 `UncraftingMenu#getRecipesFor(ItemStack, Level)`
  的顺序取第 0 条：先反查合成配方，再查拆解配方，最后才是本方块自行添加的熔炼回退。
  取到的布局再按拆解台的宽高铺到 3x3；
- `compat/TwilightUncrafting` 直接引用暮色森林的类型：
  `TFRecipes.UNCRAFTING_RECIPE`、`UncraftingRecipe#isItemStackAnIngredient`、`UncraftingRecipe#getCount`，
  以及 `TFConfig` 里那组白名单与黑名单字段。
  类型绑定在编译期，暮色森林改名时构建直接失败；
- 扫描时机：配方扫描比熔炉的 `CachedCheck` 贵，所以只在四种情况下重扫：
  原料更换、数量增加、不足以满足当前配方、`RecipeManager` 实例更换（数据包重载）。

#### 拆解配方

只有拆解配方、没有合成配方的物品按暮色森林的 `twilightforest:uncrafting` 格式编写。
例子是试验物品 017（四个钢叶加一片黑树树叶），见 `data/dawnduskterminal/recipe/experiment_017_uncrafting.json`。

另外两条同样走这个格式，都是两格排布（`"AB"`），产物落在 3x3 第一行的前两格：

| 文件 | 输入 | 产物 |
|---|---|---|
| `experiment_018_uncrafting.json` | 试验物品018 | 砷铅铁锭 + 黏土块（`minecraft:clay`） |
| `experiment_035_uncrafting.json` | 试验物品035 | 绯色钼锭 + 黏土块（`minecraft:clay`） |

三条都只有拆解配方，因此不会被「反查合成配方」那一档抢走；
哪天谁给 018 或 035 补了合成配方，拆解就会改走反查那条，
`gametest/Experiment051Tests` 里两条 `...UncraftsIntoIngotAndClay` 先红。

字段含义：

- `input`：能进机器的那件物品；
- `input_count`：消耗的数量，省略时为 1；
- `key` / `pattern`：3x3 产物网格，空格子不产出物品；
- `cost`：暮色森林那张拆解台自身收取的经验等级。

本方块不扣等级，所以 `cost` 写 0 即可。
这类配方要读暮色森林的类型，未安装时会在配方加载阶段报一条解析错误——暮色森林是硬依赖，这一行为可以接受。

#### 界面

`inventory/Experiment051Menu` 与 `client/Experiment051Screen`。
面板为 186x166（`textures/gui/experiment_051.png`），比原版熔炉宽一圈才装得下 3x3。

火焰与箭头不自己画，而是直接 `blitSprite` 原版熔炉的两个 sprite：
`container/furnace/lit_progress` 与 `container/furnace/burn_progress`。
因此面板贴图里那两个空框的位置必须与菜单的 `FLAME_` / `ARROW_` 常量对齐。

坐标通过 `IMenuProviderExtension#writeClientSideData` 传递，原版的开包不带坐标。
菜单类型用 `IMenuTypeExtension.create`。
漏斗顶面输入原料，侧面输入燃料，底面抽取产物。
合法性判定统一由方块实体的 `canPlaceItem` 处理，所以界面与漏斗的行为不会分歧。

#### 资源与测试

资源为 `blockstates/experiment_051.json` 两个变体，
外加 `models/block/experiment_051[_active].json`：`cube_bottom_top`，顶、侧、底三张贴图各有 `_active` 版本。
战利品表为 `loot_table/blocks/experiment_051.json`。
物品模型指向方块模型。

`gametest/Experiment051Tests` 通过直接调用 `serverTick` 跑完机器刻，不依赖 GameTest 等待方块实体自然刻。

### 试验物品 052（方雷）

参照砷铅铁反应堆制作，方块在 `block/Experiment052Block`，状态只有一个 `active`。

#### 点火

收到**任意一个**红石信号（`Level#hasNeighborSignal`，红石粉、红石块、贴面拉杆、中继器都算）即切换到 `active`：

- 模型换成 `experiment_052_glow`，`Properties.lightLevel` 提供 15 级光照；
- 播放一声 `experiment_052_ignite`；
- 通过 `scheduleTick` 排入 `FUSE_TICKS` = 60 tick（3 秒）的引信。

`tick` 到点即爆炸。点燃之后即使断电也停不下来，没有取消分支。

触发只承认「从未点燃变为点燃」这一次状态变化；`onPlace` 也会做同样的判断，所以摆在已经通电的位置上会立即点火。

#### 爆炸本体

爆炸在 `world/CarminiteBlast#large`，调用的是 `ServerLevel#explode` 那个带粒子与音效参数的十二参重载。
粒子参数两档都填 `particle_carminite_blast`，音效填 `experiment_052_explode`。
该重载把两者写进 `ClientboundExplodePacket` 一并发送给客户端，
服务端因此不再生成任何原版 `explosion` / `explosion_emitter` 粒子。

随后本模组自己生成两层粒子。
第一层是一圈 48 颗的逆时针旋臂，实现在 `CarminiteBlast#ring`。
按角度铺开，越靠外圈转过的角度越大；水平分量取 `(cos θ, -sin θ)`，速度由 0.45 增至 1.35，并带竖向抖动。
第二层是再生成一遍 `particle_carminite_blast`。

它与反应场那种 `particle_carminite_effect` 使用**同一批八帧贴图**，只换一份帧序更慢的配置。
配置在 `CarminiteParticle#blastProvider`：帧间隔 3 tick、寿命 20 tick；反应场那种保持原样。

分布按原版爆炸的规律确定：

- **落在球形范围内，半径等于威力本身**（不乘系数，威力 6 对应 6 格，威力 2 对应 2 格）；
- 颗数随威力增加，为威力 × 18，限定在 24~160 之间；
- 每颗沿半径向外带有**较小**的初速（0.05~0.20）。

位置与初速都要逐颗计算，所以走的是 `sendParticles` 的 `count == 0` 那条路径。
之后补一颗烟花白色闪光粒子 `minecraft:flash`，并按威力在附近地面补火，同时甩出 0~15 个碎片。

补火的规律与原版一致：威力 × 3 团，最少 3 团；撒开半径等于威力，向下查找威力/3 格。威力 6 时为 18 团。

#### 连锁

爆炋范围内的同类方块会连锁，但一次最多五个。
`CarminiteBlast#protectArmedBlocks` 挂在 `ExplosionEvent.Detonate` 上。

主爆炸进行中，先从 `getAffectedBlocks()`（即 `Explosion#getToBlow()`）里把**所有** `experiment_052` 移除。
这种方块的爆炸不破坏同类方块，范围内未被连锁到的那些便保持原样。
再从移除的位置中挑距离爆心最近的五个，直接点亮状态并排入一个 **1~10 tick 的随机**计划刻。

这一步跳过点火：不播音效，也不等 3 秒引信，到点直接爆。
每个被引爆的方块重复同样流程，所以连锁最多五路，每跳随机 1~10 tick，多出来的同类方块会被后续的连锁卷入。

#### 改群系

每次爆炸（主爆与碎片的小爆都算）都会把附近区域刷成 `dawnduskterminal:old_growth_birch_forest`。
它调用原版 `/fillbiome` 的那条实现 `FillBiomeCommand#fill`：按 4×4×4 单元改数据，并向附近玩家发包同步。
半径等于威力 × 2 格（最少 4 格）；调用前先把半径覆盖的区块载入，修改失败则跳过，不抛异常。

#### 资源

`blockstates/experiment_052.json` 两个变体，外加 `models/block/experiment_052[_glow].json`（`cube_all`）。
战利品表为 `loot_table/blocks/experiment_052.json`。
物品模型指向方块模型。

#### 砷铅铁火焰

`block/CarminiteFireBlock` 继承原版的 `BaseFireBlock`，不是 `FireBlock`。
蔓延、烧毁方块、按火消耗可燃物这些行为全在 `FireBlock#tick` 里。
`BaseFireBlock` 只保留「火这个方块」应有的内容，`canBurn` 再固定为 `false`。

`entityInside` 被整体换掉：原版那一段会给予 8 秒着火状态，这里只保留接触时 2 点伤害。
触发频率交给生物的受伤无敌时间控制，所以原地站着是每 0.5 秒 2 点。

伤害类型是本模组的 `carminite_fire`，它**只进 `#minecraft:no_knockback`，不进 `#minecraft:is_fire`**。
进了 `is_fire` 就会被 `Entity#isInvulnerableTo` 里的火焰免疫拦下，而砷铅铁火焰需要能烧穿烈焰人这类生物。
`effects: burning` 只换受击音效，不会点燃目标。

#### 熄灭行为

沿用原版火焰，即 `FireBlock#tick` 去掉蔓延的那一套：

- 每 30~39 tick 排入一个计划刻；
- 淋雨时按 `0.2 + age × 0.03` 的概率熄灭；
- `AGE` 每次增长 0~1，未落在火源上且无法引燃相邻可燃方块时，`age > 3` 即熄灭。

这就是压在石头上的原版火焰的寿命。
支撑方块消失后立即掉落；`onPlace` 不调用 `super`，否则会在主世界与下界顺带点出下界门。

#### 模型

用**原版火焰那一套**：状态包含 `north` / `east` / `south` / `west` / `up` 五个朝向位以及 `age`。
方块状态为与 `minecraft:fire` 同构的六个 multipart 段。
十张模型 `carminite_fire_{floor,side,side_alt,up,up_alt}[01]` 各自继承对应的 `template_fire_*`，
只替换 `fire` 那个贴图变量，`render_type` 写作 `minecraft:cutout`。

某一面要不要画板子由邻居的实心面决定（`isFaceSturdy`），所以贴着墙面或顶着天花板时会铺满整格。

环境音与烟都没沿用，改为在 `animateTick` 里每次生成三颗砷铅铁余烬 `particle_carminite_ember`。
客户端的 `animateTick` 一秒才轮到单个方块两三次，再叠概率就看不见了；原版火焰的可见效果同样靠每次三颗烟粒子维持。

#### 余烬粒子

另加的一种粒子类型。贴图用原版红石粒子那组 `minecraft:generic_0..7`，尺寸为原版红石粒子的 1.5 倍。
尺寸全程不变，没有随年龄增大的机制。
它始终满亮，八张贴图按年龄播成序列帧，并带负重力向上抬升与横向阻尼（`client/CarminiteEmberParticle`）。
初速自己写，不走 `Particle` 那个会附加 ±0.4 随机速度的构造器。
碎片拖出的红线也用同一种粒子。

方块属性参照原版火焰：`replaceable`、`noCollission`、`instabreak`、`WOOL`、`DESTROY`，光照 10 级，带 `noLootTable`。
它没有物品形态，只能由 052 的爆炸与碎片生成。

#### 碎片

`entity/CarminiteShrapnelEntity` 继承 `ThrowableProjectile`。
重力（0.03）与空气阻力（每 tick ×0.99）由父类提供，本类只剩两支回调。

客户端注册的是 `NoopRenderer`，外观就是每 tick 一颗 `particle_carminite_ember`。

命中生物时造成 1~10 点随机伤害，伤害来源是 `damageSources().explosion(this, 发射者)`。
这是原版爆炸伤害；弹体不消失，同一只生物只承受一次。

命中生物与命中方块都会在命中点引发一次小型爆炸，实现在 `CarminiteBlast#small`。
参数是威力 2、`ExplosionInteraction.NONE` 不破坏方块，
伤害计算器把 `shouldDamageEntity` 与击退都关掉，即 `ExplosionDamageCalculator` 的子类。
音效是原版那声 `entity.generic.explode`；粒子同一套，另加一颗白色闪光粒子，并补几团 `carminite_fire`。

命中方块之后弹体消失；100 tick 内没撞到任何对象也会自行消失。

`gametest/Experiment052Tests` 断言：

- 占位号已让出，物品是那个方块的 `BlockItem`；
- 15 级光照，一个信号即点燃，引信满 3 秒后方块消失；
- 火焰造成 2 点伤害且不点燃，并按原版年龄机制自行烧尽；
- 碎片造成的是爆炸伤害，落地补火；
- 方块状态与十张模型与原版火焰一致。

### 试验物品 065（伤害压制场）

反建器那一档里更高级的一台，也是本模组自己的设计。
方块在 `block/Experiment065Block`，没有状态，也没有 ticker，物品是它的 `BlockItem`。
方块属性参照 052 那一档的塔木机器：3.5 硬度、石头音效、`COLOR_ORANGE`，进入 `#minecraft:mineable/pickaxe`；
`Properties.lightLevel` 常亮 15 级。

#### 压制规则

场为 17×17×17（`world/Experiment065Field` 的 `RADIUS` 8）。判定只有一条：**伤害由谁打出**。

取 `DamageSource#getDirectEntity()`，不存在时才退回 `getEntity()`；该实体在场内就取消这一次伤害。
近战中的「直接实体」是攻击者本人，射弹中是那支箭或那颗火球，爆炸中是 tnt 那颗实体。
因此从场内射出的箭飞出场后照常造成伤害，而场外射入的箭在飞到目标身上时已位于场内，反而无法造成伤害。
这一条是「攻击源自范围内」的直接后果，规格中专门为它设了一条断言。

环境伤害（摔落、火焰、虚空、仙人掌、饥饿）的来源没有实体，场不做任何判定。

#### 为什么挂在 `LivingIncomingDamageEvent` 上

该事件在 `LivingEntity#hurt` 中位于无敌帧判定之后、一切减伤之前。
取消的效果等同于这一次伤害从未发生：没有伤害、没有击退、没有受击音、不消耗护盾耐久，也不进入无敌帧。

客户端那份 `hurt` 也会发出该事件，所以先拦截非服务端的情况。

#### 场的登记

中心不靠每 tick 扫描 17³ = 4913 格。
`world/Experiment065Field` 按维度存一张 `Set<BlockPos>`：

- 方块实体的 `onLoad` / `setRemoved` 负责区块载入与卸载；
- 方块的 `onPlace` / `onRemove` 负责摆放与挖掘。因此摆放那一 tick 即生效，不必等下一 tick 的方块实体装载；
- 伤害事件只扫描现存的场中心。

每次判定还会回头核对那一格是否仍是本方块。即使方块实体那边的撤登记漏了，也不会留下一个无法造成伤害的幽灵场。

查询之前先调 `ChunkSource#hasChunk`，不会因此把区块读进来。
`LevelReader` 那几个 `hasChunkAt` 在 1.21.1 中已废弃。

方块实体没有 `ticker`：场按位置查询，没有每 tick 必须做的事。

#### 贴图

只有 `experiment_065` 一张。方块自身发出 15 级光照，贴图直接读为满亮，不再叠发光层。
原先那套向外膨胀 0.02 的双层 cutout 模型已经拆除，`experiment_065_glow.png` 目前没有任何引用。

#### 场的框线

由 `world/CubeFieldMarker` 绘制，整段参照暮色森林反建器编写，对照 4.8.3345 源码：
`twilightforest:antibuilder` 的 `AntibuilderBlockEntity#tick`、`#makeOutline` 与 `#drawParticleLine`。

用红石粉（`DustParticleOptions.REDSTONE`）画框线：每 tick 在方块自身那一格飘一颗粉尘，每 10 次选取一次并连续画三组框线。
每组两段：一条斜线（中心到某个角，或起始角到中心，随机二选一），加上该角所在的那条棱。
十二条棱分为顶面四条、底面四条与竖直四条。

框的端点为 `pos ± RADIUS` 与 `pos + RADIUS + 1`，即方块中心往外各 8.5 格（`CubeFieldMarker#halfExtent`）。
这恰好压在场最外层那几格的外侧面上。

与该实现的差别只有三处：

- 半径：那边为 4，这里为 8；
- 每条线的粒子数：那边一段固定 16 颗（棱长 5 格），这里按长度每格 3 颗，密度一致；
- 场内究竟发生什么。

粒子只在客户端画，且只在 16 格内有存活玩家时画（那边同样用 `hasNearbyAlivePlayer(..., 16)`）。
两个方块都把 `EntityBlock#getTicker` 覆写成「客户端取 `CubeFieldMarker::tick`，服务端返回 `null`」。
服务端不发任何粒子，也不使用 `sendParticles`。

#### 资源与测试

资源为 `blockstates/experiment_065.json` 一个变体（没有状态）。
模型是 `models/block/experiment_065.json`，即 `cube_all`。
战利品表为 `loot_table/blocks/experiment_065.json`。
物品模型指向方块模型；占位物品原来那张 `minecraft:item/generated` 加 `experiment_temp` 的图标也一并换掉了。

`gametest/Experiment065Tests` 断言：

- 占位号已让出，物品是那个方块的 `BlockItem`；
- 方块没有状态，常亮 15 级光照，进入 `#minecraft:mineable/pickaxe`；
- 场为 17 格：三条轴各在 8 格处无法造成伤害、9 格处可以；
- 场内攻击场内与攻击场外都无法造成伤害，场外攻击进来照常生效；
- 环境伤害不受拦截，射弹按弹体位置计算；
- 摆放当 tick 生效，挖掘后即停止；
- 模型为单层 `cube_all` 且只有一张贴图，服务端没有 ticker。

### 试验物品 069（方块更新压制场）

反建器那一档的第二台。它与 065 共用 `world/CubeField` 那张 17×17×17 的场中心表，只是场内发生的事不同：
**落在场内的方块更新不被处理**。

概念取自原版更新抑制器：更新链把 `CollectingNeighborUpdater` 的计数顶到上限之后，再排入队列的更新会被整批丢弃。
这台机器把「一段时间」换成「一个固定范围」。

#### 判定

只有一条：`Experiment069Field#suppressed(level, pos)`，而且**考察被更新的那一格**。
更新总是「某一格收到了一个更新」，只有收到更新的那一格位于场内时才被丢弃。
所以场内的变化照常会把更新发给场外的邻居。

#### 三个 mixin，四个入口

- `mixin/BlockStateUpdateSuppressMixin` 拦截 `BlockStateBase#handleNeighborChanged`、`#tick` 与 `#randomTick`。
  `handleNeighborChanged` 是邻居更新唯一的投递口。
  `NeighborUpdater.executeUpdate` 就是它，由 `CollectingNeighborUpdater` 的三种 `NeighborUpdates` 调用；
  另两个入口分别是方块计划刻的 `#tick`（`ServerLevel#tickBlock` 到点调用）
  与随机刻的 `#randomTick`（`ServerLevel#tickChunk` 选中后调用）。
- `mixin/LevelShapeUpdateSuppressMixin` 拦截 `Level#neighborShapeChanged`。
  它是形状更新的排队口，`BlockStateBase#updateNeighbourShapes` 按六个方向逐个调用它。
  这里拦排队口而不拦 `BlockStateBase#updateShape`：
  后者还会被结构模板落地与区块升级数据直接调用，拦它会把那两条路径也一并影响。
- `mixin/FluidStateUpdateSuppressMixin` 拦截 `FluidState#tick` 与 `#randomTick`。
  水与岩浆的流动经由 `ServerLevel#tickFluid` 那条流体队列，与方块刻不是同一条路径。

在**执行口**而非排队口拦截，正是更新抑制器本来的设计：更新照常排入队列，排到场内时被丢弃。

没有场的地方每条只消耗一次 `HashMap` 查表。
邻居更新与随机刻都是每秒上千次的热路径，此处不能引入开销更高的操作。

#### 资源与测试

方块属性与 065 同档，地图色用 `TERRACOTTA_ORANGE`，与 065 的 `COLOR_ORANGE` 区分。

资源为 `blockstates/experiment_069.json` 一个变体，外加 `models/block/experiment_069.json`（与 065 一样即 `cube_all`）。
模型单层一张贴图，战利品表为 `loot_table/blocks/experiment_069.json`。
物品模型指向方块模型。

场的大小同样由 `world/CubeFieldMarker` 那套红石粉框线绘制，与 065 同一份实现。
客户端绘制，16 格内有玩家才画，服务端不挂 ticker。

`gametest/Experiment069Tests` 覆盖：占位号、`BlockItem`、无状态且常亮 15 级、`#minecraft:mineable/pickaxe`、场为 17 格；
邻居更新（红石灯通电后不亮）、形状更新（栅栏不连接）、计划刻（沙子不坠落）、
随机刻（冰不融化）、流体刻（水不流动）各一条，且各带场外对照；
摆放当 tick 生效而挖掘后即停止；模型为单层 `cube_all`；立方框的八个角位于中心外各 8.5 格；服务端没有 ticker。

这些测试**一条一个 batch**。
场为 17 格而测试结构只有 16 格，一台机器压制的场必然伸到自身结构之外；
而同一个 batch 内的测试并行执行，阵位只相隔 5 格，挤在一起会互相压制更新。
此前 052 的连锁测试正是因此被压制为失败。

### 试验物品 004（类似熔炉的机器）

方块继承原版 `AbstractFurnaceBlock`，状态就是熔炉那对 `facing` 与 `lit`。
摆放朝向、旋转镜像、比较器与打开界面都走原版抽象层。

方块实体没有继承 `AbstractFurnaceBlockEntity`。
它的 `serverTick` 是静态方法，每一锅的时间由私有方法按配方即时计算，这两处都没留给子类扩展；
而「烧食物快一倍」恰恰要改这两处，所以复制了一份三格容器骨架：
`DataComponents.FOOD` 的产物按配方时间的一半算，200 → 100。
四个数据槽的索引与熔炉完全一致，界面因此能直接复用原版那一套。

#### 界面

`inventory/Experiment004Menu` 继承 `AbstractFurnaceMenu`，`client/Experiment004Screen` 继承 `AbstractFurnaceScreen`。
面板贴图以及火苗、箭头两个 sprite 都用原版熔炉的，配方书也跟随原版。

菜单类型单独注册 `experiment_004`。
原版 `FurnaceMenu` 把 `MenuType.FURNACE` 写死在构造参数里。
继承它的话客户端会按熔炉界面打开，本模组注册的类型与屏幕就不会生效。
方块坐标不向客户端传，因为原版熔炉界面本来就获取空容器，内容靠槽位同步。

产物格换成自己的 `ResultSlot`。
原版 `FurnaceResultSlot` 只在容器是 `AbstractFurnaceBlockEntity` 时结算经验。
这里改为按 `recipesUsed` 结算，在取出产物与敲碎方块时释放。
行为与熔炉一致。

该方块不产生粒子。熔炉的烟与火写在 `FurnaceBlock#animateTick` 里，本方块不属于那一支。
只在 `Experiment004Block#animateTick` 补了一成概率的火响。

#### 资源与测试

`blockstates/experiment_004.json` 八个变体（`facing` × `lit`），
外加 `models/block/experiment_004[_active].json`：父模型是 `minecraft:block/orientable`。
正面为 `experiment_004_forward[_active]`，其余五面同一张 `experiment_004`。
战利品表为 `loot_table/blocks/experiment_004.json`，物品模型指向方块模型。

规格见 `gametest/Experiment004Tests`。

### 试验物品 027（画红石点的笔）

`item/Experiment027Item` 在按住右键不放时工作：`getUseDuration` 为 72000，`use` 中两侧都调 `startUsingItem`。
`onUseTick` 每 tick 在服务端生成一个粒子。

#### 落点

由 `Player#pick(blockInteractionRange, 1.0F, false)` 获取。
射线沿方块轮廓进行，命中方块时取 `BlockHitResult#getLocation()`，再沿命中面法线让出 0.02 格，以免粒子有一半陷进方块。
若看向空处，或超出 `block_interaction_range` 属性（默认 4.5 格），`pick` 返回 MISS，不生成任何粒子。

#### 粒子

类型为 `ModParticles#EXPERIMENT_027`（`SimpleParticleType`）。
客户端 provider 在 `client/Experiment027Particle`，继承 `TextureSheetParticle`。

- 贴图表 `particles/particle_experiment_027.json` 直接借用原版红石粒子那组 `minecraft:generic_4`；
- 颜色按原版 `DustParticle` 的算法相乘为红色；
- `quadSize` 在原版红石 0.75 的基准上再乘 0.5；
- 初速为 0，重力为 0，关闭 `hasPhysics`；
- `lifetime` 固定 1200 tick（60 秒）。

没用原版 `ParticleTypes.DUST`，是因为它的寿命与随年龄收缩的尺寸都写死在 `DustParticleBase` 里。

`gametest/Experiment027Tests` 断言：占位号已让出、粒子类型已注册、
正对石头时落点在那个面上、看向空处与超出距离时均为 null。

### 试验物品 060（金探针）

`item/Experiment060Item` 单击右键，从使用者正下方那一格开始往下扫，全部逻辑在 `Experiment060Item#probe`。
扫描分两段：先无视一路上所有的方块，找到第一块石头类方块；再从它下面一格起继续往下，跳过石头类与沙土类；
停在第一块两边都不是的方块上，用 actionbar 报出它的名字，并扣 1 点耐久。
扫到 `getMinBuildHeight()` 还没结果、或者压根没见到石头，都只报「下方没有东西」且不扣耐久。

「石头类」是基岩、粉砂岩，与 `#minecraft:base_stone_overworld`、`#minecraft:base_stone_nether`。
基岩是本维度底下那一整层，粉砂岩是砂岩那一档的岩层（白地地表就是它），两块都是地形本身，不是「探到的东西」；
这两块没有塞进数据包标签，因为 `#minecraft:base_stone_overworld` 同时是花岗岩、闪长岩、安山岩、凝灰岩、砂砾、
黏土、泥土那七种矿脉的替换目标，挂上去它们在地里会吃掉这两块。
`#minecraft:base_stone_overworld` 已经被数据包扩到含本模组的苍穹石、紫苏岩、榴辉岩、绿片岩、赭石、暮光石。
「沙土类」是 `#minecraft:dirt`（含草方块，数据包又扩了霞土与霞草）与 `#minecraft:sand`（数据包又扩了路之沙与不醒沙）。
两类之外的东西都是结果：矿石、砂砾、冰霜、流体、空气都算。

两条 actionbar 的键是 `message.dawnduskterminal.experiment_060.found`（带方块名）与同名 `_nothing`。
方块名取 `Block#getName()`，所以报出来的是玩家当前语言里的名字。

`gametest/Experiment060Tests` 摆了几列方块直接问 `probe`：跳过石头与沙土后落在铁矿上、石头下面是空气、
一路只有石头与沙土时扫到世界底部仍为空、基岩与粉砂岩算地形所以不报、本模组那套岩石与沙土同样被跳过，
并钉住 256 点耐久与两条翻译键。

### 试验物品 043（手持喷火器）

单击右键，把暮色森林封装的喷火器（`twilightforest:encased_fire_jet`）从接收红石信号到冷却结束的整套过程沿视线放出来。
逐拍对齐的实现在 `item/FireJetProcess`。

#### 方块原本的样子

`EncasedFireJetBlock#neighborChanged` 从 IDLE 切换到 POPPING，并播放 jet_start：
`block.comparator.click`，音量 0.3、音高 0.6。

`FireJetBlockEntity#tickPopping` 在计数 20/40/60 时各生成 8 颗 `LAVA`、各播放一声 `block.lava.pop`，到 80 时切到 FLAME。

`tickFlame` 跑 61 拍（计数 1..61，第 61 拍溢出归零后仍处理一拍）：

- 第 1 拍再播一次 jet_start；
- 每 2 tick 生成一颗大烟加五路大火焰（中心一路，上下左右各偏移 1 格，初速沿轴 0.75，两侧各带 0.05 的向心分量）；
- 每 4 tick 播一声 jet_active（`entity.ghast.shoot`）；
- 每 5 tick 对伤害立方体内的活体生物造成 2 点伤害并点燃 300 tick；
- 最后切到 TIMEOUT。

#### 本模组改了什么

整段沿用上面那套，只把「方块朝上」换成「玩家的视线」。
枪口在眼睛前方 1 格，右轴与上轴由视线算出来，四路火苗按这对轴摆放，伤害立方体随之旋转（沿轴 14 格、半径 2 格）。

**枪口与朝向每 tick 重新绑到玩家身上**（`refresh`），所以走开与转身时火焰跟着移动与旋转；玩家下线、死亡或换维度时中断。

施放期间若把用于点火的那只手换成其他物品，剩下的火焰改为从玩家**脚下**朝正上方喷射。
那一档不再把射手排除在伤害判定之外，因此连玩家自己一起烧。换回 043 即恢复按视线喷射。

伤害提到 4 点（方块为 2 点），点燃仍为 300 tick；火焰免疫会被跳过，也不判遮挡（与方块相同），射出者是玩家。

施放者与它的同族（`entity/GhastKin`，见 066 那一段）不会被烧到，同一个施放者也不会叠出第二道火焰。
伤害类型进入 `#minecraft:bypasses_cooldown`，所以每一拍都能实际打出，不会被受击后的无敌时间阻挡。

过程挂在 `ServerTickEvent.Post`（`DdtServerEvents`）上推进：单击一次即可，松手与换物品都不会中断。
它不使用 `startUsingItem`。

#### 大火焰粒子

由 `particle/JetFlameParticleOptions`、`JetFlameParticleType` 与客户端 `client/JetFlameParticle` 组成：

- 尺寸为原版火焰粒子的五倍，随寿命收缩到一半；
- 寿命 12~44 tick，速度逐 tick 衰减 0.96；
- 初速放进粒子选项一并发送，因为原版粒子包只能由客户端即时随机生成速度。

#### 耐久与冷却

物品耐久 64 点，每次喷射扣 1 点。
冷却在启动那一刻挂 `TOTAL_TICKS + 60` = 201 tick，等价于「运行结束后再冷却 3 秒」。

#### 伤害类型

`dawnduskterminal:experiment_043`，定义在 `data/dawnduskterminal/damage_type/experiment_043.json`，`effects: burning`。
与暮色森林的 `fire_jet` 一样进入 `#minecraft:no_knockback`、`#minecraft:is_fire` 与 `#minecraft:ignites_armor_stands`，
并另外加入 `#minecraft:bypasses_cooldown`。死亡信息为 `death.attack.experiment_043`。

#### 测试

`gametest/Experiment043Tests` 覆盖：

- 预热恰好三次；
- 火焰 61 拍里 13 拍带伤害，141 tick 后自行停止；
- 猪（无护甲）每拍损失 4 点并点燃 300 tick；
- 第二拍仍能穿透无敌帧造成伤害；
- 14 格内受伤、14 格外不受；
- 中途移动并转身时火焰跟着动；
- 施放期间换走物品即切成脚下向上喷射并烧灼自身；
- 背后、侧面与烈焰人都打不到；
- 单击扣 1 点耐久并挂冷却。

### 试验物品 066（恶魂陷阱书）

单击右键，把暮色森林恶魂陷阱方块（`twilightforest:ghast_trap`）的压场沿视线放出来。
实现在 `item/GhastTrapProcess`。

#### 方块原本的样子

`GhastTrapBlockEntity#tickActive` 里，场是以方块为中心、横向 6 格、纵向 16 格的立方体。
场内的生物**每 tick** 被 `setDeltaMovement` 拽向场心（`(场心 - 生物) * 0.1`），
并有十分之一的随机概率受到一次 10 点伤害。
它跑 120 tick 后自行关闭，充能靠 `tickInactive` 统计场边死去的砷铅铁恶魂幼体。

#### 本模组改了什么

去掉充能，场换成「从眼睛往前 16 格长、轴线外 4 格粗的走廊」。
判定时把活体生物包围盒中心投影到「视线轴 × 右 × 上」三条轴上（与 043 同一套写法）：
沿轴坐标落在 0~16、两条横轴均在 4 格加包围盒半径以内，即判为在场内。
牵引沿用 0.1 那一条且不设上限，方向朝向场心。
#### 伤害为什么不被无敌帧嗇掉

伤害是**每 tick 调一次 `hurt`**，不额外安排节拍。
原版 `LivingEntity#hurt` 的无敌帧机制会把连续施压自动压成每 0.5 秒一次，120 tick 恰好 12 次。
该机制是：打出一击后 `invulnerableTime` 从 20 向下递减，降至 10 之前再次攻击不计入。

因此伤害类型**不进入** `#minecraft:bypasses_cooldown`：绕过它，节奏就没了。
043 的喷火器相反需要绕过，因为它是逐拍的独立攻击。

#### 作用对象

`LivingEntity` 全体，怪物、动物与玩家都会受牵引与伤害——这本书要能装到敌对生物身上打玩家。
另外也包括 `ItemEntity`：掉落物只受牵引，不损失生命值。

排除三种对象：

- 施放者自身；
- 它的同族，即 `entity/GhastKin`（本模组的两种恶魂与 `twilightforest:carminite_ghastling`）。
  玩家施放时这一条等于不存在，两个玩家之间照常互伤；
- 观察者。

同一个施放者不会叠出第二个场。
施放者类型就是 `LivingEntity`，怪物持有并调 `GhastTrapProcess#start` 即可使用。

伤害类型 `experiment_066` 只进 `#minecraft:no_knockback`，否则伤害自带的击退会与牵引相互抵消。
#### 压制暮色恶魂的免伤

**暮色恶魂的免伤机制无法通过标签解除**。`UrGhast#hurt` 里有 `if (isInTantrum()) amount /= 10`：
退出 tantrum 需要累计实际扣除 18 点，而书每下 7 点，打两下即攒满，之后每下只剩 0.7（探针实测 40 tick 只扣除 15.4）。
`isInvulnerableTo` 只识别 `IN_WALL` / `IS_FIRE` / `invulnerable` 旗标，本模组的类型本来就不涉及这些。

所以在命中之前用 `item/UrGhastPhaseSuppressor` 直接调它的 `setInTantrum(false)` 与 `resetDamageUntilNextPhase()`，
把它压在相位之外，使每下都满额。
这两个方法都是 `public`，类型引用写在编译期，暮色森林改名时构建直接失败。

那两条调用与恶魂陷阱方块的 `tickActive` 同款，只是后者每 tick 还会调 `setInTrap()`。
#### 粒子

不自行选定，而是整份沿用那块方块的客户端分支：一拍五颗 `twilightforest:ghast_trap`。

速度公式是 `vx = cos(counter/10) * 2.5`、`vy = 20`、`vz = sin(counter/10) * 2.5`，五颗依次为：

- `(vx,vy,vz)`；
- `(-vx,vy,-vz)`；
- `(-vx,vy/2,vz)`；
- `(vx/2,vy/4,vz/2)`；
- `(-vx/2,vy/4,-vz/2)`。

方块那三个偏移中 `vy = 20` 是「吸口向」，朝向世界 Y，所以方块是从二十格高空向下收拢。
本书的吸口朝向视线，这一份传给 `direction`，另外两份分别传给右轴与上轴，臂因此从视线远处收进吸口。
速度即出生偏移，粒子从「场心 + 偏移」处收向场心。

`counter > 100` 之后每 4 tick 补一颗 `twilightforest:huge_smoke`（偏移 `cos*0.05, 0.25, sin*0.05`）。

这些粒子从服务端发出，包中的 `count` 必须为 0。
客户端那条分支调的是 `addParticle(type, x, y, z, maxSpeed * xDist, maxSpeed * yDist, maxSpeed * zDist)`，
速度按原值落地而不掺入随机，恰好对应方块客户端那六个参数。

#### 场心与音效

场心不在方块上，而是**施放者身上那个点**：眼睛往前 1 格（`TRAP_OFFSET`），每 tick 随视线转动，与 043 的枪口同一做法。
所以走廊中的目标是被吸向**施放者**的，五颗一轮的粒子与三支音效也都从这一个场心发出。

音效为三支 `block.twilightforest.ghast_trap.warmup` / `.on` / `.spindown`（`TFSounds` 的对应常量）。
与方块一样逐拍播：0~29 tick 预热、30~79 通电、80 以后收场，音量 1、音高 4，`SoundSource.BLOCKS`。

场每 tick 重新绑到施放者的眼睛与视线，转身即跟着转，死亡与换维度则中断。
过程挂在 `ServerTickEvent.Post`（`DdtServerEvents`）上推进。
冷却 180 tick（整段 120 再加 3 秒），每次消耗 1 点耐久，共 64 点。

#### 测试

`gametest/Experiment066Tests` 覆盖：

- 占位号已让出，64 点耐久且不可堆叠；
- 冷却在第 179 tick 仍锁定，第 180 tick 解锁；
- 走廊中的目标在 120 tick 内落下 12 次伤害（100 → 16）；
- 背后、侧面与 16 格以外不受伤害；
- 玩家在走廊中照常掉血，而施放者自身不受影响；
- `twilightforest:ghast_trap`、`huge_smoke` 与三支音效均可取到；
- 模型与三条语言键；
- 伤害类型只在 no_knockback，不在 bypasses_cooldown。

### 试验物品 045（盾）

`item/Experiment045Item` 直接继承原版 `ShieldItem`。
格挡、每次格挡损失 1 点耐久、可用木板修复、只能进入副手、72000 tick 的 `UseAnim.BLOCK`，全部沿用原版机制。
耐久 336，与 `Items.SHIELD` 相同；`BANNER_PATTERNS` 也参照原版给了默认空表。

#### 模型与变换

物品模型 `item/experiment_045` 挂 `minecraft:builtin/entity`：`gui_light: front` 与原版盾那套 `display`。
并按原版盾的 `blocking` 谓词切换到 `item/experiment_045_blocking`。

该谓词在原版中只注册给 `Items.SHIELD`，所以客户端 setup 里为 045 补了一条。
补上它，举盾时的第一与第三人称变换才能正确切换。

#### 盾面怎么画

几何体直接用原版 `ShieldModel`。真正绘制盾面的是 `client/Experiment045ShieldRenderer`，
它是一个 `BlockEntityWithoutLevelRenderer`，经 `RegisterClientExtensionsEvent` 挂到物品上。

- 没有旗面图案时，用 `item/experiment_045_nopattern` 整面木盾；
- 存在图案或底色时，换成 `item/experiment_045_base`（正面留白），再交给 `BannerRenderer#renderPatterns` 逐层叠加。

发光层 `item/experiment_045_glow` 画在同一块正面上，用的是 `RenderType#entityTranslucentEmissive`。

它单独注册为固定渲染缓冲（`RegisterRenderBuffersEvent`）。
固定缓冲按登记顺序冲刷，它排在原版那几条之后，所以发光层一定叠在本体与旗面图案之上，不会被自身覆盖。

三张贴图都在 `textures/item/` 下，被原版 blocks 图集的目录源收录，`Material` 直接指向方块图集。

#### 测试

`gametest/Experiment045Tests` 断言：占位号已让出、是 `ShieldItem` 子类、336 点耐久且不可堆叠、
`SHIELD_BLOCK` 能力放行且只能进副手、木板可修复、模型为 `builtin/entity` 且带 `blocking` 覆盖、三张贴图都在 jar 中。

### 试验物品 047~050（一套护甲）

047 头盔、048 胸甲、049 护腿、050 靴子，物品都是 `item/ExperimentArmorItem`。
这个类只为重写两处方法而存在：

- `getArmorTexture` 把护甲层贴图指向 `textures/entities/experiment_armor.png`；
- `verifyComponentsAfterLoad` 移除 `TRIM` 组件。理由与绯色钼那套相同：纹饰层按原版 64×32 排列，叠在这套 UV 上只会错位。
  锻造台那条路径本来也进不来，因为本套不在 `#minecraft:trimmable_armor` 中。

#### 数值

在 `ModArmorMaterials#EXPERIMENT_ARMOR`：护甲值与铁同级，每件 2 点韧性，附魔值 9，耐久倍率 15。

维修材料是三样锭中的**任意一样**。
`Ingredient.of` 接收的是候选清单，`Ingredient#test` 命中其中任意一个即成立：
砷铅铁锭、绯红钼锭与䤧锭里任意一样都能维修，不需要三样齐备。

#### 几何的来源

穿戴外观来自源文件里那份基岩版几何：`minecraft:geometry` 1.12.0，64×64 的 UV，头、胸、双臂、双腿、双脚共八根骨骼。
四件共用，`client/model/ExperimentArmorModel` 按槽位拆成四份 `LayerDefinition`：

- 头盔取头；
- 胸甲取躯干与双臂，含两根倾角 22.5° 的上臂筒与肩外侧甲片；
- 护腿取双腿；
- 靴子取双脚，含一块旋转 90° 的脚背斜护片。

把基岩版几何搬进 `PartDefinition` 的换算有三条：

- 基岩版原点在脚底且 y 轴朝上，Java 模型的 y 轴朝下，头部枢轴在 y=0 对应基岩版的 y=24。
  骨骼位姿为 `(px, 24-py, pz)`，方块最小角为 `(bx-px, py-by-sy, bz-pz)`；
- 带 `rotation` 的方块拆成骨骼下的子 `ModelPart`；
- **旋转角度照搬基岩版的符号，不取负值**。
  肩甲那对 22.5° 与脚背护片那对 ±90° 都是右正左负（左件带 `mirror`），取负会使它们朝内倾斜。

算出来的结果与原版玩家模型的基准位置完全一致：头身 0,0,0，臂 ±5,2,0，腿 ±1.9,12,0，臂与腿的方块也是原版那几组数值。
那份几何本来就按原版人体比例编写。

姿势与可见性不用自己算。
`IClientItemExtensions#getGenericArmorModel` 会把护甲层刚算好的那套 `copyModelProperties` 复制到缓存的模型上。

#### 发光层

由 `mixin/ExperimentArmorGlowMixin` 实现。
原版护甲层对一件物品只绘制一遍：`renderArmorPiece` 中的循环遍历 `ArmorMaterial#layers()`，每一层都交给 `renderModel`。
所以在 `renderArmorPiece` 的 TAIL 再补绘一遍，以同一份几何换用 `textures/entities/experiment_armor_glow.png`。

渲染类型必须**仍然**是 `armorCutoutNoCull`。
它带有 `VIEW_OFFSET_Z_LAYERING`，冲刷时把模型视图朝相机方向收缩 0.99975586。
本体因此在深度上比真实位置更近约万分之二点四。
换成不具备该偏移的 `entityTranslucentEmissive`，同一份几何摆在同一位置会始终落后于本体，整层被盔甲覆盖。
只有把相机伸进模型内部，才会在轮廓边缘露出一丝——此前的版本正是这个问题。

两个批次用同一个 RenderType 状态，深度才相等，`LEQUAL` 才能通过；发光层是后冲刷的那一批，因此稳定压在本体之上。

「发光」不依赖 emissive 着色器，而依赖顶点光照。
`rendertype_armor_cutout_no_cull` 的 vsh 中，`texelFetch(Sampler2, UV2 / 16, 0)` 直接采样顶点光照图。
传入 `LightTexture.FULL_BRIGHT` 后整层恒为满亮。
其 fsh 中 `color.a < 0.1` 时直接 discard，发光图里透明的位置不会被涂黑。

注解加在单个槽位那一层而不是 `render` 上，所以穿一件还是四件都不需要另写分支。

#### 测试

`gametest/Experiment047Tests` 断言：占位号已让出、四件各占正确槽位、护甲值 2/6/5/2 且总计 15。
另外还钉住：韧性 2、附魔值 9、耐久照铁；
三样锭逐一单独都能维修，而铁锭与钻石不能。
四件的内外层都返回那张专用图；本体图与发光图都在 jar 中；四个物品模型各指向自己的图标。

### 试验物品 057（刀与飞刃）

一把 8 点伤害、攻速 1.6 的刀。
`item/Experiment057Item` 只重写三处：`use` 打出飞刃、
`getEnchantmentValue(ItemStack)` 返回 10、`appendHoverText` 补一行描述。

#### 面板与修饰符

面板值直接写成 `ATTACK_DAMAGE = 8` 与 `ATTACK_SPEED = 1.6`。
`createAttributes()` 里减掉玩家底数（攻击力 1、攻速 4）得到两个修饰符。
于是代码里只有需求里的那两个数，没有「-2.4」这类对不上的中间量。

两个修饰符的 ID 用的是原版 `Item.BASE_ATTACK_DAMAGE_ID` 与 `BASE_ATTACK_SPEED_ID`。
物品提示只认这两个 ID 才会把「底数 + 修饰符」显示成「8 攻击伤害 / 1.6 攻击速度」；
换成自定义 ID 会退化成「+7 攻击伤害 / -2.4 攻击速度」。
判据在 `ItemStack#addModifierTooltip` 里，原版剑的显示靠的正是它，`gametest/Experiment057Tests` 把这两个 ID 也钉住了。

它不挂 tool tier：不挖方块，只在近战与发射上消耗耐久。耐久 64 点，每发扣 1 点，冷却 40 tick（2 秒）。

#### 为什么能上近战附魔

由两条共同成立：

- 附魔值不为 0；
- `data/minecraft/tags/item/swords.json` 把它挂进 `#minecraft:swords`。
  原版 `enchantable/sharp_weapon` 与 `enchantable/sword` 都指向这条标签。

锋利、亡灵杀手、节肢杀手、击退、火焰附加、抢夺、横扫之刃、耐久与经验修补因此全部认它。
1.21.1 的 `Item#isEnchantable` 还要求不可堆叠且带 `MAX_DAMAGE`，`durability(64)` 两条都满足。

附魔加成会进 `ATTACK_DAMAGE` 属性，飞刃每次命中都现读这个属性，所以锋利几级就按几级算。

#### 飞刃的飞行

`entity/Experiment057BladeEntity` 继承 `ThrowableProjectile`。
重力用 `setNoGravity` 关掉；父类每 tick 乘 0.99 的空气阻力由 `steer` 把速度大小按回发射时的那个值抵消。
命中判定整个留在父类，本类只接管「往哪飞」与「撞上之后做什么」。

初速是「朝发射者视线前方」的一个随机方向：以视线为轴，偏角 0~30 度、方位角一整圈，速度 1.2~1.8 格/tick。

制导按 CLOS 的原意做：指挥视线是从发射者眼睛出发的**一条射线**，不是一个遍布空间的方向场。
所以每 tick 先求弹体到这条射线的垂直偏距，
再取「垂足沿视线往前 6 格」那个点作为期望指向。
偏距越大偏角越大，偏距归零时期望方向就是视线本身；然后把当前速度方向朝它转至多 6 度，速度大小不变。

于是弹体是斜着切回视线、落在线上再沿它飞出去，而不是「方向平行、位置随便」。
发射者下线或死亡之后失去指令，就沿当前方向直飞。

#### 撞上方块

弹性反射（速度沿撞击面法线的分量取反）再叠 12% 的随机偏转，随后沿法线让出 0.05 格继续飞，实体不消失。

那一刻撒一片 32 颗红石粒子（`DustParticleOptions.REDSTONE`）。
只在服务端发：客户端也会跑这一支命中判定，不挡就会响两遍。

并播**该方块自己的破坏音效**（`SoundType#getBreakSound`，音量与音高也照方块自己的 `SoundType`）。
这一支不调 `super.onHitBlock`，否则钟、靶子这类方块会每弹一次触发一次自己的 `onProjectileHit`。

#### 撞上活物

由发射者**直接**结算一次这件物品的暴击伤害。伤害基数是**每次命中现读**的发射者 `ATTACK_DAMAGE` 属性，
不是发射那一刻的快照，也没有写死的数值：力量效果、临时换刀都当场算进去。

锋利、亡灵杀手、节肢杀手这一档另经 `EnchantmentHelper#modifyDamage` 加到结果上。
它与 `AbstractArrow`、`Mob#doHurtTarget` 是同一个入口，按发射者主手的武器栈算。

顺序与原版近战一致：属性伤害先乘暴击，附魔加成最后相加，倍率 1.5。
发射者此刻正在下落时（判据照抄原版近战暴击那几条，只去掉致盲与疾跑）再叠一层 1.5，得 2.25 倍。
倍率经 `CommonHooks#fireCriticalHit` 发出，别的模组改得动。

这件物品的每一击都是暴击，所以伤害打进去时**必定**配一次暴击音效（`entity.player.attack.crit`）
与一片 12 颗的暴击粒子（`crit`）。

音效对附近玩家按常规音量播放，发射者被排除在外，免得听两遍。
发射者另收一份按距离放大音量的 `ClientboundSoundPacket`：客户端对定位音效按「距离 ÷ (音量 × 16)」线性衰减，
音量取「距离 ÷ (16 × (1 − 0.8))」，于是不论命中点多远，发射者听到的都是八成响度。
音量只撑听距，响度本身被夹在 1，不会炸耳。

命中之后飞刃**原路弹回**：速度方向整个取反、大小不变，回程仍受制导影响，每 tick 至多转 6 度朝视线靠拢。
同时扣掉 80 tick（4 秒）的寿命——8 秒的飞刃只扛得住两次命中。

另外每 2 tick 一声 `entity.player.attack.sweep` 与一颗 `sweep_attack` 粒子。
寿命到点（8 秒，或被命中扣完）那一刻同样撒一片红石粒子再消失。

#### 外观

三件资源全部是源文件逐字节复制：

- `geo/e_57_blade.geo.json`：基岩版几何，两根骨骼 `spin1` 12 单位见方的厚板与 `spin2` 24 单位见方的薄片，
  都只写了 up / down 两个 UV 面，其余四个面没有 UV，GeckoLib 按空四边形跳过；
- `animations/e_57_blade.animation.json`：片段 `parallel0`，0.48 秒一圈，两根骨骼反向转；
- `textures/entity/e_57_blade.png`：64×64。

实体侧不再持有任何动画代码：`client/Experiment057BladeRenderer` 里的 `YsmAnimatable` 直接用这三条路径。
片段 `parallel0` 由并行层控制器自动接上，名字对得上 `parallelN`，注册在基类里。

飞刃是投掷物而不是生物，所以渲染器不复用 `YsmEntityRenderer`（那个基类建立在 `LivingEntityRenderer` 上），
而是直接实现 `IGeoRenderer`，自己驱动一帧动画再画模型。

**自发光**的做法是把传给 `IGeoRenderer.render` 的光照打包值换成 `LightTexture.FULL_BRIGHT`。
贴图不再被天光与方块光压暗，而渲染类型仍是 `entityCutoutNoCull`。
透明像素照旧裁掉、不写深度，不会出现半透明层挡在别的实体前面。

#### 测试

`gametest/Experiment057Tests` 挂在模板命名空间 `dawnduskterminal057` 下。
可用 `-Pgametest_namespaces=dawnduskterminal057` 单独跑。

覆盖：物品面板 8/1.6/64 且能上近战附魔；右键一次只出一片并扣 1 点耐久进 2 秒冷却；
连发 200 片初速都落在视线前方 30 度以内且速度在 1.2~1.8；无重力；制导把 3 格偏距收回一半以上；160 tick 后消失；
撞 WEST 面后 x 分量取反且实体不消失；命中结算 1.5 倍而发射者下落时 2.25 倍；
把发射者攻击力从 8 换成 20，伤害就从 12 变 30（证明不是写死的）；
命中的一拍速度方向整个反转（原路反射）且寿命多扣 80 tick（再中一次就消失）；
主手换成锋利 V 的一把时伤害从 12 变成 8×1.5+3=15；三件资源与两张贴图与模型与两条语言键都在 jar 中。

### 血肉之书（试验物品 057 的加强版）

图标是 `expowheel_fleshbook.png`，物品在 `item/ExpowheelFleshbookItem`，弹体在 `entity/ExpowheelEntity`。
注册 ID 不带序号：它不归「试验物品NNN」那一套，命名跟图标走，所以放在主栏目里，**不进**试验物品栏。

#### 与 057 的差别

| 项目 | 试验物品 057 | 血肉之书 |
|---|---|---|
| 近战面板 | 8 伤害 / 1.6 攻速，挂 `#minecraft:swords` | 没有属性修饰符、不进剑标签、附魔值 0 |
| 冷却 | 40 tick | 30 tick（1.5 秒） |
| 射弹寿命 | 160 tick | 120 tick（6 秒） |
| 初速 | 1.2~1.8 格/tick | 1.9~2.1 格/tick |
| 每 tick 最大转角 | 6 度 | 24 度 |
| 转弯半径 | 约 14.3 格 | 约 4.5~5.0 格 |
| 命中活物 | 发射者攻击力 × 1.5 暴击 + 附魔，原路弹回并扣 4 秒寿命 | 固定 10 点，不弹回、不扣寿命 |
| 打发射者 | 会 | 不会 |
| 沿途 | 挥砍音效 + `sweep_attack` 粒子 | 判定箱内撒 `particle_carminite_ember` |
| 撞方块 | 弹性反射 + 红石粒子 + 方块破坏声 | 弹性反射 + 方块破坏声，不撒粒子 |

转弯半径是「速度 ÷ 每 tick 转角（弧度）」，只把速度和转角一起翻倍是没用的，半径几乎不变。
这里速度只抬到 2 格出头，转角从 6 度抬到 24 度，半径才真正压下来。

#### 物品

`ExpowheelFleshbookItem` 只重写两处：`use` 打出飞轮、`appendHoverText` 补一行描述。
057 上的 `createAttributes()` 与 `getEnchantmentValue` 整个去掉，于是拿在手里就是空手的面板，
附魔台里也一个近战附魔都不会出来。
耐久仍是 64 点，每发扣 1 点，冷却 30 tick。

#### 飞行与索敌

骨架与 057 的飞刃相同：`ThrowableProjectile` 加 `setNoGravity`，空气阻力由每 tick 把速度大小按回
`speed` 抵消，命中判定整个留在父类。

索敌每 tick 从头评估一次，三档按顺序：

1. 发射者视线两侧各 20 度以内最近的敌怪，**不限距离**。
   每换一次目标（含第一次锁定）就在「目标 ↔ 发射者」那条线上撒一串附魔光点，
   光点走原版 `ParticleTypes.ENCHANT` 的行为：落到线上之后自己慢慢往下沉。
   判据是「眼睛到目标中心的单位向量与视线方向的点积不小于 cos(20°)」，所以只在视线前方找。
   代码里那个 128 格的方盒只是向区块要「已载入的实体」的手段，不是规则里的距离上限。
2. 弹体附近 24 格与发射者附近 24 格这两个范围的并集里最近的一只，
   排序用「到弹体」与「到发射者」两个距离里较小的那个。
3. 前两档都没有目标时，回落到 057 的 CLOS 制导：往「发射者眼睛出发的那条视线」上收。

前两档都要过一遍方块遮挡判定（`ClipContext.Block#COLLIDER` 的射线）：隔着墙、隔着山、隔着一层地板一律不算目标，
第 1 档从发射者眼睛出发看、第 2 档从弹体自己出发看；没有这一道就会朝墙里钻，而且会永远停在第一档。
前两档只认 `net.minecraft.world.entity.monster.Enemy`：玩家、动物与发射者自己都不算敌怪。
锁定之后期望方向取「指向目标判定箱中心」，再受每 tick 24 度的转角上限约束，
所以弹体是斜着切过去，不会瞬移掉头。

#### 命中

命中活物只结算一次固定的 10 点接触伤害，伤害源是 `DamageSources#thrown(this, 发射者)`：
直接来源是弹体、加害者是发射者，击杀提示与仇恨因此记在发射者头上，
但它不是近战攻击，所以不读攻击力、不吃附魔与暴击——057 那套暴击音效与暴击粒子随之消失。

命中之后弹体**不**弹回、**不**扣寿命，继续按当前速度飞。
发射者自己不受伤：弹体从眼睛前方 0.6 格出生，发射者的判定箱就在旁边，没有这一道判断一出手就打自己。

撞方块照抄 057 的弹性反射：速度沿撞击面法线的分量取反、叠一点随机偏转，再让出 0.05 格继续飞，
那一刻只播该方块自己的破坏音效。粒一颗都不撒——沿途的余烬一直在出，碰撞再撒一片是多余的。
寿命到点那一刻由服务端发一条 `ExpowheelBurstPayload`（位置 + 弹体当时的速度），客户端照着铺一圈 `particle_carminite_ring`（与反应场同一套贴图，摩擦 0.8）：24 颗、半径 1 格，每颗的速度是「弹体速度 × 0.3 + 向外 0.6」。
摩擦 0.8 意味着每 tick 速度掉两成，15 tick 后只剩百分之三：整环甩出去就自己停下，不会跟着弹体飞走。
同时播原版风弹爆开的音效 `entity.wind_charge.wind_burst`，音量给 4——原版定位音的听距是「音量 × 16 格」，
正好和 64 格的粒子半径对齐，看得见的地方就听得见。
粒子只能由服务端触发：弹体一 tick 飞两格，一秒多就出了 4 区块的客户端跟踪范围，客户端活不到 120 tick。

#### 音效与粒子

发射那一下由 `ExpowheelEntity#launch` 在发射者身上播 `expowheel_shoot`。

`expowheel_ambient` 不是「发射时在原地放一遍」：素材 9.98 秒、弹体只活 6 秒，一旦按
`Level#playSound` 在出生点播，声音就钉在发射者身上，飞轮飞走了它还在原地响。
所以改成客户端挂在弹体身上的定位音（`client/ExpowheelSoundInstance`）。

音量与收尾都自己算，不靠引擎那套：

- `attenuation = Attenuation.NONE`，`getVolume()` 里按「监听者到弹体的距离 / 48 格」线性收音量——
  引擎按位置的距离模型对这支实测不生效，听起来全程满音量；
- 素材自己首尾都有渐变，所以**不做淡出**：只到 `LIFETIME_TICKS`（120 tick）或弹体消失时自停。

素材 9.98 秒比 6 秒的寿命长，本来就用不到一半，**没有任何循环**。

沿途每 tick **八颗** `particle_carminite_ember`，位置撒在判定箱内部的**一个圆盘**上：
水平半径取箱子宽度的一半、竖直在箱高内随机，于是 2 格宽半格厚的扁轮子摊出来就是一整片火星，
与碰撞箱、模型外轮廓三处对得上。
竖直初速按 `carminite_fire` 那一档给 0.2~0.25、横向只留 0.01 的抖动，
之后的上飘由 `client/CarminiteEmberParticle` 的负重力负责。
粒子在各自的客户端就地生成（与 052 的碎片同一条写法），不需要服务端发包。

第 1 档换目标那条光带：`ParticleTypes.ENCHANT` 每 0.3 格一颗、12~48 颗，
`sendParticles` 的散布与「速度」三轴都给 0——这支粒子的初速就是它的落点偏移，
给一点就整条线歪掉。粒子本身按原版行为在寿命里往下沉 1.2 格。

#### 外观

几何、动画是源文件逐字节复制：

- `geo/expowheel.geo.json`：骨骼 `RightMagic` 上挂着 `ysmGlowIn` / `ysmGlowOut` 与 P/Q 两组十六根发光环节；
- `animations/expowheel.animation.json`：片段 `parallel0` 全部由 Molang 驱动，
  没有写死的 `animation_length`，因此按循环处理；
- 贴图不另画一份，直接用「测试恶魂591」(`tower_ghast` 模型包) 的 `ysm/tower_ghast/textures/ghast.png`，
  几何的 `texture_width/height` 也是 256，UV 一一对得上。

渲染器 `client/ExpowheelRenderer` 与 057 的那份同构：直接实现 `IGeoRenderer`，
光照打包值换成 `LightTexture.FULL_BRIGHT`，渲染类型仍是 `entityCutoutNoCull`。

碰撞箱取 2.0 x 0.5：几何的 `visible_bounds_width` 就是 4 格、最外圈 15 单位，
摆出来是一片直径两格的薄轮子，箱子跟着做成「宽 2 格、厚半格」的扁盒，与模型外轮廓对得上。

#### 测试

`gametest/ExpowheelTests` 挂在命名空间 `dawnduskterminalexpo` 下，用 `ddt_empty` 模板的副本。
这批不进默认名单，要 `-Pgametest_namespaces=dawnduskterminalexpo` 点名跑：第 1 档索敌不限距离，
和别的用例同场跑会被隔壁场地里的僵尸抢走目标。
覆盖：物品没有属性修饰符、不进剑标签、附魔值 0、64 点耐久且不可堆叠；
右键 1.5 秒一发并各扣 1 点；连发 200 片初速都落在 1.9~2.1 且无重力；转弯半径压在 6 格以内且比 057 小；
120 tick 后消失；撞 WEST 面后 x 分量取反；
命中只结算 10 点（发射者攻击力摆成 20 也不变）且同一拍方向仍然向前、寿命不多扣；
发射者自己不掉血；视线锥内的目标优先于更近的锥外目标；锥内那只躲在墙后面时第一档不锁它，
改由第二档去抓旁边看得见的那只；锥内没有目标时转向 45 度方向的那只，不引导时只会沿视线直飞；
没有目标时回到视线上；几何、动画、两张贴图、物品模型、两条音效与四条语言键都在 jar 中。

### 试验物品 055（充能外泄场）

按住右键慢慢充能的外泄场。
`item/Experiment055Item` 只重写两处：`getUseDuration` 给 72000，`use` 起过程。

整段在 `item/BeetrootProcess` 里，由 `DdtServerEvents` 挂在 `ServerTickEvent.Post` 上推进。
与 043、066、095 是同一套骨架：松手、换物品、物品碎掉、死亡、下线、换维度，都靠每 tick 看一眼 `isUsingItem()` 收场。
之所以自己看而不用原版回调，是因为每次结束都要给客户端发「停止循环音」。

#### 充能

前 100 tick（5 秒）里，影响半径从 0 线性涨到 24 格，伤害系数从 0 线性涨到 0.3，两者读同一个 `progress()`。
之后封顶不再涨。

#### 伤害

圈内除自己以外的活物每 tick 试一次 `hurt`，伤害为 `系数 × (半径 − 距离)`。
满充能时贴脸 7.2、4 格 6.0、23 格 0.3，24 格起为 0。

不绕无敌帧，节拍交给原版那 0.5 秒，与 066、095 同一个理由。
每真打中一次，就在目标的包围盒里撒 12 颗 `DustParticleOptions.REDSTONE`。

#### 射线粒子

此外**每 tick 再甩一条随机射线**。
起点是使用者身体中段，指向 `1.5 × 当前影响半径` 的球面上一点（三轴高斯归一化的球面均匀方向）。
沿这条线按**每格 4 颗**铺 `ParticleTypes.ENCHANT`：半径 24 时是 36 格、144 颗封顶。

密度必须按长度算。影响半径是从 0 涨到 24 的变量，固定颗数在半径小时还算密，半径一大就只剩一根虚线。

这一路**完全在客户端本地生成**，实现在 `client/Experiment055Particles`，挂 `ClientTickEvent.Post`。
充能进度客户端本来就有：`LocalPlayer#getTicksUsingItem()` 与物品的 `getUseDuration` 同步。
位置与方向都没有游戏性意义，而 `sendParticles` 每 tick 要发一百多个包，白白占带宽。

速度一律给 0。原版 `ENCHANT`（`FlyTowardsPositionParticle`）本身就在 30~40 tick 里一边淡出、
一边按 `-f1 × 1.2` 往下飘，位置给在射线哪一点它就在哪里飘下去。
把「射线上那点相对使用者的位移」当速度塞进去，正好是它的「飞向目标点」用法，
粒子会全体朝使用者汇拢，不是这里要的。

代价是**别人身上的场本地看不见**：客户端只知道那个实体「正在使用物品」，不知道用的是哪一件。
要给别人看，就得由服务端把射线方向与长度发下来，粒子仍在本地生成。

#### 自伤

充满那一刻起，使用者自己每 tick 也挨 1 点同类型伤害（`age >= CHARGE_TICKS` 起）。
实测玩家掉血的节拍同样被无敌帧压到每 0.5 秒一下。
每 20 tick 扣 1 点耐久，300 点耐久正好按住 5 分钟。

#### 群系

每秒把 `0.7 × 影响半径` 内的群系刷成 `dawnduskterminal:old_growth_birch_forest`，
与 052 那颗方雷炸完刷的是同一份。满充能时半径 16.8 格。

形状是**球**。形状只有半径一个参数，所以生物群系单元（4×4×4 一格）的中心到施放者的距离小于半径才换，
三个轴用同一个半径。不是方形、不是方柱、也不是贴地的一层薄片。

走原版 `/fillbiome` 那条实现 `FillBiomeCommand#fill`：按 4×4×4 的生物群系单元改数据，并把改过的区块重新发包。
但它一次只认 `commandModificationBlockLimit`（默认 32768）那么大的体积，半径 24 的一整块方盒早就超了。

所以按**区块列**切片：每片是 16×16 的水平范围加 `y ± 半径` 的竖直范围。
最大 16×48×16 = 12288 格，稳稳在线内。
同一列每 tick 只调一次，也不会整列改到半径之外。
只动已经载入的区块（`ChunkStatus.FULL` 取不到就跳过）：每秒把还没载入的区块生成出来太贵。

#### 音效

三支按时切换：`experiment_055_charge` 在充能期间循环，充满那一拍放一次 `experiment_055_full`，
之后换成 `experiment_055_geiger` 循环。

**只有盖革音需要接遍**。它要一直响，素材首尾都做了渐变，所以引擎自己那份 `looping` 不打开，
打开就没有接缝可言了。
改成每一遍各是一个实例：播到剩 20 tick 时回调调度器把下一遍接上，两遍叠着换句，接缝落在两边的渐变里。

**充能音只放一遍**。素材本身就是 5 秒、与充能期一样长（`CHARGE_TICKS = 98`），
播完到充满之间那两三 tick 是静音。
盖革音 `GEIGER_TICKS = 301`。两个数都是按 ogg 的 granule 量出来的秒数乘 20，换素材要跟着改。

起音一律走 `SoundManager#queueTickingSound`，不用 `play`。
接下一遍的回调来自 `SoundInstance#tick`，而那一刻声引擎正在 for-each 遍历 `tickingSounds`；
`play` 会往同一个 List 里加，直接 `ConcurrentModificationException` 崩客户端。

#### 协议

原版没有「服务端让客户端循环播放」的路子，所以与 095 一样走自建协议。
`network/Experiment055SoundPayload` 的内容是施放者实体 id 加档位，档位有 `CHARGE`、`FULL`、`GEIGER`、`STOP` 四种。

客户端 `client/Experiment055Sounds` 按实体 id 维护一支 `client/Experiment055SoundInstance`。
它是 `EntityBoundSoundInstance` 的子类，`looping` 置真，音源 `SoundSource.PLAYERS`，走远 64 格自己收声。

`full` 用一次性 `SimpleSoundInstance` 放在施放者当时的位置，不循环也不跟人走。

服务端每秒重发一次当前档位。客户端对同一实体同一档位是幂等的，所以重发不会叠音。
停止包按 64 格发。

听得见靠 `sounds.json` 里这三条各自的 `attenuation_distance: 32`。
不打 `stream`：它们是短循环片段，静态缓冲交给 OpenAL 自己接最无缝。

#### 伤害类型与测试

`dawnduskterminal:experiment_055` 只进 `#minecraft:no_knockback`，**不进** `#minecraft:bypasses_cooldown`。

死亡信息 `death.attack.experiment_055` 与它的 `.player` 变体都写成「… 变成了甜菜汤」。
伤害源把施放者记为攻击者，原版因此总走 `.player` 那条。

`gametest/Experiment055Tests` 覆盖：

- 占位号已让出，300 点耐久且不可堆叠；
- 半径与系数在 0 / 2.5 秒 / 5 秒上依次为 0 / 12 / 24 与 0 / 0.15 / 0.3，5 秒之后封顶不再涨；
- 满充能伤害表 7.2 / 6.0 / 0.3 / 0；
- 4 格外的铁傀儡第一拍恰好掉 6 点，而施放者自己从充满那一拍开始掉血；
- 充能期间施放者不掉血，第 19 tick 还没扣耐久、第 20 tick 扣满 1 点；
- 20 tick 之后施放者脚下的群系变成 `dawnduskterminal:old_growth_birch_forest`；
- 松手即收场；
- 三支音效与两个语言文件的关键字齐备。

### 试验物品 095（镇魂曲）

**按住右键**期间原地奏一支曲子。
`item/Experiment095Item` 只重写三处：`getUseDuration` 给 72000、`use` 起过程、`appendHoverText` 补一行描述。
整段在 `item/RequiemProcess` 里，由 `DdtServerEvents` 挂在 `ServerTickEvent.Post` 上推进。

#### 为什么不写在 onUseTick 里

这一支每次结束都要给客户端发一颗「停止那支循环音」的包，而按住的结束方式不止松手一种：

- 换物品走的是 `stopUsingItem`，不经过 `releaseUsing`；
- 物品碎掉之后 `onUseTick` 直接不再被调用：`useItem` 变空栈，`ItemStack#onUseTick` 落到空气上什么也不做；
- 死亡、下线、换维度更是另有其路。

挂在服务端 tick 上就不必依赖任何一个回调。
每 tick 自己看一眼「施放者还活着、还在这个维度、`isUsingItem()`、`getUseItem()` 还是 095」就够。
与 043、066 是同一个做法。

#### 伤害

每 tick 对 32 格内除自己以外的活物调一次 `hurt`，伤害为 `0.5 × (32 − 距离)`。
贴脸 16 点、16 格 8 点、31 格 0.5 点；取距离用 `Entity#distanceTo`（脚对脚）。
32 格起伤害为 0，且那一发不发出。

对象是 `LivingEntity`，排除施放者自身与观察者；创造模式与友军交给 `hurt` 自己判。

不绕无敌帧：原版那 0.5 秒的无敌帧自然把节拍排出来，与 066 同一个理由。043 相反需要绕过。

#### 音符环

每 10 tick 甩出一圈音符，约 24 ± 4 颗。初速水平 3.2 格/tick（正好每秒 64 格），从身体中段朝整圈散开。

整圈不是一条正圆：

- 每一圈先整体转一个随机角度；
- 颗数上下浮动；
- 每颗再各自抖角度（最多 ±35% 个格点）、抖速度（±8%）、抖出生半径（0~0.7 格）与出生高度（±0.4 格）；
- 竖直初速只给一点点随机。

所以相邻两圈连格点都对不上，不会像军阵。

粒子另开一种 `ModParticles#REQUIEM_NOTE`：

- 摩擦取 0.9，而不是原版音符的 0.66。
  环要真的扩到 32 格，按 0.66 只撑出 9 格就停；3.2 格/tick 配 0.9，40 tick 正好铺到约 31 格；
- 上飘分两段：出生时一份向上初速（`LIFT`），之后每 tick 再往上托 0.006~0.016（`RISE_PER_TICK` 加自带随机）。
  所以它到最高处之后仍在慢慢上升，而不是「弹上去停住」；
- 寿命 40 ± 4 tick 现摇，免得每一圈同时出生同时消失；尺寸乘 2，并随寿命从 0 涨到满。

**原版 `ParticleTypes.NOTE` 用不了**：`NoteParticle` 的构造函数把速度整个乘了 0.01，横向初速永远是 0，只能原地往上飘。
贴图仍借原版 `minecraft:note`（`particles/particle_requiem_note.json`）。
颜色在客户端现摇（原版那套正弦取色公式），不进粒子包。

#### 耐久

每 20 tick 扣 1 点。300 点，从头按到尾 5 分钟；创造模式下 `hurtAndBreak` 不真扣。
同时把「开始放」那颗包重发一遍。

#### 曲子素材

素材整份放在 `assets/dawnduskterminal/sounds/requiem/`，现在 6 支，17~25 秒的 Ogg Vorbis。

加曲子要三处一起改：目录里那个 `.ogg`、`sounds.json` 的一条、`ModSounds#REQUIEM_TRACKS` 的一行。
每次按住由服务端摇一个下标，`ModSounds#REQUIEM_TRACKS` 就是那张有序表。

#### 为什么要自建协议

原版没有「服务端让客户端循环播放」的路子：
`playSound` 发的是放完就没、服务端也停不掉的一次性音效，循环只存在于客户端的 `SoundInstance#isLooping()` 上。

所以走自建协议：`network/RequiemStartPayload`（施放者实体 id 加曲子下标）与 `RequiemStopPayload`（实体 id）。

客户端 `client/RequiemSounds` 按实体 id 维护一支 `client/RequiemSoundInstance`。
它继承 `EntityBoundSoundInstance`，每帧把位置跟到施放者身上、实体被移除即收声。
`looping` 置真，音源走 `SoundSource.RECORDS`。

启动包每秒重发一次，让这一秒里才走进 32 格的人也听得见。
客户端对同一个施放者是幂等的，所以不会叠音。
停止包按 64 格发：按住期间走远的人已经听不见，但声道还占着；更远的由客户端按距离自己收声。

听得见靠的不是音量，而是 `sounds.json` 里这五条各自的 `stream: true` 与 `attenuation_distance: 32`。
`stream` 是边读边放，`LoopingAudioStream` 负责接上循环；`attenuation_distance` 是线性衰减到 32 格静音。

#### 伤害类型与测试

`dawnduskterminal:experiment_095` 只进 `#minecraft:no_knockback`，**不进** `#minecraft:bypasses_cooldown`。
死亡信息 `death.attack.experiment_095` 与它的 `.player` 变体都写成「… 得到安眠」。
伤害源把施放者记为攻击者，原版因此总走 `.player` 那条。

`gametest/Experiment095Tests` 覆盖：

- 占位号已让出，300 点耐久且不可堆叠；
- 伤害在 0/2/16/31/32 格上依次为 16/15/8/0.5/0；
- 4 格外的铁傀儡第一拍恰好掉 14 点，而第二拍要等无敌帧数到十才落地。
  测试逐 tick 数出那个间隔并要求落在 8~12 tick 之间，不写死某个版本的具体差一；
- 松手即收场，第 19 tick 还没扣耐久、第 20 tick 扣满 1 点；
- 40 tick 恰好 4 圈音符；
- 每支曲子各自在 jar 里带够长的 Ogg，并在 `sounds.json` 里带 `stream` 与 `attenuation_distance: 32`。
  不写死支数，只钉「表里有几支，三处就得几支一一对上」；
- 伤害类型与两个语言文件的关键字齐备。

## 火箭机弩

`item/FireworkMachineBowItem`，注册名 `dawnduskterminal:firework_machinebow`，主栏目里排在棱镜权杖后面。

按住右键期间，每 `10 - 2 × 快速装填等级` tick 打出一发烟花火箭，最快 4 tick（0.2 秒）。
弹体照抄原版弩打火箭的那一条：`FireworkRocketEntity` 的七参构造器（带 `shot_at_angle`）、从眼睛下方 0.15 格出膛、
弹速 1.6。
多重射击时多出来的两发各偏 `7 度 + 0~3 度的随机量`，两发各自摇，互不相同。
火箭内容每一发现摇：形状、颜色、淡出色、拖尾与闪烁之外，飞行时长取 `1~3`，烟火之心数量取 `1~7`。
消耗掉的那一枚是哪种火箭都不影响结果。

### 只播「物品被使用」那一下

本物品**不继承** `CrossbowItem`：原版 `ItemInHandRenderer` 对 `CrossbowItem` 有一条专门的 instanceof 分支，
按住右键时会把物品抬到胸前作出拉弦姿势，而那正是要避免的。
改用 `UseAnim.NONE` 之后，按住右键期间物品就摆在普通位置，没有任何使用姿势。

每一发出弹的那一拍，客户端调一次 `ItemInHandRenderer#itemUsed`（`client/FireworkMachineBowBob`）：
把 `mainHandHeight` 打回 0，物品先往下沉一下，再由装备回弹那套逻辑升回去。
这就是原版每次成功使用物品时 `Minecraft#startUseItem` 调的那一句，项目里那些按一下就走完的物品
（043、057、066、血肉之书等）每用一次也都是这一下。
它与 `Player#swing` 的挥手是两条路，这里不用挥手。客户端按 `onUseTick` 的同一拍自己算，不占网络包。

### 弹药与耐久

火箭就是这门武器的弹药：每发消耗身上任意一枚烟花火箭，先看副手，再走整份背包（含快捷栏）。
身上一枚都没有时 `use` 直接失败、弩举不起来；按住打到一半打空了，`onUseTick` 会 `stopUsingItem` 收手。
多重射击仍是扣一枚：三发共用那一枚，与原版多重射击「一箭三发」的扣弹方式一致。

耐久 465，与原版弩相同；每发扣 3 点，也就是原版弩打烟花火箭的 `getDurabilityUse`。
先出弹再扣耐久，最后一发照打、弩随后碎掉。
创造模式不扣弹药也不扣耐久，走的是原版 `useAmmo` / `ItemStack#hurtAndBreak` 同一条判据。

### 声音

三声都是模组自己的事件，见 `sounds.json`：
`firework_machinebow_launch`（原版烟花的 `launch1`，火箭发射）、
`firework_machinebow_shoot`（原版弩的 `shoot1~3`，发射）与
`firework_machinebow_draw`（原版弩的 `loading_middle1~4`，装填）。
发射与装填是跟着手的两声，下一发之前先给 64 格内的玩家发 `ClientboundStopSoundPacket` 掐掉残响；
**火箭发射那一声不掐**：火箭已经射出去了，声音该跟着它走完。
弹体设了 `setSilent(true)`，原版那声 `FIREWORK_ROCKET_LAUNCH` 不会出来抢，由 `firework_machinebow_launch` 自己补。

### 贴图两档

常态是初始贴图。每一发把 `DataComponents.CUSTOM_MODEL_DATA` 在 1 与 2 之间来回切，模型 `overrides` 按它选
`firework_machinebow_1` / `firework_machinebow_2` 两张。
停手（`releaseUsing`）与不再使用中（`inventoryTick`）都会清掉这一项，回到初始贴图；切手、收起这类不走
`releaseUsing` 的路径由 `inventoryTick` 兜住。

这一项每发都在变，如果按默认的 `!oldStack.equals(newStack)` 判换手动画，每一发都会触发一次「收起再抬起」；
快速装填间隔短的时候动画来不及回弹，会一路叠着往下沉，最后把物品压出屏幕。
所以物品覆写 `shouldCauseReequipAnimation`，只看物品本体与槽位，换贴图不再触发换手动画。

### 附魔

物品挂在 `data/minecraft/tags/item/enchantable/` 的 `bow`、`crossbow` 与 `durability` 三个原版标签上：
前两条给出弓弩两套附魔，后一条让耐久类附魔（耐久、经验修补）也吃得到。
耐久 465 加上不可堆叠，正好满足原版 `Item#isEnchantable` 的两条要求，不需要另开判据。

- 力量：每级伤害乘 `1 + 0.25 × 等级`；
- 冲击：**减少**命中和爆炸的击退，每级减半，II 级起完全不击退；
- 火矢：点着被爆炸波及的目标 5 秒，火焰免疫的除外；
- 多重射击：三发，多出来的两发各偏 `7 度 + 0~3 度随机量`；
- 快速装填：每级把间隔减 2 tick，最快 0.2 秒。

### 伤害结算

原版火箭的爆炸伤害写在 `FireworkRocketEntity` 的私有 `dealExplosionDamage` 里，距离、视线与衰减都按原版那一套走，
模组不碰。出膛时把 `DdtMachinebowRocket` 标记与星数、三个附魔等级写进火箭的 `NeoForgeData`，
伤害落地前由 `LivingIncomingDamageEvent`（`world/MachinebowFireworks`）认标记再补：

- 烟火之心：星数只影响伤害，外观固定只演一颗爆炸。原版这一下是按 `5 + 2 × 1` 算的，
  这里乘 `(5 + 2 × 星数) / 7` 换成星数对应的伤害，5 格距离衰减与视线判定原样保留。
- 力量：伤害乘 `1 + 0.25 × 等级`。
- 火矢：点着被爆炸波及的目标 5 秒，火焰免疫的除外。
- 无视无敌帧：事件排在无敌帧判定之前，把 `invulnerableTime` 清零之后这一下按满额进 `actuallyHurt`；
  多重射击三发同 tick 命中也是三发全额。
- 冲击：击退由 `LivingEntity#hurt` 里的 `knockback(0.4)` 施加，这一下在事件之后；
  出手前把目标登记进 `PENDING_PUNCH`，由 `LivingKnockBackEvent` 按等级把强度削掉（每级减半，II 级起 0）。

## 反建器二型（反建场）

暮色森林 `twilightforest:antibuilder` 的第二型。
方块在 `block/AntibuilderType2Block`，方块实体在 `block/entity/AntibuilderType2BlockEntity`，
场在 `world/AntibuilderType2Field`，热路径上的那一处拦截在 `mixin/LevelSetBlockReactionMixin`。

与暮色森林那一台只差四条：范围可变、不抄快照、产出的方块换成本模组那一块、反建器家族两条例外。
其余一律照抄，包括方块属性（`noLootTable`、`pushReaction(BLOCK)`、`mapColor(SAND)`、
`requiresCorrectToolForDrops`、`WOOD` 音效、10 点硬度 6 点抗性、10 级光）、红石粉框线与 `#minecraft:mineable/pickaxe`。

### 范围

场的半边长是一条状态 `RANGE`（1~8），直径是 `2 * range + 1`，默认取满 8，即 17×17×17；最小 1，即 3×3×3。
状态而不是方块实体字段：右键调完当场生效，也不存在两份数值对不上的可能。

创造模式玩家右键加大一格、潜行右键缩小一格，两端封顶不再回绕。
动作栏报当前范围，键是 `block.dawnduskterminal.antibuilder_type2.range`。
其它模式右键不算这个方块的交互，`useWithoutItem` 与 `useItemOn` 都返回「往下走」，所以手上拿着方块照样能放。
一格都不变的判定收在 `AntibuilderType2Block#nextRadius`，测试直接钉这个函数，不必构造「按住潜行键的假玩家」。

### 红石关机

方块状态 `POWERED`（`BlockStateProperties.POWERED`）为真时这台机器关机：场整个停摆、框线不画、方块自己不再发光。

摆下那一刻由 `getStateForPlacement` 按当时的红石信号定下初值，之后由 `neighborChanged` 跟着信号变。
两处都要：贴着一条通着电的红石线摆下时，那条线不会因为这台机器落地而变化，也就不会给它发更新。
用状态而不是每次现算，是因为客户端要照着它决定画不画框线，而红石信号在客户端算不准。

关机只停场，方块本身、`RANGE` 与场心登记都留着，信号一断当 tick 恢复。
`AntibuilderType2Field` 的 `covers` 与相邻判定都跳过关机的机器，所以贴着关机的机器摆第二台是允许的。

### 不抄快照

暮色森林那一台每 tick 把 9³ 格抄进 `BlockState[]`，再慢慢比对着往回改；
这一台一个方块状态都不存，只在方块真正被破坏或被放置的那一刻判定一次。

判定位置是 `Level#setBlock(BlockPos, BlockState, int, int)`，它是原版改方块唯一的漏斗：
玩家挖方块走 `ServerPlayerGameMode#removeBlock` → `BlockState#onDestroyedByPlayer` → `Level#removeBlock`，
爆炸、活塞、`/setblock` 与 `setBlockAndUpdate` 最后也都落到它上面。
因此只需要在这一处拦一次，不必给每个入口各挂一次。

`AntibuilderType2Field#react` 拿「原来的状态」与「要写进去的状态」比一次，只认两种变化：

- 破坏：原来不是空气也不是液体，新的状态是空气或液体（后者是含水方块被拆掉）。
- 放置：原来是可以被顶掉的东西（空气、液体、`canBeReplaced()` 为真的草与雪层），新的状态是实心方块。

其余一律放过：方块自己的状态变化（门开、熔炉点火、红石灯亮灭）两条都套不上；
水的流动与退去也套不上，所以场里照常能进水。
暮色森林那一台靠 `#twilightforest:antibuilder_ignores` 里的 `minecraft:water` 躲开这件事，这里不需要那张标签。

反应不是「改参数再放行」，而是整个接管这一格：在 `HEAD` 处取消原调用，把结果写进去再返回。
直接改参数是错的——原调用接着会把真正想写的空气盖到反建方块上面，等于什么都没做。
反应自己也要写方块，那次写入必须被挡住，否则「空气 → 反建方块」会被判成一次放置，转头又退回去，来回翻烧饼。
闸是 `ThreadLocal` 上的一位，因为服务端只有一条世界刻线程，客户端则压根不判定。
代价是反应自己那一次写入引起的邻居更新也落在闸内，那一条连锁不再判定——方块变动本身还是照常发生。

### 产出的方块

被破坏的那一格换成 `dawnduskterminal:antibuildblock_type2`（`ModBlocks#ANTIBUILD_BLOCK_TYPE2`）。
方块属性照暮色森林的 `antibuilt_block`：`noLootTable`（挖掉什么都不掉）、`noOcclusion`（贴图带透明）、
`pushReaction(BLOCK)`、`WOOD` 音效、0.3 点硬度 2000 点抗性；不进 `#minecraft:mineable/pickaxe`。
与那一块不同的一点：这一块有物品形态，主栏目里直接拿得到（`ModItems#ANTIBUILD_BLOCK_TYPE2`），
不必为了摆一块去场里炸出来。

### 三条例外

- 范围内被破坏的方块是反建器家族（`twilightforest:antibuilder` 或本模组这一台）：复原它，而不是换成反建方块。
- 范围内被放置的方块是反建器家族：放着不管，而不是退回去。
  唯一的例外是相邻：反建器二型挨着任意一台开着的反建器二型摆（切比雪夫距离 ≤ 1，斜角也算）会被破坏，
  那一台照普通方块处理。暮色森林那一台不受这条管，紧贴着也能摆；关机的机器也不算相邻。
- 一个场不管自己的场心那一格：否则任何半径都包住场心，单台机器放下就再也拆不掉。

第三条只豁免「这个场自己的场心」，不是「所有机器的场心」：
别的机器的场照管它，所以范围内的反建器二型被破坏时照样复原。
距离两格以上摆进彼此的场里时两台机器仍会互相保护，谁也拆不掉，只能靠命令移走——这是规格的直接后果。
复原走的是「把原来的状态写回去」，原状态与新状态相同时那次写入是空操作，方块根本没被移走。

撤销登记靠两条路：方块的 `onPlace` / `onRemove` 管摆下与挖掉，方块实体的 `onLoad` / `setRemoved` 管区块载入与卸载。
`DdtServerEvents#onServerStopped` 里再把整张场心表清干净。

判定不扫 17³ 格，也不把全维度的机器挨个量：场心按区块分桶，查一格只看它所在区块与紧邻的两个方向共 3×3 个桶。
半径最大 8 格，小于区块的 16 格，能盖住这一格的机器一定在这九个桶里，远处的机器一次都不碰——
于是这条判定的开销与场上机器总数无关。
每个桶里还要回头核一遍那一格还是不是本方块，幽灵场只活到它下一次被问到；核之前先问 `ChunkSource#hasChunk`，
不会为了找方块把区块读进来。
每次判定会读一次场心那一格的方块状态（半径、开关机都写在那上面），这也是分桶之外唯一的读。

### 退回时要有破坏的样子

放置被退回去之前先发一次 `LevelEvent.PARTICLES_DESTROY_BLOCK`（2001，带被放方块的状态 id）：
客户端会炸出那块的碎片、放那块的破坏音。方块凭空消失看着像「没放上去」，炸一下才看得出是「被破坏了」。
暮色森林那一台在同样的位置也发这一个事件。

### 资源

两层模型：`antibuilder_type2` 是底座，`antibuilder_type2_glow` 是发光层。
发光层按满亮画（`block_light` 与 `sky_light` 都是 15），
`render_type` 是 `minecraft:cutout`；反建方块二型同构，发光层按 10 级画。
关机用的是 `antibuilder_type2_off`：只有底座、没有发光层，看上去才是真的关了。
方块状态十六个变体（八档 `range` × `powered` 两种），`powered=false` 指向带发光层的模型，
`powered=true` 指向关机模型。两个方块各有一个物品模型，都指向自己的方块模型；两个方块都没有战利品表。

模型的 `textures` 里必须把面上用到的每个键都定义出来：面写 `#bottom` 就得有 `bottom` 这个键，
只写 `down` 还不够，游戏里那一面会画成紫黑格子而不报错。
`gametest/AntibuilderType2Tests` 里有一条逐面查引用的用例把这件事钉住。

场的框线沿用 `world/CubeFieldMarker`，它为这一台加了带半径参数的重载：顶点按 `radius` 而不是固定的 8 算。
客户端绘制，16 格内有玩家才画，服务端不挂 ticker。

`gametest/AntibuilderType2Tests` 覆盖：范围跨度与两端封顶、默认 17 格、10 级光、`#minecraft:mineable/pickaxe`、
方块实体已注册、两个方块都有物品形态且都排在主栏目里、反建方块不进镐子标签；
场里破坏换成反建方块、半径缩到最小以后场外那一格不再反应；
场里放置被退回、反建器家族两样都不动、摆进草里退回草，反建器二型紧贴另一台摆不下来
（正着挨与斜着挨各一条，暮色森林那一台紧贴着摆得下）；
被破坏的暮色反建器复原、另一台反建器二型在别人的场里也复原、机器自己那一格拆得掉且拆掉当场停；
水的流动与方块自己的状态变化照过；创造模式右键加一格、其它模式右键不算交互；
收到红石信号就关机（不反应、不发光、框线不画），信号一断当 tick 恢复；
机器贴着区块边界摆时隔壁区块里的那一格照样管得住（分桶查询走的是 3×3 个桶）；
十六个变体、两层模型与关机模型、逐面的贴图引用都解析得到、两层贴图的发光档位、两个物品模型、
两个方块都没有战利品表、服务端没有 ticker。

## 砷铅铁建造器二型（构建方块）

暮色森林 `twilightforest:carminite_builder` 的第二型。
方块在 `block/TowerdevBuilderType2Block`，建造进度在 `block/entity/TowerdevBuilderType2BlockEntity`，
产物是 `block/TowerdevBuiltType2Block`（`dawnduskterminal:towerdev_built_type2`）。

与那一台只差三条：不会超时（没有格数上限，也没有 `timeout` 那一档）、产物换成上面那一块、
产物稳定存在且能挖能右键换档。其余一律照抄，包括方块属性（`pushReaction(BLOCK)`、`mapColor(SAND)`、
`requiresCorrectToolForDrops`、`WOOD` 音效、10 点硬度 6 点抗性、通着电 4 级光）、
`#minecraft:mineable/pickaxe`、开机与关机的两条音效、通着电时冒的红石粉粒子。

### 不超时

方块状态只有一条 `ACTIVE`（`active`），由红石信号定：摆下那一刻按当时的信号给初值
（`getStateForPlacement`），之后由 `neighborChanged` 跟着信号变。没有 `RANGE`，也没有 `timeout` 那一档。

每 10 tick 造一格，判据与那一台一致：附近最近的那位玩家（`PLAYER_RADIUS` 16 格，创造与旁观之外的都算）的
俯仰角按 90 度一档折成四份——正上看朝上、正下看朝下，其余按水平朝向；
目标格必须是空气，被占了就在原地等，玩家转向或那一格空出来才继续。
游标（`cursor`）记着上一格造在哪，下一格从那里往外推，所以人走开又回来时是从断掉的地方接着走。

产物不会消失，所以再次开机时前方那一段上一次造出来的格子还在。`tick` 因此先把游标沿当前方向
逐格推过「已经是本模组构建方块」的那一段（两档都算），从末端接着造；游标边走边记下，
一条很长的老线也是每 tick 走 `SCAN_PER_TICK` 格、几拍走完，不会一口气扫到底。走到头才是
「空格就造、非空就等」那一套。

两条差别都在 `TowerdevBuilderType2BlockEntity#tick` 里：没有 `blocksMade` 的上限，也没有
`blockedCounter` 引发的停摆与 60 tick 后的 `timeout`。所以「前路被堵住」只表现为原地不动，机器仍是开着的。
造到没载入的区块边上会停一停（`Level#isLoaded`）：那里 `setBlock` 会把区块读进来，一路造下去就是一台撞穿世界的机器。

关机（`active` 变假）当场把 tick 计数与游标清空，重新通电从本方块那一格起头，再靠上面那段扫描走到末端。
进度不落盘，区块重新载入后同样从本方块起头，与那一台一致。

`tick` 有两个入口：方块 ticker 用的四参数版本自己去问最近玩家，五参数版本把玩家递进来。
后者只为测试，生产那条路只有前者，语义不变——gametest 的 `makeMockPlayer` 造出来的玩家不在世界的玩家表里，
`getNearestPlayer` 找不到它，一段「附近的玩家」逻辑没有这个接缝就测不到。

### 两档的构建方块

`TowerdevBuiltType2Block` 是**一块方块两个状态**（`ON`，序列化成 `on`），不是两块方块：
挖掘、战利品表与物品形态因此只写一份，摆下时是暗的那一档。这一点与暮色森林的
`built_block` + `ACTIVE` 同构。

与那一块的四条差别：

- 不会自己消失。那一块被点亮成绿的之后排一次 10 tick 的刻，到点整格删掉并把邻格也点亮，整片会像涟漪一样化掉；
  这一块两档都一直留着。
- 可以挖。那一块 `noLootTable`，这一块带战利品表掉自己；硬度也换成本模组反建方块二型那一档 0.3
  （那一块是 50）：建造器停不下来、方格会一片一片铺开，50 点硬度意味着拆一格要九秒多。抗性仍是 2000。
- 两档都能交互：右键把<b>连在一起、而且档位相同</b>的那一片整片翻到另一档，一次最多 64 格
  （`MAX_CONNECTED`）。搜索是六面广度优先、凑够 64 格就停，所以一大片连着的格子要多右键几次才翻得完；
  斜角挨着的两格不算相连。手上正好是本方块自己的方块物品时让开放置：
  `useItemOn` 返回 `SKIP_DEFAULT_BLOCK_INTERACTION`，不是 `PASS_TO_DEFAULT_BLOCK_INTERACTION`——
  后者在 `ServerPlayerGameMode#useItemOn` 与客户端 `MultiPlayerGameMode#performUseItemOn` 里是
  「接着调 `useWithoutItem`」，也就是换档本身，点击会在放置之前被吃掉（上一版就是这么坏掉的）。
  跳过默认交互之后才轮到 `BlockItem#useOn`，落点是点击面的邻格
  （`BlockPlaceContext#getClickedPos` 在点到的方块不可替换时给相邻格）。
- 绿的那一档没有碰撞（`getCollisionShape` 返回空），并且可以往上爬（`isLadder` 只看绿的那一档）。

内部面剔除走 `skipRendering`：邻格也是本方块时不画中间那一面，否则并排的两格在同一平面上叠两份格线。
`Block#shouldRenderFace` 第一件事就是问 `skipRendering`，所以这条不用碰模型；判据不分档位，
暗的挨着绿的也并起来。

轮廓形状不跟着碰撞变：`BlockBehaviour#getShape` 的默认值就是整方块，与 `getCollisionShape` 无关，
所以穿过去的格子照样点得到，右键才换得回来。

爬梯判定走的是 NeoForge 的 `IBlockExtension#isLadder`（`LivingEntity#onClimbable` 经
`CommonHooks#isLivingOnLadder` 问到它），不是原版那张 `#minecraft:climbable` 标签：
标签管不到状态，而这里只有绿的那一档该能爬。站在绿格里按跳就得到原版梯子那一下 +0.2 的垂直速度，
`handleOnClimbable` 同时把下落钳在 -0.15。

换档的音效照抄暮色森林「重现方块」那一对（`VanishingBlock#tick` 里那几个参数一字不改）：
暗转绿是重现（`TFSounds.REAPPEAR_BLOCK`，`block.twilightforest.reappearing_block.reappear`，
音源 `BLOCKS`、音量 0.3、音高 0.6）；绿转暗是那一声消失（`TFSounds.REAPPEAR_POOF`，
`block.twilightforest.reappearing_block.vanish`，音源 `BLOCKS`、音量 0.3、音高 0.5）。
一条右键只在起点播一声。

### 资源

建造器两层模型：`towerdev_builder_type2_on` 与 `_off` 是底座，各自的 `_glow` 是发光层，
发光层按满亮画（`block_light` 与 `sky_light` 都是 15），`render_type` 是 `minecraft:cutout`；
方块状态两个变体（`active`），物品模型指向 `_off` 那一份——拿在手上的是它摆下来时的样子，不是通电运行时那副。

构建方块一份模型、两档各一张：`towerdev_built_type2_off` 与 `_on`。
贴的是 128x128 的 8x8 连接图集（`_connected`），邻格判定与 UV 重映射复用
`client/model/MountedTowerWoodModel`（「邻居是同一种方块」那条 47 格判据），
`DdtClient#wrapTowerdevBuilt` 把这一块的两个变体键都包一遍。
两档的 16x16 单图是图集里「四面都不接」的那一格，给粒子（`particle`）与物品用：
物品模型是一颗整方块——`minecraft:block/block` 底下自带的那套 `display` 变换加六个面都贴那张单图、
六面 `neoforge_data` 同样写满 15，所以拿在手里是一块透光的格子而不是一张扁图；
这一份不能指回 `block/towerdev_built_type2_off`，那是图集，物品没有邻格可算，会糊成一整张 8x8。
渲染层在 `DdtClient#onClientSetup` 里补了 `ItemBlockRenderTypes.setRenderLayer(cutout)`：
方块那一侧靠模型的 `render_type` 就够，手里那块物品走的是那张表，不在表里透明像素会变成黑底。
「满亮度」在两档都用模型的 `neoforge_data` 写满（六面 `block_light` 与 `sky_light` 都是 15）并关掉 `shade`，
方块自己不发光（`lightLevel` 为 0），两档一样亮。

`gametest/TowerdevType2Tests` 覆盖：两条方块与物品形态、名称、`active`/`on` 各只一条状态、
运行中 4 级光与停机熄灯、`#minecraft:mineable/pickaxe`（建造器进、构建方块不进）、方块实体已注册、
两个方块都在主栏目里；建造 21 格不停（超过那一台 16 格的上限）、没人时一格不造且机器仍开着、人回来接着造、
再次开机跨过已有产物从末端接着造、视线四档折算、前路被占住时原地等、红石开机断信号关机、
右键整片翻档并翻回、斜角不算相连、一次最多 64 格、手上拿着本方块时按原版那一段顺序走一遍并且方块真的摆到了点击面那一格；
并排两格不画中间那一面（暗的挨着绿的也剔）、绿档无碰撞可爬、轮廓仍在；
两个变体的模型与贴图键、满亮档位、连接图集与单图的用途、物品是整方块那一份 `_off` 单图模型、
两个方块各自的战利品表、服务端有 ticker。

## 装饰画

十幅画，尺寸从 1 格宽 2 格高（`painting_ghast_2_sub`）到 6 格宽 4 格高（`painting_ghast_9`）。
其余八幅是 `painting_ghast_1`（3x2）、`painting_ghast_2`（2x3）、`painting_ghast_3`（3x2）、
`painting_ghast_4` 到 `painting_ghast_8`（各 2x3）。
宽高的来源是贴图像素除以 32：96x64 是 3x2，64x96 是 2x3，192x128 是 6x4。
画名只写在语言文件里（`painting_ghast_3` 是 Drop，往下依次是 Ragdoll、Bakery、Butchess、Maze、Onesama、Feuer），
与注册 ID 无关。
变体属于数据包注册表，条目位于 `data/dawnduskterminal/painting_variant/`；
`registry/ModPaintingVariants` 只放置指向它们的 `ResourceKey`，并把每一幅与它唯一对应的那件物品绑成一条 `Kind`，
尺寸不在 Java 侧重复声明。

贴图放置在 `assets/dawnduskterminal/textures/painting/`。
原版 `atlases/paintings.json` 只声明了「扫描 `textures/painting` 目录」，该目录源跨命名空间收图。
因此放入这个位置就会被 `minecraft:paintings` 图集收录，渲染走原版 `PaintingRenderer`，不需要自己写。

物品图标的贴图必须另外放一份到 `textures/item/`。
1.21.1 的物品模型贴图从 `minecraft:paintings` 之外的 `blocks` 图集获取，
而该图集只声明了 `block/` 与 `item/` 两个目录源，`textures/entity/` 下的图不会被收录，
模型因此会渲染成紫黑格子（`Missing textures in model ...`）。

`entity/GhastPaintingEntity` 是独立的实体类型，十幅画各占一个，都不共用 `minecraft:painting`。
实体类型名与变体名相同（`ModEntities#painting`），种类由注册时的 factory lambda 带进构造。
原版画物品只从 `#minecraft:painting_variant/placeable` 中挑变体，而本模组的变体不进该标签，所以原版物品放不了它们；
实体又把 `setVariant` 固定为自己的变体，敲碎后掉落的是自己那件物品，中键选取得到的也是它。

`mixin/PaintingVariantGuardMixin` 堵的是手工修改这条旁路：原版画实体上的 `setVariant`
遇到本模组任意一幅画的变体时直接忽略。
所以无论用 `/summon` 指定变体，还是改旧存档里的 NBT，都拿不到本模组的画。

`item/GhastPaintingItem` 的摆放流程参照 `HangingEntityItem` 编写，一件物品只能放置它自己那一幅画，且没有配方。

## 红篝火

`block/CampCarminiteFireBlock` 是一堆压着砷铅铁火焰的红木头，模型继承原版篝火的
`template_campfire` 与 `campfire_off`，只替换三张贴图。

状态只有 `FACING`、`LIT` 与 `WATERLOGGED`，没有原版的 `SIGNAL_FIRE`（那一位只管蜂箱冒不冒信号烟）。
本类**不继承** `CampfireBlock`：那一类的 `codec()` 写死了 `CampfireBlock::new`，子类换不掉（泛型不协变），
方块编解码转一圈会变回原版篝火。
代价是点燃与熄灭两条工具动作得分开接：点燃走 NeoForge `FIRESTARTER_LIGHT` 的默认实现，
它只要求 `#minecraft:campfires` 标签加 `LIT`、`WATERLOGGED` 两个属性，本方块满足；
熄灭走方块自己的 `getToolModifiedState`，因为 `SHOVEL_DOUSE` 的默认实现判的是 `instanceof CampfireBlock`。

**它是一台篝火，能烤东西。** `block/entity/CampCarminiteFireBlockEntity` 自带原版篝火那整套数据：
四格食材、逐格烧烤进度与总时长、点燃时 `cookTick`、熄灭时 `cooldownTick`、
「这一份能不能烤」的配方查询、放食材、`CONTAINER` 组件、熄灭后重画客户端。
不能直接继承原版 `CampfireBlockEntity`：它的构造器把类型写死成 `BlockEntityType.CAMPFIRE`，
那个类型不认本方块，`BlockEntity` 构造时的校验会直接抛 `Invalid block entity`。
客户端那四格食材由 `client/CampCarminiteFireRenderer` 画，摆法与朝向照原版 `CampfireRenderer`。
火舌的透明度另有一条：1.21.1 的方块渲染层只认 `ItemBlockRenderTypes` 的 `TYPE_BY_BLOCK` 表，
原版在那张表里给 `minecraft:campfire` 写了 `cutout`；本方块不在表里，
所以客户端 setup 里补一条 `setRenderLayer(..., cutout)`，否则透明像素会变成不透明的黑块。

`entityInside` 与「砷铅铁火焰」同一条：点着时接触 2 点伤害，伤害类型是本模组的 `carminite_fire`，不点火。
粒子也是同款：`particle_carminite_ember` 每次三颗，撒在方块中间，竖直速度 0.2~0.25。
它本来就是篝火，所以照原版那样每 tick 有十分之一的概率响一声 `block.campfire.crackle`；
不抄原版的 `minecraft:lava` 火星，那属于明火。

挖掉掉自己，走 `loot_table/blocks/campcarminitefire.json`，表里没有工具条件，空手也掉；
火堆上还摆着的食材一起掉出来。
方块挂在 `#minecraft:mineable/axe` 与 `#minecraft:campfires` 两个标签上。

### 消耗度倒转

`world/CampCarminiteFireAura` 按维度存一张「篝火在哪」的表，登记与判定照 `CubeField` 那一套：
方块实体在区块载入/卸载时登记与撤登记，方块的 `onPlace` / `onRemove` 管摆下与挖掉，
所以摆下那一 tick 就生效。
判定只量现存的篝火，不扫 33³ 格，每条还会回头核那一格是不是点亮着的本方块。

点亮的红篝火 16 格内的玩家受两条规则。

- **小增量取反**：凡是增加消耗度、且增加量不大于 1 的行为，改成等量减少。
  钩子是 `mixin/CampCarminiteFireExhaustionMixin`，打在 `Player#causeFoodExhaustion` 上。
  原版玩家动作全都从这里进：挖掘 0.005、奔跑 0.1、跳跃 0.05 与 0.2、攻击 0.1 与 0.2、受伤 0.1、
  饥饿效果与吃东西带的那点。
- **大于 1 的增量一点也没变**：照旧加进消耗度，照旧按原版「大于 4 就扣一点」扣饥饿值或饱和度。
  自然回血那两条（快速回血按饱和度算的那点、慢速回血的 6.0）走的是 `FoodData#addExhaustion`，
  那里拿不到玩家、判不了距离，本来就不拦。
- **见底就补**：消耗度见底时恢复 1 点**饥饿值**，饥饿值满了才补饱和度，然后把消耗度压回 4。
  所以见底一次要再走完这 4 点才会补下一次。

  先饥饿值是必须的，不是随手挑的顺序：原版回血的两道门都只看饥饿值，都在 `FoodData#tick` 里——
  快速回血要 `饥饿值 >= 20 且 饱和度 > 0`，慢速回血要 `饥饿值 >= 18`。
  饥饿值掉到 17 以下之后，补饱和度永远补不回这两道门，玩家看到的就是「怎么都不回血」。
  之前那版先补饱和度，正好踩在这上面。

结算有两条入口：取反把消耗度压到 0 时当场补一次（`CampCarminiteFireAura#absorb`）；
玩家每 tick 那一下补「走过来时消耗度本来就是 0」那一种，
路径是 `DdtServerEvents#onPlayerTick` → `CampCarminiteFireAura#tick`。
服务器停下时气场表清空。

## 恶魂皮方块

两张恶魂皮各有自己的方块形态，`registry/ModBlocks` 里注册成普通整方块，
物品由 `registry/ModItems` 的 `registerSimpleBlockItem` 生成，所以名字取的是 `block.` 那条翻译键。

- 方块属性照原版羊毛：0.8 点硬度、`WOOL` 音效、不要求工具、挖掉掉自身。
  战利品表在 `data/dawnduskterminal/loot_table/blocks/<id>.json`，只要 `survives_explosion` 那一种。
- 物品图标不跟着方块模型走：`models/item/<id>.json` 仍然是 `item/generated` 加各自那张皮革，
  拿在手上是皮革，摆下去才是方块。
- `carminite_ghast_leather` 有六个随机朝向：`blockstates/carminite_ghast_leather.json` 里那串带
  `x`/`y` 旋转的变体按方块位置挑一条，同一格拆了重放不会变。
  朝向不存进方块状态，服务端根本看不见，与 `rosy_dirt` 那几块噪点方块同一条路。
- `carminited_leather` 走 47 格连接纹理：方块模型贴 `block/carminite_leather_connected` 那张 8x8 图集，
  粒子与物品图标贴 16x16 的 `block/carminite_leather`。
  UV 由 `MountedTowerWoodModel` 按邻格逐面重映射，`client/DdtClient` 的 `onModifyBakingResult` 里与
  `mounted_towerwood` 那几块一起包。
- `gametest/BlockStyleTests` 钉住这几条：两块与羊毛同音效同硬度、不要求工具、掉落表掉自身、
  物品是这两块方块的 `BlockItem`、六个朝向各不相同、连纹模型的三处贴图指向。

## 维度与晨昏线

`registry/ModDimensions.CHRONO` 是维度的 `ResourceKey`。

`world/ChronoLine` 是一个 record，存储原点与单位法向。
构造时拒绝非有限坐标以及非单位法向，配置、存档与网络共用该验证规则。

`ChronoLine.distance(x,z)` 计算点到该线的有符号距离。

`ChronoLine.param(x,z)` 计算距离除以最大距离的结果，并钳制到 -1 到 1。

`ChronoLine.create(random)` 为每个世界随机生成一次法向，基准朝东，抖动范围为正负 30 度，再随机决定是否旋转 180 度朝西。

配置项 `normal_override` 可以固定法向。

`world/ChronoLineState` 继承 `SavedData`，存储在主世界，键为 `dawnduskterminal_chrono_line`。
仅在确认数据文件不存在时创建晨昏线。
既有文件无法读取或解码时中止启动，不以默认方向替代，也不覆盖原文件。

服务端与客户端各有一份静态缓存 `serverLine` 与 `clientLine`。

`network/ChronoLinePayload` 在玩家登录时把线下发到客户端。

法向每个维度只计算一次，多个玩家共用同一条线。

## 群系源

`world/PaletteBiomeSource` 是自行编写的 `BiomeSource`，注册为 `dawnduskterminal:palette`。

### 当前实现

编解码要求提供 `preset` 与 `palette`，并允许提供可选的 `extra` 列表。
当前维度使用原版主世界气候参数预设 `minecraft:overworld`，以及模组的 `palette_biomes` 群系标签。
`extra` 将自建气候点追加到原版参数表末尾。

`getNoiseBiome` 通过 `ChronoTemperature` 将采样温度映射到晨昏线分带，并保留其他气候维度的采样值。
采样深度低于阈值时，直接返回调色板中的 `floatrees` 群系。
其余情况使用扩展气候表的 `ParameterList.findValue` 选点，再将 `minecraft:*` 结果映射到模组群系。
非原版命名空间的选点结果直接返回。

默认替换关系按路径匹配，例如 `minecraft:plains` 映射为 `dawnduskterminal:plains`。
替换表另将 `minecraft:desert` 映射为白地（`dawnduskterminal:white_land`）。
映射只决定选点结果对应哪个模组群系，不改变气候表的选点算法。

原版气候参数表的温度范围为 `[-1, 1]`，`ChronoTemperature` 在极带将温度向 `±2` 延伸。
维度 JSON 的 `extra` 当前包含三项：极昼的 `polar_dawn`，以及极夜按湿度区分的 `polar_dusk` 与 `blue_desert`。
极带因此使用自建群系，并非继续沿用相邻带的群系。

替换表延迟构建，因为群系源在数据包解析期创建，此时标签中的 holder 尚未提供 key。

### 占位内容与替换方向

晨昏线维度使用本模组的群系 ID，其中部分群系内容暂以原版或暮色森林的内容占位。
后续逐个替换为自有群系设计，不要求保留来源群系的地形、植被、环境或刷怪配置。

替换内容时优先保留已有群系注册 ID，避免同时引入注册身份变化。
删除或重命名 ID 前，必须单独评估存档兼容与引用迁移。
自有群系需要不同的气候分布时，再调整替换映射或追加气候点；当前占位分布不构成最终设计约束。

特征引用遵循 `AGENTS.md` 的世界生成约定。
需要使用模组调色板的原版特征引用模组移植版本，复用的暮色森林特征可以直接引用。
占位内容替换后，不再需要的暮色森林特征可逐项移除或替换。

世界生成细节见 `worldgen.md`。

## 世界生成补丁

数据包无法覆盖的原版硬编码部分由 `mixin/` 接管。

`LavaBandPaletteMixin` 把 `createFluidPicker` 中 y 以下的岩浆带替换为热情的暮光。

`LavaAquiferPaletteMixin` 把深部岩浆含水层替换为热情的暮光。

`PillarsFluidBlockMixin` 使石柱洞穴保持干燥。

`NoiseChunkVeinPaletteMixin` 使用 `world/ChronoVeinPalette` 把大矿脉中的花岗岩与凝灰岩替换为赭石与暮光石。

温度的晨昏线偏移不在此处实现，而是由 `world/PaletteBiomeSource` 与 `world/ChronoTemperature` 负责，见「世界生成补丁」。

废弃矿井不在这里，它是复制出来的结构，见「结构与废弃矿井」。

识别维度统一依靠噪声设置的 `default_fluid` 是否为 `portal_fluid`。

## 传送门

`portal/PortalShape` 负责水池识别与判定。

`portal/PortalBuilder` 把水池方块替换为传送门流体。

`portal/PortalTrigger` 判定实体是否处在可用的传送门中。

`portal/PortalBlock` 是传送门流体的方块，负责处理方块内的实体与自愈。

`portal/PortalTeleporter` 负责即时传送、落点搜索与到达门。

`portal/PortalState` 是玩家附件，存储回程维度与坐标。

细节见 `portal.md`。

## 苍穹船

`entity/CanopyBoatEntity` 是原版 `Boat` 的子类，物品 `dawnduskterminal:canopy_boat` 由 `item/CanopyBoatItem` 摆出来。

整条船的行为都继承自 `Boat`，只改三处：

- 变体固定 `Boat.Type.BAMBOO`：竹筏那套模型与 128x64 贴图展开就是 `textures/entity/boat/canopy_boat.png` 的底稿，
  客户端渲染用竹筏的 `RaftModel`，只换贴图（`client/CanopyBoatRenderer`）；
- 敲碎掉自己，父类按变体掉的是竹筏；
- `canBoatInFluid` 交给 `world/BoatFloatingFluid`：水、岩浆、`portal_fluid` 三种都托得住，热情的暮光不算。

漂浮判定没有一处 mixin：NeoForge 的 `IBoatExtension` 已经把原版那三处写死的 `FluidTags.WATER`
抽成了 `canBoatInFluid(FluidState)` 钩子（默认实现是 `FluidState#supportsBoating`），覆写它即可。
只对苍穹船生效，原版船与别的模组的船不受影响。

岩浆那一条还有两个前提：

- **实体类型必须 `fireImmune()`**。岩浆里的非生物实体照样走 `Entity#lavaHurt`：点燃 15 秒、再挨 4 点
  `lava` 伤害，而船的耐久只有 40 点、每 tick 结算一次，十来个 tick 就被 `destroy()`。免火之后这条整路都不进来。
- **乘客不受不稳定液体影响**。船浮在液面上时乘客的脚底在液面之上，但方块判定只看「箱子有没有压进这一格」，
  乘客的箱子仍然与液体那一格相交，`PortalBlock#entityInside` 于是照样触发——每 2 秒 2 点 `stability` 伤害，
  站在真门前还会被传送走。所以那里对「坐在苍穹船上的人」直接放行，见 `PortalBlock`。

`gametest/CanopyBoatTests` 钉住：实体与物品都在、变体是竹筏、掉自己、免火；三种液体各摆一池子放船推 40 tick，
船停在水面（相对高度约 1.4）、没沉底、也没被岩浆烧掉；坐在船上的猪挨不到不稳定液体的伤害而对照组会挨；
以及那几条配方。

## 流体

`registry/ModFluids` 注册 `portal_fluid`，流动参数参照水。

`registry/ModScorchingTwilight` 注册 `enthu_fluid`（热情的暮光）的类型、流体、方块与桶。

`world/FluidReactions` 在 `FMLCommonSetupEvent` 上把接触反应注册进 NeoForge 的 `FluidInteractionRegistry`。

`block/ScorchingTwilightBlock` 继承岩浆的方块属性，但不会引燃，也不会清除植物，接触时造成 6 点 `enthu_fluid` 伤害。

`item/CanocanItem` 是唯一能够舀取这两种流体的容器，原版桶会被销毁。

它还多一门用处：拿它点暮色森林那两块「正变绿」的方块，可以把方块整个取走，换来一枚 `experiment_028`。

两个绿色窗口都写在方块状态上，所以判定只看状态，不用等 tick：

- `twilightforest:reappearing_block`（重现方块）：被点过之后缩成小方块，临回来之前那 15 tick 是
  `vanished && active`（模型换成发绿的那一份）。窗口一过它自己长回原样。
- `twilightforest:built_block`（砷铅铁建造器搭出来的方块）：被激活之后 10 tick 是 `active`（同样是发绿那一份）。
  窗口一过方块直接消失。

窗口里方块本来就已经不属于「正常世界」了：这里把它整个拿掉（`removeBlock`，不掉任何东西），
发一枚 `experiment_028`，方块自己的定时逻辑随之作废——重现方块不会长回来，建造方块也不会自己消失。
取走前发一次 `LevelEvent.PARTICLES_DESTROY_BLOCK`，客户端看得见方块是被拿走的。

不是这两块、或者不在窗口里，`useOn` 一律返回 `PASS`，罐子照旧走 `use` 那条舀流体的路。
`gametest/CanocanPickupTests` 覆盖：两个窗口各取一次（方块消失、进账一枚 028）、
窗口外三块方块与一块石头都不动也不给东西、奖励就是注册表里的 `dawnduskterminal:experiment_028`。

## 进度与效果

`progression/BossKind` 枚举三个暮色森林 Boss 与对应的战利品。

`progression/BossProgress` 是 record，存储三个击败标记。

`progression/BossProgressTracker` 在击杀事件中写入附件。

附件定义在 `registry/ModAttachments` 中，`boss_progress` 会被序列化并设置 `copyOnDeath`。

`registry/ModEffects` 注册 `stability`、`compound_erosion` 与 `portal_sickness`。

`world/StabilityEffect` 是纯标记效果，方向由 `PortalBlock` 决定。

`world/CompoundErosionEffect` 每 2 秒造成 3 点 `stability` 接触伤害并播 `portal_hurt`，
颜色与 `stability` 同为 `0x7F159E`；药水与配方见 `crafting.md` 的「复方侵蚀药水」一节。

`advancement/LineParamTrigger` 是自定义判定，按晨昏线投影参数给予进度。

## 客户端

`client/DdtClient` 是客户端入口，负责注册模型、颜色、渲染层与事件。

`client/ChronoFoliageColors` 把群系副本解析成 ID，交给 `world/ChronoPlantTints` 算色。

`world/ChronoPlantTints` 是植物着色的纯函数：稀疏丛林用世界坐标上的三层值噪声取彩虹色（不用暮色森林那个
32 格一平铺的线性公式），河海类固定 `portal_fluid` 的雾色 `0x7F159E`，`jungle` 与 `dark_forest` 沿用原有的
树叶染色，其余群系原样。蓝色沙漠那三种植物（两种仙灵石竹与夜光灌木）不参与群系着色：
它们的色相按坐标算（`fairyDianthusBand`，返回烘好的色相带档号），由 `client/model/HueBandModel`
在区块网格里按坐标缩 UV 选带——不走顶点色，因为顶点色是乘数。
规则与彩虹的算法见 `worldgen.md` 的「植物着色」。

`block/AbstractFairyDianthusBlock` 是仙灵石竹的共用底座（沙土支撑、断开、形状）；
`block/FairyDianthusBlock` 额外带四向连接状态与五个 `Part`：`stem` / `branch` / `turn`（分支柱底格，柱子抬起 4 像素）/
`drop`（向下拐角，柱子占 0~12 像素）/ `hang`（下垂柱，吊在上面那一格上），
`block/FairyDianthusSmallBlock` 只靠模型变体做偏移。
`item/FairyDianthusItem` 只在点到侧面时接分支。两种植物由 `world/FairyDianthusFeature` 一簇摆完，
落在蓝色沙漠，几何与参数见 `worldgen.md` 的「仙灵石竹」。
`item/StellariaVegesItem` 是挖掉这两种植物得到的繁缕菜（食物 2 饥饿 / 2 饱和，燃料 800 tick），
掉落规则见同一节。
`world/FairyDianthusFeature#placeDownward` 摆出的向下分支（拐角 + 下垂柱）挖任意一格整列一起拆，
掉一件 `dawnduskterminal:fairy_haniwa`。

`world/ChronoFreezeFeature` 负责铺 `canopy_frost` 与 `frozen_canopy`，蓝色沙漠那一格是例外（不铺霜片）。

`client/model/MountedTowerWoodModel` 负责镶边塔木、封装塔木梁、錾制塔木梁与警示塔木的连接模型。
「47 格」索引公式位于 `client/model/ConnectedTexture47`。

`client/model/CountertopModel` 用同一张 8x8 图集绘制黑木台面的顶面，只裁剪顶面那一格。
连接判定是「邻居是任意一种台面」。

`client/PortalFluidExtensions` 与 `ScorchingTwilightFluidExtensions` 是流体的客户端扩展。

`DdtClient` 注册方块与物品颜色，草与树叶随群系染色。

天空全部交给原版渲染器，维度特效直接复用 `minecraft:overworld`，见「天空」。

## Mixin

配置为 `dawnduskterminal.mixins.json`，分为公共与客户端两份。

`FluidStatusAccessor` 向其他 mixin 暴露 `Aquifer.FluidStatus` 的流体类型。

`LavaBandPaletteMixin`、`LavaAquiferPaletteMixin` 与 `PillarsFluidBlockMixin` 见「世界生成补丁」。

`NoiseChunkVeinPaletteMixin` 见「世界生成补丁」。

废弃矿井没有 mixin：原版那三条（高度、战利品、木材）都改成了复制出来的结构，见「结构与废弃矿井」。

`FeatureSorterMixin` 把原版特征排序替换为可容忍环的实现，受配置项 `tolerate_feature_cycles` 控制。

`LightEngineSkyLightMixin` 在晨昏线维度把天光上限压低到 `ChronoSkyLight.maxSkyLight`。

`StructureExclusionMixin` 在晨昏线维度只放行结构白名单中的结构。

`BiomeColorsPlantMixin` 是客户端 mixin，把 `BiomeColors` 的草与树叶两个取色器接到 `ChronoFoliageColors`。

`ClientLevelTimeOfDayMixin` 与 `LevelRendererSkyAlignMixin` 是客户端 mixin，见「天空」。

`PaintingVariantGuardMixin` 见「装饰画」。

`CampCarminiteFireExhaustionMixin` 见「红篝火」。

`ClientLevelSkyColorMixin` 是客户端 mixin，见「天空」。

## 天空

自绘天空已经移除，天空全部由原版 `LevelRenderer.renderSky` 处理。

维度特效直接复用 `minecraft:overworld`。
雾色、日月、星空、日落光晕与云都是原版的，`dimension_type/chrono.json` 的 `effects` 因此不再是模组自己的键。

`ClientLevelTimeOfDayMixin` 在晨昏线维度把 `getTimeOfDay` 替换为 `0.25 - param * sun_angle_max / 360`。
原版那一整套渲染因此随玩家到晨昏线的距离变化，太阳仰角仍为 `param * sun_angle_max`。

原版日月轨道始终落在 X 轴上。
`LevelRendererSkyAlignMixin` 在 `renderSky` 的两处 `pushPose` 之后追加一层绕世界 Y 轴的旋转。
轨道与日落光晕因此转到晨昏线法向上。

`fixed_time` 仍保留在维度类型中，它只影响服务端的 `getTimeOfDay`，客户端渲染不再读取它。

`ClientLevelSkyColorMixin` 把晨昏线维度的 `getSkyColor` 乘以 0.75。
原版日月采用加色混合，而极昼侧自定义群系的 `sky_color` 接近白色（`white_land` 为 `FFF9F0`）。
加到纯白上仍然是纯白，太阳因此与天空混为一体、无法区分。
天穹色同时决定雾色，所以这一处理也降低了过曝的雾。
常数位于 mixin 中，调小则画面更暗。

## 事件

`server/DdtServerEvents` 挂载到 `NeoForge.EVENT_BUS`。

`onServerStarted` 读取并记录晨昏线，同时校验结构与摆放表。

`onPlayerLoggedIn` 下发晨昏线包。

`onPlayerTick` 每 10 tick 扫描玩家附近的投料物，并在玩家进入维度后触发进度判定。

`onLivingDeath` 记录 Boss 击杀。

`onRightClickItem` 拦截原版桶，禁止舀取两种模组流体。

## 结构与废弃矿井

`structure/StructureExclusions` 是唯一白名单，`StructureExclusionMixin` 排除不在其中的结构。

`structure/StructureExclusions` 中的 `UNMAPPED` 与 `LANDMARKS` 记录未放行项与地标，仅用于日志。

服务端启动时校验这几张表，缺失时只记录日志。

`registry/ModStructures` 注册两个结构类型（`dawnduskterminal:grid` 与 `dawnduskterminal:mineshaft`）
以及矿井那四个件类型（`mineshaft_room`、`mineshaft_corridor`、`mineshaft_crossing`、`mineshaft_stairs`）。
件类型必须注册，否则从磁盘读回来的件认不出自己。

`structure/GridStructure` 是网格屋群的生成器，`structure/GridFrame` 负责布局规划。

`structure/ChronoMineshaftStructure` 与 `structure/ChronoMineshaftPieces` 是复制出来的废弃矿井，
逐段对应 1.21.1 的 `MineshaftStructure` 与 `MineshaftPieces`，只改了木材套组、竖向带与战利品表。

`structure/ChronoMineshaftWoods` 是那四套暮色森林木头与「按地表群系挑一套」的规则。
细节见 `worldgen.md` 的「废弃矿井」。

## 已知遗留

`registry/ModEffects` 仍注册 `portal_sickness`，但已经没有任何调用方施加该效果，方向改由 `stability` 决定。

`config/DdtConfig` 的 `portal.cooldown_ticks` 与 `portal.entity_debuff` 已经没有读取方。

语言文件中 `death.attack.dawnduskterminal.portal` 与 `message.dawnduskterminal.searching` 已不再使用。
