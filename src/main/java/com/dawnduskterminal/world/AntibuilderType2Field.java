package com.dawnduskterminal.world;

import com.dawnduskterminal.block.AntibuilderType2Block;
import com.dawnduskterminal.registry.ModBlocks;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 反建器二型压出来的场：范围里被破坏的方块换成反建方块二型，被放置进来的方块原样退回去
 *
 * <p>与暮色森林那台反建器的根本差别是「不抄快照」：那边每 tick 把整个立方抄进 {@code BlockState[]}，
 * 再慢慢比对着往回改；这里一个方块状态都不存，只在方块真正被破坏或被放置的那一刻判定一次，
 * 见 {@code mixin/LevelSetBlockReactionMixin} 挂在 {@code Level#setBlock} 上的那一处
 *
 * <p>场心按维度登记，但**不是一张平铺的集合**：场心先按区块分桶，查一格只看它所在区块与紧邻的两个方向
 * 共 3×3 个桶。半径最大 8 格，小于一个区块的 16 格，所以能盖住这一格的场心一定落在这 9 个桶里，
 * 远处的机器一次都不用碰。每个维度里现场摆几台机器，这条决定的是「加机器会不会变慢」：
 * 平铺集合的话每台机器都要被量一遍，分桶之后与场里机器总数无关
 *
 * <p>半径不进表：每次判定都直接从场心那一格的方块状态上读 {@link AntibuilderType2Block#RANGE}，
 * 于是右键调完范围当场就变，也不存在两份数值对不上的可能
 *
 * <p>三条例外，前两条是规格，第三条是为了不把机器自己锁死在原地：
 * <ul>
 *   <li>被破坏的方块是反建器家族（暮色森林那一台、本模组这一台）：复原它，而不是换反建方块
 *   <li>被放置进来的方块是反建器家族：放着不管，而不是退回去。只有一条例外：反建器二型不许挨着
 *       另一台开着的反建器二型摆（切比雪夫距离 ≤ 1，斜角也算），那一台照普通方块处理，当场破坏
 *   <li>一个场不管自己的场心那一格：否则任何半径都包住场心，单台机器放下就再也拆不掉。
 *       别的机器的场照管它——范围内的反建器二型被破坏时照样复原，这是规格要求的
 * </ul>
 *
 * <p>收到红石信号就关机：{@link AntibuilderType2Block#POWERED} 为真的机器整个场停摆——
 * 不反应、不画框线、方块自己也不发光，但场心留在表里，信号一断就恢复。
 * 关机的机器连带不算「相邻有机器」，所以贴着它摆第二台是允许的
 *
 * <p>液体不参与判定：「原来的状态是空气、新状态是水」这种是水流，不是放置；「原来的状态是水、
 * 新状态是空气」是水退掉，不是破坏。暮色森林那边是靠 {@code #twilightforest:antibuilder_ignores}
 * 里的 {@code minecraft:water} 躲开的，这里不需要那张标签
 *
 * <p>放置被退回去之前先发一次 {@code LevelEvent.PARTICLES_DESTROY_BLOCK}：客户端会炸出那块的碎片、
 * 放那块的破坏音。方块凭空消失看着像根本没放上去，炸一下才看得出是「被破坏了」
 */
public final class AntibuilderType2Field {
    /** 反建器二型之间最近能摆多近：切比雪夫距离 1，也就是含斜角的 26 个方向挨着都算相邻 */
    public static final int ADJACENT_DISTANCE = 1;

    /** 一个区块有多少格（X 与 Z 都是这个数），分桶的粒度 */
    private static final int CHUNK_SIZE = 16;

    /**
     * 查一格时要往两侧各翻几个区块
     *
     * <p>半径最大 8 格，比一个区块的 16 格还小，所以能盖住这一格的场心一定在同一个或紧邻的区块里，
     * 翻一格就够；这个数由 {@link AntibuilderType2Block#MAX_RADIUS} 算出来，改了上限也不会漏
     */
    private static final int CHUNK_REACH =
            Math.max(1, (AntibuilderType2Block.MAX_RADIUS + CHUNK_SIZE - 1) / CHUNK_SIZE);

    /** 维度 → （区块坐标打包成 {@code long} → 这个区块里的场心） */
    private static final Map<ResourceKey<Level>, Map<Long, Set<BlockPos>>> CENTRES = new HashMap<>();

    private AntibuilderType2Field() {}

    /** 这个维度里有没有正在工作的机器；热路径上的第一道闸，没有场的地方只花一次查表 */
    public static boolean active(Level level) {
        Map<Long, Set<BlockPos>> buckets = CENTRES.get(level.dimension());
        return buckets != null && !buckets.isEmpty();
    }

    /** 登记一个场心：方块实体装载、方块刚摆下时各来一次，重复登记没有额外代价（就是个集合） */
    public static void track(Level level, BlockPos pos) {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockPos centre = pos.immutable();
        CENTRES.computeIfAbsent(level.dimension(), key -> new HashMap<>())
                .computeIfAbsent(bucketKey(centre), key -> new HashSet<>())
                .add(centre);
    }

    /** 撤掉一个场心：方块实体卸载、方块被挖掉或推走时各来一次，撤不存在的没有代价 */
    public static void untrack(Level level, BlockPos pos) {
        if (level == null || level.isClientSide) {
            return;
        }
        Map<Long, Set<BlockPos>> buckets = CENTRES.get(level.dimension());
        if (buckets == null) {
            return;
        }
        long key = bucketKey(pos);
        Set<BlockPos> bucket = buckets.get(key);
        if (bucket == null) {
            return;
        }
        bucket.remove(pos);
        if (bucket.isEmpty()) {
            buckets.remove(key);
            if (buckets.isEmpty()) {
                CENTRES.remove(level.dimension());
            }
        }
    }

    /** 服务器停下：整张表清干净，别带到下一次启动 */
    public static void clear() {
        CENTRES.clear();
    }

    /**
     * 这一格在不在某个场里
     *
     * <p>只看这一格所在区块与紧邻的两个方向共 3×3 个桶：能盖住它的场心跑不出这九个桶
     *
     * <p>一个场永远不管自己的场心那一格（否则任何半径都包住场心，机器放下就再也拆不掉），
     * 但别的机器的场照管它——于是范围内的反建器二型被破坏时照样复原，这是规格要求的。
     *
     * <p>关机的机器（{@link AntibuilderType2Block#POWERED}）整个场停摆，但场心留在表里：
     * 红石信号一断就恢复，不必等区块重新载入
     *
     * <p>最后回头核一遍那一格还是不是本方块：方块实体那边撤登记真漏了也不会留下一个死活不掉的幽灵场。
     * 核之前先问 {@code ChunkSource#hasChunk}，不会为了找方块把区块读进来
     */
    public static boolean covers(Level level, BlockPos at) {
        Map<Long, Set<BlockPos>> buckets = CENTRES.get(level.dimension());
        if (buckets == null || buckets.isEmpty()) {
            return false;
        }
        int chunkX = SectionPos.blockToSectionCoord(at.getX());
        int chunkZ = SectionPos.blockToSectionCoord(at.getZ());
        for (int dx = -CHUNK_REACH; dx <= CHUNK_REACH; dx++) {
            for (int dz = -CHUNK_REACH; dz <= CHUNK_REACH; dz++) {
                Set<BlockPos> bucket = buckets.get(ChunkPos.asLong(chunkX + dx, chunkZ + dz));
                if (bucket == null) {
                    continue;
                }
                Iterator<BlockPos> iterator = bucket.iterator();
                while (iterator.hasNext()) {
                    BlockPos centre = iterator.next();
                    if (centre.equals(at)) {
                        // 自己的场心那一格：这个场不管它
                        continue;
                    }
                    BlockState machine = machineAt(level, centre);
                    if (machine == null) {
                        iterator.remove();
                        continue;
                    }
                    if (machine.getValue(AntibuilderType2Block.POWERED)) {
                        continue;
                    }
                    int radius = AntibuilderType2Block.radiusOf(machine);
                    if (Math.abs(at.getX() - centre.getX()) > radius
                            || Math.abs(at.getY() - centre.getY()) > radius
                            || Math.abs(at.getZ() - centre.getZ()) > radius) {
                        continue;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 一次方块变化里该做些什么：返回要写回去的状态，没有反应就是 {@code null}
     *
     * <p>只有两种变化算数，其余一律放过（方块自己的状态变化，比如门开、熔炉点火、红石灯亮灭，
     * 原来的方块与新方块都不是空气，两条都套不上）：
     * <ul>
     *   <li>破坏：原来是实心方块，新的状态是空气或者液体（后者是含水方块被拆掉，水留在原地）
     *   <li>放置：原来是可以被顶掉的东西（空气、液体，以及草与雪层这类 {@code canBeReplaced()} 为真的方块），
     *       新的状态是实心方块——把方块摆进草丛与雪层里同样算一次放置
     * </ul>
     *
     * <p>返回值与原状态相同时，调用方也按「没有反应」处理
     */
    @Nullable
    public static BlockState react(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (level.isClientSide || oldState == newState) {
            return null;
        }
        boolean broken = isBreak(oldState, newState);
        boolean placed = isPlace(oldState, newState);
        if (!broken && !placed) {
            return null;
        }
        if (!covers(level, pos)) {
            return null;
        }
        if (broken) {
            // 反建器家族：复原它，而不是换成反建方块二型
            return AntibuilderType2Block.isAntibuilderFamily(oldState)
                    ? oldState
                    : ModBlocks.ANTIBUILD_BLOCK_TYPE2.get().defaultBlockState();
        }
        // 反建器家族：放着不管。只有一条例外：反建器二型挨着另一台开着的反建器二型摆不下来
        // （切比雪夫距离 ≤ ADJACENT_DISTANCE，斜角也算），那一台照普通方块处理，当场破坏
        if (AntibuilderType2Block.isAntibuilderFamily(newState)) {
            boolean crowded = false;
            Map<Long, Set<BlockPos>> buckets = CENTRES.get(level.dimension());
            if (newState.is(ModBlocks.ANTIBUILDER_TYPE2.get()) && buckets != null) {
                int chunkX = SectionPos.blockToSectionCoord(pos.getX());
                int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
                for (int dx = -CHUNK_REACH; dx <= CHUNK_REACH && !crowded; dx++) {
                    for (int dz = -CHUNK_REACH; dz <= CHUNK_REACH && !crowded; dz++) {
                        Set<BlockPos> bucket = buckets.get(ChunkPos.asLong(chunkX + dx, chunkZ + dz));
                        if (bucket == null) {
                            continue;
                        }
                        for (BlockPos centre : bucket) {
                            if (Math.abs(pos.getX() - centre.getX()) > ADJACENT_DISTANCE
                                    || Math.abs(pos.getY() - centre.getY()) > ADJACENT_DISTANCE
                                    || Math.abs(pos.getZ() - centre.getZ()) > ADJACENT_DISTANCE) {
                                continue;
                            }
                            BlockState machine = machineAt(level, centre);
                            // 关机的机器不算：它那个场本来就不管事
                            if (machine != null && !machine.getValue(AntibuilderType2Block.POWERED)) {
                                crowded = true;
                                break;
                            }
                        }
                    }
                }
            }
            if (!crowded) {
                return null;
            }
        }
        // 其余一律退回去。退回去之前先按原版挖掉那一套炸出碎片与音效：
        // 方块凭空消失看着像是没放上去，炸一下才看得出是「被破坏了」
        level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(newState));
        return oldState;
    }

    /** 这一格落在哪个桶里：区块坐标打包成一个 {@code long}，与 {@code ChunkPos#asLong} 是同一套算法 */
    private static long bucketKey(BlockPos pos) {
        return ChunkPos.asLong(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    /** 破坏：原来是实心方块，新的状态是空气或者液体 */
    private static boolean isBreak(BlockState oldState, BlockState newState) {
        return !isAirLike(oldState) && (newState.isAir() || newState.getBlock() instanceof LiquidBlock);
    }

    /** 放置：原来是可以被顶掉的东西，新的状态是实心方块 */
    private static boolean isPlace(BlockState oldState, BlockState newState) {
        return (isAirLike(oldState) || oldState.canBeReplaced()) && !isAirLike(newState);
    }

    private static boolean isAirLike(BlockState state) {
        return state.isAir() || state.getBlock() instanceof LiquidBlock;
    }

    /**
     * 场心那一格现在的状态：{@code null} 表示区块没载入，或者那一格已经不是本方块（幽灵场心），
     * 调用方顺手把这一条丢掉
     */
    @Nullable
    private static BlockState machineAt(Level level, BlockPos centre) {
        if (!level.getChunkSource()
                .hasChunk(
                        SectionPos.blockToSectionCoord(centre.getX()), SectionPos.blockToSectionCoord(centre.getZ()))) {
            return null;
        }
        BlockState state = level.getBlockState(centre);
        return state.getBlock() instanceof AntibuilderType2Block ? state : null;
    }
}
