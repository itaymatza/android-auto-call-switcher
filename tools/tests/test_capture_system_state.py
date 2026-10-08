import importlib.util
import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "capture_system_state.py"
SPEC = importlib.util.spec_from_file_location("system_capture", SCRIPT)
capture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(capture)


class SystemCaptureTest(unittest.TestCase):
    def test_stuck_process_and_large_output_are_bounded(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            result = capture.capture([sys.executable], ["-c", "import time; time.sleep(5)"],
                                     root / "timeout.txt", 0.1)
            self.assertTrue(result["timed_out"])
            self.assertLess(result["duration_ms"], 2000)
            result = capture.capture([sys.executable],
                                     ["-c", "import sys; sys.stdout.write('x' * 3000000)"],
                                     root / "large.txt", 2)
            self.assertTrue(result["truncated"])
            self.assertEqual(capture.MAX_BYTES, (root / "large.txt").stat().st_size)
            self.assertEqual(0o600, stat.S_IMODE((root / "large.txt").stat().st_mode))

    def test_partial_system_access_is_reported_and_no_evidence_is_overwritten(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            adb = root / "adb"
            adb.write_text("#!/usr/bin/env python3\nimport sys\n"
                           "if sys.argv[-1] == 'get-state': print('device')\n"
                           "elif sys.argv[-1] == 'media.audio_policy':\n"
                           " print('Permission Denial'); sys.exit(1)\n"
                           "elif sys.argv[-1] == 'media.audio_flinger': print('Permission Denial')\n"
                           "else: print('synthetic system state')\n")
            adb.chmod(0o700)
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"])
            output = root / "evidence"
            command = [sys.executable, str(SCRIPT), "--serial", "TEST", "--output", str(output)]
            result = subprocess.run(command, env=env, capture_output=True, text=True)
            self.assertEqual(1, result.returncode, result.stderr)
            report = json.loads((output / "capture.json").read_text())
            self.assertFalse(report["redacted"])
            self.assertEqual(len(capture.SERVICES) + 2 + len(capture.PROPERTIES), len(report["captures"]))
            self.assertEqual(1, sum(item["exit_code"] != 0 for item in report["captures"]))
            self.assertEqual(2, sum(item["status"] == "DENIED" for item in report["captures"]))
            self.assertEqual(0o700, stat.S_IMODE(output.stat().st_mode))
            original = (output / "capture.json").read_bytes()
            result = subprocess.run(command, env=env, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(original, (output / "capture.json").read_bytes())

    def test_package_shell_injection_is_rejected(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--serial", "TEST", "--output", "/unused",
                                 "--package", "app.id; echo injected"], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)
        self.assertIn("invalid package", result.stderr)
