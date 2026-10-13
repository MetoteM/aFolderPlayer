#!/usr/bin/env python3
"""Download pinned direct French→Russian ONNX weights and verify every byte."""
import concurrent.futures
import hashlib
import sys
import urllib.request
from pathlib import Path

REVISION = "c2ec732b045ae9f0e1ae1ea9267840145a8c03fe"
FILES = {
    "encoder": (52_580_253, "058326a2132a61b250d117ca78b4b3b6f53e81479c3d3b6bebf3c58850618743"),
    "decoder": (59_520_117, "97ad9b2f99421bd72cc026fcdd0ed579415408fcca910a7de8f9e3a06b7088a4"),
}

def download(root, name):
    size, expected = FILES[name]
    target = root / (name + ".onnx")
    if target.is_file() and target.stat().st_size == size and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        return
    part = target.with_suffix(".onnx.part")
    url = "https://huggingface.co/Xenova/opus-mt-fr-ru/resolve/" + REVISION + "/onnx/" + name + "_model_quantized.onnx"
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
                        raise ValueError("French model exceeds pinned size")
                    digest.update(data)
                    output.write(data)
            if count != size or digest.hexdigest() != expected:
                raise ValueError("French model integrity mismatch: " + name)
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
