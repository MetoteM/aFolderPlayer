#!/usr/bin/env python3
"""Download pinned Italian→English ONNX weights and verify every byte."""
import concurrent.futures
import hashlib
import sys
import urllib.request
from pathlib import Path

REVISION = "fd0b89b9c052adc1f2f64152f555aa17353728be"
FILES = {
    "encoder": (60_773_278, "a025d2d85c90da23880116b67175e43e71921d8d2bf446b9f1eacdc86015aa4c"),
    "decoder": (67_777_150, "203bac7b98e438e65e1ddfcbec254ffb6811e6a8f4f71e93517ca9550323bf6e"),
}

def download(root, name):
    size, expected = FILES[name]
    target = root / (name + ".onnx")
    if target.is_file() and target.stat().st_size == size and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        return
    part = target.with_suffix(".onnx.part")
    url = "https://huggingface.co/Xenova/opus-mt-it-en/resolve/" + REVISION + "/onnx/" + name + "_model_quantized.onnx"
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
                        raise ValueError("Italian model exceeds pinned size")
                    digest.update(data)
                    output.write(data)
            if count != size or digest.hexdigest() != expected:
                raise ValueError("Italian model integrity mismatch: " + name)
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
