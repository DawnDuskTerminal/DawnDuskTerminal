# 试验物品与衍生机器

本文记录试验物品及其衍生物的当前规格。
Java 路径均相对于 `src/main/java/com/dawnduskterminal/`。
注册关系见 [架构总览](architecture.md#试验物品)，配方数据见 [合成与标签](crafting.md)。

## 注册与资源约定

`registry/ModExperimentItems` 的 `TAKEN` 记录已有独立实现的序号。
其余序号由 `TEXTURED` 与 `PLACEHOLDERS` 注册普通物品，`simpleItems()` 返回两者的合集。
前者的模型引用同名图标，后者引用 `experiment_temp.png`；当前分别为 32 项与 40 项。
`gametest/ExperimentItemTests#placeholderTableMatchesTheIcons` 将这两张表与 115 份模型逐项比较。

创造栏由 `registry/ModCreativeTabs` 按序号排列，115 直接引用暮色森林的 `TFItems.EXPERIMENT_115`。
`withTabsBefore(dawnduskterminal:main)` 保证试验物品栏位于主栏目之后。
未声明相对顺序的栏目由 NeoForge 按注册名字母序排列，注册先后不能保证相邻。
血肉之书、可疑蛋糕与 115 再生机没有独立试验序号，位于主栏目。
104 的半块不进入创造栏。

普通方块的状态、模型、物品模型与战利品表分别位于以下目录。
下文仅补充特殊资源约束，不逐项重复相同路径。

| 内容 | 目录 |
|---|---|
| 方块状态 | `assets/dawnduskterminal/blockstates/` |
| 方块模型 | `assets/dawnduskterminal/models/block/` |
| 物品模型 | `assets/dawnduskterminal/models/item/` |
| 方块战利品表 | `data/dawnduskterminal/loot_table/blocks/` |

测试类位于 `gametest/`，运行与隔离要求见 [游戏内测试](development.md#游戏内测试)。
模型原件及动画运行时约束见 [Bedrock / YSM 动画运行时](ysm.md)。

## 基础物品与食物

### 001、007、008、017 与普通装备

001 是合成台方块，注册于 `ModBlocks`。
007 与 017 共用 `meleeAttributes`，面板为近战伤害 4、攻击速度 5，不设置工具等级。
两者使用原版基础攻击修饰符 ID，属性保存在各自物品栈组件中，保证提示显示最终面板值。
玩家攻击力底数为 1、攻击速度底数为 4，因此修饰符分别为 +3 与 +1。
008 提供 5 点饥饿值与 5 点饱和度，饱和度系数为 0.5。
`FoodProperties` 的饱和度点数为「系数 × 饥饿值 × 2」。
普通工具与物品的完整序号分配以 `ModExperimentItems` 和 `ModCreativeTabs` 为准。

### 011：盘子

`entity/Experiment011PlateEntity` 是无重力、静止的展示实体，碰撞箱为 `14/16 × 1/16 × 14/16`。
`item/Experiment011Item` 只接受朝上的放置面，高度取支撑方块碰撞箱的 `bounds().maxY`。
上方 1 像素空间被占用时拒绝摆放。

盘中只能展示一件物品，同步字段为 `DATA_DISPLAYED`，存档键为 `displayed`。
持物右键放入，空手右键取出，潜行空手右键收回盘子；破坏时盘子与展示物一同掉落。

`client/Experiment011PlateRenderer` 使用 `textures/block/experment_011.png`。
文件名中的 `experment` 必须保留，以免破坏资源引用。
盘面取中央 14×14 像素，四侧各取一行像素。
展示尺度为 12 像素，必须渲染实际模型，不能用粒子贴图替代。

- 方块物品交给方块渲染器，原点补偿为 `translate(-0.5, 0, -0.5)`。
- 其余物品使用 `ItemRenderer#renderStatic` 的 `FIXED` 上下文，旋转为 `XP(-90)` 与 `YP(180)`。
  原版物品渲染器已经平移 `(-0.5, -0.5, -0.5)`，不得重复补偿。

### 012：杯子

`block/Experiment012Block` 继承 `BushBlock`，状态 `cups` 为 1 至 3。
每只杯子尺寸为 4×6×4 像素，位于格底；多只杯子水平错开放置，不向上堆叠。
摆位取原版海泡菜模型的起点：一只居中，两只为 `(3,3)` 与 `(8,8)`，三只为 `(6,9)`、`(2,2)`、`(8,4)`。
碰撞箱取各杯子形状的并集。
`canBeReplaced` 允许同物品继续放置，`getStateForPlacement` 增加杯数。
战利品表按 `cups` 掉落 1 至 3 件。

三个 `experiment_012_1/2/3` 模型均为 `minecraft:cutout`。
方块贴图为 `textures/block/experiment_012.png`，边带取 5 至 11 行，顶面取 1 至 5 行。
物品图标独立使用 `textures/item/experiment_012.png`。

### 019：植物

`block/Experiment019Block` 继承 `BushBlock`，只能立于 `#minecraft:dirt` 或耕地上。
支撑消失时植物掉落自身。
模型使用 `minecraft:block/cross` 与 `minecraft:cutout`，贴图引用 `item/experiment_019`。
物品模型使用 `item/generated`。
模组方块的透明裁剪由模型 `render_type` 指定，不依赖已废弃的植物渲染层注册方式。

### 020：随机效果食物

`item/Experiment020Item#rollEffects` 在食用时生成效果表，随机源由参数传入以供测试。
食物提供 20 点饥饿值与 20 点饱和度。
每次随机选择 0 至 3 个互不相同的 I 级效果，各持续 10 至 300 秒。
候选池排除三个瞬发效果。
非原版效果还要求能在 `BuiltInRegistries.POTION` 中找到对应药水瓶，原版效果不受该条件限制。
效果表本身随机，不能以固定的 `FoodProperties#effects` 替代。

### 104：两段式食物

`ModItems` 注册不可堆叠的 104 与 `experiment_104_half`。
首次食用通过 `usingConvertsTo` 将物品替换为半块，第二次消耗半块。
每次提供 10 点营养与 0.6 的饱和度系数，即 12 点饱和度；两次合计为 20 与 24。
半块没有 `usingConvertsTo`，只能通过食用产生。
半块必须先于 104 注册，因为 104 的注册回调需要取得半块物品栈。

### 061、070、074：不明肉块

三件物品共用 `item/UnknownMeatItem.FOOD`，仅贴图不同。
营养为 2，饱和度系数为 0.3，即 1.2 点饱和度。
食用时独立判定两次：30% 概率获得 30 秒饥饿，30% 概率获得 5 秒中毒。
固定效果表使用 NeoForge 的 `Supplier` 重载，在服务端由原版食物逻辑判定。
描述键为各物品描述 ID 加 `.desc`，显示为「不明肉块」。

### 114：可食用方块

`block/Experiment114Block` 复制暮色森林 115 的状态、形状与红石机制。
状态名仍为 `omnomnom`（0 至 7）与 `regenerate`，不得因整理文档而修改注册或存档字段。
与 115 的差异如下。

- 放置时为完整 8 片，对缺口使用 114 一次补满，潜行收回要求方块完整。
- 每消耗一片随机播放恶魂受伤音效。
- 撒红石粉后的方块不随机补片，改为接收红石信号时随机播放恶魂受伤音效。
  同一位置已有计划方块刻时不重复发声，避免多轮邻居更新产生重复音效。

`item/Experiment114Item` 提供 32 点营养与 0.3 的饱和度系数。
对自身方块使用时优先交给方块；非潜行时尝试放置，无法放置时才食用。
食用完成后在服务端以随机音高与音量播放 `entity.ghast.hurt`。
方块没有随机刻。

模型与侧、底、切口贴图沿用 `twilightforest:block/experiment115/*` 和 `twilightforest:block/blank`。
顶面使用 `dawnduskterminal:block/experiment114_top`。
16 个模型对应片数与 `regenerate` 的组合；再生模型的父模型必须指向本模组命名空间。
再生模型只覆盖 `top_2`，`top` 从父模型继承。

### 七种可疑蛋糕与 107

`block/SusCakeBlock` 与 `item/SusCakeItem` 按 `ModBlocks#SUS_CAKE_FLAVORS` 注册七种口味。
ID 为 `sus_<口味>_cake`，口味包括 `bloodorange`、`cheese`、`cherrycheese`、`lamington`。
其余三种为 `pavlova`、`pineapple` 与 `redvelvet`。

单件物品代表一片，放置时 `omnomnom=7`，完整方块为 8 片。
同种物品对缺口使用时补一片，满片返回 `FAIL`；潜行取回一片，最后一片时移除方块并返还物品。
空手食用经 `useWithoutItem` 处理。
比较器输出为 `8 - omnomnom`，没有独立红石信号，也没有 `regenerate` 状态。
随机刻选中未满方块时补一片，不作第二次概率判定。
默认 `randomTickSpeed=3` 时平均约 68 秒补一片。

`ModBlocks#SUS_CAKE_SERVINGS` 定义每片营养：默认饥饿值 6、饱和度 4，系数为 1/3。
血橙与红丝绒为 8 与 8，系数为 0.5。
方块沿用 115 的切口与碰撞形状，以及 `noLootTable`、`DESTROY`、羊毛音效和 0.5 硬度。
每种口味有八个片数模型；四张表面贴图使用本模组资源，`top_2` 仍引用暮色森林的 `blank`。
红丝绒贴图仍为占位资源，正式美术资源可在原路径替换。
六条无序配方不包含红丝绒，原料表见 `data/dawnduskterminal/recipe/sus_*_cake.json`。

`item/Experiment107Item` 是整块红丝绒蛋糕的物品，方块仍为 `sus_redvelvet_cake`。
它放置完整 8 片，没有食物组件，进入试验物品栏的 107 号。
描述 ID 使用自身物品键，模型以 `block/sus_redvelvet_cake_8_8` 为父。
`registerBlocks` 必须为空，避免覆盖 `Item.BY_BLOCK` 中单片物品的映射，影响补片、取片与 `asItem()`。
其余交互与单片红丝绒共用。
测试入口为 `gametest/SusCakeTests`。

### 076：反应方块

`experiment_076` 为 15 级光源方块，有独立方块实体，服务端与客户端均无 ticker。
`client/Experiment076Renderer` 渲染世界外观，`Experiment076ItemRenderer` 渲染物品。
两者共用 `Experiment076Cube`，六个面每 tick 各选候选贴图，保持满亮。
物品模型使用 `builtin/entity` 并配置完整方块的 `display`，方块回退模型为 `cube_all`。

仅 `playerWillDestroy` 触发砷铅铁反应堆反应，替换或爆炸破坏不触发。
计数超过 5 时，3×3×3 内不得留下假金块或假钻石块；推进到 120 时以 `reactor_debris` 填充塌陷区域。
反应为全局过程，测试结束必须调用 `clear()` 并恢复场地。
方块掉落自身，进入 `#minecraft:mineable/pickaxe`。
`gametest/Experiment076Tests` 检查注册、反应与资源，外观仍须客户端验收。

## 容器与加工机器

### 115 再生机

`block/E115RegeneratorBlock` 与 `block/entity/E115RegeneratorBlockEntity` 注册为 `e_115_regenerator`。
工作要求顶面为完整且撒过红石粉的 115，即 `omnomnom=0`、`regenerate=true`，并存在水平侧面红石信号。
顶部 115 不消耗，产物格已满时停工。
只查询四个水平方向的 `Level#getSignal`，不使用 `hasNeighborSignal`。
完整撒粉的 115 自身会发出 `15 - omnomnom × 2` 的信号，计入顶面会使机器持续误启动。
状态属性按名称读取，方块本身直接引用暮色森林注册项。

方块实体每 tick 判定 `random.nextInt(4096) < 3`，命中产出一件 115。
期望间隔为 `4096/3` tick，在 20 TPS 下约 68.3 秒，与默认随机刻的补片速度一致。
机器不保存进度，也没有进度条。
产出时播放 `block.wool.place`；工作时每次 `animateTick` 生成一颗 `particle_carminite_ember`。

只有空手点击打开界面。
持有 115 时返回 `SKIP_DEFAULT_BLOCK_INTERACTION`，允许优先放置且不在放置失败后误开界面。
容器只有一个产物槽，任何面都不能输入，漏斗只能从底面抽取，比较器按填充度输出。
菜单使用 `IMenuTypeExtension.create`，打开时通过 `writeClientSideData` 传坐标。

`inventory/E115RegeneratorMenu` 与 `client/E115RegeneratorScreen` 使用 `textures/gui/e_115_regenerator.png`。
画布为 256×256，面板为左上角的 176×166；产物槽 `(80,35)`，背包 `(8,84)`，快捷栏 `(8,142)`。
方块无状态，模型为 `cube_bottom_top`，进入主栏目与 `#minecraft:mineable/pickaxe`。
测试入口为 `gametest/E115RegeneratorTests`。

### 051：拆解台

`block/Experiment051Block` 使用暮色森林拆解台的方块属性，硬度 2.5，进入 `#minecraft:mineable/axe`。
状态 `active` 表示燃料燃烧，激活时使用专用贴图并发出 13 级光照。
每次 `animateTick` 有 10% 概率播放熔炉燃烧音，不生成有朝向的熔炉烟火粒子。

`block/entity/Experiment051BlockEntity` 只拆解、不收经验、不切换配方，消耗燃料并直接产出。
机器刻参照原版熔炉，产物为 3×3 网格；任何一格无法容纳时停工并清零进度。
`crafting/UncraftingResolver` 依次查找反向合成配方、暮色森林拆解配方、熔炼回退，取首个结果。
布局保留拆解台的宽高，再映射至 3×3 网格。
`compat/TwilightUncrafting` 直接引用 `TFRecipes`、`UncraftingRecipe` 与 `TFConfig`。
重新扫描只发生于原料更换、数量增加、数量不足或 `RecipeManager` 因重载更换时。

拆解配方的字段、顺序约束与产物表统一见 [拆解](crafting.md#十-拆解)。
菜单为 `inventory/Experiment051Menu`，屏幕为 `client/Experiment051Screen`。
面板 `textures/gui/experiment_051.png` 为 186×166。
火焰与箭头使用原版 `container/furnace/lit_progress`、`container/furnace/burn_progress`。
底图位置须与菜单的 `FLAME_`、`ARROW_` 常量对齐。
菜单类型使用 `IMenuTypeExtension.create`，通过 `writeClientSideData` 传递方块坐标。
漏斗顶面输入原料、侧面输入燃料、底面抽取产物，界面和自动化共用 `canPlaceItem`。
方块有普通与激活两套 `cube_bottom_top` 模型。
`gametest/Experiment051Tests` 直接调用 `serverTick` 验证机器行为。

### 004：食物加速熔炉

`block/Experiment004Block` 继承 `AbstractFurnaceBlock`，使用 `facing` 与 `lit` 状态。
方块实体独立实现三格容器，因为原版静态 `serverTick` 与私有烧炼时间计算不能直接覆写。
产物带 `DataComponents.FOOD` 时耗时减半，例如 200 tick 变为 100 tick。
四个数据槽的索引与原版熔炉一致。

`inventory/Experiment004Menu` 与 `client/Experiment004Screen` 分别继承抽象熔炉菜单和屏幕。
菜单类型必须独立注册为 `experiment_004`，不能继承写死 `MenuType.FURNACE` 的 `FurnaceMenu`。
客户端使用空容器并靠槽位同步，无须传递坐标。
产物槽按 `recipesUsed` 结算经验，在取出产物或破坏方块时释放。
贴图、火焰、箭头与配方书沿用原版熔炉；不生成烟火粒子，环境燃烧音概率为 10%。
八个方块状态变体对应朝向与亮灭，模型使用 `minecraft:block/orientable`。
正面贴图为 `experiment_004_forward[_active]`，其余五面使用 `experiment_004`。
测试入口为 `gametest/Experiment004Tests`。

## 武器与手持工具

### 032、033：突刺长矛

两件物品共用 `item/ThrustSpearItem`，使用 `UseAnim.BOW` 与 72000 tick 的持续使用时间。
不改变 FOV，因为原版缩放仅识别 `Items.BOW`。
每 10 tick 选择一个目标：位于 `entity_interaction_range` 内、视线两侧各 30 度以内、视线可达。
多个目标中选择与视线垂直距离最小者，造成 1 点 `minecraft:sweet_berry_bush` 伤害并播放对应受伤音。
该类型正常受护甲减伤且不击退，保留原版无敌帧，不另加描述或死亡信息。

物品模型必须显式配置弓式 `display`，不能直接套用 `item/generated` 或 `item/handheld` 的默认变换。
第三人称旋转为 `[-80,260,-40]` 与 `[-80,-280,40]`，位移为 `[-1,-2,2.5]`。
第一人称使用 generated 的变换。

### 027：红石绘点笔

`item/Experiment027Item` 的使用时间为 72000 tick，每 tick 在服务端生成一个粒子。
落点使用 `Player#pick(blockInteractionRange, 1.0F, false)`，按方块轮廓射线检测。
粒子位置沿命中面法线偏移 0.02 格；未命中或超过方块交互距离时不生成。
默认方块交互距离为 4.5 格。

`client/Experiment027Particle` 使用 `minecraft:generic_4`，红色按原版粉尘算法取值。
尺寸为原版红石粒子的 0.5 倍基准，原版基准值为 0.75。
初速和重力均为 0，无物理碰撞，寿命固定 1200 tick，即 60 秒。
独立粒子类型避免原版 `DustParticleBase` 的固定寿命与缩放限制。
测试入口为 `gametest/Experiment027Tests`。

### 060：金探针

`item/Experiment060Item#probe` 从使用者正下方开始向下扫描，先找到石头类，再跳过石头类与沙土类。
第一个不属于两类的方块即为结果，以 actionbar 显示方块本地化名称并消耗 1 点耐久。
未找到石头或扫描至 `getMinBuildHeight()` 仍无结果时，只显示「下方没有东西」，不消耗耐久。
耐久为 256。

石头类为基岩、粉砂岩、`#minecraft:base_stone_overworld` 与 `#minecraft:base_stone_nether`。
基岩与粉砂岩只在代码中判定，不能加入基础石头标签，否则会成为矿脉替换目标。
沙土类为 `#minecraft:dirt` 与 `#minecraft:sand`，包含本模组通过数据包追加的岩石、土与沙。
矿石、砂砾、冰霜、流体与空气均可成为结果。
消息键为 `message.dawnduskterminal.experiment_060.found` 及同名 `_nothing`。
测试入口为 `gametest/Experiment060Tests`。

### 043：手持喷火器

`item/FireJetProcess` 将暮色森林封装喷火器的时序沿使用者视线执行。
单击启动，由 `DdtServerEvents` 的 `ServerTickEvent.Post` 推进，不使用 `startUsingItem`。
枪口在眼睛前方 1 格，每 tick 更新位置与朝向；死亡、下线或换维度时停止。
松手不停止；启动手换为其他物品时，改为从脚下向上喷射并可伤害使用者，换回后恢复视线喷射。
同一施放者不能叠加第二段过程。

预热在第 20、40、60 tick 各生成 8 颗 `LAVA` 并播放 `block.lava.pop`，第 80 tick 进入喷火。
启动及喷火首 tick 播放 `block.comparator.click`，音量 0.3、音高 0.6。
喷火共 61 tick，每 2 tick 生成一颗大烟和五路火焰，每 4 tick 播放 `entity.ghast.shoot`。
中心火焰沿轴初速 0.75，四侧各偏移 1 格并带 0.05 的向心分量。
伤害沿轴长 14 格、半径 2 格，每 5 tick 结算 4 点并点燃 300 tick，不检查遮挡。
正常喷射排除施放者与 `entity/GhastKin` 同族，火焰免疫实体不受伤害。
同族范围见下一节。

耐久 64，每次消耗 1 点；启动冷却为 201 tick，即完整过程 141 tick 加 60 tick。
`experiment_043` 伤害类型进入 `no_knockback`、`is_fire`、`ignites_armor_stands` 与 `bypasses_cooldown`。
独立伤害节拍必须绕过无敌帧，死亡键为 `death.attack.experiment_043`。
`JetFlameParticleOptions` 发送初速，`client/JetFlameParticle` 使用原版五倍尺寸。
粒子寿命为 12 至 44 tick，逐 tick 速度乘 0.96，尺寸最终缩至一半。
测试入口为 `gametest/Experiment043Tests`。

### 066：恶魂陷阱书

`item/GhastTrapProcess` 将恶魂陷阱改为无需充能、随视线移动的走廊场。
场心在眼睛前方 1 格；目标包围盒中心投影视线轴后，轴向须在 0 至 16 格内。
两条横轴距离均不超过 4 格加目标包围盒半径。
每 tick 施加 `(场心 - 目标位置) × 0.1` 的牵引，不设速度上限。

场影响活体与掉落物，后者只受牵引。
排除施放者、观察者与 `entity/GhastKin` 同族。
同族包括本模组两种恶魂与暮色森林的 `carminite_ghastling`，玩家之间不因此免伤。
过程接受 `LivingEntity` 施放者，怪物可直接调用 `start`；同一施放者不能叠加第二个场。

每 tick 尝试 7 点伤害，保留原版无敌帧，120 tick 内形成约 12 次有效伤害。
伤害类型 `experiment_066` 只加入 `no_knockback`，不加入 `bypasses_cooldown`，以免改变节拍。
命中暮色恶魂前，`item/UrGhastPhaseSuppressor` 调用 `setInTantrum(false)` 与 `resetDamageUntilNextPhase()`。
其相位内伤害除以 10 的规则不能靠伤害类型标签消除，必须保留直接类型化调用。

每 tick 发出五颗 `twilightforest:ghast_trap`，令 `vx=cos(counter/10)×2.5`、`vy=20`、`vz=sin(counter/10)×2.5`。
五组偏移为 `(vx,vy,vz)`、`(-vx,vy,-vz)`、`(-vx,vy/2,vz)`。
其余两组为 `(vx/2,vy/4,vz/2)` 与 `(-vx/2,vy/4,-vz/2)`。
将三轴分别映射为右轴、视线轴与上轴，使粒子沿视线收向施放者。
服务端发送粒子时 `count=0`，保留原始偏移速度，不引入随机。
`counter>100` 后每 4 tick 补一颗 `huge_smoke`，偏移为 `(cos×0.05,0.25,sin×0.05)`。

音效为 `TFSounds` 的 `warmup`、`on` 与 `spindown`，依次覆盖 0 至 29、30 至 79、80 tick 以后。
音源为 `BLOCKS`，音量 1、音高 4，随场心移动。
过程由服务端 tick 推进，死亡或换维度时停止。
耐久 64，每次消耗 1 点，冷却 180 tick，即过程 120 tick 加 60 tick。
测试入口为 `gametest/Experiment066Tests`。

### 045：盾

`item/Experiment045Item` 继承 `ShieldItem`，耐久为 336，其他格挡与木板维修规则沿用原版。
物品使用 `minecraft:builtin/entity`，客户端须为它单独注册 `blocking` 谓词及对应模型变换。
`client/Experiment045ShieldRenderer` 经 `IClientItemExtensions` 提供原版 `ShieldModel` 几何。
无图案时使用 `item/experiment_045_nopattern`。
有图案或底色时使用 `item/experiment_045_base`，由 `BannerRenderer#renderPatterns` 叠加旗面。

发光层 `item/experiment_045_glow` 使用 `entityTranslucentEmissive`。
其固定渲染缓冲必须晚于原版相关缓冲登记，保证在本体与旗面之后绘制。
三张贴图均放在 `textures/item/`，`Material` 引用 blocks 图集。
测试入口为 `gametest/Experiment045Tests`。

### 047 至 050：护甲

四件 `item/ExperimentArmorItem` 依次为头盔、胸甲、护腿与靴子。
`ModArmorMaterials#EXPERIMENT_ARMOR` 的护甲值为 2/6/5/2，总计 15。
每件韧性 2，附魔值 9，耐久倍率 15。
砷铅铁锭、绯红钼锭或䤧锭中任意一种即可维修，不要求同时提供。
护甲层贴图为 `textures/entities/experiment_armor.png`。
本套不进入 `#minecraft:trimmable_armor`，`verifyComponentsAfterLoad` 清除外部写入的 `TRIM`。
纹饰 UV 与本套 64×64 几何不兼容，不能只禁止锻造而保留旧组件。

`client/model/ExperimentArmorModel` 将 Bedrock 1.12.0 几何拆为四个槽位的 `LayerDefinition`。
头、躯干与双臂、双腿、双脚分别归入对应槽位，上臂倾角为 22.5°，脚背斜片为 ±90°。
转换时枢轴取 `(px,24-py,pz)`，方块最小角取 `(bx-px,py-by-sy,bz-pz)`。
带旋转的立方体拆为子 `ModelPart`，旋转符号不得取反。
基准位置与玩家模型一致：头身 `(0,0,0)`、臂 `(±5,2,0)`、腿 `(±1.9,12,0)`。
姿势与可见性由 `getGenericArmorModel` 的 `copyModelProperties` 同步。

`mixin/ExperimentArmorGlowMixin` 在每个槽位 `renderArmorPiece` 的 TAIL 重绘同一几何。
发光图为 `textures/entities/experiment_armor_glow.png`，光照为 `FULL_BRIGHT`。
渲染类型必须同本体使用 `armorCutoutNoCull`，保留 `VIEW_OFFSET_Z_LAYERING` 的 0.99975586 偏移。
深度相同且后绘制时 `LEQUAL` 才能显示发光层，不能换用无偏移的实体自发光类型。
透明像素仍按 alpha 小于 0.1 裁剪。
测试入口为 `gametest/Experiment047Tests`。

### 057：刀与飞刃

`item/Experiment057Item` 面板为伤害 8、攻速 1.6，附魔值 10，耐久 64。
每次发射消耗 1 点耐久，冷却 40 tick；不设置工具等级，也不用于挖掘。
属性修饰符使用原版 `BASE_ATTACK_DAMAGE_ID` 与 `BASE_ATTACK_SPEED_ID`，使提示显示面板总值。
加入 `#minecraft:swords` 后可接受近战附魔、耐久与经验修补。

`entity/Experiment057BladeEntity` 继承 `ThrowableProjectile`，关闭重力并每 tick 恢复速度大小，抵消 0.99 阻力。
初始方向位于视线前方 30 度以内，初速为 1.2 至 1.8 格/tick，寿命为 160 tick。
CLOS 制导以发射者眼睛射线为基准，目标点取弹体垂足沿射线向前 6 格处。
每 tick 最多转向 6 度，发射者死亡或离线后沿当前方向直飞。

撞击方块时作法向反射，叠加 12% 随机偏转，沿法线偏移 0.05 格后继续飞行。
服务端生成 32 颗红石粒子，并按该方块 `SoundType` 播放破坏音。
不调用 `super.onHitBlock`，避免每次反射都触发钟、靶子等方块的投掷物交互。

命中活物时现读发射者 `ATTACK_DAMAGE`，经 `CommonHooks#fireCriticalHit` 结算暴击。
基础倍率为 1.5，发射者下落时按原版近战条件再乘 1.5，但不检查致盲与疾跑。
之后按当前主手物品调用 `EnchantmentHelper#modifyDamage` 加入附魔伤害。
命中会播放暴击音与 12 颗 `crit` 粒子，弹体方向反转且寿命减少 80 tick；可命中发射者。
发射者不接收附近音效广播，改收按距离补偿的独立声音包，目标响度为 0.8。
补偿音量为 `距离 / (16 × (1 - 0.8))`，只扩展听距，实际响度仍受引擎上限约束。
每 2 tick 播放挥砍音并生成 `sweep_attack`，寿命耗尽时生成红石粒子后消失。

原件为 `geo/e_57_blade.geo.json`、`animations/e_57_blade.animation.json` 与 `textures/entity/e_57_blade.png`。
几何的 `spin1` 为 12 单位厚板，`spin2` 为 24 单位薄片，仅上下两面有 UV。
片段 `parallel0` 使两根骨骼反向旋转，每圈 0.48 秒，贴图为 64×64。
`client/Experiment057BladeRenderer` 直接实现 `IGeoRenderer`，不能套用要求活体的 `YsmEntityRenderer`。
使用 `FULL_BRIGHT` 与 `entityCutoutNoCull`，保留透明裁剪。
`gametest/Experiment057Tests` 使用 `dawnduskterminal057` 命名空间。

### 血肉之书

`item/ExpowheelFleshbookItem` 发射 `entity/ExpowheelEntity`，图标为 `expowheel_fleshbook.png`。
耐久 64，每发消耗 1 点，冷却 30 tick；无近战属性、不进入剑标签，附魔值为 0。
以下表格只列相对 057 的差异，其余投掷物运动骨架与方块反射约束沿用上一节。

| 项目 | 血肉之书 |
|---|---|
| 寿命 | 120 tick |
| 速度与最大转角 | 1.9 至 2.1 格/tick，每 tick 24 度，转弯半径约 4.5 至 5.0 格 |
| 命中活物 | 固定 10 点投掷伤害，不反转、不扣寿命，不读取攻击力、附魔或暴击 |
| 发射者 | 不受自身弹体伤害，弹体出生于眼睛前方 0.6 格 |
| 方块命中 | 只播放方块破坏音，不生成碰撞粒子 |

每 tick 按以下优先级重新选择目标，只接受 `Enemy`。

1. 发射者视线两侧各 20 度内最近的可见敌怪，不设距离上限。
   128 格方盒只用于访问已加载实体，不能解释为设计距离上限。
2. 弹体周围 24 格与发射者周围 24 格的并集内，按两种距离的较小值选择最近敌怪。
3. 无目标时使用 057 的 CLOS 制导。

前两档分别从发射者眼睛、弹体位置进行 `ClipContext.Block.COLLIDER` 遮挡判定。
目标方向取包围盒中心，仍受最大转角限制。
第一档目标变化时沿发射者至目标连线生成 `ENCHANT`，间隔 0.3 格，数量限制为 12 至 48。
偏移与速度参数必须为 0，避免原版附魔粒子的落点偏移破坏连线。

沿途每 tick 在碰撞箱内部圆盘生成 8 颗 `particle_carminite_ember`。
水平半径为箱宽的一半，竖向覆盖箱高；初速竖向为 0.2 至 0.25，横向抖动为 0.01。
这些粒子由各客户端本地生成。
寿命结束由服务端发送 `ExpowheelBurstPayload`，避免弹体提前离开跟踪范围而丢失效果。
爆裂环为 24 颗 `particle_carminite_ring`，半径 1，速度为弹体速度的 0.3 倍加径向 0.6，摩擦 0.8。
同时以音量 4 播放 `entity.wind_charge.wind_burst`，听距与 64 格粒子范围一致。

发射音为 `expowheel_shoot`。
`client/ExpowheelSoundInstance` 使 `expowheel_ambient` 跟随弹体，不循环、不额外淡出。
素材长 9.98 秒，实例在 120 tick 或弹体移除时停止。
使用 `Attenuation.NONE`，自行按监听者距离在 48 格内线性衰减。

`geo/expowheel.geo.json` 与 `animations/expowheel.animation.json` 保持原件。
`parallel0` 由 Molang 驱动且无固定时长，按循环处理。
贴图复用 `ysm/tower_ghast/textures/ghast.png`，几何贴图尺寸为 256。
`client/ExpowheelRenderer` 使用 `IGeoRenderer`、`FULL_BRIGHT` 与 `entityCutoutNoCull`。
碰撞箱宽 2 格、高 0.5 格，与扁平轮状外观对应。
`gametest/ExpowheelTests` 使用独立命名空间 `dawnduskterminalexpo`，不得与默认批次同进程运行。
不限距离的锥形索敌会受其他场地敌怪影响。

## 持续施放与音效协议

055 与 095 在按住右键期间由服务端 tick 推进。
每 tick 核对施放者存活、维度、使用状态与物品身份，覆盖松手、换物品、物品损坏、死亡和离线。
结束时必须发送停止音效包，不能只依赖 `releaseUsing` 或 `onUseTick`。
043 与 066 同样由服务端 tick 推进，但各自的中断条件以上文规格为准。

持续音效以实体 ID 为键，启动包每 20 tick 重发一次，供新进入范围的客户端恢复播放。
相同实体和相同曲目或档位的重发必须幂等，不能叠加实例。
停止包覆盖 64 格，客户端还须在实体移除或超出距离时自行停止。
一次性 `playSound` 不能替代需要由服务端停止的持续音效协议。

### 055：充能外泄场

`item/Experiment055Item` 使用时间为 72000 tick，过程为 `item/BeetrootProcess`。
前 100 tick 将半径从 0 线性增至 24 格，伤害系数从 0 增至 0.3，此后保持上限。
每 tick 对范围内除自身以外的活体尝试「系数 × (半径 - 距离)」伤害。
满充能时距离 0、4、23、24 格分别为 7.2、6.0、0.3、0 点。
成功伤害目标时生成 12 颗红石粒子。
从满充能起，施放者每 tick 也尝试承受 1 点同类型伤害。
`experiment_055` 只加入 `no_knockback`，不绕过无敌帧。
每 20 tick 消耗 1 点耐久，总耐久 300，持续使用约 5 分钟。
死亡信息为「… 变成了甜菜汤」，同时保留普通键与 `.player` 键。

每 tick 由服务端选择三轴高斯归一化的方向，通过 `Experiment055RayPayload` 发给 32 格内玩家。
长度为当前影响半径的 1.5 倍，满充能时为 36 格。
`client/Experiment055Particles` 按每格 4 颗生成 `ENCHANT`，每条最多 144 颗。
粒子初速为 0，保持原版 30 至 40 tick 的淡出与下沉 1.2 格，不将射线位移写入速度。
服务端只发送方向、长度与实体 ID，不逐颗发送粒子。

每 20 tick 将半径为影响半径 0.7 倍的球形区域改为 `dawnduskterminal:old_growth_birch_forest`。
满充能时群系半径为 16.8 格，按 4×4×4 群系单元中心判断球内外。
只处理已加载的 `ChunkStatus.FULL` 区块，不为此生成区块。
调用 `fillBiomesFromNoise`，标记区块待保存并通过 `resendBiomesForChunks` 同步。

`Experiment055SoundPayload` 有 `CHARGE`、`FULL`、`GEIGER` 与 `STOP` 四档。
`client/Experiment055Sounds` 管理 `Experiment055SoundInstance`，音源为 `PLAYERS`。
充能音只播放一次，素材长度为 98 tick；游戏充能时间仍为 100 tick。
充满音只在当时位置播放一次，不跟随实体。
盖革音每段为 301 tick，在剩余 20 tick 时启动下一实例，重叠渐变，不开启引擎 `looping`。
停止与换档时关闭所有重叠实例。
从声音 tick 回调启动下一段时必须使用 `queueTickingSound`，避免在引擎遍历列表时直接 `play`。
三条音效均设置 `attenuation_distance: 32`，不使用 `stream`。
替换素材时须同步调整按 Ogg granule 测得的时长常量。
测试入口为 `gametest/Experiment055Tests`。

### 095：镇魂曲

`item/Experiment095Item` 使用时间为 72000 tick，过程为 `item/RequiemProcess`。
每 tick 对 32 格内除施放者与观察者外的活体尝试 `0.5 × (32 - 距离)` 伤害。
距离使用 `Entity#distanceTo`，32 格及以外不结算，创造模式与友军由原版 `hurt` 判定。
`experiment_095` 只加入 `no_knockback`，保留无敌帧形成的约 0.5 秒有效伤害间隔。
死亡信息为「… 得到安眠」，保留普通与 `.player` 两个键。
耐久 300，每 20 tick 消耗 1 点；创造模式不扣耐久。

每 10 tick 生成一圈约 24±4 颗音符，水平初速为 3.2 格/tick。
整圈随机旋转，各粒子角度抖动最多为格点间隔的 ±35%，速度抖动 ±8%。
出生半径抖动为 0 至 0.7 格，高度抖动 ±0.4 格，竖直初速小幅随机。
`ModParticles#REQUIEM_NOTE` 使用 `minecraft:note`，颜色由客户端按原版正弦公式随机取值。
摩擦为 0.9，寿命为 40±4 tick，尺寸为两倍并从 0 增至完整大小。
初始上升后每 tick 额外上升 0.006 至 0.016；不能用会将横向速度清零的原版 `NOTE` 替代。

素材在 `assets/dawnduskterminal/sounds/requiem/`，当前六支，长度为 17 至 25 秒。
新增曲目时同步 `.ogg`、`sounds.json` 与 `ModSounds#REQUIEM_TRACKS`，服务端每次随机选择曲目下标。
协议为 `RequiemStartPayload`（实体 ID 与曲目下标）和 `RequiemStopPayload`（实体 ID）。
`client/RequiemSounds` 管理跟随施放者的 `RequiemSoundInstance`，音源为 `RECORDS`，开启 `looping`。
每条曲目均须设置 `stream: true` 与 `attenuation_distance: 32`。
测试入口为 `gametest/Experiment095Tests`，曲目资源检查按注册表逐项核对，不固定支数。

## 方雷与砷铅铁火焰

### 052：方雷

`block/Experiment052Block` 只有 `active` 状态，接受任意邻居红石信号。
首次点燃时切换发光模型，光照为 15，播放 `experiment_052_ignite`，排入 60 tick 引信。
放置到已通电位置也立即点燃；之后断电不能取消。

`world/CarminiteBlast#large` 使用带粒子与音效参数的 `ServerLevel#explode`。
主爆炸威力为 6，音效为 `experiment_052_explode`，两档粒子参数均为 `particle_carminite_blast`。
它通过爆炸包同步，不额外生成原版 `explosion` 或 `explosion_emitter`。

旋臂为逆时针的 48 颗粒子，水平分量为 `(cos θ,-sin θ)`，向外速度由 0.45 增至 1.35，并带竖向抖动。
爆炸粒子与反应场共用八帧贴图，爆炸配置帧间隔为 3 tick，寿命为 20 tick。
补充粒子的球形半径等于威力，数量为「威力 × 18」并限制在 24 至 160，径向初速为 0.05 至 0.20。
逐颗发送时 `count=0`，最后补一颗 `minecraft:flash`。
附近补火数量为威力的 3 倍且至少 3 团，撒布半径为威力，向下查找威力的 1/3 格。
每次还生成 0 至 15 个碎片。

`protectArmedBlocks` 在 `ExplosionEvent.Detonate` 中从破坏列表移除所有同类方块。
其中距爆心最近的最多五个被激活，在随机 1 至 10 tick 后直接爆炸，不再播放点火音或等待完整引信。
其余同类方块保持原状，后续连锁仍可引爆它们。

主爆与碎片小爆都将附近改为 `dawnduskterminal:old_growth_birch_forest`。
使用 `FillBiomeCommand#fill`，半径为威力的 2 倍且至少 4 格，预先加载覆盖区块并同步客户端。
改群系失败时跳过，不向外抛出异常。
方块使用普通与发光两套 `cube_all` 模型。
测试入口为 `gametest/Experiment052Tests`。

### 火焰与余烬

`block/CarminiteFireBlock` 继承 `BaseFireBlock`，`canBurn` 恒为 false，不蔓延、不烧毁方块或消耗可燃物。
接触造成 2 点 `carminite_fire` 伤害，不点燃目标；频率受原版无敌帧限制。
伤害类型只加入 `no_knockback`，不能加入 `is_fire`，以保证火焰免疫生物仍可受伤。
`effects: burning` 仅决定受击音效。

计划刻间隔为 30 至 39 tick。
淋雨时按 `0.2 + age × 0.03` 概率熄灭，每次年龄增加 0 或 1。
未处于火源上、没有可引燃邻居且 `age>3` 时熄灭，支撑消失时立即移除。
`onPlace` 不调用父类，避免附带创建下界传送门。

状态与原版火焰同构，包含五个方向位与 `age`，使用六段 multipart 和十张 `template_fire_*` 衍生模型。
模型为 `minecraft:cutout`，邻居实心面决定贴附方向。
方块无物品与战利品，光照 10，属性为可替换、无碰撞、瞬间破坏、羊毛音效与 `DESTROY`。
每次 `animateTick` 生成三颗 `particle_carminite_ember`，不使用原版环境音与烟。

`client/CarminiteEmberParticle` 使用 `minecraft:generic_0..7`，尺寸为原版红石粒子的 1.5 倍且不随年龄变化。
粒子保持满亮，按年龄播放八帧，负重力上升并施加横向阻尼。
初速自行赋值，不能经过会附加 ±0.4 随机速度的父类构造路径。

### 碎片

`entity/CarminiteShrapnelEntity` 继承 `ThrowableProjectile`，重力为 0.03，每 tick 阻力为 0.99。
`NoopRenderer` 不画本体，客户端每 tick 生成一颗余烬。
命中生物造成随机 1 至 10 点爆炸伤害，每个生物只承受一次，弹体继续飞行。
命中生物或方块都调用 `CarminiteBlast#small`，威力为 2，不破坏方块、不另伤实体或击退。
小爆播放 `entity.generic.explode`，使用同一套粒子、闪光与补火。
命中方块后消失，否则在 100 tick 后消失。

## 立方压制场

### 共用登记与可视化

065 与 069 分别持有 `world/CubeField` 实例，共用算法而不混用场心集合。
每个场是半径 8 格、边长 17 格的立方体，按方块坐标三轴绝对差判断覆盖范围。
按维度保存场心集合，只查询已登记场心，不扫描范围内的 4913 个方块。
没有场的维度只作映射查询。

方块 `onPlace`、`onRemove` 管理即时摆放和移除，方块实体 `onLoad`、`setRemoved` 管理区块生命周期。
摆放当 tick 生效；停服时清理集合。
命中候选中心后先检查 `ChunkSource#hasChunk`，不得为场查询加载区块。
随后核对中心仍为对应方块，移除幽灵条目。

`world/CubeFieldMarker` 在客户端绘制红石粉框线，服务端没有 ticker 或粒子广播。
只有 16 格内存在存活玩家时绘制。
每 tick 在中心生成一颗粉尘，每 10 次绘制三组线段。
每组为中心至角点的斜线和该角的一条棱，斜线起止方向随机。
线段密度为每格 3 颗；边界为 `pos-RADIUS` 至 `pos+RADIUS+1`，即中心外各 8.5 格。

两台机器均无状态、硬度 3.5、石头音效、光照 15，进入 `#minecraft:mineable/pickaxe`。
模型均为单层 `cube_all`，物品指向方块模型，掉落自身。
065 的地图色为 `COLOR_ORANGE`，069 为 `TERRACOTTA_ORANGE`。
场测试必须清理越出模板边界的影响，隔离要求见 [游戏内测试](development.md#游戏内测试)。

### 065：伤害压制场

`world/Experiment065Field` 在服务端 `LivingIncomingDamageEvent` 中检查伤害来源。
优先取 `DamageSource#getDirectEntity()`，为空时才取 `getEntity()`；来源实体位于场内则取消整次伤害。
近战按攻击者、射弹按弹体、实体爆炸按爆炸实体的位置判定，不按受害者或发射时位置判定。
因此从场内飞出的箭在场外可以伤人，从场外飞入的箭在场内不能伤人。
没有来源实体的摔落、火焰、虚空、仙人掌、饥饿等环境伤害不受影响。

取消事件同时阻止伤害、击退、受击音、护盾耐久损失与新增无敌帧。
不能改在减伤之后补回生命值，否则无法恢复这些副作用。
065 仅使用 `experiment_065` 单张贴图，不叠加发光模型。
测试入口为 `gametest/Experiment065Tests`。

### 069：方块更新压制场

`world/Experiment069Field#suppressed` 检查被更新的目标格，而非更新来源。
场内变化仍可向场外邻居发送更新，场外目标照常处理。
三个 mixin 覆盖以下入口。

| Mixin | 拦截入口 |
|---|---|
| `BlockStateUpdateSuppressMixin` | `handleNeighborChanged`、方块 `tick` 与 `randomTick` |
| `LevelShapeUpdateSuppressMixin` | `Level#neighborShapeChanged` |
| `FluidStateUpdateSuppressMixin` | 流体 `tick` 与 `randomTick` |

邻居更新与计划刻在执行时丢弃，形状更新在排队口拦截。
不能直接拦截 `updateShape`，否则结构模板放置与区块升级也会受影响。
方块刻和流体刻是独立队列，不能省略流体入口。
`gametest/Experiment069Tests` 对邻居、形状、计划、随机与流体更新分别设置场外对照。
各测试使用独立 batch，结束后移除机器，不能仅依赖默认模板清理。
