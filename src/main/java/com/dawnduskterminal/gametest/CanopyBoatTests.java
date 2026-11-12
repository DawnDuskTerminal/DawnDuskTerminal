package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.entity.CanopyBoatEntity;
import com.dawnduskterminal.item.CanopyBoatItem;
import com.dawnduskterminal.portal.PortalBlock;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModEntities;
import com.dawnduskterminal.registry.ModFluids;
import com.dawnduskterminal.registry.ModItems;
import com.dawnduskterminal.registry.ModScorchingTwilight;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import com.dawnduskterminal.world.BoatFloatingFluid;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import twilightforest.init.TFItems;

/**
 * 苍穹船与两条新材料配方
 *
 * <p>钉住五件事：
 *
 * <ul>
 *   <li>实体与物品都在、船变体是竹筏（贴图与模型都照它那一套）、敲碎掉的是自己而不是竹筏、实体免火</li>
 *   <li>浮得起来的是水、岩浆、不稳定液体三种，热情的暮光不在内</li>
 *   <li>真的浮：水、岩浆、不稳定液体各摆一池子，把船放上去推 40 tick，它得停在水面上、既不沉底也不被岩浆烧掉</li>
 *   <li>坐在岩浆上那一条船里的乘客：上得了船、坐 80 tick 不挨岩浆伤害也不被点着</li>
 *   <li>四条配方：苍穹船（苍穹锭）、狄拉克精炼粉（无序）、狄拉克精炼粉烧成狄拉克锭、玻璃剑；
 *       以及「狄拉克不能直接烧成锭」——狄拉克锭的配方全局只有精炼粉那一条</li>
 * </ul>
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class CanopyBoatTests {
    /** 池子的内壁边长，船 1.375 格宽，塞得下 */
    private static final int POOL = 5;

    @GameTest(template = "ddt_empty", batch = "canopy_boat")
    public static void boatIsRegisteredAsABambooRaft(GameTestHelper helper) {
        CanopyBoatEntity boat = new CanopyBoatEntity(ModEntities.CANOPY_BOAT.get(), helper.getLevel());
        helper.assertTrue(
                ModItems.CANOPY_BOAT.get() instanceof CanopyBoatItem,
                "canopy_boat must be built from CanopyBoatItem, got "
                        + ModItems.CANOPY_BOAT.get().getClass().getSimpleName());
        helper.assertTrue(
                boat.getVariant() == Boat.Type.BAMBOO,
                "the canopy boat must wear the bamboo raft model, got " + boat.getVariant());
        helper.assertTrue(
                boat.getDropItem() == ModItems.CANOPY_BOAT.get(),
                "a broken canopy boat must drop itself, it drops " + boat.getDropItem());
        helper.assertTrue(
                boat.getType().getCategory() == MobCategory.MISC,
                "a boat belongs to the MISC category, got " + boat.getType().getCategory());
        helper.assertTrue(
                boat.getType().getDimensions().width() == 1.375F
                        && boat.getType().getDimensions().height() == 0.5625F,
                "the hitbox must be the vanilla boat's 1.375 x 0.5625, got "
                        + boat.getType().getDimensions().width() + "x"
                        + boat.getType().getDimensions().height());
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "canopy_boat")
    public static void exactlyThreeFluidsFloatTheBoat(GameTestHelper helper) {
        helper.assertTrue(
                BoatFloatingFluid.floatsBoat(Fluids.WATER.getSource(false)), "water must float the canopy boat");
        helper.assertTrue(
                BoatFloatingFluid.floatsBoat(Fluids.FLOWING_WATER.getSource(false)),
                "flowing water must float the canopy boat");
        helper.assertTrue(BoatFloatingFluid.floatsBoat(Fluids.LAVA.getSource(false)), "lava must float the boat");
        helper.assertTrue(
                BoatFloatingFluid.floatsBoat(Fluids.FLOWING_LAVA.getSource(false)), "flowing lava must float the boat");
        helper.assertTrue(
                BoatFloatingFluid.floatsBoat(ModFluids.PORTAL_FLUID.get().defaultFluidState()),
                "portal fluid must float the boat");
        helper.assertTrue(
                BoatFloatingFluid.floatsBoat(ModFluids.PORTAL_FLOWING.get().defaultFluidState()),
                "flowing portal fluid must float the boat");
        helper.assertFalse(
                BoatFloatingFluid.floatsBoat(ModScorchingTwilight.SOURCE.get().defaultFluidState()),
                "enthu fluid burns whatever touches it, the boat must not float there");
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "canopy_boat")
    public static void boatFloatsOnWater(GameTestHelper helper) {
        assertFloats(helper, Blocks.WATER);
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "canopy_boat")
    public static void boatFloatsOnLava(GameTestHelper helper) {
        assertFloats(helper, Blocks.LAVA);
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "canopy_boat")
    public static void boatFloatsOnPortalFluid(GameTestHelper helper) {
        assertFloats(helper, ModBlocks.PORTAL_FLUID.get());
        helper.succeed();
    }

    @GameTest(template = "ddt_empty", batch = "canopy_boat")
    public static void theNewRecipesArePinned(GameTestHelper helper) {
        RecipeManager manager = helper.getLevel().getRecipeManager();

        // 苍穹船：五块苍穹锭摆成原版船那个 U 形
        RecipeHolder<?> boat = recipe(helper, manager, "canopy_boat");
        helper.assertTrue(boat.value() instanceof ShapedRecipe, "canopy_boat must be a shaped recipe");
        assertResult(helper, boat, ModItems.CANOPY_BOAT.get());
        assertAccepts(helper, boat, new ItemStack(ModItems.CANOPY_INGOT.get()));

        // 狄拉克精炼粉：苍穹锭 + 混乱石 + 狄拉克 + 弦，无序
        RecipeHolder<?> mix = recipe(helper, manager, "dirac_mix");
        helper.assertTrue(mix.value() instanceof ShapelessRecipe, "dirac_mix must be a shapeless recipe");
        assertResult(helper, mix, ModItems.DIRAC_MIX.get());
        helper.assertTrue(
                mix.value().getIngredients().size() == 4,
                "dirac_mix must take exactly four ingredients, got "
                        + mix.value().getIngredients().size());
        for (ItemStack ingredient : List.of(
                new ItemStack(ModItems.CANOPY_INGOT.get()),
                new ItemStack(ModTerrainBlocks.CHAOS_STONE_ITEM.get()),
                new ItemStack(ModItems.DIRAC.get()),
                new ItemStack(ModItems.STRING.get()))) {
            assertAccepts(helper, mix, ingredient);
        }

        // 烧炼：狄拉克精炼粉 → 狄拉克锭
        RecipeHolder<?> smelting = recipe(helper, manager, "dirac_mix_smelting");
        assertResult(helper, smelting, ModItems.DIRAC_INGOT.get());
        assertAccepts(helper, smelting, new ItemStack(ModItems.DIRAC_MIX.get()));

        // 狄拉克锭只能从精炼粉烧出来：原来那两条「狄拉克直接烧成锭」的配方必须不在了，
        // 只要留着一条，精炼粉这一步就白做了
        for (String shortcut : List.of("dirac_ingot_smelting", "dirac_ingot_blasting")) {
            ResourceLocation id = DawnDuskTerminal.id(shortcut);
            helper.assertTrue(
                    manager.byKey(id).isEmpty(),
                    "recipe " + id + " must not exist any more, dirac ingot comes from dirac_mix only");
        }
        helper.assertTrue(
                manager.getRecipes().stream()
                                .filter(holder -> holder.value()
                                        .getResultItem(helper.getLevel().registryAccess())
                                        .is(ModItems.DIRAC_INGOT.get()))
                                .count()
                        == 1,
                "dirac_ingot must come from exactly one recipe (dirac_mix_smelting), got "
                        + manager.getRecipes().stream()
                                .filter(holder -> holder.value()
                                        .getResultItem(helper.getLevel().registryAccess())
                                        .is(ModItems.DIRAC_INGOT.get()))
                                .map(RecipeHolder::id)
                                .toList());

        // 玻璃剑：易碎的苍穹 / 凝固的苍穹 / 苍穹剑 / 锻铁锭
        Item canopySword = BuiltInRegistries.ITEM.get(DawnDuskTerminal.id("canopy_sword"));
        helper.assertFalse(canopySword == Items.AIR, "dawnduskterminal:canopy_sword must be registered");
        RecipeHolder<?> glass = recipe(helper, manager, "glass_sword");
        assertResult(helper, glass, TFItems.GLASS_SWORD.get());
        for (ItemStack ingredient : List.of(
                new ItemStack(ModTerrainBlocks.FRAGILE_CANOPY.get()),
                new ItemStack(ModTerrainBlocks.FROZEN_CANOPY.get()),
                new ItemStack(canopySword),
                new ItemStack(TFItems.WROUGHT_IRON_BAR.get()))) {
            assertAccepts(helper, glass, ingredient);
        }
        helper.succeed();
    }

    /**
     * 一池子液体，把船放在水面上推 40 tick
     *
     * <p>池子是一格深、四周一圈石头的 5x5：液体既流不出去，船也划不出去。船停在水面上时
     * {@code y ≈ 液面高度 − 船高 + 0.101 ≈ 1.4}；要是漂浮判定没生效，它会一路落到池底（{@code y = 1.0}），
     * 两种结果差着 0.4 格，断言按 1.2 分开
     */
    private static void assertFloats(GameTestHelper helper, Block fluid) {
        CanopyBoatEntity boat = boatOnPool(helper, fluid);
        for (int tick = 0; tick < 40; tick++) {
            boat.tick();
        }
        // 断言用模板内的相对高度：结构整体摆在 y=-60 那一带，绝对坐标是负数
        double localY = boat.getY() - helper.absolutePos(BlockPos.ZERO).getY();
        helper.assertTrue(
                localY > 1.2D,
                "the canopy boat must float on " + fluid + " instead of sinking to the pool floor, it sits at y="
                        + localY + " (" + boat.blockPosition() + ", underwater " + boat.isUnderWater() + ", type "
                        + boat.getType().getDescriptionId() + ")");
        helper.assertTrue(
                localY < 1.9D,
                "the canopy boat must settle on the surface of " + fluid + ", it climbed to y=" + localY + " at "
                        + boat.blockPosition());
        helper.assertFalse(
                boat.isUnderWater(), "a floating canopy boat must not be flagged as underwater, it is at y=" + localY);
        // 岩浆那一档的关键一条：船的耐久只有 40 点，岩浆里的非生物实体每 tick 挨 4 点 lava 伤害
        // （Entity#lavaHurt），十来个 tick 就没。免火之后它必须活到 40 tick 之后还在
        helper.assertFalse(
                boat.isRemoved(),
                "the canopy boat must survive 40 ticks on " + fluid
                        + ", a boat in lava dies within a dozen ticks without fire immunity");
        boat.discard();
    }

    /**
     * 岩浆上的乘客：上得了船，坐满 80 tick 既不挨岩浆伤害也不被点着
     *
     * <p>别指望「几何上碰巧没泡进去」：船浮在液面上时乘客脚底只比液面高零点零几格，液体落在流动格上时
     * 液面还更低，所以 {@code CanopyBoatRiderMixin} 把「坐在苍穹船上」这一种情况的 {@code Entity#lavaHurt}
     * 整个跳过。这条用例把两件事都试一遍：先上船（{@code startRiding} 要过 {@code Boat#canAddPassenger}），
     * 再把船与乘客各推 80 tick，乘客的血量与身上的火都得一动不动
     */
    @GameTest(template = "ddt_empty", batch = "canopy_boat")
    public static void riderOnLavaIsNotHarmed(GameTestHelper helper) {
        CanopyBoatEntity boat = boatOnPool(helper, Blocks.LAVA);
        // 先让它浮稳：刚放下那几 tick 还在往下掉，状态还不是 IN_WATER
        for (int tick = 0; tick < 20; tick++) {
            boat.tick();
        }
        Pig rider = helper.spawn(EntityType.PIG, new Vec3(boat.getX(), boat.getY() + 1.0D, boat.getZ()));
        rider.setNoAi(true);
        helper.assertTrue(
                rider.startRiding(boat),
                "a rider must be able to board the canopy boat while it floats on lava, boat at " + boat.blockPosition()
                        + ", underwater " + boat.isUnderWater());
        helper.assertTrue(rider.getVehicle() == boat, "the rider must be seated on the canopy boat");
        float before = rider.getHealth();
        for (int tick = 0; tick < 80; tick++) {
            boat.tick();
            // 乘客自己那一拍：rideTick → baseTick，岩浆的判定就在里面
            rider.tick();
        }
        helper.assertFalse(boat.isRemoved(), "the canopy boat must survive 80 ticks of riding on lava");
        helper.assertTrue(
                rider.getVehicle() == boat,
                "the rider must stay on the boat over lava, it rides " + rider.getVehicle());
        helper.assertTrue(
                rider.getRemainingFireTicks() <= 0,
                "the rider must not be set on fire by the lava under the boat, remaining fire ticks "
                        + rider.getRemainingFireTicks() + ", health " + rider.getHealth());
        helper.assertTrue(
                Math.abs(rider.getHealth() - before) < 1.0E-4F,
                "the rider must take no lava damage while the canopy boat carries it, health " + before + " -> "
                        + rider.getHealth() + " (rider y " + rider.getY() + ", boat y " + boat.getY() + ")");
        helper.assertFalse(rider.isRemoved(), "the rider must still be there after the ride");
        rider.discard();
        boat.discard();
        helper.succeed();
    }

    /** 一格深的池子（四周两格高的石头墙）加一条飘在池子正中的船 */
    private static CanopyBoatEntity boatOnPool(GameTestHelper helper, Block fluid) {
        for (int x = 0; x <= POOL + 1; x++) {
            for (int z = 0; z <= POOL + 1; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                boolean rim = x == 0 || x == POOL + 1 || z == 0 || z == POOL + 1;
                helper.setBlock(new BlockPos(x, 1, z), rim ? Blocks.STONE : fluid);
                if (rim) {
                    // 两格高的围墙：液体流不出去，船也挤不出去
                    helper.setBlock(new BlockPos(x, 2, z), Blocks.STONE);
                    helper.setBlock(new BlockPos(x, 3, z), Blocks.STONE);
                }
            }
        }
        return helper.spawn(ModEntities.CANOPY_BOAT.get(), POOL / 2 + 1, 2, POOL / 2 + 1);
    }

    /** 免火不是可选项：岩浆里的船会被 {@code Entity#lavaHurt} 每 tick 打掉 4 点，见上面的断言 */
    @GameTest(template = "ddt_empty", batch = "canopy_boat")
    public static void boatTypeIsFireImmune(GameTestHelper helper) {
        helper.assertTrue(
                ModEntities.CANOPY_BOAT.get().fireImmune(),
                "the canopy boat must be fire immune, otherwise lava destroys it in a dozen ticks");
        helper.succeed();
    }

    /**
     * 坐在船上的人不吃不稳定液体的那一套：不挨 stability 伤害，也不会被传送
     *
     * <p>船浮在液面上时乘客的脚底其实在液面之上，但方块判定只看「箱子有没有压进这一格的方块」，
     * 所以 {@code PortalBlock#entityInside} 照样会在乘客身上触发。船就是造来在上面行船的，
     * 那一路对它放行
     *
     * <p>这里直接调 {@code entityInside}：对照组是「没上船的猪」，它每 40 tick 该挨一下 2 点
     * stability 伤害；实验组是「坐在苍穹船上的猪」，血量一动不动
     */
    @GameTest(template = "ddt_empty", batch = "canopy_boat")
    public static void ridersAreNotAffectedByPortalFluid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = new BlockPos(3, 2, 3);
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE);
        helper.setBlock(pos, ModBlocks.PORTAL_FLUID.get());
        BlockPos absolute = helper.absolutePos(pos);
        PortalBlock portal = ModBlocks.PORTAL_FLUID.get();
        BlockState state = level.getBlockState(absolute);
        helper.assertTrue(
                BoatFloatingFluid.floatsBoat(level.getFluidState(absolute)),
                "sanity: the block at " + absolute + " must hold portal fluid, it holds " + state);

        // 对照组：没上船的猪，tickCount 卡在 40 的倍数上就该挨一下
        Pig loose =
                helper.spawn(EntityType.PIG, new Vec3(absolute.getX() + 0.5D, absolute.getY(), absolute.getZ() + 0.5D));
        loose.tickCount = 40;
        float looseBefore = loose.getHealth();
        portal.entityInside(state, level, absolute, loose);
        helper.assertTrue(
                loose.getHealth() < looseBefore,
                "control: a pig that is not riding must take the 2 point stability hit, it has " + loose.getHealth());

        // 实验组：坐在苍穹船上的猪，同样压在液体那一格里，血量不动
        CanopyBoatEntity boat = helper.spawn(ModEntities.CANOPY_BOAT.get(), pos.getX(), pos.getY(), pos.getZ());
        Pig rider =
                helper.spawn(EntityType.PIG, new Vec3(absolute.getX() + 0.5D, absolute.getY(), absolute.getZ() + 0.5D));
        rider.tickCount = 40;
        helper.assertTrue(rider.startRiding(boat), "the pig must manage to board the canopy boat");
        helper.assertTrue(rider.getVehicle() == boat, "the pig must be riding the canopy boat");
        float riderBefore = rider.getHealth();
        portal.entityInside(state, level, absolute, rider);
        helper.assertTrue(
                Math.abs(rider.getHealth() - riderBefore) < 1.0E-4F,
                "a rider on the canopy boat must not take stability damage from the fluid it floats on, went from "
                        + riderBefore + " to " + rider.getHealth());
        helper.assertTrue(
                rider.level() == level && rider.getVehicle() == boat,
                "a rider on the canopy boat must not be teleported away by the fluid");
        loose.discard();
        rider.discard();
        boat.discard();
        helper.succeed();
    }

    private static RecipeHolder<?> recipe(GameTestHelper helper, RecipeManager manager, String path) {
        ResourceLocation id = DawnDuskTerminal.id(path);
        RecipeHolder<?> holder = manager.byKey(id).orElse(null);
        helper.assertTrue(holder != null, "recipe " + id + " is missing from the recipe manager");
        return holder;
    }

    private static void assertResult(GameTestHelper helper, RecipeHolder<?> holder, Item expected) {
        ItemStack result = holder.value().getResultItem(helper.getLevel().registryAccess());
        helper.assertTrue(
                result.is(expected),
                holder.id() + " must craft " + expected.getDescriptionId() + ", it crafts " + result);
    }

    private static void assertAccepts(GameTestHelper helper, RecipeHolder<?> holder, ItemStack stack) {
        helper.assertTrue(
                holder.value().getIngredients().stream().anyMatch(ingredient -> ingredient.test(stack)),
                holder.id() + " must accept " + stack.getItem().getDescriptionId() + ", its ingredients are "
                        + holder.value().getIngredients());
    }

    private CanopyBoatTests() {}
}
