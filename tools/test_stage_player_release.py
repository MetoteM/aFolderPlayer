#!/usr/bin/env python3
"""Offline checks for the signed APK staging helper."""
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("stage-player-release.py")
spec = importlib.util.spec_from_file_location("stage_player_release", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class StagingTest(unittest.TestCase):
    def test_rejects_wrong_cert_and_wrong_version(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            apk.write_bytes(b"apk bytes")
            badging = "package: name='dev.alex.folderplayer' versionCode='32' versionName='0.7.6'\n"
            good_cert = "Signer #1 certificate SHA-256 digest: " + module.EXPECTED_CERT + "\n"
            with patch.object(subprocess, "check_output", side_effect=["Signer #1 certificate SHA-256 digest: " + "0"*64, badging]):
                with self.assertRaisesRegex(ValueError, "Signing certificate mismatch"):
                    module.validate_apk(apk, "apksigner", "aapt")
            with patch.object(subprocess, "check_output", side_effect=[good_cert, badging]):
                self.assertEqual(b"apk bytes", module.validate_apk(apk, "apksigner", "aapt"))
            with patch.object(subprocess, "check_output", side_effect=[good_cert, badging.replace("0.7.6", "0.7.5")]):
                with self.assertRaisesRegex(ValueError, "identity/version"):
                    module.validate_apk(apk, "apksigner", "aapt")

    def test_compressed_apk_is_deterministic(self):
        data = b"not a signing key" * 150
        self.assertEqual(module.gzip.compress(data, mtime=0), module.gzip.compress(data, mtime=0))


if __name__ == "__main__":
    unittest.main()
