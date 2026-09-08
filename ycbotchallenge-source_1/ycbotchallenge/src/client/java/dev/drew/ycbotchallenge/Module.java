package dev.drew.ycbotchallenge;

import net.minecraft.client.MinecraftClient;

/**
 * 0.9.62: what the client asks of every controller that takes turns between fights - the
 * boss event, the upgrades, the enchanter, the rebirth upgrades, the companions, the hero.
 * The client used to name all six twice (a busy pass and a start pass) and again in every
 * reset / logger / enable loop; now they are one ordered list, and a module that opens a
 * menu says so through {@link #isOurGui} instead of being enumerated by hand in the
 * stray-GUI classifier.
 */
public interface Module {
    /** The log prefix ("hero", "enchant", ...). */
    String name();

    /** A visit is under way: the module keeps the tick until it is done. */
    boolean isBusy();

    /** Suspended after repeated aborts (HUD only). */
    default boolean isSuspended() { return false; }

    /** One tick; true when the module took it (combat waits). */
    boolean tick(MinecraftClient client, CombatController combat);

    /** Drop everything in flight (captcha, toggle, teleport). */
    void reset(MinecraftClient client);

    /** The bot was just enabled. */
    default void onEnable(long now, int kills) {}

    /** HUD status line, or null. */
    default String hudLine() { return null; }

    /** The open screen is this module's own menu (never a captcha, never a server menu). */
    default boolean isOurGui(MinecraftClient client) { return false; }

    void setLogger(EventLogger logger);
}
