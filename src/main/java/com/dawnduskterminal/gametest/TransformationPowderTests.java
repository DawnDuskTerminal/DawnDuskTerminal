package com.dawnduskterminal.gametest;

import com.dawnduskterminal.entity.TransformationPowderEntity;
import com.dawnduskterminal.item.TransformationPowderItem;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModChronoOres;
import com.dawnduskterminal.registry.ModEntities;
import com.dawnduskterminal.registry.ModItems;
import com.dawnduskterminal.registry.ModScorchingTwilight;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.dawnduskterminal.world.TransformationBlast;
import com.dawnduskterminal.world.TransformationConversion;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import twilightforest.init.TFEntities;

/**
 * 两瓶转换粉：对照表、落地那一下与投掷物
 *
 * <p>对照表那几条查 {@link TransformationConversion} 本身——方块表整张往返、群系两侧的映射、
 * 实体的双向筛选；落地那几条查 {@link TransformationBlast} 真的把半径 5 格内的东西换掉、半径外的留着；
 * 最后一条查物品右键之后真的丢出一颗带对的粉的投掷物
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class TransformationPowderTests {
    /** 测试用的球心：挑一块空地，模板是 ddt_empty */
    private static final BlockPos CENTRE = new BlockPos(4, 4, 4);

    /** 球外的那一格与球心的水平距离：9 格，刚好在半径 5 之外 */
    private static final int OUTSIDE = 9;

    /** 球壳那一格与球心的水平距离：正好等于半径，仍在球内；再往外一格就出球了 */
    private static final int SHELL = (int) TransformationConversion.RADIUS;

    /** 找实体时比作用半径放宽两格，替身摆在哪里都收得到 */
    private static final double SEARCH_MARGIN = TransformationConversion.RADIUS + 2.0D;

    /**
     * 方块表整张往返：{@code docs/biomes.md} 附录那份地表调色板，加十二块两档矿石
     *
     * <p>逐对查「晨昏线 → 主世界」与「主世界 → 晨昏线」两个方向，少写一半也红
     */
    @GameTest(template = "ddt_empty")
    public static void blockPairsCoverThePalette(GameTestHelper helper) {
        assertPair(helper, Blocks.GRASS_BLOCK, ModTerrainBlocks.ROSY_GRASS.get());
        assertPair(helper, Blocks.DIRT, ModTerrainBlocks.ROSY_DIRT.get());
        assertPair(helper, Blocks.STONE, ModTerrainBlocks.CANOPY_STONE.get());
        assertPair(helper, Blocks.GRANITE, ModTerrainBlocks.OCHRE.get());
        assertPair(helper, Blocks.DIORITE, ModTerrainBlocks.GREENSCHIST.get());
        assertPair(helper, Blocks.ANDESITE, ModTerrainBlocks.ECLOGITE.get());
        assertPair(helper, Blocks.DEEPSLATE, ModTerrainBlocks.CHARNOCKITE.get());
        assertPair(helper, Blocks.TUFF, ModTerrainBlocks.TWILIGHT_STONE.get());
        assertPair(helper, Blocks.SAND, ModTerrainBlocks.ROAD_SAND.get());
        assertPair(helper, Blocks.RED_SAND, ModTerrainBlocks.UNCONSCIOUS_SAND.get());
        assertPair(helper, Blocks.SANDSTONE, ModTerrainBlocks.SILTSTONE.get());
        assertPair(helper, Blocks.ICE, ModTerrainBlocks.FROZEN_CANOPY.get());
        assertPair(helper, Blocks.SNOW, ModTerrainBlocks.CANOPY_FROST.get());
        assertPair(helper, Blocks.SNOW_BLOCK, ModTerrainBlocks.CANOPY_FROST_BLOCK.get());
        assertPair(helper, Blocks.WATER, ModBlocks.PORTAL_FLUID.get());
        // 岩浆与热情的暮光不参与转换
        helper.assertTrue(
                TransformationConversion.toOverworldBlock(
                                        ModScorchingTwilight.BLOCK.get().defaultBlockState())
                                == null
                        && TransformationConversion.toChronoBlock(Blocks.LAVA.defaultBlockState()) == null,
                "lava and the scorching twilight fluid are out of scope, they must not map either way");

        assertPair(helper, Blocks.COPPER_ORE, ModChronoOres.CANOPY_STONE_COPPER_ORE.get());
        assertPair(helper, Blocks.IRON_ORE, ModChronoOres.CANOPY_STONE_IRON_ORE.get());
        assertPair(helper, Blocks.GOLD_ORE, ModChronoOres.CANOPY_STONE_GOLD_ORE.get());
        assertPair(helper, Blocks.REDSTONE_ORE, ModChronoOres.CANOPY_STONE_REDSTONE_ORE.get());
        assertPair(helper, Blocks.LAPIS_ORE, ModChronoOres.CANOPY_STONE_LAPIS_ORE.get());
        assertPair(helper, Blocks.DIAMOND_ORE, ModChronoOres.CANOPY_STONE_DIAMOND_ORE.get());
        assertPair(helper, Blocks.DEEPSLATE_COPPER_ORE, ModChronoOres.CHARNOCKITE_COPPER_ORE.get());
        assertPair(helper, Blocks.DEEPSLATE_IRON_ORE, ModChronoOres.CHARNOCKITE_IRON_ORE.get());
        assertPair(helper, Blocks.DEEPSLATE_GOLD_ORE, ModChronoOres.CHARNOCKITE_GOLD_ORE.get());
        assertPair(helper, Blocks.DEEPSLATE_REDSTONE_ORE, ModChronoOres.CHARNOCKITE_REDSTONE_ORE.get());
        assertPair(helper, Blocks.DEEPSLATE_LAPIS_ORE, ModChronoOres.CHARNOCKITE_LAPIS_ORE.get());
        assertPair(helper, Blocks.DEEPSLATE_DIAMOND_ORE, ModChronoOres.CHARNOCKITE_DIAMOND_ORE.get());

        // 模组独有的矿与方块没有对应物：查出来必须是 null，不许瞎猜一个
        for (Block orphan : List.of(
                ModTerrainBlocks.SCARLET_MOLYBDENUM_ORE.get(),
                ModChronoOres.TRANSFORE_ORE.get(),
                ModChronoOres.DIRAC_ORE.get(),
                ModTerrainBlocks.CHAOS_STONE.get())) {
            helper.assertTrue(
                    TransformationConversion.toOverworldBlock(orphan.defaultBlockState()) == null,
                    orphan.getDescriptionId() + " has no overworld counterpart, it must stay untouched");
        }
        helper.succeed();
    }

    /** 同名属性要照抄：五层霜层换过去还是五层 */
    @GameTest(template = "ddt_empty")
    public static void blockSwapKeepsTheSharedProperties(GameTestHelper helper) {
        BlockState frost =
                ModTerrainBlocks.CANOPY_FROST.get().defaultBlockState().setValue(SnowLayerBlock.LAYERS, 5);
        BlockState snow = TransformationConversion.toOverworldBlock(frost);
        helper.assertTrue(
                snow != null && snow.is(Blocks.SNOW) && snow.getValue(SnowLayerBlock.LAYERS) == 5,
                "five layers of canopy frost must come back as five layers of snow, got " + snow);
        helper.succeed();
    }

    /**
     * 群系表：副本按同名换命名空间，自建那四个照沙漠，暮色森林的群系按移植表回到它占的那一格；
     * 反向只有主世界那一侧有表，沙漠改指白地
     */
    @GameTest(template = "ddt_empty")
    public static void biomePairsFollowThePaletteRules(GameTestHelper helper) {
        Registry<Biome> biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        assertBiome(
                helper,
                biomes,
                biome("dawnduskterminal", "plains"),
                biome("minecraft", "plains"),
                "a same-named copy must go back to its vanilla cell");
        assertBiome(
                helper, biomes, biome("dawnduskterminal", "blue_desert"), biome("minecraft", "desert"), "blue desert");
        assertBiome(
                helper,
                biomes,
                biome("twilightforest", "highlands"),
                biome("minecraft", "windswept_hills"),
                "the twilight highlands sit in the windswept hills cell");
        assertBiome(
                helper, biomes, biome("twilightforest", "forest"), biome("minecraft", "forest"), "the twilight forest");

        // 白地与极昼极夜没有对应的原版格子：查出来必须是 null
        for (String orphan : List.of("white_land", "polar_dawn", "polar_dusk")) {
            helper.assertTrue(
                    TransformationConversion.toOverworldBiome(biome("dawnduskterminal", orphan), biomes) == null,
                    orphan + " has no overworld counterpart, it must stay untouched");
        }

        ResourceKey<Biome> plains = TransformationConversion.toChronoBiome(biome("minecraft", "plains"), biomes);
        helper.assertTrue(
                biome("dawnduskterminal", "plains").equals(plains),
                "minecraft:plains must come back as dawnduskterminal:plains, got " + plains);
        ResourceKey<Biome> desert = TransformationConversion.toChronoBiome(biome("minecraft", "desert"), biomes);
        helper.assertTrue(
                biome("dawnduskterminal", "blue_desert").equals(desert),
                "the desert copy was renamed to blue desert, so minecraft:desert must come back as blue desert, got "
                        + desert);
        helper.assertTrue(
                TransformationConversion.toOverworldBiome(biome("minecraft", "plains"), biomes) == null,
                "an overworld biome has nothing to transform into on the overworld side");
        helper.assertTrue(
                TransformationConversion.toChronoBiome(biome("twilightforest", "clearing"), biomes) == null,
                "a twilight forest biome is not an overworld cell, it must not come back as a copy");
        helper.succeed();
    }

    /** 实体表：暮色森林的大角羊与原版的羊互相换，表外的僵尸不动 */
    @GameTest(template = "ddt_empty")
    public static void entityPairsUseTheTwilightDataMap(GameTestHelper helper) {
        EntityType<?> bighorn = TFEntities.BIGHORN_SHEEP.get();
        helper.assertTrue(
                TransformationConversion.toOverworldEntity(bighorn) == EntityType.SHEEP,
                "the twilight bighorn sheep must turn into a vanilla sheep");
        helper.assertTrue(
                TransformationConversion.toTwilightEntity(EntityType.SHEEP) == bighorn,
                "a vanilla sheep must turn into the twilight bighorn sheep");
        helper.assertTrue(
                TransformationConversion.toOverworldEntity(EntityType.SHEEP) == null,
                "the overworld side must not answer for a vanilla sheep, otherwise the direction is lost");
        helper.assertTrue(
                TransformationConversion.toTwilightEntity(bighorn) == null,
                "the twilight side must not answer for a twilight mob either");
        helper.assertTrue(
                TransformationConversion.toOverworldEntity(EntityType.ZOMBIE) == null
                        && TransformationConversion.toTwilightEntity(EntityType.ZOMBIE) == null,
                "a zombie is in neither direction of the data map, it must stay as it is");
        helper.succeed();
    }

    /**
     * 强效那瓶落地：球内的晨昏线方块换成主世界方块，9 格外那块不动，球内的暮色大角羊换成原版羊
     */
    @GameTest(template = "ddt_empty")
    public static void potentBlastConvertsOnlyInsideTheRadius(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(CENTRE);
        helper.setBlock(CENTRE, ModTerrainBlocks.CANOPY_STONE.get());
        helper.setBlock(CENTRE.east(), ModTerrainBlocks.OCHRE.get());
        // 冰不需要下方承托，霜层要：测试模板是空的，摆下去会当场碎掉
        helper.setBlock(CENTRE.north(), ModTerrainBlocks.FROZEN_CANOPY.get());
        helper.setBlock(CENTRE.east(OUTSIDE), ModTerrainBlocks.CANOPY_STONE.get());
        helper.spawn(TFEntities.BIGHORN_SHEEP.get(), CENTRE.above());

        TransformationBlast.detonate(level, Vec3.atCenterOf(centre), false);

        helper.assertBlockPresent(Blocks.STONE, CENTRE);
        helper.assertBlockPresent(Blocks.GRANITE, CENTRE.east());
        helper.assertBlockPresent(Blocks.ICE, CENTRE.north());
        helper.assertBlockPresent(ModTerrainBlocks.CANOPY_STONE.get(), CENTRE.east(OUTSIDE));

        AABB box = new AABB(Vec3.atCenterOf(centre), Vec3.atCenterOf(centre)).inflate(SEARCH_MARGIN);
        helper.assertTrue(
                level.getEntitiesOfClass(Sheep.class, box).size() == 1,
                "the twilight bighorn sheep must have turned into exactly one vanilla sheep, got "
                        + level.getEntitiesOfClass(Sheep.class, box).size());
        helper.assertTrue(
                level.getEntitiesOfClass(
                                Entity.class, box, entity -> entity.getType() == TFEntities.BIGHORN_SHEEP.get())
                        .isEmpty(),
                "the bighorn sheep itself must be gone, only its vanilla replacement stays");
        helper.succeed();
    }

    /**
     * 反转那瓶方向相反：球内的石头变苍穹石，球内的群系换成晨昏线那一侧的同名副本
     *
     * <p>群系这一条只在测试场地的群系确实有对应副本时才成立；没有就跳过，不去断言一个不存在的转换
     */
    @GameTest(template = "ddt_empty")
    public static void reversedBlastGoesTheOtherWay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(CENTRE);
        helper.setBlock(CENTRE, Blocks.STONE);

        ResourceKey<Biome> before = level.getBiome(centre).unwrapKey().orElse(null);
        ResourceKey<Biome> expected = before == null
                ? null
                : TransformationConversion.toChronoBiome(
                        before, level.registryAccess().registryOrThrow(Registries.BIOME));

        TransformationBlast.detonate(level, Vec3.atCenterOf(centre), true);

        helper.assertBlockPresent(ModTerrainBlocks.CANOPY_STONE.get(), CENTRE);
        if (expected != null) {
            ResourceKey<Biome> after = level.getBiome(centre).unwrapKey().orElse(null);
            helper.assertTrue(
                    expected.equals(after), "the biome under the centre must become " + expected + ", got " + after);
        }
        helper.succeed();
    }

    /**
     * 粒子：强效那瓶是 {@code glow} 加 {@code totem_of_undying}，反转那瓶只有 {@code witch}；
     * 连线强效用 {@code enchant}、反转用 {@code dripping_obsidian_tear}
     *
     * <p>粒子只往客户端发包，服务端看不见，所以这里钉的是「打算撒什么」那份数据：
     * 种类、数量、每次小喷发的散布、谁摊开谁不摊开（图腾不摊开，它在落点自己飞出去），
     * 取点半径必须小于作用半径（漂出去之后才不超出作用球），以及连线用的是哪两种粒子
     */
    @GameTest(template = "ddt_empty")
    public static void particleBurstsUseTheRightIds(GameTestHelper helper) {
        helper.assertTrue(
                TransformationBlast.SUB_BURSTS >= 6,
                "the bursts must be spread over the sphere through several sub-bursts, got "
                        + TransformationBlast.SUB_BURSTS);
        helper.assertTrue(
                TransformationBlast.SUB_BURST_REACH_RATIO < 1.0D,
                "the sub-burst points must stay inside the radius so the drift does not overshoot the sphere, got ratio "
                        + TransformationBlast.SUB_BURST_REACH_RATIO);

        List<TransformationBlast.ParticleBurst> potent = TransformationBlast.particleBursts(false);
        TransformationBlast.ParticleBurst glow = null;
        TransformationBlast.ParticleBurst totem = null;
        for (TransformationBlast.ParticleBurst burst : potent) {
            helper.assertFalse(
                    burst.type() == ParticleTypes.WITCH || burst.type() == ParticleTypes.REVERSE_PORTAL,
                    "the potent powder only uses glow and totem_of_undying, got " + burst.type());
            if (burst.type() == ParticleTypes.GLOW) {
                glow = burst;
            } else if (burst.type() == ParticleTypes.TOTEM_OF_UNDYING) {
                totem = burst;
            }
            assertBurst(helper, burst);
        }
        helper.assertTrue(glow != null, "the potent powder must pour out glow particles");
        helper.assertTrue(totem != null, "the potent powder must also sparkle with totem_of_undying");
        helper.assertTrue(glow.distributed(), "glow particles must be spread over the sphere");
        helper.assertFalse(
                totem.distributed(), "the totem already flies out on its own, it must burst once at the impact point");
        helper.assertTrue(
                glow.count() >= TransformationBlast.SUB_BURSTS,
                "every sub-burst must carry at least one glow particle, got " + glow.count() + " over "
                        + TransformationBlast.SUB_BURSTS + " bursts");

        List<TransformationBlast.ParticleBurst> reversed = TransformationBlast.particleBursts(true);
        TransformationBlast.ParticleBurst witch = null;
        for (TransformationBlast.ParticleBurst burst : reversed) {
            helper.assertTrue(
                    burst.type() == ParticleTypes.WITCH,
                    "the reversed powder may only spawn minecraft:witch, got " + burst.type());
            witch = burst;
            assertBurst(helper, burst);
        }
        helper.assertTrue(witch != null, "the reversed powder must spawn witch particles");
        helper.assertTrue(witch.distributed(), "witch particles must be spread over the sphere");
        helper.assertTrue(
                witch.count() >= TransformationBlast.SUB_BURSTS,
                "every sub-burst must carry at least one witch particle, got " + witch.count() + " over "
                        + TransformationBlast.SUB_BURSTS + " bursts");

        helper.assertTrue(
                TransformationBlast.lineParticle(false) == ParticleTypes.ENCHANT,
                "the potent powder draws its lines with enchant, got " + TransformationBlast.lineParticle(false));
        helper.assertTrue(
                TransformationBlast.lineParticle(true) == ParticleTypes.DRIPPING_OBSIDIAN_TEAR,
                "the reversed powder draws its lines with the crying obsidian drip, got "
                        + TransformationBlast.lineParticle(true));
        helper.succeed();
    }

    /**
     * 一把粒子的口径：位置的高斯散布乘三仍要落在作用半径以内，速度大于 0
     *
     * <p>高斯尾巴能到三倍，所以 {@code spread × 3 ≤ 半径} 才保证粒子不会从落点直接撒到球外
     */
    private static void assertBurst(GameTestHelper helper, TransformationBlast.ParticleBurst burst) {
        helper.assertTrue(burst.speed() > 0.0D, burst.type() + " must start with some velocity, got " + burst.speed());
        helper.assertTrue(
                burst.spread() * 3.0D <= TransformationConversion.RADIUS,
                burst.type() + " may scatter outside the " + TransformationConversion.RADIUS + " block radius: spread "
                        + burst.spread() + " with a three sigma tail");
    }

    /**
     * 换液体时不反应：球内那一行水换成不稳定液体之后保持一片，只有球壳上那一格与球外的水反应
     *
     * <p>水从球心往东铺 0~6 格：0~5 在球内（5 格正好在边界上），6 在球外。
     * 反转那瓶把球内的水换成不稳定液体。球内不该冒出脆弱的苍穹，边界那一格该被反应换掉，
     * 球外那一格还该是水
     */
    @GameTest(template = "ddt_empty")
    public static void fluidReactionsOnlyHappenAtTheShell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(CENTRE);
        for (int i = 0; i <= SHELL + 1; i++) {
            helper.setBlock(CENTRE.east(i), Blocks.WATER);
        }

        TransformationBlast.detonate(level, Vec3.atCenterOf(centre), true);

        helper.assertBlockPresent(ModBlocks.PORTAL_FLUID.get(), CENTRE);
        helper.assertBlockPresent(ModBlocks.PORTAL_FLUID.get(), CENTRE.east(1));
        helper.assertBlockPresent(ModBlocks.PORTAL_FLUID.get(), CENTRE.east(SHELL - 1));
        helper.assertBlockPresent(ModTerrainBlocks.FRAGILE_CANOPY.get(), CENTRE.east(SHELL));
        helper.assertBlockPresent(Blocks.WATER, CENTRE.east(SHELL + 1));
        helper.succeed();
    }

    /**
     * 易碎的苍穹没有「另一侧的对应物」：它是「不稳定液体 + 水」的流体反应产物，只能拆回原料——
     * 强效那瓶拆成水，反转那瓶拆成不稳定液体
     */
    @GameTest(template = "ddt_empty")
    public static void fragileCanopyMeltsBackIntoWater(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(CENTRE);
        helper.setBlock(CENTRE, ModTerrainBlocks.FRAGILE_CANOPY.get());
        helper.setBlock(CENTRE.east(2), ModTerrainBlocks.FRAGILE_CANOPY.get());

        TransformationBlast.detonate(level, Vec3.atCenterOf(centre), false);

        helper.assertBlockPresent(Blocks.WATER, CENTRE);
        helper.assertBlockPresent(Blocks.WATER, CENTRE.east(2));
        helper.succeed();
    }

    /** 反转那瓶把易碎的苍穹拆成不稳定液体 */
    @GameTest(template = "ddt_empty")
    public static void reversedPowderMeltsFragileCanopyIntoPortalFluid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(CENTRE);
        helper.setBlock(CENTRE, ModTerrainBlocks.FRAGILE_CANOPY.get());
        helper.setBlock(CENTRE.east(2), ModTerrainBlocks.FRAGILE_CANOPY.get());

        TransformationBlast.detonate(level, Vec3.atCenterOf(centre), true);

        helper.assertBlockPresent(ModBlocks.PORTAL_FLUID.get(), CENTRE);
        helper.assertBlockPresent(ModBlocks.PORTAL_FLUID.get(), CENTRE.east(2));
        helper.succeed();
    }

    /**
     * 物品右键：真的丢出一颗投掷物，粉带在投掷物上、手上少一个，反转那瓶丢的是另一条实体类型
     *
     * <p>物理（重力 0.05、空气阻力 0.99、命中判定）都在 {@code ThrowableItemProjectile} 与父类里，
     * 与喷溅药水同一套；这里只查「扔出去了、带的是哪一种粉」
     */
    @GameTest(template = "ddt_empty")
    public static void powdersThrowTheRightProjectile(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 at = Vec3.atCenterOf(helper.absolutePos(CENTRE));
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(at.x, at.y, at.z);

        throwOnce(helper, level, player, ModItems.POTENT_TRANSFORMATION_POWDER.get(), at, false);
        throwOnce(helper, level, player, ModItems.REVERSED_TRANSFORMATION_POWDER.get(), at, true);
        helper.succeed();
    }

    /**
     * 碰到液体也算落点：投掷物一进水里就炸在水面那一格，不会一路穿到底
     *
     * <p>液体没有碰撞箱，父类那条命中判定看不见它；这一条钉的就是补上的那一支：
     * 把粉摆在满满一格水的中间，手动 tick 一次，它应当立刻消失，并且把这一格水换成不稳定液体
     */
    @GameTest(template = "ddt_empty")
    public static void powderDetonatesOnTouchingLiquid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(CENTRE);
        helper.setBlock(CENTRE, Blocks.WATER);

        TransformationPowderEntity powder =
                new TransformationPowderEntity(ModEntities.REVERSED_TRANSFORMATION_POWDER.get(), level);
        Vec3 at = Vec3.atCenterOf(centre);
        powder.setPos(at.x, at.y, at.z);
        powder.tick();

        helper.assertTrue(powder.isRemoved(), "the powder must burst as soon as it touches a liquid");
        helper.assertBlockPresent(ModBlocks.PORTAL_FLUID.get(), CENTRE);
        helper.succeed();
    }

    /**
     * 自己那一侧的液体不算落点：强效那瓶穿过水、反转那瓶穿过不稳定液体
     *
     * <p>反过来那一侧仍然算——强效那瓶扔进不稳定液体里就该当场炸开。三条一起钉，
     * 免得把「穿过自己的流体」写成「什么液体都不管」
     */
    @GameTest(template = "ddt_empty")
    public static void powderFliesThroughItsOwnFluid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos centre = helper.absolutePos(CENTRE);

        helper.setBlock(CENTRE, Blocks.WATER);
        TransformationPowderEntity potent = powder(level, ModEntities.POTENT_TRANSFORMATION_POWDER.get(), centre);
        potent.tick();
        helper.assertFalse(
                potent.isRemoved(), "the potent powder must fly through water, water is the side it produces");
        helper.assertBlockPresent(Blocks.WATER, CENTRE);

        BlockPos chrono = CENTRE.east(3);
        helper.setBlock(chrono, ModBlocks.PORTAL_FLUID.get());
        TransformationPowderEntity reversed =
                powder(level, ModEntities.REVERSED_TRANSFORMATION_POWDER.get(), helper.absolutePos(chrono));
        reversed.tick();
        helper.assertFalse(
                reversed.isRemoved(),
                "the reversed powder must fly through portal fluid, that is the side it produces");
        helper.assertBlockPresent(ModBlocks.PORTAL_FLUID.get(), chrono);

        BlockPos other = CENTRE.west(3);
        helper.setBlock(other, ModBlocks.PORTAL_FLUID.get());
        TransformationPowderEntity potentInChrono =
                powder(level, ModEntities.POTENT_TRANSFORMATION_POWDER.get(), helper.absolutePos(other));
        potentInChrono.tick();
        helper.assertTrue(
                potentInChrono.isRemoved(),
                "the potent powder must still burst in portal fluid, that is not its own side");
        helper.succeed();
    }

    private static TransformationPowderEntity powder(
            ServerLevel level, EntityType<TransformationPowderEntity> type, BlockPos pos) {
        TransformationPowderEntity powder = new TransformationPowderEntity(type, level);
        Vec3 at = Vec3.atCenterOf(pos);
        powder.setPos(at.x, at.y, at.z);
        return powder;
    }

    private static void throwOnce(
            GameTestHelper helper,
            ServerLevel level,
            Player player,
            TransformationPowderItem item,
            Vec3 at,
            boolean reversed) {
        AABB box = new AABB(at, at).inflate(2.0D);
        int before =
                level.getEntitiesOfClass(TransformationPowderEntity.class, box).size();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item, 2));
        var result = item.use(level, player, InteractionHand.MAIN_HAND);
        helper.assertTrue(result.getResult().consumesAction(), "throwing a powder must consume the click action");
        List<TransformationPowderEntity> thrown = level.getEntitiesOfClass(TransformationPowderEntity.class, box);
        helper.assertTrue(
                thrown.size() == before + 1,
                "one click must add exactly one powder, got " + before + " -> " + thrown.size());
        TransformationPowderEntity powder = thrown.get(thrown.size() - 1);
        helper.assertTrue(
                powder.reversed() == reversed,
                "the thrown powder must know which direction it is, got reversed=" + powder.reversed());
        helper.assertTrue(
                powder.getItem().is(item), "the thrown powder must carry its own item, got " + powder.getItem());
        helper.assertTrue(
                player.getMainHandItem().getCount() == 1,
                "one powder must be spent per throw, the stack is "
                        + player.getMainHandItem().getCount());
    }

    private static void assertPair(GameTestHelper helper, Block vanilla, Block chrono) {
        BlockState toOverworld = TransformationConversion.toOverworldBlock(chrono.defaultBlockState());
        helper.assertTrue(
                toOverworld != null && toOverworld.is(vanilla),
                chrono.getDescriptionId() + " must come back as " + vanilla.getDescriptionId() + ", got "
                        + toOverworld);
        BlockState toChrono = TransformationConversion.toChronoBlock(vanilla.defaultBlockState());
        helper.assertTrue(
                toChrono != null && toChrono.is(chrono),
                vanilla.getDescriptionId() + " must turn into " + chrono.getDescriptionId() + ", got " + toChrono);
    }

    private static void assertBiome(
            GameTestHelper helper,
            Registry<Biome> biomes,
            ResourceKey<Biome> from,
            ResourceKey<Biome> to,
            String what) {
        ResourceKey<Biome> mapped = TransformationConversion.toOverworldBiome(from, biomes);
        helper.assertTrue(
                to.equals(mapped),
                "biome mapping (" + what + "): " + from.location() + " must map to " + to.location() + ", got "
                        + (mapped == null ? "null" : mapped.location()));
    }

    private static ResourceKey<Biome> biome(String namespace, String path) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    private TransformationPowderTests() {}
}
