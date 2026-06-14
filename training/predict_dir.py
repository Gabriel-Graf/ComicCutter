#!/usr/bin/env python3
"""Vorhersage-Export für ein YOLO-.pt-Modell über ein BELIEBIGES Bildverzeichnis
(nicht nur dataset/img/*.png wie generate_model_json.py). Pixel-JSON-Schema
{name,imageWidth,imageHeight,panels:[[x,y,w,h]],confs:[]} pro Bild.

Aufruf: python3 yolo/predict_dir.py <model.pt> <input_dir> <output_dir> [conf] [ext]
Rerunnable: bereits erzeugte JSONs werden übersprungen. Device via CUDA (sonst CPU)
oder YOLO_DEVICE überschreibbar.
"""
import json
import os
import sys

import torch
from PIL import Image
from ultralytics import YOLO

MODEL = sys.argv[1]
INDIR = sys.argv[2]
OUTDIR = sys.argv[3]
CONF = float(sys.argv[4]) if len(sys.argv) > 4 else 0.30
EXT = sys.argv[5] if len(sys.argv) > 5 else ".jpg"
DEVICE = os.environ.get("YOLO_DEVICE") or ("0" if torch.cuda.is_available() else "cpu")


def reading_order(boxes):
    """Grob zeilenweise (oben→unten), je Zeile links→rechts — wie die App erwartet."""
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
    files = sorted(f for f in os.listdir(INDIR) if f.endswith(EXT))
    total = len(files)
    n = 0
    for fn in files:
        outp = os.path.join(OUTDIR, os.path.splitext(fn)[0] + ".json")
        if os.path.exists(outp):
            continue
        path = os.path.join(INDIR, fn)
        w, h = Image.open(path).size
        r = model.predict(path, conf=CONF, imgsz=1024, device=DEVICE, verbose=False)[0]
        xyxy = r.boxes.xyxy.cpu().numpy()
        conf = r.boxes.conf.cpu().numpy()
        boxes = [[int(x1), int(y1), int(x2 - x1), int(y2 - y1), float(cf)]
                 for (x1, y1, x2, y2), cf in zip(xyxy, conf)]
        ordered = reading_order(boxes)
        out = {"name": fn, "imageWidth": w, "imageHeight": h,
               "panels": [b[:4] for b in ordered], "confs": [round(b[4], 4) for b in ordered]}
        json.dump(out, open(outp, "w"))
        n += 1
        if n % 50 == 0:
            print(f"  {n} neu ({total} gesamt)")
    print(f"Fertig: {n} neue JSONs → {OUTDIR} (device={DEVICE}, conf={CONF})")


if __name__ == "__main__":
    main()
