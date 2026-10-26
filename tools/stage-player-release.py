#!/usr/bin/env python3
"""Prepare reproducible Git-staged release bits from an *already signed* player APK.

Run on a trusted signing machine. Neither a keystore nor signing password is
needed or accepted by this script. Does not publish or overwrite release data.
"""
import argparse
import gzip
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.7.6"
EXPECTED_CERT = "36efde603967282debd976200c92acf46b68144b2704d61930ebdd9c171302cd"
APK_NAME = "aFolderPlayer_0.7.6.apk"
PACKAGE = "dev.alex.folderplayer"
CODE = 32
PART_SIZE = 500_000


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def validate_apk(apk, apksigner, aapt):
    signed = subprocess.check_output(
        [apksigner, "verify", "--print-certs", str(apk)], text=True
    )
    certificates = re.findall(
        r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", signed
    )
    if len(certificates) != 1 or certificates[0].lower() != EXPECTED_CERT:
        raise ValueError("Signing certificate mismatch: would break installed upgrades")
    badging = subprocess.check_output([aapt, "dump", "badging", str(apk)], text=True)
    match = re.search(
        r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
        badging, re.MULTILINE
    )
    if not match or match.groups() != (PACKAGE, str(CODE), VERSION):
        raise ValueError("APK identity/version mismatch: expected " + PACKAGE + " " + VERSION)
    return apk.read_bytes()


def prepare(apk, apksigner, aapt):
    apk = Path(apk).resolve()
    if not apk.is_file():
        raise ValueError("Signed release APK not found")
    source_sha = subprocess.check_output(
        ["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True
    ).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("Source Git commit unavailable")
    if subprocess.call(["git", "-C", str(ROOT), "diff", "--quiet", "HEAD", "--", "app", "translator"]):
        raise ValueError("Uncommitted application sources: build cannot be tied to HEAD")
    apk_bytes = validate_apk(apk, apksigner, aapt)
    previous = ROOT / "deployment/releases/0.7.5"
    old_manifest = json.loads((previous / "manifest.json").read_text(encoding="utf-8"))
    if old_manifest["certificate_sha256"] != EXPECTED_CERT:
        raise ValueError("Trusted certificate changed in previous release")
    catalog = (previous / "catalog.json").read_bytes()
    new_folder = ROOT / "deployment/releases" / VERSION
    if new_folder.exists():
        raise ValueError("Staged release already exists: refusing to replace signed bytes")
    new_folder.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="afp-stage-", dir=new_folder.parent) as scratch:
        temp = Path(scratch)
        compressed = gzip.compress(apk_bytes, compresslevel=9, mtime=0)
        names = []
        for index, offset in enumerate(range(0, len(compressed), PART_SIZE)):
            filename = APK_NAME + ".gz.part" + f"{index:02d}"
            (temp / filename).write_bytes(compressed[offset:offset + PART_SIZE])
            names.append("deployment/releases/" + VERSION + "/" + filename)
        if not names:
            raise ValueError("Empty APK")
        app_asset = {
            "name": APK_NAME,
            "size": len(apk_bytes),
            "sha256": sha256(apk_bytes),
            "compression": "gzip",
            "compressed_sha256": sha256(compressed),
            "parts": names,
        }
        engine = old_manifest["apks"][1]
        models = old_manifest["models"]
        manifest = {
            "source_commit": source_sha, "certificate_sha256": EXPECTED_CERT,
            "apks": [app_asset, engine], "models": models
        }
        (temp / "catalog.json").write_bytes(catalog)
        (temp / "manifest.json").write_text(
            json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )
        sums = [(app_asset["sha256"], APK_NAME), (engine["sha256"], engine["name"])]
        sums += [(model["sha256"], model["name"]) for model in models]
        sums += [(sha256(catalog), "catalog.json")]
        (temp / "SHA256SUMS").write_text(
            "".join(digest + "  " + name + "\n" for digest, name in sums), encoding="ascii"
        )
        (temp / "release-notes.md").write_text(
            "## Что нового в 0.7.6\n\n"
            "- Прогресс скачивания расширений: проценты, объём, проверка и установка.\n"
            "- Выбор языка для конкретного текста песни без изменения общей настройки.\n"
            "- Сообщение о нужном языковом пакете и улучшенное определение немецкого.\n\n"
            "AFP Translate 0.3.1 и установленные языковые пакеты обновлять не требуется.\n",
            encoding="utf-8",
        )
        # Validate the gzip round trip and all byte-for-byte content before committing.
        if gzip.decompress(b"".join((temp / Path(name).name).read_bytes() for name in names)) != apk_bytes:
            raise ValueError("Staging integrity mismatch")
        temp.rename(new_folder)
    print("Prepared", VERSION, "from", source_sha)
    print("Player SHA-256:", sha256(apk_bytes))
    print("Staged parts:", len(names))
    print("NEXT: python3 tools/release-checkpoint.py prepare " + VERSION)
    return new_folder


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, help="Previously signed APK from trusted build")
    parser.add_argument("--apksigner", required=True, help="Android build-tools apksigner path")
    parser.add_argument("--aapt", required=True, help="Android build-tools aapt path")
    args = parser.parse_args()
    prepare(args.apk, args.apksigner, args.aapt)
