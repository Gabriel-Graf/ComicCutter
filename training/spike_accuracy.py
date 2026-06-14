#!/usr/bin/env python3
"""Accuracy retention across variants, same greedy-IoU>=0.5 metric.

Variants:
  (i)   best.pt            -> ultralytics (fp32 reference)
  (ii)  ultra-onnx-fp32    -> ultralytics YOLO(onnx) wrapper
  (iii) custom-onnx-fp32   -> self-contained ONNX runner (validates the runner)
  (iv)  custom-onnx-int8   -> onnxruntime static-quantized model, same runner
"""
import sys

import onnxruntime as ort
from ultralytics import YOLO

import spike_lib as L

CONF = 0.25
W = "runs/panel/weights"


def ultra_detector(model):
    def det(path, w, h):
        r = model.predict(path, conf=CONF, imgsz=L.IMGSZ, verbose=False)[0]
        return [(float(x1), float(y1), float(x2 - x1), float(y2 - y1))
                for x1, y1, x2, y2 in r.boxes.xyxy.cpu().numpy()]
    return det


def onnx_detector(onnx_path):
    sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
    iname = sess.get_inputs()[0].name

    def det(path, w, h):
        x, r, padw, padh = L.preprocess(path)
        out = sess.run(None, {iname: x})[0]
        return L.decode_yolo(out, r, padw, padh, conf=CONF)
    return det


def fmt(tag, d):
    print(f"{tag:20} precision={d['precision']:.3f} recall={d['recall']:.3f} "
          f"meanIoU={d['meanIoU']:.3f} perfect={d['perfect']}/{d['n']} "
          f"(matched={d['matched']} extra={d['extra']} missed={d['missed']})")


def main():
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    print(f"=== Accuracy (IoU>=0.5 greedy, conf={CONF}, imgsz={L.IMGSZ}) ===")
    if which in ("all", "pt"):
        fmt("best.pt (fp32 ref)", L.aggregate(ultra_detector(YOLO(f"{W}/best.pt"))))
    if which in ("all", "uonnx"):
        fmt("ultra-onnx-fp32", L.aggregate(ultra_detector(YOLO(f"{W}/best.onnx"))))
    if which in ("all", "connx"):
        fmt("custom-onnx-fp32", L.aggregate(onnx_detector(f"{W}/best.onnx")))
    if which in ("all", "int8"):
        fmt("custom-onnx-int8", L.aggregate(onnx_detector(f"{W}/best.int8.onnx")))


if __name__ == "__main__":
    main()
