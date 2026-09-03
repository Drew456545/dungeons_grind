package dev.drew.ycbotchallenge;

import dev.drew.ycbotchallenge.mixin.KeyBindingAccessor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

/**
 * Minecraft-typed side of the enchanter: reading container slots into
 * {@link EnchantLore.Item}s, classifying the open screen, clicking, and the
 * right-click that opens the menu. Mirrors {@link RebirthScreens}: the string
 * logic lives in {@link EnchantLore} so it stays testable.
 */
public final class EnchantScreens {
    private EnchantScreens() {}

    public enum Kind { NONE, ENCHANTER, UPGRADE, OTHER }

    public record SlotItem(int slot, EnchantLore.Item item) {}

    /** What is on screen right now, from the enchanter's point of view. */
    public static Kind classify(MinecraftClient client, EnchantLore lore) {
        if (client.currentScreen == null) return Kind.NONE;
        if (!(client.currentScreen instanceof HandledScreen<?> hs)) return Kind.OTHER;
        String title = hs.getTitle() != null ? hs.getTitle().getString() : "";
        if (lore.isUpgradeTitle(title)) return Kind.UPGRADE;
        if (isEnchanter(hs.getScreenHandler(), lore)) return Kind.ENCHANTER;
        return Kind.OTHER;
    }

    public static ScreenHandler handler(MinecraftClient client) {
        return client.currentScreen instanceof HandledScreen<?> hs ? hs.getScreenHandler() : null;
    }

    /**
     * Content signature: the enchanter's title is formatting-only, so it is the
     * items that identify it — any lore with the signature line, or 3+ level items.
     */
    public static boolean isEnchanter(ScreenHandler handler, EnchantLore lore) {
        int levelItems = 0;
        for (SlotItem si : items(handler, lore)) {
            if (si.item().signature()) return true;
            if (si.item().isEnchant() && ++levelItems >= 3) return true;
        }
        return false;
    }

    /** Every non-empty container slot (player inventory excluded), in slot order. */
    public static List<SlotItem> items(ScreenHandler handler, EnchantLore lore) {
        List<SlotItem> out = new ArrayList<>();
        if (handler == null || handler.slots == null) return out;
        int chestEnd = Math.max(0, handler.slots.size() - 36);
        for (int i = 0; i < chestEnd; i++) {
            Slot slot = handler.slots.get(i);
            if (slot == null) continue;
            ItemStack stack = slot.getStack();
            if (stack == null || stack.isEmpty()) continue;
            out.add(new SlotItem(slot.id, lore.parse(name(stack), loreLines(stack))));
        }
        return out;
    }

    public static List<SlotItem> enchantItems(ScreenHandler handler, EnchantLore lore) {
        List<SlotItem> out = new ArrayList<>();
        for (SlotItem si : items(handler, lore)) if (si.item().isEnchant()) out.add(si);
        return out;
    }

    /** Tab buttons live in the top row (slots 0–8); nothing else is ever a tab. */
    public static Integer tabSlot(ScreenHandler handler, String tab, EnchantLore lore) {
        for (SlotItem si : items(handler, lore)) {
            if (si.slot() < 9 && tab.equals(lore.tabOfName(si.item().name()))) return si.slot();
        }
        return null;
    }

    /** Tab names found in the top row, in slot order. */
    public static List<String> tabsPresent(ScreenHandler handler, EnchantLore lore) {
        List<String> out = new ArrayList<>();
        for (SlotItem si : items(handler, lore)) {
            if (si.slot() >= 9) break;
            String t = lore.tabOfName(si.item().name());
            if (t != null) out.add(t);
        }
        return out;
    }

    /** Evidence: every non-enchant item as "slot:name" (tab buttons and icons), for pattern tuning from the JSONL. */
    public static List<String> menuItems(ScreenHandler handler, EnchantLore lore) {
        List<String> out = new ArrayList<>();
        for (SlotItem si : items(handler, lore)) {
            if (si.item().isEnchant()) continue;
            String first = si.item().lore().isEmpty() ? "" : " | " + si.item().lore().get(0);
            out.add(si.slot() + ":" + si.item().name() + first);
        }
        return out;
    }

    public static SlotItem maxUpgradeItem(ScreenHandler handler, EnchantLore lore) {
        for (SlotItem si : items(handler, lore)) if (si.item().maxUpgrade()) return si;
        return null;
    }

    public static String name(ItemStack stack) {
        return SidebarParser.strip(stack.getName().getString());
    }

    public static List<String> loreLines(ItemStack stack) {
        List<String> out = new ArrayList<>();
        LoreComponent lc = stack.get(DataComponentTypes.LORE);
        if (lc == null) return out;
        for (Text t : lc.lines()) {
            String s = SidebarParser.strip(t.getString());
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** The held item is the sword (name / "Enchants:" lore). Drew keeps it in hotbar slot 1. */
    public static boolean swordInHand(MinecraftClient client, EnchantLore lore) {
        if (client.player == null) return false;
        ItemStack s = client.player.getMainHandStack();
        if (s == null || s.isEmpty()) return false;
        return lore.isSword(name(s), loreLines(s));
    }

    public static List<String> mainHandLore(MinecraftClient client) {
        if (client.player == null) return List.of();
        ItemStack s = client.player.getMainHandStack();
        return s == null || s.isEmpty() ? List.of() : loreLines(s);
    }

    public static void click(MinecraftClient client, ScreenHandler handler, int slot) {
        if (client.interactionManager == null || client.player == null || handler == null) return;
        client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, client.player);
    }

    /**
     * One real right-click: bump the use key's press counter so vanilla's own
     * handleInputEvents → doItemUse runs the interact exactly as a mouse click
     * would (the same path CombatController.pressAttack uses for the attack key).
     * The key is never held — a held use key fires a second use once the item-use
     * cooldown clears. {@code viaInteract} bypasses the key and calls the
     * interaction manager directly (fallback if a server build ignores the key path).
     */
    public static void pressUse(MinecraftClient client, boolean viaInteract) {
        if (client.player == null) return;
        if (viaInteract) {
            if (client.interactionManager != null) client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            return;
        }
        KeyBinding use = client.options.useKey;
        KeyBindingAccessor acc = (KeyBindingAccessor) use;
        acc.ycbotchallenge$setTimesPressed(acc.ycbotchallenge$getTimesPressed() + 1);
    }

    public static void releaseUse(MinecraftClient client) {
        client.options.useKey.setPressed(false);
    }

    /** Esc: the server-side close packet when a container is open. */
    public static void closeGui(MinecraftClient client) {
        if (client.player != null && client.currentScreen instanceof HandledScreen) {
            client.player.closeHandledScreen();
        } else if (client.currentScreen != null) {
            client.setScreen(null);
        }
    }
}
