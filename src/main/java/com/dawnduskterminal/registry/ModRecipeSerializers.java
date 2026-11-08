package com.dawnduskterminal.registry;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.crafting.TagSmeltingRecipe;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 配方序列化器
 *
 * <p>只有一条：{@link TagSmeltingRecipe} 用的 {@code dawnduskterminal:tag_smelting}。
 * 它的配方类型仍然是 {@code minecraft:smelting}（熔炉只认这个类型），序列化器只是读取 JSON 的那一手
 */
public final class ModRecipeSerializers {
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, DawnDuskTerminal.MOD_ID);

    /** 产物按物品标签现查的熔炉配方，见 {@link TagSmeltingRecipe} */
    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<TagSmeltingRecipe>> TAG_SMELTING =
            SERIALIZERS.register("tag_smelting", () -> new RecipeSerializer<TagSmeltingRecipe>() {
                @Override
                public MapCodec<TagSmeltingRecipe> codec() {
                    return TagSmeltingRecipe.CODEC;
                }

                @Override
                public StreamCodec<RegistryFriendlyByteBuf, TagSmeltingRecipe> streamCodec() {
                    return TagSmeltingRecipe.STREAM_CODEC;
                }
            });

    private ModRecipeSerializers() {}

    public static void register(IEventBus bus) {
        SERIALIZERS.register(bus);
    }
}
