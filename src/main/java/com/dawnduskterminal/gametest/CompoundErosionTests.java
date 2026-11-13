package com.dawnduskterminal.gametest;

import com.dawnduskterminal.registry.ModDamageTypes;
import com.dawnduskterminal.registry.ModEffects;
import com.dawnduskterminal.registry.ModItems;
import com.dawnduskterminal.registry.ModPotions;
import com.dawnduskterminal.registry.ModSounds;
import com.dawnduskterminal.world.CompoundErosionEffect;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.FastColor;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 复方侵蚀药水：稳定药水加一罐不稳定液体压出来的那瓶
 *
 * <p>本型 45 秒、延长型 1 分 30 秒、喷溅型与滞留型，四种形态的默认内容、时长、颜色、酿造链与那条合成配方
 * 都在这里钉住。颜色要与稳定药水完全一样（{@link #PORTAL_PURPLE}）：两瓶只差在喝下去之后。
 *
 * <p>最后一例钉的是效果本身：每 2 秒（40 tick）一下 3 点，伤害类型与泡在不稳定液体里那一下相同
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class CompoundErosionTests {
    /** 传送门流体的雾色，也是稳定与复方侵蚀两个效果的颜色 */
    private static final int PORTAL_PURPLE = 0x7F159E;

    @GameTest(template = "ddt_empty")
    public static void potionIsFortyFiveSecondsOfErosion(GameTestHelper helper) {
        assertDefaultContents(helper, ModItems.COMPOUND_EROSION_POTION.get());
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void splashPotionDefaultsToFortyFiveSecondsOfErosion(GameTestHelper helper) {
        assertDefaultContents(helper, ModItems.COMPOUND_EROSION_SPLASH_POTION.get());
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void lingeringPotionDefaultsToFortyFiveSecondsOfErosion(GameTestHelper helper) {
        assertDefaultContents(helper, ModItems.COMPOUND_EROSION_LINGERING_POTION.get());
        helper.succeed();
    }

    /** 两瓶药水的颜色必须一模一样：只看瓶子分不出哪瓶是「稳定」、哪瓶是「复方侵蚀」 */
    @GameTest(template = "ddt_empty")
    public static void colourMatchesTheStabilityPotion(GameTestHelper helper) {
        int stability = contents(new ItemStack(ModItems.STABILITY_POTION.get())).getColor();
        int erosion =
                contents(new ItemStack(ModItems.COMPOUND_EROSION_POTION.get())).getColor();
        helper.assertTrue(
                stability == erosion,
                "both potions must render in the same colour, got 0x" + Integer.toHexString(stability) + " and 0x"
                        + Integer.toHexString(erosion));
        helper.succeed();
    }

    /** 红石把 45 秒推到 1 分 30 秒 */
    @GameTest(template = "ddt_empty")
    public static void redstoneExtendsToNinetySeconds(GameTestHelper helper) {
        PotionBrewing brewing = helper.getLevel().potionBrewing();
        ItemStack redstone = new ItemStack(Items.REDSTONE);
        ItemStack base = new ItemStack(ModItems.COMPOUND_EROSION_POTION.get());
        helper.assertTrue(brewing.hasMix(base, redstone), "redstone must be accepted on the erosion potion");

        ItemStack extended = brewing.mix(redstone, base);
        PotionContents contents = contents(extended);
        helper.assertTrue(
                contents.is(ModPotions.LONG_COMPOUND_EROSION),
                "redstone must brew dawnduskterminal:long_compound_erosion, got " + contents.potion());
        helper.assertTrue(
                singleEffect(helper, contents).getDuration() == 90 * 20,
                "the extended potion must last 1 minute 30 seconds (1800 ticks)");
        helper.succeed();
    }

    /** 火药换喷溅瓶、龙息换滞留瓶，药水内容原样带过去 */
    @GameTest(template = "ddt_empty")
    public static void gunpowderAndDragonBreathSwapTheBottle(GameTestHelper helper) {
        PotionBrewing brewing = helper.getLevel().potionBrewing();
        ItemStack plain = new ItemStack(ModItems.COMPOUND_EROSION_POTION.get());

        ItemStack gunpowder = new ItemStack(Items.GUNPOWDER);
        helper.assertTrue(brewing.hasMix(plain, gunpowder), "gunpowder must be accepted on the erosion potion");
        ItemStack splash = brewing.mix(gunpowder, plain);
        helper.assertTrue(
                splash.is(ModItems.COMPOUND_EROSION_SPLASH_POTION.get()),
                "gunpowder must brew dawnduskterminal:compound_erosion_splash_potion, got " + splash);
        helper.assertTrue(contents(splash).is(ModPotions.COMPOUND_EROSION), "gunpowder must keep the potion contents");

        ItemStack dragonBreath = new ItemStack(Items.DRAGON_BREATH);
        helper.assertTrue(brewing.hasMix(splash, dragonBreath), "dragon breath must be accepted on the splash potion");
        ItemStack lingering = brewing.mix(dragonBreath, splash);
        helper.assertTrue(
                lingering.is(ModItems.COMPOUND_EROSION_LINGERING_POTION.get()),
                "dragon breath must brew dawnduskterminal:compound_erosion_lingering_potion, got " + lingering);
        helper.assertTrue(
                contents(lingering).is(ModPotions.COMPOUND_EROSION), "dragon breath must keep the potion contents");

        for (Item bottle : List.of(
                ModItems.COMPOUND_EROSION_POTION.get(),
                ModItems.COMPOUND_EROSION_SPLASH_POTION.get(),
                ModItems.COMPOUND_EROSION_LINGERING_POTION.get())) {
            helper.assertTrue(
                    brewing.isInput(new ItemStack(bottle)),
                    bottle.getDescriptionId() + " must be accepted as a brewing input");
        }
        helper.succeed();
    }

    /**
     * 酿造台：稳定药水 + 一罐不稳定液体 → 复方侵蚀药水，空罐退回原料槽
     *
     * <p>两档各出各的，与原版的 {@code long_*} 一样参与后续酿造：
     * 本型 3 分钟 → 本型 45 秒，延长型 8 分钟 → 延长型 1 分 30 秒。
     * 产物是模组自己那瓶（{@code compound_erosion_potion}），不是「稳定药水装着复方侵蚀」——
     * 那正是这条自定义配方存在的理由
     *
     * <p>空罐那半条钉的是这两桶流体的剩余物：{@code item/CanocanBucketItem} 覆写了物品栈敏感的钩子，
     * NeoForge 补过的 {@code BrewingStandBlockEntity#doBrew} 与合成都认它，
     * 退回来的是苍穹罐（{@code canocan}），不是铁桶
     */
    @GameTest(template = "ddt_empty")
    public static void portalBucketBrewsTheErosionPotion(GameTestHelper helper) {
        PotionBrewing brewing = helper.getLevel().potionBrewing();
        ItemStack bucket = new ItemStack(ModItems.PORTAL_BUCKET.get());
        helper.assertTrue(brewing.isIngredient(bucket), "the portal bucket must count as a brewing ingredient");
        helper.assertTrue(
                bucket.getCraftingRemainingItem().is(ModItems.CANOCAN.get()),
                "the portal bucket must hand the canopy can back, got " + bucket.getCraftingRemainingItem());

        for (boolean extended : new boolean[] {false, true}) {
            ItemStack bottle = new ItemStack(ModItems.STABILITY_POTION.get());
            if (extended) {
                bottle.set(DataComponents.POTION_CONTENTS, new PotionContents(ModPotions.LONG_STABILITY));
            }
            String tier = extended ? "the eight-minute stability potion" : "the three-minute stability potion";
            helper.assertTrue(brewing.hasMix(bottle, bucket), tier + " must accept a portal bucket");

            ItemStack brewed = brewing.mix(bucket, bottle);
            helper.assertTrue(
                    brewed.is(ModItems.COMPOUND_EROSION_POTION.get()),
                    tier + " must brew dawnduskterminal:compound_erosion_potion, got " + brewed);
            PotionContents contents = contents(brewed);
            helper.assertTrue(
                    contents.is(extended ? ModPotions.LONG_COMPOUND_EROSION : ModPotions.COMPOUND_EROSION),
                    tier + " must brew the matching tier, got " + contents.potion());
            helper.assertTrue(
                    singleEffect(helper, contents).getDuration() == (extended ? 90 : 45) * 20,
                    tier + " must keep its tier: expected " + (extended ? 90 : 45) + " seconds, got "
                            + singleEffect(helper, contents).getDuration() + " ticks");
        }
        helper.succeed();
    }

    /**
     * 效果本身：每 2 秒 3 点，伤害类型是传送门流体的接触伤害，音效已注册
     *
     * <p>节拍按剩余时长取 40 的倍数（与 {@code PortalSicknessEffect} 同一条写法），
     * 而 45 秒的 900 tick 不是 40 的倍数，所以第一下落在 880 tick，也就是喝下 1 秒之后
     *
     * <p>服务端播声音没有可读的返回值，这里只能钉住「那一声确实已注册」——
     * 与 {@code ChronoRainTests} 里雨天那一下同一个做法
     */
    @GameTest(template = "ddt_empty")
    public static void erosionTicksEveryTwoSecondsForThreePoints(GameTestHelper helper) {
        CompoundErosionEffect erosion = (CompoundErosionEffect) ModEffects.COMPOUND_EROSION.get();
        helper.assertTrue(
                !erosion.shouldApplyEffectTickThisTick(39, 0), "the effect must not tick one tick before the interval");
        helper.assertTrue(erosion.shouldApplyEffectTickThisTick(40, 0), "the effect must tick every 40 ticks");
        helper.assertTrue(
                !erosion.shouldApplyEffectTickThisTick(900, 0),
                "900 is not a multiple of the interval, so the very first tick cannot land on the full duration");
        helper.assertTrue(
                erosion.shouldApplyEffectTickThisTick(880, 0),
                "the first hit must land on the first multiple of the interval below the duration");
        helper.assertTrue(ModSounds.PORTAL_HURT.isBound(), "the portal hurt sound must be registered");

        ServerLevel level = helper.getLevel();
        Cow cow = EntityType.COW.create(level);
        helper.assertTrue(cow != null, "cow must create");
        // 只摆一只不进世界的牛：伤害、伤害类型与音效都用不到区块刻。
        // 这个模板是与其他用例共用的那一份，往里放活物会去挡别人的活盔甲、链锤
        cow.setHealth(cow.getMaxHealth());

        float before = cow.getHealth();
        helper.assertTrue(erosion.applyEffectTick(cow, 0), "the effect must keep ticking");
        helper.assertTrue(
                Math.abs(cow.getHealth() - (before - CompoundErosionEffect.DAMAGE)) < 1.0E-4F,
                "each tick must deal " + CompoundErosionEffect.DAMAGE + " points, lost " + (before - cow.getHealth()));
        helper.assertTrue(
                cow.getLastDamageSource() != null && cow.getLastDamageSource().is(ModDamageTypes.STABILITY),
                "the damage type must be the portal fluid contact one (stability)");
        helper.succeed();
    }

    /** 默认栈的内容、时长、等级与颜色 */
    private static void assertDefaultContents(GameTestHelper helper, Item item) {
        PotionContents contents = contents(new ItemStack(item));
        helper.assertTrue(
                contents.is(ModPotions.COMPOUND_EROSION),
                item.getDescriptionId() + " must default to dawnduskterminal:compound_erosion, got "
                        + contents.potion());

        MobEffectInstance effect = singleEffect(helper, contents);
        helper.assertTrue(
                effect.getEffect().value() == ModEffects.COMPOUND_EROSION.get(),
                "the effect must be dawnduskterminal:compound_erosion, got " + effect.getEffect());
        helper.assertTrue(
                effect.getDuration() == 45 * 20,
                "the effect must last 45 seconds (900 ticks), got " + effect.getDuration());
        helper.assertTrue(
                effect.getAmplifier() == 0, "the effect must be level I, got amplifier " + effect.getAmplifier());
        helper.assertTrue(
                contents.getColor() == FastColor.ARGB32.opaque(PORTAL_PURPLE),
                "the potion must render in the portal purple 0x7F159E, got 0x"
                        + Integer.toHexString(contents.getColor()));
    }

    private static MobEffectInstance singleEffect(GameTestHelper helper, PotionContents contents) {
        List<MobEffectInstance> effects = new ArrayList<>();
        contents.forEachEffect(effects::add);
        helper.assertTrue(
                effects.size() == 1, "the erosion potion must carry exactly one effect, got " + effects.size());
        return effects.get(0);
    }

    private static PotionContents contents(ItemStack stack) {
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
    }

    private CompoundErosionTests() {}
}
