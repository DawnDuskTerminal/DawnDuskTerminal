package com.dawnduskterminal.entity;

import com.dawnduskterminal.registry.ModSounds;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.ListIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import twilightforest.entity.monster.CarminiteGhastling;
import twilightforest.init.TFDamageTypes;
import twilightforest.init.TFEntities;
import twilightforest.init.TFParticleType;

/**
 * 「大恶魂」：暮色恶魂的精英形态
 *
 * <p>本类不实现暮色恶魂的「改变天气」与「伤害减免」两项专属机制，其余行为与该生物相似：
 * 以漂浮方式移动，按原版恶魂的节奏发射火球。在此之上另有下列两套专属机制。
 *
 * <ul>
 *   <li>常驻：每 tick 生成一颗泪粒子（{@code TFParticleType.BOSS_TEAR}），以 {@code longDistance} 方式
 *       发放给 512 格以内的玩家；每 {@value #TEAR_INTERVAL} tick 结算一次伤害，伤害类型为
 *       {@code twilightforest:ghast_tear}，每次 {@value #TEAR_DAMAGE} 点，与暮色恶魂的泪滴一致。
 *   <li>一次性：第一次取得索敌对象时，于本体正下方水平半径 {@value #MINION_SPAWN_RADIUS} 格的方形区域内
 *       依次召出 {@value #MINION_COUNT} 只 {@code twilightforest:carminite_ghastling}，并将该目标共享给它们。
 * </ul>
 *
 * <p>与「恶果」相同，火球撞击本体时不会爆炸，而是被改向：存在索敌对象时打向该对象，否则沿来路折返。
 */
public class GhastEliteEntity extends Ghast implements YsmPackUser {
    /** 最大生命值 */
    public static final float MAX_HEALTH = 250.0F;

    /** 索敌范围，单位为格 */
    public static final double FOLLOW_RANGE = 100.0D;

    /** 击杀经验：与暮色恶魂 {@code UrGhast} 的取值相同 */
    public static final int XP_REWARD = 317;

    /**
     * 标记本类召唤的小鬼
     *
     * <p>{@code mixin/GhastlingShouldAttackMixin} 依据该标记放开暮色森林的守卫规则
     * （原规则要求玩家准星对准该生物）。
     * 自然生成的砷铅铁恶魂守卫不带该标记，行为不受影响。
     */
    public static final String MINION_TAG = "dawnduskterminal:elite_minion";

    /**
     * 嘴部在模型空间中的水平前伸量
     *
     * <p>嘴部的模型空间坐标为 {@code (0, 31, -66)}（像素）。Bedrock 模型中 −Z 为正面，16 单位等于 1 格；
     * 该坐标取自几何本身：{@code thehead} 主立方体的范围为 y 0..128、z −64..64，
     * 两片脸的薄片位于 z≈−64.2 与 −64.4，故脸面位于 z=−64 单位，嘴部再向前 2 单位。
     * 换算到世界坐标为正前方 {@code 66/16 = 4.125} 格，见 {@link #MOUTH_HEIGHT}。
     */
    private static final double MOUTH_FORWARD = 66.0D / 16.0D;

    /** 嘴部相对本体脚部的竖直高度：{@code 31/16 = 1.9375} 格 */
    private static final double MOUTH_HEIGHT = 31.0D / 16.0D;

    /**
     * 拨出的火球相对本体中心的偏移距离
     *
     * <p>本体碰撞箱为 8 格见方，其半对角线约 6.93 格；取 7.5 格可保证任何方向均在碰撞箱之外，
     * 否则下一 tick 火球将再次撞击本体，形成无限反弹。
     */
    private static final double DEFLECT_ORIGIN = 7.5D;

    /** 伤害结算周期（tick）：粒子每 tick 生成一颗，伤害每 10 tick 结算一次 */
    private static final int TEAR_INTERVAL = 10;

    /** 每一次泪滴伤害的数值，与暮色恶魂一致 */
    private static final float TEAR_DAMAGE = 3.0F;

    /**
     * 泪滴判定范围在竖直方向的扩展量
     *
     * <p>以本体碰撞箱为基准向下扩展该距离，并在竖直方向放大同一距离，水平方向不放大，
     * 与暮色恶魂一致。
     */
    private static final double TEAR_DROP = 16.0D;

    /** 一次召唤的迷你恶魂数量 */
    private static final int MINION_COUNT = 6;

    /** 召唤区域：本体下方的方形区域，x、z 方向均为 ±该值。公开以供游戏测试断言该数值 */
    public static final double MINION_SPAWN_RADIUS = 3.0D;

    /** 生成高度：位于本体脚部下方该距离处，使小鬼自本体下方出现 */
    private static final double MINION_SPAWN_DROP = 2.0D;

    /** 单只小鬼随机延迟的上界（tick）：实际取值为 0 至 59，各只依次出现而非同一 tick 同时生成 */
    private static final int MINION_SPAWN_DELAY = 60;

    /**
     * 跟随 goal 的优先级
     *
     * <p>必须小于暮色森林自带两个飞行 goal 的优先级 5：在 {@code MOVE} 标志上优先级数值较小者取得控制权，
     * 若取 8 则始终无法取得控制权，表现为召唤出的小鬼不跟随本体。
     */
    private static final int MINION_FOLLOW_PRIORITY = 4;

    /** 补充分配索敌对象的周期（tick） */
    private static final int MINION_TEND_INTERVAL = 20;

    /** 小鬼环绕本体飞行时的半径，单位为格 */
    private static final double MINION_HOLD_DISTANCE = 6.0D;

    /** 照看半径：超出该距离的小鬼不再处理，交由自身的 AI 决定去向 */
    private static final double MINION_TEND_RADIUS = 48.0D;

    /** 单轮齐射的发数 */
    private static final int FIREBALL_COUNT = 3;

    /** 需要施加瞄准抖动的发数：除第一发之外的全部 */
    private static final int JITTERED_BALLS = FIREBALL_COUNT - 1;

    /** 瞄准抖动的幅度：{@code (rand - rand) * 8}，与暮色恶魂的实现一致 */
    private static final float VOLLEY_AIM_JITTER = 8.0F;

    /**
     * 面部状态，必须同步
     *
     * <p>{@code getTarget()} 只在服务端有值，客户端要判断「当前是否存在索敌对象」只能依赖同步数据；
     * 蓄力状态（{@code isCharging()}）原本就在原版恶魂的同步数据之内，此处一并折算进来，渲染器只读取这一位。
     */
    private static final EntityDataAccessor<Byte> DATA_FACE =
            SynchedEntityData.defineId(GhastEliteEntity.class, EntityDataSerializers.BYTE);

    /** 模型包 id，空串表示使用默认模型包；见 {@link YsmPackUser} */
    private static final EntityDataAccessor<String> DATA_PACK =
            SynchedEntityData.defineId(GhastEliteEntity.class, EntityDataSerializers.STRING);

    /** 模型包 id 的递增序号：客户端仅在序号变化时重新装载 */
    private static final EntityDataAccessor<Integer> DATA_PACK_SEQ =
            SynchedEntityData.defineId(GhastEliteEntity.class, EntityDataSerializers.INT);

    /** 外观开关，格式为 {@code hat=1;cloth=2} */
    private static final EntityDataAccessor<String> DATA_ROAMING =
            SynchedEntityData.defineId(GhastEliteEntity.class, EntityDataSerializers.STRING);

    /** 外观开关的递增序号 */
    private static final EntityDataAccessor<Integer> DATA_ROAMING_SEQ =
            SynchedEntityData.defineId(GhastEliteEntity.class, EntityDataSerializers.INT);

    /** 手动动作的按键名 */
    private static final EntityDataAccessor<String> DATA_ACTION =
            SynchedEntityData.defineId(GhastEliteEntity.class, EntityDataSerializers.STRING);

    /** 手动动作的递增序号 */
    private static final EntityDataAccessor<Integer> DATA_ACTION_SEQ =
            SynchedEntityData.defineId(GhastEliteEntity.class, EntityDataSerializers.INT);

    /** 平静、有索敌、蓄力中三种面部状态，其序号与 {@link #DATA_FACE} 中存储的值一致 */
    public enum Face {
        CALM,
        ANNOYED,
        ATTACK;

        /** {@link #values()} 的缓存，避免每帧读取面部状态时复制一次数组 */
        private static final Face[] VALUES = values();

        /** 由同步数据中的数值取得枚举值，越界时按 {@link #CALM} 处理 */
        public static Face of(int ordinal) {
            return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : CALM;
        }
    }

    private boolean minionsSummoned;

    /** 已经排队、尚未落地的小鬼的剩余延迟（tick） */
    private final List<Integer> pendingMinionDelays = new ArrayList<>();

    public GhastEliteEntity(EntityType<? extends GhastEliteEntity> type, Level level) {
        super(type, level);
        // 不设 noPhysics：本体的「拨开火球」依赖于在 ProjectileImpactEvent 中取消撞击，
        // 而 AbstractHurtingProjectile#canHitEntity 会直接跳过 noPhysics 的实体，拨开机制将永不触发
        this.xpReward = XP_REWARD;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Ghast.createAttributes()
                .add(Attributes.MAX_HEALTH, MAX_HEALTH)
                .add(Attributes.FOLLOW_RANGE, FOLLOW_RANGE)
                .add(Attributes.ARMOR, 10.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.9D);
    }

    /**
     * 注册 AI
     *
     * <p>此处不调用父类实现：父类注册的 {@code GhastShootFireballGoal} 与本模组的 {@link GhastShootGoal}
     * 功能重叠，且其音效写死在 {@code levelEvent} 中，无法替换。
     * 由于父类的 {@code GhastLookGoal} 随之一并缺失，本类按原实现补回 {@link EliteLookGoal}：
     * 该 goal 负责设定本体的 {@code yRot}，缺失时本体出生后不再转向。
     */
    @Override
    protected void registerGoals() {
        // 不走寻路的飞行生物无法依靠 PathNavigation 回避危险流体，见 AvoidHazardousFluidGoal
        this.goalSelector.addGoal(4, new AvoidHazardousFluidGoal(this));
        this.goalSelector.addGoal(5, new RandomFloatAroundGoal(this));
        this.goalSelector.addGoal(7, new EliteLookGoal(this));
        this.goalSelector.addGoal(
                7, new GhastShootGoal<GhastEliteEntity>(this, GhastEliteEntity::mouthPosition, this::fireVolley));
        // 常规实体索敌：范围取自 FOLLOW_RANGE（100 格），不叠加原版恶魂的 |Δy| <= 4 过滤
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return ModSounds.GHAST_UNIVERSAL_AMBIENT.get();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.GHAST_UNIVERSAL_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.GHAST_UNIVERSAL_DEATH.get();
    }

    @Override
    public void tick() {
        super.tick();
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        this.entityData.set(DATA_FACE, (byte) this.currentFace().ordinal());
        // 泪粒子每 tick 生成一颗：远距离可见性依赖 sendParticles 的 longDistance（512 格），不依赖射弹实体
        this.spawnTearParticle(level);
        if (this.tickCount % TEAR_INTERVAL == 0) {
            this.doTearDamage(level);
        }
        if (this.tickCount % MINION_TEND_INTERVAL == 0) {
            this.tendMinions();
        }
        this.spawnPendingMinions();
        if (!this.minionsSummoned && this.getTarget() != null) {
            // 只有确实排入队列，才算消耗掉这一次性技能
            this.minionsSummoned = this.summonMinions();
        }
    }

    /** 蓄力中为攻击脸，存在索敌对象为不悦脸，其余为平静 */
    public Face getFace() {
        return Face.of(this.entityData.get(DATA_FACE));
    }

    private Face currentFace() {
        if (this.isCharging()) {
            return Face.ATTACK;
        }
        return this.getTarget() == null ? Face.CALM : Face.ANNOYED;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_FACE, (byte) Face.CALM.ordinal());
        builder.define(DATA_PACK, "");
        builder.define(DATA_PACK_SEQ, 0);
        builder.define(DATA_ROAMING, "");
        builder.define(DATA_ROAMING_SEQ, 0);
        builder.define(DATA_ACTION, "");
        builder.define(DATA_ACTION_SEQ, 0);
    }

    @Override
    public void setYsmPack(String id) {
        this.entityData.set(DATA_PACK, id == null ? "" : id);
        this.entityData.set(DATA_PACK_SEQ, this.entityData.get(DATA_PACK_SEQ) + 1);
    }

    @Override
    public String ysmPackId() {
        return this.entityData.get(DATA_PACK);
    }

    @Override
    public void setRoaming(String key, double value) {
        this.entityData.set(DATA_ROAMING, YsmPackUser.mergeRoaming(this.entityData.get(DATA_ROAMING), key, value));
        this.entityData.set(DATA_ROAMING_SEQ, this.entityData.get(DATA_ROAMING_SEQ) + 1);
    }

    @Override
    public String roamingBlob() {
        return this.entityData.get(DATA_ROAMING);
    }

    @Override
    public int roamingSeq() {
        return this.entityData.get(DATA_ROAMING_SEQ);
    }

    @Override
    public void playAction(String key) {
        this.entityData.set(DATA_ACTION, key);
        this.entityData.set(DATA_ACTION_SEQ, this.entityData.get(DATA_ACTION_SEQ) + 1);
    }

    @Override
    public String actionKey() {
        return this.entityData.get(DATA_ACTION);
    }

    @Override
    public int actionSeq() {
        return this.entityData.get(DATA_ACTION_SEQ);
    }

    @Override
    public int ysmPackSeq() {
        return this.entityData.get(DATA_PACK_SEQ);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("MinionsSummoned", this.minionsSummoned);
        // 排队中的小鬼必须一并写入：否则读档之后这几只不会再出现
        int[] delays = new int[this.pendingMinionDelays.size()];
        for (int index = 0; index < delays.length; index++) {
            delays[index] = this.pendingMinionDelays.get(index);
        }
        tag.putIntArray("PendingMinionDelays", delays);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.minionsSummoned = tag.getBoolean("MinionsSummoned");
        this.pendingMinionDelays.clear();
        for (int delay : tag.getIntArray("PendingMinionDelays")) {
            this.pendingMinionDelays.add(delay);
        }
    }

    /** 交战中不按距离消失：玩家离开之后亦不会消失 */
    @Override
    public boolean requiresCustomPersistence() {
        return this.getTarget() != null;
    }

    /** 自然生成的判定：仅在出生时占用一个名额，刷怪蛋与 {@code /summon} 不占用 */
    public static boolean checkGhastEliteSpawnRules(
            EntityType<GhastEliteEntity> type,
            ServerLevelAccessor level,
            MobSpawnType spawnType,
            BlockPos pos,
            RandomSource random) {
        return spawnType != MobSpawnType.NATURAL || GhastElitePopulation.tryReserve(level.getLevel());
    }

    /**
     * 火球出膛的位置：模型嘴部所在的点，按本体当前的水平朝向放置到世界坐标中
     *
     * <p>模型空间的嘴位于 {@code (0, 31, -66)}（像素），换算为脚下 1.9375 格、正前方 4.125 格，
     * 见 {@link #MOUTH_FORWARD} 与 {@link #MOUTH_HEIGHT}。此处只取水平朝向：
     * 头骨没有俯仰通道，嘴不随视线上下移动，竖直方向固定为脚上方 1.9375 格。
     */
    public static Vec3 mouthPosition(GhastEliteEntity elite) {
        Vec3 view = elite.getViewVector(1.0F);
        Vec3 forward = new Vec3(view.x, 0.0D, view.z);
        if (forward.lengthSqr() < 1.0E-6D) {
            // 垂直向上或向下看时不存在水平朝向，固定取正 Z，避免归一化产生 NaN
            forward = new Vec3(0.0D, 0.0D, 1.0D);
        }
        forward = forward.normalize();
        return new Vec3(
                elite.getX() + forward.x * MOUTH_FORWARD,
                elite.getY() + MOUTH_HEIGHT,
                elite.getZ() + forward.z * MOUTH_FORWARD);
    }

    /**
     * 改变撞击本体的火球的飞行方向
     *
     * <p>存在索敌对象时打向该对象的眼睛，否则沿来路折返；火球的所有者改为执行改向的本体，
     * 因此后续命中均计为本体的攻击。改向实现见 {@link GhastDeflect#deflect}，并播放一声
     * {@code ghast_universal_deflect}。
     */
    public void deflectFireball(LargeFireball fireball) {
        GhastDeflect.deflect(this, fireball, DEFLECT_ORIGIN);
        GhastVoice.playVoice(this, ModSounds.GHAST_UNIVERSAL_DEFLECT.get());
    }

    /** 撞击落在大恶魂身上时取消撞击并改向，实现见 {@link GhastDeflect#handleImpact} */
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        GhastDeflect.handleImpact(event, GhastEliteEntity.class, GhastEliteEntity::deflectFireball);
    }

    /**
     * 生成一颗泪粒子
     *
     * <p>远距离可见性依赖 {@code sendParticles(player, type, longDistance = true, ...)}：
     * 该重载的分发半径为 <strong>512 格</strong>，而非普通粒子的 32 格。泪雨的远距离表现由此实现，
     * 既不需要射弹实体，也不额外生成任何对象。
     *
     * <p>生成位置与暮色恶魂的 {@code aiStep} 相同：水平方向落在身体宽度之内，竖直方向落在 0 至半高之间，
     * 粒子自行下落。
     */
    private void spawnTearParticle(ServerLevel level) {
        double x = this.getX() + (this.getRandom().nextDouble() - 0.5D) * this.getBbWidth() * 0.75D;
        double y = this.getY() + this.getRandom().nextDouble() * this.getBbHeight() * 0.5D;
        double z = this.getZ() + (this.getRandom().nextDouble() - 0.5D) * this.getBbWidth() * 0.75D;
        for (ServerPlayer player : level.players()) {
            level.sendParticles(player, TFParticleType.BOSS_TEAR.get(), true, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /**
     * 泪雨的伤害结算，与暮色恶魂的 {@code doTantrumDamageEffects} 一致
     *
     * <p>判定范围为以本体碰撞箱为基准的竖直柱体：向下扩展 {@value #TEAR_DROP} 格，竖直方向再放大
     * {@value #TEAR_DROP} 格，水平方向不放大。仅当玩家处于可见天光处时结算，每次
     * {@value #TEAR_DAMAGE} 点，伤害类型为 {@code twilightforest:ghast_tear}。
     * 该判定只针对玩家：小鬼不受影响，本体亦不会因此恢复生命。
     */
    private void doTearDamage(ServerLevel level) {
        AABB below = this.getBoundingBox().move(0.0D, -TEAR_DROP, 0.0D).inflate(0.0D, TEAR_DROP, 0.0D);
        DamageSource tear = TFDamageTypes.getEntityDamageSource(
                this.level(), TFDamageTypes.GHAST_TEAR, this, TFEntities.UR_GHAST.get());
        for (Player player : level.getEntitiesOfClass(Player.class, below)) {
            if (level.canSeeSkyFromBelowWater(player.blockPosition())) {
                player.hurt(tear, TEAR_DAMAGE);
            }
        }
    }

    /**
     * 一次性技能：将一批迷你恶魂排入队列，落地位置为本体下方水平半径 {@value #MINION_SPAWN_RADIUS} 格的方形区域
     *
     * <p>每个条目携带一段随机延迟，各只依次出现，不会在同一 tick 内同时生成。本方法只在服务端调用，
     * 调用方已保证这一点，因此不再重复判断运行环境。
     *
     * <p>落地流程与暮色恶魂的 {@code UrGhast#spawnMinionGhastsAt} 一致：{@code moveTo} →
     * {@code makeBossMinion()}（6 点生命、isMinion 标记、wanderFactor）→ {@code EventHooks.finalizeMobSpawn}
     * → {@code checkSpawnRules(MOB_SUMMONED)} → {@code addFreshEntity} → {@code spawnAnim()}。
     */
    private boolean summonMinions() {
        if (this.getTarget() == null) {
            return false;
        }
        this.pendingMinionDelays.clear();
        for (int index = 0; index < MINION_COUNT; index++) {
            // 此处只安排延迟，落点在真正生成的那一刻按本体的当前位置计算
            this.pendingMinionDelays.add(this.getRandom().nextInt(MINION_SPAWN_DELAY));
        }
        GhastVoice.playVoice(this, ModSounds.GHAST_UNIVERSAL_CHARGE.get());
        return true;
    }

    /** 每 tick 将队列中的剩余延迟减一，减至零的条目立即生成 */
    private void spawnPendingMinions() {
        if (this.pendingMinionDelays.isEmpty() || !(this.level() instanceof ServerLevel level)) {
            return;
        }
        for (ListIterator<Integer> iterator = this.pendingMinionDelays.listIterator(); iterator.hasNext(); ) {
            int delay = iterator.next() - 1;
            if (delay > 0) {
                iterator.set(delay);
                continue;
            }
            iterator.remove();
            this.spawnMinion(level);
        }
    }

    /**
     * 在本体下方水平半径 ±{@value #MINION_SPAWN_RADIUS} 格的方形区域内生成一只小鬼
     *
     * <p>落点在生成的那一刻按本体的当前位置计算，因此始终位于「当前本体」的下方。
     * 落点若与方块相交，则逐次向上抬升两格，最多尝试八次；
     * 随后挂上标记、持久标记与本类提供的跟随 goal。
     */
    private void spawnMinion(ServerLevel level) {
        CarminiteGhastling minion = TFEntities.CARMINITE_GHASTLING.get().create(level);
        if (minion == null) {
            return;
        }
        double x = this.getX() + (this.getRandom().nextDouble() * 2.0D - 1.0D) * MINION_SPAWN_RADIUS;
        double z = this.getZ() + (this.getRandom().nextDouble() * 2.0D - 1.0D) * MINION_SPAWN_RADIUS;
        double y = this.getY() - MINION_SPAWN_DROP;
        minion.moveTo(x, y, z, this.getRandom().nextFloat() * 360.0F, 0.0F);
        // 避免将小鬼生成在树干或地面之内：被方块卡住的小鬼会在数 tick 内窒息死亡
        for (int attempt = 0; attempt < 8 && !level.noCollision(minion); attempt++) {
            y += 2.0D;
            minion.moveTo(x, y, z, minion.getYRot(), 0.0F);
        }
        minion.makeBossMinion();
        EventHooks.finalizeMobSpawn(
                minion, level, level.getCurrentDifficultyAt(minion.blockPosition()), MobSpawnType.MOB_SUMMONED, null);
        if (!minion.checkSpawnRules(level, MobSpawnType.MOB_SUMMONED)) {
            return;
        }
        LivingEntity target = this.getTarget();
        if (target != null && target.isAlive()) {
            minion.setTarget(target);
        }
        minion.getPersistentData().putBoolean(MINION_TAG, true);
        // 本体存活期间小鬼不得自行消失：玩家拉开距离也无法摆脱它们
        minion.setPersistenceRequired();
        minion.goalSelector.addGoal(MINION_FOLLOW_PRIORITY, new MinionFollowGoal(minion, this));
        level.addFreshEntity(minion);
        minion.spawnAnim();
    }

    /**
     * 照看本体召唤的小鬼：仅补充分配索敌对象，移动交由每只小鬼自己的 {@link MinionFollowGoal} 负责
     *
     * <p>暮色森林的 {@code NearestAttackableTargetGoal} 在失去视线时会将其 {@code target} 置为 {@code null}，
     * 因此需要周期性地重新分配。
     */
    private void tendMinions() {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        LivingEntity target = this.getTarget();
        for (CarminiteGhastling minion : level.getEntitiesOfClass(
                CarminiteGhastling.class, this.getBoundingBox().inflate(MINION_TEND_RADIUS))) {
            if (minion.getTarget() == null && target != null && target.isAlive() && this.canAttack(target)) {
                minion.setTarget(target);
            }
        }
    }

    /**
     * 小鬼跟随本体：每 tick 将移动目标设为本体周围的一个环绕点
     *
     * <p>优先级 {@value #MINION_FOLLOW_PRIORITY} 必须小于暮色森林自带的两个飞行 goal 的优先级 5，
     * 否则在 {@code MOVE} 标志上永远无法取得控制权，表现为召唤出的小鬼不跟随本体。
     *
     * <p>本体死亡之后 {@link #canUse()} 返回 {@code false}，控制权交还给小鬼自身的飞行 goal，
     * 并在 {@link #stop()} 中将其解散：被召唤的小鬼带有持久标记，若不在此处解散，将永久滞留于该区域。
     */
    private static final class MinionFollowGoal extends Goal {
        private final Mob minion;
        private final GhastEliteEntity elite;

        private MinionFollowGoal(Mob minion, GhastEliteEntity elite) {
            this.minion = minion;
            this.elite = elite;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return this.elite.isAlive() && this.elite.level() == this.minion.level();
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        @Override
        public void tick() {
            Vec3 center = this.elite.getBoundingBox().getCenter();
            double angle = this.minion.tickCount * 0.06D + this.minion.getId() * 1.7D;
            double x = center.x + Math.cos(angle) * MINION_HOLD_DISTANCE;
            double z = center.z + Math.sin(angle) * MINION_HOLD_DISTANCE;
            double y = center.y + Math.sin(angle * 0.8D) * 2.0D;
            this.minion.getMoveControl().setWantedPosition(x, y, z, 1.4D);
        }

        @Override
        public void stop() {
            // 本体已不存在时，小鬼随之解散；本体仍然存活时只是交出移动控制权，不做处理
            if (!this.elite.isAlive()) {
                this.minion.discard();
            }
        }
    }

    /**
     * 一轮三发，摆法与暮色森林的 {@code UrGhastAttackGoal#spitFireball} 一致
     *
     * <p>三发<strong>在同一 tick 内</strong>出膛，出膛点同为 {@link #mouthPosition} 返回的那一点，
     * 因此三发等高。第一发直接瞄准目标碰撞箱的中心；其余两发仅对瞄准向量的 x、z 分量施加
     * {@code (rand - rand) * 8} 的抖动，竖直分量保持不变。
     *
     * <p>瞄准向量自<strong>嘴部</strong>起算，而非自本体中心起算。
     */
    private void fireVolley(LivingEntity target, Vec3 muzzle) {
        Level level = this.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        double targetY = target.getBoundingBox().minY + target.getBbHeight() / 2.0D;
        double dx = target.getX() - muzzle.x;
        double dy = targetY - muzzle.y;
        double dz = target.getZ() - muzzle.z;

        GhastEliteFireball first = new GhastEliteFireball(level, this, new Vec3(dx, dy, dz));
        first.setPos(muzzle.x, muzzle.y, muzzle.z);
        serverLevel.addFreshEntity(first);

        for (int index = 0; index < JITTERED_BALLS; index++) {
            double jitterX = (this.getRandom().nextFloat() - this.getRandom().nextFloat()) * VOLLEY_AIM_JITTER;
            double jitterZ = (this.getRandom().nextFloat() - this.getRandom().nextFloat()) * VOLLEY_AIM_JITTER;
            GhastEliteFireball extra = new GhastEliteFireball(level, this, new Vec3(dx + jitterX, dy, dz + jitterZ));
            extra.setPos(muzzle.x, muzzle.y, muzzle.z);
            serverLevel.addFreshEntity(extra);
        }
    }

    /**
     * 原版 {@code Ghast.GhastLookGoal} 的等价实现
     *
     * <p>原版该类为 {@code Ghast} 的包私有内部类，本模组无法直接引用，故按原实现重写。
     * 该 goal 负责设定本体的 {@code yRot}：不存在索敌对象时朝向飞行方向；存在索敌对象且距离在 64 格以内时朝向该对象。
     * 缺少该 goal 时，本体在出生之后不再转向，{@link #mouthPosition} 的水平朝向与火球的出膛朝向都将固定为出生时的取值。
     */
    private static final class EliteLookGoal extends Goal {
        /** 64 格的平方：超出该距离后不再朝向索敌对象 */
        private static final double LOOK_RANGE_SQR = 4096.0D;

        private static final float DEGREES_PER_RADIAN = 180.0F / (float) Math.PI;

        private final GhastEliteEntity ghast;

        private EliteLookGoal(GhastEliteEntity ghast) {
            this.ghast = ghast;
            this.setFlags(EnumSet.of(Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return true;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity target = this.ghast.getTarget();
            if (target == null) {
                Vec3 movement = this.ghast.getDeltaMovement();
                this.ghast.setYRot(-((float) Mth.atan2(movement.x, movement.z)) * DEGREES_PER_RADIAN);
            } else if (target.distanceToSqr(this.ghast) < LOOK_RANGE_SQR) {
                double dx = target.getX() - this.ghast.getX();
                double dz = target.getZ() - this.ghast.getZ();
                this.ghast.setYRot(-((float) Mth.atan2(dx, dz)) * DEGREES_PER_RADIAN);
            } else {
                // 超出范围时不改变朝向，与原版一致
                return;
            }
            this.ghast.yBodyRot = this.ghast.getYRot();
        }
    }

    /**
     * 原版 {@code Ghast.RandomFloatAroundGoal} 的等价实现：以 16 格范围内的一个随机点作为下一段航线的终点
     *
     * <p>移动控制器沿用父类构造函数装入的原版 {@code Ghast.GhastMoveControl}。该控制器会对整条直线做碰撞预检，
     * 而本体的碰撞箱为 8 格见方，在树林中往往找不到一条十余格无阻的直线；此时控制器会放弃当前目标点，
     * 本体随之停住。为此本实现补充进度判定：目标距离连续 {@value #WAYPOINT_TIMEOUT} tick 未见缩短时，
     * 视为该点不可达并重新选点。
     */
    private static final class RandomFloatAroundGoal extends Goal {
        /** 连续多少个 tick 未能接近目标点即放弃该点：目标点位于地下时不得持续推动本体 */
        private static final int WAYPOINT_TIMEOUT = 30;

        private final GhastEliteEntity ghast;
        private double lastDistance = Double.MAX_VALUE;
        private int stuckTicks;

        private RandomFloatAroundGoal(GhastEliteEntity ghast) {
            this.ghast = ghast;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            MoveControl control = this.ghast.getMoveControl();
            if (!control.hasWanted()) {
                return true;
            }
            double x = control.getWantedX() - this.ghast.getX();
            double y = control.getWantedY() - this.ghast.getY();
            double z = control.getWantedZ() - this.ghast.getZ();
            double distance = x * x + y * y + z * z;
            if (distance < 1.0D || distance > 3600.0D) {
                return true;
            }
            if (distance < this.lastDistance - 0.05D) {
                this.lastDistance = distance;
                this.stuckTicks = 0;
                return false;
            }
            this.lastDistance = distance;
            return ++this.stuckTicks > WAYPOINT_TIMEOUT;
        }

        @Override
        public boolean canContinueToUse() {
            return false;
        }

        @Override
        public void start() {
            RandomSource random = this.ghast.getRandom();
            double x = this.ghast.getX() + (random.nextFloat() * 2.0F - 1.0F) * 16.0F;
            double y = this.ghast.getY() + (random.nextFloat() * 2.0F - 1.0F) * 16.0F;
            double z = this.ghast.getZ() + (random.nextFloat() * 2.0F - 1.0F) * 16.0F;
            this.ghast.getMoveControl().setWantedPosition(x, y, z, 1.0D);
            this.lastDistance = Double.MAX_VALUE;
            this.stuckTicks = 0;
        }
    }
}
