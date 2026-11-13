package com.dawnduskterminal.gametest;

import com.dawnduskterminal.registry.ModEffects;
import com.dawnduskterminal.registry.ModItems;
import com.dawnduskterminal.registry.ModPotions;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.FastColor;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 稳定药水：物品默认栈自带药水内容，喷溅型与滞留型都自带本型（3 分钟）稳定
 *
 * <p>默认药水内容不是写在 {@code Item.Properties} 里的，而是 {@code ModifyDefaultComponentsEvent} 事后补的
 * （物品注册早于药水）。那条补丁没生效时，{@code new ItemStack(item)} 拿到的是一瓶没有内容的药水：
 * 喝下去什么都不发生，名字显示成未翻译的键，而创造栏里看起来只是一只白瓶子 —— 所以前三个用例从物品栈往回查
 *
 * <p>后两个用例走的是 {@code PotionBrewing}：炼药配方没有数据包形式，只能在
 * {@code RegisterBrewingRecipesEvent} 里补（见 {@link ModPotions#registerBrewingRecipes}）。
 * 事件没挂上时不报错，只是酿造台对这瓶药一副「不认识」的样子，所以这里把四条链逐一酿一遍
 *
 * <p>最后一个用例问的是「扔出去算不算滞留瓶」：原版按物品本身判 {@code Items.LINGERING_POTION}，
 * 模组这瓶要靠 {@code mixin/ThrownPotionLingeringMixin} 放宽，漏了它这瓶只会按喷溅结算
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class StabilityPotionTests {
    /** 传送门流体的雾色，也是稳定效果的颜色 {@code StabilityEffect} */
    private static final int PORTAL_PURPLE = 0x7F159E;

    @GameTest(template = "ddt_empty")
    public static void potionIsThreeMinutesOfStability(GameTestHelper helper) {
        assertDefaultContents(helper, ModItems.STABILITY_POTION.get(), ModPotions.STABILITY, 3 * 60 * 20);
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void splashPotionDefaultsToThreeMinutesOfStability(GameTestHelper helper) {
        assertDefaultContents(helper, ModItems.STABILITY_SPLASH_POTION.get(), ModPotions.STABILITY, 3 * 60 * 20);
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void lingeringPotionDefaultsToThreeMinutesOfStability(GameTestHelper helper) {
        assertDefaultContents(helper, ModItems.STABILITY_LINGERING_POTION.get(), ModPotions.STABILITY, 3 * 60 * 20);
        helper.succeed();
    }

    /** 红石把本型推到 8 分钟：两个药水都登记了，延长那一条才成立 */
    @GameTest(template = "ddt_empty")
    public static void redstoneExtendsStabilityToEightMinutes(GameTestHelper helper) {
        PotionBrewing brewing = helper.getLevel().potionBrewing();
        ItemStack base = bottle(Items.POTION, ModPotions.STABILITY);
        ItemStack redstone = new ItemStack(Items.REDSTONE);
        helper.assertTrue(
                brewing.hasMix(base, redstone), "the brewing stand must accept redstone on the stability potion");

        PotionContents contents = mixContents(brewing, redstone, base);
        helper.assertTrue(
                contents.is(ModPotions.LONG_STABILITY),
                "redstone must brew dawnduskterminal:long_stability, got " + contents.potion());
        helper.assertTrue(
                singleEffect(helper, contents).getDuration() == 8 * 60 * 20,
                "the extended potion must last 8 minutes (9600 ticks)");
        helper.succeed();
    }

    /** 繁缕菜是起手原料；火药与龙息负责两种瓶型，与原版同一套规则 */
    @GameTest(template = "ddt_empty")
    public static void chickweedBrewsAndContainerRulesApply(GameTestHelper helper) {
        PotionBrewing brewing = helper.getLevel().potionBrewing();
        ItemStack veges = new ItemStack(ModTerrainBlocks.STELLARIA_VEGES_ITEM.get());
        helper.assertTrue(brewing.isIngredient(veges), "stellaria_veges must count as a brewing ingredient");

        // 水瓶子 + 繁缕菜 → 平凡药水（原版的失败分支，与糖、岩浆膏那批一致）
        ItemStack water = bottle(Items.POTION, Potions.WATER);
        helper.assertTrue(brewing.hasMix(water, veges), "water + stellaria_veges must be a recognised mix");
        PotionContents mundane = mixContents(brewing, veges, water);
        helper.assertTrue(
                mundane.is(Potions.MUNDANE),
                "water + stellaria_veges must brew minecraft:mundane, got " + mundane.potion());

        // 粗制的药水 + 繁缕菜 → 稳定药水（本型），产物落到模组自己那瓶上
        ItemStack awkward = bottle(Items.POTION, Potions.AWKWARD);
        helper.assertTrue(brewing.hasMix(awkward, veges), "awkward + stellaria_veges must be a recognised mix");
        ItemStack brewed = brewing.mix(veges, awkward);
        helper.assertTrue(
                brewed.is(ModItems.STABILITY_POTION.get()),
                "awkward + stellaria_veges must brew dawnduskterminal:stability_potion, got " + brewed);
        PotionContents stability = contents(brewed);
        helper.assertTrue(
                stability.is(ModPotions.STABILITY),
                "the brewed potion must carry dawnduskterminal:stability, got " + stability.potion());
        helper.assertTrue(
                singleEffect(helper, stability).getDuration() == 3 * 60 * 20,
                "the brewed potion must last 3 minutes (3600 ticks)");

        // 火药：普通瓶 → 喷溅瓶，药水内容不变
        ItemStack gunpowder = new ItemStack(Items.GUNPOWDER);
        helper.assertTrue(brewing.hasMix(brewed, gunpowder), "gunpowder must be accepted on the stability potion");
        ItemStack splash = brewing.mix(gunpowder, brewed);
        helper.assertTrue(
                splash.is(ModItems.STABILITY_SPLASH_POTION.get()),
                "gunpowder must brew dawnduskterminal:stability_splash_potion, got " + splash);
        helper.assertTrue(
                contents(splash).is(ModPotions.STABILITY), "gunpowder must keep the potion contents of the input");

        // 龙息：喷溅瓶 → 滞留瓶
        ItemStack dragonBreath = new ItemStack(Items.DRAGON_BREATH);
        helper.assertTrue(brewing.hasMix(splash, dragonBreath), "dragon breath must be accepted on the splash potion");
        ItemStack lingering = brewing.mix(dragonBreath, splash);
        helper.assertTrue(
                lingering.is(ModItems.STABILITY_LINGERING_POTION.get()),
                "dragon breath must brew dawnduskterminal:stability_lingering_potion, got " + lingering);
        helper.assertTrue(contents(lingering).is(ModPotions.STABILITY), "dragon breath must keep the potion contents");

        // 三种瓶型都要能放进酿造台下面三个槽，否则配方在界面上根本摆不进去
        for (Item bottle : List.of(
                ModItems.STABILITY_POTION.get(),
                ModItems.STABILITY_SPLASH_POTION.get(),
                ModItems.STABILITY_LINGERING_POTION.get())) {
            helper.assertTrue(
                    brewing.isInput(new ItemStack(bottle)),
                    bottle.getDescriptionId() + " must be accepted as a brewing input");
        }
        helper.succeed();
    }

    /**
     * 滞留型真的要留药水云，喷溅型不能被一起算成滞留型
     *
     * <p>{@code ThrownPotion#isLingering} 是私有的，落地那条路又得跑一遍物理才看得到，
     * 所以这里只反射问它一句：答案对了，{@code onHit} 就走 {@code makeAreaOfEffectCloud}
     */
    @GameTest(template = "ddt_empty")
    public static void lingeringPotionStillLeavesACloud(GameTestHelper helper) {
        helper.assertTrue(
                throwsAsLingering(helper, ModItems.STABILITY_LINGERING_POTION.get()),
                "the lingering stability potion must throw a lingering cloud, not a splash; "
                        + "check mixin/ThrownPotionLingeringMixin");
        helper.assertTrue(
                !throwsAsLingering(helper, ModItems.STABILITY_SPLASH_POTION.get()),
                "the splash stability potion must stay a splash potion");
        helper.succeed();
    }

    private static boolean throwsAsLingering(GameTestHelper helper, Item item) {
        ThrownPotion potion = new ThrownPotion(helper.getLevel(), 0.0, 0.0, 0.0);
        potion.setItem(new ItemStack(item));
        try {
            Method isLingering = ThrownPotion.class.getDeclaredMethod("isLingering");
            isLingering.setAccessible(true);
            return (boolean) isLingering.invoke(potion);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("ThrownPotion#isLingering 调不到", error);
        }
    }

    /** 默认栈的内容、时长、等级与颜色：颜色是可见效果的加权平均，只有一条效果时就是传送门那个紫 */
    private static void assertDefaultContents(
            GameTestHelper helper, Item item, Holder<Potion> expected, int expectedTicks) {
        PotionContents contents = contents(new ItemStack(item));
        helper.assertTrue(
                contents.is(expected),
                item.getDescriptionId() + " must default to the expected potion, got " + contents.potion());

        MobEffectInstance effect = singleEffect(helper, contents);
        helper.assertTrue(
                effect.getEffect().value() == ModEffects.STABILITY.get(),
                "the effect must be dawnduskterminal:stability, got " + effect.getEffect());
        helper.assertTrue(
                effect.getDuration() == expectedTicks,
                "the effect must last " + expectedTicks / 20 + " seconds (" + expectedTicks + " ticks), got "
                        + effect.getDuration());
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
                effects.size() == 1, "the stability potion must carry exactly one effect, got " + effects.size());
        return effects.get(0);
    }

    private static PotionContents contents(ItemStack stack) {
        return stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
    }

    /**
     * 酿造台里一瓶药水的内容：{@code PotionBrewing#mix} 的参数顺序是「上格的原料、下面的药水瓶」，
     * 与 {@code hasMix} 的「药水瓶、原料」正好相反，调用点（{@code BrewingStandBlockEntity#doBrew}）也是这么传的
     */
    private static PotionContents mixContents(PotionBrewing brewing, ItemStack ingredient, ItemStack bottle) {
        return contents(brewing.mix(ingredient, bottle));
    }

    private static ItemStack bottle(Item item, Holder<Potion> potion) {
        return PotionContents.createItemStack(item, potion);
    }

    private StabilityPotionTests() {}
}
