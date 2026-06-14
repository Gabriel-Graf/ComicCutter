#!/usr/bin/env python3
"""NCNN fp32 inference via the ncnn python binding: latency (1 and 4 threads)
plus an accuracy sanity-check using the same metric/decode as the ONNX path.
This is the closest in-process proxy for the on-device NCNN runtime.
"""
import time

import cv2
import ncnn
import numpy as np

import spike_lib as L

MODEL = "runs/panel/weights/best_ncnn_model"
WARMUP = 3


def make_net(threads):
    net = ncnn.Net()
    net.opt.use_vulkan_compute = False
    net.opt.num_threads = threads
    net.load_param(f"{MODEL}/model.ncnn.param")
    net.load_model(f"{MODEL}/model.ncnn.bin")
    return net


def infer(net, path):
    img = cv2.imread(path)
    lb, r, padw, padh = L.letterbox(img)
    rgb = cv2.cvtColor(lb, cv2.COLOR_BGR2RGB)
    mat = ncnn.Mat.from_pixels(rgb, ncnn.Mat.PixelType.PIXEL_RGB, L.IMGSZ, L.IMGSZ)
    mat.substract_mean_normalize([], [1 / 255.0, 1 / 255.0, 1 / 255.0])
    ex = net.create_extractor()
    ex.input("in0", mat)
    _, out = ex.extract("out0")
    arr = np.array(out)  # (5, 21504)
    return L.decode_yolo(arr[None], r, padw, padh, conf=0.25), (r, padw, padh)


def accuracy():
    net = make_net(4)

    def det(path, w, h):
        boxes, _ = infer(net, path)
        return boxes
    d = L.aggregate(det)
    print(f"ncnn-fp32 (sanity)   precision={d['precision']:.3f} recall={d['recall']:.3f} "
          f"meanIoU={d['meanIoU']:.3f} perfect={d['perfect']}/{d['n']}")


def latency():
    names = L.val_names()[:24]
    paths = [f"{L.IMG}/{n}.png" for n in names]
    print(f"=== NCNN fp32 latency, imgsz={L.IMGSZ}, n={len(paths)} (x86 proxy) ===")
    print(f"{'variant':22} {'threads':>7} {'mean_ms':>9} {'median_ms':>10}")
    for th in (1, 4):
        net = make_net(th)
        for _ in range(WARMUP):
            infer(net, paths[0])
        times = []
        for p in paths:
            t0 = time.perf_counter()
            infer(net, p)
            times.append((time.perf_counter() - t0) * 1000.0)
        a = np.array(times)
        print(f"{'ncnn-fp32':22} {th:>7} {a.mean():>9.1f} {np.median(a):>10.1f}")


if __name__ == "__main__":
    accuracy()
    latency()
