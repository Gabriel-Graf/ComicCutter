#!/usr/bin/env python3
"""Schreibt YOLO11n-Vorhersagen für ALLE Bilder als Pixel-JSON ins Webtool-Verzeichnis
(<MAIN>/dataset/img/yolo/<base>.json, Schema {name,imageWidth,imageHeight,panels:[[x,y,w,h]]}),
damit das Backend sie wie eine externe GT-Quelle ('yolo'-Engine) anzeigen kann.

Aufruf: python3 yolo/generate_json.py [conf]
"""
import json
import os
import sys

from PIL import Image
from ultralytics import YOLO

MAIN = "/home/gabriel/Documents/Projekte/komga-reader-guided-comic"
IMG = os.path.join(MAIN, "dataset", "img")
OUT = os.path.join(IMG, "yolo")
HERE = os.path.dirname(os.path.abspath(__file__))
BEST = os.path.join(HERE, "runs", "panel", "weights", "best.pt")
CONF = float(sys.argv[1]) if len(sys.argv) > 1 else 0.30


def reading_order(boxes):
    """Grob zeilenweise (oben→unten), je Zeile links→rechts — wie die App erwartet."""
    boxes = sorted(boxes, key=lambda b: b[1])
    rows, cur, line_top = [], [], None
    for b in boxes:
        if line_top is None or b[1] <= line_top + b[3] * 0.5:
            cur.append(b); line_top = b[1] if line_top is None else line_top
        else:
            rows.append(sorted(cur, key=lambda c: c[0])); cur = [b]; line_top = b[1]
    if cur:
        rows.append(sorted(cur, key=lambda c: c[0]))
    return [b for r in rows for b in r]


def main():
    os.makedirs(OUT, exist_ok=True)
    model = YOLO(BEST)
    pngs = sorted(f for f in os.listdir(IMG) if f.endswith(".png"))
    n = 0
    for fn in pngs:
        outp = os.path.join(OUT, fn[:-4] + ".json")
        if os.path.exists(outp):
            continue  # nur neue Seiten verarbeiten
        path = os.path.join(IMG, fn)
        w, h = Image.open(path).size
        r = model.predict(path, conf=CONF, imgsz=1024, device="cpu", verbose=False)[0]
        boxes = [[int(x1), int(y1), int(x2 - x1), int(y2 - y1)]
                 for x1, y1, x2, y2 in r.boxes.xyxy.cpu().numpy()]
        boxes = reading_order(boxes)
        out = {"name": fn, "imageWidth": w, "imageHeight": h, "panels": boxes}
        json.dump(out, open(outp, "w"))
        n += 1
    print(f"geschrieben: {n} YOLO-JSONs (conf={CONF}) nach {OUT}")


if __name__ == "__main__":
    main()
