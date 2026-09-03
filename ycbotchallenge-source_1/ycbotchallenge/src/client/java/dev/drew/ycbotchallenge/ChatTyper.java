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
 */
final class ChatTyper {
    enum State { IDLE, OPEN, TYPE, SEND, DONE, FAILED }

    private final YCBotChallengeConfig cfg;
    private State state = State.IDLE;
    private long until;
    private String text = "";
    private String typed = "";
    private int typedChars;
    private int typoAt = -1;
    private int typos;
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

    /** Open the chat screen and start typing {@code line}. */
    void begin(MinecraftClient client, String line, long now) {
        text = line == null ? "" : line;
        typed = "";
        typedChars = 0;
        typoAt = -1;
        typos = 0;
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
        typoAt = -1;
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
                if (typoAt >= 0) {
                    // noticed the slip: backspace, then a slightly longer beat before going on
                    typed = typed.substring(0, typed.length() - 1);
                    if (field != null) field.setText(typed);
                    typoAt = -1;
                    until = now + HumanTiming.logNormalMs(cfg.typeKeyMinMs, cfg.typeKeyMaxMs + 120);
                    return state;
                }
                if (typedChars < text.length()) {
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    char c = text.charAt(typedChars);
                    boolean typo = cfg.ninja && typedChars > 1 && Character.isLetterOrDigit(c)
                        && rng.nextDouble() < cfg.typoChancePerChar;
                    if (typo) {
                        typed += (char) ('a' + rng.nextInt(26));
                        typoAt = typedChars;
                        typos++;
                    } else {
                        typed += c;
                    }
                    typedChars++;
                    if (field != null) field.setText(typed);
                    else cs.insertText(String.valueOf(typed.charAt(typed.length() - 1)), false);
                    until = now + HumanTiming.logNormalMs(cfg.typeKeyMinMs, cfg.typeKeyMaxMs);
                } else {
                    state = State.SEND;
                    until = now + HumanTiming.logNormalMs(80, 220);
                }
            }
            case SEND -> {
                if (now < until) return state;
                if (!(client.currentScreen instanceof ChatScreen cs)) return fail("chat-closed");
                cs.sendMessage(text, true);
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
        typoAt = -1;
        return state;
    }
}
