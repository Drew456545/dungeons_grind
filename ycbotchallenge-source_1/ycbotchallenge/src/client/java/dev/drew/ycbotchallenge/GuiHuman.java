package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/**
 * 0.9.33: the one way every container menu is read, clicked and closed. Before this the
 * four menu flows each carried their own copy of the slot walker and their own timing
 * (the companion egg opens and the enchanter tab clicked in the same tick as the decision;
 * three different close policies). A server only sees click and close packets and their
 * spacing — the cursor never moves inside a menu on the client — so the timing is the whole
 * contract: a notice beat before every click ({@code guiClickMinMs..MaxMs}), a beat before
 * every close ({@code guiCloseMinMs..MaxMs}), a pause between two menus
 * ({@code guiBetweenMinMs..MaxMs}), a shorter read of a sub-menu ({@code guiReadMinMs..MaxMs}),
 * the flow's own first look ({@link #lookDelayMs}). Every click and close is logged
 * ({@code gui_click}, {@code gui_close}) with the flow and the slot.
 */
public final class GuiHuman {
    private GuiHuman() {}

    /** One non-empty container slot: id, stripped name, stripped lore lines. */
    public record Item(int slot, String name, List<String> lore) {}

    public static ScreenHandler handler(MinecraftClient client) {
        return client.currentScreen instanceof HandledScreen<?> hs ? hs.getScreenHandler() : null;
    }

    public static String title(MinecraftClient client) {
        if (client.currentScreen == null || client.currentScreen.getTitle() == null) return null;
        return client.currentScreen.getTitle().getString();
    }

    /** Non-empty container slots (the player's 36 inventory slots excluded), in slot order. */
    public static List<Item> items(MinecraftClient client) {
        List<Item> out = new ArrayList<>();
        ScreenHandler h = handler(client);
        if (h == null || h.slots == null) return out;
        int chestEnd = Math.max(0, h.slots.size() - 36);
        for (int i = 0; i < chestEnd; i++) {
            Slot slot = h.slots.get(i);
            if (slot == null) continue;
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            out.add(new Item(slot.id, EnchantScreens.name(stack), EnchantScreens.loreLines(stack)));
        }
        return out;
    }

    /** "slot:name | lore | lore" per item, for the evidence log. */
    public static List<String> describe(List<Item> items) {
        List<String> out = new ArrayList<>();
        for (Item e : items) out.add(e.slot() + ":" + e.name() + (e.lore().isEmpty() ? "" : " | " + String.join(" | ", e.lore())));
        return out;
    }

    /** The beat between seeing a button and clicking it. */
    public static long clickDelayMs(YCBotChallengeConfig cfg) {
        return HumanTiming.logNormalMs(cfg.guiClickMinMs, Math.max(cfg.guiClickMinMs + 1, cfg.guiClickMaxMs));
    }

    /** The beat between being done with a menu and closing it. */
    public static long closeDelayMs(YCBotChallengeConfig cfg) {
        return HumanTiming.logNormalMs(cfg.guiCloseMinMs, Math.max(cfg.guiCloseMinMs + 1, cfg.guiCloseMaxMs));
    }

    /** The pause between closing one menu and opening the next (typing the next command). */
    public static long betweenDelayMs(YCBotChallengeConfig cfg) {
        return HumanTiming.logNormalMs(cfg.guiBetweenMinMs, Math.max(cfg.guiBetweenMinMs + 1, cfg.guiBetweenMaxMs));
    }

    /** A short read of a sub-menu or of a menu just re-read after a purchase. */
    public static long readDelayMs(YCBotChallengeConfig cfg) {
        return HumanTiming.logNormalMs(cfg.guiReadMinMs, Math.max(cfg.guiReadMinMs + 1, cfg.guiReadMaxMs));
    }

    /** The first look at a freshly opened menu, per flow: "enchant", "companion", else the rebirth knobs. */
    public static long lookDelayMs(YCBotChallengeConfig cfg, String flow) {
        int lo;
        int hi;
        if ("enchant".equals(flow)) { lo = cfg.enchantLookMinMs; hi = cfg.enchantLookMaxMs; }
        else if ("companion".equals(flow)) { lo = cfg.companionLookMinMs; hi = cfg.companionLookMaxMs; }
        else { lo = cfg.rebirthLookMinMs; hi = cfg.rebirthLookMaxMs; }
        return HumanTiming.logNormalMs(lo, Math.max(lo + 1, hi));
    }

    /** One left click on a container slot (the packet a real click sends), logged. */
    public static boolean click(MinecraftClient client, int slot, String flow, String what, EventLogger logger) {
        ScreenHandler h = handler(client);
        if (h == null || slot < 0 || client.interactionManager == null || client.player == null) return false;
        client.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.PICKUP, client.player);
        if (logger != null) logger.log("gui_click", "flow", flow, "slot", slot, "what", what);
        return true;
    }

    /** Esc on the open container (the server-side close packet), logged. */
    public static void close(MinecraftClient client, String flow, EventLogger logger) {
        if (client.currentScreen == null) return;
        if (logger != null) logger.log("gui_close", "flow", flow, "title", title(client));
        EnchantScreens.closeGui(client);
    }
}
