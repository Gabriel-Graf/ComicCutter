#!/usr/bin/env python3
"""Finetunt yolo11n auf den Panel-Labels. Comics sind ~1000x1500 → größere imgsz als Default.

Run-Name versioniert via PANEL_RUN (Default "panel"), damit v2 die v1-Gewichte (runs/panel) nicht
überschreibt: `PANEL_RUN=panel_v2 .venv/bin/python yolo/train.py`.
"""
import os
from ultralytics import YOLO

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    run = os.environ.get("PANEL_RUN", "panel")
    model = YOLO("yolo11n.pt")  # pretrained, lädt automatisch
    model.train(
        data=os.path.join(HERE, "data.yaml"),
        epochs=150,
        imgsz=1024,
        batch=16,
        patience=40,
        seed=42,
        project=os.path.join(HERE, "runs"),
        name=run,
        exist_ok=False,
        verbose=True,
    )


if __name__ == "__main__":
    main()
