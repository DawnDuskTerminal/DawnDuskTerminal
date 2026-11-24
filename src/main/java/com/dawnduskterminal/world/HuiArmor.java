package com.dawnduskterminal.world;

import com.dawnduskterminal.mixin.HuiTotemAccess;
import com.dawnduskterminal.registry.ModAttachments;
import com.dawnduskterminal.registry.ModDamageTypes;
import com.dawnduskterminal.registry.ModEquipment;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.UseAnim;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import twilightforest.block.Experiment115Block;
import twilightforest.init.TFBlocks;

public final class HuiArmor {
    public static final float DOWNED_HEALTH = 0.01F;

    private HuiArmor() {}

    public static boolean equipped(Player player) {
        var items = ModEquipment.sets().get("hui").items();
        return player.getItemBySlot(EquipmentSlot.HEAD).is(items.get(5).get())
                && player.getItemBySlot(EquipmentSlot.CHEST).is(items.get(6).get())
                && player.getItemBySlot(EquipmentSlot.LEGS).is(items.get(7).get())
                && player.getItemBySlot(EquipmentSlot.FEET).is(items.get(8).get());
    }

    private static HuiVitals state(Player player) {
        return player.getData(ModAttachments.HUI_VITALS);
    }

    public static boolean downed(Player player) {
        return player.hasData(ModAttachments.HUI_VITALS) && state(player).downed;
    }

    public static float visibleShield(Player player) {
        return player.hasData(ModAttachments.HUI_VITALS) && equipped(player) ? state(player).shield : 0;
    }

    private static void sync(Player player) {
        player.syncData(ModAttachments.HUI_VITALS);
    }

    public static boolean armoredHit(Player player) {
        return player.hasData(ModAttachments.HUI_VITALS) && state(player).armoredHit;
    }

    public static void setArmoredHit(Player player, boolean active) {
        state(player).armoredHit = active;
    }

    public static void onDamage(LivingDamageEvent.Pre event) {
        if (!(event.getEntity() instanceof Player player)
                || !(player.level() instanceof ServerLevel level)
                || !armoredHit(player)
                || event.getNewDamage() <= 0) return;
        level.sendParticles(
                DustParticleOptions.REDSTONE, player.getX(), player.getY(0.55), player.getZ(), 10, 0.24, 0.35, 0.24, 0);
        if (!downed(player)) event.setNewDamage(absorb(player, event.getNewDamage()));
    }

    private static float absorb(Player player, float amount) {
        HuiVitals vitals = state(player);
        float spent = Math.min(amount, vitals.shield);
        if (spent > 0) {
            vitals.shield -= spent;
            sync(player);
        }
        return amount - spent;
    }

    public static void afterMeal(Player player, float before) {
        if (player.level().isClientSide || !equipped(player)) return;
        HuiVitals vitals = state(player);
        float gain = Math.max(0, player.getFoodData().getSaturationLevel() - before);
        if (gain > 0 && vitals.shield < 20) {
            vitals.shield = Math.min(20, vitals.shield + gain);
            sync(player);
        }
        if (vitals.downed) settleDowned(player);
    }

    public static boolean delayDirectHealthLoss(Player player) {
        if (armoredHit(player) || !player.isAlive() || !equipped(player) || state(player).finalizingDeath) return false;
        enterDowned(player);
        settleDowned(player);
        return true;
    }

    public static void enterDowned(Player player) {
        player.setHealth(DOWNED_HEALTH);
        HuiVitals vitals = state(player);
        vitals.downed = true;
        vitals.recoveryTicks = 0;
        vitals.primaryTicks = 0;
        vitals.drainProgress = 0;
        vitals.damageRemainder = 0;
        sync(player);
    }

    public static void redirectDamage(Player player, float damage) {
        HuiVitals vitals = state(player);
        float total = vitals.damageRemainder + damage;
        int points = (int) total;
        vitals.damageRemainder = total - points;
        FoodData food = player.getFoodData();
        food.setFoodLevel(Math.max(0, food.getFoodLevel() - points));
    }

    public static void checkEquipment(Player player) {
        // 受击期间的护甲破损由同一次伤害结束时统一结算。
        if (downed(player) && !armoredHit(player) && !equipped(player)) finishDeath(player);
    }

    public static void settleDowned(Player player) {
        if (!downed(player)) return;
        FoodData food = player.getFoodData();
        if (!equipped(player) || food.getFoodLevel() <= 0) {
            finishDeath(player);
        } else if (food.getFoodLevel() == 20 && food.getSaturationLevel() >= 20) {
            state(player).downed = false;
            food.setSaturation(0);
            food.setFoodLevel(6);
            player.setHealth(2);
            sync(player);
        }
    }

    private static void finishDeath(Player player) {
        HuiVitals vitals = state(player);
        vitals.downed = false;
        vitals.shield = 0;
        vitals.exhaustion = 0;
        sync(player);
        DamageSource source = player.damageSources().source(ModDamageTypes.HUI_DEATH);
        player.getCombatTracker().recordDamage(source, player.getHealth());
        vitals.finalizingDeath = true;
        try {
            player.setHealth(0);
            player.die(source);
        } finally {
            vitals.finalizingDeath = false;
        }
        BlockPos pos = player.blockPosition();
        var remains = TFBlocks.EXPERIMENT_115
                .get()
                .defaultBlockState()
                .setValue(Experiment115Block.BITES_TAKEN, player.getRandom().nextInt(8));
        if (player.level().isEmptyBlock(pos) && remains.canSurvive(player.level(), pos)) {
            player.level().setBlockAndUpdate(pos, remains);
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        if (!player.level().isClientSide && downed(player)) finishDeath(player);
    }

    public static void tick(Player player) {
        if (!player.isAlive()) return;
        if (downed(player)) {
            settleDowned(player);
            if (!downed(player)) return;
            HuiVitals vitals = state(player);
            var item = player.getUseItem();
            boolean eating = player.isUsingItem()
                    && item.getFoodProperties(player) != null
                    && (item.getUseAnimation() == UseAnim.EAT || item.getUseAnimation() == UseAnim.DRINK);
            vitals.drainProgress += eating ? 1 : 5;
            if (vitals.drainProgress >= 20) {
                vitals.drainProgress -= 20;
                redirectDamage(player, 1);
                settleDowned(player);
            }
            return;
        }
        if (!equipped(player)) {
            if (player.hasData(ModAttachments.HUI_VITALS)) {
                state(player).recoveryTicks = 0;
                state(player).primaryTicks = 0;
            }
            return;
        }
        FoodData food = player.getFoodData();
        if (food.getFoodLevel() == 0) {
            state(player).recoveryTicks = 0;
            state(player).primaryTicks = 0;
            exhaust(player, 1);
            return;
        }
        HuiVitals vitals = state(player);
        float fraction = player.getHealth() / player.getMaxHealth();
        boolean health = fraction <= food.getFoodLevel() / 20F;
        if (health != vitals.recoveringHealth) {
            vitals.recoveryTicks = 0;
            vitals.recoveringHealth = health;
            vitals.primaryTicks = 0;
        }
        int tick = ++vitals.recoveryTicks;
        boolean low = fraction < 0.5F;
        int interval = health && low ? 10 : 20;
        if (interval != vitals.primaryInterval) {
            vitals.primaryInterval = interval;
            vitals.primaryTicks = 0;
        }
        boolean due = ++vitals.primaryTicks == interval;
        if (due) vitals.primaryTicks = 0;
        if (health) {
            if (due && player.getHealth() < player.getMaxHealth()) {
                float before = player.getHealth();
                player.heal(1);
                if (player.getHealth() > before) food.addExhaustion(food.getSaturationLevel() > 0 ? 6 : low ? 2 : 4);
            }
            if (tick == 80 && food.getFoodLevel() < 20) food.setFoodLevel(food.getFoodLevel() + 1);
        } else {
            if (due) {
                food.setFoodLevel(food.getFoodLevel() + 1);
                exhaust(player, 2);
            }
            if (tick == 80 && !vitals.downed && player.isAlive()) player.heal(1);
        }
        if (tick == 80) vitals.recoveryTicks = 0;
    }

    private static void exhaust(Player player, int amount) {
        HuiVitals vitals = state(player);
        vitals.exhaustion += amount;
        while (vitals.exhaustion >= 4) {
            vitals.exhaustion -= 4;
            float health = player.getHealth() - absorb(player, 1);
            if (health > 0) player.setHealth(health);
            else {
                if (((HuiTotemAccess) player)
                        .ddt$tryTotem(player.damageSources().starve())) return;
                enterDowned(player);
                settleDowned(player);
                return;
            }
        }
    }
}
