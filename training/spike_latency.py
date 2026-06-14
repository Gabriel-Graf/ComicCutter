#!/usr/bin/env python3
"""CPU inference latency per page (x86 proxy). Measures pure model inference
(session.run) so fp32 vs INT8 is compared on equal footing; preprocessing is
done once per image and excluded. Reports single-thread and 4-thread.
"""
import time

import numpy as np
import onnxruntime as ort

import spike_lib as L

W = "runs/panel/weights"
WARMUP = 3


def bench(onnx_path, threads, inputs):
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    so.inter_op_num_threads = 1
    so.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    sess = ort.InferenceSession(onnx_path, so, providers=["CPUExecutionProvider"])
    iname = sess.get_inputs()[0].name
    for _ in range(WARMUP):
        sess.run(None, {iname: inputs[0]})
    times = []
    for x in inputs:
        t0 = time.perf_counter()
        sess.run(None, {iname: x})
        times.append((time.perf_counter() - t0) * 1000.0)
    a = np.array(times)
    return a.mean(), a.std(), np.median(a)


def main():
    names = L.val_names()[:24]
    inputs = [L.preprocess(f"{L.IMG}/{n}.png")[0] for n in names]
    print(f"=== CPU latency, imgsz={L.IMGSZ}, n={len(inputs)} pages (x86 proxy) ===")
    print(f"{'variant':22} {'threads':>7} {'mean_ms':>9} {'median_ms':>10} {'std_ms':>8}")
    for tag, path in (("onnx-fp32", f"{W}/best.onnx"), ("onnx-int8", f"{W}/best.int8.onnx")):
        for th in (1, 4):
            mean, std, med = bench(path, th, inputs)
            print(f"{tag:22} {th:>7} {mean:>9.1f} {med:>10.1f} {std:>8.1f}")


if __name__ == "__main__":
    main()
