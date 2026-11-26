package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.entity.VirtualImageEntity;
import com.dawnduskterminal.registry.ModDamageTypes;
import com.dawnduskterminal.registry.ModEntities;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 「虚像」的刷怪表与数值
 *
 * <h2>钉住的东西</h2>
 *
 * <ul>
 *   <li>{@link #spawnPlacementIsRegistered}：群系表里有、{@code SpawnPlacements} 里没有的实体会被 NeoForge
 *       报 ERROR，而且一条也刷不出来
 *   <li>{@link #riverAndOceanBiomesSpawnIt}：河与海的全部温度档都要列出它
 *   <li>{@link #nonRiverBiomesStayClean}：不在河海之列的群系不该出现它
 *   <li>{@link #statsScaleWithRandomSize}：生命与攻击按随机体型偏移缩放，基准与小型「弦」同档
 * </ul>
 *
 * <p>群系 JSON 里的 {@code spawners} 是数据包内容，写错 id 或写错分类时游戏不会报错，只是「刷不出来」；
 * 这里直接读注册表里的那一份群系，把列表翻出来对一遍
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class VirtualImageTests {
    /** 河海类群系：河与海，含全部温度档 */
    private static final List<String> RIVER_AND_OCEAN = List.of(
            "river",
            "frozen_river",
            "ocean",
            "deep_ocean",
            "warm_ocean",
            "lukewarm_ocean",
            "deep_lukewarm_ocean",
            "cold_ocean",
            "deep_cold_ocean",
            "frozen_ocean",
            "deep_frozen_ocean");

    /** 不在河海之列的群系：这里只抽两份有代表性的，确认它没有铺到全维度 */
    private static final List<String> NON_RIVER = List.of("plains", "forest");

    @GameTest(template = "ddt_empty")
    public static void spawnPlacementIsRegistered(GameTestHelper helper) {
        helper.assertTrue(
                SpawnPlacements.hasPlacement(ModEntities.VIRTUAL_IMAGE.get()),
                "virtual_image must have a spawn placement, otherwise natural spawning never asks its rules");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void riverAndOceanBiomesSpawnIt(GameTestHelper helper) {
        for (String biome : RIVER_AND_OCEAN) {
            helper.assertTrue(
                    isListed(helper, biome),
                    "dawnduskterminal:" + biome + " must list virtual_image in its monster spawners, got "
                            + monsters(helper, biome));
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void nonRiverBiomesStayClean(GameTestHelper helper) {
        for (String biome : NON_RIVER) {
            helper.assertFalse(
                    isListed(helper, biome),
                    "dawnduskterminal:" + biome + " must not list virtual_image, got " + monsters(helper, biome));
        }
        helper.succeed();
    }

    /**
     * 落点判定不读亮度：霞草上方就算摆满光源也照样放行
     *
     * <p>本维度的天光按坐标衰减，拿原版「越亮越不刷」去卡会把昼侧河岸整片卡掉。
     * 这里放一盏萤石把方块光顶满，判定仍须通过；若实现退回
     * {@code Monster.checkMonsterSpawnRules}，这条会立刻失败
     */
    @GameTest(template = "ddt_empty", skyAccess = true)
    public static void spawnIgnoresBrightness(GameTestHelper helper) {
        BlockPos ground = new BlockPos(5, 1, 5);
        helper.setBlock(ground, ModTerrainBlocks.ROSY_GRASS.get());
        helper.setBlock(ground.above(2), Blocks.GLOWSTONE);
        helper.assertTrue(
                VirtualImageEntity.checkVirtualImageSpawnRules(
                        ModEntities.VIRTUAL_IMAGE.get(),
                        helper.getLevel(),
                        MobSpawnType.NATURAL,
                        helper.absolutePos(ground.above()),
                        RandomSource.create()),
                "a fully lit rosy grass block must still be a valid spawn position");
        helper.succeed();
    }

    /**
     * 随机体型偏移按同一个比例缩放生命与攻击
     *
     * <p>基准与小型「弦」同档（生命 60、攻击 10），偏移 1.15 时分别是 69 与 11.5；
     * 碰撞箱宽度也跟着放大，否则「体型越大」只写在属性里、画面上看不出来
     */
    @GameTest(template = "ddt_empty")
    public static void statsScaleWithRandomSize(GameTestHelper helper) {
        VirtualImageEntity baseline = new VirtualImageEntity(ModEntities.VIRTUAL_IMAGE.get(), helper.getLevel());
        baseline.setSizeScale(1.0F);
        helper.assertTrue(
                close(baseline.getMaxHealth(), 60.0F),
                "base virtual image must have 60 health, got " + baseline.getMaxHealth());
        helper.assertTrue(
                close((float) baseline.getAttributeValue(Attributes.ATTACK_DAMAGE), 10.0F),
                "base virtual image must have 10 attack");

        VirtualImageEntity bigger = new VirtualImageEntity(ModEntities.VIRTUAL_IMAGE.get(), helper.getLevel());
        bigger.setSizeScale(1.15F);
        helper.assertTrue(
                close(bigger.getMaxHealth(), 69.0F),
                "the random size offset must scale health, got " + bigger.getMaxHealth());
        helper.assertTrue(
                close((float) bigger.getAttributeValue(Attributes.ATTACK_DAMAGE), 11.5F),
                "the random size offset must scale attack");
        helper.assertTrue(bigger.getBbWidth() > baseline.getBbWidth(), "a bigger individual must get a bigger hitbox");
        helper.succeed();
    }

    /** 免疫：击退、推动、药水与传送门流体的稳定伤害，与「弦」同一套 */
    @GameTest(template = "ddt_empty")
    public static void immunities(GameTestHelper helper) {
        VirtualImageEntity image = new VirtualImageEntity(ModEntities.VIRTUAL_IMAGE.get(), helper.getLevel());
        image.setSizeScale(1.0F);
        helper.assertTrue(
                !image.canBeAffected(new MobEffectInstance(MobEffects.POISON)),
                "virtual image must ignore potion effects");
        helper.assertTrue(
                image.isInvulnerableTo(helper.getLevel().damageSources().source(ModDamageTypes.STABILITY)),
                "portal fluid damage must not hurt the virtual image");
        helper.assertTrue(
                !image.isPushable() && !image.isPushedByFluid(),
                "virtual image must ignore entity pushing and fluid currents");
        helper.succeed();
    }

    private static boolean isListed(GameTestHelper helper, String biome) {
        return monsters(helper, biome).contains(DawnDuskTerminal.MOD_ID + ":virtual_image");
    }

    /** 这一份群系怪物表里的实体 id，只用于失败信息 */
    private static List<String> monsters(GameTestHelper helper, String biome) {
        Registry<Biome> biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        Biome registered = biomes.get(DawnDuskTerminal.id(biome));
        if (registered == null) {
            throw new IllegalStateException("the palette copy dawnduskterminal:" + biome + " must exist");
        }
        return registered.getMobSettings().getMobs(MobCategory.MONSTER).unwrap().stream()
                .map(data -> BuiltInRegistries.ENTITY_TYPE.getKey(data.type).toString())
                .toList();
    }

    private static boolean close(float value, float expected) {
        return Math.abs(value - expected) < 1.0E-3F;
    }

    private VirtualImageTests() {}
}
