import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "check_version_consistency.py"
SPEC = importlib.util.spec_from_file_location("check_version_consistency", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class VersionConsistencyTest(unittest.TestCase):
    def test_release_tag_must_match_source_version(self) -> None:
        self.assertEqual(1, MODULE.main(["--tag", "v99.0.0"]))
        self.assertEqual(0, MODULE.main(["--tag", "v0.3.0-beta.18"]))
        self.assertEqual(1, MODULE.main(["--tag", "v0.3.0-beta.18-debug.commit"]))

    def test_capture_reads_expected_value(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sample.txt").write_text("version=beta.3\n", encoding="utf-8")
            with mock.patch.object(MODULE, "ROOT", root):
                self.assertEqual("beta.3", MODULE.capture("sample.txt", r"version=(.+)", "version"))

    def test_capture_rejects_missing_marker(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "sample.txt").write_text("unrelated\n", encoding="utf-8")
            with mock.patch.object(MODULE, "ROOT", root):
                with self.assertRaisesRegex(ValueError, "could not find version"):
                    MODULE.capture("sample.txt", r"version=(.+)", "version")


if __name__ == "__main__":
    unittest.main()
