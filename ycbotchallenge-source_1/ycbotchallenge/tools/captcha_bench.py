#!/usr/bin/env python3
"""Captcha reader benchmark against the certified fixtures.

Every captcha whose answer we know goes into tools/captcha-fixtures/fixtures.json
(the bot dumps each capture to ycbotchallenge-logs/captcha-<time>-map.png; copy it
here, downsampled to the 128 px map grid, with the answer the server accepted).
Run this before changing captchaMapPrompt / captchaMapScale / captchaMapSmooth:

    python tools/captcha_bench.py --single --real # THE GATE since 0.9.32
    python tools/captcha_bench.py                # full matrix, 8 samples per cell
    python tools/captcha_bench.py --samples 16   # tighter
    python tools/captcha_bench.py --prompt shipped --render x4bil

--single is what the mod actually does since 0.9.32: one greedy read of the native
128 px map. The full matrix and --vote are comparison modes kept for tuning, not a
description of the shipped config. Keep cloud runs to a handful of calls.

It talks to whatever OpenAI-compatible endpoint the mod is pointed at — QwenCloud by
default, a local vLLM if YCBOT_VLM_URL says so (chat-completions, image as a data
URI). A cell's score is the exact-match rate of the parsed answer (the same
JSON-array parsing the mod does, case-sensitive). "pair" columns score the
two-render strategy: truth is in {first render's reading, second render's reading}.
"""
import argparse, base64, collections, io, json, os, re, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, "captcha-fixtures")
# Defaults track the shipped config (0.9.32+). For the local vLLM reader:
#   YCBOT_VLM_URL=http://127.0.0.1:8000/v1/chat/completions
#   YCBOT_VLM_MODEL=Qwen/Qwen3-VL-4B-Instruct-FP8
URL = os.environ.get("YCBOT_VLM_URL",
                     "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions")
MODEL = os.environ.get("YCBOT_VLM_MODEL", "qwen3.6-flash")
# Cloud endpoints (0.9.32): the same env the mod reads; never written to a file.
KEY = os.environ.get("YCBOT_VLM_KEY")
if not KEY:
    # ~/.ycbot_vlm_key: one line, the key. Kept out of the repo and out of the shell history.
    _kf = os.path.join(os.path.expanduser("~"), ".ycbot_vlm_key")
    if os.path.exists(_kf):
        KEY = open(_kf, encoding="utf-8").read().strip() or None

PROMPTS = {
    # captchaMapPrompt as shipped in 0.9.13
    "shipped": ("The image is a Minecraft map captcha: a short random string of large colored letters over a dark "
                "background with small colored noise specks. It is NOT a word, so do not autocorrect. Ignore the specks. "
                "Read the large letters left to right, keeping exact case. Reply with exactly one line:\n"
                "ANSWER: <the letters as a JSON array of single characters, e.g. [\"a\",\"B\"]>"),
    # letters-or-digits wording
    "mild": ("The image is a Minecraft map captcha: a short random string of large colored characters (letters, "
             "occasionally a digit) over a dark background with small colored noise specks. It is NOT a word, so do not "
             "autocorrect. Ignore the specks. Read the large characters left to right, keeping exact case. Reply with "
             "exactly one line:\nANSWER: <the characters as a JSON array of single characters, e.g. [\"a\",\"B\"]>"),
    # explicit look-alike coaching (over-corrected p -> 8 in the 2026-09-03 bench)
    "digits-hint": ("The image is a Minecraft map captcha: a short random string of large colored characters (letters "
                    "a-z, A-Z and digits 0-9 mixed) over a dark background with small colored noise specks. It is NOT a "
                    "word, so do not autocorrect. Ignore the specks. Read the large characters left to right, keeping "
                    "exact case, and decide carefully between look-alikes: 8 vs B, 0 vs O, 1 vs l vs I, 5 vs S, 6 vs b. "
                    "Reply with exactly one line:\nANSWER: <the characters as a JSON array of single characters>"),
}

# name -> (scale, resample); the mod renders the 128 px map at captchaMapScale, bilinear when smooth and scale > 2
RENDERS = {
    "x1": (1, "nearest"), "x2near": (2, "nearest"), "x3bil": (3, "bilinear"), "x3near": (3, "nearest"),
    "x4near": (4, "nearest"), "x4bil": (4, "bilinear"), "x5bil": (5, "bilinear"), "x6bil": (6, "bilinear"),
}
# The PRE-0.9.32 ballot schedule, kept so --vote can still replay the local-4B setup.
# The shipped config is one native read (captchaVoteRenders ["x1"], temperature 0);
# since 0.9.34 the mod hedges that read in TIME rather than across renders.
VOTE_RENDERS = ["x4bil", "x3bil", "x2near", "x5bil", "x3near"]
VOTE_TEMPERATURE = 0.6

ARRAY_RE = re.compile(r"\[([^\]]*)\]")
ITEM_RE = re.compile(r"\"([^\"]*)\"|'([^']*)'|([^,\s\"']+)")


def parse_answer(content):
    """Same rule as ChatClassifier.parseAnswerArray: array after ANSWER: (or first array), chars joined, case kept."""
    if not content:
        return None
    at = content.upper().find("ANSWER")
    m = ARRAY_RE.search(content, at if at >= 0 else 0) or ARRAY_RE.search(content)
    if not m:
        return None
    out = []
    for q1, q2, bare in ITEM_RE.findall(m.group(1)):
        s = q1 or q2 or bare
        out.append("".join(c for c in s if not c.isspace()))
    s = "".join(out)
    return s or None


def render(img, name):
    from PIL import Image
    scale, res = RENDERS[name]
    im = img.resize((128 * scale, 128 * scale), Image.BILINEAR if res == "bilinear" else Image.NEAREST)
    b = io.BytesIO(); im.save(b, "PNG"); return b.getvalue()


def ask(prompt, png, temperature, n, timeout=120):
    b64 = base64.b64encode(png).decode()
    body = {"model": MODEL, "temperature": temperature, "max_tokens": 64, "n": n,
            # DashScope/QwenCloud thinking models: no reasoning tokens for a four-letter read.
            "enable_thinking": False,
            "messages": [{"role": "user", "content": [
                {"type": "image_url", "image_url": {"url": "data:image/png;base64," + b64}},
                {"type": "text", "text": prompt}]}]}
    headers = {"Content-Type": "application/json"}
    if KEY:
        headers["Authorization"] = "Bearer " + KEY
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), headers=headers)
    t = time.time()
    r = json.load(urllib.request.urlopen(req, timeout=timeout))
    return [c["message"]["content"].strip() for c in r["choices"]], time.time() - t


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--samples", type=int, default=8, help="samples per cell at temperature 0.7 (plus one at 0)")
    ap.add_argument("--prompt", action="append", help="restrict to these prompt names")
    ap.add_argument("--render", action="append", help="restrict to these render names")
    ap.add_argument("--kind", default="map", help="fixture kind to run: map (default) or screen")
    ap.add_argument("--real", action="store_true", help="only the bot's own certified captures (no synthetic fixture)")
    ap.add_argument("--single", action="store_true",
                    help="one greedy read of the x4bil render per fixture (the 'is one guess enough' test), repeated --samples times")
    ap.add_argument("--vote", action="store_true",
                    help="replay the mod's ballot (VOTE_RENDERS at t0 then t0.6) per fixture and print the leader")
    args = ap.parse_args()
    if args.single:
        sys.exit(single(args))

    if args.vote:
        return vote(args)

    from PIL import Image
    fixtures = [f for f in json.load(open(os.path.join(FIX, "fixtures.json"))) if f["kind"] == args.kind]
    prompts = {k: v for k, v in PROMPTS.items() if not args.prompt or k in args.prompt}
    renders = [r for r in RENDERS if not args.render or r in args.render]
    print(f"model {MODEL} at {URL}; {len(fixtures)} fixtures ({args.kind}); {args.samples} samples/cell\n")

    results = {}  # (prompt, render, fixture) -> dict(greedy, hits, n, readings)
    for pn, prompt in prompts.items():
        for rn in renders:
            for fx in fixtures:
                img = Image.open(os.path.join(FIX, fx["file"])).convert("RGB")
                png = render(img, rn) if fx["kind"] == "map" else open(os.path.join(FIX, fx["file"]), "rb").read()
                g, dt = ask(prompt, png, 0.0, 1)
                greedy = parse_answer(g[0])
                outs, _ = ask(prompt, png, 0.7, args.samples)
                readings = collections.Counter(parse_answer(o) for o in outs)
                hits = readings.get(fx["answer"], 0)
                results[(pn, rn, fx["file"])] = dict(greedy=greedy, hits=hits, n=args.samples, readings=readings, ms=int(dt * 1000))
                flag = "OK " if greedy == fx["answer"] else "BAD"
                print(f"{flag} {pn:12s} {rn:7s} {fx['answer']:5s} greedy={str(greedy):6s} "
                      f"t0.7 {hits}/{args.samples}  {dict(readings.most_common(3))}  {int(dt*1000)}ms")
            if fx["kind"] == "screen":
                break  # renders do not apply to screenshots
    print("\n== per prompt x render: fixtures read right greedily / mean hit rate at t0.7")
    for pn in prompts:
        for rn in renders:
            cells = [v for (p, r, f), v in results.items() if p == pn and r == rn]
            if not cells:
                continue
            g_ok = sum(1 for fx in fixtures if results[(pn, rn, fx["file"])]["greedy"] == fx["answer"])
            rate = sum(c["hits"] / c["n"] for c in cells) / len(cells)
            print(f"  {pn:12s} {rn:7s} greedy {g_ok}/{len(fixtures)}  sampled {rate:.0%}")
    if args.kind == "map" and len(renders) > 1:
        print("\n== two-render pairs (truth in {greedy A, greedy B}):")
        for pn in prompts:
            best = []
            for a in renders:
                for b in renders:
                    if a >= b:
                        continue
                    ok = sum(1 for fx in fixtures if fx["answer"] in
                             {results[(pn, a, fx["file"])]["greedy"], results[(pn, b, fx["file"])]["greedy"]})
                    best.append((ok, a, b))
            best.sort(reverse=True)
            for ok, a, b in best[:4]:
                print(f"  {pn:12s} {a}+{b}: {ok}/{len(fixtures)}")


def single(args):
    """One guess, no ballot: read one render (default x1, the native map — the 0.9.32 mod default) greedily once per fixture (--samples repeats). With --real only
    the bot's own certified captures are used (no synthetic fixture). Exit 0 iff every read is right."""
    from PIL import Image
    fixtures = [f for f in json.load(open(os.path.join(FIX, "fixtures.json"))) if f["kind"] == "map"]
    if args.real:
        fixtures = [f for f in fixtures if f.get("source", "").startswith("bot capture")]
    prompt = PROMPTS["shipped"]
    print("model %s at %s; single greedy read of %s, %dx per fixture, %d fixtures" % (MODEL, URL, (args.render or ["x1"])[0], args.samples, len(fixtures)))
    total = right = 0
    for fx in fixtures:
        img = Image.open(os.path.join(FIX, fx["file"])).convert("RGB")
        reads, secs = [], []
        for _ in range(args.samples):
            outs, dt = ask(prompt, render(img, (args.render or ["x1"])[0]), 0.0, 1)
            reads.append(parse_answer(outs[0]))
            secs.append(dt)
        ok = sum(r == fx["answer"] for r in reads)
        total += len(reads)
        right += ok
        flag = "OK " if ok == len(reads) else "BAD"
        print("%s %-5s %d/%d right  reads=%s  %.1fs avg" % (flag, fx["answer"], ok, len(reads), reads, sum(secs) / len(secs)))
    print("%d/%d single reads right" % (right, total))
    return 0 if right == total else 1


def vote(args):
    """The mod's 0.9.26 ballot: every render read once greedily, then once at temperature; leader = most
    votes, ties to the first-seen reading. Prints the tallies per fixture and whether the leader is right."""
    from PIL import Image
    fixtures = [f for f in json.load(open(os.path.join(FIX, "fixtures.json"))) if f["kind"] == "map"]
    prompt = PROMPTS["shipped"]
    print(f"model {MODEL} at {URL}; ballot {VOTE_RENDERS} at t0 then t{VOTE_TEMPERATURE}\n")
    ok = 0
    for fx in fixtures:
        img = Image.open(os.path.join(FIX, fx["file"])).convert("RGB")
        tallies, order, log = collections.Counter(), [], []
        for temp in (0.0, VOTE_TEMPERATURE):
            for rn in VOTE_RENDERS:
                outs, _ = ask(prompt, render(img, rn), temp, 1)
                r = parse_answer(outs[0])
                log.append(f"{rn}@{temp:g}={r}")
                if r:
                    tallies[r] += 1
                    if r not in order:
                        order.append(r)
        leader = max(order, key=lambda r: (tallies[r], -order.index(r))) if order else None
        flag = "OK " if leader == fx["answer"] else "BAD"
        ok += leader == fx["answer"]
        print(f"{flag} {fx['answer']:5s} leader={leader} tallies={dict(tallies)}  {' '.join(log)}")
    print(f"\n{ok}/{len(fixtures)} fixtures led by the right reading")
    return 0 if ok == len(fixtures) else 1


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    main()
