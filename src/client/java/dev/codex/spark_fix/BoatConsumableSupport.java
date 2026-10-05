package dev.codex.spark_fix;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
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

        if (!isFoodOrDrink(player.getItemInHand(InteractionHand.OFF_HAND))) {
            return false;
        }

        // Keep the normal hands-busy rule for items that can still be used in
        // the main hand.  The NONE animation is the existing compatibility
        // path for blocks and utility items; the two vanilla projectile
        // weapons need an explicit no-projectile check because their use
        // animations are BOW/CROSSBOW even when their use will fail.
        return isMainHandSafeToSkip(player);
    }

    /**
     * Returns whether a failed main-hand interaction may fall through to the
     * off-hand food/drink. The caller must still pass the original result;
     * successful main-hand interactions are never overridden.
     */
    public static boolean shouldPassFailedMainHand(
            LocalPlayer player,
            InteractionHand hand,
            InteractionResult result
    ) {
        return result instanceof InteractionResult.Fail
                && hand == InteractionHand.MAIN_HAND
                && player.getControlledVehicle() instanceof AbstractBoat
                && isMainHandSafeToSkip(player)
                && !isFoodOrDrink(player.getItemInHand(InteractionHand.MAIN_HAND))
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

    private static boolean isKnownProjectileWeaponUnavailable(LocalPlayer player, ItemStack stack) {
        if (stack.getItem() instanceof BowItem) {
            return !player.hasInfiniteMaterials() && player.getProjectile(stack).isEmpty();
        }
        if (stack.getItem() instanceof CrossbowItem) {
            return !CrossbowItem.isCharged(stack) && player.getProjectile(stack).isEmpty();
        }
        return false;
    }

    private static boolean isMainHandSafeToSkip(LocalPlayer player) {
        ItemStack mainHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        return mainHand.isEmpty()
                || mainHand.getUseAnimation() == ItemUseAnimation.NONE
                || isKnownProjectileWeaponUnavailable(player, mainHand);
    }
}
