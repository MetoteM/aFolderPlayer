#!/usr/bin/env python3
"""Build deterministic direct Spanish→Russian ZIP from the audited registry."""
import gzip
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REVISION = "268ccd60bde3cabde17068d482ca6930512f1a85"

def build(source, output):
    registry = (ROOT / "translator/src/main/java/dev/alex/folderplayer/ModelPackages.kt").read_text()
    expected = dict(re.findall(r'"(es-ru/[^\"]+)"\s+to\s+"([0-9a-f]{64})"', registry))
    if set(expected) != {"es-ru/encoder.onnx", "es-ru/decoder.onnx", "es-ru/tokenizer.json"}:
        raise ValueError("Incomplete audited Spanish registry")
    data = {"es-ru/" + name: (source / name).read_bytes() for name in ("encoder.onnx", "decoder.onnx")}
    data["es-ru/tokenizer.json"] = gzip.decompress((ROOT / "deployment/spanish-v1/tokenizer.json.gz").read_bytes())
    for name, digest in expected.items():
        if hashlib.sha256(data[name]).hexdigest() != digest:
            raise ValueError("Wrong model bytes: " + name)
    data["LICENSE.txt"] = (ROOT / "deployment/spanish-v1/LICENSE.txt").read_bytes()
    data["NOTICE.txt"] = (ROOT / "deployment/spanish-v1/NOTICE.txt").read_bytes()
    data["manifest.json"] = json.dumps({
        "model": "opus-es-ru-v1", "source": "es", "target": "ru",
        "revision": REVISION, "files": expected,
    }, sort_keys=True, separators=(",", ":")).encode()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, value in sorted(data.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, value)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    entry = json.loads((ROOT / "deployment/spanish-v1/catalog-entry.json").read_text())
    if entry["model"] != "opus-es-ru-v1" or entry["minimumEngine"] != 6 or entry["size"] != output.stat().st_size or entry["sha256"] != digest:
        raise ValueError("Spanish catalogue entry differs from reproducible package")
    print(digest)

if __name__ == "__main__":
    build(Path(sys.argv[1]), Path(sys.argv[2]))
