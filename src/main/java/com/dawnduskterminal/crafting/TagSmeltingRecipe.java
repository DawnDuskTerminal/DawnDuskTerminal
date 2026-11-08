package com.dawnduskterminal.crafting;

import com.dawnduskterminal.registry.ModRecipeSerializers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CookingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;

/**
 * 产物按物品标签现查的熔炉配方：烧出来的是标签里的第一个物品
 *
 * <p>原版配方的产物必须是写死的物品 ID，而这里要的是「客户端里装了银就烧成银锭，没装就当没有这条配方」。
 * 写死一个模组的银锭 ID，换个整合包就不成立；为每个可能的模组各写一条配方又会撞成一堆重复配方
 * （同一个输入出现多条匹配，熔炉取哪一条不确定）。所以做成一条按 {@code c:ingots/silver}
 * 这类通用标签解析的配方：谁提供了银、谁把银锭加进那条标签，这条配方就烧谁
 *
 * <p>没有标签、或者标签是空的，也就是这一局里根本没有银——{@link #matches} 直接回 {@code false}，
 * 对熔炉来说等于这条配方不存在，输入物不会被吞掉
 *
 * <p>继承的是 {@link SmeltingRecipe} 而不是 {@link AbstractCookingRecipe}：熔炉与别的模组（JEI 之类）
 * 都会按 {@code minecraft:smelting} 这个类型取配方，取出来当成 {@link SmeltingRecipe} 用。
 * 类型与序列化器不是一回事，所以这里只换 {@link #getSerializer()}
 */
public class TagSmeltingRecipe extends SmeltingRecipe {
    public static final MapCodec<TagSmeltingRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.STRING.optionalFieldOf("group", "").forGetter(recipe -> recipe.group),
                    CookingBookCategory.CODEC
                            .fieldOf("category")
                            .orElse(CookingBookCategory.MISC)
                            .forGetter(AbstractCookingRecipe::category),
                    Ingredient.CODEC_NONEMPTY.fieldOf("ingredient").forGetter(recipe -> recipe.ingredient),
                    TagKey.codec(Registries.ITEM).fieldOf("result_tag").forGetter(recipe -> recipe.resultTag),
                    Codec.FLOAT.fieldOf("experience").orElse(0.0F).forGetter(recipe -> recipe.experience),
                    Codec.INT.fieldOf("cookingtime").orElse(200).forGetter(recipe -> recipe.cookingTime))
            .apply(instance, TagSmeltingRecipe::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, TagSmeltingRecipe> STREAM_CODEC =
            StreamCodec.of(TagSmeltingRecipe::toNetwork, TagSmeltingRecipe::fromNetwork);

    /** 从哪条标签里取产物 */
    private final TagKey<Item> resultTag;

    public TagSmeltingRecipe(
            String group,
            CookingBookCategory category,
            Ingredient ingredient,
            TagKey<Item> resultTag,
            float experience,
            int cookingTime) {
        // 产物不写死：真实结果每次按标签现查，见 resolve
        super(group, category, ingredient, ItemStack.EMPTY, experience, cookingTime);
        this.resultTag = resultTag;
    }

    /** 输入物对得上，而且这条标签里真有东西 */
    @Override
    public boolean matches(SingleRecipeInput input, Level level) {
        return super.matches(input, level) && hasAny(level.registryAccess());
    }

    @Override
    public ItemStack assemble(SingleRecipeInput input, HolderLookup.Provider registries) {
        return resolve(registries).copy();
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return resolve(registries);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.TAG_SMELTING.get();
    }

    /** 标签里有没有东西；这就是「这一局里有没有银」那一条判定 */
    private boolean hasAny(HolderLookup.Provider registries) {
        return registries
                .lookupOrThrow(Registries.ITEM)
                .get(this.resultTag)
                .map(holders -> holders.size() > 0)
                .orElse(false);
    }

    /** 标签里的第一个物品；标签不存在或者空的就回空栈 */
    private ItemStack resolve(HolderLookup.Provider registries) {
        HolderSet<Item> holders =
                registries.lookupOrThrow(Registries.ITEM).get(this.resultTag).orElse(null);
        if (holders == null) {
            return ItemStack.EMPTY;
        }
        for (Holder<Item> holder : holders) {
            return new ItemStack(holder.value());
        }
        return ItemStack.EMPTY;
    }

    private static void toNetwork(RegistryFriendlyByteBuf buffer, TagSmeltingRecipe recipe) {
        buffer.writeUtf(recipe.group);
        buffer.writeEnum(recipe.category());
        Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, recipe.ingredient);
        buffer.writeResourceLocation(recipe.resultTag.location());
        buffer.writeFloat(recipe.experience);
        buffer.writeVarInt(recipe.cookingTime);
    }

    private static TagSmeltingRecipe fromNetwork(RegistryFriendlyByteBuf buffer) {
        String group = buffer.readUtf();
        CookingBookCategory category = buffer.readEnum(CookingBookCategory.class);
        Ingredient ingredient = Ingredient.CONTENTS_STREAM_CODEC.decode(buffer);
        TagKey<Item> resultTag = TagKey.create(Registries.ITEM, buffer.readResourceLocation());
        float experience = buffer.readFloat();
        int cookingTime = buffer.readVarInt();
        return new TagSmeltingRecipe(group, category, ingredient, resultTag, experience, cookingTime);
    }
}
