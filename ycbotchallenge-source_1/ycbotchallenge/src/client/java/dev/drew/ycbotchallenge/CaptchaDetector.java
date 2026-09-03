package dev.drew.ycbotchallenge;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.ItemStack;

/**
 * Watches the hands and hotbar for a filled map the server just handed us:
 * EnchantedMC's captcha is a map put in the player's hand (hotbar slot 9 in the
 * 2026-09-02 capture) whose picture is the code to type in chat. No chat line
 * accompanies it, so the map itself is the signal.
 *
 * A map appearing in a slot that had none at enable time (or since) fires
 * after {@code captchaSignalConfirmMs}; that map id is then muted until it is
 * gone, so one captcha triggers exactly once. Slots: 0-8 hotbar, 9 main hand,
 * 10 off hand.
 */
final class CaptchaDetector {
    static final int SLOT_MAIN = 9;
    static final int SLOT_OFF = 10;
    static final int SLOTS = 11;

    /** A confirmed captcha map. */
    record Hit(String source, String detail, int slot, int mapId) {}

    private final YCBotChallengeConfig cfg;
    private final StatsTracker stats;
    private EventLogger logger;

    private final boolean[] known = new boolean[SLOTS];
    private final int[] ids = new int[SLOTS];
    private int pendingSlot = -1;
    private long pendingSince;
    private Integer mutedMapId = null;

    CaptchaDetector(YCBotChallengeConfig cfg, StatsTracker stats) {
        this.cfg = cfg;
        this.stats = stats;
    }

    void setLogger(EventLogger logger) { this.logger = logger; }

    /** Snapshot what is already in the hotbar/hands so pre-existing maps never fire. */
    void onEnable(MinecraftClient client) {
        if (client.player == null) return;
        scan(client, ids);
        List<Integer> withMaps = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            known[i] = ids[i] >= 0;
            if (known[i]) withMaps.add(i);
        }
        pendingSlot = -1;
        mutedMapId = null;
        if (logger != null) logger.log("hotbar_snapshot", "mapSlots", withMaps, "anySlot", cfg.captchaMapAnySlot);
    }

    /** Call each tick while the bot is enabled and no solve is running. Non-null = captcha. */
    Hit tick(MinecraftClient client, long now) {
        if (client.player == null) return null;
        int[] cur = new int[SLOTS];
        scan(client, cur);
        // slots that lost their map are free to fire again; a muted map that vanished is forgotten
        boolean mutedPresent = false;
        for (int i = 0; i < SLOTS; i++) {
            if (cur[i] < 0) known[i] = false;
            if (mutedMapId != null && cur[i] == mutedMapId) mutedPresent = true;
        }
        if (mutedMapId != null && !mutedPresent) mutedMapId = null;

        int slot = newMapSlot(known, cur, mutedMapId, cfg.captchaMapAnySlot);
        if (slot < 0) { pendingSlot = -1; return null; }
        if (slot != pendingSlot) { pendingSlot = slot; pendingSince = now; }
        long confirm = cfg.captchaSignalConfirmMs;
        // a server line like "type the code" moments ago makes the map unambiguous
        if (stats != null && stats.captchaHintAt != 0 && now - stats.captchaHintAt <= 10_000) confirm = 0;
        if (now - pendingSince < confirm) return null;

        known[slot] = true;
        mutedMapId = cur[slot];
        pendingSlot = -1;
        String source = slot >= SLOT_MAIN || isSelected(client, slot) ? "held-map" : "hotbar-map";
        String where = slot == SLOT_MAIN ? "main" : slot == SLOT_OFF ? "off" : "hotbar" + (slot + 1);
        return new Hit(source, "slot:" + where + " mapId:" + cur[slot], slot, cur[slot]);
    }

    /**
     * Pure: the first slot holding a map that was not known before (skipping the
     * muted map id). Hand slots always count; hotbar slots only with {@code anySlot}.
     */
    static int newMapSlot(boolean[] known, int[] cur, Integer muted, boolean anySlot) {
        for (int i = 0; i < cur.length; i++) {
            if (cur[i] < 0 || known[i]) continue;
            if (muted != null && cur[i] == muted) continue;
            if (i < SLOT_MAIN && !anySlot) continue;
            return i;
        }
        return -1;
    }

    private static boolean isSelected(MinecraftClient client, int hotbarSlot) {
        ItemStack main = client.player.getMainHandStack();
        return main != null && main == client.player.getInventory().getStack(hotbarSlot);
    }

    /** Map id per slot (-1 = no filled map). */
    private static void scan(MinecraftClient client, int[] out) {
        for (int i = 0; i < 9; i++) out[i] = mapId(client.player.getInventory().getStack(i));
        out[SLOT_MAIN] = mapId(client.player.getMainHandStack());
        out[SLOT_OFF] = mapId(client.player.getOffHandStack());
    }

    static int mapId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return -1;
        MapIdComponent id = stack.get(DataComponentTypes.MAP_ID);
        return id == null ? -1 : id.id();
    }
}
