package com.dawnduskterminal.gametest;

import com.dawnduskterminal.entity.GhoflowerProjectile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 鬼花射弹的伤害曲线
 *
 * <p>钉住三件事：两种射弹在晨昏线上都是 10 点；昼版朝昼侧升到 30 点、朝夜侧降到 0 点；
 * 夜版与昼版按晨昏线镜像对称。
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class GhoflowerProjectileTests {
    @GameTest(template = "ddt_empty", batch = "ghoflower_projectile")
    public static void dayDamageRisesTowardsTheDaySide(GameTestHelper helper) {
        assertDamage(helper, false, 0.0D, 10.0F);
        assertDamage(helper, false, 1.0D, 30.0F);
        assertDamage(helper, false, -1.0D, 0.0F);
        assertDamage(helper, false, 0.5D, 20.0F);
        assertDamage(helper, false, -0.5D, 5.0F);
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "ghoflower_projectile")
    public static void nightDamageMirrorsTheDayCurve(GameTestHelper helper) {
        assertDamage(helper, true, 0.0D, 10.0F);
        assertDamage(helper, true, -1.0D, 30.0F);
        assertDamage(helper, true, 1.0D, 0.0F);
        assertDamage(helper, true, -0.5D, 20.0F);
        assertDamage(helper, true, 0.5D, 5.0F);
        helper.succeed();
    }

    private static void assertDamage(GameTestHelper helper, boolean night, double param, float expected) {
        float actual = GhoflowerProjectile.damageAt(night, param);
        helper.assertTrue(
                Math.abs(actual - expected) < 1.0E-6F,
                "the " + (night ? "night" : "day") + " projectile at param " + param + " must deal " + expected
                        + ", got " + actual);
    }

    private GhoflowerProjectileTests() {}
}
