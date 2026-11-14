package com.dawnduskterminal.gametest;

import com.dawnduskterminal.entity.AvoidHazardousFluidGoal;
import com.dawnduskterminal.entity.GhastEliteEntity;
import com.dawnduskterminal.entity.GhastFruityEntity;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModEntities;
import com.dawnduskterminal.registry.ModFluids;
import com.dawnduskterminal.registry.ModScorchingTwilight;
import com.dawnduskterminal.world.HazardousFluid;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 「会伤人的流体」得被躲开
 *
 * <p>两种流体沾上就掉血（{@code portal_fluid} 每 2 秒 2 点、{@code enthu_fluid} 接触即 6 点），
 * 所以生物得把它们当岩浆看。这里钉住三件事：
 *
 * <ul>
 *   <li>两种流体的四个流体（源与流动）都算危险，水与岩浆不算；</li>
 *   <li>走寻路的生物：原版规则把「不是岩浆的液体」一律当可走的 {@link PathType#OPEN}，
 *       而经过 {@code HazardousFluidPathingMixin} 的节点判定会把它降成 {@link PathType#DAMAGE_OTHER}，
 *       于是路径要么绕开、要么根本不含危险格；</li>
 *   <li>不走寻路的飞行生物（{@link AvoidHazardousFluidGoal}）：恶魂变体扫得到身边的流体，
 *       并且把「想去的地方」推到反方向、抬高两格。</li>
 * </ul>
 *
 * <p>寻路那条特意把「原版怎么说」与「我们的节点判定怎么说」并排放：只断言「寻不出路径」是弱证据——
 * 被墙挡住、被石头围住同样寻不出来
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class HazardousFluidAvoidanceTests {
    /** 底板那一层（实心，防止落进虚空） */
    private static final int FLOOR_Y = 0;

    /** 走的那一层：这一层是石头，生物踩在它顶上 */
    private static final int WALK_Y = 1;

    private static final int MIN_X = 1;
    private static final int MAX_X = 9;
    private static final int MIN_Z = 1;
    private static final int MAX_Z = 7;

    /** 沟那一列，横跨整片场地的 z 方向 */
    private static final int TRENCH_X = 5;

    private static final int START_X = 2;
    private static final int TARGET_X = 8;
    private static final int LANE_Z = 4;

    @GameTest(template = "ddt_empty", batch = "hazardous_fluid")
    public static void vanillaWouldWalkStraightIn(GameTestHelper helper) {
        buildArena(helper, ModBlocks.PORTAL_FLUID.get());
        BlockPos fluid = helper.absolutePos(new BlockPos(TRENCH_X, WALK_Y, LANE_Z));
        Mob witness = witness(helper, fluid);
        // 原版对这一格的说法：不是岩浆的液体照走不误——这正是生物会一头扎进去的原因
        PathType vanilla = WalkNodeEvaluator.getPathTypeStatic(witness, fluid);
        helper.assertTrue(
                vanilla.getMalus() >= 0.0F,
                "vanilla must consider the portal fluid passable, otherwise this whole avoidance never had a cause,"
                        + " got " + vanilla + " with malus " + vanilla.getMalus());
        helper.succeed();
    }

    /**
     * 节点判定把这一格降成 {@link PathType#DAMAGE_OTHER}（代价 -1，与岩浆同档），空气不受影响
     *
     * <p>直接问节点判定，不跑整条路径：一条路径是否真的绕开，取决于 A* 怎么挑，而这里要钉的是
     * 「这一格算不算雷区」这一条
     */
    @GameTest(template = "ddt_empty", batch = "hazardous_fluid")
    public static void theNodeEvaluatorRefusesThatCell(GameTestHelper helper) {
        buildArena(helper, ModBlocks.PORTAL_FLUID.get());
        BlockPos fluid = helper.absolutePos(new BlockPos(TRENCH_X, WALK_Y, LANE_Z));
        Mob witness = witness(helper, fluid);
        WalkNodeEvaluator evaluator = new WalkNodeEvaluator();
        PathfindingContext context = new PathfindingContext(helper.getLevel(), witness);

        PathType fluidType = evaluator.getPathTypeOfMob(context, fluid.getX(), fluid.getY(), fluid.getZ(), witness);
        helper.assertTrue(
                fluidType == PathType.DAMAGE_OTHER,
                "the portal fluid cell must come back as DAMAGE_OTHER so no path enters it, got " + fluidType);

        BlockPos air = fluid.above();
        PathType airType = evaluator.getPathTypeOfMob(context, air.getX(), air.getY(), air.getZ(), witness);
        helper.assertTrue(
                airType != PathType.DAMAGE_OTHER,
                "plain air must keep its own path type, got " + airType + " which would block every path");
        helper.succeed();
    }

    /** 一整条路径也不许踩进危险流体：沟横在场地中间，绕得过去，但绕不过去的那条不该被选中 */
    @GameTest(template = "ddt_empty", batch = "hazardous_fluid")
    public static void noPathStepsIntoTheFluid(GameTestHelper helper) {
        buildArena(helper, ModBlocks.PORTAL_FLUID.get());
        ServerLevel level = helper.getLevel();
        Zombie zombie = standingZombie(helper);
        Path path =
                zombie.getNavigation().createPath(helper.absolutePos(new BlockPos(TARGET_X, WALK_Y + 1, LANE_Z)), 1);
        helper.assertTrue(
                path != null,
                "the trench can be walked around, so a path must still exist, got none from " + zombie.blockPosition()
                        + " to " + helper.absolutePos(new BlockPos(TARGET_X, WALK_Y + 1, LANE_Z)));
        for (int i = 0; i < path.getNodeCount(); i++) {
            Node node = path.getNode(i);
            helper.assertFalse(
                    HazardousFluid.isHazard(level, new BlockPos(node.x, node.y, node.z)),
                    "a path must never enter the portal fluid, it went through " + describePath(path));
        }
        zombie.discard();
        helper.succeed();
    }

    /** 对照组：同样的场地、同样的沟，填空气的路径存在 */
    @GameTest(template = "ddt_empty", batch = "hazardous_fluid")
    public static void theSameArenaIsPathableWithPlainAir(GameTestHelper helper) {
        buildArena(helper, Blocks.AIR);
        Zombie zombie = standingZombie(helper);
        Path path =
                zombie.getNavigation().createPath(helper.absolutePos(new BlockPos(TARGET_X, WALK_Y + 1, LANE_Z)), 1);
        helper.assertTrue(
                path != null,
                "control: the very same arena with an air gap must be pathable, otherwise the fluid assertion proves"
                        + " nothing. Zombie stands on " + zombie.blockPosition() + ", target is "
                        + helper.absolutePos(new BlockPos(TARGET_X, WALK_Y + 1, LANE_Z)) + ", on ground "
                        + zombie.onGround());
        zombie.discard();
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "hazardous_fluid")
    public static void bothFluidsCountAsHazards(GameTestHelper helper) {
        helper.assertTrue(
                HazardousFluid.isHazard(ModFluids.PORTAL_FLUID.get().defaultFluidState()),
                "portal fluid source must be a hazard");
        helper.assertTrue(
                HazardousFluid.isHazard(ModFluids.PORTAL_FLOWING.get().defaultFluidState()),
                "flowing portal fluid must be a hazard");
        helper.assertTrue(
                HazardousFluid.isHazard(ModScorchingTwilight.SOURCE.get().defaultFluidState()),
                "enthu fluid source must be a hazard");
        helper.assertTrue(
                HazardousFluid.isHazard(ModScorchingTwilight.FLOWING.get().defaultFluidState()),
                "flowing enthu fluid must be a hazard");
        helper.assertFalse(
                HazardousFluid.isHazard(Fluids.WATER.getSource(false)),
                "plain water only drowns, it must stay pathable");
        helper.assertFalse(
                HazardousFluid.isHazard(Fluids.LAVA.getSource(false)),
                "lava is already a hazard in vanilla, it must not be reclassified by this mod");
        helper.succeed();
    }

    /**
     * 两只不依赖寻路的恶魂：能够发现身边的危险流体，且退开点位于本体与流体连线的反方向上并高于当前位置
     *
     * <p>两只恶魂均为 {@code FlyingMob}，移动完全依赖 {@code MoveControl}，寻路层的约束对其无效。
     * 此处直接执行 {@link AvoidHazardousFluidGoal} 的「发现」与「转向」两个步骤，读取其写入移动控制器的目标点。
     * 该 goal 是否已注册到实体之上，另由 {@code GhastFruityTests} 断言。
     */
    @GameTest(template = "ddt_empty", batch = "hazardous_fluid")
    public static void theFlyingGhastsBackAwayFromTheFluid(GameTestHelper helper) {
        BlockPos threat = new BlockPos(TRENCH_X, WALK_Y + 1, LANE_Z);
        // 一格流体，四周与下方都垫上石头：不垫的话它会顺着模板流出去，把隔壁用例的场地淹掉
        helper.setBlock(threat.below(), Blocks.STONE);
        helper.setBlock(threat.west(), Blocks.STONE);
        helper.setBlock(threat.east(), Blocks.STONE);
        helper.setBlock(threat.north(), Blocks.STONE);
        helper.setBlock(threat.south(), Blocks.STONE);
        helper.setBlock(threat, ModBlocks.PORTAL_FLUID.get());
        ServerLevel level = helper.getLevel();
        Vec3 threatCentre = Vec3.atCenterOf(helper.absolutePos(threat));

        GhastFruityEntity fruity = new GhastFruityEntity(ModEntities.GHASTFRUITY.get(), level);
        fruity.setPos(threatCentre.x - 3.0D, threatCentre.y, threatCentre.z);
        assertBacksAway(helper, fruity, threatCentre);

        GhastEliteEntity elite = new GhastEliteEntity(ModEntities.GHAST_ELITE.get(), level);
        elite.setPos(threatCentre.x - 3.0D, threatCentre.y, threatCentre.z);
        assertBacksAway(helper, elite, threatCentre);
        helper.succeed();
    }

    private static void assertBacksAway(GameTestHelper helper, Mob mob, Vec3 threatCentre) {
        String name = mob.getType().getDescriptionId();
        BlockPos nearest = AvoidHazardousFluidGoal.nearest(mob);
        helper.assertTrue(nearest != null, name + " must spot the portal fluid three blocks away");

        AvoidHazardousFluidGoal goal = new AvoidHazardousFluidGoal(mob);
        helper.assertTrue(goal.canUse(), name + " must want to back away, otherwise the goal never runs");
        goal.start();
        MoveControl control = mob.getMoveControl();
        double before = mob.position().distanceTo(threatCentre);
        double after = new Vec3(control.getWantedX(), mob.getY(), control.getWantedZ()).distanceTo(threatCentre);
        helper.assertTrue(
                after > before,
                name + " must steer away from the fluid, " + before + " blocks out and heading for " + after);
        helper.assertTrue(
                control.getWantedY() > mob.getY(),
                name + " must climb while backing away, wanted y " + control.getWantedY() + " versus " + mob.getY());
    }

    /**
     * 一片 9×7 的场地：底板一层的石头、走一层的石头，中间一列（x = {@value #TRENCH_X}，整个 z 跨度）
     * 留成 {@code trench}
     *
     * <p>沟只有一格宽，所以绕过去只需要从 z 的一端多走两步——这条路对 A* 是通的，于是「路径里没有危险格」
     * 才是个有意义的断言
     */
    private static void buildArena(GameTestHelper helper, Block trench) {
        for (int x = MIN_X - 1; x <= MAX_X + 1; x++) {
            for (int z = MIN_Z - 1; z <= MAX_Z + 1; z++) {
                helper.setBlock(new BlockPos(x, FLOOR_Y, z), Blocks.STONE);
                boolean inTrench = x == TRENCH_X && z >= MIN_Z && z <= MAX_Z;
                helper.setBlock(new BlockPos(x, WALK_Y, z), inTrench ? trench : Blocks.STONE);
            }
        }
    }

    /**
     * 站在地面上的僵尸，只当「是个 Mob」用
     *
     * <p>{@code GroundPathNavigation#canUpdatePath} 要求 {@code onGround()}，而刚生成的实体在这一 tick
     * 里还没落地，所以必须先把这一步补上，否则 {@code createPath} 一律返回 {@code null}
     */
    private static Zombie standingZombie(GameTestHelper helper) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, START_X, WALK_Y + 1, LANE_Z);
        zombie.setOnGround(true);
        return zombie;
    }

    /** 不进世界的 Mob：节点判定只看它的碰撞箱与流体容忍度，用不着它出现在世界里 */
    private static Mob witness(GameTestHelper helper, BlockPos at) {
        Zombie zombie = new Zombie(EntityType.ZOMBIE, helper.getLevel());
        zombie.setPos(at.getX() + 0.5D, at.getY() + 1.0D, at.getZ() + 0.5D);
        return zombie;
    }

    /** 一条路径的节点串起来，只为失败信息里看得见它到底怎么过去的 */
    private static String describePath(Path path) {
        if (path == null) {
            return "(none)";
        }
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < path.getNodeCount(); i++) {
            Node node = path.getNode(i);
            text.append('(')
                    .append(node.x)
                    .append(',')
                    .append(node.y)
                    .append(',')
                    .append(node.z)
                    .append(')');
        }
        return text.append(']').toString();
    }

    private HazardousFluidAvoidanceTests() {}
}
