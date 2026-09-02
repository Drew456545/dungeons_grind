package dev.drew.ycbotchallenge;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;

/**
 * Rebirth GUI helpers. Title match is string-only so tests can cover it;
 * diamond slot lookup needs a live {@link ScreenHandler}.
 */
public final class RebirthScreens {
    private RebirthScreens() {}

    public static boolean isRebirthGui(String title) {
        return ChatClassifier.isRebirthGui(title);
    }

    /**
     * First diamond in the container (not the player inventory). Null if the
     * menu has no matching stack. The live screenshot puts it at chest slot 13;
     * we search by item so a layout shift still works.
     */
    public static Integer diamondSlot(ScreenHandler handler) {
        if (handler == null || handler.slots == null) return null;
        int chestEnd = Math.max(0, handler.slots.size() - 36);
        for (int i = 0; i < chestEnd; i++) {
            Slot slot = handler.slots.get(i);
            if (slot == null) continue;
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            if (stack.isOf(Items.DIAMOND)) return slot.id;
        }
        return null;
    }
}
