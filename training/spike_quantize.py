#!/usr/bin/env python3
"""INT8 static quantization of best.onnx via onnxruntime, calibrated on training
images. Also emits an fp16 ONNX for size reference.
"""
import os

import numpy as np
import onnx
from onnxconverter_common import float16
from onnxruntime.quantization import (CalibrationDataReader, QuantFormat,
                                      QuantType, quantize_static)
from onnxruntime.quantization.shape_inference import quant_pre_process

import spike_lib as L

W = "runs/panel/weights"
FP32 = f"{W}/best.onnx"
PREP = f"{W}/best.prep.onnx"
INT8 = f"{W}/best.int8.onnx"
FP16 = f"{W}/best.fp16.onnx"
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

    # The Detect head (model.23: tiny dynamic range pre-sigmoid + DFL) collapses
    # to zero scores under INT8. Standard YOLO practice: keep the head in fp32.
    exclude = [n.name for n in onnx.load(PREP).graph.node
               if "/model.23/" in n.name]
    print(f"Excluding {len(exclude)} head nodes (whole model.23) from INT8")

    quantize_static(
        PREP, INT8, Reader(paths, iname),
        quant_format=QuantFormat.QDQ,
        per_channel=True,
        weight_type=QuantType.QInt8,
        activation_type=QuantType.QInt8,
        nodes_to_exclude=exclude,
    )
    print("INT8 written:", INT8)

    m = onnx.load(FP32)
    onnx.save(float16.convert_float_to_float16(m, keep_io_types=True), FP16)
    print("FP16 written:", FP16)

    for f in (FP32, FP16, INT8):
        mb = os.path.getsize(f) / 1048576
        print(f"  {f:35} {mb:7.2f} MB")


if __name__ == "__main__":
    main()
