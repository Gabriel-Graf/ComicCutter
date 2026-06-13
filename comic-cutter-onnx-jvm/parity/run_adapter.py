#!/usr/bin/env python3
"""Wrapper: calls adapter.py and prints detections as a JSON array to stdout.

Usage:
    python run_adapter.py <model_dir> <image_path>

Output (stdout, exactly one line):
    [[x, y, w, h, score], ...]   -- pixel coordinates as returned by adapter.predict()

Interpreter selection (in order):
  1. Env PARITY_PYTHON
  2. /home/gabriel/Documents/Projekte/mllabeltool/.venv/bin/python  (fallback)
  3. python3

This wrapper is invoked by the JUnit test OnnxModelRunnerParityTest via ProcessBuilder.
"""

import json
import sys
import os

def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <model_dir> <image_path>", file=sys.stderr)
        sys.exit(1)

    model_dir = sys.argv[1]
    image_path = sys.argv[2]

    # look for adapter.py in the same directory as model_dir (mllabeltool/models/yolo_v3/)
    adapter_path = os.path.join(model_dir, "adapter.py")
    if not os.path.isfile(adapter_path):
        print(f"ERROR: adapter.py not found: {adapter_path}", file=sys.stderr)
        sys.exit(2)

    # load adapter.py as a module
    import importlib.util
    spec = importlib.util.spec_from_file_location("adapter", adapter_path)
    if spec is None or spec.loader is None:
        print(f"adapter.py not loadable: {adapter_path}", file=sys.stderr)
        sys.exit(2)
    adapter = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(adapter)

    handle = adapter.load(model_dir)
    detections = adapter.predict(handle, image_path)

    # Format: [[x, y, w, h, score], ...]
    result = [[d["box"][0], d["box"][1], d["box"][2], d["box"][3], d["score"]] for d in detections]
    print(json.dumps(result))

if __name__ == "__main__":
    main()
