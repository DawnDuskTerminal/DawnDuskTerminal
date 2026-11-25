package com.dawnduskterminal.client.compat;

import com.dawnduskterminal.block.MimicBlock;
import net.minecraft.world.item.ItemStack;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin
public final class MimicJadePlugin implements IWailaPlugin {
    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.addRayTraceCallback((hit, accessor, originalAccessor) -> {
            if (accessor instanceof BlockAccessor block && block.getBlock() instanceof MimicBlock) {
                var ore = MimicBlock.disguise(block.getLevel(), block.getBlockState(), block.getPosition());
                return registration
                        .blockAccessor()
                        .from(block)
                        .fakeBlock(new ItemStack(ore))
                        .build();
            }
            return accessor;
        });
    }
}
