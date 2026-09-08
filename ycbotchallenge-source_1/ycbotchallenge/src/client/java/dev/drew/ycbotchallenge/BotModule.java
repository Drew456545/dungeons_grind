package dev.drew.ycbotchallenge;

/**
 * 0.9.62: what every controller carried by copy - the event logger, its setter and the
 * null-safe {@code log}. {@link #logThrottled} replaces the hand-rolled
 * {@code lastXLogAt} fields (eleven of them, each with its own interval).
 */
public abstract class BotModule {
    protected EventLogger logger;

    public void setLogger(EventLogger logger) { this.logger = logger; }

    protected void log(String type, Object... kv) {
        if (logger != null) logger.log(type, kv);
    }

    /** Log {@code type} at most once per {@code everyMs} under {@code key}; true when it logged. */
    protected boolean logThrottled(String key, long everyMs, String type, Object... kv) {
        return logger != null && logger.throttled(key, everyMs, type, kv);
    }
}
