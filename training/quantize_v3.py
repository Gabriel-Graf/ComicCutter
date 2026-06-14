#!/usr/bin/env python3
"""INT8 static quantization of panel_v3 best.onnx — same recipe as spike_quantize.py
(QDQ, per-channel, INT8 weights+activations, Detect head model.23 kept fp32),
calibrated on training images."""
import os

import onnx
from onnxruntime.quantization import (CalibrationDataReader, QuantFormat,
                                      QuantType, quantize_static)
from onnxruntime.quantization.shape_inference import quant_pre_process

import spike_lib as L

W = "runs/panel_v3/weights"
FP32 = f"{W}/best.onnx"
PREP = f"{W}/best.prep.onnx"
INT8 = f"{W}/best.int8.onnx"
TRAIN_DIR = "dataset/images/train"
N_CALIB = 80


class Reader(CalibrationDataReader):
    def __init__(self, paths, iname):
        self.iname = iname
        self.data = iter(paths)

    def get_next(self):
        p = next(self.data, None)
        if p is None:
            return None
        x, _, _, _ = L.preprocess(p)
        return {self.iname: x}


def main():
    paths = sorted(os.path.join(TRAIN_DIR, f) for f in os.listdir(TRAIN_DIR))[:N_CALIB]
    print(f"Calibration images: {len(paths)}")
    model = onnx.load(FP32)
    iname = model.graph.input[0].name
    quant_pre_process(FP32, PREP, skip_symbolic_shape=False)
    exclude = [n.name for n in onnx.load(PREP).graph.node if "/model.23/" in n.name]
    print(f"Excluding {len(exclude)} head nodes (whole model.23) from INT8")
    quantize_static(
        PREP, INT8, Reader(paths, iname),
        quant_format=QuantFormat.QDQ,
        per_channel=True,
        weight_type=QuantType.QInt8,
        activation_type=QuantType.QInt8,
        nodes_to_exclude=exclude,
    )
    for f in (FP32, INT8):
        print(f"  {f:40} {os.path.getsize(f)/1048576:7.2f} MB")
    print("INT8 written:", INT8)


if __name__ == "__main__":
    main()
