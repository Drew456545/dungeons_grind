#!/usr/bin/env python3
"""Rebirth-by-rebirth progress from the bot's JSONL logs (0.9.37).

How long each stage took and how long each rebirth cycle took, in BOT-ON minutes (a paused
bot does not count) with wall minutes beside them, so the trend is visible across days:

    python tools/progress.py                      # every log in the default logs dir
    python tools/progress.py path/to/logs         # another directory (or one .jsonl file)
    python tools/progress.py --since 2026-09-04   # cycles ending on/after a date
    python tools/progress.py --stages             # the per-stage table for every cycle

A cycle runs from one `rebirth` (sidebar counter) / `economy_reset` to the next. Stage
time is attributed from `zone_change` / `zone_teleport` / `boss_level` rows and the row
context's zone label; rows carry `bot` (on/off/paused) since 0.9.33 - older logs have no
bot flag and are summed as wall time, marked "(wall)". Where the 0.9.36+ `stage_record`
and `cycle_end` rows exist they are printed as the bot recorded them.

Money strings are suffixed (K M B T Q QQ S SS O N ...); each rung is x1000.
"""
import argparse, glob, json, os, re, sys
from collections import OrderedDict

DEFAULT_LOGS = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), ".minecraft", "ycbotchallenge-logs")
SUFFIX = ["", "K", "M", "B", "T", "Q", "QQ", "S", "SS", "O", "N", "D", "U"]
SCALE = {s: 1000.0 ** i for i, s in enumerate(SUFFIX)}
AMOUNT = re.compile(r"^(-?[\d,]+(?:\.\d+)?)\s*([A-Za-z]*)$")
GAP_MS = 5 * 60 * 1000


def money(v):
    if v is None:
        return None
    m = AMOUNT.match(str(v).strip())
    if not m or m.group(2) not in SCALE:
        return None
    return float(m.group(1).replace(",", "")) * SCALE[m.group(2)]


def fmt(x):
    if x is None:
        return "-"
    for s in reversed(SUFFIX):
        if s and abs(x) >= SCALE[s]:
            return f"{x / SCALE[s]:.3g}{s}"
    return f"{x:.3g}"


def lvl(row):
    z = (row.get("ctx") or {}).get("zone")
    m = re.match(r"lvl(\d+)", str(z or ""))
    return int(m.group(1)) if m else None


def rows(paths):
    for p in paths:
        with open(p, encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    r = json.loads(line)
                except ValueError:
                    continue
                r["_file"] = os.path.basename(p)
                yield r


class Stage:
    def __init__(self, level, t):
        self.level = level
        self.start = t
        self.end = t
        self.on_ms = 0
        self.kills = 0
        self.sword_buys = 0
        self.earned = 0.0
        self.peak = None
        self.flagged = False

    @property
    def wall_min(self):
        return (self.end - self.start) / 60000.0

    @property
    def on_min(self):
        return self.on_ms / 60000.0


class Cycle:
    def __init__(self, rebirths, t, file):
        self.rebirths = rebirths
        self.start = t
        self.end = t
        self.file = file
        self.stages = []
        self.on_ms = 0
        self.zone_buys = 0
        self.sword_buys = 0
        self.eggs_spent = 0.0
        self.eggs = 0
        self.recorded = None  # cycle_end row, if the bot wrote one
        self.closed = False   # ended by a rebirth (else the log simply stopped)
        self.flagged = False

    def stage(self, level, t):
        if self.stages and self.stages[-1].level == level:
            return self.stages[-1]
        s = Stage(level, t)
        self.stages.append(s)
        return s

    @property
    def wall_min(self):
        return (self.end - self.start) / 60000.0

    @property
    def on_min(self):
        return self.on_ms / 60000.0

    @property
    def top(self):
        return max((s.level for s in self.stages if s.level), default=None)

    def to_stage(self, target):
        total = 0.0
        for s in self.stages:
            if s.level is not None and s.level >= target:
                return total
            total += s.on_min
        return None


def reconstruct(paths):
    cycles = []
    cur = None
    last_t = None
    last_on = None
    for r in rows(paths):
        t = r.get("t")
        if t is None:
            continue
        typ = r.get("type")
        bot = r.get("bot")
        flagged = bot is not None
        on = (bot == "on") if flagged else True
        level = lvl(r)
        if typ in ("rebirth", "economy_reset"):
            if cur is not None and (t - cur.start) < 60000:
                # the same rebirth arriving as a second signal (chat line, money collapse,
                # sidebar counter): keep the cycle, take the counter's own number
                if typ == "rebirth" and r.get("rebirths") is not None:
                    cur.rebirths = r.get("rebirths")
            else:
                if cur is not None:
                    cur.end = t
                    cur.closed = True
                    cycles.append(cur)
                rb = r.get("rebirths") if typ == "rebirth" else None
                if rb is None:
                    prev = (r.get("ctx") or {}).get("rebirths")
                    rb = prev + 1 if isinstance(prev, int) else None
                cur = Cycle(rb, t, r["_file"])
                last_t = t
        # a rebirth that happened while no log was open: the context counter jumps
        crb = (r.get("ctx") or {}).get("rebirths")
        if cur is not None and cur.rebirths is not None and isinstance(crb, int) and crb > cur.rebirths                 and typ not in ("rebirth", "economy_reset"):
            cur.end = last_t if last_t is not None else t
            cur.closed = True
            cycles.append(cur)
            cur = Cycle(crb, t, r["_file"])
            last_t = t
        if cur is None:
            continue
        # the row context still names the pre-rebirth stage for a few seconds after the reset
        # (a rebirth lands on lvl1; the label only updates with the first boss bar there)
        if level is not None and not cur.stages and level > 1 and t - cur.start < 90000:
            level = None
        # time accounting: bot-on ms, never across a gap
        if last_t is not None and 0 < t - last_t < GAP_MS and (on or not flagged):
            dt = t - last_t
            cur.on_ms += dt
            if cur.stages:
                cur.stages[-1].on_ms += dt
        last_t = t
        cur.end = t
        if flagged:
            cur.flagged = True
        if level is not None:
            s = cur.stage(level, t)
            s.end = t
            s.flagged = s.flagged or flagged
        if typ == "kill" and cur.stages:
            cur.stages[-1].kills += 1
        if typ == "upgrade_result" and r.get("success") and r.get("kind") == "sword" and not r.get("extraLevel"):
            cur.sword_buys += 1
            if cur.stages:
                cur.stages[-1].sword_buys += 1
        if typ == "upgrade_result" and r.get("kind") == "zone" and r.get("success") and not r.get("extraLevel"):
            cur.zone_buys += 1
        if typ == "companion_open" and r.get("bought"):
            cur.eggs += int(r.get("count") or 0)
            cur.eggs_spent += money(r.get("paid")) or 0
        if typ == "companion_observed":
            cur.eggs += int(r.get("eggs") or 0)
            cur.eggs_spent += money(r.get("drop")) or 0
        if typ == "income" and cur.stages:
            v = money(r.get("moneyPerMin"))
            s = cur.stages[-1]
            if v is not None and (s.peak is None or v > s.peak):
                s.peak = v
        if typ == "cycle_end":
            # written right after the sidebar counter moved, i.e. just after the new cycle began
            target = cycles[-1] if cycles and t - cur.start < 5000 else cur
            target.recorded = r
            target.closed = True
    if cur is not None:
        cycles.append(cur)
    return cycles


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("path", nargs="?", default=DEFAULT_LOGS, help="logs directory or one .jsonl file")
    ap.add_argument("--since", default=None, help="only cycles ending on/after YYYY-MM-DD")
    ap.add_argument("--stages", action="store_true", help="print the per-stage table for every cycle")
    ap.add_argument("--min-minutes", type=float, default=3.0, help="hide cycles shorter than this (bot-on)")
    a = ap.parse_args()
    if os.path.isdir(a.path):
        paths = sorted(glob.glob(os.path.join(a.path, "events-*.jsonl")))
    else:
        paths = [a.path]
    if not paths:
        print("no logs found in", a.path)
        return 1
    cycles = reconstruct(paths)
    import datetime as dt
    since = dt.datetime.strptime(a.since, "%Y-%m-%d").timestamp() * 1000 if a.since else 0
    shown = 0
    for c in cycles:
        if c.end < since or c.on_min < a.min_minutes:
            continue
        shown += 1
        ended = dt.datetime.fromtimestamp(c.end / 1000).strftime("%Y-%m-%d %H:%M")
        closed = "" if c.closed else " (open: no rebirth seen)"
        wall_note = "" if c.flagged else " (wall - no bot flag in this log)"
        to14 = c.to_stage(14)
        print(f"rb {c.rebirths if c.rebirths is not None else '?'}: {c.on_min:.1f} bot-on min / {c.wall_min:.1f} wall"
              f"{wall_note}, lvl1→14 in {to14:.1f}" if to14 is not None else
              f"rb {c.rebirths if c.rebirths is not None else '?'}: {c.on_min:.1f} bot-on min / {c.wall_min:.1f} wall{wall_note}, lvl14 not reached",
              end="")
        print(f", top lvl{c.top}, {c.zone_buys} zone buys, {c.sword_buys} sword buys, eggs {c.eggs} ({fmt(c.eggs_spent)}),"
              f" ended {ended}{closed}")
        if c.recorded:
            r = c.recorded
            print(f"    bot recorded: onMin {r.get('onMin')} wallMin {r.get('wallMin')} toLvl14 {r.get('toLvl14OnMin')} top lvl{r.get('topStage')}")
        if a.stages:
            print(f"    {'stage':>6} {'on min':>7} {'wall':>7} {'kills':>5} {'swords':>6} {'peak/min':>9}")
            for s in c.stages:
                if s.on_min < 0.25 and s.kills == 0:
                    continue
                print(f"    {('lvl' + str(s.level)) if s.level else '?':>6} {s.on_min:>7.1f} {s.wall_min:>7.1f} {s.kills:>5} {s.sword_buys:>6} {fmt(s.peak):>9}")
    if shown == 0:
        print("no cycles to show")
    return 0


if __name__ == "__main__":
    sys.exit(main())
