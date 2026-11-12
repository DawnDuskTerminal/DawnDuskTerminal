package com.dawnduskterminal.entity;

import com.dawnduskterminal.world.ChronoRain;
import java.util.EnumSet;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;

/**
 * 下雨时躲到「有顶」的地方去
 *
 * <p>晨昏线维度下雨时，露天的步行生物每 2 秒挨一点雨滴伤害（见 {@link ChronoRain}）。
 * 玩家会自己找屋檐，本模组自己会走路的那些生物得有人替它们想这件事：这条行为在
 * {@link ChronoRain#isExposed(net.minecraft.world.entity.LivingEntity)} 成立时找一个头顶没有天空的落脚点，
 * 寻路过去；走到头顶有遮挡之后行为自然结束，攻击 闲逛那一套接着跑
 *
 * <p>只管「走地面」的那些：飞行的与水生的不挨雨滴伤害（这一版不给它们写躲雨逻辑），
 * 带稳定效果的也免疫，两条都在 {@link #canUse} 里先问过 {@link ChronoRain#isRainImmune}
 *
 * <h2>怎么算「有顶」</h2>
 *
 * <p>{@link #isShelteredAt} 三条一起看：这一格与上面一格都是空气（生物站得进去）、脚下方块是能站的
 * （碰撞箱非空）、并且 {@code canSeeSky} 为假。最后一条用的是原版天光系统，所以树冠
 * 隧道 屋檐 洞穴口都算，与淋雨判定同一套遮蔽关系
 *
 * <h2>扫描方式</h2>
 *
 * <p>按切比雪夫距离一圈一圈往外找（{@link #findShelter}），同一圈里先扫到谁就是谁，因此返回的
 * 一定是「最近的」那一圈里的点。每一列从 {@code MOTION_BLOCKING} 高度图往下最多扫
 * {@value #SHELTER_SCAN_DEPTH} 格：高度图把屋顶也算作遮挡，所以顶下的空腔就在它下面这几格
 *
 * <p>扫描是纯几何，与天气无关，所以能在游戏测试里直接断言：见 {@code ChronoRainTests#shelterSearchFindsCover}
 */
public final class SeekRainShelterGoal extends Goal {
    /** 两次扫描之间隔多少 tick：每只生物每 tick 都会被问一次，扫描按这个周期摊薄 */
    public static final int SCAN_INTERVAL = 10;

    /** 找落脚点的水平半径，单位是格 */
    public static final int SEARCH_RADIUS = 8;

    /** 一列里从高度图往下最多看几格 */
    public static final int SHELTER_SCAN_DEPTH = 8;

    /** 到达判定：水平距离缩到这么近就算到了，剩下的交给「头顶没有天空」自己收尾 */
    public static final double ARRIVE_DISTANCE = 1.5D;

    /** 寻路速度倍率，与原版 {@code WaterAvoidingRandomStrollGoal} 那一档一致 */
    private static final double SPEED = 1.15D;

    /** 一条路走这么久还没到就放弃，免得被地形卡住之后一直占着 MOVE 标志 */
    private static final int PATH_TIMEOUT_TICKS = 80;

    private final PathfinderMob mob;

    @Nullable
    private BlockPos shelter;

    private int scanCooldown;

    private int pathAge;

    public SeekRainShelterGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.mob.level().isClientSide() || this.mob.isNoAi()) {
            return false;
        }
        if (this.scanCooldown > 0) {
            // 每 tick 都会被问一次，扫描按 SCAN_INTERVAL 摊薄
            this.scanCooldown--;
            return false;
        }
        this.scanCooldown = SCAN_INTERVAL;
        // 带稳定效果的免疫淋雨，没有躲的必要
        if (ChronoRain.isRainImmune(this.mob) || !ChronoRain.isExposed(this.mob)) {
            return false;
        }
        this.shelter = findShelter(this.mob.level(), this.mob.blockPosition(), SEARCH_RADIUS);
        return this.shelter != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.shelter == null || !this.mob.isAlive()) {
            return false;
        }
        // 头顶一有遮挡就结束：这时候已经安全，攻击 闲逛那一套该回来
        return ChronoRain.isExposed(this.mob) && !hasArrived();
    }

    @Override
    public void start() {
        this.pathAge = 0;
        this.walkToShelter();
    }

    @Override
    public void tick() {
        if (this.shelter == null) {
            return;
        }
        if (++this.pathAge > PATH_TIMEOUT_TICKS) {
            // 路走不通就作废这一轮，下一轮重新找
            this.shelter = null;
            return;
        }
        if (this.mob.getNavigation().isDone()) {
            // 寻路自己停了（贴住目标或者算不出路）：再给一次目标点，超时那一支负责收尾
            this.walkToShelter();
        }
    }

    @Override
    public void stop() {
        this.shelter = null;
        this.pathAge = 0;
        this.scanCooldown = SCAN_INTERVAL;
        this.mob.getNavigation().stop();
    }

    private void walkToShelter() {
        if (this.shelter == null) {
            return;
        }
        Path path = this.mob.getNavigation().createPath(this.shelter, 1);
        if (path == null) {
            this.mob
                    .getNavigation()
                    .moveTo(this.shelter.getX() + 0.5D, this.shelter.getY(), this.shelter.getZ() + 0.5D, SPEED);
            return;
        }
        this.mob.getNavigation().moveTo(path, SPEED);
    }

    private boolean hasArrived() {
        if (this.shelter == null) {
            return true;
        }
        double dx = this.mob.getX() - (this.shelter.getX() + 0.5D);
        double dz = this.mob.getZ() - (this.shelter.getZ() + 0.5D);
        return dx * dx + dz * dz <= ARRIVE_DISTANCE * ARRIVE_DISTANCE;
    }

    /**
     * 这一格能不能当避雨点
     *
     * <p>三条一起看：自己是空气、上面一格也是空气（站得进去）、脚下方块挡得住（站得住）、
     * 并且头顶有遮挡（{@link ChronoRain#isOpenSky} 为假）
     *
     * <p>「遮挡」走高度图而不是 {@code canSeeSky}：这个维度的天光被压到 12~15，露天处
     * {@code canSeeSky} 也是假，拿它当判据会让每只生物都以为自己已经躲好了
     */
    public static boolean isShelteredAt(Level level, BlockPos pos) {
        if (ChronoRain.isOpenSky(level, pos)) {
            return false;
        }
        if (!level.isEmptyBlock(pos) || !level.isEmptyBlock(pos.above())) {
            return false;
        }
        BlockPos floor = pos.below();
        // 用碰撞箱而不是已经弃用的 blocksMotion()：台阶 半砖这类「站得住但不完整」的方块也算地面
        return !level.getBlockState(floor).getCollisionShape(level, floor).isEmpty();
    }

    /**
     * 找最近的避雨点：按切比雪夫距离一圈一圈往外扫
     *
     * <p>同一圈里哪一格先被找到就用哪一格，所以结果是「最近这一圈里的某一个」，不会为了更远的一格
     * 多走十几步。一圈也没找到就返回 {@code null}
     */
    @Nullable
    public static BlockPos findShelter(Level level, BlockPos origin, int radius) {
        for (int ring = 1; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    BlockPos found = shelterInColumn(level, origin.getX() + dx, origin.getZ() + dz);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    /** 一列里离地表最近的避雨点：从高度图往下扫 {@value #SHELTER_SCAN_DEPTH} 格 */
    @Nullable
    private static BlockPos shelterInColumn(Level level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        int bottom = Math.max(level.getMinBuildHeight() + 1, top - SHELTER_SCAN_DEPTH);
        for (int y = top; y >= bottom; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (isShelteredAt(level, pos)) {
                return pos;
            }
        }
        return null;
    }
}
