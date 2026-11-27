package com.dawnduskterminal.block.entity;

import com.dawnduskterminal.entity.GhoflowerProjectile;
import com.dawnduskterminal.registry.ModBlockEntities;
import com.dawnduskterminal.world.GhoflowerSight;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 向日葵、月葵与鬼花的方块实体
 *
 * <p>四类花盘与两类茎共用一个类型。普通的花与茎只用到这里的几何缓存；鬼花另外用到状态机与朝向同步。
 *
 * <h2>鬼花的状态机</h2>
 *
 * <p>只有顶段在跑，由 {@code GhoflowerBlock#getTicker} 挂上来。
 *
 * <ul>
 *   <li><b>观察期</b>：每 4~20 秒一次「尝试」。附近恰好一个玩家（创造、旁观不算）时，
 *       先改朝向面朝这个玩家，再判鬼花是否落在这个玩家的视野里；前两次看见各播一声
 *       cave 的 1/2/10 之一并继续观察，第三次连同转向一起转攻击期，看不见也转攻击期。
 *       附近没有玩家、或者有两个以上玩家，整次尝试跳过——多个玩家的视野不同，
 *       无法用一个结论代表全部玩家。
 *   <li><b>攻击期</b>：不再做任何视野检测。每 2~6 秒朝目标改一次朝向并发一枚射弹；
 *       目标失效或离开 {@link #DISENGAGE_RANGE} 格就脱战，回到观察期。
 * </ul>
 *
 * <p>朝向会同步到客户端：花盘平时按太阳或月亮摆，鬼花一旦有了目标就改用它。
 */
public final class SunflowerBlockEntity extends BlockEntity {
    /** 观察期的倒计时范围：4~20 秒 */
    private static final int WATCH_MIN_TICKS = 4 * 20;

    private static final int WATCH_MAX_TICKS = 20 * 20;

    /** 攻击期的发射间隔：2~6 秒 */
    private static final int FIRE_MIN_TICKS = 2 * 20;

    private static final int FIRE_MAX_TICKS = 6 * 20;

    /**
     * 攻击期的脱战距离，单位格
     *
     * <p>比观察期的 24 格多 8 格：玩家在 24 格边缘来回移动时不会刚脱战又进入观察。
     * 超过这个距离，下一次攻击结算时停火、清掉目标并回到观察期。
     */
    public static final double DISENGAGE_RANGE = 32.0D;

    /**
     * 观察期连续被看见几次就转入攻击
     *
     * <p>前两次只转向并播 cave 音，第三次连同转向一起开火。
     * 「连续」有两次中断：这一次看不见（立刻攻击），或者这一次附近根本没有唯一玩家（计数清零）。
     */
    private static final int SIGHTINGS_TO_ATTACK = 3;

    private enum Phase {
        WATCHING,
        HUNTING
    }

    /** 缓存属于哪一种状态组合（段号与是否顶段），对不上就重建 */
    private int cachedKey = -1;

    /** 柱身与叶片：每顶点 9 个 float，见 {@code SunflowerRenderer} */
    private float[] body;

    private Phase phase = Phase.WATCHING;

    /** 下一次尝试或发射还有多少 tick；{@code -1} 表示尚未初始化 */
    private int countdown = -1;

    /** 观察期里连续被看见的次数，见 {@link #SIGHTINGS_TO_ATTACK} */
    private int sightings;

    /** 是否已经有朝向目标 */
    private boolean hasAim;

    private float aimX;

    private float aimY = 1.0F;

    private float aimZ;

    @Nullable
    private UUID target;

    public SunflowerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SUNFLOWER.get(), pos, state);
    }

    public int cachedKey() {
        return this.cachedKey;
    }

    public float[] body() {
        return this.body;
    }

    public void setBody(int key, float[] body) {
        this.cachedKey = key;
        this.body = body;
    }

    public boolean hasAim() {
        return this.hasAim;
    }

    public Vec3 aim() {
        return new Vec3(this.aimX, this.aimY, this.aimZ);
    }

    /** 由 {@code GhoflowerBlock#getTicker} 每 tick 调用一次，只跑服务端 */
    public static void serverTick(Level level, BlockPos pos, BlockState state, SunflowerBlockEntity blockEntity) {
        if (level.isClientSide || !(level instanceof ServerLevel server)) {
            return;
        }
        blockEntity.tickGhost(server, pos);
    }

    private void tickGhost(ServerLevel level, BlockPos pos) {
        if (this.countdown < 0) {
            this.countdown = roll(level, this.phase);
            return;
        }
        if (this.countdown > 0) {
            this.countdown--;
            return;
        }
        if (this.phase == Phase.WATCHING) {
            this.watch(level, pos);
        } else {
            this.hunt(level, pos);
        }
    }

    /** 观察期的一次尝试 */
    private void watch(ServerLevel level, BlockPos pos) {
        Player onlooker = GhoflowerSight.soleOnlooker(level, pos);
        if (onlooker == null) {
            // 没有唯一玩家：这一次什么都没发生，连续计数断掉
            this.sightings = 0;
            this.countdown = roll(level, this.phase);
            return;
        }
        this.aimAt(level, pos, onlooker);
        if (!GhoflowerSight.isSeen(level, pos, onlooker)) {
            // 看不见：立刻转攻击
            this.beginHunt(level, pos, onlooker);
            return;
        }
        this.sightings++;
        if (this.sightings >= SIGHTINGS_TO_ATTACK) {
            // 第三次：转向已经做完，直接开火
            this.beginHunt(level, pos, onlooker);
            return;
        }
        GhoflowerSight.playWhisper(level, pos, onlooker);
        this.countdown = roll(level, this.phase);
    }

    /** 转入攻击期并立刻发射一枚 */
    private void beginHunt(ServerLevel level, BlockPos pos, Player target) {
        this.phase = Phase.HUNTING;
        this.target = target.getUUID();
        this.sightings = 0;
        this.fire(level, pos, target);
        this.countdown = roll(level, this.phase);
    }

    /** 攻击期的一次射击；目标失效或超出脱战距离就停火并回观察期 */
    private void hunt(ServerLevel level, BlockPos pos) {
        Player locked = this.target == null ? null : level.getPlayerByUUID(this.target);
        boolean lost = locked == null
                || locked.isRemoved()
                || locked.isSpectator()
                || locked.isCreative()
                || locked.distanceToSqr(Vec3.atCenterOf(pos)) > DISENGAGE_RANGE * DISENGAGE_RANGE;
        if (lost) {
            this.standDown(level, pos);
            this.countdown = roll(level, this.phase);
            return;
        }
        this.aimAt(level, pos, locked);
        this.fire(level, pos, locked);
        this.countdown = roll(level, this.phase);
    }

    /** 这株是不是处在攻击期 */
    public boolean isHunting() {
        return this.phase == Phase.HUNTING;
    }

    /** 立刻开战：传染出来的鬼花如果有明确的挖掘者，就锁定该玩家并进入攻击期 */
    public void startHunting(UUID attacker) {
        this.phase = Phase.HUNTING;
        this.target = attacker;
        this.sightings = 0;
        this.hasAim = false;
        this.countdown = FIRE_MIN_TICKS;
        this.setChanged();
        if (this.level instanceof ServerLevel server) {
            server.sendBlockUpdated(
                    this.worldPosition, this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** 脱战：回观察期、清掉目标与朝向，并同步给客户端让花盘交还给太阳或月亮 */
    private void standDown(ServerLevel level, BlockPos pos) {
        this.phase = Phase.WATCHING;
        this.target = null;
        this.sightings = 0;
        this.hasAim = false;
        this.setChanged();
        level.sendBlockUpdated(pos, this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
    }

    private void aimAt(ServerLevel level, BlockPos pos, Player player) {
        Vec3 from = GhoflowerSight.headCenter(level, pos);
        Vec3 direction = player.getEyePosition(1.0F).subtract(from);
        if (direction.lengthSqr() < 1.0E-6D) {
            return;
        }
        Vec3 unit = direction.normalize();
        this.aimX = (float) unit.x;
        this.aimY = (float) unit.y;
        this.aimZ = (float) unit.z;
        this.hasAim = true;
        this.setChanged();
        level.sendBlockUpdated(pos, this.getBlockState(), this.getBlockState(), Block.UPDATE_CLIENTS);
    }

    private void fire(ServerLevel level, BlockPos pos, Player target) {
        Vec3 from = GhoflowerSight.headCenter(level, pos);
        // 射弹跟着方块走：鬼花方块发射昼版，月鬼花方块发射夜版
        boolean night = this.getBlockState().getBlock() instanceof com.dawnduskterminal.block.LunarGhoflowerBlock;
        GhoflowerProjectile.spawn(level, from, target.getEyePosition(1.0F), target, night);
        level.playSound(null, BlockPos.containing(from), SoundEvents.GRAVEL_STEP, SoundSource.HOSTILE, 1.0F, 0.6F);
    }

    private static int roll(ServerLevel level, Phase phase) {
        int min = phase == Phase.WATCHING ? WATCH_MIN_TICKS : FIRE_MIN_TICKS;
        int max = phase == Phase.WATCHING ? WATCH_MAX_TICKS : FIRE_MAX_TICKS;
        return min + level.getRandom().nextInt(max - min + 1);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putByte("phase", (byte) this.phase.ordinal());
        tag.putInt("countdown", this.countdown);
        tag.putInt("sightings", this.sightings);
        tag.putBoolean("has_aim", this.hasAim);
        tag.putFloat("aim_x", this.aimX);
        tag.putFloat("aim_y", this.aimY);
        tag.putFloat("aim_z", this.aimZ);
        if (this.target != null) {
            tag.putUUID("target", this.target);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.phase = tag.getByte("phase") == Phase.HUNTING.ordinal() ? Phase.HUNTING : Phase.WATCHING;
        this.countdown = tag.getInt("countdown");
        this.sightings = tag.getInt("sightings");
        this.hasAim = tag.getBoolean("has_aim");
        this.aimX = tag.getFloat("aim_x");
        this.aimY = tag.getFloat("aim_y");
        this.aimZ = tag.getFloat("aim_z");
        this.target = tag.hasUUID("target") ? tag.getUUID("target") : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
