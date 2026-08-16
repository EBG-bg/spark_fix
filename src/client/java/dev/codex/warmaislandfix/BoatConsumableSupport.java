package dev.codex.warmaislandfix;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;

public final class BoatConsumableSupport {
    private BoatConsumableSupport() {
    }

    public static boolean canStartUsingConsumable(LocalPlayer player) {
        if (!(player.getControlledVehicle() instanceof AbstractBoat)) {
            return false;
        }

        ItemStack mainHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (isFoodOrDrink(mainHand)) {
            return true;
        }

        return mainHand.getUseAnimation() == ItemUseAnimation.NONE
                && isFoodOrDrink(player.getItemInHand(InteractionHand.OFF_HAND));
    }

    public static boolean shouldShowUseAnimation(LocalPlayer player) {
        return player.getControlledVehicle() instanceof AbstractBoat
                && player.isUsingItem()
                && isFoodOrDrink(player.getUseItem());
    }

    private static boolean isFoodOrDrink(ItemStack stack) {
        ItemUseAnimation animation = stack.getUseAnimation();
        return animation == ItemUseAnimation.EAT || animation == ItemUseAnimation.DRINK;
    }
}
