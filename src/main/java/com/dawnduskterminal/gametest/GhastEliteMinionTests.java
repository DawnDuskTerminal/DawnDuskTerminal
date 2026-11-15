package com.dawnduskterminal.gametest;

import com.dawnduskterminal.entity.GhastEliteEntity;
import com.dawnduskterminal.registry.ModEntities;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import twilightforest.entity.monster.CarminiteGhastling;
import twilightforest.init.TFEntities;

/** 随从归属恢复与正常世界刻中的移动。 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class GhastEliteMinionTests {
    @GameTest(template = "ddt_large", batch = "elite_minions", timeoutTicks = 200)
    public static void legacyMinionMovesAndIsRemovedWithOwner(GameTestHelper helper) {
        Vec3 origin = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(16, 12, 16)));
        GhastEliteEntity elite = new GhastEliteEntity(ModEntities.GHAST_ELITE.get(), helper.getLevel());
        elite.setPos(origin);
        elite.setNoAi(true);
        helper.getLevel().addFreshEntity(elite);
        CarminiteGhastling minion = TFEntities.CARMINITE_GHASTLING.get().create(helper.getLevel());
        helper.assertTrue(minion != null, "the minion must be creatable");
        minion.setPos(origin.add(10.0D, 0.0D, 0.0D));
        minion.makeBossMinion();
        minion.getPersistentData().putBoolean(GhastEliteEntity.MINION_TAG, true);
        helper.getLevel().addFreshEntity(minion);
        Vec3 start = minion.position();
        helper.runAfterDelay(100, () -> {
            long goals = minion.goalSelector.getAvailableGoals().stream()
                    .filter(goal -> goal.getGoal().getClass().getSimpleName().equals("MinionFollowGoal"))
                    .count();
            helper.assertTrue(goals == 1, "restoration must install exactly one follow goal");
            helper.assertTrue(minion.position().distanceToSqr(start) > 1.0D, "the minion must actually move");
            elite.discard();
            helper.assertTrue(minion.isRemoved(), "permanent owner removal must dismiss its loaded minion");
            helper.succeed();
        });
    }

    @GameTest(template = "ddt_large", batch = "elite_minions_isolation")
    public static void ambiguousLegacyAndWildMinionsAreNotClaimed(GameTestHelper helper) {
        Vec3 origin = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(16, 12, 16)));
        GhastEliteEntity first = new GhastEliteEntity(ModEntities.GHAST_ELITE.get(), helper.getLevel());
        first.setPos(origin.add(-8.0D, 0.0D, 0.0D));
        first.setNoAi(true);
        helper.getLevel().addFreshEntity(first);
        GhastEliteEntity second = new GhastEliteEntity(ModEntities.GHAST_ELITE.get(), helper.getLevel());
        second.setPos(origin.add(8.0D, 0.0D, 0.0D));
        second.setNoAi(true);
        helper.getLevel().addFreshEntity(second);
        CarminiteGhastling legacy = TFEntities.CARMINITE_GHASTLING.get().create(helper.getLevel());
        CarminiteGhastling wild = TFEntities.CARMINITE_GHASTLING.get().create(helper.getLevel());
        helper.assertTrue(legacy != null && wild != null, "ghastlings must be creatable");
        legacy.setPos(origin);
        legacy.getPersistentData().putBoolean(GhastEliteEntity.MINION_TAG, true);
        legacy.tickCount = 20;
        wild.setPos(origin.add(0.0D, 2.0D, 0.0D));
        helper.getLevel().addFreshEntity(legacy);
        helper.getLevel().addFreshEntity(wild);
        GhastEliteEntity.restoreMinion(legacy);
        GhastEliteEntity.restoreMinion(wild);
        helper.assertFalse(
                legacy.getPersistentData().hasUUID("dawnduskterminal:elite_owner"),
                "ambiguous legacy minions must not be assigned");
        helper.assertFalse(
                wild.getPersistentData().hasUUID("dawnduskterminal:elite_owner"),
                "wild ghastlings must not be assigned");
        first.discard();
        helper.assertFalse(legacy.isRemoved() || wild.isRemoved(), "unowned ghastlings must not be dismissed");
        second.discard();
        legacy.discard();
        wild.discard();
        helper.succeed();
    }

    @GameTest(template = "ddt_large", batch = "elite_minions_reload")
    public static void savedOwnerAndMinionRestoreInEitherOrder(GameTestHelper helper) {
        Vec3 origin = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(16, 12, 16)));
        GhastEliteEntity originalOwner = new GhastEliteEntity(ModEntities.GHAST_ELITE.get(), helper.getLevel());
        originalOwner.setPos(origin);
        CarminiteGhastling original = TFEntities.CARMINITE_GHASTLING.get().create(helper.getLevel());
        helper.assertTrue(original != null, "the minion must be creatable");
        original.setPos(origin.add(10.0D, 0.0D, 0.0D));
        original.makeBossMinion();
        original.getPersistentData().putBoolean(GhastEliteEntity.MINION_TAG, true);
        original.getPersistentData().putUUID("dawnduskterminal:elite_owner", originalOwner.getUUID());
        CompoundTag ownerData = originalOwner.saveWithoutId(new CompoundTag());
        CompoundTag minionData = original.saveWithoutId(new CompoundTag());
        CarminiteGhastling first = TFEntities.CARMINITE_GHASTLING.get().create(helper.getLevel());
        helper.assertTrue(first != null, "the saved minion must be creatable");
        first.load(minionData);
        helper.getLevel().addFreshEntity(first);
        GhastEliteEntity.restoreMinion(first);
        helper.assertTrue(followGoals(first) == 0 && !first.isRemoved(), "unloaded owner must not dismiss the minion");
        GhastEliteEntity restoredOwner = new GhastEliteEntity(ModEntities.GHAST_ELITE.get(), helper.getLevel());
        restoredOwner.load(ownerData);
        helper.getLevel().addFreshEntity(restoredOwner);
        GhastEliteEntity.restoreMinion(first);
        GhastEliteEntity.restoreMinion(first);
        helper.assertTrue(followGoals(first) == 1, "minion-first loading must install exactly one goal");
        first.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        CarminiteGhastling second = TFEntities.CARMINITE_GHASTLING.get().create(helper.getLevel());
        helper.assertTrue(second != null, "the saved minion must be recreatable");
        second.load(minionData);
        helper.getLevel().addFreshEntity(second);
        GhastEliteEntity.restoreMinion(second);
        helper.assertTrue(followGoals(second) == 1, "owner-first loading must restore the goal");
        restoredOwner.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        helper.assertFalse(second.isRemoved(), "unloading an owner must not dismiss the minion");
        second.discard();
        helper.succeed();
    }

    @GameTest(template = "ddt_large", batch = "elite_minions_obstacle", timeoutTicks = 500)
    public static void minionNavigatesAroundObstacle(GameTestHelper helper) {
        for (int y = 7; y <= 18; y++) {
            for (int z = 12; z <= 20; z++) {
                helper.setBlock(new BlockPos(20, y, z), Blocks.STONE);
            }
        }
        Vec3 origin = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(12, 12, 16)));
        GhastEliteEntity owner = new GhastEliteEntity(ModEntities.GHAST_ELITE.get(), helper.getLevel());
        owner.setPos(origin);
        owner.setNoAi(true);
        helper.getLevel().addFreshEntity(owner);
        CarminiteGhastling minion = TFEntities.CARMINITE_GHASTLING.get().create(helper.getLevel());
        helper.assertTrue(minion != null, "the minion must be creatable");
        minion.setPos(origin.add(14.0D, 0.0D, 0.0D));
        minion.makeBossMinion();
        minion.getRandom().setSeed(9281L);
        minion.getPersistentData().putBoolean(GhastEliteEntity.MINION_TAG, true);
        minion.getPersistentData().putUUID("dawnduskterminal:elite_owner", owner.getUUID());
        helper.getLevel().addFreshEntity(minion);
        helper.runAfterDelay(350, () -> {
            helper.assertTrue(
                    minion.position().distanceTo(owner.position()) < 12.0D,
                    "the minion must approach the owner around the obstacle");
            helper.assertTrue(helper.getLevel().noCollision(minion), "the minion must not pass into blocks");
            owner.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "ddt_large", batch = "elite_minions_six", timeoutTicks = 400)
    public static void summonedMinionsMoveAfterOwnerMoves(GameTestHelper helper) {
        Vec3 origin = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(10, 12, 16)));
        GhastEliteEntity owner = new GhastEliteEntity(ModEntities.GHAST_ELITE.get(), helper.getLevel());
        owner.setPos(origin);
        owner.setNoAi(true);
        helper.getLevel().addFreshEntity(owner);
        Player target = helper.makeMockPlayer(GameType.SURVIVAL);
        target.setPos(origin.add(0.0D, 0.0D, 20.0D));
        owner.setTarget(target);
        helper.runAfterDelay(100, () -> {
            List<CarminiteGhastling> minions = helper.getLevel()
                    .getEntitiesOfClass(
                            CarminiteGhastling.class,
                            owner.getBoundingBox().inflate(48.0D),
                            mob -> mob.getPersistentData().hasUUID("dawnduskterminal:elite_owner")
                                    && mob.getPersistentData()
                                            .getUUID("dawnduskterminal:elite_owner")
                                            .equals(owner.getUUID()));
            helper.assertTrue(minions.size() == 6, "all six summoned minions must be present");
            List<Vec3> positions = minions.stream().map(Entity::position).toList();
            owner.setPos(origin.add(12.0D, 0.0D, 0.0D));
            double before =
                    minions.stream().mapToDouble(mob -> mob.distanceTo(owner)).sum();
            helper.runAfterDelay(100, () -> {
                for (int index = 0; index < minions.size(); index++) {
                    helper.assertTrue(
                            minions.get(index).position().distanceToSqr(positions.get(index)) > 0.25D,
                            "each summoned minion must actually move");
                }
                double after = minions.stream()
                        .mapToDouble(mob -> mob.distanceTo(owner))
                        .sum();
                helper.assertTrue(after < before, "summoned minions must approach their moved owner");
                owner.discard();
                helper.succeed();
            });
        });
    }

    private static long followGoals(CarminiteGhastling minion) {
        return minion.goalSelector.getAvailableGoals().stream()
                .filter(goal -> goal.getGoal().getClass().getSimpleName().equals("MinionFollowGoal"))
                .count();
    }

    private GhastEliteMinionTests() {}
}
