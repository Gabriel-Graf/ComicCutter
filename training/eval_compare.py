#!/usr/bin/env python3
"""Fairer Head-to-Head auf demselben Val-Set, gleiche Metrik (greedy IoU>=0.5):
YOLO11n (finetuned) vs. klassischer Detektor (Backend engine=ours auf Port 7421).

Aufruf: python3 yolo/eval_compare.py [conf]
"""
import json
import os
import sys
import urllib.parse
import urllib.request

from PIL import Image
from ultralytics import YOLO

MAIN = "/home/gabriel/Documents/Projekte/komga-reader-guided-comic"
IMG = os.path.join(MAIN, "dataset", "img")
LBL = os.path.join(IMG, "labels")
HERE = os.path.dirname(os.path.abspath(__file__))
BEST = os.path.join(HERE, "runs", "panel", "weights", "best.pt")
CONF = float(sys.argv[1]) if len(sys.argv) > 1 else 0.25
PORT = "7421"


def iou(a, b):
    ax, ay, aw, ah = a; bx, by, bw, bh = b
    ix = max(ax, bx); iy = max(ay, by)
    axx = min(ax + aw, bx + bw); ayy = min(ay + ah, by + bh)
    iw = axx - ix; ih = ayy - iy
    if iw <= 0 or ih <= 0:
        return 0.0
    inter = iw * ih
    return inter / (aw * ah + bw * bh - inter)


def score(gt, det, thr=0.5):
    used = [False] * len(det); m = 0; s = 0.0
    for g in gt:
        bi, bv = -1, 0.0
        for i, d in enumerate(det):
            if used[i]:
                continue
            v = iou(g, d)
            if v > bv:
                bv, bi = v, i
        if bi >= 0 and bv >= thr:
            used[bi] = True; m += 1; s += bv
    return m, len(det) - m, len(gt) - m, s  # matched, extra, missed, iou_sum


def classical(name, w, h):
    qs = urllib.parse.urlencode({"dir": IMG, "engine": "ours"})
    u = f"http://localhost:{PORT}/api/labels/{urllib.parse.quote(name)}?{qs}"
    ps = json.loads(urllib.request.urlopen(u, timeout=60).read())["panels"]
    return [(p["left"] * w, p["top"] * h, p["width"] * w, p["height"] * h) for p in ps]


def main():
    names = [n.strip() for n in open(os.path.join(HERE, "val_images.txt")) if n.strip()]
    model = YOLO(BEST)
    agg = {"yolo": [0, 0, 0, 0.0, 0], "classic": [0, 0, 0, 0.0, 0]}  # m, extra, miss, iou, perfect
    for name in names:
        path = os.path.join(IMG, name + ".png")
        w, h = Image.open(path).size
        gt = [(p["left"] * w, p["top"] * h, p["width"] * w, p["height"] * h)
              for p in json.load(open(os.path.join(LBL, name + ".png.json")))["panels"]]
        # YOLO
        r = model.predict(path, conf=CONF, imgsz=1024, verbose=False)[0]
        yb = [(float(x1), float(y1), float(x2 - x1), float(y2 - y1))
              for x1, y1, x2, y2 in r.boxes.xyxy.cpu().numpy()]
        # klassisch
        cb = classical(name + ".png", w, h)
        for key, det in (("yolo", yb), ("classic", cb)):
            m, ex, mi, s = score(gt, det)
            a = agg[key]
            a[0] += m; a[1] += ex; a[2] += mi; a[3] += s
            if mi == 0 and ex == 0:
                a[4] += 1
    print(f"=== Val-Set n={len(names)}, IoU>=0.5, YOLO conf={CONF} ===")
    for key in ("classic", "yolo"):
        m, ex, mi, s, perf = agg[key]
        prec = m / (m + ex) if m + ex else 0
        rec = m / (m + mi) if m + mi else 0
        miou = s / m if m else 0
        print(f"{key:8} precision={prec:.3f} recall={rec:.3f} meanIoU={miou:.3f} perfect={perf}/{len(names)}")


if __name__ == "__main__":
    main()
