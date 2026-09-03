package dev.drew.ycbotchallenge;

import dev.drew.ycbotchallenge.mixin.ChatScreenAccessor;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;

/**
 * Types a chat line the way a person does: open the chat screen, one key per
 * log-normal delay ({@code typeKeyMinMs/MaxMs}), the occasional typo followed
 * by a backspace ({@code typoChancePerChar}), a short beat, then Enter.
 * Tick-driven so the caller stays on the client thread; every chat send the
 * bot makes ({@code /swordmax}, {@code /zone max}, {@code /rebirth}, captcha
 * answers) goes through here so no path ever "teleports" a full line into chat.
 *
 * 0.9.26: the keystroke rule is a pure function ({@link #step}) because the old
 * one lost a character on every typo — it appended the wrong key AND advanced the
 * index, so the backspace removed the wrong key and the intended one was never
 * typed. The field read "/zne max" (19:43 log) while the send passed the original
 * string, so the server got "/zone max": what was on screen was not what was sent.
 * Now the field ends exactly as the command, and that is what is sent.
 */
final class ChatTyper {
    enum State { IDLE, OPEN, TYPE, SEND, DONE, FAILED }

    /** Pure keystroke state: what is in the field, the next intended index, a pending typo (-1 = none). */
    record Keys(String typed, int next, int typoAt) {
        static Keys start() { return new Keys("", 0, -1); }
    }

    /**
     * One keystroke. A pending typo is corrected first (backspace; the index does not
     * move, so the intended character is typed on the following stroke). Otherwise
     * either the wrong key goes in and stays pending, or the intended character is
     * typed and the index advances. Past the end nothing changes.
     */
    static Keys step(Keys k, String text, boolean makeTypo, char wrong) {
        if (k.typoAt() >= 0) {
            String t = k.typed().isEmpty() ? "" : k.typed().substring(0, k.typed().length() - 1);
            return new Keys(t, k.next(), -1);
        }
        if (text == null || k.next() >= text.length()) return k;
        if (makeTypo) return new Keys(k.typed() + wrong, k.next(), k.next());
        return new Keys(k.typed() + text.charAt(k.next()), k.next() + 1, -1);
    }

    /** True once every character is in and no typo is pending. */
    static boolean done(Keys k, String text) {
        return k.typoAt() < 0 && (text == null || k.next() >= text.length());
    }

    private final YCBotChallengeConfig cfg;
    private State state = State.IDLE;
    private long until;
    private String text = "";
    private Keys keys = Keys.start();
    private int typos;
    private boolean mismatch;
    private String failReason;

    ChatTyper(YCBotChallengeConfig cfg) {
        this.cfg = cfg;
    }

    boolean running() {
        return state == State.OPEN || state == State.TYPE || state == State.SEND;
    }

    String failReason() { return failReason; }

    /** Typos made (and corrected) while typing the last line — for the event log. */
    int typos() { return typos; }

    /** What the field held when the line was sent. */
    String typed() { return keys.typed(); }

    /** True if the field did not end as the intended line (the intended line was sent; logged as evidence). */
    boolean typedMismatch() { return mismatch; }

    /** Open the chat screen and start typing {@code line}. */
    void begin(MinecraftClient client, String line, long now) {
        text = line == null ? "" : line;
        keys = Keys.start();
        typos = 0;
        mismatch = false;
        failReason = null;
        client.setScreen(new ChatScreen("", false));
        state = State.OPEN;
        until = now + 80;
    }

    /** Close our chat screen (if it is ours) and go idle. */
    void cancel(MinecraftClient client) {
        if (running() && client != null && client.currentScreen instanceof ChatScreen) {
            client.setScreen(null);
        }
        state = State.IDLE;
    }

    /** Advance one tick; returns the state afterwards (DONE once the line was sent). */
    State tick(MinecraftClient client, long now) {
        switch (state) {
            case OPEN -> {
                if (now < until) return state;
                if (!(client.currentScreen instanceof ChatScreen)) return fail("chat-closed");
                state = State.TYPE;
                until = now + HumanTiming.logNormalMs(cfg.typeKeyMinMs, cfg.typeKeyMaxMs);
            }
            case TYPE -> {
                if (!(client.currentScreen instanceof ChatScreen cs)) return fail("chat-closed");
                if (now < until) return state;
                TextFieldWidget field = ((ChatScreenAccessor) cs).ycBotChallenge$getChatField();
                if (keys.typoAt() >= 0) {
                    // noticed the slip: backspace, then a slightly longer beat before going on
                    keys = step(keys, text, false, ' ');
                    if (field != null) field.setText(keys.typed());
                    until = now + HumanTiming.logNormalMs(cfg.typeKeyMinMs, cfg.typeKeyMaxMs + 120);
                    return state;
                }
                if (!done(keys, text)) {
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    char c = text.charAt(keys.next());
                    boolean typo = cfg.ninja && keys.next() > 1 && Character.isLetterOrDigit(c)
                        && rng.nextDouble() < cfg.typoChancePerChar;
                    char wrong = (char) ('a' + rng.nextInt(26));
                    keys = step(keys, text, typo, wrong);
                    if (typo) typos++;
                    if (field != null) field.setText(keys.typed());
                    else cs.insertText(String.valueOf(keys.typed().charAt(keys.typed().length() - 1)), false);
                    until = now + HumanTiming.logNormalMs(cfg.typeKeyMinMs, cfg.typeKeyMaxMs);
                } else {
                    state = State.SEND;
                    until = now + HumanTiming.logNormalMs(80, 220);
                }
            }
            case SEND -> {
                if (now < until) return state;
                if (!(client.currentScreen instanceof ChatScreen cs)) return fail("chat-closed");
                // Send what was typed. It equals the intended line by construction; if it
                // ever does not, the intended line goes (a garbled command helps nobody)
                // and the caller logs typedMismatch as evidence.
                mismatch = !keys.typed().equals(text);
                cs.sendMessage(mismatch ? text : keys.typed(), true);
                client.setScreen(null);
                state = State.DONE;
            }
            default -> { }
        }
        return state;
    }

    private State fail(String why) {
        failReason = why;
        state = State.FAILED;
        return state;
    }
}
