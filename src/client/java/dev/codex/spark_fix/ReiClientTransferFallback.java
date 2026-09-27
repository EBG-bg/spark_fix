package dev.codex.spark_fix;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ReiClientTransferFallback {
    private static final Logger LOGGER = LoggerFactory.getLogger("spark_fix/rei-transfer");

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
        AbstractContainerMenu menu = context.getMenu();
        if (player == null || gameMode == null || menu == null) {
            return failed("error.spark_fix.rei_transfer.unavailable");
        }
        if (menu != player.containerMenu) {
            return failed("error.spark_fix.rei_transfer.menu_changed");
        }

        List<SlotAccessor> inputAccessors = snapshot(inputSlots);
        List<SlotAccessor> inventoryAccessors = snapshot(inventorySlots);
        List<Integer> inputIndices = resolveIndices(menu, player, inputAccessors);
        List<Integer> inventoryIndices = resolveIndices(menu, player, inventoryAccessors);
        if (!validIndices(menu, inputIndices)
                || !validIndices(menu, inventoryIndices)) {
            return failed("error.spark_fix.rei_transfer.unsupported");
        }
        List<Integer> targetIndices = targetSlots(inputs, inputIndices);
        if (targetIndices == null) {
            return failed("error.spark_fix.rei_transfer.unsupported");
        }
        if (hasOverlap(inputIndices, inventoryIndices)) {
            return failed("error.spark_fix.rei_transfer.overlap");
        }

        TransferSession session = new TransferSession(minecraft, gameMode, player, menu);
        minecraft.setScreenAndShow(context.getContainerScreen());
        if (!parkCursor(session, inventoryIndices)) {
            return failure(session, "error.spark_fix.rei_transfer.cursor");
        }
        if (!clearInputs(session, inputIndices)) {
            return failure(session, "error.spark_fix.rei_transfer.inventory_full");
        }

        TransferPlan plan = createPlan(menu, inputs, inventoryAccessors, targetIndices, context.isStackedCrafting());
        if (plan == null) {
            return TransferHandler.Result.createFailed(Component.translatable("error.rei.not.enough.materials"));
        }
        if (plan.placements().isEmpty()) {
            return TransferHandler.Result.createSuccessful();
        }

        try {
            for (Placement placement : plan.placements()) {
                if (!placeItems(session, inventoryIndices, placement)) {
                    cleanupCursor(session, inventoryIndices);
                    return failure(session, "error.spark_fix.rei_transfer.rejected");
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Client-side REI transfer failed", exception);
            cleanupCursor(session, inventoryIndices);
            return failure(session, "error.spark_fix.rei_transfer.failed");
        }

        if (!session.isCurrent() || !menu.getCarried().isEmpty()) {
            cleanupCursor(session, inventoryIndices);
            return failure(session, "error.spark_fix.rei_transfer.failed");
        }
        return TransferHandler.Result.createSuccessful();
    }

    private static TransferPlan createPlan(
            AbstractContainerMenu menu,
            List<InputIngredient<ItemStack>> inputs,
            List<SlotAccessor> inventorySlots,
            List<Integer> targetIndices,
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
        for (int index = 0; index < selected.size() && index < targetIndices.size(); index++) {
            ItemStack stack = selected.get(index);
            if (!stack.isEmpty()) {
                Slot target = menu.getSlot(targetIndices.get(index));
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
        for (int index = 0; index < selected.size() && index < targetIndices.size(); index++) {
            ItemStack stack = selected.get(index);
            if (!stack.isEmpty()) {
                placements.add(new Placement(targetIndices.get(index), stack.copy(), craftCount));
            }
        }
        return new TransferPlan(placements);
    }

    private static List<Integer> targetSlots(List<InputIngredient<ItemStack>> inputs, List<Integer> inputIndices) {
        List<Integer> targets = new ArrayList<>(inputs.size());
        Set<Integer> used = new HashSet<>();
        for (InputIngredient<ItemStack> input : inputs) {
            int index = input.getIndex();
            if (index < 0 || index >= inputIndices.size() || !used.add(index)) return null;
            targets.add(inputIndices.get(index));
        }
        return targets;
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
            TransferSession session,
            List<Integer> inventoryIndices,
            Placement placement
    ) {
        AbstractContainerMenu menu = session.menu;
        int remaining = placement.count();
        Slot target = menu.getSlot(placement.targetSlot());
        while (remaining > 0) {
            if (!session.isCurrent() || !menu.getCarried().isEmpty()) {
                return false;
            }

            int sourceIndex = findMatchingSource(menu, inventoryIndices, placement.stack(), remaining);
            if (sourceIndex < 0) {
                return false;
            }

            Slot source = menu.getSlot(sourceIndex);
            ItemStack sourceBefore = source.getItem().copy();
            int availableSpace = target.getMaxStackSize(sourceBefore)
                    - (target.getItem().isEmpty() ? 0 : target.getItem().getCount());
            int toPlace = Math.min(remaining, Math.min(sourceBefore.getCount(), availableSpace));
            if (toPlace < 1 || !target.mayPlace(sourceBefore)
                    || (!target.getItem().isEmpty()
                        && !ItemStack.isSameItemSameComponents(target.getItem(), sourceBefore))) {
                return false;
            }
            int pickupButton = (sourceBefore.getCount() + 1) / 2 >= toPlace ? 1 : 0;
            if (!session.click(sourceIndex, pickupButton, ContainerInput.PICKUP)) return false;
            ItemStack carried = menu.getCarried();
            if (carried.isEmpty() || !ItemStack.isSameItemSameComponents(carried, placement.stack())) {
                return false;
            }
            if (carried.getCount() != (pickupButton == 1
                    ? (sourceBefore.getCount() + 1) / 2 : sourceBefore.getCount())) {
                return false;
            }

            ItemStack targetBefore = target.getItem().copy();
            int targetCount = targetBefore.isEmpty() ? 0 : targetBefore.getCount();
            int excess = carried.getCount() - toPlace;
            if (excess <= toPlace) {
                for (int count = 0; count < excess; count++) {
                    if (!session.click(sourceIndex, 1, ContainerInput.PICKUP)) return false;
                }
                if (menu.getCarried().getCount() != toPlace
                        || !session.click(placement.targetSlot(), 0, ContainerInput.PICKUP)) return false;
            } else {
                for (int count = 0; count < toPlace; count++) {
                    if (!session.click(placement.targetSlot(), 1, ContainerInput.PICKUP)) return false;
                }
                if (!menu.getCarried().isEmpty()
                        && !session.click(sourceIndex, 0, ContainerInput.PICKUP)) return false;
            }
            ItemStack targetAfter = target.getItem();
            ItemStack sourceAfter = source.getItem();
            if (!menu.getCarried().isEmpty()
                    || targetAfter.getCount() != targetCount + toPlace
                    || !ItemStack.isSameItemSameComponents(targetAfter, placement.stack())
                    || sourceAfter.getCount() != sourceBefore.getCount() - toPlace
                    || (!sourceAfter.isEmpty()
                        && !ItemStack.isSameItemSameComponents(sourceAfter, sourceBefore))) return false;
            remaining -= toPlace;
        }
        return true;
    }

    private static int findMatchingSource(
            AbstractContainerMenu menu,
            List<Integer> inventoryIndices,
            ItemStack wanted,
            int remaining
    ) {
        int bestIndex = -1;
        int bestCost = Integer.MAX_VALUE;
        int largestShortStack = 0;
        for (int slotIndex : inventoryIndices) {
            ItemStack stack = menu.getSlot(slotIndex).getItem();
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, wanted)) {
                int count = stack.getCount();
                if (count >= remaining) {
                    int picked = (count + 1) / 2 >= remaining ? (count + 1) / 2 : count;
                    int excess = picked - remaining;
                    int cost = 1 + (excess <= remaining ? excess + 1 : remaining + 1);
                    if (cost < bestCost) {
                        bestIndex = slotIndex;
                        bestCost = cost;
                    }
                } else if (bestCost == Integer.MAX_VALUE && count > largestShortStack) {
                    bestIndex = slotIndex;
                    largestShortStack = count;
                }
            }
        }
        return bestIndex;
    }

    private static boolean parkCursor(
            TransferSession session,
            List<Integer> inventoryIndices
    ) {
        AbstractContainerMenu menu = session.menu;
        while (!menu.getCarried().isEmpty()) {
            if (!session.isCurrent()) {
                return false;
            }
            ItemStack carried = menu.getCarried().copy();
            int destination = findCursorDestination(menu, inventoryIndices, carried);
            if (destination < 0 || !session.click(destination, 0, ContainerInput.PICKUP)) {
                return false;
            }
            ItemStack carriedAfter = menu.getCarried();
            if (!carriedAfter.isEmpty()
                    && (!ItemStack.isSameItemSameComponents(carriedAfter, carried)
                        || carriedAfter.getCount() >= carried.getCount())) {
                return false;
            }
        }
        return true;
    }

    private static void cleanupCursor(TransferSession session, List<Integer> inventoryIndices) {
        if (session.isCurrent() && !session.menu.getCarried().isEmpty()) {
            parkCursor(session, inventoryIndices);
        }
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
            TransferSession session,
            List<Integer> inputIndices
    ) {
        AbstractContainerMenu menu = session.menu;
        for (int slotIndex : inputIndices) {
            Slot input = menu.getSlot(slotIndex);
            if (!input.getItem().isEmpty()) {
                ItemStack carriedBefore = menu.getCarried().copy();
                if (!session.click(slotIndex, 0, ContainerInput.QUICK_MOVE)) {
                    return false;
                }
                ItemStack carriedAfter = menu.getCarried();
                if (!input.getItem().isEmpty()
                        || !sameStackState(carriedBefore, carriedAfter)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean sameStackState(ItemStack first, ItemStack second) {
        if (first.isEmpty() || second.isEmpty()) {
            return first.isEmpty() && second.isEmpty();
        }
        return first.getCount() == second.getCount()
                && ItemStack.isSameItemSameComponents(first, second);
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

    private static boolean validIndices(AbstractContainerMenu menu, List<Integer> indices) {
        for (int index : indices) {
            if (index < 0 || index >= menu.slots.size()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasOverlap(List<Integer> inputs, List<Integer> inventory) {
        Set<Integer> inputSet = new HashSet<>(inputs);
        for (int index : inventory) {
            if (inputSet.contains(index)) {
                return true;
            }
        }
        return false;
    }

    private static TransferHandler.Result failure(TransferSession session, String translationKey) {
        if (session.limitReached) {
            return failedLimit(session.maxClicks);
        }
        if (!session.isCurrent()) {
            return failed("error.spark_fix.rei_transfer.menu_changed");
        }
        return failed(translationKey);
    }

    private static TransferHandler.Result failedLimit(int maxClicks) {
        return TransferHandler.Result.createFailed(
            Component.translatable("error.spark_fix.rei_transfer.limit", maxClicks)
        );
    }

    private static TransferHandler.Result failed(String translationKey) {
        return TransferHandler.Result.createFailed(Component.translatable(translationKey));
    }

    private record Placement(int targetSlot, ItemStack stack, int count) {
    }

    private record TransferPlan(List<Placement> placements) {
    }

    private static final class TransferSession {
        private final Minecraft minecraft;
        private final MultiPlayerGameMode gameMode;
        private final Player player;
        private final AbstractContainerMenu menu;
        private final int containerId;
        private final int maxClicks;
        private int clicks;
        private boolean limitReached;

        private TransferSession(
                Minecraft minecraft,
                MultiPlayerGameMode gameMode,
                Player player,
                AbstractContainerMenu menu
        ) {
            this.minecraft = minecraft;
            this.gameMode = gameMode;
            this.player = player;
            this.menu = menu;
            this.containerId = menu.containerId;
            this.maxClicks = SparkFixConfig.maxReiClicks();
        }

        private boolean isCurrent() {
            return this.minecraft.player == this.player
                    && this.minecraft.gameMode == this.gameMode
                    && this.player.containerMenu == this.menu
                    && this.menu.containerId == this.containerId;
        }

        private boolean click(int slot, int button, ContainerInput input) {
            if (!isCurrent() || slot < 0 || slot >= this.menu.slots.size()) {
                return false;
            }
            if (this.clicks >= this.maxClicks) {
                this.limitReached = true;
                return false;
            }

            this.clicks++;
            this.gameMode.handleContainerInput(this.containerId, slot, button, input, this.player);
            return true;
        }
    }
}
