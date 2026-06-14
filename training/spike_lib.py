#!/usr/bin/env python3
"""Shared helpers for the on-device de-risking spike.

Reuses the SAME greedy-IoU>=0.5 metric as eval_compare.py so that .pt / ONNX-fp32
/ INT8 accuracy numbers are directly comparable. Also provides a self-contained
ONNX runner (letterbox + sigmoid-free YOLO11 decode + NMS) that matches the
ultralytics preprocessing/postprocessing, so a quantized ONNX (which the
ultralytics wrapper cannot run through its NMS path) is scored identically.
"""
import json
import os

import cv2
import numpy as np
from PIL import Image

MAIN = "/home/gabriel/Documents/Projekte/komga-reader-guided-comic"
IMG = os.path.join(MAIN, "dataset", "img")
LBL = os.path.join(IMG, "labels")
HERE = os.path.dirname(os.path.abspath(__file__))
IMGSZ = 1024


# --- metric (verbatim from eval_compare.py) ---------------------------------
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
    return m, len(det) - m, len(gt) - m, s


def val_names():
    return [n.strip() for n in open(os.path.join(HERE, "val_images.txt")) if n.strip()]


def gt_boxes(name, w, h):
    data = json.load(open(os.path.join(LBL, name + ".png.json")))
    return [(p["left"] * w, p["top"] * h, p["width"] * w, p["height"] * h)
            for p in data["panels"]]


def aggregate(detector, names=None):
    """detector(path, w, h) -> list of (x, y, w, h) in pixels. Returns metrics dict."""
    names = names or val_names()
    m_t = ex_t = mi_t = 0; iou_t = 0.0; perf = 0
    for name in names:
        path = os.path.join(IMG, name + ".png")
        w, h = Image.open(path).size
        gt = gt_boxes(name, w, h)
        det = detector(path, w, h)
        m, ex, mi, s = score(gt, det)
        m_t += m; ex_t += ex; mi_t += mi; iou_t += s
        if mi == 0 and ex == 0:
            perf += 1
    prec = m_t / (m_t + ex_t) if (m_t + ex_t) else 0.0
    rec = m_t / (m_t + mi_t) if (m_t + mi_t) else 0.0
    miou = iou_t / m_t if m_t else 0.0
    return {"precision": prec, "recall": rec, "meanIoU": miou,
            "perfect": perf, "n": len(names),
            "matched": m_t, "extra": ex_t, "missed": mi_t}


# --- self-contained ONNX runner (matches ultralytics pre/post) ---------------
def letterbox(img, new=IMGSZ, color=114):
    h, w = img.shape[:2]
    r = min(new / h, new / w)
    nh, nw = round(h * r), round(w * r)
    resized = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_LINEAR)
    canvas = np.full((new, new, 3), color, dtype=np.uint8)
    top = (new - nh) // 2
    left = (new - nw) // 2
    canvas[top:top + nh, left:left + nw] = resized
    return canvas, r, left, top


def _nms(boxes, scores, iou_thr=0.7):
    """boxes: Nx4 xyxy. Returns kept indices."""
    if len(boxes) == 0:
        return []
    x1, y1, x2, y2 = boxes[:, 0], boxes[:, 1], boxes[:, 2], boxes[:, 3]
    areas = (x2 - x1) * (y2 - y1)
    order = scores.argsort()[::-1]
    keep = []
    while order.size > 0:
        i = order[0]; keep.append(i)
        xx1 = np.maximum(x1[i], x1[order[1:]])
        yy1 = np.maximum(y1[i], y1[order[1:]])
        xx2 = np.minimum(x2[i], x2[order[1:]])
        yy2 = np.minimum(y2[i], y2[order[1:]])
        w = np.maximum(0.0, xx2 - xx1); h = np.maximum(0.0, yy2 - yy1)
        inter = w * h
        ovr = inter / (areas[i] + areas[order[1:]] - inter)
        order = order[1:][ovr <= iou_thr]
    return keep


def decode_yolo(out, r, padw, padh, conf=0.25, iou_thr=0.7):
    """out: (1,5,8400) YOLO11 single-class head. Returns list of (x,y,w,h) pixels."""
    pred = out[0]  # (5, N)
    pred = pred.T  # (N, 5): cx,cy,w,h,score
    scores = pred[:, 4]
    mask = scores >= conf
    pred = pred[mask]; scores = scores[mask]
    if len(pred) == 0:
        return []
    cx, cy, bw, bh = pred[:, 0], pred[:, 1], pred[:, 2], pred[:, 3]
    x1 = cx - bw / 2; y1 = cy - bh / 2; x2 = cx + bw / 2; y2 = cy + bh / 2
    boxes = np.stack([x1, y1, x2, y2], axis=1)
    keep = _nms(boxes, scores, iou_thr)
    res = []
    for i in keep:
        bx1 = (x1[i] - padw) / r; by1 = (y1[i] - padh) / r
        bx2 = (x2[i] - padw) / r; by2 = (y2[i] - padh) / r
        res.append((float(bx1), float(by1), float(bx2 - bx1), float(by2 - by1)))
    return res


def preprocess(path):
    img = cv2.imread(path)  # BGR
    lb, r, padw, padh = letterbox(img)
    rgb = cv2.cvtColor(lb, cv2.COLOR_BGR2RGB)
    x = rgb.astype(np.float32) / 255.0
    x = x.transpose(2, 0, 1)[None]  # 1,3,H,W
    return np.ascontiguousarray(x), r, padw, padh
