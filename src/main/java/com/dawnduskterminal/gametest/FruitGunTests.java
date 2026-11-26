package com.dawnduskterminal.gametest;

import com.dawnduskterminal.entity.FruitKind;
import com.dawnduskterminal.entity.FruitProjectileEntity;
import com.dawnduskterminal.item.FruitGunItem;
import com.dawnduskterminal.registry.ModEntities;
import com.dawnduskterminal.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 西瓜炮与南瓜炮的规格
 *
 * <p>钉住三件事：两件物品都是 {@link FruitGunItem}，耐久 256 且不可堆叠；两条炮弹实体的碰撞箱
 * 都是 1 格见方，种类由实体类型决定；击退概率按身高反比，身高 2 格时正好 50%。
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class FruitGunTests {
    @GameTest(template = "ddt_empty", batch = "fruit_gun")
    public static void gunsAreDurableAndSingleStack(GameTestHelper helper) {
        for (Item item : new Item[] {ModItems.MELONGUN.get(), ModItems.PUMPKINGUN.get()}) {
            helper.assertTrue(item instanceof FruitGunItem, item + " must be a " + FruitGunItem.class.getSimpleName());
            ItemStack stack = new ItemStack(item);
            helper.assertTrue(
                    stack.getMaxDamage() == FruitGunItem.DURABILITY,
                    item + " must have " + FruitGunItem.DURABILITY + " durability, got " + stack.getMaxDamage());
            helper.assertTrue(
                    stack.getMaxStackSize() == 1,
                    item + " is damageable, it must not stack, got " + stack.getMaxStackSize());
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "fruit_gun")
    public static void projectilesAreOneBlockAndTyped(GameTestHelper helper) {
        FruitProjectileEntity melon = ModEntities.MELON_PROJECTILE.get().create(helper.getLevel());
        FruitProjectileEntity pumpkin = ModEntities.PUMPKIN_PROJECTILE.get().create(helper.getLevel());
        helper.assertTrue(melon != null, "melon_projectile must create an entity");
        helper.assertTrue(pumpkin != null, "pumpkin_projectile must create an entity");
        helper.assertTrue(
                melon.kind() == FruitKind.MELON, "melon_projectile must carry the melon kind, got " + melon.kind());
        helper.assertTrue(
                pumpkin.kind() == FruitKind.PUMPKIN,
                "pumpkin_projectile must carry the pumpkin kind, got " + pumpkin.kind());
        helper.assertTrue(
                melon.getBbWidth() == 1.0F && melon.getBbHeight() == 1.0F,
                "melon_projectile must be one block in size, got " + melon.getBbWidth() + " x " + melon.getBbHeight());
        helper.assertTrue(
                pumpkin.getBbWidth() == 1.0F && pumpkin.getBbHeight() == 1.0F,
                "pumpkin_projectile must be one block in size, got " + pumpkin.getBbWidth() + " x "
                        + pumpkin.getBbHeight());
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "fruit_gun")
    public static void knockbackChanceFollowsHeight(GameTestHelper helper) {
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.knockbackChance(2.0D) - 0.5D) < 1.0E-9D,
                "a two block tall target must be knocked back 50% of the time");
        helper.assertTrue(
                FruitProjectileEntity.knockbackChance(1.0D) == 1.0D,
                "a one block tall target must always be knocked back");
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.knockbackChance(4.0D) - 0.25D) < 1.0E-9D,
                "a four block tall target must be knocked back 25% of the time");
        helper.assertTrue(
                FruitProjectileEntity.knockbackChance(0.0D) == 1.0D,
                "a zero height target must fall back to a 100% chance");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "fruit_gun")
    public static void deflectionScalesWithTargetHeight(GameTestHelper helper) {
        double twoBlockDegrees = FruitProjectileEntity.maxDeflectionDegrees(2.0D);
        helper.assertTrue(
                Math.abs(twoBlockDegrees - 40.0D) < 1.0E-9D,
                "a two block tall target must deflect at most 40 degrees, got " + twoBlockDegrees);
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.maxDeflectionDegrees(1.0D) - 20.0D) < 1.0E-9D,
                "a one block tall target must deflect at most 20 degrees");
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.maxDeflectionDegrees(4.0D) - 80.0D) < 1.0E-9D,
                "a four block tall target must deflect at most 80 degrees");
        helper.assertTrue(
                FruitProjectileEntity.maxDeflectionDegrees(8.0D) > FruitProjectileEntity.maxDeflectionDegrees(4.0D),
                "the deflection must keep growing past four blocks");
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.maxDeflectionDegrees(8.0D) - 160.0D) < 1.0E-9D,
                "an eight block tall target must deflect at most 160 degrees");

        double twoBlockFactor = FruitProjectileEntity.deflectionSpeedFactor(2.0D);
        helper.assertTrue(
                Math.abs(twoBlockFactor - 0.7D) < 1.0E-9D,
                "a two block tall target must lose 30% speed, factor " + twoBlockFactor);
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.deflectionSpeedFactor(1.0D) - 0.85D) < 1.0E-9D,
                "a one block tall target must lose 15% speed");
        helper.assertTrue(
                FruitProjectileEntity.deflectionSpeedFactor(4.0D) < twoBlockFactor,
                "a larger target must lose more speed");
        helper.assertTrue(
                FruitProjectileEntity.deflectionSpeedFactor(8.0D) < FruitProjectileEntity.deflectionSpeedFactor(4.0D),
                "the speed loss must keep growing past four blocks");
        helper.assertTrue(
                FruitProjectileEntity.deflectionSpeedFactor(8.0D) >= 0.0D,
                "the remaining speed must never become negative");
        helper.succeed();
    }

    private FruitGunTests() {}
}
