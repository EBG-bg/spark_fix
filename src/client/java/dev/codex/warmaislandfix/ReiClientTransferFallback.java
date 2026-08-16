package dev.codex.warmaislandfix;

import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import me.shedaniel.rei.api.common.entry.InputIngredient;
import me.shedaniel.rei.api.common.transfer.ItemRecipeFinder;
import me.shedaniel.rei.api.common.transfer.info.stack.PlayerInventorySlotAccessor;
import me.shedaniel.rei.api.common.transfer.info.stack.SlotAccessor;
import me.shedaniel.rei.api.common.transfer.info.stack.VanillaSlotAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class ReiClientTransferFallback {
    private static final Logger LOGGER = LoggerFactory.getLogger("warmaislandfix/rei-transfer");

    private ReiClientTransferFallback() {
    }

    public static boolean canTransfer(
            Iterable<SlotAccessor> inventorySlots,
            List<InputIngredient<ItemStack>> inputs
    ) {
        ItemRecipeFinder finder = createFinder(inventorySlots);
        return finder.findRecipe(toOptions(inputs), 1, null);
    }

    public static TransferHandler.Result transfer(
            TransferHandler.Context context,
            List<InputIngredient<ItemStack>> inputs,
            Iterable<SlotAccessor> inputSlots,
            Iterable<SlotAccessor> inventorySlots
    ) {
        Minecraft minecraft = context.getMinecraft();
        Player player = minecraft.player;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player == null || gameMode == null) {
            return failed("error.warmaislandfix.rei_transfer.unavailable");
        }

        AbstractContainerMenu menu = context.getMenu();
        List<SlotAccessor> inputAccessors = snapshot(inputSlots);
        List<SlotAccessor> inventoryAccessors = snapshot(inventorySlots);
        List<Integer> inputIndices = resolveIndices(menu, player, inputAccessors);
        List<Integer> inventoryIndices = resolveIndices(menu, player, inventoryAccessors);
        if (inputIndices.contains(-1) || inventoryIndices.contains(-1) || inputIndices.size() < inputs.size()) {
            return failed("error.warmaislandfix.rei_transfer.unsupported");
        }

        minecraft.setScreenAndShow(context.getContainerScreen());
        if (!parkCursor(gameMode, menu, player, inventoryIndices)) {
            return failed("error.warmaislandfix.rei_transfer.cursor");
        }
        if (!clearInputs(gameMode, menu, player, inputIndices)) {
            return failed("error.warmaislandfix.rei_transfer.inventory_full");
        }

        TransferPlan plan = createPlan(menu, inputs, inventoryAccessors, inputIndices, context.isStackedCrafting());
        if (plan == null) {
            return TransferHandler.Result.createFailed(Component.translatable("error.rei.not.enough.materials"));
        }

        try {
            for (Placement placement : plan.placements()) {
                if (!placeItems(gameMode, menu, player, inventoryIndices, placement)) {
                    return failed("error.warmaislandfix.rei_transfer.rejected");
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Client-side REI transfer failed", exception);
            parkCursor(gameMode, menu, player, inventoryIndices);
            return failed("error.warmaislandfix.rei_transfer.failed");
        }

        return TransferHandler.Result.createSuccessful();
    }

    private static TransferPlan createPlan(
            AbstractContainerMenu menu,
            List<InputIngredient<ItemStack>> inputs,
            List<SlotAccessor> inventorySlots,
            List<Integer> inputIndices,
            boolean stacked
    ) {
        ItemRecipeFinder finder = createFinder(inventorySlots);
        List<List<ItemStack>> options = toOptions(inputs);
        int availableCrafts = finder.countRecipeCrafts(options, Integer.MAX_VALUE, null);
        if (availableCrafts < 1) {
            return null;
        }

        int craftCount = stacked ? availableCrafts : 1;
        List<ItemStack> selected = selectIngredients(finder, options, craftCount);
        if (selected == null) {
            return null;
        }

        int slotLimit = craftCount;
        for (int index = 0; index < selected.size() && index < inputIndices.size(); index++) {
            ItemStack stack = selected.get(index);
            if (!stack.isEmpty()) {
                Slot target = menu.getSlot(inputIndices.get(index));
                if (!target.mayPlace(stack)) {
                    return null;
                }
                slotLimit = Math.min(slotLimit, target.getMaxStackSize(stack));
            }
        }
        if (slotLimit < 1) {
            return null;
        }
        if (slotLimit != craftCount) {
            craftCount = slotLimit;
            selected = selectIngredients(finder, options, craftCount);
            if (selected == null) {
                return null;
            }
        }

        List<Placement> placements = new ArrayList<>();
        for (int index = 0; index < selected.size() && index < inputIndices.size(); index++) {
            ItemStack stack = selected.get(index);
            if (!stack.isEmpty()) {
                placements.add(new Placement(inputIndices.get(index), stack.copy(), craftCount));
            }
        }
        return new TransferPlan(placements);
    }

    private static List<ItemStack> selectIngredients(
            ItemRecipeFinder finder,
            List<List<ItemStack>> options,
            int craftCount
    ) {
        List<ItemStack> selected = new ArrayList<>();
        return finder.findRecipe(options, craftCount, selected::add) ? selected : null;
    }

    private static boolean placeItems(
            MultiPlayerGameMode gameMode,
            AbstractContainerMenu menu,
            Player player,
            List<Integer> inventoryIndices,
            Placement placement
    ) {
        int remaining = placement.count();
        Slot target = menu.getSlot(placement.targetSlot());
        while (remaining > 0) {
            int sourceIndex = findMatchingSource(menu, inventoryIndices, placement.stack());
            if (sourceIndex < 0) {
                return false;
            }

            click(gameMode, menu, player, sourceIndex, 0, ContainerInput.PICKUP);
            ItemStack carried = menu.getCarried();
            if (carried.isEmpty() || !ItemStack.isSameItemSameComponents(carried, placement.stack())) {
                return false;
            }

            int before = target.getItem().getCount();
            int toPlace = Math.min(remaining, carried.getCount());
            for (int count = 0; count < toPlace; count++) {
                click(gameMode, menu, player, placement.targetSlot(), 1, ContainerInput.PICKUP);
            }
            int placed = target.getItem().getCount() - before;
            if (!menu.getCarried().isEmpty()) {
                click(gameMode, menu, player, sourceIndex, 0, ContainerInput.PICKUP);
            }
            if (!menu.getCarried().isEmpty() || placed <= 0) {
                return false;
            }
            remaining -= placed;
        }
        return true;
    }

    private static int findMatchingSource(
            AbstractContainerMenu menu,
            List<Integer> inventoryIndices,
            ItemStack wanted
    ) {
        for (int slotIndex : inventoryIndices) {
            ItemStack stack = menu.getSlot(slotIndex).getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, wanted)) {
                return slotIndex;
            }
        }
        return -1;
    }

    private static boolean parkCursor(
            MultiPlayerGameMode gameMode,
            AbstractContainerMenu menu,
            Player player,
            List<Integer> inventoryIndices
    ) {
        while (!menu.getCarried().isEmpty()) {
            ItemStack carried = menu.getCarried();
            int previousCount = carried.getCount();
            int destination = findCursorDestination(menu, inventoryIndices, carried);
            if (destination < 0) {
                return false;
            }
            click(gameMode, menu, player, destination, 0, ContainerInput.PICKUP);
            if (!menu.getCarried().isEmpty() && menu.getCarried().getCount() >= previousCount) {
                return false;
            }
        }
        return true;
    }

    private static int findCursorDestination(
            AbstractContainerMenu menu,
            List<Integer> inventoryIndices,
            ItemStack carried
    ) {
        for (int slotIndex : inventoryIndices) {
            Slot slot = menu.getSlot(slotIndex);
            ItemStack existing = slot.getItem();
            if (slot.mayPlace(carried)
                    && !existing.isEmpty()
                    && ItemStack.isSameItemSameComponents(existing, carried)
                    && existing.getCount() < slot.getMaxStackSize(carried)) {
                return slotIndex;
            }
        }
        for (int slotIndex : inventoryIndices) {
            Slot slot = menu.getSlot(slotIndex);
            if (slot.mayPlace(carried) && slot.getItem().isEmpty()) {
                return slotIndex;
            }
        }
        return -1;
    }

    private static boolean clearInputs(
            MultiPlayerGameMode gameMode,
            AbstractContainerMenu menu,
            Player player,
            List<Integer> inputIndices
    ) {
        for (int slotIndex : inputIndices) {
            if (!menu.getSlot(slotIndex).getItem().isEmpty()) {
                click(gameMode, menu, player, slotIndex, 0, ContainerInput.QUICK_MOVE);
                if (!menu.getSlot(slotIndex).getItem().isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void click(
            MultiPlayerGameMode gameMode,
            AbstractContainerMenu menu,
            Player player,
            int slot,
            int button,
            ContainerInput input
    ) {
        gameMode.handleContainerInput(menu.containerId, slot, button, input, player);
    }

    private static ItemRecipeFinder createFinder(Iterable<SlotAccessor> inventorySlots) {
        ItemRecipeFinder finder = new ItemRecipeFinder();
        for (SlotAccessor accessor : inventorySlots) {
            finder.addNormalItem(accessor.getItemStack());
        }
        return finder;
    }

    private static List<List<ItemStack>> toOptions(List<InputIngredient<ItemStack>> inputs) {
        List<List<ItemStack>> options = new ArrayList<>(inputs.size());
        for (InputIngredient<ItemStack> input : inputs) {
            options.add(input.get());
        }
        return options;
    }

    private static List<SlotAccessor> snapshot(Iterable<SlotAccessor> accessors) {
        List<SlotAccessor> slots = new ArrayList<>();
        for (SlotAccessor accessor : accessors) {
            slots.add(accessor);
        }
        return slots;
    }

    private static List<Integer> resolveIndices(
            AbstractContainerMenu menu,
            Player player,
            List<SlotAccessor> accessors
    ) {
        List<Integer> indices = new ArrayList<>(accessors.size());
        for (SlotAccessor accessor : accessors) {
            indices.add(resolveIndex(menu, player, accessor));
        }
        return indices;
    }

    private static int resolveIndex(AbstractContainerMenu menu, Player player, SlotAccessor accessor) {
        if (accessor instanceof VanillaSlotAccessor vanilla) {
            return menu.slots.indexOf(vanilla.getSlot());
        }
        if (accessor instanceof PlayerInventorySlotAccessor inventory) {
            return menu.findSlot(player.getInventory(), inventory.getIndex()).orElse(-1);
        }
        return -1;
    }

    private static TransferHandler.Result failed(String translationKey) {
        return TransferHandler.Result.createFailed(Component.translatable(translationKey));
    }

    private record Placement(int targetSlot, ItemStack stack, int count) {
    }

    private record TransferPlan(List<Placement> placements) {
    }
}
