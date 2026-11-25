package com.dawnduskterminal.portal;

import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModDimensions;
import com.dawnduskterminal.registry.ModItems;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import twilightforest.init.TFDimension;

public enum PortalRoute {
    OVERWORLD(Level.OVERWORLD, ModBlocks.PORTAL_FLUID),
    TWILIGHT(TFDimension.DIMENSION_KEY, ModBlocks.TWILIGHT_PORTAL),
    NETHER(Level.NETHER, ModBlocks.NETHER_PORTAL);

    private final ResourceKey<Level> endpoint;
    private final Supplier<? extends Block> block;

    PortalRoute(ResourceKey<Level> endpoint, Supplier<? extends Block> block) {
        this.endpoint = endpoint;
        this.block = block;
    }

    public Block block() {
        return block.get();
    }

    public ResourceKey<Level> destination(ResourceKey<Level> source) {
        if (source.equals(ModDimensions.CHRONO)) return endpoint;
        return source.equals(endpoint) ? ModDimensions.CHRONO : null;
    }

    public static PortalRoute from(BlockState state) {
        for (PortalRoute route : values()) {
            if (state.is(route.block())) return route;
        }
        return null;
    }

    public static PortalRoute poolRoute(ResourceKey<Level> source, ItemStack stack) {
        if (stack.is(Items.DIAMOND) && source.equals(ModDimensions.CHRONO)) return TWILIGHT;
        if (!stack.is(ModItems.ENCASED_DUST.get())) return null;
        if (source.equals(TFDimension.DIMENSION_KEY)) return TWILIGHT;
        return OVERWORLD.destination(source) == null ? null : OVERWORLD;
    }
}
