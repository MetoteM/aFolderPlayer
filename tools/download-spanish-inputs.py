#!/usr/bin/env python3
"""Download pinned direct Spanish→Russian ONNX weights and verify every byte."""
import concurrent.futures
import hashlib
import sys
import urllib.request
from pathlib import Path

REVISION = "268ccd60bde3cabde17068d482ca6930512f1a85"
FILES = {
    "encoder": (52_095_390, "04702c95f2380c074b9458735fffe44b095ef1a7632f0e13a7f7c7440d995241"),
    "decoder": (59_031_466, "648cc96da4127a7ad9623e0d7f6da5c103a4438c64c697cb4fe9ff76148f7819"),
}

def download(root, name):
    size, expected = FILES[name]
    target = root / (name + ".onnx")
    if target.is_file() and target.stat().st_size == size and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        return
    part = target.with_suffix(".onnx.part")
    url = "https://huggingface.co/Xenova/opus-mt-es-ru/resolve/" + REVISION + "/onnx/" + name + "_model_quantized.onnx"
    for attempt in range(3):
        try:
            digest = hashlib.sha256()
            count = 0
            with urllib.request.urlopen(url, timeout=45) as response, part.open("wb") as output:
                while True:
                    data = response.read(1024 * 1024)
                    if not data:
                        break
                    count += len(data)
                    if count > size:
                        raise ValueError("Spanish model exceeds pinned size")
                    digest.update(data)
                    output.write(data)
            if count != size or digest.hexdigest() != expected:
                raise ValueError("Spanish model integrity mismatch: " + name)
            part.replace(target)
            return
        except Exception:
            part.unlink(missing_ok=True)
            if attempt == 2:
                raise

if __name__ == "__main__":
    root = Path(sys.argv[1])
    root.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(lambda name: download(root, name), FILES))
