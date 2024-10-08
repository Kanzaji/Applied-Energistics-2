package appeng.recipes.game;

import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;

import appeng.block.crafting.AbstractCraftingUnitBlock;
import appeng.core.AELog;
import appeng.recipes.AERecipeTypes;

/**
 * Used to handle Upgrading / Disassembly of the Crafting Units in the world.
 */
public class CraftingUnitTransformRecipe extends CustomRecipe {
    public static final MapCodec<CraftingUnitTransformRecipe> CODEC = RecordCodecBuilder.mapCodec((builder) -> {
        return builder.group(
                ResourceLocation.CODEC.fieldOf("block").forGetter(CraftingUnitTransformRecipe::getBlock),
                BuiltInRegistries.ITEM.byNameCodec().listOf().optionalFieldOf("upgrade_items")
                        .forGetter(it -> Optional.ofNullable(it.getUpgradeItems())),
                ItemStack.CODEC.listOf().optionalFieldOf("disassembly_items")
                        .forGetter(it -> Optional.ofNullable(it.getDisassemblyItems())),
                ResourceLocation.CODEC.optionalFieldOf("disassembly_loot_table")
                        .forGetter(it -> Optional.ofNullable(it.getDisassemblyLootTable())))
                .apply(builder,
                        (block, upgradeItems, disassemblyItems, disassemblyLoot) -> new CraftingUnitTransformRecipe(
                                block, upgradeItems.orElse(null), disassemblyItems.orElse(null),
                                disassemblyLoot.orElse(null)));
    });

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftingUnitTransformRecipe> STREAM_CODEC = StreamCodec
            .composite(
                    ResourceLocation.STREAM_CODEC,
                    CraftingUnitTransformRecipe::getBlock,
                    ByteBufCodecs.registry(BuiltInRegistries.ITEM.key()).apply(ByteBufCodecs.list())
                            .apply(ByteBufCodecs::optional),
                    it -> Optional.ofNullable(it.getUpgradeItems()),
                    ItemStack.STREAM_CODEC.apply(ByteBufCodecs.list()).apply(ByteBufCodecs::optional),
                    it -> Optional.ofNullable(it.getDisassemblyItems()),
                    ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs::optional),
                    it -> Optional.ofNullable(it.getDisassemblyLootTable()),
                    (block, upgradeItems, disassemblyItems, disassemblyLoot) -> new CraftingUnitTransformRecipe(block,
                            upgradeItems.orElse(null), disassemblyItems.orElse(null), disassemblyLoot.orElse(null)));

    private final ResourceLocation disassemblyLootTable;
    private final List<ItemStack> disassemblyItems;
    private final List<Item> upgradeItems;
    private final ResourceLocation block;

    public CraftingUnitTransformRecipe(ResourceLocation block, List<Item> upgradeItems,
            List<ItemStack> disassemblyItems, ResourceLocation lootTable) {
        super(CraftingBookCategory.MISC);
        this.upgradeItems = ImmutableList.copyOf(upgradeItems);
        this.disassemblyItems = disassemblyItems;
        this.disassemblyLootTable = lootTable;
        this.block = block;
    }

    public ResourceLocation getBlock() {
        return this.block;
    }

    public List<Item> getUpgradeItems() {
        return upgradeItems;
    }

    public List<ItemStack> getDisassemblyItems() {
        return disassemblyItems.stream().map(ItemStack::copy).toList();
    }

    public ResourceLocation getDisassemblyLootTable() {
        return this.disassemblyLootTable;
    }

    public List<ItemStack> getDisassemblyLoot(Level level, LootParams params) {
        if (this.disassemblyLootTable == null || level.isClientSide())
            return null;

        LootTable table = level
                .getServer()
                .reloadableRegistries()
                .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, disassemblyLootTable));

        if (table == LootTable.EMPTY) {
            AELog.debug("LootTable for Crafting Unit Upgrade %s doesn't exist: %s", null, this.disassemblyLootTable);
            return List.of();
        }

        return table.getRandomItems(params, level.getRandom());
    }

    /**
     * @return True when any Disassembly Output is specified.
     */
    public boolean canDisassemble() {
        return this.disassemblyLootTable != null || (this.disassemblyItems != null && !this.disassemblyItems.isEmpty());
    }

    /**
     * @return True when Disassembly Items aren't specified in the recipe.
     */
    public boolean useLootTable() {
        return this.disassemblyItems == null || this.disassemblyItems.isEmpty();
    }

    /**
     * @return True when Upgrade Items are specified in the recipe.
     */
    public boolean canUpgrade() {
        return this.upgradeItems != null && !this.upgradeItems.isEmpty();
    }

    /**
     * @param stack ItemStack to compare
     * @return True if this recipe matches the provided stack, otherwise false.
     */
    public boolean canUpgradeWith(ItemStack stack) {
        return canUpgrade() && this.upgradeItems.contains(stack.getItem());
    }

    /**
     * Used to get the disassembly recipe based on the provided ResourceLocation. If not found will do a lookup for
     * recipes that specify provided block.
     * 
     * @param level
     * @param location ResourceLocation of the recipe to get.
     * @param block    Fallback ResourceLocation to look for.
     * @return If a single recipe is found - CraftingUnitTransformRecipe, otherwise null.
     */
    public static CraftingUnitTransformRecipe getDisassemblyRecipe(Level level, ResourceLocation location,
            ResourceLocation block) {
        var recipeManager = level.getRecipeManager();
        var recipeHolder = recipeManager.byKey(location);

        // Checking the direct recipe first - if invalid, search for a correct one.
        if (recipeHolder.isPresent() &&
                recipeHolder.get().value() instanceof CraftingUnitTransformRecipe recipe &&
                recipe.canDisassemble() &&
                recipe.getBlock().equals(block))
            return recipe;

        var recipes = recipeManager.byType(AERecipeTypes.UNIT_TRANSFORM).stream()
                .filter(it -> it.value().getBlock().equals(block) && it.value().canDisassemble()).toList();

        if (recipes.size() != 1) {
            if (recipes.size() > 1) {
                AELog.debug("Multiple disassembly recipes found for %s. Disassembly is impossible.", block);
                recipes.forEach(recipe -> AELog.debug("Recipe: %s", recipe.id()));
            }
            return null;
        }
        return recipes.getFirst().value();
    }

    /**
     * Used to get the upgrade recipe for the provided ItemStack.
     * 
     * @param level
     * @param upgradeItem ItemStack to upgrade with.
     * @return If a single recipe is found - CraftingUnitTransformRecipe, otherwise null.
     */
    public static CraftingUnitTransformRecipe getUpgradeRecipe(Level level, ItemStack upgradeItem) {
        List<RecipeHolder<CraftingUnitTransformRecipe>> recipes = level.getRecipeManager()
                .byType(AERecipeTypes.UNIT_TRANSFORM)
                .stream()
                .filter(it -> it.value().canUpgradeWith(upgradeItem))
                .toList();

        if (recipes.size() != 1) {
            if (recipes.size() > 1) {
                AELog.debug("Multiple upgrade recipes found for item {}. Upgrade is impossible.",
                        upgradeItem.getItem());
                recipes.forEach(recipe -> AELog.debug("Recipe: {}", recipe.id()));
            }
            return null;
        }

        var recipe = recipes.getFirst();

        if (BuiltInRegistries.BLOCK.get(recipe.value().getBlock()) instanceof AbstractCraftingUnitBlock<?>)
            return recipe.value();
        AELog.debug("Found invalid Block provided in Crafting Unit Upgrade Recipe: %s", recipe.id());
        return null;
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return false;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return ItemStack.EMPTY;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return CraftingUnitTransformRecipeSerializer.INSTANCE;
    }

    @Override
    public RecipeType<?> getType() {
        return AERecipeTypes.UNIT_TRANSFORM;
    }
}
