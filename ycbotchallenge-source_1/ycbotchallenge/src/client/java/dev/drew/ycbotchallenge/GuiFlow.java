package dev.drew.ycbotchallenge;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;

/**
 * 0.9.62: the menu-visit driver. Six controllers each carried the same skeleton by copy - a
 * Phase enum, a phase deadline, a visit clock, an abort that closes the menu and counts
 * toward a suspension - and the same steps: type a command, wait for the menu, look at it,
 * click a slot, settle, close. A visit is now a chain of {@link Step}s; the driver runs one
 * step per tick, keeps the step and visit clocks, turns a step's {@code abort} into the
 * {@code <prefix>_abort} / {@code <prefix>_suspended} bookkeeping ({@link Aborts}) and the
 * owner's abort hook, and ends the visit on {@link #DONE}.
 *
 * <p>Step names are what the old Phase names were, lowercased - the {@code phase} field of
 * the abort rows reads the same as before. The primitives never touch Minecraft when the
 * client is null, so a scripted flow runs in the checks with a fake clock.
 */
public final class GuiFlow {
    /** One step of a visit: return the next step, {@link #DONE}, or null to run again next tick. */
    public interface Step {
        String name();
        Step tick(Ctx c);
    }

    /** The visit is over. */
    public static final Step DONE = new Step() {
        @Override public String name() { return "done"; }
        @Override public Step tick(Ctx c) { return this; }
    };

    /** What a step sees. The step clock ({@link #arm}, {@link #due}) resets whenever the step changes. */
    public static final class Ctx {
        public MinecraftClient client;
        public CombatController combat;
        public EventLogger logger;
        public long now;
        public long stepSince;
        public long visitSince;
        private long until;
        private String abortReason;

        /** End the visit as an abort; the driver logs it and runs the owner's hook. */
        public void abort(String why) { abortReason = why; }
        public boolean armed() { return until != 0; }
        public void arm(long delayMs) { until = now + Math.max(0, delayMs); }
        public boolean due() { return until != 0 && now >= until; }
        public long sinceStep() { return now - stepSince; }
        public long sinceVisit() { return now - visitSince; }
    }

    /**
     * The abort bookkeeping every controller had: consecutive aborts, the suspension at
     * {@code maxAborts}, an optional self-un-suspend after {@code suspendMs} (0 = until the
     * toggle). Logs {@code <prefix>_abort}, {@code <prefix>_suspended}, {@code <prefix>_resumed}.
     */
    public static final class Aborts {
        private final String prefix;
        private final IntSupplier maxAborts;
        private final LongSupplier suspendMs;
        private int count;
        private boolean suspended;
        private long suspendedAt;
        private String lastReason;

        public Aborts(String prefix, IntSupplier maxAborts, LongSupplier suspendMs) {
            this.prefix = prefix;
            this.maxAborts = maxAborts;
            this.suspendMs = suspendMs;
        }

        public boolean suspended() { return suspended; }
        public int count() { return count; }
        public String lastReason() { return lastReason; }
        public long suspendedAt() { return suspendedAt; }

        /** The bot was toggled on: a clean slate. */
        public void onEnable() { suspended = false; count = 0; }

        /** A visit ended well. */
        public void finish() { count = 0; }

        /** One abort ({@code extra} = more key/value pairs for the row); true when it tipped the module into suspension. */
        public boolean abort(long now, String why, String phase, EventLogger logger, Object... extra) {
            if (logger != null) {
                Object[] kv = new Object[4 + (extra == null ? 0 : extra.length)];
                kv[0] = "reason"; kv[1] = why; kv[2] = "phase"; kv[3] = phase;
                if (extra != null) System.arraycopy(extra, 0, kv, 4, extra.length);
                logger.log(prefix + "_abort", kv);
            }
            lastReason = why;
            if (++count >= Math.max(1, maxAborts.getAsInt())) {
                suspended = true;
                suspendedAt = now;
                long ms = suspendMs.getAsLong();
                if (logger != null) logger.log(prefix + "_suspended", "aborts", count, "lastReason", why, "resumeInMs", ms > 0 ? ms : null);
                return true;
            }
            return false;
        }

        /** An abort the owner logged itself (the boss event's window rows): the count and the suspension only. */
        public boolean count(long now, String why, EventLogger logger) {
            lastReason = why;
            if (++count >= Math.max(1, maxAborts.getAsInt())) {
                suspended = true;
                suspendedAt = now;
                long ms = suspendMs.getAsLong();
                if (logger != null) logger.log(prefix + "_suspended", "aborts", count, "lastReason", why, "resumeInMs", ms > 0 ? ms : null);
                return true;
            }
            return false;
        }

        /** Lifts a suspension once {@code suspendMs} has passed (never when it is 0); true when it did. */
        public boolean maybeUnsuspend(long now, EventLogger logger) {
            long ms = suspendMs.getAsLong();
            if (!suspended || ms <= 0 || now - suspendedAt < ms) return false;
            suspended = false;
            count = 0;
            if (logger != null) logger.log(prefix + "_resumed", "afterMs", now - suspendedAt);
            return true;
        }
    }

    /** The beat a sub-menu takes to give the parent menu back (Esc on it) - nine literals in the enchanter. */
    public static final long SUBMENU_BEAT_MS = 1500;

    private final Aborts aborts;
    private final LongSupplier maxVisitMs;
    private final String timeoutReason;
    private final Ctx ctx = new Ctx();
    private Step step = null;
    private BiConsumer<MinecraftClient, String> onAbort;
    private Function<Ctx, Step> onTimeout;
    private Supplier<Object[]> abortFields;

    /** @param maxVisitMs the whole visit's limit (0 = none); past it the visit aborts with {@code timeoutReason}. */
    public GuiFlow(Aborts aborts, LongSupplier maxVisitMs, String timeoutReason) {
        this.aborts = aborts;
        this.maxVisitMs = maxVisitMs;
        this.timeoutReason = timeoutReason;
    }

    /** The owner's part of an abort: close its own menu, cancel its typer, reschedule. */
    public void onAbort(BiConsumer<MinecraftClient, String> hook) { this.onAbort = hook; }

    /**
     * What the visit limit does instead of aborting: the handler returns the step to jump to
     * (the rebirth upgrades jump to their close), or null to carry on. Called every tick past
     * the limit, so a handler that has already jumped returns null.
     */
    public void onTimeout(Function<Ctx, Step> handler) { this.onTimeout = handler; }

    /** Extra key/value pairs for the {@code <prefix>_abort} row (the rebirth upgrades add their click count). */
    public void abortFields(Supplier<Object[]> fields) { this.abortFields = fields; }

    public boolean isBusy() { return step != null; }
    public String phaseName() { return step == null ? "idle" : step.name(); }
    public long visitSince() { return ctx.visitSince; }
    public Aborts aborts() { return aborts; }

    public void start(Step first, long now) {
        step = first;
        ctx.visitSince = now;
        ctx.stepSince = now;
        ctx.until = 0;
        ctx.abortReason = null;
    }

    /** Drop the visit without an abort (a captcha, a toggle, a teleport). */
    public void cancel() { step = null; }

    /** One tick; true while the visit goes on, false the tick it ends (done or aborted). */
    public boolean tick(MinecraftClient client, CombatController combat, long now, EventLogger logger) {
        if (step == null) return false;
        ctx.client = client;
        ctx.combat = combat;
        ctx.logger = logger;
        ctx.now = now;
        long limit = maxVisitMs.getAsLong();
        if (limit > 0 && now - ctx.visitSince > limit) {
            if (onTimeout == null) {
                abort(client, timeoutReason, logger);
                return false;
            }
            Step to = onTimeout.apply(ctx);
            if (to != null && to != step) {
                step = to;
                ctx.stepSince = now;
                ctx.until = 0;
            }
        }
        Step next = step.tick(ctx);
        if (ctx.abortReason != null) {
            String why = ctx.abortReason;
            ctx.abortReason = null;
            abort(client, why, logger);
            return false;
        }
        if (next == DONE) {
            step = null;
            aborts.finish();
            return false;
        }
        if (next != null && next != step) {
            step = next;
            ctx.stepSince = now;
            ctx.until = 0;
        }
        return true;
    }

    /** An abort from outside a step (the owner's own rule). */
    public void abort(MinecraftClient client, String why, EventLogger logger) {
        String phase = phaseName();
        step = null;
        aborts.abort(ctx.now, why, phase, logger, abortFields == null ? new Object[0] : abortFields.get());
        if (onAbort != null) onAbort.accept(client, why);
    }

    // ---------------------------------------------------------------- primitives

    /** A step written in place: the body returns the next step, {@link #DONE}, or null to stay. */
    public static Step custom(String name, Function<Ctx, Step> body) {
        return new Step() {
            @Override public String name() { return name; }
            @Override public Step tick(Ctx c) { return body.apply(c); }
        };
    }

    /** Wait until {@code open} holds, else abort with {@code abortReason} after {@code timeoutMs}. */
    public static Step waitFor(String name, Predicate<Ctx> open, LongSupplier timeoutMs, String abortReason, Step next) {
        return new Step() {
            @Override public String name() { return name; }
            @Override public Step tick(Ctx c) {
                if (open.test(c)) return next;
                if (!c.armed()) c.arm(timeoutMs.getAsLong());
                if (c.due()) c.abort(abortReason);
                return null;
            }
        };
    }

    /** Wait until {@code still} holds (a stationary player), else abort "not-still" after {@code timeoutMs}. */
    public static Step waitStill(String name, Predicate<Ctx> still, LongSupplier timeoutMs, Step next) {
        return waitFor(name, still, timeoutMs, "not-still", next);
    }

    /** A human beat, then {@code next}; with {@code abortIfScreen}, a screen open at the end aborts "screen-open". */
    public static Step pause(String name, LongSupplier delayMs, boolean abortIfScreen, Step next) {
        return new Step() {
            @Override public String name() { return name; }
            @Override public Step tick(Ctx c) {
                if (!c.armed()) c.arm(delayMs.getAsLong());
                if (!c.due()) return null;
                if (abortIfScreen && c.client != null && c.client.currentScreen != null) { c.abort("screen-open"); return null; }
                return next;
            }
        };
    }

    /**
     * Type a command. A screen already open before typing starts is either our own menu
     * (straight to {@code next}) or an abort "screen-open"; a typer failure aborts with its
     * reason; {@code onSent} runs once the line is away.
     */
    public static Step type(String name, ChatTyper typer, Supplier<String> command, Predicate<Ctx> ourGui, Consumer<Ctx> onSent, Step next) {
        return new Step() {
            @Override public String name() { return name; }
            @Override public Step tick(Ctx c) {
                if (c.client.currentScreen != null && !typer.running()) {
                    if (ourGui.test(c)) return next;
                    c.abort("screen-open");
                    return null;
                }
                if (!typer.running()) { typer.begin(c.client, command.get(), c.now); return null; }
                ChatTyper.State s = typer.tick(c.client, c.now);
                if (s == ChatTyper.State.FAILED) { c.abort(typer.failReason()); return null; }
                if (s != ChatTyper.State.DONE) return null;
                if (onSent != null) onSent.accept(c);
                return next;
            }
        };
    }

    /** The first look at a menu: {@code closedReason} if it is gone, else after {@code delayMs} the decision. */
    public static Step look(String name, Predicate<Ctx> open, LongSupplier delayMs, String closedReason, Function<Ctx, Step> decide) {
        return new Step() {
            @Override public String name() { return name; }
            @Override public Step tick(Ctx c) {
                if (!open.test(c)) { c.abort(closedReason); return null; }
                if (!c.armed()) c.arm(delayMs.getAsLong());
                if (!c.due()) return null;
                return decide.apply(c);
            }
        };
    }

    /** A beat, then one click on {@code slot} (gui_click), then {@code next}; the menu gone meanwhile aborts "menu-closed". */
    public static Step click(String name, Predicate<Ctx> open, LongSupplier delayMs, IntSupplier slot, String flow,
                             Supplier<String> what, Consumer<Ctx> onClick, Step next) {
        return new Step() {
            @Override public String name() { return name; }
            @Override public Step tick(Ctx c) {
                if (!open.test(c)) { c.abort("menu-closed"); return null; }
                if (!c.armed()) c.arm(delayMs.getAsLong());
                if (!c.due()) return null;
                if (c.client != null) GuiHuman.click(c.client, slot.getAsInt(), flow, what == null ? null : what.get(), c.logger);
                if (onClick != null) onClick.accept(c);
                return next;
            }
        };
    }

    /** A beat, then the decision (a re-read after a purchase, a confirmation wait). */
    public static Step settle(String name, LongSupplier delayMs, Function<Ctx, Step> decide) {
        return new Step() {
            @Override public String name() { return name; }
            @Override public Step tick(Ctx c) {
                if (!c.armed()) c.arm(delayMs.getAsLong());
                if (!c.due()) return null;
                return decide.apply(c);
            }
        };
    }

    /** A beat, then close the menu if it is still open (gui_close), then {@code after}. */
    public static Step close(String name, Predicate<Ctx> open, LongSupplier delayMs, String flow, Step after) {
        return new Step() {
            @Override public String name() { return name; }
            @Override public Step tick(Ctx c) {
                if (!c.armed()) c.arm(delayMs.getAsLong());
                if (!c.due()) return null;
                if (c.client != null && open.test(c)) GuiHuman.close(c.client, flow, c.logger);
                return after;
            }
        };
    }
}
