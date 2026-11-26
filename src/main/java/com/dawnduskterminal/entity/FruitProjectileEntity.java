package com.dawnduskterminal.entity;

import com.dawnduskterminal.registry.ModSounds;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 西瓜炮与南瓜炮射出的那颗整块果实
 *
 * <p>物理走 {@link ThrowableProjectile}：空气阻力 0.99 与碰撞判定都在父类里。重力由
 * {@link #getDefaultGravity()} 压到 {@value #GRAVITY}（雪球是 0.03），出手速度
 * {@value #SPEED}（雪球是 1.5），于是弹道比雪球更平、更快。
 *
 * <p>命中活物先结算 {@value #DIRECT_DAMAGE} 点远程伤害，再按目标身高掷一次概率：
 * 概率是 {@code 1 / 身高}，身高 2 格时正好 50%，越矮越容易触发。触发时按冲击 I 的量级
 * 把目标推开（水平 {@value #PUNCH_STRENGTH}、竖直 {@value #PUNCH_LIFT}），播放原版暴击音效与
 * 暴击粒子，炮弹随机偏转并损失速度；两者都随目标身高增加，2 格目标最多 40 度、损失 30%。
 * 没有触发时炮弹爆裂，对被命中的生物保留那 12 点伤害，再对半径 {@value #AREA_RADIUS} 格内
 * 除它以外的生物各结算 {@value #AREA_DAMAGE} 点范围伤害。
 *
 * <p>命中方块同样爆裂并结算范围伤害。方块破坏粒子按原版数量：整格方块在
 * {@code ParticleEngine#destroy} 里是 4×4×4 网格，共 64 颗；{@code dust_pillar} 回到
 * 初次引入时的 16 颗、0.5 散布、0.15 速度。方块状态不变：西瓜是西瓜方块加火珊瑚块，
 * 南瓜是南瓜方块加黄色羊毛。西瓜的音效是 {@code melon_hit1}~{@code 3} 三条随机一条、
 * 音高再随机；南瓜取南瓜方块自己的破坏声。爆裂音效按音量 4 播放，听距 64 格；
 * 暴击音效只对发射者额外发一条音量 4 的包，其余玩家仍按默认 16 格听。
 *
 * <p>偏转只保证「不比原来更向下」：先在按身高算出的锥内随机取方向（2 格目标 40 度，按身高线性放大，不另设上限），
 * 若 {@code y} 分量低于原方向就重摇，重摇多次仍不满足时改为绕垂直轴向上转。这样向上飞或平飞时
 * 不会突然折向地面，向下飞时也不会折得更陡。
 */
public class FruitProjectileEntity extends ThrowableProjectile {
    /** 命中活物的固定远程伤害 */
    public static final float DIRECT_DAMAGE = 12.0F;

    /** 爆裂时的范围伤害 */
    public static final float AREA_DAMAGE = 6.0F;

    /** 范围伤害半径，单位格 */
    public static final double AREA_RADIUS = 2.5D;

    /** 出手速度，比雪球的 1.5 稍快 */
    public static final float SPEED = 1.8F;

    /** 出手散布，与雪球相同 */
    public static final float INACCURACY = 1.0F;

    public static final double GRAVITY = 0.03D;

    /** 冲击 I 的水平推力：原版箭的冲击附魔每级 1.0，乘 0.6 后落到箭上 */
    public static final double PUNCH_STRENGTH = 0.6D;

    /** 冲击 I 的竖直抬升，与原版箭的 {@code push(..., 0.1, ...)} 相同 */
    public static final double PUNCH_LIFT = 0.1D;

    /** 2 格身高目标的偏转上限，单位度；更大的目标按身高线性放大，不另设上限 */
    public static final double DEFLECT_DEGREES_AT_TWO_BLOCKS = 40.0D;

    /** 2 格身高目标的速度损失比例；更大的目标按身高线性放大 */
    public static final double SPEED_LOSS_AT_TWO_BLOCKS = 0.3D;

    /** 偏转后从目标身边让出的距离，避免下一 tick 立刻再次命中 */
    private static final double DEFLECT_ESCAPE = 1.2D;

    /** 同一只生物在这段时间内不会被同一颗炮弹重复命中 */
    private static final int HIT_COOLDOWN_TICKS = 10;

    /** 从眼睛沿视线往前让出的距离，1 格大的炮弹不会一出来就贴住发射者 */
    private static final double SPAWN_OFFSET = 0.8D;

    /** 炮弹的碰撞箱与外观都是 1 格见方，位置取方块底面中心 */
    private static final float SIZE = 1.0F;

    /** 爆裂后在命中点停留的 tick 数，让客户端把最后一个位置渲染出来再移除 */
    private static final int BURST_LINGER_TICKS = 2;

    /** 暴击音效对发射者的听距：音量 4 对应 64 格 */
    private static final float CRIT_SOUND_SHOOTER_VOLUME = 4.0F;

    /** 爆裂音效对全体玩家的听距：音量 4 对应 64 格 */
    private static final float BURST_SOUND_VOLUME = 4.0F;

    /** 原版方块破坏：整格方块在 ParticleEngine#destroy 里是 4×4×4 网格，共 64 颗 */
    private static final int BLOCK_BREAK_PARTICLES = 64;

    /** dust_pillar 回到初次引入时的数量、散布与速度 */
    private static final int DUST_PILLAR_PARTICLES = 16;

    private static final double DUST_PILLAR_SPREAD = 0.5D;

    private static final double DUST_PILLAR_SPEED = 0.15D;

    /** 命中暴击粒子的数量 */
    private static final int CRIT_PARTICLES = 16;

    /** 身高趋于零时的除法保护 */
    private static final double MIN_HEIGHT = 1.0E-4D;

    private static final EntityDataAccessor<Float> DATA_SPIN_X =
            SynchedEntityData.defineId(FruitProjectileEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_SPIN_Y =
            SynchedEntityData.defineId(FruitProjectileEntity.class, EntityDataSerializers.FLOAT);

    private static final EntityDataAccessor<Float> DATA_SPIN_Z =
            SynchedEntityData.defineId(FruitProjectileEntity.class, EntityDataSerializers.FLOAT);

    private final FruitKind kind;

    /** 上一次命中的生物，配合 {@link #hitCooldown} 防止同一 tick 内反复结算 */
    private int lastHitEntityId;

    private int hitCooldown;

    /** 爆裂后的停留计时，只在服务端使用 */
    private int burstLinger;

    public FruitProjectileEntity(EntityType<? extends FruitProjectileEntity> type, Level level, FruitKind kind) {
        super(type, level);
        this.kind = kind;
    }

    /**
     * 由发射者打出一颗炮弹：出生在视线前方 {@value #SPAWN_OFFSET} 格、眼睛高度，
     * 三个轴的初始旋转各自随机，随后按发射者的俯仰与偏航出手
     */
    public static FruitProjectileEntity launch(
            Level level, Player shooter, FruitKind kind, EntityType<? extends FruitProjectileEntity> type) {
        FruitProjectileEntity projectile = new FruitProjectileEntity(type, level, kind);
        Vec3 look = shooter.getViewVector(1.0F);
        Vec3 origin = shooter.getEyePosition().add(look.scale(SPAWN_OFFSET));
        projectile.setPos(origin.x, origin.y - SIZE * 0.5D, origin.z);
        projectile.setOwner(shooter);
        projectile.setSpin(
                projectile.random.nextFloat() * 360.0F,
                projectile.random.nextFloat() * 360.0F,
                projectile.random.nextFloat() * 360.0F);
        projectile.shootFromRotation(shooter, shooter.getXRot(), shooter.getYRot(), 0.0F, SPEED, INACCURACY);
        projectile.hasImpulse = true;
        level.addFreshEntity(projectile);
        return projectile;
    }

    /** 这一颗是西瓜还是南瓜 */
    public FruitKind kind() {
        return this.kind;
    }

    /** 三个轴的初始旋转，渲染器照这个摆方块 */
    public float spinX() {
        return this.entityData.get(DATA_SPIN_X);
    }

    public float spinY() {
        return this.entityData.get(DATA_SPIN_Y);
    }

    public float spinZ() {
        return this.entityData.get(DATA_SPIN_Z);
    }

    private void setSpin(float x, float y, float z) {
        this.entityData.set(DATA_SPIN_X, x);
        this.entityData.set(DATA_SPIN_Y, y);
        this.entityData.set(DATA_SPIN_Z, z);
    }

    @Override
    protected double getDefaultGravity() {
        return GRAVITY;
    }

    @Override
    public void tick() {
        if (this.burstLinger > 0) {
            this.burstLinger--;
            if (this.burstLinger == 0) {
                this.discard();
            }
            return;
        }
        super.tick();
        if (!this.level().isClientSide && this.hitCooldown > 0) {
            this.hitCooldown--;
        }
    }

    /**
     * 把炮弹停在命中点并保留 {@value #BURST_LINGER_TICKS} tick 再移除
     *
     * <p>服务端在扫掠命中的同一 tick 就 {@code discard} 时，客户端往往还没把最后一个位置插值到命中点：
     * 实机看起来就是炮弹提前消失，而爆裂粒子已经在命中点出现。停两拍让位置包先到，
     * 炮弹和粒子就落在同一处；停留期间跳过父类 tick，避免在目标体内再次结算命中
     */
    private void lingerAt(Vec3 hit) {
        this.setPos(hit.x, hit.y, hit.z);
        this.setDeltaMovement(Vec3.ZERO);
        this.hasImpulse = true;
        this.burstLinger = BURST_LINGER_TICKS;
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        if (target == this.getOwner()) {
            return false;
        }
        if (this.hitCooldown > 0 && target.getId() == this.lastHitEntityId) {
            return false;
        }
        return super.canHitEntity(target);
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        if (this.level().isClientSide) {
            return;
        }
        Entity hit = result.getEntity();
        if (hit == this.getOwner()) {
            return;
        }
        if (!(hit instanceof LivingEntity target)) {
            Vec3 at = result.getLocation();
            this.burstAt(at.x, at.y, at.z, null);
            this.lingerAt(at);
            return;
        }
        if (!target.isAlive() || target.isSpectator()) {
            return;
        }
        target.hurt(this.damageSources().thrown(this, this.getOwner()), DIRECT_DAMAGE);
        if (this.random.nextDouble() < knockbackChance(target.getBbHeight())) {
            this.punch(target);
            this.playCriticalHit(target);
            this.deflect(target);
        } else {
            Vec3 at = result.getLocation();
            this.burstAt(at.x, at.y, at.z, target);
            this.lingerAt(at);
        }
    }

    /**
     * 触发高额击退的概率：身高 2 格时正好 50%，越矮越高、越高越低。
     * 身高趋于零时按 {@value #MIN_HEIGHT} 兜底，结果为 1
     */
    public static double knockbackChance(double height) {
        return Mth.clamp(1.0D / Math.max(height, MIN_HEIGHT), 0.0D, 1.0D);
    }

    /**
     * 目标身高对应的偏转上限：2 格为 {@value #DEFLECT_DEGREES_AT_TWO_BLOCKS} 度，
     * 越矮越小、越高越大，按身高线性外推，不另设上限
     */
    public static double maxDeflectionDegrees(double height) {
        return DEFLECT_DEGREES_AT_TWO_BLOCKS * Math.max(height, 0.0D) / 2.0D;
    }

    /**
     * 目标身高对应的速度损失：2 格损失 {@value #SPEED_LOSS_AT_TWO_BLOCKS}，越矮越小、越高越大，
     * 按身高线性外推；损失到 100% 为止，剩余速度最低为 0，不会反向
     */
    public static double deflectionSpeedLoss(double height) {
        double loss = SPEED_LOSS_AT_TWO_BLOCKS * Math.max(height, 0.0D) / 2.0D;
        return Math.min(loss, 1.0D);
    }

    /** 偏转后保留的速度比例：2 格目标保留七成，越大保留越少，最低 0 */
    public static double deflectionSpeedFactor(double height) {
        return 1.0D - deflectionSpeedLoss(height);
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        if (this.level().isClientSide) {
            return;
        }
        Vec3 hit = result.getLocation();
        this.burstAt(hit.x, hit.y, hit.z, null);
        this.lingerAt(hit);
    }

    /**
     * 冲击 I 的推力：水平方向沿弹道，竖直只抬 0.1，与原版箭的 {@code doKnockback} 同一个量级。
     * 目标的击退抗性照原版折掉一部分
     */
    private void punch(LivingEntity target) {
        Vec3 motion = this.getDeltaMovement();
        Vec3 direction = new Vec3(motion.x, 0.0D, motion.z);
        if (direction.lengthSqr() < 1.0E-6D) {
            // 竖直命中时没有水平弹道可循，改为从炮弹指向目标，推离炮弹
            direction = new Vec3(target.getX() - this.getX(), 0.0D, target.getZ() - this.getZ());
        }
        if (direction.lengthSqr() < 1.0E-6D) {
            direction = new Vec3(0.0D, 0.0D, 1.0D);
        }
        double resistance = 1.0D - Mth.clamp(target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.0D, 1.0D);
        Vec3 push = direction.normalize().scale(PUNCH_STRENGTH * resistance);
        target.push(push.x, PUNCH_LIFT, push.z);
        target.hurtMarked = true;
    }

    /** 触发高额击退时的那一套原版暴击演出；发射者另收一条音量 4 的声音包，听距 64 格 */
    private void playCriticalHit(LivingEntity target) {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        double x = target.getX();
        double y = target.getY() + target.getBbHeight() * 0.5D;
        double z = target.getZ();
        server.sendParticles(ParticleTypes.CRIT, x, y, z, CRIT_PARTICLES, 0.3D, 0.3D, 0.3D, 0.2D);
        ServerPlayer shooter = this.getOwner() instanceof ServerPlayer player ? player : null;
        // 其他玩家仍按默认音量 1.0（16 格）听；发射者被排除在世界音之外，单独收高音量包
        server.playSound(shooter, x, y, z, SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0F, 1.0F);
        if (shooter != null) {
            shooter.connection.send(new ClientboundSoundPacket(
                    Holder.direct(SoundEvents.PLAYER_ATTACK_CRIT),
                    SoundSource.PLAYERS,
                    x,
                    y,
                    z,
                    CRIT_SOUND_SHOOTER_VOLUME,
                    1.0F,
                    server.random.nextLong()));
        }
    }

    /**
     * 偏转：方向随机但不会比原来更向下；偏转上限与速度损失按目标身高决定，
     * 2 格目标最多 40 度、损失 30%，越大越多
     */
    private void deflect(LivingEntity target) {
        Vec3 motion = this.getDeltaMovement();
        double speed = motion.length();
        if (speed < 1.0E-6D) {
            return;
        }
        Vec3 direction = motion.normalize();
        double height = Math.max(target.getBbHeight(), 0.0D);
        double maxDegrees = maxDeflectionDegrees(height);
        double speedFactor = deflectionSpeedFactor(height);
        Vec3 deflected = this.randomDeflection(direction, maxDegrees);
        this.setDeltaMovement(deflected.scale(speed * speedFactor));
        this.hasImpulse = true;
        Vec3 escape = this.position().add(deflected.scale(DEFLECT_ESCAPE));
        this.setPos(escape.x, escape.y, escape.z);
        this.lastHitEntityId = target.getId();
        this.hitCooldown = HIT_COOLDOWN_TICKS;
    }

    /**
     * 在 {@code direction} 周围 {@code maxDegrees} 度的锥内随机取一个方向，
     * 并拒绝任何 {@code y} 分量低于原方向的候选。重摇多次仍不满足时退化为绕一条水平轴向上转，
     * 保证不会把炮弹压向地面
     */
    private Vec3 randomDeflection(Vec3 direction, double maxDegrees) {
        double maxRadians = Math.toRadians(maxDegrees);
        for (int attempt = 0; attempt < 24; attempt++) {
            Vec3 candidate = this.randomConeDirection(direction, maxRadians);
            if (candidate.y >= direction.y) {
                return candidate;
            }
        }
        Vec3 side = direction.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (side.lengthSqr() < 1.0E-6D) {
            side = direction.cross(new Vec3(1.0D, 0.0D, 0.0D));
        }
        side = side.normalize();
        Vec3 up = side.cross(direction).normalize();
        if (up.y < 0.0D) {
            up = up.scale(-1.0D);
        }
        double angle = maxRadians * 0.5D;
        return direction.scale(Math.cos(angle)).add(up.scale(Math.sin(angle))).normalize();
    }

    /** 锥内均匀取方向：极角按立体角均匀，方位角一整圈 */
    private Vec3 randomConeDirection(Vec3 axis, double maxRadians) {
        double cosMax = Math.cos(maxRadians);
        double angle = Math.acos(1.0D - this.random.nextDouble() * (1.0D - cosMax));
        double azimuth = this.random.nextDouble() * Math.PI * 2.0D;
        Vec3 side = axis.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (side.lengthSqr() < 1.0E-6D) {
            side = axis.cross(new Vec3(1.0D, 0.0D, 0.0D));
        }
        side = side.normalize();
        Vec3 up = side.cross(axis).normalize();
        double sin = Math.sin(angle);
        return axis.scale(Math.cos(angle))
                .add(side.scale(sin * Math.cos(azimuth)))
                .add(up.scale(sin * Math.sin(azimuth)))
                .normalize();
    }

    /**
     * 在当前点爆裂：按种类铺两套方块粒子、放对应的命中音效，再对半径
     * {@value #AREA_RADIUS} 格内的生物结算 {@value #AREA_DAMAGE} 点范围伤害
     *
     * @param directTarget 已经被直接命中的生物，排除在范围伤害之外；方块命中时为 {@code null}
     */
    private void burstAt(double x, double y, double z, @Nullable LivingEntity directTarget) {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        this.playBurstParticles(server, x, y, z);
        this.playBurstSound(server, x, y, z);
        Vec3 center = new Vec3(x, y, z);
        AABB search = AABB.ofSize(center, AREA_RADIUS * 2.0D, AREA_RADIUS * 2.0D, AREA_RADIUS * 2.0D);
        double rangeSqr = AREA_RADIUS * AREA_RADIUS;
        for (LivingEntity candidate : server.getEntitiesOfClass(
                LivingEntity.class,
                search,
                candidate -> candidate != directTarget
                        && candidate != this.getOwner()
                        && candidate.isAlive()
                        && !candidate.isSpectator()
                        && candidate.getBoundingBox().getCenter().distanceToSqr(center) <= rangeSqr)) {
            candidate.hurt(this.damageSources().thrown(this, this.getOwner()), AREA_DAMAGE);
        }
    }

    /** 主方块加各自的那一种副方块；每一种方块都铺 {@code dust_pillar} 与 {@code block} 两遍 */
    private void playBurstParticles(ServerLevel server, double x, double y, double z) {
        this.playBlockParticles(server, this.kind.block(), x, y, z);
        Block accent = this.kind == FruitKind.MELON ? Blocks.FIRE_CORAL_BLOCK : Blocks.YELLOW_WOOL;
        this.playBlockParticles(server, accent, x, y, z);
    }

    private void playBlockParticles(ServerLevel server, Block block, double x, double y, double z) {
        BlockState state = block.defaultBlockState();
        // 方块破坏：ParticleEngine#destroy 对整格方块铺 4×4×4，共 64 颗；速度范围 ±0.375
        this.sendBlockParticles(
                server, ParticleTypes.BLOCK, state, x, y, z, BLOCK_BREAK_PARTICLES, 0.375D, 0.375D, 0.375D, 0.375D);
        // dust_pillar：回到初次引入时的 16 颗、0.5 散布、0.15 速度
        this.sendBlockParticles(
                server,
                ParticleTypes.DUST_PILLAR,
                state,
                x,
                y,
                z,
                DUST_PILLAR_PARTICLES,
                DUST_PILLAR_SPREAD,
                DUST_PILLAR_SPREAD,
                DUST_PILLAR_SPREAD,
                DUST_PILLAR_SPEED);
    }

    private void sendBlockParticles(
            ServerLevel server,
            ParticleType<BlockParticleOption> type,
            BlockState state,
            double x,
            double y,
            double z,
            int count,
            double xDist,
            double yDist,
            double zDist,
            double speed) {
        server.sendParticles(new BlockParticleOption(type, state), x, y, z, count, xDist, yDist, zDist, speed);
    }

    private void playBurstSound(ServerLevel server, double x, double y, double z) {
        if (this.kind == FruitKind.MELON) {
            server.playSound(
                    null,
                    x,
                    y,
                    z,
                    ModSounds.MELON_HIT.get(),
                    SoundSource.PLAYERS,
                    BURST_SOUND_VOLUME,
                    Mth.nextFloat(this.random, 0.9F, 1.1F));
            return;
        }
        // 南瓜方块的 SoundType 是 WOOD，被挖掉时放的就是这一声 block.wood.break。
        // 不取 BlockState#getSoundType()：那个重载已废弃，而南瓜的材质是固定的
        server.playSound(null, x, y, z, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, BURST_SOUND_VOLUME, 1.0F);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SPIN_X, 0.0F).define(DATA_SPIN_Y, 0.0F).define(DATA_SPIN_Z, 0.0F);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        this.setSpin(compound.getFloat("SpinX"), compound.getFloat("SpinY"), compound.getFloat("SpinZ"));
        this.burstLinger = compound.getInt("BurstLinger");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putFloat("SpinX", this.spinX());
        compound.putFloat("SpinY", this.spinY());
        compound.putFloat("SpinZ", this.spinZ());
        compound.putInt("BurstLinger", this.burstLinger);
    }
}
