package com.dawnduskterminal.entity;

import com.dawnduskterminal.registry.ModDamageTypes;
import com.dawnduskterminal.registry.ModSounds;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.dawnduskterminal.world.HazardousFluid;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 「虚像」：河海类群系里由不稳定液体凝成的人形影子
 *
 * <h2>外形</h2>
 * 几何是一具玩家比例的人形骨架，动作照玩家那一套算：走路甩臂迈腿、低头抬头、出手时抬手。
 * 贴图是 {@code block/portal_fluid_flow}——一张 32×1024 的 32 帧动画竖条；
 * 几何只声明单帧 32×32，所有 UV 都落在这一帧之内，渲染时逐帧换成当前帧。
 * 外面再套一层同源的盔甲壳，alpha 0.2，与本体一起发光。
 * 全身每个部位逐帧小幅随机抖动，身上不断落下哭泣的黑曜石泪滴。
 * 这些都在客户端，见 {@link com.dawnduskterminal.client.VirtualImageRenderer}。
 *
 * <h2>数值</h2>
 * 生命、伤害与小型「弦」同档：生命 60、近战 10 点，抗性也照同一套机制写，见「免疫」一节。
 * 每只个体再叠一个 0.85 到 1.15 的随机体型偏移，生命、攻击与移速都跟着这个偏移走，
 * 与「弦」的随机体型是同一个机制。
 *
 * <h2>音效</h2>
 * 直接用「弦」的那两组事件：待机音一条接一条地排，受伤与死亡各播 {@code string_hit}。
 *
 * <h2>AI</h2>
 * 地面近战：寻路靠近、贴脸挥击，与僵尸那一类同构。它不上天、不下水，也不离开脚下的地面；
 * 例外是 {@code portal_fluid}：它能在液面上行走，见 {@link #canStandOnFluid}。
 *
 * <h2>生成</h2>
 * 只出现在河海类群系（河与海的全部温度档）的霞草方块上，
 * 见 {@link #checkVirtualImageSpawnRules}。群系条目写在 {@code worldgen/biome/} 下
 * 每一份河海群系的 {@code monster} 表里。
 *
 * <h2>免疫</h2>
 * 与「弦」同一套：不吃击退，也不被活塞与水流推动；药水无效；传送门流体那条稳定伤害对它无效。
 */
public class VirtualImageEntity extends Monster {
    /** 随机体型偏移的范围 */
    private static final float MIN_SIZE = 0.85F;

    private static final float MAX_SIZE = 1.15F;

    /** 小型「弦」的基准生命，随机体型偏移再乘上去 */
    private static final float BASE_HEALTH = 60.0F;

    /** 小型「弦」的基准近战伤害，随机体型偏移再乘上去 */
    private static final float BASE_ATTACK = 10.0F;

    /** 地面寻路的基准移动速度，与玩家步行的折算值相近 */
    private static final double BASE_SPEED = 0.3D;

    /**
     * 待机音的长度，187 tick
     *
     * <p>与 {@code StringEntity} 同一段素材：{@code string_ambient_1..3} 三个文件都是 413192 个采样、
     * 44100 Hz，即 9.369 秒 = 187.4 tick。排期按整 tick 走，取 187
     */
    private static final int AMBIENT_LENGTH_TICKS = 187;

    /** 下一条在当前这条尾巴前这么多 tick 起播，两条重叠，听感上就没有断点 */
    private static final int AMBIENT_OVERLAP_TICKS = 8;

    /** 排期周期：一条的时长减去重叠量 */
    private static final int AMBIENT_PERIOD_TICKS = AMBIENT_LENGTH_TICKS - AMBIENT_OVERLAP_TICKS;

    /** 每两 tick 落一颗泪滴：再密就成了水柱，再疏就看不出「不断产生」 */
    private static final int TEAR_INTERVAL_TICKS = 2;

    private static final double MAX_CONTINUOUS_STEP_SQUARED = 16.0D;
    private static final int RETALIATION_UNSEEN_TICKS = 60;
    private static final byte IDLE_REPLAY = 1;
    private static final byte USE_MAIN_HAND = 2;
    private static final byte USE_OFF_HAND = 3;
    private static final EntityDataAccessor<Float> DATA_SIZE =
            SynchedEntityData.defineId(VirtualImageEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> DATA_REPLAY_STATE =
            SynchedEntityData.defineId(VirtualImageEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Boolean> DATA_REPLAY_TELEPORT =
            SynchedEntityData.defineId(VirtualImageEntity.class, EntityDataSerializers.BOOLEAN);

    /** 随机体型偏移：生命、攻击、移速与渲染缩放都乘这一个数 */
    private float sizeScale = 1.0F;

    /** 距离下一条待机音还有多少 tick */
    private int ambientTimer = AMBIENT_PERIOD_TICKS / 2;

    @Nullable
    private VirtualImageTrace replay;

    private int replayFrame;
    private boolean replayBlocked;
    private int retaliationUnseenTicks;

    public VirtualImageEntity(EntityType<? extends VirtualImageEntity> type, Level level) {
        super(type, level);
        this.xpReward = 10;
        // Entity 构造函数跑 getDimensions 时子类字段还没初始化，这里补一次
        this.refreshDimensions();
    }

    /**
     * 属性表
     *
     * <p>基准生命 60、近战 10 点，与小型「弦」同一档；随机体型偏移在 {@link #setSizeScale} 里乘上去。
     * 抗性也走同一条：击退抗性拉满，另外在 {@link #knockback} 里把击退整个吞掉
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, BASE_HEALTH)
                .add(Attributes.ATTACK_DAMAGE, BASE_ATTACK)
                .add(Attributes.MOVEMENT_SPEED, BASE_SPEED)
                .add(Attributes.FOLLOW_RANGE, 24.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SIZE, 1.0F);
        builder.define(DATA_REPLAY_STATE, (byte) 0);
        builder.define(DATA_REPLAY_TELEPORT, false);
    }

    // ---------------------------------------------------------------- 随机体型

    /** 渲染器用的整体缩放：玩家比例的基准尺寸只叠这一个随机偏移 */
    public float getRenderScale() {
        return this.sizeScale;
    }

    /**
     * 设置随机体型并重算属性
     *
     * <p>只在服务端改属性，客户端靠同步数据拿到体型；{@link #onSyncedDataUpdated} 负责刷新碰撞箱。
     * 生命、攻击与移速按同一个偏移缩放，于是「个体越大越强」在整档之内也成立
     */
    public void setSizeScale(float scale) {
        this.sizeScale = Mth.clamp(scale, MIN_SIZE, MAX_SIZE);
        if (!this.level().isClientSide) {
            this.entityData.set(DATA_SIZE, this.sizeScale);
            applySizeAttributes();
            this.setHealth(this.getMaxHealth());
        }
        this.refreshDimensions();
    }

    private void applySizeAttributes() {
        this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(BASE_HEALTH * this.sizeScale);
        this.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(BASE_ATTACK * this.sizeScale);
        this.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(BASE_SPEED * this.sizeScale);
    }

    /** 碰撞箱按玩家尺寸取 0.6 x 1.8、眼高 1.62，再乘随机体型偏移 */
    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        // 超类构造期字段仍是 0，给一个合法的兜底尺寸
        float scale = this.sizeScale <= 0.0F ? 1.0F : this.sizeScale;
        return switch (pose) {
            case CROUCHING -> EntityDimensions.scalable(0.6F * scale, 1.5F * scale)
                    .withEyeHeight(1.27F * scale);
            case SWIMMING -> EntityDimensions.scalable(0.6F * scale, 0.6F * scale)
                    .withEyeHeight(0.4F * scale);
            default -> EntityDimensions.scalable(0.6F * scale, 1.8F * scale).withEyeHeight(1.62F * scale);
        };
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_SIZE.equals(key)) {
            this.sizeScale = this.entityData.get(DATA_SIZE);
            this.refreshDimensions();
        } else if (DATA_REPLAY_TELEPORT.equals(key) && this.level().isClientSide && this.lerpSteps > 0) {
            // 实体追踪先发送位移，再发送同步数据。传送时直接应用已经收到的目标位置。
            this.absMoveTo(
                    this.lerpTargetX(),
                    this.lerpTargetY(),
                    this.lerpTargetZ(),
                    this.lerpTargetYRot(),
                    this.lerpTargetXRot());
            this.setOldPosAndRot();
            this.lerpSteps = 0;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public SpawnGroupData finalizeSpawn(
            ServerLevelAccessor level,
            DifficultyInstance difficulty,
            MobSpawnType reason,
            @Nullable SpawnGroupData spawnData) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, reason, spawnData);
        RandomSource random = level.getRandom();
        this.setSizeScale(MIN_SIZE + random.nextFloat() * (MAX_SIZE - MIN_SIZE));
        // 一群同时刷出来时不要齐声开口
        this.ambientTimer = random.nextInt(AMBIENT_PERIOD_TICKS);
        return data;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("SizeScale", this.sizeScale);
        if (this.replay != null) {
            tag.put("Replay", this.replay.save());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.replay = VirtualImageTrace.load(tag.getCompound("Replay"));
        this.replayFrame = 0;
        this.replayBlocked = false;
        if (this.replay != null) {
            this.setPersistenceRequired();
        }
        this.entityData.set(DATA_REPLAY_STATE, this.replay == null ? (byte) 0 : IDLE_REPLAY);
        float saved = tag.contains("SizeScale") ? tag.getFloat("SizeScale") : 1.0F;
        this.sizeScale = Mth.clamp(saved, MIN_SIZE, MAX_SIZE);
        if (!this.level().isClientSide) {
            this.entityData.set(DATA_SIZE, this.sizeScale);
            applySizeAttributes();
            // 存档里的当前血量不能因为重算上限被回满
            this.setHealth(Math.min(this.getHealth(), this.getMaxHealth()));
        }
        this.refreshDimensions();
    }

    // ---------------------------------------------------------------- AI

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.0D, true));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this) {
            @Override
            public boolean canUse() {
                return VirtualImageEntity.this.replay == null && super.canUse();
            }
        });
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true) {
            @Override
            public boolean canUse() {
                return VirtualImageEntity.this.replay == null && super.canUse();
            }
        });
    }

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        LivingEntity previous = this.getTarget();
        super.setTarget(target);
        LivingEntity current = this.getTarget();
        VirtualImageRecording.targetChanged(this, previous, current);
        if (this.replay != null && previous != current) {
            this.replayFrame = 0;
            this.replayBlocked = false;
            this.getNavigation().stop();
            this.setPose(Pose.STANDING);
            this.setSprinting(false);
            this.setAggressive(false);
            this.entityData.set(DATA_REPLAY_STATE, current == null ? IDLE_REPLAY : (byte) 0);
            this.retaliationUnseenTicks = 0;
        }
    }

    @Override
    public void onRemovedFromLevel() {
        VirtualImageRecording.stopTracking(this, this.getTarget());
        super.onRemovedFromLevel();
    }

    @Override
    public boolean isEffectiveAi() {
        return super.isEffectiveAi() && (this.replay == null || this.getTarget() != null);
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return this.replay == null && super.shouldDespawnInPeaceful();
    }

    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return this.replay == null && super.canUsePortal(allowPassengers);
    }

    @Override
    public boolean isIgnoringBlockTriggers() {
        return this.isReplaying() || super.isIgnoringBlockTriggers();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt
                && this.replay != null
                && this.isAlive()
                && source.getEntity() instanceof LivingEntity attacker
                && this.canRetaliateAgainst(attacker)) {
            this.setTarget(attacker);
            if (this.getTarget() == attacker) {
                this.retaliationUnseenTicks = 0;
            }
        }
        return hurt;
    }

    private boolean canRetaliateAgainst(LivingEntity target) {
        double range = this.getAttributeValue(Attributes.FOLLOW_RANGE);
        return this.level().getDifficulty() != Difficulty.PEACEFUL
                && target != this
                && target.level() == this.level()
                && this.canAttack(target)
                && !this.isAlliedTo(target)
                && this.distanceToSqr(target) <= range * range;
    }

    private void checkRetaliation() {
        LivingEntity target = this.getTarget();
        if (target != null) {
            this.retaliationUnseenTicks = this.hasLineOfSight(target) ? 0 : this.retaliationUnseenTicks + 1;
            if (!this.canRetaliateAgainst(target) || this.retaliationUnseenTicks >= RETALIATION_UNSEEN_TICKS) {
                this.setTarget(null);
            }
        }
    }

    @Override
    public void travel(Vec3 input) {
        if (this.replay != null
                && this.isAlive()
                && this.getTarget() == null
                && this.level() instanceof ServerLevel server) {
            this.advanceReplay(server, this.replay);
            return;
        }
        super.travel(input);
    }

    public boolean isReplaying() {
        return this.entityData.get(DATA_REPLAY_STATE) != 0;
    }

    @Nullable
    public InteractionHand getReplayUsedHand() {
        return switch (this.entityData.get(DATA_REPLAY_STATE)) {
            case USE_MAIN_HAND -> InteractionHand.MAIN_HAND;
            case USE_OFF_HAND -> InteractionHand.OFF_HAND;
            default -> null;
        };
    }

    void spawnReplay(ServerLevel level, VirtualImageTrace trace) {
        this.replay = trace;
        this.setPersistenceRequired();
        this.entityData.set(DATA_REPLAY_STATE, IDLE_REPLAY);
        // 优先在死亡处生成，从第一帧开始播放时再传送到起点。
        for (int index = trace.size() - 1; index >= 0; index--) {
            VirtualImageTrace.Frame frame = trace.frame(index);
            AABB box = this.getDefaultDimensions(frame.pose()).makeBoundingBox(frame.position());
            if (chunksLoaded(level, box) && this.canOccupy(level, box)) {
                this.setPose(frame.pose());
                this.moveTo(frame.position().x, frame.position().y, frame.position().z, frame.yaw(), frame.pitch());
                this.setYHeadRot(frame.yaw());
                level.addFreshEntity(this);
                return;
            }
        }
        BlockPos last = BlockPos.containing(trace.frame(trace.size() - 1).position());
        if (level.getChunkSource().getChunkNow(last.getX() >> 4, last.getZ() >> 4) != null) {
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, last);
            Vec3 position = Vec3.atBottomCenterOf(surface);
            AABB box = this.getDefaultDimensions(Pose.STANDING).makeBoundingBox(position);
            if (chunksLoaded(level, box) && this.canOccupy(level, box)) {
                this.moveTo(position);
                level.addFreshEntity(this);
            }
        }
    }

    private void advanceReplay(ServerLevel level, VirtualImageTrace trace) {
        VirtualImageTrace.Frame frame = trace.frame(this.replayFrame);
        Vec3 displacement = frame.position().subtract(this.position());
        boolean teleport = this.replayFrame == 0 || displacement.lengthSqr() > MAX_CONTINUOUS_STEP_SQUARED;
        AABB start = this.getDefaultDimensions(frame.pose()).makeBoundingBox(this.position());
        AABB destination = start.move(displacement);
        this.setDeltaMovement(Vec3.ZERO);
        this.resetFallDistance();
        this.jumping = false;
        if (!this.replayBlocked) {
            if (!chunksLoaded(level, teleport ? destination : start.expandTowards(displacement))) {
                this.calculateEntityAnimation(false);
                return;
            }
            if (this.canOccupy(level, destination)
                    && (teleport
                            || displacement.lengthSqr() < 1.0E-10D
                            || Entity.collideBoundingBox(this, displacement, start, level, List.of())
                                            .distanceToSqr(displacement)
                                    < 1.0E-10D)) {
                this.applyReplayFrame(level, frame, teleport, displacement.lengthSqr());
            } else {
                this.replayBlocked = true;
                this.setSprinting(false);
                this.entityData.set(DATA_REPLAY_STATE, IDLE_REPLAY);
            }
        }
        this.calculateEntityAnimation(false);
        if (++this.replayFrame == trace.size()) {
            this.replayFrame = 0;
            this.replayBlocked = false;
        }
    }

    private void applyReplayFrame(ServerLevel level, VirtualImageTrace.Frame frame, boolean teleport, double distance) {
        this.setPose(frame.pose());
        Vec3 destination = frame.position();
        if (teleport && distance > 1.0E-6D) {
            this.teleportReplay(level, destination);
        } else {
            this.setPos(destination.x, destination.y, destination.z);
        }
        this.setYRot(frame.yaw());
        this.setYHeadRot(frame.yaw());
        this.setXRot(frame.pitch());
        this.setOnGround(frame.onGround());
        this.setSprinting(frame.sprinting());
        InteractionHand usedHand = frame.usedHand();
        this.entityData.set(
                DATA_REPLAY_STATE,
                usedHand == null ? IDLE_REPLAY : usedHand == InteractionHand.MAIN_HAND ? USE_MAIN_HAND : USE_OFF_HAND);
        if (frame.swingMain() || frame.swingOff()) {
            this.swing(frame.swingMain() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
        }
    }

    private void teleportReplay(ServerLevel level, Vec3 destination) {
        Vec3 origin = this.position();
        this.teleportTo(destination.x, destination.y, destination.z);
        this.entityData.set(DATA_REPLAY_TELEPORT, !this.entityData.get(DATA_REPLAY_TELEPORT));
        if (!this.isSilent()) {
            level.playSound(
                    null,
                    origin.x,
                    origin.y,
                    origin.z,
                    SoundEvents.ENDERMAN_TELEPORT,
                    this.getSoundSource(),
                    1.0F,
                    1.0F);
            this.playSound(SoundEvents.ENDERMAN_TELEPORT, 1.0F, 1.0F);
        }
        for (Vec3 position : List.of(origin, destination)) {
            level.sendParticles(
                    ParticleTypes.PORTAL,
                    position.x,
                    position.y + this.getBbHeight() * 0.5D,
                    position.z,
                    16,
                    0.3D,
                    this.getBbHeight() * 0.3D,
                    0.3D,
                    0.2D);
        }
    }

    private boolean canOccupy(ServerLevel level, AABB box) {
        return box.minY >= level.getMinBuildHeight()
                && box.maxY <= level.getMaxBuildHeight()
                && level.getWorldBorder().isWithinBounds(box)
                && !level.getBlockCollisions(this, box).iterator().hasNext();
    }

    private static boolean chunksLoaded(ServerLevel level, AABB box) {
        for (int x = (Mth.floor(box.minX) - 1) >> 4; x <= (Mth.floor(box.maxX) + 1) >> 4; x++) {
            for (int z = (Mth.floor(box.minZ) - 1) >> 4; z <= (Mth.floor(box.maxZ) + 1) >> 4; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 生成

    /**
     * 自然生成的落点判定：脚下必须是霞草，其余只保留难度与地面合法性
     *
     * <p>群系表只能写「哪个群系、多少权重」，写不出「必须站在哪种方块上」；这里把方块条件补上。
     * <b>不读亮度</b>：本维度不存在原版那套「天光越亮越不刷」的固定关系，刷怪规则由
     * 位置与群系决定，这只生物白天与夜里都该出现。难度与「脚下方块能不能站」仍交给
     * {@link Monster#checkAnyLightMonsterSpawnRules}，于是和平难度下照样刷不出来
     */
    public static boolean checkVirtualImageSpawnRules(
            EntityType<VirtualImageEntity> type,
            ServerLevelAccessor level,
            MobSpawnType spawnType,
            BlockPos pos,
            RandomSource random) {
        return level.getBlockState(pos.below()).is(ModTerrainBlocks.ROSY_GRASS.get())
                && Monster.checkAnyLightMonsterSpawnRules(type, level, spawnType, pos, random);
    }

    // ---------------------------------------------------------------- 每 tick

    @Override
    public void tick() {
        if (this.replay != null && this.isAlive()) {
            this.checkRetaliation();
        }
        super.tick();
        if (this.level().isClientSide() || this.isDeadOrDying()) {
            return;
        }
        if (this.tickCount % TEAR_INTERVAL_TICKS == 0) {
            this.dripTear();
        }
        this.tickAmbientSound();
    }

    /**
     * 在身上落下一颗哭泣的黑曜石泪滴
     *
     * <p>位置在碰撞箱之内随机取，竖直方向贴着身体，粒子自己带重力往下掉，看起来就是沿着身体淌下来。
     * 只在服务端发，客户端按追踪范围收到同一条粒子包
     */
    private void dripTear() {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        RandomSource random = this.getRandom();
        double x = this.getX() + (random.nextDouble() - 0.5D) * this.getBbWidth() * 0.9D;
        double y = this.getY() + random.nextDouble() * this.getBbHeight() * 0.95D;
        double z = this.getZ() + (random.nextDouble() - 0.5D) * this.getBbWidth() * 0.9D;
        server.sendParticles(ParticleTypes.DRIPPING_OBSIDIAN_TEAR, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
    }

    // ---------------------------------------------------------------- 行走

    /**
     * 能在不稳定液体的液面上行走
     *
     * <p>打开这一个开关之后，{@code LiquidBlock#getCollisionShape} 会像对待岩浆上的炽足兽那样，
     * 给源方块一层可站立的面；寻路那边由 {@code HazardousFluidPathingMixin} 把最上面一层源方块
     * 标成 {@code PathType.WALKABLE}。与炽足兽在岩浆上的做法相同（{@code Strider#canStandOnFluid}）
     */
    @Override
    public boolean canStandOnFluid(FluidState fluid) {
        return HazardousFluid.isPortalFluid(fluid);
    }

    // ---------------------------------------------------------------- 免疫

    @SuppressWarnings("deprecation")
    @Override
    public boolean canBeAffected(MobEffectInstance effect) {
        return false;
    }

    @Override
    public void knockback(double strength, double x, double z) {
        // 免疫击退
    }

    /** 爆炸、活塞与水流直接推实体的路径也一并关掉 */
    @Override
    public void push(double x, double y, double z) {
        // 免疫击退
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean isPushedByFluid() {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return source.is(ModDamageTypes.STABILITY) || super.isInvulnerableTo(source);
    }

    // ---------------------------------------------------------------- 音效

    /**
     * 原版的待机音这里不报，改由 {@link #tickAmbientSound} 按音效长度排期
     *
     * <p>与「弦」同一条理由：原版那条路「隔一段随机间隔掷一次」，中间必有静音期；
     * 返回 {@code null} 时 {@code LivingEntity#playAmbientSound} 什么也不做
     */
    @Override
    protected SoundEvent getAmbientSound() {
        return null;
    }

    /**
     * 一条接一条地播待机音：当前这条还剩 {@link #AMBIENT_OVERLAP_TICKS} tick 时起下一条
     *
     * <p>周期是常量，因为三个变体取自同一段素材、长度一致；音高固定 1.0，变调会改变实际时长，
     * 排期就对不上了。客户端每次自己随机挑一个变体，服务端不需要知道挑了哪条
     */
    private void tickAmbientSound() {
        if (this.ambientTimer > 0) {
            this.ambientTimer--;
            return;
        }
        this.ambientTimer = AMBIENT_PERIOD_TICKS;
        this.playSound(ModSounds.STRING_AMBIENT.get(), 1.0F, 1.0F);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.STRING_HIT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.STRING_HIT.get();
    }

    /** 死亡时同样落一圈泪滴：它不是被打死的，是散了形 */
    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(
                    ParticleTypes.DRIPPING_OBSIDIAN_TEAR,
                    this.getX(),
                    this.getY() + this.getBbHeight() * 0.5D,
                    this.getZ(),
                    24,
                    0.35D,
                    0.6D,
                    0.35D,
                    0.0D);
        }
        super.die(source);
        if (this.dead) {
            VirtualImageRecording.stopTracking(this, this.getTarget());
        }
    }
}
