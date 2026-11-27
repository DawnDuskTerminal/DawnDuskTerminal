package com.dawnduskterminal.block;

import com.dawnduskterminal.block.entity.SunflowerBlockEntity;
import com.dawnduskterminal.registry.ModTerrainBlocks;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;

/**
 * 鬼花：外观与它那一侧的花完全相同，行为不同
 *
 * <p>昼侧是 {@link GhoflowerBlock}，夜侧是 {@link LunarGhoflowerBlock}，两个方块、两个物品。
 *
 * <p>掉落与传染在整株结算时发生一次，由父类的 {@code onDestroyedByPlayer} 与 {@code onExplosionHit}
 * 调到 {@link #dropPlant}；{@code onRemove} 只做移除，整株倒塌不会再结算一次。
 */
public class GhoflowerBlock extends AbstractSunflowerBlock {
    /** 攻击期被破坏时抽到鬼花物品的概率 */
    public static final float GHOFLOWER_DROP_CHANCE = 0.15F;

    /** 攻击期被破坏时传染的概率；不在攻击期时固定为 1 */
    public static final float INFECT_CHANCE = 0.4F;

    /** 传染的搜索范围：水平半径与竖直半径，单位格 */
    public static final int INFECT_RADIUS = 8;

    public static final int INFECT_RADIUS_VERTICAL = 4;

    public GhoflowerBlock(Properties properties) {
        super(properties);
    }

    /** 这一株是不是夜侧 */
    public boolean isLunar() {
        return this instanceof LunarGhoflowerBlock;
    }

    /** 本方块对应的物品 */
    public Item ownItem() {
        return this.isLunar() ? ModTerrainBlocks.LUNAR_GHOFLOWER_ITEM.get() : ModTerrainBlocks.GHOFLOWER_ITEM.get();
    }

    /** 本方块对应的花盘方块（传染与掉落都按这一侧走） */
    public Block flowerBlock() {
        return this.isLunar() ? ModTerrainBlocks.MOONFLOWER.get() : ModTerrainBlocks.SUNFLOWER.get();
    }

    /** 创造模式中键取物：给本方块自己的物品 */
    @Override
    public ItemStack getCloneItemStack(
            BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return new ItemStack(this.ownItem());
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || !state.getValue(HEAD)) {
            return null;
        }
        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof SunflowerBlockEntity sunflower) {
                SunflowerBlockEntity.serverTick(tickLevel, pos, tickState, sunflower);
            }
        };
    }

    /**
     * 被破坏时掉一件并传染一次
     *
     * <p>攻击期有 15% 掉本侧鬼花物品，其余情况掉本侧的花；观察期必定传染，攻击期 40%。
     * 由父类在整株结算时调用，所以破坏茎也会结算到这一株。
     */
    @Override
    protected void dropPlant(ServerLevel level, BlockPos headPos, @Nullable Player player) {
        SunflowerBlockEntity sunflower =
                level.getBlockEntity(headPos) instanceof SunflowerBlockEntity entity ? entity : null;
        boolean hunting = sunflower != null && sunflower.isHunting();
        Item drop = hunting && level.getRandom().nextFloat() < GHOFLOWER_DROP_CHANCE
                ? this.ownItem()
                : this.flowerBlock().asItem();
        Block.popResource(level, headPos, new ItemStack(drop));

        double chance = hunting ? INFECT_CHANCE : 1.0D;
        infectNearby(level, headPos, chance, player == null ? null : player.getUUID());
    }

    /** 在搜索范围里随机挑一株同侧的花，把它的顶段换成本方块 */
    private static void infectNearby(ServerLevel level, BlockPos origin, double chance, @Nullable UUID attacker) {
        if (level.getRandom().nextDouble() >= chance) {
            return;
        }
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos candidate : BlockPos.betweenClosed(
                origin.offset(-INFECT_RADIUS, -INFECT_RADIUS_VERTICAL, -INFECT_RADIUS),
                origin.offset(INFECT_RADIUS, INFECT_RADIUS_VERTICAL, INFECT_RADIUS))) {
            BlockState state = level.getBlockState(candidate);
            if ((state.getBlock() instanceof SunflowerBlock || state.getBlock() instanceof MoonflowerBlock)
                    && state.getValue(HEAD)) {
                candidates.add(candidate.immutable());
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        BlockPos target = candidates.get(level.getRandom().nextInt(candidates.size()));
        BlockState replaced = level.getBlockState(target);
        boolean lunar = replaced.getBlock() instanceof MoonflowerBlock;
        level.setBlock(
                target,
                (lunar ? ModTerrainBlocks.LUNAR_GHOFLOWER.get() : ModTerrainBlocks.GHOFLOWER.get())
                        .defaultBlockState()
                        .setValue(SEGMENT, replaced.getValue(SEGMENT))
                        .setValue(HEAD, true),
                Block.UPDATE_CLIENTS);
        if (attacker != null && level.getBlockEntity(target) instanceof SunflowerBlockEntity sunflower) {
            sunflower.startHunting(attacker);
        }
    }
}
