#!/usr/bin/env python3
"""Checkpoint pre-signed release uploads without storing signing credentials.

prepare VERSION: inventory each prepared release file and save upload-plan.json.
status VERSION: verify files *linked to the staging branch*, not dangling blobs.
Missing or mismatched files get an UPLOAD marker and nonzero exit status.
"""
import argparse
import hashlib
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = "MetoteM/aFolderPlayer"


def blob_sha(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def prepare(version):
    folder = ROOT / "deployment" / "releases" / version
    manifest_file = folder / "manifest.json"
    if not manifest_file.is_file():
        raise ValueError("Release manifest missing: signed and verified APK required")
    manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    commit = manifest["source_commit"]
    if len(commit) != 40 or any(c not in "0123456789abcdef" for c in commit):
        raise ValueError("Invalid source commit")
    rows = []
    for file in sorted(folder.iterdir()):
        if file.is_file() and file.name not in ("upload-plan.json", "upload-plan.json.tmp"):
            data = file.read_bytes()
            rows.append({
                "path": file.relative_to(ROOT).as_posix(),
                "size": len(data),
                "git_blob": blob_sha(data),
                "sha256": hashlib.sha256(data).hexdigest(),
            })
    if not rows:
        raise ValueError("No prepared release files")
    plan = {
        "version": version, "source_commit": commit,
        "staging_branch": "release/" + version + "-staging", "files": rows,
    }
    temporary = folder / "upload-plan.json.tmp"
    temporary.write_text(json.dumps(plan, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    temporary.replace(folder / "upload-plan.json")
    print("Saved upload plan:", len(rows), "files")


def remote_blob(path, branch):
    url = ("https://api.github.com/repos/" + REPOSITORY + "/contents/" +
           urllib.parse.quote(path, safe="/") + "?ref=" + urllib.parse.quote(branch, safe=""))
    request = urllib.request.Request(url, headers={
        "User-Agent": "AFP-release-checkpoint",
        "Accept": "application/vnd.github+json",
    })
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            item = json.load(response)
            if item.get("type") != "file":
                return None
            return item.get("sha"), item.get("size")
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def status(version):
    plan = json.loads((ROOT / "deployment" / "releases" / version /
                       "upload-plan.json").read_text(encoding="utf-8"))
    if plan["version"] != version or plan["staging_branch"] != "release/" + version + "-staging":
        raise ValueError("Plan identity mismatch")
    missing = 0
    for row in plan["files"]:
        present = remote_blob(row["path"], plan["staging_branch"])
        valid = present == (row["git_blob"], row["size"])
        print(("present" if valid else "UPLOAD"), row["path"])
        if not valid:
            missing += 1
    if missing:
        print("Incomplete staging:", missing, "files missing or mismatched", file=sys.stderr)
    return missing


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["prepare", "status"])
    parser.add_argument("version")
    args = parser.parse_args()
    if not args.version or any(c not in "0123456789." for c in args.version):
        parser.error("Expected numeric version")
    if args.command == "prepare":
        prepare(args.version)
    else:
        sys.exit(1 if status(args.version) else 0)
