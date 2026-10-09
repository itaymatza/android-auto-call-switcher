import tempfile
import unittest
from pathlib import Path

from tools.summarize_qualification import summarize


INVARIANTS = {
    "manufacturer": "Samsung",
    "model": "TestModel",
    "android_api": "37",
    "build_fingerprint": "samsung/test/test:17/TEST/1:user/release-keys",
    "package": "org.carcallrouter.companion",
    "version_name": "0.3.0-beta.3",
    "version_code": "5",
    "installed_apk_sha256": "a" * 64,
}


class QualificationSummaryTest(unittest.TestCase):
    def write_run(
        self,
        root: Path,
        number: int,
        scenario: str,
        tags: list[str],
        verdict: str = "PASS",
        apk_hash: str | None = None,
    ) -> None:
        run = root / f"run-{number:03d}"
        run.mkdir()
        metadata = dict(INVARIANTS)
        if apk_hash is not None:
            metadata["installed_apk_sha256"] = apk_hash
        metadata.update(
            captured_utc=f"20260920T00{number:04d}Z",
            scenario=scenario,
            qualification_tags=",".join(tags),
        )
        (run / "device.txt").write_text(
            "".join(f"{key}={value}\n" for key, value in metadata.items()),
            encoding="utf-8",
        )
        (run / "verdict.txt").write_text(f"verdict={verdict}\n", encoding="utf-8")

    def make_complete_batch(self, root: Path) -> None:
        states = ("cold-start", "warm-start", "screen-off", "post-reboot")
        batteries = ("battery-unrestricted", "battery-optimized", "battery-restricted")
        for index in range(40):
            tags = [
                states[index % len(states)],
                batteries[index % len(batteries)],
                "android-auto-first" if index % 2 == 0 else "target-first",
            ]
            if index < 5:
                tags.append("consecutive")
            elif index < 10:
                tags.append("after-idle")
            self.write_run(root, index, "incoming" if index < 20 else "outgoing", tags)

        next_run = 40
        for disconnect_tag in ("target-disconnect", "projection-disconnect"):
            for _ in range(5):
                self.write_run(root, next_run, "reconnect", [disconnect_tag])
                next_run += 1
        for override_tag in (
                "override-speaker",
                "override-handset",
                "override-wired",
                "override-other-bluetooth",
        ):
            for _ in range(3):
                self.write_run(root, next_run, "override", [override_tag])
                next_run += 1
        for scenario in ("hold-resume", "second-call", "conference"):
            for _ in range(3):
                self.write_run(root, next_run, scenario, [])
                next_run += 1

    def test_complete_consistent_batch_is_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_complete_batch(root)
            summary = summarize(root)
            self.assertTrue(summary.ready, summary.errors)
            self.assertEqual(71, summary.passed)
            self.assertAlmostEqual(3 / 71, summary.upper_failure_bound_95)
            self.assertTrue(all(item.complete for item in summary.requirements))

    def test_legacy_vehicle_tags_still_qualify_existing_captures(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_complete_batch(root)
            for metadata in root.glob("*/device.txt"):
                metadata.write_text(
                    metadata.read_text().replace("target-first", "bmw-first")
                    .replace("target-disconnect", "bmw-disconnect"), encoding="utf-8",
                )
            summary = summarize(root)
            self.assertTrue(summary.ready, summary.errors)
            self.assertEqual(71, summary.passed)


    def test_changed_apk_and_failure_block_readiness(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_complete_batch(root)
            self.write_run(root, 100, "outgoing", [], verdict="FAIL", apk_hash="b" * 64)
            summary = summarize(root)
            self.assertFalse(summary.ready)
            self.assertFalse(summary.consistent)
            self.assertEqual(1, summary.failed)
            self.assertIsNone(summary.upper_failure_bound_95)
            self.assertTrue(any("installed_apk_sha256" in error for error in summary.errors))

    def test_missing_evidence_is_not_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            summary = summarize(Path(directory))
            self.assertFalse(summary.ready)
            self.assertTrue(any("no device-run evidence" in error for error in summary.errors))


if __name__ == "__main__":
    unittest.main()
