#!/usr/bin/env python3
"""Wrapper: Ruft adapter.py auf und gibt Detektionen als JSON-Array nach stdout aus.

Aufruf:
    python run_adapter.py <model_dir> <image_path>

Ausgabe (stdout, genau eine Zeile):
    [[x, y, w, h, score], ...]   -- Pixel-Koordinaten wie von adapter.predict()

Interpreter-Wahl (Reihenfolge):
  1. Env PARITY_PYTHON
  2. /home/gabriel/Documents/Projekte/mllabeltool/.venv/bin/python  (Fallback)
  3. python3

Dieser Wrapper wird vom JUnit-Test OnnxModelRunnerParityTest via ProcessBuilder aufgerufen.
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

    # adapter.py im selben Verzeichnis wie model_dir suchen (mllabeltool/models/yolo_v3/)
    adapter_path = os.path.join(model_dir, "adapter.py")
    if not os.path.isfile(adapter_path):
        print(f"ERROR: adapter.py nicht gefunden: {adapter_path}", file=sys.stderr)
        sys.exit(2)

    # adapter.py als Modul laden
    import importlib.util
    spec = importlib.util.spec_from_file_location("adapter", adapter_path)
    adapter = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(adapter)

    handle = adapter.load(model_dir)
    detections = adapter.predict(handle, image_path)

    # Format: [[x, y, w, h, score], ...]
    result = [[d["box"][0], d["box"][1], d["box"][2], d["box"][3], d["score"]] for d in detections]
    print(json.dumps(result))

if __name__ == "__main__":
    main()
