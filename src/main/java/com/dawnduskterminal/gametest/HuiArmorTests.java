package com.dawnduskterminal.gametest;

import com.dawnduskterminal.registry.ModEquipment;
import com.dawnduskterminal.registry.ModExperimentItems;
import com.dawnduskterminal.world.HuiArmor;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import twilightforest.init.TFBlocks;

@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class HuiArmorTests {
    private static Player armored(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        var items = ModEquipment.sets().get("hui").items();
        var slots =
                new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < slots.length; i++)
            player.setItemSlot(slots[i], items.get(i + 5).toStack());
        var pos = helper.absolutePos(new BlockPos(2, 2, 2));
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        helper.getLevel().setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        return player;
    }

    private static void ticks(Player player, int count) {
        for (int i = 0; i < count; i++) HuiArmor.tick(player);
    }

    private static void hit(Player player, float damage) {
        player.invulnerableTime = 0;
        player.hurt(player.damageSources().genericKill(), damage);
    }

    @GameTest(template = "ddt_empty")
    public static void huiHealthThresholds(GameTestHelper helper) {
        Player player = armored(helper);
        player.setHealth(9);
        player.getFoodData().setFoodLevel(10);
        player.getFoodData().setSaturation(0);
        ticks(player, 10);
        helper.assertTrue(
                player.getHealth() == 10 && player.getFoodData().getExhaustionLevel() == 2, "low health recovery");
        ticks(player, 19);
        helper.assertTrue(player.getHealth() == 10, "half health requires a full second");
        ticks(player, 1);
        helper.assertTrue(
                player.getHealth() == 11 && player.getFoodData().getExhaustionLevel() == 6,
                "equal percentages recover health");
        player.getFoodData().setFoodLevel(1);
        ticks(player, 40);
        helper.assertTrue(
                player.getHealth() == 10 && player.getFoodData().getFoodLevel() == 3,
                "two hunger restorations cost one health");
        player.getFoodData().setFoodLevel(0);
        ticks(player, 4);
        helper.assertTrue(player.getHealth() == 9, "four empty hunger ticks cost one health");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void huiShieldAndRescue(GameTestHelper helper) {
        Player player = armored(helper);
        player.getFoodData().setFoodLevel(19);
        player.getFoodData().setSaturation(17);
        ItemStack meal = ModExperimentItems.EXPERIMENT_020.toStack();
        player.eat(player.level(), meal, meal.getFoodProperties(player));
        helper.assertTrue(HuiArmor.visibleShield(player) == 3, "only actual saturation gain counts");
        player.setHealth(2.5F);
        player.getFoodData().setFoodLevel(10);
        player.getFoodData().setSaturation(0);
        hit(player, 5);
        helper.assertTrue(player.getHealth() == 0.5F && !HuiArmor.downed(player), "fractional health remains alive");
        hit(player, 3);
        helper.assertTrue(
                HuiArmor.downed(player) && player.getHealth() == HuiArmor.DOWNED_HEALTH, "lethal hit delays death");
        player.heal(20);
        hit(player, 0.5F);
        hit(player, 0.5F);
        helper.assertTrue(
                player.getFoodData().getFoodLevel() == 9 && player.getHealth() == HuiArmor.DOWNED_HEALTH,
                "damage redirects with fractional carry");
        ticks(player, 4);
        helper.assertTrue(player.getFoodData().getFoodLevel() == 8, "downed hunger drains every four ticks");
        player.getFoodData().setFoodLevel(20);
        helper.assertTrue(player.canEat(false), "ordinary food is usable at full hunger while downed");
        meal = ModExperimentItems.EXPERIMENT_020.toStack();
        player.eat(player.level(), meal, meal.getFoodProperties(player));
        helper.assertTrue(
                !HuiArmor.downed(player)
                        && player.getHealth() == 2
                        && player.getFoodData().getFoodLevel() == 6
                        && player.getFoodData().getSaturationLevel() == 0
                        && HuiArmor.visibleShield(player) == 20,
                "020 rescues and fills life saturation");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void huiEatingAndArmorBreak(GameTestHelper helper) {
        Player player = armored(helper);
        hit(player, 100);
        player.setItemInHand(InteractionHand.MAIN_HAND, ModExperimentItems.EXPERIMENT_020.toStack());
        player.startUsingItem(InteractionHand.MAIN_HAND);
        ticks(player, 19);
        helper.assertTrue(player.getFoodData().getFoodLevel() == 20, "eating slows the downed drain");
        player.stopUsingItem();
        ticks(player, 1);
        helper.assertTrue(player.getFoodData().getFoodLevel() == 19, "interrupting a meal preserves progress");
        player.getFoodData().setFoodLevel(1);
        ticks(player, 4);
        helper.assertTrue(!player.isAlive(), "zero hunger ends the downed state");

        Player other = armored(helper);
        ItemStack boots = other.getItemBySlot(EquipmentSlot.FEET);
        boots.setDamageValue(boots.getMaxDamage() - 1);
        other.hurt(other.damageSources().cactus(), 200);
        helper.assertTrue(
                !other.isAlive() && !HuiArmor.equipped(other), "armor broken by lethal hit causes final death");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void huiProtectionOrder(GameTestHelper helper) {
        Player player = armored(helper);
        player.getFoodData().setFoodLevel(19);
        player.getFoodData().setSaturation(18);
        ItemStack meal = ModExperimentItems.EXPERIMENT_020.toStack();
        player.eat(player.level(), meal, meal.getFoodProperties(player));
        player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 200));
        float absorption = player.getAbsorptionAmount();
        helper.assertTrue(absorption > 0, "vanilla absorption must be present");
        hit(player, 1);
        helper.assertTrue(
                HuiArmor.visibleShield(player) == 1 && player.getAbsorptionAmount() == absorption,
                "life saturation pays before vanilla absorption");
        hit(player, 2);
        helper.assertTrue(
                HuiArmor.visibleShield(player) == 0 && player.getAbsorptionAmount() == absorption - 1,
                "remaining damage reaches vanilla absorption");
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        player.invulnerableTime = 0;
        player.hurt(player.damageSources().magic(), 200);
        helper.assertTrue(
                player.getHealth() == 1
                        && !HuiArmor.downed(player)
                        && player.getOffhandItem().isEmpty(),
                "totem takes priority");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void huiDeathOnLogoutAndUnequip(GameTestHelper helper) {
        Player player = armored(helper);
        hit(player, 100);
        HuiArmor.onLogout(new PlayerEvent.PlayerLoggedOutEvent(player));
        helper.assertTrue(
                !player.isAlive()
                        && player.level().getBlockState(player.blockPosition()).is(TFBlocks.EXPERIMENT_115.get()),
                "logout produces remains");
        Player other = armored(helper);
        other.setHealth(0);
        helper.assertTrue(HuiArmor.downed(other), "direct health loss also enters the downed state");
        other.getFoodData().setFoodLevel(20);
        other.getFoodData().setSaturation(20);
        other.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        helper.assertTrue(!other.isAlive(), "missing armor takes priority over rescue");
        helper.succeed();
    }
}
