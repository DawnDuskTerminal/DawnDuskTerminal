# 合成与标签

苍穹石必须能够充当石头与圆石使用，这样在维度中取得的石头才可以适用于整套原版石头配方。

配方层面的实现比看上去更为曲折，原因有三个，具体见下文。

## 一 只加标签不会生效

1.21.1 的物品标签中**没有** `minecraft:stone` 与 `minecraft:cobblestone`。

原版配方把「石头」与「圆石」当作物品写死在 `key` 与 `ingredient` 之中，例如：

```json
"key": { "#": { "item": "minecraft:stone" } }
```

在整份原版数据中，只有两条石头材料是通过标签指定的：

- `minecraft:stone_crafting_materials`，用于熔炉与酿造台；
- `minecraft:stone_tool_materials`，用于石制工具五件。

因此，即便向自建的 `minecraft:stone` 标签中加入一个方块，也不会有任何配方识别它。

## 二 本模组的做法

本模组在 `data/minecraft/tags/item/` 下提供四条标签。

| 标签 | 内容 | 谁在用 |
|---|---|---|
| `minecraft:stone` | `minecraft:stone` 与 `canopy_stone` | `data/minecraft/recipe/` 下改写过的原版配方 |
| `minecraft:cobblestone` | `minecraft:cobblestone` 与 `canopy_stone` | 同上 |
| `minecraft:stone_crafting_materials` | 追加 `canopy_stone` | 原版熔炉与酿造台配方 |
| `minecraft:stone_tool_materials` | 追加 `canopy_stone` | 原版石制工具配方 |

`data/minecraft/recipe/` 下的 22 个文件是原版配方的副本，其中只把 `{"item": …}` 替换为 `{"tag": …}`。
由于同名路径会覆盖原版数据包，因此游戏中生效的是改写后的版本。

- 合成台：石头砖、石台阶、石楼梯、石按钮与石压力板，以及活塞所用的红石中继器与比较器等；
- 石切机：石头系的七个 `from_stone` 配方，以及圆石系的三个 `from_cobblestone` 配方；
- 熔炉：圆石烧制石头，石头烧制平滑石头。

产物仍然是原版方块，苍穹石只顶替原料位，不改变产物。

## 三 NeoForge 的数据包优先级高于模组数据包

NeoForge 自带 191 个原版配方覆盖，其中 13 条与本模组需要修改的配方重合：
`andesite`、`diorite`、`dispenser`、`dropper`、`lever`、`observer`、`piston`、
`mossy_cobblestone_from_vine`、`mossy_cobblestone_from_moss_block`、
`coast_/sentry_/vex_armor_trim_smithing_template` 与 `stonecutter`。

在这 13 条配方中，NeoForge 已经把圆石替换为通用标签 `c:cobblestones/normal`。
但它的数据包优先级高于模组数据包，因此即便写出同名文件也不会生效。

于是改为从标签侧入手，在 `data/c/tags/item/` 下补充两条标签：

- 在 `c:cobblestones/normal` 中追加 `canopy_stone`；
- 在 `c:stones` 中追加 `canopy_stone`。

至于 `stonecutter.json` 那一条，NeoForge 仍然写死 `minecraft:stone`，通过标签无法补救。
因此由 `data/dawnduskterminal/recipe/canopy_stone_stonecutter.json` 补一条模组自身的配方。

需要记录以下结论：再次实现「某方块顶替原版材料」时，应先查明 NeoForge 覆盖了哪些原版配方，
对应 `neoforge-<版本>-universal.jar` 中的 `data/minecraft/recipe/`；
若无法覆盖，则改为修改标签，或补充自己的配方。

## 四 测试

`gametest/RecipeTagTests` 包含四条测试，通过 `./gradlew runGameTestServer` 运行：

1. 六条标签都认得苍穹石。
2. 石头系原版配方逐条都能被苍穹石顶替。
3. 圆石系原版配方逐条都能被苍穹石顶替。
4. 存在使用苍穹石制作出石切机的方法。

断言通过 `RecipeManager` 取得的是**最终生效**的那一版配方，而不检查本模组写了哪些文件。
因此，若 NeoForge 以后增加或删除覆盖，或者本模组遗漏某一个改写文件，这些测试都会失败。

## 五 天门松木家族

天门松照苍穹木配齐：原木、去皮原木、木、去皮木、空心原木。
木的六面都用 `block/skygate_pine_log` 张贴图，不单独出一张，与 `minecraft:oak_wood` 的做法相同。

| 文件 | 内容 |
|---|---|
| `recipe/skygate_pine_wood.json` | 4 原木 → 3 木 |
| `recipe/stripped_skygate_pine_wood.json` | 4 去皮原木 → 3 去皮木 |
| `recipe/hollow_skygate_pine_log.json` | 石切机，原木 → 空心原木 |

去皮走 `data/neoforge/data_maps/block/strippables.json`：原木→去皮原木、木→去皮木两条映射都写在那里。

标签分三层，缺一层就会出现「配方不认、斧子不快、僵尸门都拆不动」这类问题。

| 标签 | 内容 |
|---|---|
| `minecraft:logs` 与 `minecraft:logs_that_burn`（方块与物品各一份） | 原木、去皮原木、木、去皮木 |
| `minecraft:stripped_logs`（方块与物品各一份） | 去皮原木、去皮木 |
| `minecraft:mineable/axe` | 上述四块加三块空心原木 |
| `minecraft:climbable` | 攀爬版空心原木；缺了它挂上梯子也爬不动 |
| `dawnduskterminal:skygate_pine_logs`（方块与物品各一份） | 原木家族四块，照暮色森林按树种分标签的习惯 |
| `dawnduskterminal:hollow_logs` | 三块空心原木 |

## 六 空心原木

照暮色森林移植：一块「木头」实际是三个方块，共用一个物品 `hollow_skygate_pine_log`。

| 方块 | 状态 | 说明 |
|---|---|---|
| `hollow_skygate_pine_log_horizontal` | `axis` + `variant`（五种） | 躺着的空心原木，能钻进去 |
| `hollow_skygate_pine_log_vertical` | `waterlogged` | 立着的空心原木，从顶上跳进去 |
| `hollow_skygate_pine_log_climbable` | `facing` + `variant`（三种） | 挂藤或梯子的那面 |

`variant` 的取值：横放那一块是 `empty`、`moss`、`moss_and_grass`、`snow`、`waterlogged` 五种；
攀爬那一块是 `vine`、`ladder`、`ladder_waterlogged` 三种。

- 放置：`item/HollowLogItem` 按点击面分派，点顶面或底面放竖向那块，点侧面放横向那块。
- 挖下来的掉落：`BlockItem#registerBlocks` 把三块都映射回同一件物品，因此不会出现三种掉落物。
- 横向那块往洞里塞苔藓毯、草、雪球分别切到 `moss`、`moss_and_grass`、`snow`，铁锹铲雪、剪刀收苔藓退回 `empty`。
- 竖向那块往洞里塞藤或梯子会整块换成攀爬版，剪掉再换回来。
- 苔藓与积雪直接取暮色森林的贴图：`twilightforest:block/mosspatch`、`moss_overhang`、`snow_overhang`。
  塞苔藓用的是暮色森林的 `moss_patch` 物品，剪刀回收的也是它，因此不需要另画贴图。
- 与暮色森林的差别只剩水的表示：这边直接用 `variant` 或 `waterlogged` 这一档，那边另有一层接口在三种方块之间搬水。
- 攀爬版必须进 `minecraft:climbable` 标签。
  梯子只是模型里的一层薄板，方块本身没有爬行逻辑，标签缺了这一项就只看得到梯子、爬不上去。

`SkygateWoodTests` 断言放置分派与掉落映射，`SkygatePineTests` 断言树上的枝用的是木。

## 七 试验物品的烧炼

烧炼配方位于 `data/dawnduskterminal/recipe/`，命名为 `<试验物品>_smelting.json`。
003、005、009、013 分别烧成铜锭、土、骨粉与砷铅铁锭，其他特殊产物如下。

| 配方 | 输入 | 产物 |
|---|---|---|
| `experiment_060_smelting` | 金探针 | `minecraft:gold_ingot` |
| `experiment_015_smelting` | 试验物品 015 | 通用标签 `c:ingots/silver` 里的第一个物品 |
| `experiment_059_smelting` | 铂催化网格 | 通用标签 `c:ingots/platinum` 里的第一个物品 |

060 是无条件的，另外两条要「这一局里存在那种金属」才成立。

### 产物为什么写标签

原版配方的产物必须是写死的物品 ID，而银与铂不是原版材料：整合包装了什么模组，银锭就是哪一个 ID。
写死某一个模组的 ID，换个整合包就不成立；为每个可能的模组各写一条配方又会撞出重复配方——
同一个输入出现多条匹配，熔炉取哪一条不确定。

所以这两条用本模组自己的配方类型 `dawnduskterminal:tag_smelting`（`crafting/TagSmeltingRecipe`，
序列化器注册在 `registry/ModRecipeSerializers`），配方的 `result_tag` 写通用标签：

```json
{
  "type": "dawnduskterminal:tag_smelting",
  "ingredient": { "item": "dawnduskterminal:experiment_015" },
  "result_tag": "c:ingots/silver",
  "experience": 0.7,
  "cookingtime": 200
}
```

规则只有三条：

- `getType()` 仍然是 `minecraft:smelting`：熔炉与别的模组都按这个类型取配方，取出来当 `SmeltingRecipe` 用，
  所以这个类继承的是 `SmeltingRecipe`，只换掉 `getSerializer()`；
- 标签不存在或者是空的（这一局里没有银、没有铂）时 `matches` 直接回 `false`，对熔炉来说等于这条配方不存在，
  输入物不会被吞掉；
- 标签里有东西时，产物是标签里的第一个物品，`assemble` 与 `getResultItem` 都按它现查。

`gametest/TagSmeltingTests` 钉住三件事：060 一定烧得出金锭；没有银与铂的环境里 015 与 059 匹配不上
（配方本身在数据里，靠标签为空关掉）；把同一条配方的标签换成原版自带的 `c:ingots/gold` 时能烧出金锭，
证明「标签里有东西就烧得出」那一半。

如果某个模组提供了银锭却没有把银锭加进 `c:ingots/silver`，那条配方就烧不出来，需要在那边补标签。

## 八 苍穹船与狄拉克精炼粉

以下配方均位于 `data/dawnduskterminal/recipe/`。

### 苍穹船

| 配方 | 摆法 | 产物 |
|---|---|---|
| `canopy_boat` | 原版船那个 U 形，五块苍穹锭（`"a a"` / `"aaa"`） | `canopy_boat` |

船体与流体承载规则见 [苍穹船](architecture.md#苍穹船)。

### 狄拉克精炼粉

| 配方 | 内容 | 产物 |
|---|---|---|
| `dirac_mix` | 无序：苍穹锭 + 混乱石 + 狄拉克 + 弦 | `dirac_mix` |
| `dirac_mix_smelting` | 烧炼，200 tick、0.7 经验 | `dirac_ingot` |

狄拉克锭只能通过精炼粉链取得，不提供狄拉克直接烧炼或高炉炼锭的配方。

「弦」是本模组那只怪掉的材料（`dawnduskterminal:string`），不是原版的线。

### 玻璃剑

暮色森林的 `twilightforest:glass_sword` 原本只能靠拆解得到，这里补一条有序配方：

```text
        易碎的苍穹  凝固的苍穹
易碎的苍穹  凝固的苍穹
苍穹剑      锻铁锭
```

对应 `glass_sword.json` 的 `" ab"` / `"ab "` / `"cd "`：`a` 是 `fragile_canopy`，
`b` 是 `frozen_canopy`，`c` 是 `canopy_sword`，`d` 是 `twilightforest:wrought_iron_bar`（锻铁锭）。

`gametest/CanopyBoatTests` 通过 `RecipeManager` 取这四条最终生效的配方，逐条核对产物与每一种原料；
产物是暮色森林的物品也照查，因此那边改 ID 时这里会先红。

## 九 血鲁珀特

血鲁珀特是新加的材料（`dawnduskterminal:blood_rupert`），图标由美术提供，模型走 `item/generated`。
它引出两条支线：钼链与炽热之泪。

| 配方 | 摆法 | 产物 |
|---|---|---|
| `experiment_030` | 无序：血鲁珀特 + 粗钼铅矿 + 炼药锅 | 1 试验物品030 |
| `experiment_030_from_experiment_029` | 无序：试验物品029 + 2 粗钼铅矿 + 炼药锅 | 2 试验物品030 |
| `experiment_030_smelting` | 烧炼，200 tick、0.7 经验 | 绯色钼锭 |
| `blood_rupert` | 无序：血鲁珀特 + 石榴石 + 光辉粉 + 3 恶魂之泪 | 4 血鲁珀特 |
| `fiery_tears_from_blood_rupert` | 无序：血鲁珀特 + 玻璃瓶 + 恶魂之泪 + 炽热的方块 | 1 炽热之泪 |
| `fiery_tears_from_experiment_029` | 无序：试验物品029 + 玻璃瓶 + 恶魂之泪 + 炽热的方块 | 2 炽热之泪 |

几处容易看错的地方：

- 「炼药锅」是 `minecraft:cauldron` 这件**物品**，占无序配方的一格，合成时一并消耗；
  不是把原料丢进炼药锅方块里煮
- 「粗钼铅矿」是挖矿掉落的 `rawscarletmolybdenum`，不是钼铅矿方块本身
- 「光辉粉」指本模组的 `illustrious_powder`
- 「炽热之泪」是暮色森林的 `twilightforest:fiery_tears`，配方里写死这个 ID

两条分支都各有一条配方产出同一件物品（试验物品030、炽热之泪），靠原料里粗钼铅矿的个数区分。
因此 `gametest/BloodRupertTests` 除了核对产物与数量，还核对命中的配方 ID：
两条若混淆，拿试验物品029 的那一叠会被当成单颗那条，白吃一份粗钼铅矿。

试验序号与图标登记规则见 [注册与资源约定](experiments.md#注册与资源约定)。

## 十 拆解

051 读取暮色森林的 `twilightforest:uncrafting` 配方，文件位于 `data/dawnduskterminal/recipe/`。
机器的燃料、扫描缓存、界面与漏斗规则见 [051：拆解台](experiments.md#051拆解台)。

| 配方 | 输入 | 一次吃掉 | 产物 |
|---|---|---|---|
| `experiment_017_uncrafting` | 试验物品017 | 1 | 4 钢叶锭 + 黑树树叶 |
| `experiment_018_uncrafting` | 试验物品018 | 1 | 砷铅铁锭 + 黏土块 |
| `experiment_035_uncrafting` | 试验物品035 | 1 | 绯色钼锭 + 黏土块 |

「黏土块」是 `minecraft:clay`，不是黏土球（`clay_ball`）。

后两条使用 `"AB"` 两格排布，产物位于 3×3 网格第一行前两格；017 使用 3×3 斜排。
字段含义如下。

| 字段 | 含义 |
|---|---|
| `input` | 原料物品 |
| `input_count` | 原料数量，默认 1 |
| `key`、`pattern` | 3×3 产物网格，空白格不产出 |
| `cost` | 暮色森林拆解台收取的经验等级；051 不扣经验，可写 0 |

三件物品均不提供合成配方，以免被优先级更高的反向合成配方覆盖。
`gametest/Experiment051Tests` 检查产物、位置与不可合成约束。

## 十一 易矿与转换粉

易矿是本模组新加的矿（`dawnduskterminal:transfore_ore`，见 `worldgen.md` 的「易矿」一节），
挖下来掉易矿本体（`dawnduskterminal:transfore`），再往上是一条烧炼加两条无序：

| 配方 | 摆法 | 产物 |
|---|---|---|
| `transfore_smelting` | 烧炼，200 tick、0.7 经验 | 暮色森林的转换粉（`twilightforest:transformation_powder`） |
| `potent_transformation_powder` | 无序：易矿 + 弦 + 光辉粉 + 稳定药水 | 1 强效转换粉 |
| `reversed_transformation_powder` | 无序：强效转换粉 + 狄拉克 | 1 逆转转换粉 |

几处容易看错的地方：

- 「弦」是本模组那只怪掉的材料（`dawnduskterminal:string`），不是原版的线
- 「稳定药水」是 `dawnduskterminal:stability_potion`，整瓶吃掉，**不退还玻璃瓶**：
  原版药水物品没有 `craftRemainder`，在无序配方里它就是普通原料
- 「狄拉克」是 `dawnduskterminal:dirac` 那件材料，不是狄拉克锭也不是狄拉克矿
- 转换粉写死暮色森林的 ID，因此暮色森林仍是必装依赖

`gametest/TransforeTests` 钉住这条链：矿石真的按战利品表掉易矿（精准采集掉方块本身、空手不掉），
烧炼与两条无序配方都走 `RecipeManager` 核对产物与数量，并按 ID 区分两条配方以防混淆。

## 十二 两瓶转换粉的投掷

强效转换粉与逆转转换粉都是投掷物（`item/TransformationPowderItem`），物理照喷溅药水：
重力 0.05、出手俯仰 -20°、速度 0.5、散布 1.0，可堆叠 16（雪球与鸡蛋那一档）。
投掷物是同一个类 `entity/TransformationPowderEntity`，方向由实体类型分——
`dawnduskterminal:potent_transformation_powder` 与 `dawnduskterminal:reversed_transformation_powder`。

`world/TransformationBlast` 在命中位置处理半径为 5 格的球形区域。

| 项 | 强效转换粉 | 逆转转换粉 |
|---|---|---|
| 命中音效 | `twilightforest:item.twilightforest.transformation_powder.use`（暮色森林转换粉那一声，实际转发 `entity.zombie_villager.cure`） | 同左 |
| 粒子 | `glow` ×180 + `totem_of_undying` ×120 | `witch` ×240 |
| 连线 | 落点 → 每个小喷发点，用 `enchant` | 同一条线，用 `dripping_obsidian_tear` |
| 方块 | 晨昏线那一侧 → 主世界 | 主世界 → 晨昏线那一侧 |
| 易碎的苍穹 | → 水（`minecraft:water`） | → 不稳定液体（`portal_fluid`） |
| 群系 | 晨昏线副本 → 同名主世界格；蓝沙 → 沙漠；暮色森林 → 它占的那一格 | 主世界 → 同名副本；沙漠 → 蓝沙 |
| 活物 | 暮色森林那一侧 → 原版 | 原版 → 暮色森林那一侧 |

易碎的苍穹单列一行，因为它不是调色板里的一格：它是「不稳定液体 + 水」的**流体反应产物**
（见 `world/FluidReactions`），没有「另一侧的对应物」，只能拆回两种原料之一——
强效那瓶拆成水，反转那瓶拆成不稳定液体（`TransformationConversion#fragileCanopyMelt`）。

粒子走原版那条 `sendParticles`，与喷溅药水、图腾爆炸同一套：
位置 = 喷发点 + 高斯 × 散布，速度 = 高斯 × `speed`。
但**真正决定一颗粒子怎么动的是它的 provider**，三种粒子对这份速度的接受程度是三套：

| 粒子 | provider | 它拿这份速度做什么 | 我们的参数 |
|---|---|---|---|
| `totem_of_undying` | `TotemParticle.Provider` | `xd/yd/zd` **原样收下**；摩擦 0.6、重力 1.25（往下拉）、寿命 60~72 tick | 散布 0.25、速度 1.0，**单发落在落点**——它自己会飞出去，不参与铺开 |
| `glow` | `GlowParticle.GlowSquidProvider` | **水平的两个分量被丢掉**，自己用 `0.5 − random` 给 ±0.5；只有竖直分量收下并乘 0.2；摩擦 0.96、无碰撞、寿命 8~40 tick | 散布 0.4、速度 1.0（只影响竖直那一点），拆 8 次小喷发 |
| `witch` | `SpellParticle.Provider` | 与辉光同一套：水平丢、自己 ±0.5，竖直收下乘 0.2；重力参数 −0.1（往上飘）、摩擦 0.96 | 散布 0.3、速度 0.5（竖直 0.1 格每 tick），拆 8 次小喷发 |

铺开的两种（辉光、女巫）拆成 8 次小喷发，取点在**半径的一半**以内（`SUB_BURST_REACH_RATIO = 0.5`）：
它们各自还会漂 1~3 格，取点若摊到整个半径，合起来的观感就比作用球还大一圈。
图腾不拆——它本来就在落点炸开、自己飞出去。

连线从落点拉到每个小喷发点，做法照项目里已有的那条线（`ExpowheelEntity#noteConeTarget`）：
沿线每隔 `0.3` 格取一点、每点一颗、`count = 1`，**三个偏移全给 0**。

- 强效用 `enchant`（`FlyTowardsPositionParticle`）：那三个偏移对它来说不是速度而是**落点偏移**，
  非 0 的粒子会从偏移处往回飘，线就散了；给 0 它才正好落在取样点上，再靠自己寿命里下沉的 1.2 格
  慢慢飘落
- 反转用哭泣的黑曜石的滴落 `dripping_obsidian_tear`：挂在取样点上，然后往下滴

`spread × 3`（高斯尾巴）仍要落在作用半径 5 格以内。
`gametest/TransformationPowderTests#particleBurstsUseTheRightIds` 检查粒子种类、分布范围与连线参数。

命中判定有两条：撞到方块或活物走原版投掷物那条，**碰到液体**由实体自己每 tick 查一次
（液体没有碰撞箱，原版那条看不见它）。所以射进水里会炸在水面那一格，不会一路穿到底。

但**自己那一侧的液体不算落点**：强效那瓶穿过水，反转那瓶穿过不稳定液体——那正是它们各自造出来的东西，
否则往刚造出来的水里再扔一瓶就会炸在半空。判断走 `FluidType`，源与流动两种都算同一种流体。

`world/TransformationConversion` 集中定义转换关系，来源如下。

- 方块照 `biomes.md` 附录那张「原版方块到模组方块」表，再加 `ModChronoOres` 的十二块两档矿石。
  岩浆与热情的暮光（`enthu_fluid`）不参与转换；模组独有的矿（赤钼、狄拉克、carid、易矿）与混乱石、
  炽热的方块没有对应物，转换时原样留着，不会瞎猜一个替身
- 群系照「占位不改注册名」那条约定：副本的 ID 等于它顶替的原版群系，同一 ID 换命名空间即可。
  自建的几个里只有蓝沙有对应：`desert` 的同名副本被改成了自建 ID `blue_desert`，所以沙漠与蓝沙互相换；
  白地、极昼、极夜不参与。暮色森林那一侧照 `biomes.md`「装了暮色森林内容的副本」那张表，反向查
- 活物照暮色森林自己的数据图 `twilightforest:transformation_powder`（`TFDataMaps.TRANSFORMATION_POWDER`）。
  那张表里每一对都是双向的，代码只按「结果落在哪个命名空间」筛方向：
  强效那瓶只认结果落在 `minecraft:` 的，逆转那瓶只认结果落在 `twilightforest:` 的

几处实现上的约定：

- 方块换过去时把两边**同名**的属性照抄一遍（`copySharedProperties`）：霜层的层数、原木的 `axis`、
  草方块的 `snowy` 都因此保住，五层霜层不会压成一层
- 换液体时不立刻反应：整批写入外面套着 `world/FluidReactionGate`，它让
  `FluidInteractionRegistry#canInteract` 在写入期间一律回 `false`（`mixin/FluidReactionSuppressMixin`），
  于是「新流体挨着旧流体」的那一瞬间不算数，球内不会当场变出一层脆弱的苍穹。
  写完只对**球壳**补一次判定——球内那一格与它外侧那一格都交回 `canInteract`，
  范围外的液体照常与边界那一格自发反应，球心那一片则一动不动
- 群系走原版 `/fillbiome` 那条路（`ChunkAccess#fillBiomesFromNoise` 加 `BiomeResolver`），
  每个 4×4×4 生物群系单元问一次，只动已经载入的区块，改完 `resendBiomesForChunks` 同步给客户端
- 活物替换是「在旧实体位置摆一只新的、把位置朝向动量与自定义名带过去、旧的下线」。
  谁会被换完全由数据图决定：表外的实体（玩家、打出去的飞行物、船等等）没有对应物，天然不受影响，
  代码不额外挑类型

`gametest/TransformationPowderTests` 钉住这几条：方块表整张往返（含岩浆不参与、孤儿方块必须查不到）、
同名属性照抄、群系两侧的映射（含沙漠 ↔ 蓝沙）、实体只认一个方向、两瓶各自的粒子种类与数量，
落地那一下真的只换球内的东西（9 格外那块方块不动，球内的暮色大角羊换成原版羊），
以及换液体只在球壳上反应（球内保持一片不稳定液体，边界那一格变成脆弱的苍穹）。

## 十三 稳定药水的炼药

药水没有数据包形式：1.21.1 的炼药配方硬编码在 `PotionBrewing#addVanillaMixes` 里，
模组只能挂 `RegisterBrewingRecipesEvent` 往那份表上补，写法在 `registry/ModPotions#registerBrewingRecipes`。

| 步骤 | 上格原料 | 下面那瓶 | 产物 |
|---|---|---|---|
| 起手 | 繁缕菜 | 粗制的药水 | 稳定药水（本型，3 分钟） |
| 起手（失败分支） | 繁缕菜 | 水瓶子 | 平凡药水 |
| 延长 | 红石 | 稳定药水 | 稳定药水（延长型，8 分钟） |
| 喷溅 | 火药 | 稳定药水 | 喷溅型稳定药水 |
| 滞留 | 龙息 | 喷溅型稳定药水 | 滞留型稳定药水 |

- 繁缕菜（`stellaria_veges`）是仙灵石竹的产物，见 `worldgen.md`。
  它当起手原料用，但**起手那一条不是原版规则**：原版的药水规则只按「物品 → 物品」换内容，
  拿粗制的药水去酿只会得到一只 `minecraft:potion` 里装着本模组的药水，
  换不出模组自己那件 `stability_potion`。
  所以起手走 NeoForge 的 `IBrewingRecipe`（`crafting/ChickweedStabilityBrewing`）：
  它在 `PotionBrewing#mix` 里排在原版规则之前，拿到的是整个物品栈，于是能按药水内容认输入。
- 「水瓶子 → 平凡药水」是单独一条原版规则（`addMix`），
  与原版起手原料的失败分支一致，不经过上面那条自定义配方。
- 两种时长各是一个药水 ID（`stability` 与 `long_stability`），但共用同一个翻译键，
  与原版的 `swiftness` / `long_swiftness` 一致，具体时长由提示框里的效果行显示。
- 三种瓶型是三个物品，都要先用 `addContainer` 登记成容器，
  否则酿造台下面三个槽连放都不让放（判据是 `PotionBrewing#isInput`）。
- 默认药水内容（`ModifyDefaultComponentsEvent` 补的那份）取本型 3 分钟，
  与最基础的那条配方产物一致。
- 两瓶投掷型照原版那两瓶的行为走，各有一处要补：
  发射器在 `ModPotions#registerDispenserBehaviors` 里登记 `ProjectileDispenseBehavior`（否则只会扔出一件物品）；
  滞留型的落地判定由 `mixin/ThrownPotionLingeringMixin` 放宽，
  原版 `ThrownPotion#isLingering` 只认物品本身，不放宽的话模组这瓶只会按喷溅结算。
- `gametest/StabilityPotionTests` 把四条链各酿一遍，
  并钉住三种瓶型的默认内容、时长与颜色，最后一例还反射问了一次滞留型的落地判定。

## 十四 复方侵蚀药水

稳定药水在酿造台上加一罐不稳定液体（`portal_bucket`）酿成「复方侵蚀药水」：
药水放下面、罐放上格，酿完那一格变回苍穹罐。

| 步骤 | 上格原料 | 下面那瓶 | 产物 |
|---|---|---|---|
| 加罐 | 罐装的不稳定液体 | 稳定药水（本型，3 分钟） | 复方侵蚀药水（本型，45 秒） |
| 加罐 | 罐装的不稳定液体 | 稳定药水（延长型，8 分钟） | 复方侵蚀药水（延长型，1 分 30 秒） |
| 延长 | 红石 | 复方侵蚀药水（本型） | 复方侵蚀药水（延长型） |
| 喷溅 | 火药 | 复方侵蚀药水 | 喷溅型复方侵蚀药水 |
| 滞留 | 龙息 | 喷溅型复方侵蚀药水 | 滞留型复方侵蚀药水 |

- 「加罐」那一条不是 `addMix`：原版的药水规则只按瓶里的药水换内容、瓶子本身不变，
  酿出来会是 `stability_potion` 里装着 `compound_erosion`，而不是模组自己那件 `compound_erosion_potion`。
  它走 NeoForge 的 `addRecipe(Ingredient, Ingredient, ItemStack)`，
  输入侧由 `registry/ModPotions#potionBottle` 生成
  （`DataComponentIngredient` 非严格模式，比对物品与 `potion_contents`），本型与延长型各一条。
- 这一条写成 NeoForge 自带的 `BrewingRecipe` 而不是自写 `IBrewingRecipe`，是为了 JEI：
  它的酿造页只模拟 `potionMixes` 与 `containerMixes` 两张表，自写的 `IBrewingRecipe` 不在其中，
  按 R 查不到产物、按 U 查不到原料；它给 NeoForge 自带的 `BrewingRecipe` 单独挂了一条扩展
  （`neoforge/platform/BrewingRecipeCategoryExtension`），只有这一类进得了那一页。
- 已知的差距：JEI 对没登记子类型的物品只算一个 UID，上面两条配方算出来是同一个 UID，
  界面上只显示本型那一条。酿造本身两档都在，`gametest/CompoundErosionTests` 钉着。
- 两档各出各的，与原版的 `long_*` 一样参与后续酿造
  （原版就有 `long_night_vision` + 发酵蛛眼 → `long_invisibility`）：
  本型进本型、延长型进延长型，瓶子都是同一件，只有瓶里的药水换档。
- 空罐不用另写：两桶流体（`portal_bucket`、`enthu_fluid_bucket`）都是 `item/CanocanBucketItem`，
  剩余物是苍穹罐（`canocan`）——原版那套 `craftRemainder` 写死铁桶，这里换成物品栈敏感的钩子。
  NeoForge 补过的 `BrewingStandBlockEntity#doBrew` 会把原料的剩余物写回原料槽，
  所以酿完之后那一格变回一个苍穹罐。这也是酿造台上唯一会退还容器的原料。
- 三种瓶型与稳定那三瓶同构：同样的 `addContainer` 登记、同样的红石/火药/龙息三条规则、
  同样挂进客户端取色与发射器（`ModPotions#registerDispenserBehaviors`）。
- 效果 `compound_erosion`（`world/CompoundErosionEffect`）每 2 秒造成 3 点 `stability` 接触伤害，
  与泡在不稳定液体里那一下是同一个伤害类型，同时也播 `portal_hurt`；
  颜色与 `stability` 一样是 `0x7F159E`，所以两瓶药水看起来完全相同。
- 节拍按剩余时长取 40 的倍数，这是 `PortalSicknessEffect` 用的同一条写法。
  45 秒的 900 tick 不是 40 的倍数，因此第一下落在 880 tick，也就是喝下 1 秒之后。
- `gametest/CompoundErosionTests` 钉住加罐与空罐、四条酿造链、默认内容、时长与颜色，
  以及伤害的节拍、数值与伤害类型。

## 十五 锻铁玻璃

`wrought_iron_glass` 无序配方使用 8 个原版玻璃与 1 个 `twilightforest:wrought_iron_bar`，产出 8 个。
方块使用原版 `TransparentBlock`，硬度为 0.3，带 `noOcclusion`，不窒息、不遮挡视线并允许天光穿透。
加入通用 `#c:glass_blocks` 标签，供其他模组识别。
同类方块之间剔除内部面，外露顶面保留，竖直连接纹理依赖该行为。
`gametest/WroughtIronGlassTests` 检查方块语义、标签、面剔除与最终生效配方。
