#!/usr/bin/env python3
"""Train the captcha recogniser (0.9.32): a small CRNN with CTC on synthetic maps.

Data comes from captcha_synth.make() on the fly (no dataset on disk); the only real
images are the certified fixtures, used as the held-out check every epoch. Runs on
the GPU (the vLLM server must be stopped first — the 3080 Ti cannot hold both).

    python tools/captcha_train.py --minutes 25 --out tools/captcha-model/crnn.pt

Exports a TorchScript module (~3 MB) that captcha_serve.py loads on the CPU.
"""
import argparse, json, os, random, sys, time

import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import captcha_synth as synth  # noqa: E402

CHARS = synth.CHARS
BLANK = 0
IDX = {c: i + 1 for i, c in enumerate(CHARS)}
SIZE = 128


class CRNN(nn.Module):
    """RGB 128x128 -> conv features -> width sequence (32 steps) -> BiLSTM -> CTC logits."""

    def __init__(self, n_classes=len(CHARS) + 1):
        super().__init__()
        def block(i, o, pool):
            return nn.Sequential(nn.Conv2d(i, o, 3, padding=1), nn.BatchNorm2d(o), nn.ReLU(inplace=True), nn.MaxPool2d(pool))
        self.cnn = nn.Sequential(
            block(3, 32, (2, 2)),    # 64x64
            block(32, 64, (2, 2)),   # 32x32
            block(64, 128, (2, 1)),  # 16x32
            block(128, 128, (2, 1)), # 8x32
            block(128, 256, (2, 1)), # 4x32
            nn.Conv2d(256, 256, (4, 1)), nn.BatchNorm2d(256), nn.ReLU(inplace=True),  # 1x32
        )
        self.rnn = nn.LSTM(256, 128, num_layers=2, bidirectional=True, batch_first=True, dropout=0.1)
        self.fc = nn.Linear(256, n_classes)

    def forward(self, x):
        f = self.cnn(x)                 # B, C, 1, W
        f = f.squeeze(2).permute(0, 2, 1)  # B, W, C
        f, _ = self.rnn(f)
        return self.fc(f)               # B, W, classes


def to_tensor(img):
    t = torch.frombuffer(bytearray(img.convert("RGB").tobytes()), dtype=torch.uint8).view(SIZE, SIZE, 3)
    return t.permute(2, 0, 1).float().div_(255.0)


def decode(logits):
    """Greedy CTC decode of B x W x C logits -> strings."""
    out = []
    for seq in logits.argmax(-1).tolist():
        s, prev = [], BLANK
        for k in seq:
            if k != BLANK and k != prev:
                s.append(CHARS[k - 1])
            prev = k
        out.append("".join(s))
    return out


def batch(rng, fonts, n):
    imgs, labels = [], []
    for _ in range(n):
        img, label = synth.make(rng, fonts)
        imgs.append(to_tensor(img))
        labels.append(label)
    x = torch.stack(imgs)
    targets = torch.tensor([IDX[c] for l in labels for c in l], dtype=torch.long)
    lengths = torch.tensor([len(l) for l in labels], dtype=torch.long)
    return x, targets, lengths, labels


def fixtures():
    fx = json.load(open(os.path.join(HERE, "captcha-fixtures", "fixtures.json"), encoding="utf-8"))
    out = []
    for f in fx:
        if f["kind"] != "map":
            continue
        img = Image.open(os.path.join(HERE, "captcha-fixtures", f["file"])).convert("RGB")
        if img.size != (SIZE, SIZE):
            img = img.resize((SIZE, SIZE), Image.BOX)
        out.append((to_tensor(img), f["answer"]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--minutes", type=float, default=20)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--out", default=os.path.join(HERE, "captcha-model", "crnn.pt"))
    ap.add_argument("--seed", type=int, default=0)
    a = ap.parse_args()
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    rng = random.Random(a.seed)
    torch.manual_seed(a.seed)
    fonts = synth.find_fonts()
    model = CRNN().to(dev)
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=1e-4)
    ctc = nn.CTCLoss(blank=BLANK, zero_infinity=True)
    real = fixtures()
    rx = torch.stack([t for t, _ in real]).to(dev)
    ry = [l for _, l in real]
    print(f"device {dev}; fonts {[os.path.basename(f) for f in fonts]}; real fixtures {ry}", flush=True)
    t0 = time.time()
    step = 0
    best = -1
    while time.time() - t0 < a.minutes * 60:
        model.train()
        x, targets, lengths, labels = batch(rng, fonts, a.batch)
        x = x.to(dev)
        logits = model(x)                          # B, W, C
        logp = F.log_softmax(logits, -1).permute(1, 0, 2)  # W, B, C
        in_len = torch.full((x.size(0),), logits.size(1), dtype=torch.long)
        loss = ctc(logp, targets, in_len, lengths)
        opt.zero_grad()
        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), 5.0)
        opt.step()
        step += 1
        if step % 100 == 0:
            model.eval()
            with torch.no_grad():
                sy = decode(model(x[:16]))
                acc = sum(p == l for p, l in zip(sy, labels[:16])) / 16
                ry_hat = decode(model(rx))
            hits = sum(p == l for p, l in zip(ry_hat, ry))
            print(f"[{(time.time() - t0) / 60:5.1f} min] step {step} loss {loss.item():.3f} synth-acc {acc:.2f} "
                  f"real {hits}/{len(ry)} {list(zip(ry, ry_hat))}", flush=True)
            if hits >= best:
                best = hits
                os.makedirs(os.path.dirname(a.out), exist_ok=True)
                model.eval()
                scripted = torch.jit.script(model.cpu())
                scripted.save(a.out)
                model.to(dev)
    print(f"done: {step} steps, best real {best}/{len(ry)}, saved {a.out}", flush=True)


if __name__ == "__main__":
    main()
