package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.config.DdtConfig;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.dawnduskterminal.world.ChronoLine;
import com.dawnduskterminal.world.ChronoSkyLight;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import twilightforest.init.TFEntities;

/**
 * 晨昏线的刷怪表：暮色森林那一套落到了对应的副本上，原版生物只留蝙蝠
 *
 * <h2>三条各自管什么</h2>
 *
 * <ul>
 *   <li>{@link #twilightTablesLandInTheMatchingCopies}：读注册表里那份群系，核对动物表与怪物表；
 *   <li>{@link #swampAndGlacierCarryCenterAndOuterTables}：只有中心群系的两族要能拿到外围的条目；
 *   <li>{@link #vanillaSpawnsAreGoneExceptBats}：蝙蝠之外的 {@code minecraft:*} 条目必须一处不剩。
 * </ul>
 *
 * <h2>能刷出来这件事靠什么保证</h2>
 *
 * <p>光把条目写进群系不够：落地判定由实体类型的 {@code SpawnPlacements} 决定。
 * 暮色森林的动物走 {@code Animal.checkAnimalSpawnRules}，它只认脚下是
 * {@link BlockTags#ANIMALS_SPAWNABLE_ON} 里的方块；企鹅走 TF 自己的
 * {@code penguins_spawnable_on}，那份标签指向 {@code #minecraft:ice}。
 * 两条标签里都必须有本模组的地表方块，否则条目写得再对也一只都刷不出来，
 * 这就是 {@link #theChronoSurfaceSatisfiesTheGroundTags} 与
 * {@link #everySpawnedMobHasAPlacement} 要盯的东西。
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class ChronoSpawnTests {
    /**
     * 暮色森林森林那一份动物表：clearing、forest、firefly_forest 等群系共用
     *
     * <p>表里带 `minecraft:chicken` 与 `minecraft:wolf`，那是暮色森林的原设计；
     * 并入这份表的副本会连它们一起拿到，不在表里的群系则只剩下面那群暮色森林动物。
     */
    private static final Map<String, Integer> TWILIGHT_FOREST = new LinkedHashMap<>();
    /** 清洗后不含原版条目的那一份动物表：只有暮色森林的七种动物 */
    private static final Map<String, Integer> TWILIGHT_FOREST_CLEANED = new LinkedHashMap<>();
    /** 按对应关系并入暮色森林刷怪表的副本，一个暮色森林群系只喂一个 */
    private static final List<String> FOREST_COPIES =
            List.of("forest", "birch_forest", "flower_forest", "jungle", "plains", "savanna", "windswept_hills");
    /** 铺通用动物表的林地、草地与丘陵：不是映射，是同一份表铺给同类群系 */
    private static final List<String> GENERIC_ANIMALS = List.of(
            "taiga",
            "old_growth_pine_taiga",
            "old_growth_spruce_taiga",
            "meadow",
            "sunflower_plains",
            "savanna_plateau",
            "windswept_savanna",
            "windswept_forest",
            "sparse_jungle",
            "bamboo_jungle");
    /** 铺雪地一族的两个极冷群系：冰川的企鹅与雪林的冬狼、雪怪 */
    private static final List<String> SNOW_FAMILY = List.of("frozen_peaks", "jagged_peaks");

    static {
        TWILIGHT_FOREST.put("twilightforest:deer", 15);
        TWILIGHT_FOREST.put("twilightforest:tiny_bird", 15);
        TWILIGHT_FOREST.put("twilightforest:bighorn_sheep", 12);
        TWILIGHT_FOREST.put("twilightforest:boar", 10);
        TWILIGHT_FOREST.put("twilightforest:squirrel", 10);
        TWILIGHT_FOREST.put("twilightforest:dwarf_rabbit", 10);
        TWILIGHT_FOREST.put("twilightforest:raven", 10);
        TWILIGHT_FOREST.put("minecraft:chicken", 10);
        TWILIGHT_FOREST.put("minecraft:wolf", 5);
        TWILIGHT_FOREST_CLEANED.putAll(TWILIGHT_FOREST);
        TWILIGHT_FOREST_CLEANED.remove("minecraft:chicken");
        TWILIGHT_FOREST_CLEANED.remove("minecraft:wolf");
    }

    /** 七份森林副本：动物表正好是暮色森林那一份，敌对与水生全空 */
    @GameTest(template = "ddt_empty")
    public static void twilightTablesLandInTheMatchingCopies(GameTestHelper helper) {
        for (String biome : FOREST_COPIES) {
            helper.assertTrue(
                    creatures(helper, biome).equals(TWILIGHT_FOREST),
                    biome + " must carry the twilight forest creature table, got " + creatures(helper, biome));
            helper.assertTrue(
                    monsters(helper, biome).isEmpty(),
                    biome + " must not spawn any monster, got " + monsters(helper, biome));
            helper.assertTrue(
                    weights(helper, biome, MobCategory.AMBIENT).isEmpty(), biome + " must not spawn ambient mobs");
        }
        helper.succeed();
    }

    /**
     * 通用动物表铺到十片林地、草地与丘陵
     *
     * <p>这份表就是暮色森林森林群系那一份，包含它自带的鸡与狼；
     * 这十片不在映射表里，用的是同一份共享表，所以敌对栏仍然为空
     */
    @GameTest(template = "ddt_empty")
    public static void genericAnimalTableCoversTheLandBiomes(GameTestHelper helper) {
        for (String biome : GENERIC_ANIMALS) {
            helper.assertTrue(
                    creatures(helper, biome).equals(TWILIGHT_FOREST),
                    biome + " must carry the generic twilight creature table, got " + creatures(helper, biome));
            helper.assertTrue(
                    monsters(helper, biome).isEmpty(),
                    biome + " must not spawn any monster, got " + monsters(helper, biome));
        }
        helper.succeed();
    }

    /**
     * 两个极冷的雪地群系铺雪地一族
     *
     * <p>企鹅来自冰川，冬狼与雪怪来自雪林。
     * 其余雪地群系不铺这份表：它们的温度没有低到这一档
     */
    @GameTest(template = "ddt_empty")
    public static void theColdestSnowBiomesCarryTheSnowFamily(GameTestHelper helper) {
        Map<String, Integer> penguins = new LinkedHashMap<>();
        penguins.put("twilightforest:penguin", 10);
        Map<String, Integer> snowMonsters = new LinkedHashMap<>();
        snowMonsters.put("twilightforest:winter_wolf", 5);
        snowMonsters.put("twilightforest:yeti", 5);
        for (String biome : SNOW_FAMILY) {
            helper.assertTrue(
                    creatures(helper, biome).equals(penguins),
                    biome + " creatures must be " + penguins + ", got " + creatures(helper, biome));
            helper.assertTrue(
                    monsters(helper, biome).equals(snowMonsters),
                    biome + " monsters must be " + snowMonsters + ", got " + monsters(helper, biome));
        }
        helper.succeed();
    }

    /** 明确不接生物的两个群系：樱花林与风袭砂砾丘陵只剩蝙蝠 */
    @GameTest(template = "ddt_empty")
    public static void cherryGroveAndGravellyHillsStayEmpty(GameTestHelper helper) {
        for (String biome : new String[] {"cherry_grove", "windswept_gravelly_hills"}) {
            helper.assertTrue(
                    creatures(helper, biome).isEmpty(),
                    biome + " must not spawn creatures, got " + creatures(helper, biome));
            helper.assertTrue(
                    monsters(helper, biome).isEmpty(),
                    biome + " must not spawn monsters, got " + monsters(helper, biome));
        }
        helper.succeed();
    }

    /**
     * 不并入暮色森林表的两个群系：原版条目照样清掉，自己的东西原样保留
     *
     * <p>红草树林是萤火虫森林那份移植再染红，动物表与自己那份敌对（恶果与大恶魂）保留；
     * 天门是自建群系，只保留自己那份动物表。
     * 两者都不在映射表里，因此鸡与狼这两条原版条目按规则删掉，不会因为来自暮色森林的表被留下
     */
    @GameTest(template = "ddt_empty")
    public static void unmappedBiomesLoseOnlyTheVanillaEntries(GameTestHelper helper) {
        helper.assertTrue(
                creatures(helper, "old_growth_birch_forest").equals(TWILIGHT_FOREST_CLEANED),
                "old_growth_birch_forest creatures must be " + TWILIGHT_FOREST_CLEANED + ", got "
                        + creatures(helper, "old_growth_birch_forest"));
        Map<String, Integer> ghosts = new LinkedHashMap<>();
        ghosts.put("dawnduskterminal:ghastfruity", 10);
        ghosts.put("dawnduskterminal:ghast_elite", 1);
        helper.assertTrue(
                monsters(helper, "old_growth_birch_forest").equals(ghosts),
                "old_growth_birch_forest monsters must be " + ghosts + ", got "
                        + monsters(helper, "old_growth_birch_forest"));
        helper.assertTrue(
                creatures(helper, "floatrees").equals(TWILIGHT_FOREST_CLEANED),
                "floatrees creatures must be " + TWILIGHT_FOREST_CLEANED + ", got " + creatures(helper, "floatrees"));
        helper.assertTrue(
                monsters(helper, "floatrees").isEmpty(),
                "floatrees must not spawn any monster, got " + monsters(helper, "floatrees"));
        helper.succeed();
    }

    /**
     * 沼泽与冰川：项目里只有中心群系，中心与外围的刷怪表要合在一起
     *
     * <p>沼泽是 {@code swamp}（外围，僵尸与蚊群）加 {@code fire_swamp}（中心，空表）；
     * 冰川是 {@code glacier}（中心，企鹅）加 {@code snowy_forest}（外围，冬狼与雪怪）
     */
    @GameTest(template = "ddt_empty")
    public static void swampAndGlacierCarryCenterAndOuterTables(GameTestHelper helper) {
        Map<String, Integer> swamp = new LinkedHashMap<>();
        swamp.put("minecraft:zombie", 10);
        swamp.put("twilightforest:mosquito_swarm", 10);
        helper.assertTrue(
                monsters(helper, "swamp").equals(swamp),
                "swamp monsters must be " + swamp + ", got " + monsters(helper, "swamp"));

        Map<String, Integer> penguins = new LinkedHashMap<>();
        penguins.put("twilightforest:penguin", 10);
        Map<String, Integer> glacier = new LinkedHashMap<>();
        glacier.put("twilightforest:winter_wolf", 5);
        glacier.put("twilightforest:yeti", 5);
        helper.assertTrue(
                creatures(helper, "snowy_plains").equals(penguins),
                "snowy_plains creatures must be the glacier table, got " + creatures(helper, "snowy_plains"));
        helper.assertTrue(
                monsters(helper, "snowy_plains").equals(glacier),
                "snowy_plains monsters must be the snowy forest table, got " + monsters(helper, "snowy_plains"));
        helper.succeed();
    }

    /** 暗森林保持原样：活盔甲与暮色森林那四只 */
    @GameTest(template = "ddt_empty")
    public static void darkForestKeepsItsOwnTable(GameTestHelper helper) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("dawnduskterminal:living_armor", 10);
        expected.put("twilightforest:carminite_ghastguard", 15);
        expected.put("twilightforest:carminite_ghastling", 15);
        expected.put("twilightforest:king_spider", 10);
        expected.put("twilightforest:pinch_beetle", 10);
        helper.assertTrue(
                monsters(helper, "dark_forest").equals(expected),
                "dark_forest monsters must be " + expected + ", got " + monsters(helper, "dark_forest"));
        helper.succeed();
    }

    /**
     * 蝙蝠之外的原版生物一处不剩
     *
     * <p>{@code minecraft:chicken} 与 {@code minecraft:wolf} 是暮色森林那份动物表自带的，
     * 所以这里只允许它们出现在带暮色动物表的副本里——计数按「带那张表的群系」逐一对账
     */
    @GameTest(template = "ddt_empty")
    public static void vanillaSpawnsAreGoneExceptBats(GameTestHelper helper) {
        int bats = 0;
        for (Map.Entry<ResourceKey<Biome>, Biome> entry : helper.getLevel()
                .registryAccess()
                .registryOrThrow(Registries.BIOME)
                .entrySet()) {
            if (!entry.getKey().location().getNamespace().equals(DawnDuskTerminal.MOD_ID)) {
                continue;
            }
            String biome = entry.getKey().location().getPath();
            for (MobCategory category : MobCategory.values()) {
                for (MobSpawnSettings.SpawnerData data :
                        entry.getValue().getMobSettings().getMobs(category).unwrap()) {
                    ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(data.type);
                    if (!id.getNamespace().equals("minecraft")) {
                        continue;
                    }
                    if (id.getPath().equals("bat")) {
                        bats++;
                        continue;
                    }
                    boolean fromTwilightTable =
                            id.getPath().equals("chicken") || id.getPath().equals("wolf")
                                    ? FOREST_COPIES.contains(biome) || GENERIC_ANIMALS.contains(biome)
                                    : id.getPath().equals("zombie") && biome.equals("swamp");
                    helper.assertTrue(
                            fromTwilightTable, biome + " still spawns " + id + " outside the twilight tables");
                }
            }
        }
        helper.assertTrue(bats > 0, "the bat entries must survive the cleanup, counted " + bats);
        helper.succeed();
    }

    /** 群系表里出现的每一个实体类型都得有一份落点判定，否则永远刷不出来 */
    @GameTest(template = "ddt_empty")
    public static void everySpawnedMobHasAPlacement(GameTestHelper helper) {
        for (ResourceKey<Biome> key : helper.getLevel()
                .registryAccess()
                .registryOrThrow(Registries.BIOME)
                .registryKeySet()) {
            if (!key.location().getNamespace().equals(DawnDuskTerminal.MOD_ID)) {
                continue;
            }
            Biome biome = helper.getLevel()
                    .registryAccess()
                    .registryOrThrow(Registries.BIOME)
                    .getOrThrow(key);
            for (MobCategory category : MobCategory.values()) {
                for (MobSpawnSettings.SpawnerData data :
                        biome.getMobSettings().getMobs(category).unwrap()) {
                    ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(data.type);
                    helper.assertTrue(
                            SpawnPlacements.hasPlacement(data.type),
                            id + " is spawned by " + key.location() + " but has no spawn placement");
                }
            }
        }
        helper.succeed();
    }

    /**
     * 地表方块得进得了落点判定要读的方块标签
     *
     * <p>动物只肯站在 {@link BlockTags#ANIMALS_SPAWNABLE_ON} 里，而本维度的草地是霞草；
     * 企鹅只肯站在暮色森林 {@code penguins_spawnable_on} 里，那份标签就是 {@code #minecraft:ice}。
     * 两条少任何一条，条目写对了也是一只不刷
     */
    @GameTest(template = "ddt_empty")
    public static void theChronoSurfaceSatisfiesTheGroundTags(GameTestHelper helper) {
        Block grass = ModTerrainBlocks.ROSY_GRASS.get();
        Block ice = ModTerrainBlocks.FROZEN_CANOPY.get();
        helper.assertTrue(
                grass.defaultBlockState().is(BlockTags.ANIMALS_SPAWNABLE_ON),
                "rosy grass must be in #minecraft:animals_spawnable_on, otherwise no animal can spawn");
        TagKey<Block> penguins = TagKey.create(
                Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("twilightforest", "penguins_spawnable_on"));
        helper.assertTrue(
                ice.defaultBlockState().is(penguins),
                "frozen canopy must be in #twilightforest:penguins_spawnable_on, otherwise no penguin can spawn");
        helper.succeed();
    }

    /**
     * 落点判定本身要在霞草上通过
     *
     * <p>只查标签是纸面结论：判定走的是实体类型上登记的那个谓词。这里把霞草铺下去直接问一次，
     * 原生鸡与暮色森林的鹿都必须通过；把脚下换成苍穹石，两者都必须被拒。
     *
     * <p>亮度用一块荧石保证：白天的原始亮度取决于测试世界的时刻，取巧等于留下一条会随时刻失败的用例
     */
    @GameTest(template = "ddt_empty", skyAccess = true)
    public static void theChronoGroundPassesTheAnimalPlacement(GameTestHelper helper) {
        // GameTestHelper#setBlock 收的是相对坐标，落点判定收的是绝对坐标，两者别混
        BlockPos ground = new BlockPos(2, 1, 2);
        BlockPos spawn = helper.absolutePos(ground.above());
        helper.setBlock(ground, ModTerrainBlocks.ROSY_GRASS.get());
        helper.setBlock(ground.above(), Blocks.AIR);
        helper.setBlock(ground.offset(1, 1, 0), Blocks.GLOWSTONE);
        helper.assertTrue(
                helper.getLevel().getRawBrightness(spawn, 0) > 8,
                "the fixture must be bright enough for animals, got "
                        + helper.getLevel().getRawBrightness(spawn, 0));
        for (EntityType<?> type : new EntityType<?>[] {EntityType.CHICKEN, TFEntities.DEER.get()}) {
            helper.assertTrue(
                    SpawnPlacements.checkSpawnRules(
                            type,
                            helper.getLevel(),
                            MobSpawnType.NATURAL,
                            spawn,
                            helper.getLevel().getRandom()),
                    BuiltInRegistries.ENTITY_TYPE.getKey(type) + " must accept rosy grass as its ground");
            helper.setBlock(ground, ModTerrainBlocks.CANOPY_STONE.get());
            helper.assertFalse(
                    SpawnPlacements.checkSpawnRules(
                            type,
                            helper.getLevel(),
                            MobSpawnType.NATURAL,
                            spawn,
                            helper.getLevel().getRandom()),
                    BuiltInRegistries.ENTITY_TYPE.getKey(type)
                            + " must reject canopy stone: the ground tag is what makes spawning work");
            helper.setBlock(ground, ModTerrainBlocks.ROSY_GRASS.get());
        }
        helper.succeed();
    }

    /**
     * 夜侧够不够暗：刷怪判定读的是「天光减去压暗量」
     *
     * <p>这个维度的时刻被 {@code fixed_time} 锁死，服务端算出的压暗量晴天恒为 0，
     * 原版判定于是读到天光本身（昼侧 15、夜侧 12），两者都高于维度类型给出的抽样上限 7，
     * <b>露天的地表一只敌对生物也刷不出来</b>。{@code MonsterSpawnLightMixin} 把压暗量换成按坐标取的值，
     * 这条用例钉住三件事：昼侧照样不刷、夜侧确实能刷、压暗量取 0 时夜侧刷不出来。
     *
     * <p>用例只读 {@link ChronoSkyLight} 的纯函数，不依赖测试维度是晨昏线：游戏测试跑在主世界。
     */
    @GameTest(template = "ddt_empty")
    public static void theNightSideIsDarkEnoughForMonsters(GameTestHelper helper) {
        // 兜底线：法向 +X，x 为正落昼侧，为负落夜侧
        ChronoLine line = ChronoLine.fallback();
        int edge = (int) Math.ceil(DdtConfig.maxDistance());
        int daySky = ChronoSkyLight.maxSkyLight(line, edge, 0);
        int nightSky = ChronoSkyLight.maxSkyLight(line, -edge, 0);
        int dayDarken = ChronoSkyLight.localSkyDarken(line, edge, 0, 0.0F, 0.0F);
        int centerDarken = ChronoSkyLight.localSkyDarken(line, 0, 0, 0.0F, 0.0F);
        int nightDarken = ChronoSkyLight.localSkyDarken(line, -edge, 0, 0.0F, 0.0F);
        helper.assertTrue(
                dayDarken <= centerDarken && centerDarken <= nightDarken,
                "the darkening must grow from the day side to the night side, got " + dayDarken + " / " + centerDarken
                        + " / " + nightDarken);
        helper.assertFalse(
                ChronoSkyLight.lightAllowsSpawn(daySky, 0, dayDarken, 7),
                "the day side must stay too bright for monsters, sky " + daySky + " darkening " + dayDarken);
        helper.assertTrue(
                ChronoSkyLight.lightAllowsSpawn(nightSky, 0, nightDarken, 7),
                "the night side must be dark enough for monsters, sky " + nightSky + " darkening " + nightDarken);
        // 回归锁：压暗量取 0 就是修复之前的行为，夜侧一样刷不出来
        helper.assertFalse(
                ChronoSkyLight.lightAllowsSpawn(nightSky, 0, 0, 7),
                "a darkening of 0 must keep the night side unspawnable, that is the bug this pins");
        helper.succeed();
    }

    /**
     * 压暗量只进刷怪判定：动物与作物读的仍是天光本身
     *
     * <p>{@code Animal#isBrightEnoughToSpawn} 读 {@code getRawBrightness(pos, 0)}，即不减压暗量的天光，
     * 要求大于 8；作物生长同样要求 9 以上。把天光上限压到 8 以下虽然也能让怪物在夜侧刷出来，
     * 但夜侧会连动物与作物一起失去，所以这里钉住「上限始终高于 8」，修复只能走刷怪判定那一路。
     */
    @GameTest(template = "ddt_empty")
    public static void theSkyLightCapStillFeedsAnimalsAndCrops(GameTestHelper helper) {
        ChronoLine line = ChronoLine.fallback();
        int edge = (int) Math.ceil(DdtConfig.maxDistance());
        for (int x : new int[] {edge, 0, -edge}) {
            int cap = ChronoSkyLight.maxSkyLight(line, x, 0);
            helper.assertTrue(
                    cap > 8, "the sky light cap at x=" + x + " must stay above 8, otherwise animals and crops die out");
        }
        helper.succeed();
    }

    private static Map<String, Integer> creatures(GameTestHelper helper, String biome) {
        return weights(helper, biome, MobCategory.CREATURE);
    }

    private static Map<String, Integer> monsters(GameTestHelper helper, String biome) {
        return weights(helper, biome, MobCategory.MONSTER);
    }

    private static Map<String, Integer> weights(GameTestHelper helper, String biome, MobCategory category) {
        Holder<Biome> holder = helper.getLevel()
                .registryAccess()
                .registryOrThrow(Registries.BIOME)
                .getHolderOrThrow(ResourceKey.create(Registries.BIOME, DawnDuskTerminal.id(biome)));
        Map<String, Integer> actual = new LinkedHashMap<>();
        for (MobSpawnSettings.SpawnerData data :
                holder.value().getMobSettings().getMobs(category).unwrap()) {
            actual.put(
                    BuiltInRegistries.ENTITY_TYPE.getKey(data.type).toString(),
                    data.getWeight().asInt());
        }
        return actual;
    }

    private ChronoSpawnTests() {}
}
