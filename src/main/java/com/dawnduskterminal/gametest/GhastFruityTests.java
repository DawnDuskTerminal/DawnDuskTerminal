package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.block.Experiment052Block;
import com.dawnduskterminal.entity.AvoidHazardousFluidGoal;
import com.dawnduskterminal.entity.GhastEliteEntity;
import com.dawnduskterminal.entity.GhastEliteFireball;
import com.dawnduskterminal.entity.GhastElitePopulation;
import com.dawnduskterminal.entity.GhastFruityEntity;
import com.dawnduskterminal.entity.GhastFruityPopulation;
import com.dawnduskterminal.entity.GhastKin;
import com.dawnduskterminal.entity.GhastVoice;
import com.dawnduskterminal.item.FireJetProcess;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModEntities;
import com.dawnduskterminal.registry.ModItems;
import com.dawnduskterminal.registry.ModSounds;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import twilightforest.init.TFBlocks;
import twilightforest.init.TFEntities;

/**
 * 「恶果」的几件事：配置（数值、尺寸、生成、还手距离、暮色火球的继承关系）、生成表、接住火球之后往哪飞
 *
 * <p>接火球这一条不依赖真实弹道：{@code ProjectileImpactEvent} 就是 {@code LargeFireball} 撞击时
 * 服务端唯一的那处分叉，直接构造一个事件交给处理器，走的正是实机里的那条路径
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class GhastFruityTests {
    @GameTest(template = "ddt_large", batch = "ghast_population")
    public static void populationReservationsDoNotCrossSessions(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(
                GhastElitePopulation.loaded(level) == 0 && GhastFruityPopulation.loaded(level) == 0,
                "the isolated population batch must start without loaded ghasts");
        long gameTime = level.getGameTime();
        GhastEliteEntity elite = null;
        GhastFruityEntity fruity = null;
        try {
            GhastElitePopulation.clear();
            GhastFruityPopulation.clear();
            reserveEmptyPopulation(helper);
            // 同一维度、同一游戏时间模拟下一会话，旧预留名额不得继续占用。
            GhastElitePopulation.clear();
            GhastFruityPopulation.clear();
            reserveEmptyPopulation(helper);
            elite = (GhastEliteEntity) helper.spawnWithNoFreeWill(ModEntities.GHAST_ELITE.get(), 8, 5, 8);
            fruity = (GhastFruityEntity) helper.spawnWithNoFreeWill(ModEntities.GHASTFRUITY.get(), 20, 5, 20);
            GhastElitePopulation.clear();
            GhastFruityPopulation.clear();
            helper.assertFalse(GhastElitePopulation.tryReserve(level), "a loaded elite must consume its actual slot");
            for (int i = 1; i < GhastFruityPopulation.CAP; i++) {
                helper.assertTrue(GhastFruityPopulation.tryReserve(level), "remaining fruity slots must be available");
            }
            helper.assertFalse(GhastFruityPopulation.tryReserve(level), "loaded and reserved slots must share the cap");
            helper.assertTrue(level.getGameTime() == gameTime, "session isolation must be checked within one tick");
        } finally {
            if (elite != null) {
                elite.discard();
            }
            if (fruity != null) {
                fruity.discard();
            }
            GhastElitePopulation.clear();
            GhastFruityPopulation.clear();
        }
        try {
            reserveEmptyPopulation(helper);
            helper.runAfterDelay(1, () -> {
                try {
                    reserveEmptyPopulation(helper);
                    helper.succeed();
                } finally {
                    GhastElitePopulation.clear();
                    GhastFruityPopulation.clear();
                }
            });
        } catch (RuntimeException | Error failure) {
            GhastElitePopulation.clear();
            GhastFruityPopulation.clear();
            throw failure;
        }
    }

    private static void reserveEmptyPopulation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.assertTrue(GhastElitePopulation.tryReserve(level), "the first elite reservation must succeed");
        helper.assertFalse(GhastElitePopulation.tryReserve(level), "a second elite reservation must fail");
        for (int i = 0; i < GhastFruityPopulation.CAP; i++) {
            helper.assertTrue(GhastFruityPopulation.tryReserve(level), "an empty fruity slot must be reservable");
        }
        helper.assertFalse(GhastFruityPopulation.tryReserve(level), "a fifth fruity reservation must fail");
    }

    /**
     * 一整套配置钉在这里：生命值 30、索敌 24 格、碰撞箱照 {@code Root_spin_M} 的 1.5 格、
     * 一次只生一只（加载范围内最多 4 只）、火球从脸前 1.3 格出膛，外加两条依附于它的约定
     *
     * <p>尺寸是硬指标：模型给的几何不做任何缩放，碰撞箱就是那个 24 单位见方的立方体
     *
     * <p>「24 格是索敌距离，不是还手距离」：原版 {@code HurtByTargetGoal} 只收 {@code PathfinderMob}，
     * 恶魂是 {@code FlyingMob}，所以这条只能由实体自己的 {@code hurt} 补上；这里把攻击者放在 44 格开外
     * 验证它仍然成为目标
     *
     * <p>暮色森林的恶魂火球也要能被拨开：反编译 4.8.3345 可知砷铅铁恶灵与守卫的
     * {@code GhastguardAttackGoal} 直接 {@code new} 原版 {@code LargeFireball}，乌尔恶魂用的是
     * {@code twilightforest.entity.projectile.UrGhastFireball}，而那个类继承自 {@code LargeFireball}。
     * 反弹只认 {@code instanceof LargeFireball}，这里把该继承关系钉住，暮色森林以后改基类时先在这里炸
     */
    @GameTest(template = "ddt_large")
    public static void statsAreTheConfiguredOnes(GameTestHelper helper) {
        GhastFruityEntity ghast = new GhastFruityEntity(ModEntities.GHASTFRUITY.get(), helper.getLevel());
        ghast.setPos(4.0D, 2.0D, 4.0D);

        helper.assertTrue(
                ghast.getMaxHealth() == 30.0F, "ghastfruity must have 30 max health, was " + ghast.getMaxHealth());
        helper.assertTrue(
                ghast.getAttributeValue(Attributes.FOLLOW_RANGE) == 24.0D,
                "ghastfruity must use the normal 24 block follow range, was "
                        + ghast.getAttributeValue(Attributes.FOLLOW_RANGE));
        helper.assertTrue(
                ghast.getType().getDimensions().width() == 1.5F
                        && ghast.getType().getDimensions().height() == 1.5F,
                "the hitbox must match Root_spin_M (24 units = 1.5 blocks), was "
                        + ghast.getType().getDimensions().width() + "x"
                        + ghast.getType().getDimensions().height());
        helper.assertTrue(
                ghast.getMaxSpawnClusterSize() == 1,
                "a natural batch must be a single ghastfruity (the 4 cap is a population cap), was "
                        + ghast.getMaxSpawnClusterSize());
        helper.assertTrue(ghast.getType().fireImmune(), "a fireball spitting ghast must be immune to fire");
        // 常驻音、受伤、死亡这三声的音量：客户端按 max(音量, 1) × 16 算可听半径，1.5 就是 24 格
        helper.assertTrue(
                Math.abs(ghast.getSoundVolume() - GhastFruityEntity.VOICE_VOLUME) < 1.0E-6F,
                "the everyday voice must play at " + GhastFruityEntity.VOICE_VOLUME + ", got "
                        + ghast.getSoundVolume());
        helper.assertTrue(
                Math.abs(GhastFruityEntity.VOICE_VOLUME * 16.0F - 24.0F) < 1.0E-6F,
                "the everyday voice must fade out at 24 blocks, got " + GhastFruityEntity.VOICE_VOLUME * 16.0F);
        // 攻击（蓄力、喷吐）与反弹另走 GhastVoice，仍旧是原版恶魂那 5（80 格）
        helper.assertTrue(
                GhastVoice.VOLUME == 5.0F,
                "the attack and deflect voice must keep the vanilla volume of 5 (80 blocks), got " + GhastVoice.VOLUME);
        helper.assertTrue(
                ghast.getType().getCategory() == MobCategory.MONSTER,
                "ghastfruity must spawn through the monster channel");

        Player far = helper.makeMockPlayer(GameType.SURVIVAL);
        far.setPos(4.5D, 2.0D, 48.5D);
        helper.assertTrue(
                far.distanceTo(ghast) > 24.0D,
                "sanity: the attacker must stand outside the follow range, was " + far.distanceTo(ghast));
        ghast.hurt(ghast.damageSources().playerAttack(far), 1.0F);
        helper.assertTrue(
                ghast.getTarget() == far,
                "an attack from beyond the 24 block follow range must still make the ghastfruity retaliate, got "
                        + ghast.getTarget());

        Class<?> urGhastFireball = classOrNull("twilightforest.entity.projectile.UrGhastFireball");
        helper.assertTrue(
                urGhastFireball != null,
                "Twilight Forest is required at runtime, so twilightforest.entity.projectile.UrGhastFireball must exist");
        helper.assertTrue(
                LargeFireball.class.isAssignableFrom(urGhastFireball),
                "the ur-ghast fireball must stay a LargeFireball, otherwise the ghastfruity stops deflecting it");

        // 出膛点：脸的正面在体中心前 0.75 格，球口贴着它；原版恶魂那 4 格是给 4 格见方的身体写的
        ghast.setPos(0.0D, 2.0D, 0.0D);
        ghast.setYRot(90.0F);
        ghast.setXRot(0.0F);
        Vec3 mouth = GhastFruityEntity.mouthPosition(ghast);
        Vec3 view = ghast.getViewVector(1.0F);
        Vec3 horizontal = mouth.subtract(ghast.getX(), mouth.y, ghast.getZ());
        helper.assertTrue(
                Math.abs(mouth.y - (ghast.getY() + 0.75D)) < 1.0E-6D,
                "the fireball must leave from the face centre (0.75 above the feet), was " + mouth);
        helper.assertTrue(
                Math.abs(horizontal.length() - 1.3D) < 1.0E-4D,
                "the fireball must leave 1.3 blocks ahead of the body centre (the face is at 0.75), was " + mouth);
        helper.assertTrue(
                horizontal.normalize().dot(view) > 0.9998D,
                "the mouth must sit in the facing direction (yRot=" + ghast.getYRot() + " view=" + view + "), was "
                        + mouth);

        // 持块变体：手里那块方块只认暮色森林的三个 ID，外加本模组那颗 052 方雷；
        // 重现方块那一只受击有四成概率整只消失
        helper.assertTrue(
                GhastFruityEntity.HeldBlock.REAPPEARING_BLOCK.block() == TFBlocks.REAPPEARING_BLOCK.get()
                        && GhastFruityEntity.HeldBlock.ENCASED_FIRE_JET.block() == TFBlocks.ENCASED_FIRE_JET.get()
                        && GhastFruityEntity.HeldBlock.GHAST_TRAP.block() == TFBlocks.GHAST_TRAP.get(),
                "the three twilight forest held blocks must stay registered");
        helper.assertTrue(
                GhastFruityEntity.HeldBlock.EXPERIMENT_052.block() == ModBlocks.EXPERIMENT_052.get(),
                "the experiment 052 variant must hold dawnduskterminal:experiment_052, got "
                        + GhastFruityEntity.HeldBlock.EXPERIMENT_052.block());
        helper.assertTrue(
                GhastFruityEntity.HeldBlock.EXPERIMENT_052.ordinal() == GhastFruityEntity.HeldBlock.values().length - 1,
                "a new variant must be appended last, or old saves lose their held block");
        helper.assertTrue(
                GhastFruityEntity.KAMIKAZE_FUSE_TICKS == Experiment052Block.FUSE_TICKS,
                "the 052 variant must use the very fuse of the block, was " + GhastFruityEntity.KAMIKAZE_FUSE_TICKS);
        helper.assertTrue(
                GhastFruityEntity.KAMIKAZE_REACH == 4.0D,
                "the 052 variant must ignite once it is 4 blocks from its target, was "
                        + GhastFruityEntity.KAMIKAZE_REACH);
        helper.assertTrue(
                GhastFruityEntity.KAMIKAZE_HUG > 0.0D
                        && GhastFruityEntity.KAMIKAZE_HUG < GhastFruityEntity.KAMIKAZE_REACH,
                "the fuse must close in to something closer than the ignition range, was "
                        + GhastFruityEntity.KAMIKAZE_HUG);
        ghast.setHeldBlock(GhastFruityEntity.HeldBlock.REAPPEARING_BLOCK);
        helper.assertTrue(ghast.isBlockVariant(), "setting a held block must turn the entity into the block variant");
        boolean dodged = false;
        for (int attempt = 0; attempt < 200 && !dodged; attempt++) {
            ghast.invulnerableTime = 0;
            ghast.setHealth(30.0F);
            dodged = !ghast.hurt(ghast.damageSources().playerAttack(far), 1.0F);
        }
        helper.assertTrue(dodged, "the reappearing block variant must swallow some hits (40% each)");
        MobEffectInstance invisibility = ghast.getEffect(MobEffects.INVISIBILITY);
        helper.assertTrue(
                invisibility != null && !invisibility.isVisible(),
                "a swallowed hit must grant a particle-less invisibility, was " + invisibility);
        helper.assertTrue(
                invisibility.getDuration() > 0 && invisibility.getDuration() <= 100,
                "the invisibility must last 5 seconds, was " + invisibility.getDuration() + " ticks");
        ghast.setHeldBlock(GhastFruityEntity.HeldBlock.NONE);

        // 容量：玩家加载范围内最多 4 只。一批 4 只不算数，累计数量才是被卡住的那个
        helper.assertTrue(
                GhastFruityPopulation.CAP == 4,
                "the natural population cap must be 4, was " + GhastFruityPopulation.CAP);
        for (int i = 0; i < GhastFruityPopulation.CAP; i++) {
            var loaded = helper.spawnWithNoFreeWill(ModEntities.GHASTFRUITY.get(), 6, 2, 4 + i);
            loaded.setNoAi(true);
        }
        helper.assertTrue(
                GhastFruityPopulation.loaded(helper.getLevel()) == GhastFruityPopulation.CAP,
                "four loaded ghastfruitries must be counted, got " + GhastFruityPopulation.loaded(helper.getLevel()));
        helper.assertFalse(
                GhastFruityPopulation.tryReserve(helper.getLevel()),
                "a full loaded population must refuse further natural spawns");

        // 俯角：目标在下面时必须低头。喷火与陷阱的走廊沿着 getViewVector 走，
        // 只转偏航的话走廊永远是水平的，站在地上的目标只能蹭到边缘
        GhastFruityEntity aimer = (GhastFruityEntity) helper.spawn(ModEntities.GHASTFRUITY.get(), 4, 5, 4);
        Pig below = helper.spawn(EntityType.PIG, 12, 2, 4);
        aimer.setTarget(below);

        // 生成时抬高 0.75 格：几何脚下的裙摆要落在地面上，而不是埋进去。
        // 走真正的刷怪蛋路径（EntityType#spawn → finalizeSpawn），helper.spawn 不调 finalizeSpawn
        BlockPos eggPos = helper.absolutePos(new BlockPos(2, 5, 2));
        Entity eggSpawned = ModEntities.GHASTFRUITY
                .get()
                .spawn(
                        helper.getLevel(),
                        new ItemStack(ModItems.GHASTFRUITY_SPAWN_EGG.get()),
                        null,
                        eggPos,
                        MobSpawnType.SPAWN_EGG,
                        false,
                        false);
        helper.assertTrue(eggSpawned instanceof GhastFruityEntity, "the spawn egg must place a ghastfruity");
        helper.assertTrue(
                Math.abs(eggSpawned.getY() - (eggPos.getY() + 0.75D)) < 1.0E-4D,
                "the egg spawn must be lifted by the skirt depth (0.75), got y=" + eggSpawned.getY() + " for "
                        + eggPos.getY());

        for (int tick = 0; tick < 5; tick++) {
            aimer.tick();
        }
        helper.assertTrue(
                aimer.getXRot() > 5.0F, "a lower target must give a real depression, got xRot=" + aimer.getXRot());

        // 大恶魂：生命 250、索敌范围 100 格、碰撞箱 8 格、自然生成上限 1
        helper.assertTrue(
                GhastEliteEntity.MAX_HEALTH == 250.0F,
                "the elite must have 250 health, was " + GhastEliteEntity.MAX_HEALTH);
        helper.assertTrue(
                GhastEliteEntity.FOLLOW_RANGE == 100.0D,
                "the elite must target within 100 blocks, was " + GhastEliteEntity.FOLLOW_RANGE);
        GhastEliteEntity elite = (GhastEliteEntity) helper.spawn(ModEntities.GHAST_ELITE.get(), 4, 5, 12);
        helper.assertTrue(
                elite.getMaxHealth() == 250.0F,
                "the elite attribute map must carry 250 max health, was " + elite.getMaxHealth());
        helper.assertTrue(
                elite.getAttributeValue(Attributes.FOLLOW_RANGE) == 100.0D,
                "the elite follow range must be 100, was " + elite.getAttributeValue(Attributes.FOLLOW_RANGE));
        helper.assertTrue(
                elite.getBbWidth() == 8.0F && elite.getBbHeight() == 8.0F,
                "the elite hitbox must match thehead (8x8x8), was " + elite.getBbWidth() + "x" + elite.getBbHeight());
        // getMaxSpawnClusterSize 未覆写，取值来自原版 Ghast 的 1
        helper.assertTrue(elite.getMaxSpawnClusterSize() == 1, "the elite must spawn one at a time");
        // 转向与回避危险流体分别由一个 goal 承担：缺失时本体既不转身，也不回避 portal_fluid
        helper.assertTrue(
                elite.goalSelector.getAvailableGoals().stream()
                        .anyMatch(wrapped -> wrapped.getGoal().getFlags().contains(Goal.Flag.LOOK)),
                "the elite must register a LOOK goal, otherwise it never turns to its target");
        helper.assertTrue(
                elite.goalSelector.getAvailableGoals().stream()
                        .anyMatch(wrapped -> wrapped.getGoal() instanceof AvoidHazardousFluidGoal),
                "the elite must register AvoidHazardousFluidGoal, otherwise it never retreats from hazardous fluid");
        // 音效取自项目自有的 ghast_universal 一组：常驻、受伤、死亡各一声
        helper.assertTrue(
                invokeSound(elite, "getAmbientSound", new Class<?>[0]) == ModSounds.GHAST_UNIVERSAL_AMBIENT.get(),
                "the elite ambient sound must be ghast_universal_ambient");
        helper.assertTrue(
                invokeSound(
                                elite,
                                "getHurtSound",
                                new Class<?>[] {DamageSource.class},
                                helper.getLevel().damageSources().generic())
                        == ModSounds.GHAST_UNIVERSAL_HURT.get(),
                "the elite hurt sound must be ghast_universal_hurt");
        helper.assertTrue(
                invokeSound(elite, "getDeathSound", new Class<?>[0]) == ModSounds.GHAST_UNIVERSAL_DEATH.get(),
                "the elite death sound must be ghast_universal_death");
        helper.assertTrue(
                GhastElitePopulation.CAP == 1, "the elite natural cap must be 1, was " + GhastElitePopulation.CAP);
        helper.assertTrue(
                GhastElitePopulation.loaded(helper.getLevel()) == 1,
                "one loaded elite must be counted, got " + GhastElitePopulation.loaded(helper.getLevel()));
        helper.assertFalse(
                GhastElitePopulation.tryReserve(helper.getLevel()),
                "a loaded elite must refuse a second natural spawn");

        // 飞行：设定目标点之后本体必须实际移动（移动控制器为原版 GhastMoveControl）
        Vec3 eliteStart = elite.position();
        elite.getMoveControl().setWantedPosition(elite.getX() + 6.0D, elite.getY() + 4.0D, elite.getZ(), 1.0D);
        for (int tick = 0; tick < 30; tick++) {
            elite.tick();
        }
        helper.assertTrue(
                elite.position().distanceToSqr(eliteStart) > 1.0D,
                "the elite must fly towards its waypoint, moved "
                        + elite.position().distanceTo(eliteStart));

        // 索敌之后排队召唤迷你恶魂：本体正下方 ±3 格的方形内，每只带随机延迟依次出现
        elite.setTarget(below);
        elite.tick();
        EntityType<?> ghastlingType = TFEntities.CARMINITE_GHASTLING.get();
        helper.assertTrue(
                minionsOf(helper, elite, ghastlingType).size() < 6,
                "the six minions must not all appear on the same tick");

        List<Entity> minions = new ArrayList<>();
        for (int tick = 0; tick < 120 && minions.size() < 6; tick++) {
            List<Entity> before = minionsOf(helper, elite, ghastlingType);
            elite.tick();
            for (Entity candidate : minionsOf(helper, elite, ghastlingType)) {
                if (!before.contains(candidate)) {
                    minions.add(candidate);
                }
            }
        }
        helper.assertTrue(minions.size() == 6, "the elite must summon six carminite ghastlings, got " + minions.size());
        for (Entity minion : minions) {
            // 落点就在本体下方 ±3 格的方形里，见下面的 MINION_SPAWN_RADIUS 断言；
            // 具体坐标不在这里比：本体与小鬼都在飞，检测到它的那一 tick 已经各自挪了几格
            helper.assertTrue(
                    minion instanceof Mob mob && mob.isPersistenceRequired(),
                    "a summoned minion must not despawn on its own");
        }
        helper.assertTrue(
                minions.stream().anyMatch(minion -> minion instanceof Mob mob && mob.getTarget() == below),
                "the summoned minions must share the elite's target");

        // 小鬼的召唤范围是本体正下方半径 3 格的方形，见 GhastEliteEntity 的 MINION_SPAWN_RADIUS
        helper.assertTrue(
                GhastEliteEntity.MINION_SPAWN_RADIUS == 3.0D,
                "the minions must appear inside a 3 block square, was " + GhastEliteEntity.MINION_SPAWN_RADIUS);

        // 目标点埋在地里时不能永远推着不动：卡住 30 tick 之后把点丢掉、重挑一个
        double buriedY = elite.getY() - 12.0D;
        elite.getMoveControl().setWantedPosition(elite.getX() + 6.0D, buriedY, elite.getZ(), 1.0D);
        Vec3 pinned = elite.position();
        for (int tick = 0; tick < 45; tick++) {
            elite.setDeltaMovement(Vec3.ZERO);
            elite.setPos(pinned.x, pinned.y, pinned.z);
            elite.tick();
        }
        helper.assertTrue(
                elite.getMoveControl().getWantedY() != buriedY,
                "a waypoint it cannot reach must be dropped instead of pushed forever, still wanted y="
                        + elite.getMoveControl().getWantedY());

        // 被召唤的小鬼不再挑人：玩家背对着它也该打；没标记的野生守卫仍按暮色森林的规则拒绝
        Player bystander = helper.makeMockPlayer(GameType.SURVIVAL);
        bystander.setPos(elite.getX(), elite.getY(), elite.getZ() + 6.0D);
        bystander.setYRot(0.0F);
        bystander.setXRot(0.0F);
        Entity marked = ghastlingType.create(helper.getLevel());
        helper.assertTrue(marked instanceof Mob, "a carminite ghastling must be a mob");
        marked.moveTo(elite.getX(), elite.getY(), elite.getZ());
        marked.getPersistentData().putBoolean(GhastEliteEntity.MINION_TAG, true);
        helper.getLevel().addFreshEntity(marked);
        Entity wild = ghastlingType.create(helper.getLevel());
        helper.assertTrue(wild instanceof Mob, "a carminite ghastling must be a mob");
        wild.moveTo(elite.getX(), elite.getY(), elite.getZ());
        helper.getLevel().addFreshEntity(wild);
        try {
            java.lang.reflect.Method shouldAttack = Class.forName("twilightforest.entity.monster.CarminiteGhastling")
                    .getMethod("shouldAttack", LivingEntity.class);
            helper.assertTrue(
                    (Boolean) shouldAttack.invoke(marked, bystander),
                    "a summoned minion must attack a target it can see, even when the player looks away");
            helper.assertFalse(
                    (Boolean) shouldAttack.invoke(wild, bystander),
                    "a wild carminite ghastling must keep the vanilla guard rule");
        } catch (ReflectiveOperationException problem) {
            helper.assertTrue(false, "carminite ghastling hook: " + problem);
        }
        marked.discard();
        wild.discard();

        // 自己人：两种恶魂与小鬼互不算敌人，玩家不算
        Entity kinGhastling = ghastlingType.create(helper.getLevel());
        helper.assertTrue(kinGhastling != null, "a carminite ghastling must be creatable");
        helper.assertTrue(GhastKin.isKin(elite, kinGhastling), "the elite and a carminite ghastling must count as kin");
        helper.assertFalse(GhastKin.isKin(elite, below), "the elite and a player must not count as kin");

        // 同一个施放者不会叠第二道火焰；施放者没了过程当场收尾
        Player caster = helper.makeMockPlayer(GameType.SURVIVAL);
        FireJetProcess.start(helper.getLevel(), caster);
        FireJetProcess.start(helper.getLevel(), caster);
        helper.assertTrue(
                FireJetProcess.activeCount(caster) == 1,
                "a second jet on the same caster must reuse the running one, got "
                        + FireJetProcess.activeCount(caster));
        caster.discard();
        FireJetProcess.tickAll();
        helper.assertTrue(FireJetProcess.activeCount(caster) == 0, "a jet must end as soon as its caster is gone");

        // 交战中不自然消失：有索敌时按持久处理，脱战后回到原版的消失规则
        elite.setTarget(null);
        helper.assertFalse(elite.requiresCustomPersistence(), "an idle elite must keep the vanilla despawn rules");
        elite.setTarget(below);
        helper.assertTrue(
                elite.requiresCustomPersistence(),
                "an engaged elite must not despawn mid fight, even 32 blocks away from the player");

        // 剑能打回去：原版那一支只认 #minecraft:redirectable_projectile 里的弹体
        helper.assertTrue(
                ModEntities.GHAST_ELITE_FIREBALL
                        .get()
                        .builtInRegistryHolder()
                        .is(EntityTypeTags.REDIRECTABLE_PROJECTILE),
                "the elite fireball must be in #minecraft:redirectable_projectile, or a sword hit cannot send it back");

        // 吃碰撞才吃火球：noPhysics 的实体不被任何弹体命中，拨弹会整条失效
        helper.assertFalse(
                elite.noPhysics,
                "the elite must collide, or AbstractHurtingProjectile#canHitEntity rejects it and the deflect never fires");

        // 经验与暮色恶魂的 UrGhast 同值
        helper.assertTrue(
                GhastEliteEntity.XP_REWARD == 317,
                "the elite must drop the UrGhast's 317 experience, was " + GhastEliteEntity.XP_REWARD);

        // 出膛点：模型里的嘴在 (0, 31, -66) 像素 → 脚上方 1.9375 格、水平朝向前方 4.125 格
        elite.setYRot(45.0F);
        elite.setXRot(0.0F);
        Vec3 eliteMouth = GhastEliteEntity.mouthPosition(elite);
        Vec3 eliteView = elite.getViewVector(1.0F);
        Vec3 eliteHorizontal = new Vec3(eliteMouth.x - elite.getX(), 0.0D, eliteMouth.z - elite.getZ());
        helper.assertTrue(
                Math.abs(eliteMouth.y - (elite.getY() + 1.9375D)) < 1.0E-6D,
                "the muzzle must sit 1.9375 blocks above the feet (mouth at y=31 px), was " + eliteMouth);
        helper.assertTrue(
                Math.abs(eliteHorizontal.length() - 4.125D) < 1.0E-4D,
                "the muzzle must sit 4.125 blocks ahead of the body centre (mouth at z=-66 px), was " + eliteMouth);
        helper.assertTrue(
                eliteHorizontal.normalize().dot(new Vec3(eliteView.x, 0.0D, eliteView.z).normalize()) > 0.9998D,
                "the muzzle must lie along the horizontal look direction, was " + eliteMouth);
        elite.setYRot(0.0F);

        // 泪雨只结算伤害与粒子：不吸收小鬼、不给本体回血
        Entity dripping = ghastlingType.create(helper.getLevel());
        helper.assertTrue(dripping != null, "a carminite ghastling must be creatable");
        dripping.moveTo(elite.getX(), elite.getY() + 1.0D, elite.getZ());
        helper.getLevel().addFreshEntity(dripping);
        double beforeHealth = elite.getHealth();
        // 有界推进：最多 10 tick 里必然遇到一次落雨；本体若已被移除也不会卡死
        for (int tick = 0; tick < 10 && elite.tickCount % 10 != 9; tick++) {
            elite.tick();
        }
        helper.assertTrue(!dripping.isRemoved(), "the tear rain must not absorb a summoned minion");
        helper.assertTrue(
                Math.abs(elite.getHealth() - beforeHealth) < 1.0E-4D,
                "the tear rain must not heal the elite, health went " + beforeHealth + " -> " + elite.getHealth());

        // 刷怪蛋路径能放下本体即可：贴地/贴侧面时埋进碰撞箱这问题原版也有，不额外抬高
        BlockPos eliteEggPos = helper.absolutePos(new BlockPos(10, 5, 12));
        Entity eliteEgg = ModEntities.GHAST_ELITE
                .get()
                .spawn(
                        helper.getLevel(),
                        new ItemStack(ModItems.GHAST_ELITE_SPAWN_EGG.get()),
                        null,
                        eliteEggPos,
                        MobSpawnType.SPAWN_EGG,
                        false,
                        false);
        helper.assertTrue(eliteEgg instanceof GhastEliteEntity, "the elite spawn egg must place a ghast_elite");

        // 创造模式手持方块右击 → 变成对应变体；生存模式不许变
        Item reappearing = TFBlocks.REAPPEARING_BLOCK.get().asItem();
        Player creative = helper.makeMockPlayer(GameType.CREATIVE);
        creative.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(reappearing));
        aimer.setHeldBlock(GhastFruityEntity.HeldBlock.NONE);
        helper.assertTrue(
                aimer.mobInteract(creative, InteractionHand.MAIN_HAND).consumesAction(),
                "a creative block click must consume the interaction");
        helper.assertTrue(
                aimer.getHeldBlock() == GhastFruityEntity.HeldBlock.REAPPEARING_BLOCK,
                "holding the reappearing block must turn the ghastfruity into that variant, got "
                        + aimer.getHeldBlock());

        Player survival = helper.makeMockPlayer(GameType.SURVIVAL);
        survival.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(reappearing));
        aimer.setHeldBlock(GhastFruityEntity.HeldBlock.NONE);
        aimer.mobInteract(survival, InteractionHand.MAIN_HAND);
        helper.assertTrue(
                aimer.getHeldBlock() == GhastFruityEntity.HeldBlock.NONE,
                "a survival block click must not change the variant, got " + aimer.getHeldBlock());
        helper.succeed();
    }

    /** 生成表必须真的登记过，否则这一条在群系 JSON 里写了也不会生效 */
    @GameTest(template = "ddt_empty")
    public static void spawnPlacementIsRegistered(GameTestHelper helper) {
        helper.assertTrue(
                SpawnPlacements.hasPlacement(ModEntities.GHASTFRUITY.get()),
                "ghastfruity must have a spawn placement, otherwise natural spawning never even asks the rules");
        helper.succeed();
    }

    /**
     * 白桦林副本的 monster 生成表里必须有它
     *
     * <p>群系 JSON 里的 spawners 是数据包内容，写错 id 或写错分类时游戏不会报错，只是「刷不出来」；
     * 这里直接读注册表里的那一份群系，把列表翻出来对一遍
     */
    @GameTest(template = "ddt_empty")
    public static void oldGrowthBirchForestSpawnsIt(GameTestHelper helper) {
        Registry<Biome> biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        Biome biome = biomes.get(DawnDuskTerminal.id("old_growth_birch_forest"));
        helper.assertTrue(biome != null, "the palette copy dawnduskterminal:old_growth_birch_forest must exist");
        boolean listed = biome.getMobSettings().getMobs(MobCategory.MONSTER).unwrap().stream()
                .anyMatch(data -> data.type == ModEntities.GHASTFRUITY.get());
        helper.assertTrue(
                listed,
                "dawnduskterminal:old_growth_birch_forest must list ghastfruity in its monster spawners, got "
                        + biome.getMobSettings().getMobs(MobCategory.MONSTER).unwrap().stream()
                                .map(data -> data.type + " x" + data.minCount + "-" + data.maxCount)
                                .toList());
        helper.succeed();
    }

    /** 没有索敌时沿来路弹回：速度方向取反，且挪到碰撞箱之外，免得下一 tick 又撞上自己 */
    @GameTest(template = "ddt_empty")
    public static void fireballBouncesBackWithoutTarget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GhastFruityEntity ghast = ghastAt(level);
        ghast.setTarget(null);

        LargeFireball fireball = incomingFireball(level, ghast);
        ProjectileImpactEvent event = new ProjectileImpactEvent(fireball, new EntityHitResult(ghast, ghast.position()));
        GhastFruityEntity.onProjectileImpact(event);

        helper.assertTrue(
                event.isCanceled(), "the impact must be cancelled, otherwise the fireball explodes on contact");
        Vec3 movement = fireball.getDeltaMovement();
        helper.assertTrue(
                Math.abs(movement.x) < 1.0E-6D && Math.abs(movement.y) < 1.0E-6D && movement.z > 0.9D,
                "a fireball that hits a targetless ghastfruity must fly straight back, was " + movement);
        helper.assertTrue(
                !ghast.getBoundingBox().contains(fireball.position()),
                "the bounced fireball must start outside the hitbox, was " + fireball.position());
        helper.succeed();
    }

    /** 有索敌时打向索敌对象的眼睛，并且这一发算恶果自己的攻击 */
    @GameTest(template = "ddt_empty")
    public static void fireballIsAimedAtTheCurrentTarget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GhastFruityEntity ghast = ghastAt(level);
        Pig pig = helper.spawn(EntityType.PIG, 12, 2, 8);
        ghast.setTarget(pig);

        LargeFireball fireball = incomingFireball(level, ghast);
        ProjectileImpactEvent event = new ProjectileImpactEvent(fireball, new EntityHitResult(ghast, ghast.position()));
        GhastFruityEntity.onProjectileImpact(event);

        Vec3 aim = pig.getEyePosition().subtract(ghast.getEyePosition()).normalize();
        Vec3 movement = fireball.getDeltaMovement().normalize();
        helper.assertTrue(
                aim.distanceToSqr(movement) < 1.0E-4D,
                "the deflected fireball must head for the current target, wanted " + aim + " got " + movement);
        helper.assertTrue(
                fireball.getOwner() == ghast, "the deflected fireball must count as the ghastfruity's own projectile");
        helper.succeed();
    }

    /**
     * 大恶魂的火球：爆炸威力 2
     *
     * <p>威力是 {@link GhastEliteFireball#EXPLOSION_POWER}，由火球自己的 {@code onHit} 交给
     * {@code level().explode}（照暮色森林 {@code UrGhastFireball} 的写法，不碰原版的 private 字段）。
     * 这条钉住数值；「直接命中不结算伤害」没有可观察的状态位，靠的是 {@code onHitEntity} 被空实现，
     * 行为在实机验证。
     */
    @GameTest(template = "ddt_empty")
    public static void eliteFireballHasDoubleExplosionPower(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GhastEliteEntity owner = helper.spawn(ModEntities.GHAST_ELITE.get(), 3, 4, 3);
        GhastEliteFireball fireball = new GhastEliteFireball(level, owner, new Vec3(0.0D, 0.0D, 1.0D));
        helper.assertTrue(
                GhastEliteFireball.EXPLOSION_POWER == 2,
                "the elite fireball's explosion power must be 2, was " + GhastEliteFireball.EXPLOSION_POWER);
        helper.assertTrue(
                fireball.getDeltaMovement().length() < 0.2D,
                "the fireball must leave the muzzle at accelerationPower (0.1), like the vanilla and TF fireballs, was "
                        + fireball.getDeltaMovement().length());
        fireball.discard();
        owner.discard();
        helper.succeed();
    }

    /** 三张脸的状态机：没目标 → 平静，有目标 → 不悦，蓄力 → 攻击 */
    @GameTest(template = "ddt_empty")
    public static void faceFollowsTargetAndCharge(GameTestHelper helper) {
        GhastFruityEntity ghast = ghastAt(helper.getLevel());
        helper.assertTrue(
                ghast.currentFace() == GhastFruityEntity.Face.CALM,
                "an idle ghastfruity must wear the calm face, got " + ghast.currentFace());

        ghast.setTarget(helper.makeMockPlayer(GameType.SURVIVAL));
        helper.assertTrue(
                ghast.currentFace() == GhastFruityEntity.Face.ANNOYED,
                "a ghastfruity that has a target must wear the annoyed face, got " + ghast.currentFace());

        ghast.setCharging(true);
        helper.assertTrue(
                ghast.currentFace() == GhastFruityEntity.Face.ATTACK,
                "a charging ghastfruity must wear the attack face, got " + ghast.currentFace());

        ghast.setCharging(false);
        ghast.setTarget(null);
        helper.assertTrue(
                ghast.currentFace() == GhastFruityEntity.Face.CALM,
                "the face must fall back to calm once the target is gone");
        helper.succeed();
    }

    /**
     * 052 那一只：进到 4 格就点火，引信期间以更慢的速度继续靠近，到点自爆——自爆不进死亡流程，所以没有掉落物
     *
     * <p>爆炸按 052 那一份走（威力 6、真的炸方块、真的刷群系），所以这条测试**单独一个 batch**：
     * 同一个 batch 里的阵位只隔 5 格，会互相波及
     *
     * <p>引信是手动一拍一拍推的（与仓库里其它推实体的测试同一做法）：游戏测试的区块有时进不了
     * 实体刻（见 {@code GameTestChunkWaitMixin}），交给世界去 tick 会让这条测试跟着卡住
     *
     * <p>「没有掉落物」由两条一起钉：{@code deathTime == 0} 且血量仍在（说明它从未走 {@code die()}），
     * 以及周围地上没有恶果那三样战利品
     */
    @GameTest(template = "ddt_empty", batch = "ghastfruity_kamikaze", timeoutTicks = 200)
    public static void experiment052VariantHugsThenBlowsItselfUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Pig target = helper.spawn(EntityType.PIG, 4, 3, 4);
        target.setNoAi(true);
        target.setNoGravity(true);
        GhastFruityEntity ghast = helper.spawn(ModEntities.GHASTFRUITY.get(), 4, 3, 10);
        ghast.setHeldBlock(GhastFruityEntity.HeldBlock.EXPERIMENT_052);
        ghast.setTarget(target);

        // 六格开外只许往过压，不许点着
        for (int tick = 0; tick < 3; tick++) {
            ghast.tick();
        }
        helper.assertFalse(
                ghast.isIgnited(),
                "a 052 variant must not ignite from 6 blocks away, the range is " + GhastFruityEntity.KAMIKAZE_REACH);

        // 进到 4 格以内立刻点着，并且换成攻击脸（客户端靠同步位把手里那颗方雷画成发光的那套）
        ghast.moveTo(target.getX(), target.getY(), target.getZ() + 3.5D);
        ghast.tick();
        helper.assertTrue(ghast.isIgnited(), "a 052 variant that got within 4 blocks must ignite");
        helper.assertTrue(
                ghast.currentFace() == GhastFruityEntity.Face.ATTACK,
                "an ignited ghastfruity must wear the attack face, got " + ghast.currentFace());
        ghast.tick();
        helper.assertTrue(ghast.isIgnited(), "the fuse must not un-ignite itself");

        // 引信期间仍在靠近，只是慢：二十 tick 里必须真的近了一些，而且还没提前炸
        double before = ghast.distanceTo(target);
        for (int tick = 0; tick < 20; tick++) {
            ghast.tick();
        }
        double after = ghast.distanceTo(target);
        helper.assertFalse(ghast.isRemoved(), "the fuse must not blow up early");
        helper.assertTrue(
                after < before - 0.05D,
                "an ignited 052 variant must keep closing in, went from " + before + " to " + after);

        // 引信走完：炸，而且不进死亡流程
        Vec3 blast = ghast.position();
        for (int tick = 0; tick <= GhastFruityEntity.KAMIKAZE_FUSE_TICKS && !ghast.isRemoved(); tick++) {
            ghast.tick();
        }
        helper.assertTrue(
                ghast.isRemoved(),
                "the 052 variant must blow itself up once the " + GhastFruityEntity.KAMIKAZE_FUSE_TICKS
                        + " tick fuse runs out");
        helper.assertTrue(
                ghast.deathTime == 0 && ghast.getHealth() > 0.0F,
                "self destructing must not go through the death path, or it would drop loot (deathTime="
                        + ghast.deathTime + ", health=" + ghast.getHealth() + ")");
        List<String> dropped = itemsNear(level, blast, 8.0D).stream()
                .map(item ->
                        BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString())
                .filter(GHASTFRUITY_LOOT::contains)
                .toList();
        helper.assertTrue(dropped.isEmpty(), "a self destructing 052 variant must not drop anything, found " + dropped);
        helper.succeed();
    }

    /** 恶果身上那三样战利品：自爆不许掉它们 */
    private static final List<String> GHASTFRUITY_LOOT = List.of(
            "twilightforest:carminite",
            "dawnduskterminal:carminite_ghast_leather",
            "dawnduskterminal:carminite_ghast_meat");

    /** 半径内的掉落物 */
    private static List<Entity> minionsOf(GameTestHelper helper, GhastEliteEntity elite, EntityType<?> ghastlingType) {
        return helper.getLevel()
                .getEntitiesOfClass(
                        Entity.class,
                        new AABB(elite.blockPosition()).inflate(64.0D),
                        entity -> entity.getType() == ghastlingType);
    }

    private static List<ItemEntity> itemsNear(ServerLevel level, Vec3 around, double radius) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(around, around).inflate(radius));
    }

    /**
     * 反射读取受保护的音效方法，用于断言音效绑定
     *
     * <p>{@code getAmbientSound}、{@code getHurtSound} 与 {@code getDeathSound} 在 {@code LivingEntity}
     * 中为 {@code protected}，测试类位于另一包，只能经反射调用。
     */
    private static SoundEvent invokeSound(Object entity, String method, Class<?>[] parameters, Object... arguments) {
        try {
            Method accessor = entity.getClass().getDeclaredMethod(method, parameters);
            accessor.setAccessible(true);
            return (SoundEvent) accessor.invoke(entity, arguments);
        } catch (ReflectiveOperationException problem) {
            throw new AssertionError(method + " is not readable: " + problem);
        }
    }

    /** 只按类名反射，代码里不 import 暮色森林，与仓库里其它暮色兼容代码同一做法；不触发静态初始化 */
    private static Class<?> classOrNull(String name) {
        try {
            return Class.forName(name, false, GhastFruityTests.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            return null;
        }
    }

    private static GhastFruityEntity ghastAt(ServerLevel level) {
        GhastFruityEntity ghast = new GhastFruityEntity(ModEntities.GHASTFRUITY.get(), level);
        ghast.setPos(4.0D, 2.0D, 4.0D);
        return ghast;
    }

    /** 一发朝 -Z 飞、速度 1 格每 tick 的火球，正是原版恶魂那一发 */
    private static LargeFireball incomingFireball(ServerLevel level, GhastFruityEntity ghast) {
        LargeFireball fireball = new LargeFireball(level, ghast, new Vec3(0.0D, 0.0D, -1.0D), 1);
        fireball.setDeltaMovement(0.0D, 0.0D, -1.0D);
        return fireball;
    }

    private GhastFruityTests() {}
}
