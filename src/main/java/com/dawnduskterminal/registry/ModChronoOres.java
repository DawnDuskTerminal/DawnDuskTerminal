package com.dawnduskterminal.registry;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.world.DiracOreBlock;
import java.util.List;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 晨昏线自己的矿石
 *
 * <p>前半是三块自有矿：赤钼矿、carid 矿与狄拉克矿
 *
 * <p>后半是主世界六种矿石（铜、铁、金、红石、青金石、钻石）的调色板副本。维度里的主世界矿石
 * 一律不再铺原版方块，而是铺这里的两块：上层用 {@code canopy_stone_*}，硬度、爆炸抗性与音效照抄
 * 苍穹石；深层用 {@code charnockite_*}，照抄紫苏岩——紫苏岩那一档用的是深板岩音效
 * （{@link SoundType#DEEPSLATE}），矿石跟着宿主石走。两档的分界就是
 * {@code stone_ore_replaceables} 与 {@code deepslate_ore_replaceables} 两张标签
 *
 * <p>这十二块与源矿石的行为一致：红石矿是 {@link RedStoneOreBlock}（会发光、踩上去掉粒子），
 * 青金石与钻石矿是带经验的 {@link DropExperienceBlock}，其余三块经验为 0。掉落物写在
 * {@code loot_table/blocks/} 下，与其原版矿石一致
 *
 * <p>它们不进创造模式物品栏：只作为世界生成的产物存在，想要带在身上只能精准采集。
 * 创造栏的条目是 {@link ModCreativeTabs} 里逐个点名的，这里不加就自然没有
 */
public final class ModChronoOres {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DawnDuskTerminal.MOD_ID);

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DawnDuskTerminal.MOD_ID);

    public static final DeferredBlock<Block> DEEP_SCARLET = BLOCKS.registerSimpleBlock(
            "charnockite_scarlet_molybdenum_ore",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .requiresCorrectToolForDrops()
                    .strength(5F, 9.0F)
                    .sound(SoundType.DEEPSLATE));

    public static final DeferredBlock<Block> CARID_ORE = BLOCKS.registerSimpleBlock(
            "carid_ore",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .requiresCorrectToolForDrops()
                    .strength(3.0F, 9.0F)
                    .sound(SoundType.STONE));

    // 狄拉克矿只生成在深层石头里，贴图底色也是紫苏岩那种暗色，音效跟着紫苏岩走深板岩那一套
    public static final DeferredBlock<DiracOreBlock> DIRAC_ORE = BLOCKS.registerBlock(
            "dirac_ore",
            DiracOreBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_PURPLE)
                    .requiresCorrectToolForDrops()
                    .strength(6.0F, 9.0F)
                    .sound(SoundType.DEEPSLATE));

    public static final DeferredItem<BlockItem> DEEP_SCARLET_ITEM = ITEMS.registerSimpleBlockItem(DEEP_SCARLET);
    public static final DeferredItem<BlockItem> CARID_ORE_ITEM = ITEMS.registerSimpleBlockItem(CARID_ORE);
    public static final DeferredItem<BlockItem> DIRAC_ORE_ITEM = ITEMS.registerSimpleBlockItem(DIRAC_ORE);

    /**
     * 易矿：挖下来掉 {@link ModItems#TRANSFORE}，烧成暮色森林的转换粉（{@code twilightforest:transformation_powder}）
     *
     * <p>只长在上层那一档宿主石里（{@code dawnduskterminal:stone_ore_replaceables}），
     * 高度带 y10~280、数量少，见 {@code data/dawnduskterminal/worldgen/placed_feature/ore_transfore.json}。
     * 属性照苍穹石这一档的矿石抄：硬度 2.5、爆炸抗性 9、普通石头音效、需要正确工具（铁镐）
     *
     * <p>它不是 {@link DropExperienceBlock}：掉落物是材料而不是方块本身，与原版粗矿那一类一样不给经验，
     * 战利品表在 {@code loot_table/blocks/transfore_ore.json}
     */
    public static final DeferredBlock<Block> TRANSFORE_ORE = BLOCKS.registerSimpleBlock(
            "transfore_ore",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .requiresCorrectToolForDrops()
                    // 爆炸抗性 9 就是下面 STONE_BLAST_RESISTANCE 那个值：常量的声明在这个字段之后，
                    // 这里写自己的字面量，读的人不必去对声明顺序
                    .strength(2.5F, 9.0F)
                    .sound(SoundType.STONE));

    public static final DeferredItem<BlockItem> TRANSFORE_ORE_ITEM = ITEMS.registerSimpleBlockItem(TRANSFORE_ORE);

    /**
     * 一档宿主石：地图色、硬度与音效
     *
     * <p>矿石的属性按宿主石取，挖起来的手感与它埋着的那块石头一致
     */
    private record HostTier(MapColor color, float hardness, SoundType sound) {}

    /** 苍穹石那一档：普通石头音效 */
    private static final HostTier CANOPY_STONE_TIER = new HostTier(MapColor.STONE, 2.5F, SoundType.STONE);

    /** 紫苏岩那一档：深板岩音效，与 {@code Blocks.DEEPSLATE} 对齐 */
    private static final HostTier CHARNOCKITE_TIER = new HostTier(MapColor.DEEPSLATE, 5.0F, SoundType.DEEPSLATE);

    /** 两种宿主石的爆炸抗性都是 9，矿石照抄 */
    private static final float STONE_BLAST_RESISTANCE = 9.0F;

    /** 红石矿点亮时的光照，与 {@code Blocks.REDSTONE_ORE} 取同一个值 */
    private static final int REDSTONE_ORE_LIGHT = 9;

    public static final DeferredBlock<DropExperienceBlock> CANOPY_STONE_COPPER_ORE =
            dropOre("canopy_stone_copper_ore", ConstantInt.of(0), CANOPY_STONE_TIER);

    public static final DeferredBlock<DropExperienceBlock> CANOPY_STONE_IRON_ORE =
            dropOre("canopy_stone_iron_ore", ConstantInt.of(0), CANOPY_STONE_TIER);

    public static final DeferredBlock<DropExperienceBlock> CANOPY_STONE_GOLD_ORE =
            dropOre("canopy_stone_gold_ore", ConstantInt.of(0), CANOPY_STONE_TIER);

    public static final DeferredBlock<RedStoneOreBlock> CANOPY_STONE_REDSTONE_ORE =
            redstoneOre("canopy_stone_redstone_ore", CANOPY_STONE_TIER);

    public static final DeferredBlock<DropExperienceBlock> CANOPY_STONE_LAPIS_ORE =
            dropOre("canopy_stone_lapis_ore", UniformInt.of(2, 5), CANOPY_STONE_TIER);

    public static final DeferredBlock<DropExperienceBlock> CANOPY_STONE_DIAMOND_ORE =
            dropOre("canopy_stone_diamond_ore", UniformInt.of(3, 7), CANOPY_STONE_TIER);

    public static final DeferredBlock<DropExperienceBlock> CHARNOCKITE_COPPER_ORE =
            dropOre("charnockite_copper_ore", ConstantInt.of(0), CHARNOCKITE_TIER);

    public static final DeferredBlock<DropExperienceBlock> CHARNOCKITE_IRON_ORE =
            dropOre("charnockite_iron_ore", ConstantInt.of(0), CHARNOCKITE_TIER);

    public static final DeferredBlock<DropExperienceBlock> CHARNOCKITE_GOLD_ORE =
            dropOre("charnockite_gold_ore", ConstantInt.of(0), CHARNOCKITE_TIER);

    public static final DeferredBlock<RedStoneOreBlock> CHARNOCKITE_REDSTONE_ORE =
            redstoneOre("charnockite_redstone_ore", CHARNOCKITE_TIER);

    public static final DeferredBlock<DropExperienceBlock> CHARNOCKITE_LAPIS_ORE =
            dropOre("charnockite_lapis_ore", UniformInt.of(2, 5), CHARNOCKITE_TIER);

    public static final DeferredBlock<DropExperienceBlock> CHARNOCKITE_DIAMOND_ORE =
            dropOre("charnockite_diamond_ore", UniformInt.of(3, 7), CHARNOCKITE_TIER);

    public static final DeferredItem<BlockItem> CANOPY_STONE_COPPER_ORE_ITEM =
            ITEMS.registerSimpleBlockItem(CANOPY_STONE_COPPER_ORE);
    public static final DeferredItem<BlockItem> CANOPY_STONE_IRON_ORE_ITEM =
            ITEMS.registerSimpleBlockItem(CANOPY_STONE_IRON_ORE);
    public static final DeferredItem<BlockItem> CANOPY_STONE_GOLD_ORE_ITEM =
            ITEMS.registerSimpleBlockItem(CANOPY_STONE_GOLD_ORE);
    public static final DeferredItem<BlockItem> CANOPY_STONE_REDSTONE_ORE_ITEM =
            ITEMS.registerSimpleBlockItem(CANOPY_STONE_REDSTONE_ORE);
    public static final DeferredItem<BlockItem> CANOPY_STONE_LAPIS_ORE_ITEM =
            ITEMS.registerSimpleBlockItem(CANOPY_STONE_LAPIS_ORE);
    public static final DeferredItem<BlockItem> CANOPY_STONE_DIAMOND_ORE_ITEM =
            ITEMS.registerSimpleBlockItem(CANOPY_STONE_DIAMOND_ORE);
    public static final DeferredItem<BlockItem> CHARNOCKITE_COPPER_ORE_ITEM =
            ITEMS.registerSimpleBlockItem(CHARNOCKITE_COPPER_ORE);
    public static final DeferredItem<BlockItem> CHARNOCKITE_IRON_ORE_ITEM =
            ITEMS.registerSimpleBlockItem(CHARNOCKITE_IRON_ORE);
    public static final DeferredItem<BlockItem> CHARNOCKITE_GOLD_ORE_ITEM =
            ITEMS.registerSimpleBlockItem(CHARNOCKITE_GOLD_ORE);
    public static final DeferredItem<BlockItem> CHARNOCKITE_REDSTONE_ORE_ITEM =
            ITEMS.registerSimpleBlockItem(CHARNOCKITE_REDSTONE_ORE);
    public static final DeferredItem<BlockItem> CHARNOCKITE_LAPIS_ORE_ITEM =
            ITEMS.registerSimpleBlockItem(CHARNOCKITE_LAPIS_ORE);
    public static final DeferredItem<BlockItem> CHARNOCKITE_DIAMOND_ORE_ITEM =
            ITEMS.registerSimpleBlockItem(CHARNOCKITE_DIAMOND_ORE);

    private ModChronoOres() {}

    /**
     * 十二块主世界矿石的副本，顺序是「苍穹石一档六块，再紫苏岩一档六块」
     *
     * <p>游戏测试与文档都按这张表走，新增矿石时只改这里与上面的注册
     */
    public static List<DeferredBlock<? extends Block>> overworldOres() {
        return List.of(
                CANOPY_STONE_COPPER_ORE,
                CANOPY_STONE_IRON_ORE,
                CANOPY_STONE_GOLD_ORE,
                CANOPY_STONE_REDSTONE_ORE,
                CANOPY_STONE_LAPIS_ORE,
                CANOPY_STONE_DIAMOND_ORE,
                CHARNOCKITE_COPPER_ORE,
                CHARNOCKITE_IRON_ORE,
                CHARNOCKITE_GOLD_ORE,
                CHARNOCKITE_REDSTONE_ORE,
                CHARNOCKITE_LAPIS_ORE,
                CHARNOCKITE_DIAMOND_ORE);
    }

    /** {@link #overworldOres()} 对应的十二个方块物品，顺序相同 */
    public static List<DeferredItem<BlockItem>> overworldOreItems() {
        return List.of(
                CANOPY_STONE_COPPER_ORE_ITEM,
                CANOPY_STONE_IRON_ORE_ITEM,
                CANOPY_STONE_GOLD_ORE_ITEM,
                CANOPY_STONE_REDSTONE_ORE_ITEM,
                CANOPY_STONE_LAPIS_ORE_ITEM,
                CANOPY_STONE_DIAMOND_ORE_ITEM,
                CHARNOCKITE_COPPER_ORE_ITEM,
                CHARNOCKITE_IRON_ORE_ITEM,
                CHARNOCKITE_GOLD_ORE_ITEM,
                CHARNOCKITE_REDSTONE_ORE_ITEM,
                CHARNOCKITE_LAPIS_ORE_ITEM,
                CHARNOCKITE_DIAMOND_ORE_ITEM);
    }

    // 矿石的公共属性照原版矿石抄：玄武岩音色、需要正确工具。音效与地图色按宿主石取
    private static BlockBehaviour.Properties ore(HostTier tier) {
        return BlockBehaviour.Properties.of()
                .mapColor(tier.color())
                .instrument(NoteBlockInstrument.BASEDRUM)
                .requiresCorrectToolForDrops()
                .strength(tier.hardness(), STONE_BLAST_RESISTANCE)
                .sound(tier.sound());
    }

    private static DeferredBlock<DropExperienceBlock> dropOre(String name, IntProvider experience, HostTier tier) {
        return BLOCKS.registerBlock(name, properties -> new DropExperienceBlock(experience, properties), ore(tier));
    }

    // 原版红石矿的 lit 状态与光照都在 Blocks.REDSTONE_ORE 的属性里，方块类自己不设：
    // RedStoneOreBlock 只负责点亮的逻辑，亮度与 randomTicks 得这里补
    private static DeferredBlock<RedStoneOreBlock> redstoneOre(String name, HostTier tier) {
        return BLOCKS.registerBlock(
                name,
                RedStoneOreBlock::new,
                ore(tier)
                        .randomTicks()
                        .lightLevel(state -> state.getValue(RedStoneOreBlock.LIT) ? REDSTONE_ORE_LIGHT : 0));
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
}
