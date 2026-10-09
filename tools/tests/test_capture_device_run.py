import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).parents[2]
SCRIPT = ROOT / "tools" / "capture_device_run.sh"


FAKE_ADB = r'''#!/usr/bin/env python3
import os
import sys
from pathlib import Path

args = sys.argv[1:]
if args[:2] == ["-s", "FAKE"]:
    args = args[2:]

if args == ["get-state"]:
    print("device")
elif args[:3] == ["shell", "pm", "path"]:
    print("package:/data/app/base.apk")
elif args == ["exec-out", "cat", "/data/app/base.apk"]:
    print("fixed test apk bytes", end="")
elif args[:4] == ["shell", "cmd", "appops", "get"]:
    print("MANAGE_ONGOING_CALLS: allow")
elif args[:3] == ["exec-out", "run-as", "org.carcallrouter.companion"] and args[-1] == "id":
    print("uid=10000")
elif args[:4] == ["exec-out", "run-as", "org.carcallrouter.companion", "cat"]:
    if not args[-1].endswith("router.log"):
        sys.exit(1)
    state = Path(os.environ["FAKE_ADB_STATE"])
    count = int(state.read_text() or "0") if state.exists() else 0
    state.write_text(str(count + 1))
    def event(session, seq, elapsed, name, fields=""):
        suffix = " " + fields if fields else ""
        print(f"2026-09-19T12:00:00Z +1ms ROUTING_TRACE schema=1 session={session} seq={seq} elapsed_ms={elapsed} event={name}{suffix}")
    event("old", 1, 0, "SESSION_STARTED", "mode=manual trigger=route_now")
    event("old", 2, 1, "TELECOM_ENDPOINT_CONFIRMED")
    event("old", 3, 2, "TARGET_HFP_AUDIO_CONFIRMED")
    event("old", 4, 3, "SESSION_FINISHED", "phase=SUCCEEDED reason=TARGET_VERIFIED termination=call_removed best_confirmation=TARGET_HFP_AUDIO")
    if count > 0:
        event("new", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
        event("new", 2, 1, "SESSION_ENVIRONMENT", "sdk=37 model=TestModel")
        event("new", 3, 10, "EVIDENCE_SNAPSHOT", "route=COMPETING_DEVICE hfp_audio_owner=COMPETITOR target_sco=false")
        event("new", 4, 20, "REQUEST_SUBMITTED", "attempt=1")
        event("new", 5, 21, "REQUEST_CONTEXT", "attempt=1 request=1")
        print("2026-09-19T12:00:01Z +21ms HFP_QUERY_TIMING elapsedMs=3 uptimeMs=3 deviceCount=3 known=true")
        event("new", 6, 40, "TELECOM_ENDPOINT_CONFIRMED")
        event("new", 7, 60, "TARGET_HFP_AUDIO_CONFIRMED")
        event("new", 8, 70, "SESSION_FINISHED", "phase=SUCCEEDED reason=TARGET_VERIFIED termination=call_removed best_confirmation=TARGET_HFP_AUDIO")
        if os.environ.get("FAKE_PARKED_ANSWER"):
            answer = os.environ["FAKE_PARKED_ANSWER"]
            print(f"2026-09-19T12:00:02Z +1ms USER_PARKED_TEST source=user_report; speaker={answer}; microphone=PASS; aa_navigation=PASS; aa_media_resumed=PASS; physical_audio_automatically_verified=false; universal_qualification=false")
elif args[:3] == ["shell", "getprop", "ro.product.manufacturer"]:
    print("Samsung")
elif args[:3] == ["shell", "getprop", "ro.product.model"]:
    print("TestModel")
elif args[:3] == ["shell", "getprop", "ro.build.version.sdk"]:
    print("37")
elif args[:3] == ["shell", "getprop", "ro.build.version.release"]:
    print("17")
elif args[:3] == ["shell", "getprop", "ro.build.version.security_patch"]:
    print("2026-09-01")
elif args[:3] == ["shell", "getprop", "ro.build.id"]:
    print("TEST1")
elif args[:3] == ["shell", "getprop", "ro.build.version.oneui"]:
    print("90000")
elif args[:3] == ["shell", "getprop", "ro.build.fingerprint"]:
    print("samsung/test/test:17/TEST/1:user/release-keys")
elif args[:3] == ["shell", "dumpsys", "package"]:
    print("versionCode=5 minSdk=34")
    print("versionName=0.3.0-beta.3")
else:
    print("unexpected fake adb arguments: " + repr(args), file=sys.stderr)
    sys.exit(2)
'''


class CaptureDeviceRunTest(unittest.TestCase):
    def test_override_requires_route_tag(self):
        completed = subprocess.run(
            ["bash", str(SCRIPT), "--scenario", "override"],
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(2, completed.returncode)
        self.assertIn("exactly one override-* tag", completed.stderr)

    def test_pass_record_contains_only_new_session(self):
        self.check_capture(None, "PASS")

    def test_parked_report_veto_cannot_be_overridden_by_operator_yes(self):
        for answer, verdict in [("FAIL", "FAIL"), ("NOT_CHECKED", "FAIL"), ("PASS", "PASS")]:
            with self.subTest(answer=answer):
                self.check_capture(answer, verdict)

    def check_capture(self, answer, verdict):
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            adb = temporary / "adb"
            adb.write_text(textwrap.dedent(FAKE_ADB), encoding="utf-8")
            adb.chmod(0o755)
            output = temporary / "run"
            environment = os.environ.copy()
            environment["PATH"] = f"{temporary}:{environment['PATH']}"
            environment["FAKE_ADB_STATE"] = str(temporary / "state")
            environment.pop("FAKE_PARKED_ANSWER", None)
            if answer is not None:
                environment["FAKE_PARKED_ANSWER"] = answer
            completed = subprocess.run(
                ["bash", str(SCRIPT), "--serial", "FAKE", "--scenario", "outgoing",
                 "--tag", "cold-start", "--tag", "android-auto-first",
                 "--output", str(output)],
                input="\n\ny\ny\ny\n",
                text=True,
                capture_output=True,
                env=environment,
                check=False,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)
            trace = (output / "trace.log").read_text(encoding="utf-8")
            self.assertIn("session=new", trace)
            self.assertNotIn("session=old", trace)
            app_events = (output / "app-events.log").read_text(encoding="utf-8")
            self.assertIn("HFP_QUERY_TIMING elapsedMs=3", app_events)
            self.assertIn("session=new", app_events)
            self.assertNotIn("session=old", app_events)
            self.assertEqual("verdict=" + verdict, (output / "verdict.txt").read_text().splitlines()[0])
            self.assertTrue((output / "parked-reports.json").exists())
            device = (output / "device.txt").read_text(encoding="utf-8")
            self.assertIn("build_fingerprint=samsung/test/test:17/TEST/1:user/release-keys", device)
            self.assertIn("security_patch=2026-09-01", device)
            self.assertIn("one_ui_version=90000", device)
            self.assertIn("qualification_tags=cold-start,android-auto-first", device)
            self.assertRegex(device, r"installed_apk_sha256=[0-9a-f]{64}")


if __name__ == "__main__":
    unittest.main()
