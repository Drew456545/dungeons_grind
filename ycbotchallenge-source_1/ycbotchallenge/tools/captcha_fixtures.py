#!/usr/bin/env python3
"""Accepted captchas become fixtures, automatically (0.9.40).

The server prints nothing when a captcha answer is accepted; the mod knows it was accepted
because the map went away (`captcha_solved`). Every such solve is a certified fixture: the
image the model actually read (the 128 px map dump next to the logs) and the answer the
server took. This script walks the logs, pairs each `captcha_solved` with its capture, and
adds anything new to tools/captcha-fixtures/fixtures.json - so the bench
(captcha_bench.py) always scores prompt and model changes against every captcha the server
has ever accepted.

    python tools/captcha_fixtures.py            # import new accepted captchas
    python tools/captcha_fixtures.py --dry-run  # show what would be added
    python tools/captcha_fixtures.py path/to/logs

A solve is imported only when the answer was typed once and accepted (answersSent 1) or
when the accepted answer is the last one sent; a `captcha_failed` after the capture, or a
`captcha_reprompted` naming the answer as wrong, disqualifies it.
"""
import argparse, glob, json, os, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "captcha-fixtures")
DEFAULT_LOGS = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), ".minecraft", "ycbotchallenge-logs")


def rows(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except ValueError:
                continue


def accepted_solves(logs_dir):
    """(answer, capture png path, iso, log file) for every accepted map solve with a dump on disk."""
    out = []
    for path in sorted(glob.glob(os.path.join(logs_dir, "events-*.jsonl"))):
        pending_png = None
        wrong = set()
        for r in rows(path):
            t = r.get("type")
            if t == "captcha_captured" and r.get("png"):
                pending_png = r["png"]
                wrong = set()
            elif t == "captcha_reprompted":
                wrong.update(r.get("wrong") or [])
            elif t == "captcha_solved" and r.get("mode") == "map" and pending_png:
                ans = r.get("answer")
                if ans and ans not in wrong:
                    png = pending_png if os.path.isabs(pending_png) else os.path.join(logs_dir, os.path.basename(pending_png))
                    if os.path.exists(png):
                        out.append((ans, png, r.get("iso"), os.path.basename(path)))
                pending_png = None
            elif t == "captcha_failed":
                pending_png = None
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("path", nargs="?", default=DEFAULT_LOGS)
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()
    fx_path = os.path.join(FIX, "fixtures.json")
    fixtures = json.load(open(fx_path, encoding="utf-8")) if os.path.exists(fx_path) else []
    known = {f["file"] for f in fixtures}
    known_answers = {(f["answer"], f.get("kind", "map")) for f in fixtures}
    added = 0
    for ans, png, iso, log in accepted_solves(a.path):
        name = f"{ans}-map128.png"
        if name in known or (ans, "map") in known_answers:
            continue
        print(f"+ {name}  answer={ans}  from {log} at {iso}")
        if not a.dry_run:
            shutil.copyfile(png, os.path.join(FIX, name))
            fixtures.append({"file": name, "answer": ans, "kind": "map",
                             "source": f"bot capture {iso} (native 128 px map render, the image the model read); typed {ans}, server accepted (auto-imported)"})
        known.add(name)
        known_answers.add((ans, "map"))
        added += 1
    if added and not a.dry_run:
        with open(fx_path, "w", encoding="utf-8", newline="\n") as f:
            f.write(json.dumps(fixtures, ensure_ascii=False, indent=2) + "\n")
    print(f"{added} added, {len(fixtures)} fixtures total")
    return 0


if __name__ == "__main__":
    sys.exit(main())
