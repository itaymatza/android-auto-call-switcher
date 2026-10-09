import hashlib
import importlib.util
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("publish_debug_release", Path(__file__).parents[1] / "publish_debug_release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.local = self.root / "local"
        self.remote = self.root / "remote"
        self.local.mkdir()
        self.remote.mkdir()
        self.apk = self.local / "build.apk"
        self.assets(self.local, b"original APK")
        self.metadata = None
        self.calls = []
        self.fail_upload = False

    def assets(self, directory, content):
        digest = hashlib.sha256(content).hexdigest()
        (directory / "build.apk").write_bytes(content)
        (directory / "build.apk.sha256").write_text(f"{digest}  build.apk\n")
        (directory / "apk-verification.txt").write_text(
            f"verified=true\napk_sha256={digest}\npackage=example.app\nversion_code=100301\n"
            "version_name=beta.commit\nmin_sdk=34\ntarget_sdk=36\ndebuggable=true\ncertificate_sha256=pinned\n")

    def execute(self, *args):
        self.calls.append(args)
        if args[1] == "api":
            if "--slurp" in args:
                return json.dumps([[], [dict(self.metadata, tag_name="vbeta-debug.commit")] if self.metadata else []])
            if self.metadata is None or self.metadata["draft"]:
                raise subprocess.CalledProcessError(1, args, stderr="gh: Not Found (HTTP 404)")
            return json.dumps(self.metadata)
        action = args[2]
        if action == "create":
            self.metadata = dict(target_commitish="commit", prerelease=True, draft=True)
        elif action == "upload":
            if self.fail_upload:
                raise subprocess.CalledProcessError(1, args, stderr="upload failed")
            for name in ("build.apk", "build.apk.sha256", "apk-verification.txt"):
                shutil.copy(self.local / name, self.remote / name)
        elif action == "download":
            destination = Path(args[args.index("--dir") + 1])
            for asset in self.remote.iterdir():
                shutil.copy(asset, destination / asset.name)
        elif action == "edit":
            self.metadata["draft"] = False
        return ""

    def publish(self):
        release.publish("owner/repo", "vbeta-debug.commit", "commit", self.apk, self.root / "notes", self.execute)

    def test_new_release_is_verified_before_draft_is_published(self):
        self.publish()
        self.assertFalse(self.metadata["draft"])
        actions = [call[2] for call in self.calls if call[1] == "release"]
        self.assertEqual(["create", "upload", "download", "edit"], actions)

    def test_partial_upload_stays_draft_and_retry_replaces_whole_set(self):
        self.fail_upload = True
        with self.assertRaises(subprocess.CalledProcessError):
            self.publish()
        self.assertTrue(self.metadata["draft"])
        self.fail_upload = False
        self.publish()
        self.assertFalse(self.metadata["draft"])

    def test_published_retry_preserves_original_bytes_even_when_rebuild_differs(self):
        self.publish()
        self.assets(self.local, b"another build of same source")
        self.calls.clear()
        self.publish()
        self.assertFalse(any(call[2] in ("upload", "edit") for call in self.calls if call[1] == "release"))
        self.assertEqual(b"original APK", (self.remote / "build.apk").read_bytes())

    def test_missing_published_asset_is_not_filled_from_a_different_build(self):
        self.publish()
        (self.remote / "apk-verification.txt").unlink()
        self.calls.clear()
        with self.assertRaises(FileNotFoundError):
            self.publish()
        self.assertFalse(any(call[2] == "upload" for call in self.calls if call[1] == "release"))

    def test_corrupt_digest_report_and_identity_fail_closed(self):
        for name in ("build.apk", "build.apk.sha256", "apk-verification.txt"):
            self.assets(self.local, b"original APK")
            (self.local / name).write_text("corrupt")
            with self.assertRaises((ValueError, KeyError)):
                self.publish()
        self.assertEqual([], self.calls)
        self.assets(self.local, b"original APK")
        self.publish()
        report = self.remote / "apk-verification.txt"
        report.write_text(report.read_text().replace("certificate_sha256=pinned", "certificate_sha256=wrong"))
        with self.assertRaisesRegex(ValueError, "identity differs"):
            self.publish()

    def test_wrong_source_or_channel_never_uploads(self):
        for metadata in (dict(target_commitish="other", prerelease=True, draft=True),
                         dict(target_commitish="commit", prerelease=False, draft=True)):
            self.metadata = metadata
            self.calls.clear()
            with self.assertRaisesRegex(ValueError, "unexpected source"):
                self.publish()
            self.assertTrue(all(call[1] == "api" for call in self.calls))

    def test_network_failure_is_not_treated_as_missing_release(self):
        def fail(*args):
            raise subprocess.CalledProcessError(1, args, stderr="HTTP 403")
        with self.assertRaises(subprocess.CalledProcessError):
            release.publish("owner/repo", "tag", "commit", self.apk, self.root / "notes", fail)


if __name__ == "__main__":
    unittest.main()
