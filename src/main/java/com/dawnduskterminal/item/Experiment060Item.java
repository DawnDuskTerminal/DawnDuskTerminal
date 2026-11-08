package com.dawnduskterminal.item;

import com.dawnduskterminal.registry.ModTerrainBlocks;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 试验物品 060：那支往下探的金探针
 *
 * <p>单击右键，从使用者正下方那一格开始往下找第一块石头类方块，再从那一格继续往下，
 * 跳过一路的石头与沙土，停在第一块既不是石头、也不是沙土的方块上，用 actionbar 报出它的名字。
 * 空气也算结果：石头下面碰巧是个洞，报的就是「空气」
 *
 * <p>「石头类」是基岩、粉砂岩，加上原版那两组基础岩层 {@code #minecraft:base_stone_overworld}（数据包已扩到含
 * 本模组的苍穹石、紫苏岩、榴辉岩、绿片岩、赭石、暮光石）与 {@code #minecraft:base_stone_nether}；
 * 「沙土类」是 {@code #minecraft:dirt}（含草方块、霞土、霞草）与 {@code #minecraft:sand}（含路之沙、不醒沙）。
 * 两类之外的东西都是结果：矿石、砂砾、冰霜、流体、空气都算
 *
 * <p>探到了才扣 1 点耐久，{@value #DURABILITY} 点正好 256 次；
 * 一路只有石头与沙土、或者压根没见到石头（悬在空中、下面是水），都只报「下方没有东西」，不扣耐久。
 * 扫到世界底部（{@code getMinBuildHeight()}）还没结果，也算没有东西
 */
public class Experiment060Item extends Item {
    public static final int DURABILITY = 256;

    /** 探到东西时那条 actionbar，参数是方块自己的名字 */
    public static final String FOUND_KEY = "message.dawnduskterminal.experiment_060.found";

    /** 什么都没探到那条 actionbar，没有参数 */
    public static final String NOTHING_KEY = "message.dawnduskterminal.experiment_060.nothing";

    public Experiment060Item(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel server && player instanceof ServerPlayer serverPlayer) {
            Optional<BlockPos> found = probe(server, player.blockPosition());
            if (found.isPresent()) {
                BlockState state = server.getBlockState(found.get());
                serverPlayer.displayClientMessage(
                        Component.translatable(FOUND_KEY, state.getBlock().getName()), true);
                stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
            } else {
                serverPlayer.displayClientMessage(Component.translatable(NOTHING_KEY), true);
            }
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    /**
     * 从 {@code origin} 往下的第一块石头类方块起算，返回再往下第一块既不是石头、也不是沙土的方块
     *
     * <p>起点是 {@code origin} 下面一格，不含 {@code origin} 自己：玩家站在石头里时，脚所在的那一格是石头，
     * 但那一格不叫「下方」。一路没见到石头，或者扫到 {@code getMinBuildHeight()} 都没结果，都返回空
     *
     * <p>写成一个静态方法是为了让 {@code gametest/Experiment060Tests} 能直接摆一列方块来问它，
     * 不必真的让一个玩家去右键
     */
    public static Optional<BlockPos> probe(LevelReader level, BlockPos origin) {
        int bottom = level.getMinBuildHeight();
        BlockPos.MutableBlockPos cursor = origin.mutable();
        boolean started = false;
        while (cursor.getY() > bottom) {
            cursor.move(0, -1, 0);
            BlockState state = level.getBlockState(cursor);
            if (!started) {
                started = isStoneLike(state);
                continue;
            }
            if (!isStoneLike(state) && !isSandOrDirtLike(state)) {
                return Optional.of(cursor.immutable());
            }
        }
        return Optional.empty();
    }

    /**
     * 石头类：基岩、粉砂岩，加上原版那两组基础岩层
     *
     * <p>基岩与粉砂岩都是本维度地形本身：基岩是底下那一整层（见 {@code noise_settings/chrono.json} 的
     * {@code surface_rule}），粉砂岩是砂岩那一档的岩层（见 {@code docs/biomes.md} 的方块对照表，
     * 白地地表就是它，硬度也比苍穹石高一档、要铁镐），两块都不该是「探到的东西」
     *
     * <p>这两块单独列在这里、不塞进数据包标签，是因为 {@code #minecraft:base_stone_overworld} 同时是花岗岩、
     * 闪长岩、安山岩、凝灰岩、砂砾、黏土、泥土那七种矿脉的替换目标，挂上去它们在地里就会吃掉这两块
     *
     * <p>标签里的六种本模组岩石（苍穹石、紫苏岩、榴辉岩、绿片岩、赭石、暮光石）由数据包扩进
     * {@code #minecraft:base_stone_overworld}（见 {@code data/minecraft/tags/block/base_stone_overworld.json}）
     */
    private static boolean isStoneLike(BlockState state) {
        return state.is(Blocks.BEDROCK)
                || state.is(ModTerrainBlocks.SILTSTONE.get())
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER);
    }

    /**
     * 沙土类：原版 {@code #minecraft:dirt}（含草方块，数据包又扩了霞土与霞草）与
     * {@code #minecraft:sand}（数据包又扩了路之沙与不醒沙）
     */
    private static boolean isSandOrDirtLike(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND);
    }

    @Override
    public void appendHoverText(
            ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.dawnduskterminal.experiment_060.desc")
                .withStyle(ChatFormatting.GRAY));
    }
}
