package com.dawnduskterminal.gametest;

import com.dawnduskterminal.entity.FruitKind;
import com.dawnduskterminal.entity.FruitProjectileEntity;
import com.dawnduskterminal.item.FruitGunItem;
import com.dawnduskterminal.registry.ModEntities;
import com.dawnduskterminal.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 西瓜炮与南瓜炮的规格
 *
 * <p>钉住六件事：两件物品都是 {@link FruitGunItem}，耐久 256 且不可堆叠；两条炮弹实体的碰撞箱
 * 都是 1 格见方，种类由实体类型决定；直射伤害、范围伤害与范围半径按 {@link FruitKind} 分开；
 * 击退概率与偏转按身高定，且南瓜硬于西瓜——不爆裂的概率更高、偏得更少、掉速更少；
 * 只有南瓜炮会撞方块弹开，弹开时切向保留七成、法向只剩三成；碰撞只给最后一轴一个 8 到 20 度每 tick 的随机角速度，
 * 前两轴是静态角，合成后角速度矢量恒定。
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
    public static void damageAndRadiusFollowKind(GameTestHelper helper) {
        FruitKind.Stats melon = FruitKind.MELON.stats();
        helper.assertTrue(
                melon.directDamage() == 10.0F, "the melon gun must deal 10 direct damage, got " + melon.directDamage());
        helper.assertTrue(melon.areaDamage() == 6.0F, "the melon burst must deal 6 damage, got " + melon.areaDamage());
        helper.assertTrue(
                melon.areaRadius() == 3.5D, "the melon burst must reach 3.5 blocks, got " + melon.areaRadius());
        FruitKind.Stats pumpkin = FruitKind.PUMPKIN.stats();
        helper.assertTrue(
                pumpkin.directDamage() == 16.0F,
                "the pumpkin gun must deal 16 direct damage, got " + pumpkin.directDamage());
        helper.assertTrue(
                pumpkin.areaDamage() == 2.0F, "the pumpkin burst must deal 2 damage, got " + pumpkin.areaDamage());
        helper.assertTrue(
                pumpkin.areaRadius() == 2.5D, "the pumpkin burst must reach 2.5 blocks, got " + pumpkin.areaRadius());
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "fruit_gun")
    public static void knockbackChanceFollowsHeightAndKind(GameTestHelper helper) {
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.knockbackChance(FruitKind.MELON, 2.0D) - 0.5D) < 1.0E-9D,
                "a two block tall target must be kept intact 50% of the time against the melon gun");
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.knockbackChance(FruitKind.PUMPKIN, 2.0D) - 0.75D) < 1.0E-9D,
                "a two block tall target must be kept intact 75% of the time against the pumpkin gun");
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.knockbackChance(FruitKind.MELON, 1.0D) - 1.0D) < 1.0E-9D,
                "a one block tall target must always be knocked back by the melon gun");
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.knockbackChance(FruitKind.PUMPKIN, 1.0D) - 1.0D) < 1.0E-9D,
                "a one block tall target must always be knocked back by the pumpkin gun");
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.knockbackChance(FruitKind.MELON, 4.0D) - 0.25D) < 1.0E-9D,
                "a four block tall target must be kept intact 25% of the time against the melon gun");
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.knockbackChance(FruitKind.PUMPKIN, 4.0D) - 0.375D) < 1.0E-9D,
                "a four block tall target must be kept intact 37.5% of the time against the pumpkin gun");
        helper.assertTrue(
                FruitProjectileEntity.knockbackChance(FruitKind.MELON, 0.0D) == 1.0D,
                "a zero height target must fall back to a 100% chance");
        helper.assertTrue(
                FruitProjectileEntity.knockbackChance(FruitKind.PUMPKIN, 0.0D) == 1.0D,
                "a zero height target must fall back to a 100% chance against the pumpkin gun too");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "fruit_gun")
    public static void deflectionScalesWithTargetHeight(GameTestHelper helper) {
        double melonTwoBlocks = FruitProjectileEntity.maxDeflectionDegrees(FruitKind.MELON, 2.0D);
        helper.assertTrue(
                Math.abs(melonTwoBlocks - 40.0D) < 1.0E-9D,
                "a two block tall target must deflect at most 40 degrees off the melon, got " + melonTwoBlocks);
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.maxDeflectionDegrees(FruitKind.MELON, 1.0D) - 20.0D) < 1.0E-9D,
                "a one block tall target must deflect at most 20 degrees off the melon");
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.maxDeflectionDegrees(FruitKind.MELON, 4.0D) - 80.0D) < 1.0E-9D,
                "a four block tall target must deflect at most 80 degrees off the melon");
        double pumpkinTwoBlocks = FruitProjectileEntity.maxDeflectionDegrees(FruitKind.PUMPKIN, 2.0D);
        helper.assertTrue(
                Math.abs(pumpkinTwoBlocks - 25.0D) < 1.0E-9D,
                "a two block tall target must deflect at most 25 degrees off the pumpkin, got " + pumpkinTwoBlocks);
        helper.assertTrue(
                pumpkinTwoBlocks < melonTwoBlocks,
                "the pumpkin is harder, it must deflect less than the melon, got " + pumpkinTwoBlocks);
        helper.assertTrue(
                FruitProjectileEntity.maxDeflectionDegrees(FruitKind.MELON, 8.0D)
                        > FruitProjectileEntity.maxDeflectionDegrees(FruitKind.MELON, 4.0D),
                "the deflection must keep growing past four blocks");

        double melonFactor = FruitProjectileEntity.deflectionSpeedFactor(FruitKind.MELON, 2.0D);
        helper.assertTrue(
                Math.abs(melonFactor - 0.7D) < 1.0E-9D,
                "a two block tall target must slow the melon to 70%, factor " + melonFactor);
        helper.assertTrue(
                Math.abs(FruitProjectileEntity.deflectionSpeedFactor(FruitKind.MELON, 1.0D) - 0.85D) < 1.0E-9D,
                "a one block tall target must slow the melon to 85%");
        double pumpkinFactor = FruitProjectileEntity.deflectionSpeedFactor(FruitKind.PUMPKIN, 2.0D);
        helper.assertTrue(
                Math.abs(pumpkinFactor - 0.85D) < 1.0E-9D,
                "a two block tall target must slow the pumpkin to 85%, factor " + pumpkinFactor);
        helper.assertTrue(
                pumpkinFactor > melonFactor,
                "the pumpkin is harder, it must keep more speed than the melon, got " + pumpkinFactor);
        helper.assertTrue(
                FruitProjectileEntity.deflectionSpeedFactor(FruitKind.MELON, 8.0D) >= 0.0D,
                "the remaining speed must never become negative");
        helper.assertTrue(
                FruitProjectileEntity.deflectionSpeedFactor(FruitKind.PUMPKIN, 8.0D) >= 0.0D,
                "the remaining speed must never become negative for the pumpkin either");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "fruit_gun")
    public static void onlyThePumpkinBouncesOffBlocks(GameTestHelper helper) {
        helper.assertTrue(
                FruitKind.MELON.stats().blockBounceChance() == 0.0D,
                "the melon must burst on every block it hits, bounce chance "
                        + FruitKind.MELON.stats().blockBounceChance());
        helper.assertTrue(
                FruitKind.PUMPKIN.stats().blockBounceChance() > 0.0D,
                "the pumpkin must be able to bounce off blocks, bounce chance "
                        + FruitKind.PUMPKIN.stats().blockBounceChance());
        helper.assertTrue(
                FruitKind.PUMPKIN.stats().blockBounceChance() < 1.0D,
                "the pumpkin must bounce only some of the time, bounce chance "
                        + FruitKind.PUMPKIN.stats().blockBounceChance());
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "fruit_gun")
    public static void bounceDampsTheNormalMoreThanTheTangential(GameTestHelper helper) {
        // 沿 -X 撞上朝 +X 开的面：法向速度从 2 掉到 0.6，并翻向 +X
        Vec3 headOn = FruitProjectileEntity.bounceVelocity(new Vec3(-2.0D, 0.0D, 0.0D), new Vec3(1.0D, 0.0D, 0.0D));
        helper.assertTrue(
                headOn.equals(new Vec3(0.6D, 0.0D, 0.0D)),
                "a head-on hit must rebound at 30% of the normal speed, got " + headOn);
        // 落到地面：水平切向保留七成，竖直法向只剩三成
        Vec3 floor = FruitProjectileEntity.bounceVelocity(new Vec3(1.0D, -1.0D, 0.0D), new Vec3(0.0D, 1.0D, 0.0D));
        helper.assertTrue(
                floor.equals(new Vec3(0.7D, 0.3D, 0.0D)),
                "a floor hit must keep 70% of the horizontal component and 30% of the vertical one, got " + floor);
        Vec3 oblique = FruitProjectileEntity.bounceVelocity(new Vec3(2.0D, -3.0D, 4.0D), new Vec3(0.0D, 1.0D, 0.0D));
        helper.assertTrue(
                Math.abs(oblique.x - 1.4D) < 1.0E-9D && Math.abs(oblique.z - 2.8D) < 1.0E-9D,
                "the tangential components must keep 70% of their length, got " + oblique);
        helper.assertTrue(
                Math.abs(oblique.y - 0.9D) < 1.0E-9D,
                "the normal component must be scaled by the restitution, got " + oblique.y);
        helper.assertTrue(
                oblique.length() < new Vec3(2.0D, -3.0D, 4.0D).length(),
                "the bounce must lose speed, got " + oblique.length());
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "fruit_gun")
    public static void collisionsSpinTheModelAboutOneFixedAxis(GameTestHelper helper) {
        FruitProjectileEntity pumpkin = ModEntities.PUMPKIN_PROJECTILE.get().create(helper.getLevel());
        helper.assertTrue(pumpkin != null, "pumpkin_projectile must create an entity");
        helper.assertTrue(pumpkin.spinSpeed() == 0.0F, "a fresh projectile must fly without spinning");
        RandomSource random = RandomSource.create(20260101L);
        boolean sawPositive = false;
        boolean sawNegative = false;
        for (int sample = 0; sample < 64; sample++) {
            float speed = FruitProjectileEntity.randomSpinSpeed(random);
            float magnitude = Math.abs(speed);
            helper.assertTrue(
                    magnitude >= 8.0F && magnitude <= 20.0F,
                    "a collision must spin the last axis by 8 to 20 degrees per tick, got " + speed);
            sawPositive |= speed > 0.0F;
            sawNegative |= speed < 0.0F;
        }
        helper.assertTrue(sawPositive && sawNegative, "the spin direction must be random on both sides");
        helper.succeed();
    }

    private FruitGunTests() {}
}
