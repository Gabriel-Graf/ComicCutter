#!/usr/bin/env python3
"""Konvertiert ALLE 250 GT-Seiten ins YOLO-Format. GT-Quelle pro Seite:
  - bevorzugt  <IMG>/labels/<name>.png.json  (PanelLabels, normalisiert, Hand-Label)
  - sonst      <IMG>/json/<name>.json         (Pixel {imageWidth,imageHeight,panels:[[x,y,w,h]]}, vom User abgenommen)

Split für FAIREN Vergleich: Val NUR aus Hand-Labels (unabhängige GT, nicht zirkulär),
Train = alle übrigen 250 (max. Daten). Bilder werden symgelinkt. Schreibt data.yaml + val_images.txt.
"""
import json
import os
import random
import shutil

MAIN = "/home/gabriel/Documents/Projekte/komga-reader-guided-comic"
IMG = os.path.join(MAIN, "dataset", "img")
LBL = os.path.join(IMG, "labels")
JSN = os.path.join(IMG, "json")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dataset")
VAL_FRAC = 0.2
SEED = 42


def yolo_from_norm(panels):
    out = []
    for p in panels:
        cx, cy = p["left"] + p["width"] / 2, p["top"] + p["height"] / 2
        out.append((cx, cy, p["width"], p["height"]))
    return out


def yolo_from_pixel(d):
    W, H = d["imageWidth"], d["imageHeight"]
    out = []
    for x, y, w, h in d["panels"]:
        out.append(((x + w / 2) / W, (y + h / 2) / H, w / W, h / H))
    return out


def gt_for(name):
    """(yolo_boxes, is_hand) für eine Seite; bevorzugt Hand-Label."""
    hp = os.path.join(LBL, name + ".png.json")
    if os.path.isfile(hp):
        return yolo_from_norm(json.load(open(hp))["panels"]), True
    jp = os.path.join(JSN, name + ".json")
    if os.path.isfile(jp):
        return yolo_from_pixel(json.load(open(jp))), False
    return None, False


def fmt(boxes):
    lines = []
    for cx, cy, w, h in boxes:
        cx, cy = min(max(cx, 0), 1), min(max(cy, 0), 1)
        w, h = min(max(w, 0), 1), min(max(h, 0), 1)
        if w > 0 and h > 0:
            lines.append(f"0 {cx:.6f} {cy:.6f} {w:.6f} {h:.6f}")
    return lines


def main():
    pngs = sorted(f[:-4] for f in os.listdir(IMG) if f.endswith(".png"))
    hand = sorted(f[:-9] for f in os.listdir(LBL) if f.endswith(".png.json"))
    random.seed(SEED)
    random.shuffle(hand)
    val = set(hand[: round(len(hand) * VAL_FRAC)])  # Val NUR aus Hand-Labels

    # Split-Ordner KOMPLETT leeren — sonst akkumulieren Symlinks/Labels aus früheren Läufen mit
    # anderem Split → train/val-Leakage (verfälscht die Metrik).
    for split in ("train", "val"):
        for sub in ("images", "labels"):
            d = os.path.join(OUT, sub, split)
            if os.path.isdir(d):
                shutil.rmtree(d)
            os.makedirs(d, exist_ok=True)

    val_list, n_train = [], 0
    for name in pngs:
        boxes, _ = gt_for(name)
        if not boxes:
            continue
        lines = fmt(boxes)
        if not lines:
            continue
        split = "val" if name in val else "train"
        dst = os.path.join(OUT, "images", split, name + ".png")
        if not os.path.lexists(dst):
            os.symlink(os.path.join(IMG, name + ".png"), dst)
        open(os.path.join(OUT, "labels", split, name + ".txt"), "w").write("\n".join(lines) + "\n")
        if split == "val":
            val_list.append(name)
        else:
            n_train += 1

    open(os.path.join(os.path.dirname(OUT), "data.yaml"), "w").write(
        f"path: {OUT}\ntrain: images/train\nval: images/val\nnc: 1\nnames: [panel]\n")
    open(os.path.join(os.path.dirname(OUT), "val_images.txt"), "w").write("\n".join(sorted(val_list)) + "\n")
    print(f"konvertiert: train={n_train}, val={len(val_list)} (Val nur Hand-Labels)")


if __name__ == "__main__":
    main()
