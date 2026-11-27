package com.dawnduskterminal.world;

import com.dawnduskterminal.block.AbstractSunflowerBlock;
import com.dawnduskterminal.registry.ModSounds;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 鬼花的「被看见」判定
 *
 * <p>判定的对象是花盘中心，判定者是附近唯一那个玩家：
 *
 * <ol>
 *   <li>距离：{@link #RANGE} 格以内；
 *   <li>视锥：玩家视线与「眼 → 花盘」的夹角不超过 {@link #HALF_ANGLE_DEGREES} 度；
 *   <li>遮挡：从眼睛到花盘拉一条射线，只认<b>有碰撞箱</b>的方块。
 * </ol>
 *
 * <p>第三条是这套判定的关键：向日葵长在植物丛里，而草、花、高草、藤蔓、雪层都没有碰撞箱，
 * 所以草丛不会把鬼花「藏」起来；只有树叶、泥土、石头这一类才会挡住视线。
 *
 * <p>附近没有玩家、或者有两个以上玩家时，{@link #soleOnlooker} 一律返回 {@code null}，
 * 调用方据此整次跳过：多个玩家的视野不同，没法用一个「被看见」的结论代表所有人。
 */
public final class GhoflowerSight {
    /** 检测距离，单位格 */
    public static final double RANGE = 24.0D;

    /** 视锥半角，单位度 */
    public static final double HALF_ANGLE_DEGREES = 60.0D;

    private static final double HALF_ANGLE_COS = Math.cos(Math.toRadians(HALF_ANGLE_DEGREES));

    /** 贴脸不算看见，避免站在花上时的退化情形 */
    private static final double MIN_RANGE = 0.5D;

    private GhoflowerSight() {}

    /** 花盘中心的绝对坐标：顶段的柱身只有 8 像素高，花盘就坐在那上面 */
    public static Vec3 headCenter(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        float height =
                state.getValue(AbstractSunflowerBlock.HEAD) ? AbstractSunflowerBlock.HEAD_STEM_HEIGHT / 16.0F : 0.0F;
        return new Vec3(pos.getX() + 0.5D, pos.getY() + height, pos.getZ() + 0.5D);
    }

    /** 范围内唯一的玩家；没有玩家或有两个以上都返回 {@code null}（整次跳过） */
    @Nullable
    public static Player soleOnlooker(ServerLevel level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        double rangeSquared = RANGE * RANGE;
        List<ServerPlayer> candidates = level.getPlayers(player ->
                !player.isCreative() && !player.isSpectator() && player.distanceToSqr(center) <= rangeSquared);
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    /**
     * 距离与视锥的纯判定：眼到花盘的距离在范围内，且视线与目标方向的夹角不超过半角
     *
     * <p>只接受眼、视线与目标三个向量，不读玩家对象的朝向状态。
     */
    public static boolean withinCone(Vec3 eye, Vec3 view, Vec3 target) {
        Vec3 toTarget = target.subtract(eye);
        double distance = toTarget.length();
        if (distance < MIN_RANGE || distance > RANGE) {
            return false;
        }
        if (view.lengthSqr() < 1.0E-6D) {
            return false;
        }
        return view.normalize().dot(toTarget.scale(1.0D / distance)) >= HALF_ANGLE_COS;
    }

    /** 这个玩家此刻能不能看见花盘 */
    public static boolean isSeen(ServerLevel level, BlockPos pos, Player player) {
        Vec3 target = headCenter(level, pos);
        Vec3 eye = player.getEyePosition(1.0F);
        if (!withinCone(eye, player.getViewVector(1.0F), target)) {
            return false;
        }
        BlockHitResult hit =
                level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(target) < 0.25D;
    }

    /**
     * 播 cave 1 / 2 / 10 之一，只放给这一个玩家
     *
     * <p>不能用 {@code level.playSound}：那是位置音效，按距离衰减，
     * 而鬼花的检测距离是 24 格、洞穴音的默认衰减距离只有 16 格，站远一点就听不见。
     * 原版洞穴环境声本身就是本地音效（{@code client/sounds/CaveSound}），
     * 所以这里用 {@link Player#playNotifySound}：按 0 距离、只发给该玩家、不衰减。
     */
    public static void playWhisper(ServerLevel level, BlockPos pos, Player target) {
        SoundEvent sound =
                switch (level.getRandom().nextInt(3)) {
                    case 0 -> ModSounds.GHOFLOWER_CAVE_1.get();
                    case 1 -> ModSounds.GHOFLOWER_CAVE_2.get();
                    default -> ModSounds.GHOFLOWER_CAVE_10.get();
                };
        target.playNotifySound(sound, SoundSource.AMBIENT, 1.0F, 1.0F);
    }
}
