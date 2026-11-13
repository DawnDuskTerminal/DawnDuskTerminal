package com.dawnduskterminal.world;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidInteractionRegistry;
import twilightforest.init.TFSounds;

/**
 * 转换粉落地那一下：音效、粒子、实体、方块与群系
 *
 * <p>一瓶粉落地时做五件事，半径都是 {@value TransformationConversion#RADIUS} 格、形状都是球：
 *
 * <ol>
 *   <li>响一声转换粉命中那一带的声音（{@link TFSounds#POWDER_USE}，暮色森林自己那瓶转换粉用的就是它），
 *       两瓶一样
 *   <li>撒粒子：强效那瓶是 {@code glow} 加 {@code totem_of_undying}，反转那瓶是 {@code witch}；
 *       摊开的两种拆成小喷发，另外从落点往每个小喷发点拉一条线（强效 {@code enchant}、反转
 *       哭泣的黑曜石的滴落），见 {@link #spawnParticles(ServerLevel, Vec3, boolean)}
 *   <li>球内的活物换成另一侧的对应物（{@link TransformationConversion#toOverworldEntity} /
 *       {@link TransformationConversion#toTwilightEntity}），没有对应物的不动
 *   <li>球内的方块换成另一侧的对应物（{@link TransformationConversion#toOverworldBlock} /
 *       {@link TransformationConversion#toChronoBlock}），同名的属性照抄；
 *       整批写入期间 {@link FluidReactionGate} 挂着，流体反应只在球壳上补判一次
 *   <li>球内的群系换成另一侧的对应物（{@link TransformationConversion#toOverworldBiome} /
 *       {@link TransformationConversion#toChronoBiome}）
 * </ol>
 *
 * <p>群系写进区块走的是原版 {@code /fillbiome} 那条路（{@code ChunkAccess#fillBiomesFromNoise} 加
 * {@code BiomeResolver}），与试验物品 055 刷群系的做法是同一套：每个生物群系单元（4×4×4 一格）问一次，
 * 单元中心落在球内才换，改完把动过的区块统一 {@code resendBiomesForChunks} 同步给客户端。
 * 只动已经载入的区块（{@code ChunkStatus.FULL} 取不到就跳过），一次转换不值得为此生成新地形。
 */
public final class TransformationBlast {
    /**
     * 辉光与女巫拆成几次小喷发
     *
     * <p>这两种粒子的 provider 会把 packet 里的水平速度整个丢掉（见下面的常量注释），粒子不会自己往外
     * 走，所以覆盖范围只能靠**位置**：一次 {@code sendParticles} 只能围着同一个点按高斯撒，
     * 撒出来是一小团；拆成若干次、每次取一个点，合起来才铺开
     */
    public static final int SUB_BURSTS = 8;

    /**
     * 小喷发取点用的半径，是作用半径的几分之几
     *
     * <p>粒子从取到的点出发后还会各漂 1~3 格（辉光与女巫的水平漂移是它们自己给的 ±0.5），
     * 取点若是摊到整个半径，合成出来的观感就比作用球还大一圈。所以取点收在半径的一半，
     * 漂出去之后仍落在球内
     */
    public static final double SUB_BURST_REACH_RATIO = 0.8D;

    /** 强效那瓶撒几颗辉光、几颗图腾 */
    private static final int GLOW_COUNT = 180;

    private static final int TOTEM_COUNT = 120;

    /** 反转那瓶撒几颗女巫粒子 */
    private static final int WITCH_COUNT = 240;

    /** 连线取点的间隔：照项目里已有的那条线（{@code ExpowheelEntity#noteConeTarget}）的 0.3 格 */
    private static final double LINE_STEP = 0.1D;

    /** 一条线至少几颗、最多几颗：短到贴脸也看得见是条线，长到两格半也不至于几百包 */
    private static final int LINE_MIN_POINTS = 6;

    private static final int LINE_MAX_POINTS = 24;

    /**
     * 三种粒子对 {@code sendParticles} 那几个字段的接受程度完全不一样，参数只能按各自的 provider 给
     *
     * <p>原版 {@code ClientboundLevelParticlesPacket} 在 {@code count > 0} 时的口径是：
     * 位置 = 落点 + 高斯 × (xDist, yDist, zDist)，速度 = 高斯 × speed（三个轴各一个）。
     * 但**真正决定一颗粒子怎么动的是它的 provider**，三种 provider 是三套：
     *
     * <ul>
     *   <li>{@code TOTEM_OF_UNDYING} → {@code TotemParticle.Provider}：{@code xd/yd/zd} 原样收下，
     *       摩擦 0.6、{@code SimpleAnimatedParticle} 的重力参数 1.25（往下拉）、寿命 60~72 tick。
     *       所以这一种的速度是真的速度：给 1.0 就是原版 {@code TrackingEmitter} 那个量级。
     *       它本来就该在落点上炸开、自己飞出去，所以**不拆小喷发**，单发落在落点
     *   <li>{@code GLOW} → {@code GlowParticle.GlowSquidProvider}：**水平的两个分量被丢掉**，
     *       自己用 {@code 0.5 - random} 重新给 ±0.5；只有竖直分量被收下并乘 0.2；
     *       摩擦 0.96、无碰撞、寿命 8~40 tick
     *   <li>{@code WITCH} → {@code SpellParticle.Provider}：与辉光同一套——水平分量丢掉、自己 ±0.5，
     *       竖直收下乘 0.2；另外重力参数是 −0.1（往上飘）、摩擦 0.96
     * </ul>
     *
     * <p>所以：{@code speed} 对辉光与女巫只等于「一开始竖直冲多快」（最终 {@code speed × 0.2}），
     * 水平方向无从控制
     */
    private static final double GLOW_SPREAD = 0.4D;

    /** 辉光：竖直 {@code 1.0 × 0.2 = 0.2} 格每 tick，配它自己的 ±0.5 水平漂移 */
    private static final double GLOW_SPEED = 1.0D;

    /** 图腾位置贴脸：原版 {@code TrackingEmitter} 就是 ±0.25 格，速度自己带出去 */
    private static final double TOTEM_SPREAD = 0.25D;

    private static final double TOTEM_SPEED = 1.0D;

    private static final double WITCH_SPREAD = 0.3D;

    /** 女巫：竖直 {@code 0.5 × 0.2 = 0.1} 格每 tick；它自己的重力 −0.1 会接着把它往上带 */
    private static final double WITCH_SPEED = 0.5D;

    private TransformationBlast() {}

    /** 落地：{@code reversed} 为 true 表示这瓶是逆转转换粉 */
    public static void detonate(ServerLevel level, Vec3 centre, boolean reversed) {
        // 响的是暮色森林转换粉的那一声（经僵尸村民治愈音转发），与它自己 {@code EntityUtil} 里
        // {@code playSound(player, pos, TFSounds.POWDER_USE, source)} 那条同一个音效、同一档音量与音高
        level.playSound(null, centre.x, centre.y, centre.z, TFSounds.POWDER_USE.get(), SoundSource.NEUTRAL, 1.0F, 1.0F);
        spawnParticles(level, centre, reversed);
        convertEntities(level, centre, reversed);
        convertBlocks(level, centre, reversed);
        convertBiomes(level, centre, reversed);
    }

    /**
     * 这一瓶落地撒哪几把粒子
     *
     * <p>拆成一个可查询的方法是为了能被测试钉住：粒子只往客户端发包，服务端看不见，但
     * 「哪一种粒子、几颗、每次小喷发放开多少、竖直冲量多大、要不要摊到球里」是纯数据
     */
    public static List<ParticleBurst> particleBursts(boolean reversed) {
        if (reversed) {
            return List.of(new ParticleBurst(ParticleTypes.WITCH, WITCH_COUNT, WITCH_SPREAD, WITCH_SPEED, true));
        }
        return List.of(
                new ParticleBurst(ParticleTypes.GLOW, GLOW_COUNT, GLOW_SPREAD, GLOW_SPEED, true),
                new ParticleBurst(ParticleTypes.TOTEM_OF_UNDYING, TOTEM_COUNT, TOTEM_SPREAD, TOTEM_SPEED, false));
    }

    /**
     * 连线用哪一种粒子
     *
     * <p>强效用 {@code enchant}、反转用哭泣的黑曜石的滴落。两种都是「挂在线上的光点/水滴」，
     * 摆法照 {@link #drawLine} ——沿线取点、每点一颗、三个偏移给 0
     */
    public static ParticleOptions lineParticle(boolean reversed) {
        return reversed ? ParticleTypes.DRIPPING_OBSIDIAN_TEAR : ParticleTypes.ENCHANT;
    }

    /**
     * 一把粒子：哪一种、几颗、每次小喷发的位置散布多少、速度给多少、要不要摊到球里
     *
     * <p>{@code distributed} 为真表示这颗粒子要拆成 {@link #SUB_BURSTS} 次小喷发、位置摊在球里；
     * 为假表示单发落在落点（图腾就是这样，它自己会飞出去）
     */
    public record ParticleBurst(ParticleOptions type, int count, double spread, double speed, boolean distributed) {}

    /**
     * 撒粒子：本体、连线两件事
     *
     * <ol>
     *   <li>本体：摊开的那两种（辉光、女巫）拆成 {@link #SUB_BURSTS} 次小喷发，取点在
     *       {@code 半径 × SUB_BURST_REACH_RATIO} 以内，漂出去刚好铺满作用球；图腾单发落在落点
     *   <li>连线：从落点拉一条到每个小喷发点的线。强效用 {@code enchant}，反转用哭泣的黑曜石的滴落
     * </ol>
     */
    private static void spawnParticles(ServerLevel level, Vec3 centre, boolean reversed) {
        RandomSource random = level.getRandom();
        double reach = TransformationConversion.RADIUS * SUB_BURST_REACH_RATIO;
        List<Vec3> points = new ArrayList<>(SUB_BURSTS);
        for (int i = 0; i < SUB_BURSTS; i++) {
            points.add(randomPointInSphere(random, centre, reach));
        }
        for (ParticleBurst burst : particleBursts(reversed)) {
            if (!burst.distributed()) {
                sendBurst(level, burst, centre, burst.count());
                continue;
            }
            int perBurst = Math.max(1, burst.count() / SUB_BURSTS);
            for (Vec3 point : points) {
                sendBurst(level, burst, point, perBurst);
            }
        }
        for (Vec3 point : points) {
            drawLine(level, centre, point, reversed);
        }
    }

    private static void sendBurst(ServerLevel level, ParticleBurst burst, Vec3 at, int count) {
        level.sendParticles(
                burst.type(), at.x, at.y, at.z, count, burst.spread(), burst.spread(), burst.spread(), burst.speed());
    }

    /**
     * 从落点到小喷发点的那条线
     *
     * <p>照项目里已有的那条连线的写法（{@code ExpowheelEntity#noteConeTarget}）：沿线每隔
     * {@value #LINE_STEP} 格取一点，每点一颗、{@code count = 1}，三个偏移全给 0。
     *
     * <p>为什么三轴必须是 0：{@code ParticleTypes.ENCHANT} 的 provider 是
     * {@code FlyTowardsPositionParticle}，它把 {@code sendParticles} 的那三个「速度」当**落点偏移**用，
     * 非 0 的粒子会从偏移处往回飘，线就散了；给 0 它才正好落在取样点上，再靠自己寿命里下沉的 1.2 格
     * 慢慢飘落（见 {@code FlyTowardsPositionParticle#tick}）。哭泣的黑曜石的滴落同理：它挂着、然后往下滴
     */
    private static void drawLine(ServerLevel level, Vec3 centre, Vec3 point, boolean reversed) {
        ParticleOptions particle = lineParticle(reversed);
        int points =
                Mth.clamp((int) Math.round(centre.distanceTo(point) / LINE_STEP), LINE_MIN_POINTS, LINE_MAX_POINTS);
        for (int index = 0; index <= points; index++) {
            Vec3 at = centre.lerp(point, (double) index / (double) points);
            level.sendParticles(particle, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /** 球内均匀取一点：{@code r = R · ∛U}，方向在单位球面上均匀 */
    private static Vec3 randomPointInSphere(RandomSource random, Vec3 centre, double radius) {
        double distance = radius * Math.cbrt(random.nextDouble());
        double cosPhi = random.nextDouble() * 2.0D - 1.0D;
        double sinPhi = Math.sqrt(Math.max(0.0D, 1.0D - cosPhi * cosPhi));
        double theta = random.nextDouble() * Math.PI * 2.0D;
        return centre.add(distance * sinPhi * Math.cos(theta), distance * cosPhi, distance * sinPhi * Math.sin(theta));
    }

    /**
     * 球内的活物换成另一侧
     *
     * <p>先把候选收成一张表再替换：{@code level.getEntities} 的返回值是快照，但替换会 discard 旧实体，
     * 边遍历边改容易把同一只换两次。谁会被换完全由数据图决定——表外的实体（玩家、拴绳上的船、
     * 打出去的各种飞行物都在表外）没有对应物，因此天然不受影响，这里不额外挑类型；
     * 替身建不出来（原版 {@code EntityType#create} 可能给 null）就放弃这一只
     */
    private static void convertEntities(ServerLevel level, Vec3 centre, boolean reversed) {
        AABB box = new AABB(centre, centre).inflate(TransformationConversion.RADIUS);
        List<Entity> candidates = level.getEntities((Entity) null, box, Entity::isAlive);
        for (Entity entity : candidates) {
            if (entity.distanceToSqr(centre) > TransformationConversion.RADIUS * TransformationConversion.RADIUS) {
                continue;
            }
            EntityType<?> target = reversed
                    ? TransformationConversion.toTwilightEntity(entity.getType())
                    : TransformationConversion.toOverworldEntity(entity.getType());
            if (target == null) {
                continue;
            }
            replace(level, entity, target);
        }
    }

    /** 照着一只旧实体摆一只新实体：位置、朝向、动量与自定义名都带过去 */
    private static void replace(ServerLevel level, Entity from, EntityType<?> target) {
        Entity created = target.create(level);
        if (created == null) {
            return;
        }
        created.moveTo(from.getX(), from.getY(), from.getZ(), from.getYRot(), from.getXRot());
        created.setDeltaMovement(from.getDeltaMovement());
        if (from.hasCustomName()) {
            created.setCustomName(from.getCustomName());
        }
        level.addFreshEntity(created);
        from.discard();
    }

    /**
     * 球内的方块换成另一侧；表外的方块原样留着
     *
     * <p>易碎的苍穹另走一条：它不是调色板里的一格，而是「不稳定液体 + 水」的流体反应产物，
     * 所以没有「另一侧的对应物」可换，只能拆回原料——强效那瓶拆成水，反转那瓶拆成不稳定液体
     * （见 {@link TransformationConversion#fragileCanopyMelt(boolean)}）
     *
     * <p>整批写入外面套着 {@link FluidReactionGate}：换液体的那一瞬间「新流体挨着旧流体」不算数，
     * 所以球内不会当场反应。写完之后只对**球壳**补一次判定——把球内边界那一格与它外侧那一格都交回
     * {@link FluidInteractionRegistry#canInteract}，于是范围外的液体照常与边界那一格自发反应，
     * 球心那一片则一动不动。
     *
     * <p>不套闸门的话，换一格水就当场变一次「脆弱的苍穹」，一整球液体最后只剩一层反应产物
     */
    private static void convertBlocks(ServerLevel level, Vec3 centre, boolean reversed) {
        int radius = Mth.ceil(TransformationConversion.RADIUS);
        BlockPos origin = BlockPos.containing(centre);
        double radiusSqr = TransformationConversion.RADIUS * TransformationConversion.RADIUS;
        List<BlockPos> changed = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    cursor.setWithOffset(origin, dx, dy, dz);
                    if (origin.distSqr(cursor) > radiusSqr) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    BlockState mapped = mappedBlock(state, reversed);
                    if (mapped == null || mapped == state) {
                        continue;
                    }
                    changed.add(cursor.immutable());
                    states.add(mapped);
                }
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        FluidReactionGate.suppress(() -> {
            for (int i = 0; i < changed.size(); i++) {
                level.setBlockAndUpdate(changed.get(i), states.get(i));
            }
        });

        // 球壳：球内那一格有邻居落在球外，边界就在它们之间
        Set<BlockPos> shell = new LinkedHashSet<>();
        for (BlockPos pos : changed) {
            for (Direction direction : Direction.values()) {
                BlockPos outside = pos.relative(direction);
                if (origin.distSqr(outside) > radiusSqr) {
                    shell.add(pos);
                    shell.add(outside);
                }
            }
        }
        for (BlockPos pos : shell) {
            if (!level.getFluidState(pos).isEmpty()) {
                FluidInteractionRegistry.canInteract(level, pos);
            }
        }
    }

    /**
     * 一格方块在这次转换里换成什么
     *
     * <p>两条支路：易碎的苍穹拆回原料（{@link TransformationConversion#fragileCanopyMelt(boolean)}），
     * 其余照对照表换；两边都没接上的返 {@code null}
     */
    private static BlockState mappedBlock(BlockState state, boolean reversed) {
        if (TransformationConversion.isFragileCanopy(state)) {
            return TransformationConversion.fragileCanopyMelt(reversed);
        }
        return reversed
                ? TransformationConversion.toChronoBlock(state)
                : TransformationConversion.toOverworldBlock(state);
    }

    /** 球内的群系换成另一侧；表外的群系原样留着 */
    private static void convertBiomes(ServerLevel level, Vec3 centre, boolean reversed) {
        Registry<Biome> registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        double radiusSqr = TransformationConversion.RADIUS * TransformationConversion.RADIUS;
        int minChunkX = SectionPos.blockToSectionCoord(Mth.floor(centre.x - TransformationConversion.RADIUS));
        int maxChunkX = SectionPos.blockToSectionCoord(Mth.floor(centre.x + TransformationConversion.RADIUS));
        int minChunkZ = SectionPos.blockToSectionCoord(Mth.floor(centre.z - TransformationConversion.RADIUS));
        int maxChunkZ = SectionPos.blockToSectionCoord(Mth.floor(centre.z + TransformationConversion.RADIUS));
        List<ChunkAccess> touched = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                boolean[] changed = {false};
                chunk.fillBiomesFromNoise(
                        (quartX, quartY, quartZ, sampler) -> {
                            Holder<Biome> current = chunk.getNoiseBiome(quartX, quartY, quartZ);
                            // 单元中心（+2）落在球内才换：三个轴用同一个半径
                            double dx = QuartPos.toBlock(quartX) + 2 - centre.x;
                            double dy = QuartPos.toBlock(quartY) + 2 - centre.y;
                            double dz = QuartPos.toBlock(quartZ) + 2 - centre.z;
                            if (dx * dx + dy * dy + dz * dz > radiusSqr) {
                                return current;
                            }
                            Optional<ResourceKey<Biome>> currentKey = current.unwrapKey();
                            if (currentKey.isEmpty()) {
                                return current;
                            }
                            ResourceKey<Biome> mapped = reversed
                                    ? TransformationConversion.toChronoBiome(currentKey.get(), registry)
                                    : TransformationConversion.toOverworldBiome(currentKey.get(), registry);
                            if (mapped == null) {
                                return current;
                            }
                            Optional<Holder.Reference<Biome>> target = registry.getHolder(mapped);
                            if (target.isEmpty()) {
                                return current;
                            }
                            changed[0] = true;
                            return target.get();
                        },
                        level.getChunkSource().randomState().sampler());
                if (changed[0]) {
                    chunk.setUnsaved(true);
                    touched.add(chunk);
                }
            }
        }
        if (!touched.isEmpty()) {
            level.getChunkSource().chunkMap.resendBiomesForChunks(touched);
        }
    }
}
