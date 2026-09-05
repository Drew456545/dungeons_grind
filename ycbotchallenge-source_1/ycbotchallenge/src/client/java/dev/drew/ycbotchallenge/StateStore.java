package dev.drew.ycbotchallenge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Learned prices per player username, persisted in {@code config/ycbotchallenge-state.json}
 * so a restart never has to re-probe /rebirth or /swordmax to relearn what the
 * server already told us (the 0.9.x "enable ritual"). Keyed by username because
 * Drew runs an alt. Pure Gson — unit-tested in EconomyChecks on a temp file.
 */
public final class StateStore {
    /** One user's learned economy. Nulls mean unknown. */
    public static final class Entry {
        public Double swordTarget;
        public Double zoneTarget;
        public Double rebirthTarget;
        public Double swordLastPrice;
        public Double zoneLastPrice;
        public Double rebirthLastPrice;
        public Integer rebirths;
        /** 0.9.30: rebirth count when a /rebirth visit last read zero points — no re-check until it moves. */
        public Integer pointsCheckedAtRebirths;
        /** 0.9.31: price ladders learned on this account (sword ×3.5 / zone ×55 per step), and whether the targets are predictions. */
        public Double swordGrowth;
        public Double zoneGrowth;
        public Boolean swordTargetPredicted;
        public Boolean zoneTargetPredicted;
        /** 0.9.33 companions: the stage last bought at, visits this rebirth (and which rebirth), the once-per-rebirth end visit, per-stage egg prices seen. */
        public Integer companionLastBoughtStage;
        public Integer companionVisitsThisRebirth;
        public Integer companionVisitsAtRebirths;
        /** Unused since 0.9.35 (the end-of-rebirth visit became a normal companion-end decision); kept so an old file still round-trips. */
        public Boolean companionEndFallbackDone;
        public Map<String, Double> companionEggPriceByStage;
        /**
         * 0.9.35: the income multiplier a batch actually brought, learned per account from
         * the income before/after each visit, and the visits already spent per stage (the
         * per-rebirth cap used to burn both on a stage the bot then never left).
         */
        public Double companionGainLearned;
        public Map<String, Integer> companionVisitsByStage;
        public Integer companionVisitsStageRebirths;
        /**
         * 0.9.36: the rebirth cycle clock. Bot-on minutes of the last full cycle (what a
         * persistent income multiplier pays back against), the running bot-on ms of the
         * current one and the rebirth count it belongs to.
         */
        public Double lastCycleOnMin;
        public Long cycleOnMs;
        public Integer cycleAtRebirths;
        /** 0.9.37: the stage the last bought visit changed nothing on (its egg pool is spent). */
        public Integer companionSaturatedStage;
        /** 0.9.41: companions owned per zone/stage ("z3s1" -> count) at the last menu read. */
        public Map<String, Integer> companionRosterByZs;
        /** 0.9.37: the stages of the rebirth in progress, and the last cycles, so the record survives a restart. */
        public List<StageEntry> cycleStages;
        public List<CycleEntry> cycleHistory;
        public long savedAt;
    }

    /** One stage of a rebirth (0.9.37): bot-on and wall minutes, kills, buys, what it earned. */
    public static final class StageEntry {
        public Integer stage;
        public double onMin;
        public double wallMin;
        public int kills;
        public int swordBuys;
        public Double moneyEarned;
        public Double peakPerMin;
        public Double medianTtkMs;
    }

    /** One finished rebirth cycle (0.9.37). */
    public static final class CycleEntry {
        public Integer rebirths;
        public long endedAt;
        public double onMin;
        public Double wallMin;
        public Double toLvl14OnMin;
        public Integer topStage;
        public List<StageEntry> stages;
        /** 0.9.42: the climb/farm split and what the farm paid for. */
        public Double climbMin;
        public Double farmMin;
        public Integer farmKills;
        public Double rebirthCost;
        public Double topZonePrice;
        public Double ratio;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Entry>>() {}.getType();

    private final Path file;
    private Map<String, Entry> users = new LinkedHashMap<>();

    public StateStore(Path file) {
        this.file = file;
        load();
    }

    public Path file() { return file; }

    public void load() {
        try {
            if (file != null && Files.exists(file)) {
                Map<String, Entry> m = GSON.fromJson(Files.readString(file), MAP_TYPE);
                if (m != null) users = new LinkedHashMap<>(m);
            }
        } catch (Exception e) {
            YCBotChallengeClient.LOGGER.warn("Failed to read state file {}: {}", file, e.toString());
        }
    }

    public static String key(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    public Entry get(String username) {
        String k = key(username);
        return k == null ? null : users.get(k);
    }

    public void put(String username, Entry entry) {
        String k = key(username);
        if (k == null || entry == null) return;
        entry.savedAt = System.currentTimeMillis();
        users.put(k, entry);
        save();
    }

    public void remove(String username) {
        String k = key(username);
        if (k != null && users.remove(k) != null) save();
    }

    public Set<String> usernames() { return users.keySet(); }

    private void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(users, MAP_TYPE));
        } catch (IOException e) {
            YCBotChallengeClient.LOGGER.warn("Failed to write state file {}: {}", file, e.toString());
        }
    }
}
