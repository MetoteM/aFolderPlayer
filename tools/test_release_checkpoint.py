#!/usr/bin/env python3
"""Pure/offline regression tests for interrupted release staging."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("release-checkpoint.py")
spec = importlib.util.spec_from_file_location("release_checkpoint", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CheckpointTest(unittest.TestCase):
    def test_blob_hash(self):
        self.assertEqual(module.blob_sha(b"test"),
                         "30d74d258442c7c65512eafab474568dd706c430")

    def test_prepare_and_resume(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            folder = base / "deployment/releases/0.7.6"
            folder.mkdir(parents=True)
            (folder / "manifest.json").write_text(json.dumps({"source_commit": "a" * 40}))
            (folder / "player.apk.gz.part00").write_bytes(b"abc")
            with patch.object(module, "ROOT", base):
                module.prepare("0.7.6")
                plan = json.loads((folder / "upload-plan.json").read_text())
                self.assertEqual(len(plan["files"]), 2)
                self.assertEqual(plan["source_commit"], "a" * 40)
                with patch.object(module, "remote_blob", return_value=None):
                    self.assertEqual(module.status("0.7.6"), 2)
                with patch.object(module, "remote_blob", side_effect=lambda path, branch: next(
                    (row["git_blob"], row["size"]) for row in plan["files"] if row["path"] == path
                )):
                    self.assertEqual(module.status("0.7.6"), 0)

    def test_rejects_unsigned_missing_manifest(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(module, "ROOT", Path(directory)):
            with self.assertRaises(ValueError):
                module.prepare("0.7.6")


if __name__ == "__main__":
    unittest.main()
