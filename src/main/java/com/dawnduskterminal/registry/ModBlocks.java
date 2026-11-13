package com.dawnduskterminal.registry;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.block.AntibuilderType2Block;
import com.dawnduskterminal.block.CampCarminiteFireBlock;
import com.dawnduskterminal.block.CarminiteFireBlock;
import com.dawnduskterminal.block.E115RegeneratorBlock;
import com.dawnduskterminal.block.Experiment004Block;
import com.dawnduskterminal.block.Experiment012Block;
import com.dawnduskterminal.block.Experiment019Block;
import com.dawnduskterminal.block.Experiment051Block;
import com.dawnduskterminal.block.Experiment052Block;
import com.dawnduskterminal.block.Experiment059Block;
import com.dawnduskterminal.block.Experiment065Block;
import com.dawnduskterminal.block.Experiment069Block;
import com.dawnduskterminal.block.Experiment076Block;
import com.dawnduskterminal.block.Experiment114Block;
import com.dawnduskterminal.block.SusCakeBlock;
import com.dawnduskterminal.block.TowerdevBuilderType2Block;
import com.dawnduskterminal.block.TowerdevBuiltType2Block;
import com.dawnduskterminal.portal.PortalBlock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DawnDuskTerminal.MOD_ID);

    public static final DeferredBlock<PortalBlock> PORTAL_FLUID = BLOCKS.registerBlock(
            "portal_fluid",
            props -> new PortalBlock(ModFluids.PORTAL_FLUID.get(), props),
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .replaceable()
                    .noCollission()
                    .randomTicks()
                    .strength(100.0F)
                    .lightLevel(state -> 15)
                    .pushReaction(PushReaction.DESTROY)
                    .noLootTable()
                    .liquid()
                    .sound(SoundType.EMPTY));

    public static final DeferredBlock<Block> CHRONO_CRUST = BLOCKS.registerSimpleBlock(
            "chrono_crust",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(-1.0F, 3600000.0F)
                    .noLootTable()
                    .lightLevel(state -> 3));

    /**
     * 能当合成台用的方块
     *
     * <p>原版 {@code CraftingMenu#stillValid} 把「玩家开着的这台还在不在」硬编码成
     * {@code level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)}，所以光让方块继承
     * {@link CraftingTableBlock} 是不够的：界面会打开，下一秒就被服务端以「方块不是工作台」关掉。
     * 这里用方块标签接管那条判定，{@code mixin/CraftingMenuMixin} 读的就是它。
     * 标签文件在 {@code data/dawnduskterminal/tags/block/crafting_tables.json}，原版工作台也在里面
     */
    public static final TagKey<Block> CRAFTING_TABLES =
            TagKey.create(Registries.BLOCK, DawnDuskTerminal.id("crafting_tables"));

    /**
     * 试验物品 019 摆下来的样子：一丛植物，行为在 {@link Experiment019Block}
     *
     * <p>属性照原版花与草那一套：{@code PLANT} 地图色、{@code instabreak}（空手一下就掉）、{@code GRASS} 音效、
     * {@code noCollission}、随机 XZ 偏移、{@code DESTROY} 推挤反应。掉自身走
     * {@code data/dawnduskterminal/loot_table/blocks/experiment_019.json}
     */
    public static final DeferredBlock<Experiment019Block> EXPERIMENT_019 = BLOCKS.registerBlock(
            "experiment_019",
            Experiment019Block::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.PLANT)
                    .noCollission()
                    .instabreak()
                    .sound(SoundType.GRASS)
                    .offsetType(BlockBehaviour.OffsetType.XZ)
                    .pushReaction(PushReaction.DESTROY));

    /**
     * 试验物品 019 栽进花盆里的样子
     *
     * <p>「能不能塞进花盆」在原版只有一条路：{@code FlowerPotBlock} 构造时把自己连同被栽的方块一起登记进
     * 那张私有静态表 {@code POTTED_BY_CONTENT}，{@code useItemOn} 拿着手上的方块去查表，查不到就当空气。
     * 所以这里注册的是原版的 {@link FlowerPotBlock}，被栽的方块是 {@link #EXPERIMENT_019}
     *
     * <p>方块属性照 {@code Blocks#flowerPot} 那一行：{@code instabreak}（空手一下就掉）、{@code noOcclusion}、
     * {@code DESTROY} 推挤反应。模型 {@code models/block/potted_experiment_019.json} 继承
     * {@code minecraft:block/flower_pot_cross} 并指向那丛植物自己的贴图，
     * 敲下来掉「空花盆 + 那丛植物」，见 {@code data/dawnduskterminal/loot_table/blocks/potted_experiment_019.json}
     *
     * <p>没有物品形态：原版七个 {@code potted_*} 也都没有，取回走 {@code useWithoutItem}
     */
    public static final DeferredBlock<FlowerPotBlock> POTTED_EXPERIMENT_019 = BLOCKS.registerBlock(
            "potted_experiment_019",
            properties -> new FlowerPotBlock(EXPERIMENT_019.get(), properties),
            BlockBehaviour.Properties.of().instabreak().noOcclusion().pushReaction(PushReaction.DESTROY));

    /**
     * 试验物品 001：属性和工作台完全一样（{@link net.minecraft.world.level.block.Blocks#CRAFTING_TABLE}
     * 的构造参数逐字照抄），只是没有配方，只能创造栏或命令拿到
     *
     * <p>掉自身的战利品表不走 {@code ofFullCopy}，走本方块自己的
     * {@code data/dawnduskterminal/loot_table/blocks/experiment_001.json}
     */
    public static final DeferredBlock<CraftingTableBlock> EXPERIMENT_001 = BLOCKS.registerBlock(
            "experiment_001",
            CraftingTableBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(2.5F)
                    .sound(SoundType.WOOD)
                    .ignitedByLava());

    /**
     * 试验物品 012 摆下来的样子：一只杯子，一格最多三只、横着错开摆，行为在 {@link Experiment012Block}
     *
     * <p>属性照「能随手拿起来的小物件」那一档：{@code instabreak}（空手一下就掉）、{@code noOcclusion}
     * （4x6x4 的小模型不该把邻面挡掉）、{@code COLOR_GREEN} 地图色与 {@code STONE} 音效（贴图是海泡菜那种
     * 青绿色，撞起来像陶器）。掉自身走 {@code data/dawnduskterminal/loot_table/blocks/experiment_012.json}，
     * 那一份按 {@code cups} 掉 1~3 个
     */
    public static final DeferredBlock<Experiment012Block> EXPERIMENT_012 = BLOCKS.registerBlock(
            "experiment_012",
            Experiment012Block::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .noOcclusion()
                    .instabreak()
                    .sound(SoundType.STONE));

    /**
     * 试验物品 004：照熔炉工作的那一台
     *
     * <p>属性逐条照抄原版熔炉（{@code Blocks.FURNACE}）的注册参数：{@code mapColor(STONE)}、
     * {@code requiresCorrectToolForDrops()}、3.5 硬度、石头音效，烧着时 13 级光
     * （原版 {@code litBlockEmission(13)} 写死了 {@code BlockStateProperties.LIT}，这里用的正是
     * {@link AbstractFurnaceBlock#LIT}，所以直接写一行 lambda）
     *
     * <p>方块模型、方块实体与界面见 {@link Experiment004Block}、
     * {@code Experiment004BlockEntity}、{@code Experiment004Menu}；掉自身走
     * {@code data/dawnduskterminal/loot_table/blocks/experiment_004.json}
     */
    public static final DeferredBlock<Experiment004Block> EXPERIMENT_004 = BLOCKS.registerBlock(
            "experiment_004",
            Experiment004Block::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F)
                    .sound(SoundType.STONE)
                    .lightLevel(state -> state.getValue(AbstractFurnaceBlock.LIT) ? 13 : 0));

    /**
     * 试验物品 114 摆下来的那一块：整块照抄暮色森林的试验物品 115 方块
     *
     * <p>方块属性逐条照搬 115 的注册参数（{@code noLootTable}、{@code pushReaction(DESTROY)}、
     * {@code strength(0.5)}、{@code WOOL}），只有 {@code randomTicks()} 没跟：115 的随机刻只用于给
     * {@code regenerate} 那版补片，我们那版不补片而是收到红石信号响一声，所以没有随机刻要做的事
     */
    public static final DeferredBlock<Experiment114Block> EXPERIMENT_114 = BLOCKS.registerBlock(
            "experiment_114",
            Experiment114Block::new,
            BlockBehaviour.Properties.of()
                    .noLootTable()
                    .pushReaction(PushReaction.DESTROY)
                    .sound(SoundType.WOOL)
                    .strength(0.5F));

    /**
     * 七种可疑蛋糕（{@code sus_<口味>_cake}）：暮色森林试验物品 115 的衍生物
     *
     * <p>口味表就是注册 ID 中间那一段，方块与物品两边都照它生成。方块属性照搬 115
     * （{@code noLootTable}、{@code pushReaction(DESTROY)}、{@code strength(0.5)}、{@code WOOL}），
     * 外加 {@code randomTicks()}：这一版不需要先撒红石粉，吃到一半靠随机刻自己补片，见 {@link SusCakeBlock}
     */
    public static final List<String> SUS_CAKE_FLAVORS =
            List.of("bloodorange", "cheese", "cherrycheese", "lamington", "pavlova", "pineapple", "redvelvet");

    /**
     * 各口味一片的数值，没列出的走 {@link SusCakeBlock#DEFAULT_SERVING}
     *
     * <p>七种里血橙与红丝绒是 8 点饥饿、8 点饱和度（系数 0.5），其余五种是一片 6 点饥饿、4 点饱和度（系数 1/3）；
     * 血橙那条数值是配方指定的，红丝绒是单独给定的（暂无配方）。必须写在 {@link #SUS_CAKES} 前面：
     * 那张表在静态初始化里就要读这份数值
     */
    private static final Map<String, SusCakeBlock.Serving> SUS_CAKE_SERVINGS = Map.of(
            "bloodorange", SusCakeBlock.EIGHT_SERVING,
            "redvelvet", SusCakeBlock.EIGHT_SERVING);

    /** 六种可疑蛋糕的方块，键是完整注册 ID（{@code sus_<口味>_cake}），顺序同 {@link #SUS_CAKE_FLAVORS} */
    private static final Map<String, DeferredBlock<SusCakeBlock>> SUS_CAKES = registerSusCakes();

    /** 六种可疑蛋糕的方块，按 {@link #SUS_CAKE_FLAVORS} 的顺序 */
    public static Map<String, DeferredBlock<SusCakeBlock>> susCakes() {
        return SUS_CAKES;
    }

    private static Map<String, DeferredBlock<SusCakeBlock>> registerSusCakes() {
        Map<String, DeferredBlock<SusCakeBlock>> map = new LinkedHashMap<>();
        for (String flavor : SUS_CAKE_FLAVORS) {
            String id = "sus_" + flavor + "_cake";
            SusCakeBlock.Serving serving = SUS_CAKE_SERVINGS.getOrDefault(flavor, SusCakeBlock.DEFAULT_SERVING);
            map.put(
                    id,
                    BLOCKS.registerBlock(
                            id,
                            properties -> new SusCakeBlock(properties, serving),
                            BlockBehaviour.Properties.of()
                                    .noLootTable()
                                    .pushReaction(PushReaction.DESTROY)
                                    .randomTicks()
                                    .sound(SoundType.WOOL)
                                    .strength(0.5F)));
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * 试验物品 115 再生机：顶上摆一块撒过红石粉的完整 115、侧面来红石信号时，照着 115 补片的速度产 115 物品
     *
     * <p>方块属性没有源 mod 可以照抄，按「塔木机器」那一档给：3.5 硬度、石头音效、红这一档地图色，
     * 挖它要镐子（进 {@code #minecraft:mineable/pickaxe}）。方块本身没有状态，产出判定与进度都在
     * 方块实体里，见 {@link E115RegeneratorBlock} 与
     * {@code block/entity/E115RegeneratorBlockEntity}
     *
     * <p>掉自身走 {@code data/dawnduskterminal/loot_table/blocks/e_115_regenerator.json}；
     * 格子里攒下的 115 由方块自己的 {@code onRemove} 撒出来
     */
    public static final DeferredBlock<E115RegeneratorBlock> E_115_REGENERATOR = BLOCKS.registerBlock(
            "e_115_regenerator",
            E115RegeneratorBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F)
                    .sound(SoundType.STONE));

    /**
     * 试验物品 051：拆解台那台机器
     *
     * <p>方块属性逐条照版暮色森林的 {@code twilightforest:uncrafting_table} 的注册参数
     * （{@code ignitedByLava}、{@code mapColor(FIRE)}、{@code sound(WOOD)}、{@code strength(2.5)}），
     * 也一起进 {@code #minecraft:mineable/axe}。与拆解台的功能差别见
     * {@link com.dawnduskterminal.block.entity.Experiment051BlockEntity} 的类注释
     *
     * <p>掉自身走 {@code data/dawnduskterminal/loot_table/blocks/experiment_051.json}；
     * 里面 11 格的内容物由方块自己的 {@code onRemove} 撒出来
     */
    public static final DeferredBlock<Experiment051Block> EXPERIMENT_051 = BLOCKS.registerBlock(
            "experiment_051",
            Experiment051Block::new,
            BlockBehaviour.Properties.of()
                    .ignitedByLava()
                    .mapColor(MapColor.FIRE)
                    .sound(SoundType.WOOD)
                    .strength(2.5F)
                    // 烧起来时发 13 级光，数字与原版熔炉的 litBlockEmission(13) 一致；
                    // 原版那个辅助方法写死了 BlockStateProperties.LIT，我们用的是 active，只能自己写一行
                    .lightLevel(state -> state.getValue(Experiment051Block.ACTIVE) ? 13 : 0));

    /**
     * 试验物品 052：照砷铅铁反应堆做的那颗方雷，行为在 {@link Experiment052Block}
     *
     * <p>方块属性的取值没有源 mod 可以照抄（这一件是本模组自己的设计），按「塔木机器」那一档给：
     * 3.5 硬度、石头音效、红这一档地图色，挖它要镐子（进 {@code #minecraft:mineable/pickaxe}）。
     * 光只跟着 {@code active} 走：通着电、还没炸的那 3 秒是 15 级
     *
     * <p>掉自身走 {@code data/dawnduskterminal/loot_table/blocks/experiment_052.json}
     */
    public static final DeferredBlock<Experiment052Block> EXPERIMENT_052 = BLOCKS.registerBlock(
            "experiment_052",
            Experiment052Block::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F)
                    .sound(SoundType.STONE)
                    .lightLevel(state -> state.getValue(Experiment052Block.ACTIVE) ? 15 : 0));

    /**
     * 试验物品 059：铂催化网格，一块能摆在场地里的金属网，行为与格子选取在 {@link Experiment059Block}
     *
     * <p>属性是两边的拼合。渲染那一半照原版玻璃（{@link TransparentBlock} 那三个方法由方块类继承下来）：
     * {@code noOcclusion} 让邻面照画，四条 {@code false} 的判定让这张网不当红石导体、不闷死实体、
     * 不挡视线，也不在它顶上刷怪——原版玻璃那八行里除音效与硬度之外的就是这四条。
     * 挖掘与音效那一半照原版铁栅栏：{@code requiresCorrectToolForDrops}、5.0 硬度、6.0 爆炸抗性、
     * {@code METAL} 音效，进 {@code #minecraft:mineable/pickaxe}，但<b>不</b>进
     * {@code #minecraft:needs_iron_tool}，木镐也挖得动
     *
     * <p>地图色取 {@code METAL}：贴图是那张蓝灰的钢色网，与石类那一档的灰分得开。
     * 挖掉掉自己，走 {@code data/dawnduskterminal/loot_table/blocks/experiment_059.json}
     */
    public static final DeferredBlock<Experiment059Block> EXPERIMENT_059 = BLOCKS.registerBlock(
            "experiment_059",
            Experiment059Block::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .requiresCorrectToolForDrops()
                    .strength(5.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .isValidSpawn((state, level, pos, type) -> false)
                    .isRedstoneConductor((state, level, pos) -> false)
                    .isSuffocating((state, level, pos) -> false)
                    .isViewBlocking((state, level, pos) -> false));

    /**
     * 试验物品 065：反建器那一档里更高级的一台，压出一张 17×17×17 的「打不出伤害」场
     *
     * <p>方块属性照 052 那一档的塔木机器给：3.5 硬度、石头音效、红这一档地图色，挖它要镐子
     * （进 {@code #minecraft:mineable/pickaxe}）。方块自己发 15 级光，贴图只有一张、直接读满亮；
     * 场有多大由 {@code world/CubeFieldMarker} 那圈立方框粒子画出来（客户端画，服务端不挂 ticker）
     *
     * <p>场没有状态，方块实体只用来登记场心，见 {@link Experiment065Block} 与
     * {@code world/Experiment065Field}；掉自身走 {@code data/dawnduskterminal/loot_table/blocks/experiment_065.json}
     */
    public static final DeferredBlock<Experiment065Block> EXPERIMENT_065 = BLOCKS.registerBlock(
            "experiment_065",
            Experiment065Block::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F)
                    .sound(SoundType.STONE)
                    .lightLevel(state -> 15));

    /**
     * 试验物品 069：反建器那一档里更高级的第二台，压出一张 17×17×17 的「更新不处理」场
     *
     * <p>方块属性与 065 同档（3.5 硬度、石头音效、红这一档地图色、要镐子、常亮 15 级光），
     * 地图色用 {@code TERRACOTTA_ORANGE}：比 065 那个 {@code COLOR_ORANGE} 偏棕一档，与 069 那张更暗的贴图
     * 对得上，两台摆在一起在地图上分得清
     *
     * <p>场不扫方块，服务端也不挂 ticker，方块实体只用来登记场心，见 {@link Experiment069Block} 与
     * {@code world/Experiment069Field}；场有多大由 {@code world/CubeFieldMarker} 那圈立方框粒子画出来
     * （客户端画）。贴图只有一张，方块自己发 15 级光
     *
     * <p>掉自身走 {@code data/dawnduskterminal/loot_table/blocks/experiment_069.json}
     */
    public static final DeferredBlock<Experiment069Block> EXPERIMENT_069 = BLOCKS.registerBlock(
            "experiment_069",
            Experiment069Block::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.TERRACOTTA_ORANGE)
                    .requiresCorrectToolForDrops()
                    .strength(3.5F)
                    .sound(SoundType.STONE)
                    .lightLevel(state -> 15));

    /**
     * 试验物品 076：那个每个面每一刻都在换贴图的异常方块，外观整个在客户端
     * {@code client/Experiment076Renderer}，方块本身只挂一个空方块实体当落点
     *
     * <p>它是<b>光源方块</b>：15 级光，外壳在渲染器那一侧也一律按满亮画（{@link
     * com.dawnduskterminal.client.Experiment076Cube} 里连光图都不吃），所以不管摆在洞里还是夜里，
     * 六个面都是亮的。这一条是它「发光」的全部来源，别把 {@code lightLevel} 删了
     *
     * <p>剩下的属性：黑这一档地图色、镐子能挖但 5 点硬度、爆炸抗性 1200（与黑曜石同档）、石头音效。
     * 挖掉掉自己，走 {@code data/dawnduskterminal/loot_table/blocks/experiment_076.json}
     */
    public static final DeferredBlock<Experiment076Block> EXPERIMENT_076 = BLOCKS.registerBlock(
            "experiment_076",
            Experiment076Block::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .requiresCorrectToolForDrops()
                    .strength(5.0F, 1200.0F)
                    .sound(SoundType.STONE)
                    .lightLevel(state -> 15));

    /**
     * 砷铅铁火焰：试验物品 052 炸出来、碎片落地补上的那种火，行为在 {@link CarminiteFireBlock}
     * <p>属性照原版火焰那一套（{@code replaceable}、{@code noCollission}、{@code instabreak}、
     * {@code WOOL} 音效、{@code DESTROY} 推挤反应），只有两条不同：光是 10 级不是 15 级，
     * 以及没有战利品表（敲掉什么都不掉，也没有对应的物品）
     */
    public static final DeferredBlock<CarminiteFireBlock> CARMINITE_FIRE = BLOCKS.registerBlock(
            "carminite_fire",
            CarminiteFireBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.FIRE)
                    .replaceable()
                    .noCollission()
                    .instabreak()
                    .lightLevel(state -> 10)
                    .sound(SoundType.WOOL)
                    .pushReaction(PushReaction.DESTROY)
                    .noLootTable());

    /**
     * 红篝火：一团砷铅铁火焰压在一堆红木头上，行为在 {@link CampCarminiteFireBlock}
     *
     * <p>属性照原版篝火：两份木头（{@code strength 2.0}）、木头音效、{@code noOcclusion}、{@code ignitedByLava}，
     * 点着时 15 级光。只加了一条改动：地图色取绯红木那档暗红，与它那堆红木头的贴图对得上
     *
     * <p>挖掉掉自己，走 {@code data/dawnduskterminal/loot_table/blocks/campcarminitefire.json}；
     * 点燃与熄灭靠 {@code #minecraft:campfires} 标签与 {@code SHOVEL_DOUSE}，见那两条标签与方块类
     */
    public static final DeferredBlock<CampCarminiteFireBlock> CAMP_CARMINITE_FIRE = BLOCKS.registerBlock(
            "campcarminitefire",
            CampCarminiteFireBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.CRIMSON_STEM)
                    .instrument(NoteBlockInstrument.BASS)
                    .strength(2.0F)
                    .sound(SoundType.WOOD)
                    .lightLevel(state -> state.getValue(CampCarminiteFireBlock.LIT) ? 15 : 0)
                    .noOcclusion()
                    .ignitedByLava());

    /**
     * 反建器二型：照暮色森林的 {@code twilightforest:antibuilder} 做的那台反建器的第二型，
     * 行为与那四条差别见 {@link AntibuilderType2Block}
     *
     * <p>方块属性照抄暮色森林那一台的注册参数：{@code noLootTable}（挖掉什么都不掉）、
     * {@code pushReaction(BLOCK)}、{@code mapColor(SAND)}、{@code requiresCorrectToolForDrops}、
     * {@code WOOD} 音效、10 点硬度 6 点抗性、开着时 10 级光（收到红石信号关机就熄灯）。
     * 进 {@code #minecraft:mineable/pickaxe}。
     * 那两条状态是这一台自己的东西：{@code RANGE}（场的半边长，1~8，右键可调）与 {@code POWERED}（红石关机）
     *
     * <p>贴图两层：{@code antibuilder_type2} 与 {@code antibuilder_type2_glow}，后者在模型里按满亮画；
     * 关机时换成不带发光层的 {@code block/antibuilder_type2_off}，看起来才是真的关了。
     * 它没有战利品表，与暮色森林那一台一样只能从创造栏或命令取得
     */
    public static final DeferredBlock<AntibuilderType2Block> ANTIBUILDER_TYPE2 = BLOCKS.registerBlock(
            "antibuilder_type2",
            AntibuilderType2Block::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.SAND)
                    .noLootTable()
                    .pushReaction(PushReaction.BLOCK)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.WOOD)
                    .strength(10.0F, 6.0F)
                    .lightLevel(state -> state.getValue(AntibuilderType2Block.POWERED) ? 0 : 10));

    /**
     * 反建方块二型：反建器二型的场里，被破坏掉的那一格换成它
     *
     * <p>方块属性照抄暮色森林的 {@code twilightforest:antibuilt_block}：{@code noLootTable}（挖掉什么都不掉）、
     * {@code noOcclusion}（贴图带透明，邻面要照画）、{@code pushReaction(BLOCK)}、{@code WOOD} 音效、
     * 0.3 点硬度 2000 点抗性。与那一块不同的一点：它有物品形态，主栏目里能直接拿到，
     * 挖掉仍然什么都不掉
     *
     * <p>贴图两层：{@code antibuildblock_type2} 与 {@code antibuildblock_type2_glow}，
     * 后者在模型里按 10 级光画，与暮色森林那一块的发光层同档
     */
    public static final DeferredBlock<Block> ANTIBUILD_BLOCK_TYPE2 = BLOCKS.registerSimpleBlock(
            "antibuildblock_type2",
            BlockBehaviour.Properties.of()
                    .noLootTable()
                    .noOcclusion()
                    .pushReaction(PushReaction.BLOCK)
                    .sound(SoundType.WOOD)
                    .strength(0.3F, 2000.0F));

    /**
     * 砷铅铁建造器二型：照暮色森林的 {@code twilightforest:carminite_builder} 做的那台的第二型，
     * 与那三条差别见 {@link TowerdevBuilderType2Block}
     *
     * <p>方块属性照抄暮色森林那一台的注册参数：{@code pushReaction(BLOCK)}、{@code mapColor(SAND)}、
     * {@code requiresCorrectToolForDrops}、{@code WOOD} 音效、10 点硬度 6 点抗性、通着电时 4 级光。
     * 进 {@code #minecraft:mineable/pickaxe}。那一台有战利品表（挖掉掉自己），这一台照办
     *
     * <p>贴图两层：{@code towerdev_builder_type2_on} 与 {@code towerdev_builder_type2_on_glow}，
     * 后者在模型里按满亮画；关机时换成一整套 {@code _off} 的那两张
     */
    public static final DeferredBlock<TowerdevBuilderType2Block> TOWERDEV_BUILDER_TYPE2 = BLOCKS.registerBlock(
            "towerdev_builder_type2",
            TowerdevBuilderType2Block::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.SAND)
                    .pushReaction(PushReaction.BLOCK)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.WOOD)
                    .strength(10.0F, 6.0F)
                    .lightLevel(state -> state.getValue(TowerdevBuilderType2Block.ACTIVE) ? 4 : 0));

    /**
     * 砷铅铁构建方块二型：建造器二型建出来的那一格，与暮色森林那一块的差别见 {@link TowerdevBuiltType2Block}
     *
     * <p>与暮色森林的 {@code built_block} 相比，这一块去掉了 {@code noLootTable}（挖掉掉自己），
     * 硬度也换成本模组反建方块二型那一档 0.3（那一块是 50）：建造器停不下来，方格会一片一片铺开，
     * 50 点硬度意味着拆一格要九秒多。抗性仍是 2000，爆炸炸不掉。
     * 其余照抄：{@code noOcclusion}（贴图带透明）、{@code pushReaction(BLOCK)}、{@code WOOD} 音效
     *
     * <p>贴图两档各一张 8x8 连接图集：{@code towerdev_built_type2_off_connected} 与
     * {@code towerdev_built_type2_on_connected}，邻格判定与 UV 重映射在 {@code MountedTowerWoodModel}；
     * 两档的 16x16 单图是那一张图集里「四面都不接」的那一格，供粒子与物品图标用
     */
    public static final DeferredBlock<TowerdevBuiltType2Block> TOWERDEV_BUILT_TYPE2 = BLOCKS.registerBlock(
            "towerdev_built_type2",
            TowerdevBuiltType2Block::new,
            BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .pushReaction(PushReaction.BLOCK)
                    .sound(SoundType.WOOD)
                    .strength(0.3F, 2000.0F));

    /**
     * 高塔恶魂皮摆下来的那一块：六种朝向随机
     *
     * <p>方块属性照原版羊毛：0.8 点硬度（连带 0.8 抗性）、{@code WOOL} 音效、不要求工具、挖掉掉自身。
     * 地图色取皮革那档粉
     *
     * <p>朝向不存进方块状态：blockstate 里那一串带 {@code x}/{@code y} 旋转的变体是**按位置随机挑**的，
     * 客户端拿方块位置算，服务端根本看不见，所以拆了重放、存档重进都不会跳。
     * 做法与 {@code rosy_dirt} 那几块同一条路，见 {@code gametest/BlockStyleTests}
     *
     * <p>物品图标仍是它自己那张 16x16：{@code models/item/carminite_ghast_leather.json} 还是
     * {@code item/generated}，拿在手上是皮革，摆下去才是方块
     */
    public static final DeferredBlock<Block> CARMINITE_GHAST_LEATHER = BLOCKS.registerSimpleBlock(
            "carminite_ghast_leather",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PINK)
                    .strength(0.8F)
                    .sound(SoundType.WOOL));

    /**
     * 活性恶魂皮摆下来的那一块：整块一条 47 格连接纹理
     *
     * <p>方块属性同上，照原版羊毛。贴图两张：16x16 的 {@code block/carminite_leather} 给粒子与物品图标，
     * 128x128 的 {@code block/carminite_leather_connected} 是 8x8 图集，方块模型贴的是它，
     * UV 由 {@code MountedTowerWoodModel} 按邻居逐面重映射（与 {@code mounted_towerwood} 同一套）
     */
    public static final DeferredBlock<Block> CARMINITED_LEATHER = BLOCKS.registerSimpleBlock(
            "carminited_leather",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(0.8F)
                    .sound(SoundType.WOOL));

    private ModBlocks() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
    }
}
