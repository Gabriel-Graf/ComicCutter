#!/usr/bin/env python3
"""Generischer Vorhersage-Export für ein beliebiges YOLO-.pt-Modell ins Webtool.
Schreibt Pixel-JSON ({name,imageWidth,imageHeight,panels:[[x,y,w,h]]}) nach
<MAIN>/dataset/img/<outdir>/<base>.json.

Aufruf: python3 yolo/generate_model_json.py <model.pt> <outdir> [conf] [keep_classes_csv]
  keep_classes_csv: nur diese Klassen-IDs behalten (z.B. '0' für panel/frame). Leer = alle.
"""
import json
import os
import sys

from PIL import Image
from ultralytics import YOLO

MAIN = "/home/gabriel/Documents/Projekte/komga-reader-guided-comic"
IMG = os.path.join(MAIN, "dataset", "img")
HERE = os.path.dirname(os.path.abspath(__file__))

MODEL = sys.argv[1]
OUTDIR = os.path.join(IMG, sys.argv[2])
CONF = float(sys.argv[3]) if len(sys.argv) > 3 else 0.30
KEEP = set(int(c) for c in sys.argv[4].split(",")) if len(sys.argv) > 4 and sys.argv[4] else None


def reading_order(boxes):
    boxes = sorted(boxes, key=lambda b: b[1])
    rows, cur, top = [], [], None
    for b in boxes:
        if top is None or b[1] <= top + b[3] * 0.5:
            cur.append(b); top = b[1] if top is None else top
        else:
            rows.append(sorted(cur, key=lambda c: c[0])); cur = [b]; top = b[1]
    if cur:
        rows.append(sorted(cur, key=lambda c: c[0]))
    return [b for r in rows for b in r]


def main():
    os.makedirs(OUTDIR, exist_ok=True)
    model = YOLO(MODEL)
    pngs = sorted(f for f in os.listdir(IMG) if f.endswith(".png"))
    n = 0
    for fn in pngs:
        outp = os.path.join(OUTDIR, fn[:-4] + ".json")
        if os.path.exists(outp):
            continue  # nur neue Seiten verarbeiten (bereits generierte überspringen)
        path = os.path.join(IMG, fn)
        w, h = Image.open(path).size
        r = model.predict(path, conf=CONF, imgsz=1024, device=os.environ.get("YOLO_DEVICE", "cpu"), verbose=False)[0]
        xyxy = r.boxes.xyxy.cpu().numpy()
        cls = r.boxes.cls.cpu().numpy().astype(int)
        conf = r.boxes.conf.cpu().numpy()
        # conf reitet als 5. Element mit, damit reading_order Box+conf gemeinsam sortiert
        boxes = [[int(x1), int(y1), int(x2 - x1), int(y2 - y1), float(cf)]
                 for (x1, y1, x2, y2), c, cf in zip(xyxy, cls, conf) if KEEP is None or c in KEEP]
        ordered = reading_order(boxes)
        out = {"name": fn, "imageWidth": w, "imageHeight": h,
               "panels": [b[:4] for b in ordered], "confs": [round(b[4], 4) for b in ordered]}
        json.dump(out, open(outp, "w"))
        n += 1
    print(f"{sys.argv[2]}: {n} JSONs (conf={CONF}, keep={KEEP}) → {OUTDIR}")


if __name__ == "__main__":
    main()
