package com.dawnduskterminal.structure;

import com.dawnduskterminal.registry.ModProcessors;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import twilightforest.init.TFBlocks;

/**
 * 按世界 y 取舍方块，并负责“只落空气 / 只落实体方块”这两遍
 *
 * <p>件已经按 y 段拆开，摆不摆由格子的处境决定；这里只负责这几类通用的过滤：
 * 按世界 y 裁出要清的那几行、把一件拆成「只落空气 / 只落实体方块」两遍、跳过结构空位，
 * 以及给整栋房子做一点随机缺失（其中一部分位置换成蜘蛛网）。
 * 不能用件的包围盒裁——{@code PoolElementStructurePiece#place} 传给模板的是<b>区块</b>的包围盒，
 * 自己那件的盒子裁不掉方块。
 *
 * <p>结构空位（{@code minecraft:structure_void}）一律不落笔：原版只在拼图里认这个块，
 * 本模组直接往 {@code StructurePiecesBuilder} 里塞件，所以在这里补上同一条语义。
 *
 * <p>{@link Phase} 把一件拆成两遍落笔：整栋房子先只落空气把地形挖开，再只落实体方块。
 * 顺序反过来的话，后摆那件的空气会把前一件摆在框架线上的边框家具（树皮、栏杆、门）擦掉。
 *
 * <p><b>参数必须进编解码器。</b>结构 start 会连同件一起写进区块，玩家分次探索时它是从磁盘
 * 读回来再落笔的；只看 {@code processor_type}、把 {@code min_y}/{@code max_y}/{@code phase}
 * 丢掉的写法，读回来就全变成「无窗口 + ALL」——清空件会多写两行空气、把天花板擦掉，
 * 同一份种子在不同探索顺序下长出的房子就不一样。旧存档没有这几个字段，按默认值读回。
 *
 * <p>随机缺失用「位置哈希 + 每个结构一颗盐」决定，不用 {@code RandomSource}：
 * {@link StructureProcessor#process} 拿不到摆放用的随机源，而世界位置哈希在同一份存档里稳定，
 * 同一区块反复进出也不会换一副面孔。带 nbt 的方块（宝箱）不参与缺失。
 */
public final class BandProcessor extends StructureProcessor {
    /** 运行时构造；所有参数都参与序列化 */
    public static final MapCodec<BandProcessor> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.INT.optionalFieldOf("min_y", Integer.MIN_VALUE).forGetter(BandProcessor::minY),
                    Codec.INT.optionalFieldOf("max_y", Integer.MAX_VALUE).forGetter(BandProcessor::maxY),
                    StringRepresentable.fromEnum(Phase::values)
                            .optionalFieldOf("phase", Phase.ALL)
                            .forGetter(BandProcessor::phase),
                    Codec.intRange(0, 1000).optionalFieldOf("decay", 0).forGetter(BandProcessor::decayPermille),
                    Codec.LONG.optionalFieldOf("web_seed", 0L).forGetter(BandProcessor::webSeed))
            .apply(instance, BandProcessor::new));

    /** 这一遍只落哪一半 */
    public enum Phase implements StringRepresentable {
        ALL("all"),
        AIR("air"),
        SOLID("solid");

        private final String name;

        Phase(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }

    private final int minY;
    private final int maxY;

    private final Phase phase;

    /** 结构缺失率，千分之几 */
    private final int decayPermille;

    /** 0 表示这栋房子不长蜘蛛网，否则拿它当位置哈希的盐 */
    private final long webSeed;

    public BandProcessor(int minY, int maxY, Phase phase) {
        this(minY, maxY, phase, 0, 0L);
    }

    public BandProcessor(int minY, int maxY, Phase phase, int decayPermille, long webSeed) {
        this.minY = minY;
        this.maxY = maxY;
        this.phase = phase;
        this.decayPermille = decayPermille;
        this.webSeed = webSeed;
    }

    public int minY() {
        return this.minY;
    }

    public int maxY() {
        return this.maxY;
    }

    public Phase phase() {
        return this.phase;
    }

    public int decayPermille() {
        return this.decayPermille;
    }

    public long webSeed() {
        return this.webSeed;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return ModProcessors.BAND.get();
    }

    @Override
    @Nullable
    public StructureTemplate.StructureBlockInfo process(
            LevelReader level,
            BlockPos pos,
            BlockPos pivot,
            StructureTemplate.StructureBlockInfo raw,
            StructureTemplate.StructureBlockInfo current,
            StructurePlaceSettings settings,
            @Nullable StructureTemplate template) {
        if (raw.state().is(Blocks.STRUCTURE_VOID)) {
            return null;
        }
        int y = current.pos().getY();
        if (y < this.minY || y > this.maxY) {
            return null;
        }
        boolean air = raw.state().isAir();
        if ((this.phase == Phase.AIR && !air) || (this.phase == Phase.SOLID && air)) {
            return null;
        }
        // 随机缺失：只挑实体方块，带 nbt 的（宝箱）不碰，绳梯那一列的方块也不碰——
        // 缺一格整条绳/梯就断，而且会变成掉落物（往下延伸的大半段就全掉下来了）
        if (this.decayPermille > 0
                && !air
                && raw.nbt() == null
                && current.nbt() == null
                && !isClimbable(raw.state())
                && !(raw.state().getBlock() instanceof TrapDoorBlock)) {
            int roll = positionHash(current.pos(), this.webSeed);
            if (Math.floorMod(roll, 1000) < this.decayPermille) {
                if (this.webSeed != 0L && Math.floorMod(roll >> 7, 4) == 0) {
                    return new StructureTemplate.StructureBlockInfo(
                            current.pos(), Blocks.COBWEB.defaultBlockState(), null);
                }
                return null;
            }
        }
        return current;
    }

    /** 绳梯列：Twilight Forest 的绳与原版的梯子都算 */
    private static boolean isClimbable(BlockState state) {
        return state.is(TFBlocks.ROPE.get()) || state.is(Blocks.LADDER);
    }

    /** 位置哈希：同一格世界坐标加上同一颗盐，永远得到同一个数 */
    private static int positionHash(BlockPos pos, long salt) {
        long h = pos.asLong() * 0x9E3779B97F4A7C15L + salt;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (int) h;
    }
}
