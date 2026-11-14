package com.dawnduskterminal.gametest;

import com.dawnduskterminal.entity.LivingArmorEntity;
import com.dawnduskterminal.entity.SeekRainShelterGoal;
import com.dawnduskterminal.entity.StringEntity;
import com.dawnduskterminal.registry.ModDamageTypes;
import com.dawnduskterminal.registry.ModEffects;
import com.dawnduskterminal.registry.ModEntities;
import com.dawnduskterminal.registry.ModSounds;
import com.dawnduskterminal.world.ChronoLine;
import com.dawnduskterminal.world.ChronoPlantTints;
import com.dawnduskterminal.world.ChronoRain;
import com.dawnduskterminal.world.ChronoSkyLight;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 雨天的判定、伤害、贴图色相与躲雨几何
 *
 * <p>游戏测试跑在 {@code minecraft:overworld} 这个测试维度里，所以「这个维度下雨」那一条只能在
 * 反向断言：把雨硬打开，确认别的维度一点都不受影响（{@link #rainNeverAppliesOutsideChronoDimension}）。
 * 正向那一条（维度是晨昏线、露天、会下雨的群系）由纯函数与几何拼出来测：
 * 谁挨这一下、贴图的色相、避雨点的搜索、伤害与无击退
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class ChronoRainTests {
    /**
     * 雨丝与水花的贴图必须是传送门流体的色相
     *
     * <p>颜色只能在贴图里：那是 {@code tools/recolor_rain.py} 从原版复制之后逐像素转色相烘出来的。
     * 这里直接读进游戏的那几张 PNG，统计彩度够的像素的平均色相，与 {@link ChronoPlantTints#PURPLE}
     * 的色相对齐——8 位通道取整会挪零点几度，容差给 2°
     */
    @GameTest(template = "ddt_empty", batch = "chrono_rain")
    public static void rainTexturesCarryPortalHue(GameTestHelper helper) {
        double portalHue = hueDegrees(ChronoPlantTints.PURPLE);
        for (String path : new String[] {
            "/assets/dawnduskterminal/textures/environment/chrono_rain.png",
            "/assets/dawnduskterminal/textures/particle/chrono_splash_0.png",
            "/assets/dawnduskterminal/textures/particle/chrono_splash_2.png"
        }) {
            BufferedImage image = readImage(helper, path);
            double hue = meanHueDegrees(image);
            helper.assertFalse(Double.isNaN(hue), path + " 里找不到有彩度的像素");
            helper.assertTrue(
                    hueDistance(hue, portalHue) < 2.0D, path + " 的色相是 " + hue + "°，应贴着传送门流体的 " + portalHue + "°");
        }
        helper.succeed();
    }

    /**
     * 谁挨雨滴伤害：玩家一律挨，走地面的生物也挨，飞行的与水生的不挨，带稳定效果的免疫
     *
     * <p>玩家不豁免这一条是刻意的：玩家自己会看天气，豁免玩家等于开挂。
     * 飞行的与水生的不挨，是因为「躲雨」那套行为逻辑这一版不做，所以它们也不吃这一下
     */
    @GameTest(template = "ddt_empty", batch = "chrono_rain")
    public static void whoTakesRainDamage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombie must create");
        helper.assertTrue(ChronoRain.isWalkingMob(zombie), "僵尸走地面，必须算");
        helper.assertTrue(ChronoRain.takesRainDamage(zombie), "走地面的生物挨雨滴伤害");

        LivingArmorEntity armor = ModEntities.LIVING_ARMOR.get().create(level);
        helper.assertTrue(armor != null, "living armor must create");
        helper.assertTrue(ChronoRain.isWalkingMob(armor), "活盔甲走地面，必须算");
        helper.assertTrue(ChronoRain.takesRainDamage(armor), "本模组的走地生物也挨");

        Ghast ghast = EntityType.GHAST.create(level);
        helper.assertTrue(ghast != null, "ghast must create");
        helper.assertFalse(ChronoRain.isWalkingMob(ghast), "恶魂是飞行的，不算走地面");
        helper.assertFalse(ChronoRain.takesRainDamage(ghast), "飞行的不挨这一下");

        Squid squid = EntityType.SQUID.create(level);
        helper.assertTrue(squid != null, "squid must create");
        helper.assertFalse(ChronoRain.takesRainDamage(squid), "水生生物不挨这一下");

        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        helper.assertTrue(stand != null, "armor stand must create");
        helper.assertFalse(ChronoRain.takesRainDamage(stand), "不寻路的活物不挨这一下");

        StringEntity flyer = new StringEntity(ModEntities.STRING.get(), level);
        flyer.setKind(StringEntity.Kind.FLYER);
        helper.assertFalse(ChronoRain.takesRainDamage(flyer), "弦的飞行型不算走地面");

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        helper.assertTrue(ChronoRain.takesRainDamage(player), "玩家一律挨，滑翔与飞行也不豁免");

        // 唯一合法的豁免：稳定效果
        player.addEffect(new MobEffectInstance(ModEffects.STABILITY));
        helper.assertTrue(ChronoRain.isRainImmune(player), "稳定效果必须算免疫");
        helper.assertFalse(ChronoRain.takesRainDamage(player), "带稳定效果的玩家免疫雨天伤害");
        zombie.addEffect(new MobEffectInstance(ModEffects.STABILITY));
        helper.assertFalse(ChronoRain.takesRainDamage(zombie), "带稳定效果的生物免疫雨天伤害");
        helper.succeed();
    }

    /**
     * 别的维度下雨一点都不受影响
     *
     * <p>把雨硬开到最大，然后断言三件事都不成立：这个维度算不算雨天、露天的格子算不算淋雨、
     * 弦的飞行型算不算放开了流体约束。跑完把雨关回去，别影响别的用例
     */
    @GameTest(template = "ddt_empty", batch = "chrono_rain")
    public static void rainNeverAppliesOutsideChronoDimension(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        float previous = level.getRainLevel(1.0F);
        level.setRainLevel(1.0F);
        try {
            helper.assertTrue(level.isRaining(), "这条用例自己得先把雨打开");
            helper.assertFalse(ChronoRain.isRainy(level), "别的维度下雨不算这个维度的雨天");

            Zombie zombie = EntityType.ZOMBIE.create(level);
            helper.assertTrue(zombie != null, "zombie must create");
            BlockPos at = helper.absolutePos(new BlockPos(8, 1, 8));
            zombie.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 0.0F, 0.0F);
            helper.assertFalse(ChronoRain.isExposed(zombie), "别的维度的露天格子不能触发淋雨");
            helper.assertFalse(ChronoRain.shouldHurt(zombie), "别的维度不该扣雨滴伤害");

            StringEntity flyer = new StringEntity(ModEntities.STRING.get(), level);
            flyer.setKind(StringEntity.Kind.FLYER);
            helper.assertFalse(flyer.isRainFreeFlight(), "放开流体约束只在这个维度的雨天成立");
        } finally {
            level.setRainLevel(previous);
        }
        helper.succeed();
    }

    /**
     * 雨滴伤害：一点血、伤害类型是传送门流体的接触伤害、不产生击退、并且带那一声接触音
     *
     * <p>木桩用牛而不是僵尸：僵尸自带 2 点护甲（{@code Attributes.ARMOR}），原版的护甲减伤会把它
     * 折成 0.94。要断言「每一下正好 1 点」就得挑没有护甲的那一类，护甲减伤仍然按原版算，这里不改它
     *
     * <p>没有攻击者的伤害仍然会走 {@code LivingEntity#hurt} 里那段 {@code knockback(0.4, 0, 0)}，
     * 所以这里专门盯住速度：结算完必须还是原来那个数
     *
     * <p>声音这一侧只能钉住「那一下真的走完了 {@code playSound} 这条路且声音已注册」：
     * 服务端播声音没有可读的返回值，探针在专用服上听到的就是它（见 {@code PortalBlock#entityInside}）
     */
    @GameTest(template = "ddt_empty", batch = "chrono_rain")
    public static void rainContactIsPortalFluidDamageWithoutKnockback(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Cow cow = EntityType.COW.create(level);
        helper.assertTrue(cow != null, "cow must create");
        BlockPos at = helper.absolutePos(new BlockPos(4, 1, 4));
        cow.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 0.0F, 0.0F);
        level.addFreshEntity(cow);
        cow.setHealth(cow.getMaxHealth());

        helper.assertTrue(ModSounds.PORTAL_HURT.isBound(), "传送门流体的接触音必须已注册");
        Vec3 motion = new Vec3(0.25D, 0.0D, -0.25D);
        cow.setDeltaMovement(motion);
        float before = cow.getHealth();
        helper.assertTrue(ChronoRain.hurtInRain(cow), "雨滴伤害必须真的扣血");
        helper.assertTrue(
                Math.abs(cow.getHealth() - (before - ChronoRain.DAMAGE)) < 1.0E-4F,
                "每一下只能扣 " + ChronoRain.DAMAGE + " 点，实扣 " + (before - cow.getHealth())
                        + "（armor=" + cow.getArmorValue()
                        + " max=" + cow.getMaxHealth()
                        + "）");
        helper.assertTrue(
                cow.getLastDamageSource() != null && cow.getLastDamageSource().is(ModDamageTypes.STABILITY),
                "伤害类型必须是传送门流体的接触伤害 stability");
        helper.assertTrue(
                cow.getDeltaMovement().distanceToSqr(motion) < 1.0E-9D,
                "无击退：结算完速度必须原样放回，实际是 " + cow.getDeltaMovement());
        helper.succeed();
    }

    /** 对 {@code stability} 免疫的生物挨不到这一下：弦的那几种都写着免疫 */
    @GameTest(template = "ddt_empty", batch = "chrono_rain")
    public static void immuneMobTakesNoRainDamage(GameTestHelper helper) {
        StringEntity string = new StringEntity(ModEntities.STRING.get(), helper.getLevel());
        string.setVariant(false, 1.0F);
        string.setHealth(string.getMaxHealth());
        float before = string.getHealth();
        helper.assertFalse(ChronoRain.hurtInRain(string), "弦免疫传送门流体的伤害，淋雨也不该扣血");
        helper.assertTrue(string.getHealth() == before, "免疫的那一套必须挡住整下伤害");
        helper.succeed();
    }

    /**
     * 避雨点的搜索：屋顶下的格子算，露天的格子不算，八格之内找得到
     *
     * <p>测试结构是空的，所以先铺一层地面，再在它上面搭一块 3×3 的屋顶、屋顶与地面之间留两格空腔，
     * 然后从八格半径之内往回找
     */
    @GameTest(template = "ddt_empty", batch = "chrono_rain", skyAccess = true)
    public static void shelterSearchFindsCover(GameTestHelper helper) {
        for (int dx = 0; dx < 9; dx++) {
            for (int dz = 0; dz < 9; dz++) {
                helper.setBlock(new BlockPos(2 + dx, 0, 2 + dz), Blocks.STONE);
            }
        }
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                helper.setBlock(new BlockPos(3 + dx, 4, 3 + dz), Blocks.STONE);
            }
        }
        // 遮挡判定走高度图，放下方块那一 tick 就更新，不必等天光
        ServerLevel level = helper.getLevel();
        BlockPos openSky = helper.absolutePos(new BlockPos(9, 1, 9));
        helper.assertFalse(SeekRainShelterGoal.isShelteredAt(level, openSky), "头顶是天空的格子不能算避雨点");
        BlockPos underRoof = helper.absolutePos(new BlockPos(4, 1, 4));
        helper.assertTrue(SeekRainShelterGoal.isShelteredAt(level, underRoof), "屋顶正下方的格子必须算避雨点");

        BlockPos found = SeekRainShelterGoal.findShelter(level, openSky, 8);
        helper.assertTrue(found != null, "八格之内必须找得到屋顶下的落脚点");
        helper.assertFalse(ChronoRain.isOpenSky(level, found), "找到的落脚点头顶必须有遮挡");
        helper.assertTrue(level.isEmptyBlock(found) && level.isEmptyBlock(found.above()), "落脚点要站得进去");
        helper.succeed();
    }

    /**
     * 天光被压到 15 以下，所以淋雨判定必须走高度图，不能借 {@code isRainingAt}/{@code canSeeSky}
     *
     * <p>原版 {@code Level#isRainingAt} 先问 {@code canSeeSky}，而 {@code canSeeSky} 判的是「天光 ≥ 15」。
     * 这个维度的天光被 {@code LightEngineSkyLightMixin} 按晨昏线压到 12~15（默认中心 14），
     * 于是露天处 {@code canSeeSky} 也是假——拿原版判定当条件，雨天会永远不生效。
     *
     * <p>测试服不会创建晨昏线维度（{@code GameTestServer} 只装原版那几个 LevelStem），所以分两半钉：
     * 天光上限由 {@link ChronoSkyLight} 的纯函数给出；「露天 = 高度图」在测试维度里搭一块屋顶直接断言
     */
    @GameTest(template = "ddt_empty", batch = "chrono_rain", skyAccess = true)
    public static void chronoRainIgnoresSkyLightCap(GameTestHelper helper) {
        int cap = ChronoSkyLight.maxSkyLight(ChronoLine.fallback(), 0, 0);
        helper.assertTrue(cap < 15, "晨昏线上的天光上限是 " + cap + "，必须低于 15；露天处 canSeeSky 因此为假");

        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                helper.setBlock(new BlockPos(3 + dx, 4, 3 + dz), Blocks.STONE);
            }
        }
        ServerLevel level = helper.getLevel();
        BlockPos openSky = helper.absolutePos(new BlockPos(9, 1, 9));
        BlockPos underRoof = helper.absolutePos(new BlockPos(4, 1, 4));
        helper.assertTrue(ChronoRain.isOpenSky(level, openSky), "露天格子在高度图上必须算露天");
        helper.assertFalse(ChronoRain.isOpenSky(level, underRoof), "屋顶下的格子在高度图上必须算有遮挡");
        helper.succeed();
    }

    /** 读一张打进模组资源的 PNG */
    private static BufferedImage readImage(GameTestHelper helper, String resource) {
        try (InputStream stream = ChronoRainTests.class.getResourceAsStream(resource)) {
            helper.assertTrue(stream != null, resource + " 必须打进模组资源");
            BufferedImage image = ImageIO.read(stream);
            helper.assertTrue(image != null, resource + " 必须是可读的 PNG");
            return image;
        } catch (IOException problem) {
            throw new AssertionError("读取 " + resource + " 失败", problem);
        }
    }

    /**
     * 一张贴图里彩度够的像素的平均色相，单位是度
     *
     * <p>灰像素的色相是算出来的噪声，不参与统计；一个都没有时返回 {@link Double#NaN}
     */
    private static double meanHueDegrees(BufferedImage image) {
        double sum = 0.0D;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if ((argb >>> 24) < 128) {
                    continue;
                }
                double[] hsv = hsv(argb & 0xFFFFFF);
                if (hsv[1] < 0.05D) {
                    continue;
                }
                sum += hsv[0];
                count++;
            }
        }
        return count == 0 ? Double.NaN : sum / count;
    }

    /** {@code 0xRRGGBB} 的色相，单位是度 */
    private static double hueDegrees(int rgb) {
        return hsv(rgb)[0];
    }

    /** {@code 0xRRGGBB} 的 HSV，色相是度，饱和度与明度是 0~1 */
    private static double[] hsv(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0D;
        double g = ((rgb >> 8) & 0xFF) / 255.0D;
        double b = (rgb & 0xFF) / 255.0D;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double delta = max - min;
        double hue = 0.0D;
        if (delta > 1.0E-9D) {
            if (max == r) {
                hue = 60.0D * (((g - b) / delta) % 6.0D);
            } else if (max == g) {
                hue = 60.0D * (((b - r) / delta) + 2.0D);
            } else {
                hue = 60.0D * (((r - g) / delta) + 4.0D);
            }
        }
        if (hue < 0.0D) {
            hue += 360.0D;
        }
        double saturation = max <= 1.0E-9D ? 0.0D : delta / max;
        return new double[] {hue, saturation, max};
    }

    /** 色相环上的最短距离，单位是度 */
    private static double hueDistance(double a, double b) {
        double diff = Math.abs(a - b) % 360.0D;
        return Math.min(diff, 360.0D - diff);
    }
}
