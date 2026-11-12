package com.dawnduskterminal.entity;

import com.dawnduskterminal.world.HazardousFluid;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * 见到会伤人的流体就退开
 *
 * <p>给的是<b>不走寻路</b>的那几种生物（两只恶魂变体都是 {@code FlyingMob}，移动全靠
 * {@code MoveControl#setWantedPosition}），它们过不了 {@code HazardousFluidPathingMixin} 那道网：
 * 那道网管的是 {@code PathNavigation}，而这一路的「想去哪」是实体自己算的，
 * 贴地飞行的变体照样会一头扎进海一样的 {@code portal_fluid} 里
 *
 * <p>做法很直白：每 {@value #SCAN_INTERVAL} tick 扫一次身边 {@value #SCAN_RADIUS} 格（竖直
 * ±{@value #VERTICAL_REACH} 格）有没有危险流体，找到就把「退开点」算在离它
 * {@value #RETREAT_DISTANCE} 格的水平反方向上、再抬高 {@value #CLIMB} 格，
 * 然后连推 {@value #HOLD_TICKS} tick。推的时候不重新扫：这 1 秒里流体既不会动、恶魂也不会瞬间飞出去
 *
 * <p>竖直只看 ±{@value #VERTICAL_REACH} 格：在大海上空几十格盘旋的那一只不该被脚下的水吓跑，
 * 而贴着水面掠过去的那一只必须跑
 */
public final class AvoidHazardousFluidGoal extends Goal {
    /** 扫描的水平半径（格） */
    public static final int SCAN_RADIUS = 4;

    /** 扫描的竖直半径（格） */
    public static final int VERTICAL_REACH = 3;

    /** 两次扫描之间隔多少 tick：扫描是 9×9×7 = 567 次取格，不必每 tick 一次 */
    public static final int SCAN_INTERVAL = 10;

    /** 退开点离那格流体有多远（格，水平方向） */
    public static final double RETREAT_DISTANCE = 6.0D;

    /** 退开点比现在高多少格：先脱离水面再说 */
    public static final double CLIMB = 2.0D;

    /** 交给移动控制器的速度，与原版 {@code RandomFloatAroundGoal} 那一个 1.0 同档 */
    public static final double SPEED = 1.0D;

    /** 一次退开连推多少 tick */
    public static final int HOLD_TICKS = 20;

    private final Mob mob;

    /** 扫描用的可变坐标，省得每次取格都新建一个 {@link BlockPos} */
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private int scanCooldown;

    private int hold;

    private Vec3 retreat;

    public AvoidHazardousFluidGoal(Mob mob) {
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
        BlockPos threat = nearest(this.mob, this.cursor);
        if (threat == null) {
            return false;
        }
        this.retreat = retreatFrom(this.mob, threat);
        this.hold = HOLD_TICKS;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.retreat == null) {
            return false;
        }
        return --this.hold > 0;
    }

    @Override
    public void start() {
        this.steer();
    }

    @Override
    public void tick() {
        this.steer();
    }

    @Override
    public void stop() {
        this.retreat = null;
        this.hold = 0;
    }

    private void steer() {
        if (this.retreat == null) {
            return;
        }
        if (this.mob instanceof PathfinderMob pathfinder) {
            pathfinder.getNavigation().moveTo(this.retreat.x, this.retreat.y, this.retreat.z, SPEED);
        } else {
            this.mob.getMoveControl().setWantedPosition(this.retreat.x, this.retreat.y, this.retreat.z, SPEED);
        }
    }

    /** 身边最近的那格危险流体；一格都没有就返回 {@code null} */
    public static BlockPos nearest(Mob mob) {
        return nearest(mob, new BlockPos.MutableBlockPos());
    }

    private static BlockPos nearest(Mob mob, BlockPos.MutableBlockPos cursor) {
        BlockPos origin = mob.blockPosition();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = -VERTICAL_REACH; dy <= VERTICAL_REACH; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!HazardousFluid.isHazard(mob.level(), cursor)) {
                        continue;
                    }
                    double distance = origin.distSqr(cursor);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = cursor.immutable();
                    }
                }
            }
        }
        return nearest;
    }

    /**
     * 退开点：从那一格流体指向自己的水平方向再往前 {@value #RETREAT_DISTANCE} 格，抬高 {@value #CLIMB} 格
     *
     * <p>正好站在那格流体正上方时水平方向是零向量，这时摇一个水平方向——不然归一化会得到 NaN，
     * 移动控制器会把这只恶魂送到 {@code (NaN, NaN, NaN)}
     */
    public static Vec3 retreatFrom(Mob mob, BlockPos threat) {
        double awayX = mob.getX() - (threat.getX() + 0.5D);
        double awayZ = mob.getZ() - (threat.getZ() + 0.5D);
        double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (length < 1.0E-4D) {
            double angle = mob.getRandom().nextDouble() * (Math.PI * 2.0D);
            awayX = Math.cos(angle);
            awayZ = Math.sin(angle);
            length = 1.0D;
        }
        return new Vec3(
                mob.getX() + awayX / length * RETREAT_DISTANCE,
                mob.getY() + CLIMB,
                mob.getZ() + awayZ / length * RETREAT_DISTANCE);
    }
}
