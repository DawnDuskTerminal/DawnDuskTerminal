package com.dawnduskterminal.entity;

import com.dawnduskterminal.registry.ModSounds;
import net.minecraft.core.BlockPos;
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
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
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
 * <p>战斗数值按种类取自 {@link FruitKind.Stats}。命中活物先结算直射伤害（西瓜炮 10 点、南瓜炮 16 点），
 * 再按目标身高掷一次概率：概率是 {@code 击退系数 / 身高}，西瓜炮的系数是 1、南瓜炮是 1.5，
 * 2 格目标因此分别有 50% 与 75% 的几率不爆裂，越矮越容易触发。触发时把目标推开
 * （水平与竖直的推力同样按种类取，南瓜炮两者都更大），播放原版暴击音效与暴击粒子，
 * 炮弹随机偏转并损失速度；两者都随目标身高增加。西瓜软，2 格目标最多偏转 40 度、损失 30%；
 * 南瓜硬，同样目标只偏转 25 度、损失 15%。没有触发时炮弹爆裂，对被命中的生物保留那份直射伤害，
 * 再对半径（西瓜炮 3.5 格、南瓜炮 2.5 格）内除它以外的生物各结算范围伤害（西瓜炮 6 点、南瓜炮 2 点）。
 *
 * <p>命中方块时南瓜炮有 {@link FruitKind.Stats#blockBounceChance()} 的概率不再爆裂，改为撞面弹开：
 * 切向速度乘 {@value #BOUNCE_SPEED_FACTOR}，法向速度取反后乘 {@value #BOUNCE_RESTITUTION}，
 * 再叠一点随机偏转，见 {@link #bounce}；
 * 速度已经低于 {@value #MIN_BOUNCE_SPEED} 格每 tick 时照常爆裂，免得弹到停下的炮弹反复命中同一块方块。
 * 西瓜炮没有这一支，一律爆裂。
 *
 * <p>模型不按固定角度摆：出手时三个轴各取一个随机角，角速度为零；此后每逢碰撞
 * （命中生物、撞上方块，爆裂与否都算）把绕 Z 轴的角速度重摇一遍，见 {@link #tumbling}，
 * 于是炮弹被撞过一次之后开始翻滚。
 * 渲染按先 X、再 Y、最后 Z 的顺序合成，X 与 Y 只是出手时定下的静态角，
 * 因此整体等价于绕世界坐标下一条固定轴匀速转动，角速度矢量在空中保持恒定；
 * 若三个轴各带一个角速度，合成出来的角速度矢量会随姿态一起转，看起来就不是刚体。
 * 转角由客户端按同步来的角速度自行积分，并在上一 tick 与本 tick 之间插值；服务端不推进它。
 *
 * <p>爆裂时方块破坏粒子按原版数量：整格方块在
 * {@code ParticleEngine#destroy} 里是 4×4×4 网格，共 64 颗；{@code dust_pillar} 回到
 * 初次引入时的 16 颗、0.5 散布、0.15 速度。方块状态不变：西瓜是西瓜方块加火珊瑚块，
 * 南瓜是南瓜方块加黄色羊毛。西瓜的音效是 {@code melon_hit1}~{@code 3} 三条随机一条、
 * 音高再随机；南瓜取南瓜方块自己的破坏声。爆裂音效按音量 4 播放，听距 64 格；
 * 暴击音效只对发射者额外发一条音量 4 的包，其余玩家仍按默认 16 格听。
 *
 * <p>偏转只保证「不比原来更向下」：先在按身高算出的锥内随机取方向（2 格目标西瓜炮 40 度、
 * 南瓜炮 25 度，按身高线性放大，不另设上限），若 {@code y} 分量低于原方向就重摇，
 * 重摇多次仍不满足时改为绕垂直轴向上转。这样向上飞或平飞时不会突然折向地面，向下飞时也不会折得更陡。
 */
public class FruitProjectileEntity extends ThrowableProjectile {
    /** 出手速度，比雪球的 1.5 稍快 */
    public static final float SPEED = 1.8F;

    /** 出手散布，与雪球相同 */
    public static final float INACCURACY = 1.0F;

    public static final double GRAVITY = 0.03D;

    /** 偏转后从目标身边让出的距离，避免下一 tick 立刻再次命中 */
    private static final double DEFLECT_ESCAPE = 1.2D;

    /** 撞方块弹开时切向速度保留的比例 */
    public static final double BOUNCE_SPEED_FACTOR = 0.7D;

    /** 撞方块弹开时法向速度保留的比例：切向按 {@value #BOUNCE_SPEED_FACTOR}，法向取反后按这个值 */
    public static final double BOUNCE_RESTITUTION = 0.3D;

    /**
     * 撞上方块时速度低于这个值就直接爆裂，不再弹。
     * 弹到快停下的炮弹会贴在地面上反复命中同一块方块，把破坏声一遍遍播出来
     */
    private static final double MIN_BOUNCE_SPEED = 0.4D;

    /** 弹开方向相对计算结果的随机抖动，按速度放大后合起来约 18 度以内 */
    private static final double BOUNCE_SPREAD = 0.15D;

    /** 弹开后从撞击面让出的余量，与碰撞箱在法向上的伸出量相加 */
    private static final double BOUNCE_CLEARANCE = 0.05D;

    /** 弹开后法向速度的最小值：抖动偶尔会把炮弹顶回方块里，这里兜底让它一定朝外走 */
    private static final double BOUNCE_MIN_OUTWARD = 0.05D;

    /** 碰撞时重摇的绕 Z 轴角速度范围，单位度每 tick，含两端 */
    private static final float MIN_SPIN_DEGREES_PER_TICK = 8.0F;

    private static final float MAX_SPIN_DEGREES_PER_TICK = 20.0F;

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

    /** 绕 Z 轴的角速度，单位度每 tick；X 与 Y 只有静态角度，不带角速度 */
    private static final EntityDataAccessor<Float> DATA_SPIN_SPEED =
            SynchedEntityData.defineId(FruitProjectileEntity.class, EntityDataSerializers.FLOAT);

    private final FruitKind kind;

    /** 客户端累计的绕 Z 轴转角，以及上一 tick 的那一份；只用于渲染插值 */
    private float spinTurnsZ;

    private float spinTurnsZOld;

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

    /**
     * 三个轴的静态角度，单位度；服务端只在出手时写一次，之后不再改动。
     *
     * <p>渲染按先 X、再 Y、最后 Z 的顺序合成为 {@code Rx · Ry · Rz}。前两个轴只给静态值，
     * 于是 {@code Rx · Ry} 是一个常量，整体等价于绕世界坐标下一条固定轴
     * {@code (Rx · Ry) · ẑ} 匀速转动，角速度矢量在空中不随时间改变
     */
    public float spinX() {
        return this.entityData.get(DATA_SPIN_X);
    }

    public float spinY() {
        return this.entityData.get(DATA_SPIN_Y);
    }

    public float spinZ() {
        return this.entityData.get(DATA_SPIN_Z);
    }

    /**
     * 渲染用的绕 Z 轴角度：出手时的末轴角度加上客户端自己累计的转角，再在上一 tick 与本 tick 之间插值。
     *
     * <p>累计只跑在客户端：角度纯属画面，服务端不推进也不同步它，客户端按同步来的角速度自己积分，
     * 因此不会出现「服务端刚同步过来的角度把本地的角度顶回去」那种每 tick 抖一次的跳变
     */
    public float spinZ(float partialTick) {
        return this.spinZ() + Mth.lerp(partialTick, this.spinTurnsZOld, this.spinTurnsZ);
    }

    /** 绕 Z 轴的角速度，单位度每 tick；只在碰撞时重摇，其余时刻保持不变 */
    public float spinSpeed() {
        return this.entityData.get(DATA_SPIN_SPEED);
    }

    private void setSpin(float x, float y, float z) {
        this.entityData.set(DATA_SPIN_X, x);
        this.entityData.set(DATA_SPIN_Y, y);
        this.entityData.set(DATA_SPIN_Z, z);
    }

    private void setSpinSpeed(float speed) {
        this.entityData.set(DATA_SPIN_SPEED, speed);
    }

    /** 客户端按同步来的角速度把绕 Z 轴的累计转角推进一 tick */
    private void advanceSpinTurns() {
        this.spinTurnsZOld = this.spinTurnsZ;
        this.spinTurnsZ += this.spinSpeed();
    }

    /**
     * 每次碰撞重摇绕 Z 轴的角速度，炮弹从此开始翻滚；幅度由 {@link #randomSpinSpeed} 给出，
     * 正负各一半。X 与 Y 的静态角度不动：它们只决定转轴朝哪儿，撞一下不该让整个模型瞬移到一个新姿态
     */
    private void tumbling() {
        this.setSpinSpeed(randomSpinSpeed(this.random));
    }

    /** 绕 Z 轴的随机角速度，单位度每 tick，落在 {@value #MIN_SPIN_DEGREES_PER_TICK} 到 {@value #MAX_SPIN_DEGREES_PER_TICK} 之间，方向随机 */
    public static float randomSpinSpeed(RandomSource random) {
        float magnitude = Mth.nextFloat(random, MIN_SPIN_DEGREES_PER_TICK, MAX_SPIN_DEGREES_PER_TICK);
        return random.nextBoolean() ? magnitude : -magnitude;
    }

    @Override
    protected double getDefaultGravity() {
        return GRAVITY;
    }

    @Override
    public void tick() {
        if (this.level().isClientSide) {
            this.advanceSpinTurns();
        }
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
        this.tumbling();
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
        target.hurt(
                this.damageSources().thrown(this, this.getOwner()),
                this.kind.stats().directDamage());
        if (this.random.nextDouble() < knockbackChance(this.kind, target.getBbHeight())) {
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
     * 触发高额击退（炮弹不爆裂）的概率：{@code 击退系数 / 身高}，西瓜炮的系数是 1、南瓜炮是 1.5，
     * 2 格目标分别为 50% 与 75%，越矮越高、越高越低。
     * 身高趋于零时按 {@value #MIN_HEIGHT} 兜底，结果为 1
     */
    public static double knockbackChance(FruitKind kind, double height) {
        return Mth.clamp(kind.stats().knockbackScale() / Math.max(height, MIN_HEIGHT), 0.0D, 1.0D);
    }

    /**
     * 目标身高对应的偏转上限：2 格为 {@link FruitKind.Stats#deflectDegreesAtTwoBlocks()}，
     * 越矮越小、越高越大，按身高线性外推，不另设上限
     */
    public static double maxDeflectionDegrees(FruitKind kind, double height) {
        return kind.stats().deflectDegreesAtTwoBlocks() * Math.max(height, 0.0D) / 2.0D;
    }

    /**
     * 偏转后保留的速度比例：2 格目标保留 {@code 1 - speedLossAtTwoBlocks}，越大保留越少，
     * 按身高线性外推；损失到 100% 为止，剩余速度最低为 0，不会反向
     */
    public static double deflectionSpeedFactor(FruitKind kind, double height) {
        double loss = kind.stats().speedLossAtTwoBlocks() * Math.max(height, 0.0D) / 2.0D;
        return 1.0D - Math.min(loss, 1.0D);
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        if (this.level().isClientSide) {
            return;
        }
        this.tumbling();
        Vec3 hit = result.getLocation();
        boolean fastEnough = this.getDeltaMovement().length() >= MIN_BOUNCE_SPEED;
        if (fastEnough && this.random.nextDouble() < this.kind.stats().blockBounceChance()) {
            this.bounce(result);
            return;
        }
        this.burstAt(hit.x, hit.y, hit.z, null);
        this.lingerAt(hit);
    }

    /**
     * 撞方块弹开：切向速度乘 {@value #BOUNCE_SPEED_FACTOR}，法向速度取反后乘
     * {@value #BOUNCE_RESTITUTION}，再叠一点与弹开后速度成正比的随机偏转，实体继续飞。
     *
     * <p>弹开后把位置挪到撞击面外面一小段：撞击点就在方块表面上，从那里起飞的下一 tick
     * 会被同一个面再判一次「撞上」，反复弹同一个面看起来就像卡在墙上。
     * 让出的距离由碰撞箱在法向上的伸出量决定，见 {@link #surfaceEscape}。
     *
     * <p>音效取被撞方块自己的破坏声，只在服务端发出
     */
    private void bounce(BlockHitResult result) {
        Vec3 normal = Vec3.atLowerCornerOf(result.getDirection().getNormal());
        Vec3 motion = this.getDeltaMovement();
        Vec3 bounced = bounceVelocity(motion, normal);
        double speed = bounced.length();
        Vec3 deflected =
                bounced.add(new Vec3(centredRandom(), centredRandom(), centredRandom()).scale(BOUNCE_SPREAD * speed));
        // 擦着撞上时法向只剩三成的那么一点，抖动有可能把它顶回方块里；兜底让它一定朝外走
        double outward = deflected.dot(normal);
        if (outward < BOUNCE_MIN_OUTWARD) {
            deflected = deflected.add(normal.scale(BOUNCE_MIN_OUTWARD - outward));
        }
        this.setDeltaMovement(deflected);
        Vec3 surface = result.getLocation().add(normal.scale(surfaceEscape(normal)));
        this.setPos(surface.x, surface.y, surface.z);
        this.hasImpulse = true;
        if (this.level() instanceof ServerLevel server) {
            this.playImpactSound(server, result.getBlockPos(), surface);
        }
    }

    /**
     * 撞面之后的速度：切向分量乘 {@value #BOUNCE_SPEED_FACTOR}，法向分量取反并乘
     * {@value #BOUNCE_RESTITUTION}。
     *
     * <p>平面镜反射是 {@code motion - 2 (motion · normal) normal}，等价于法向按 1 反弹；
     * 这里两个分量各按各的比例衰减，贴着面滑的那部分与冲进方块的那部分分开计
     */
    public static Vec3 bounceVelocity(Vec3 motion, Vec3 normal) {
        double normalSpeed = motion.dot(normal);
        Vec3 tangential = motion.subtract(normal.scale(normalSpeed)).scale(BOUNCE_SPEED_FACTOR);
        return tangential.add(normal.scale(-normalSpeed * BOUNCE_RESTITUTION));
    }

    /**
     * 弹开后沿法向让出的距离：碰撞箱在法向上伸出多远，再加 {@value #BOUNCE_CLEARANCE} 的余量。
     *
     * <p>实体位置在碰撞箱的底面中心，水平方向各伸出半个宽度，朝上的一面正好贴着位置，
     * 朝下的一面要跨过整整一格高度
     */
    private double surfaceEscape(Vec3 normal) {
        double vertical = normal.y < 0.0D ? this.getBbHeight() : 0.0D;
        double horizontal = (Math.abs(normal.x) + Math.abs(normal.z)) * this.getBbWidth() * 0.5D;
        return BOUNCE_CLEARANCE + vertical + horizontal;
    }

    /** 命中方块那一下的材质音效：破坏声，音量与音高照该方块自己的 {@code SoundType} */
    private void playImpactSound(ServerLevel server, BlockPos pos, Vec3 at) {
        BlockState state = server.getBlockState(pos);
        SoundType sound = state.getSoundType(server, pos, this);
        server.playSound(
                null, at.x, at.y, at.z, sound.getBreakSound(), SoundSource.BLOCKS, sound.getVolume(), sound.getPitch());
    }

    /** [-1, 1) 的随机数，三个轴各来一份就是「少许随机偏转」 */
    private double centredRandom() {
        return this.random.nextDouble() * 2.0D - 1.0D;
    }

    /**
     * 触发击退时的推力：水平方向沿弹道，竖直抬一截，两个分量都按种类取，
     * 南瓜炮的推力与抬升都比西瓜炮大。目标的击退抗性照原版折掉一部分
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
        FruitKind.Stats stats = this.kind.stats();
        double resistance = 1.0D - Mth.clamp(target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.0D, 1.0D);
        Vec3 push = direction.normalize().scale(stats.punchStrength() * resistance);
        target.push(push.x, stats.punchLift(), push.z);
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
     * 偏转：方向随机但不会比原来更向下；偏转上限与速度损失按种类与目标身高决定，
     * 2 格目标西瓜炮最多 40 度、损失 30%，南瓜炮最多 25 度、损失 15%，越大越多。
     * 南瓜硬，偏得少、掉得也少，因此弹开或击退之后还能继续飞一段
     */
    private void deflect(LivingEntity target) {
        Vec3 motion = this.getDeltaMovement();
        double speed = motion.length();
        if (speed < 1.0E-6D) {
            return;
        }
        Vec3 direction = motion.normalize();
        double height = Math.max(target.getBbHeight(), 0.0D);
        double maxDegrees = maxDeflectionDegrees(this.kind, height);
        double speedFactor = deflectionSpeedFactor(this.kind, height);
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
     * {@link FruitKind.Stats#areaRadius()} 格内的生物结算 {@link FruitKind.Stats#areaDamage()} 点范围伤害
     *
     * @param directTarget 已经被直接命中的生物，排除在范围伤害之外；方块命中时为 {@code null}
     */
    private void burstAt(double x, double y, double z, @Nullable LivingEntity directTarget) {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        this.playBurstParticles(server, x, y, z);
        this.playBurstSound(server, x, y, z);
        FruitKind.Stats stats = this.kind.stats();
        double radius = stats.areaRadius();
        Vec3 center = new Vec3(x, y, z);
        AABB search = AABB.ofSize(center, radius * 2.0D, radius * 2.0D, radius * 2.0D);
        double rangeSqr = radius * radius;
        for (LivingEntity candidate : server.getEntitiesOfClass(
                LivingEntity.class,
                search,
                candidate -> candidate != directTarget
                        && candidate != this.getOwner()
                        && candidate.isAlive()
                        && !candidate.isSpectator()
                        && candidate.getBoundingBox().getCenter().distanceToSqr(center) <= rangeSqr)) {
            candidate.hurt(this.damageSources().thrown(this, this.getOwner()), stats.areaDamage());
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
        builder.define(DATA_SPIN_X, 0.0F)
                .define(DATA_SPIN_Y, 0.0F)
                .define(DATA_SPIN_Z, 0.0F)
                .define(DATA_SPIN_SPEED, 0.0F);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        this.setSpin(compound.getFloat("SpinX"), compound.getFloat("SpinY"), compound.getFloat("SpinZ"));
        this.setSpinSpeed(compound.getFloat("SpinSpeed"));
        this.burstLinger = compound.getInt("BurstLinger");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        compound.putFloat("SpinX", this.spinX());
        compound.putFloat("SpinY", this.spinY());
        compound.putFloat("SpinZ", this.spinZ());
        compound.putFloat("SpinSpeed", this.spinSpeed());
        compound.putInt("BurstLinger", this.burstLinger);
    }
}
