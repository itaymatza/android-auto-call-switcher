#!/usr/bin/env python3
"""Capture bounded, local-only Android system snapshots for an unresolved routing incident.

These files are NOT redacted. They are never included in the app's shareable export.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from time import monotonic


SERVICES = ("telecom", "audio", "bluetooth_manager", "media.audio_policy", "media.audio_flinger")
PROPERTIES = (
    "ro.product.manufacturer", "ro.product.model", "ro.build.version.sdk",
    "ro.build.version.release", "ro.build.version.security_patch", "ro.build.version.oneui",
)
MAX_BYTES = 2_000_000


def capture(adb: list[str], command: list[str], path: Path, timeout: float) -> dict:
    """Stream to a capped private file; kill the process on timeout or output overflow."""
    started = monotonic()
    timed_out = False
    truncated = False
    # Poll the file size rather than keep unbounded dumps in memory.
    with path.open("xb") as output:
        path.chmod(0o600)
        process = subprocess.Popen(adb + command, stdout=output, stderr=subprocess.STDOUT)
        while process.poll() is None:
            if monotonic() - started >= timeout:
                timed_out = True
                process.kill()
                break
            if path.stat().st_size > MAX_BYTES:
                truncated = True
                process.kill()
                break
            try:
                process.wait(timeout=0.05)
            except subprocess.TimeoutExpired:
                pass
        process.wait()
    if path.stat().st_size > MAX_BYTES:
        truncated = True
        with path.open("r+b") as output:
            output.truncate(MAX_BYTES)
    with path.open("rb") as output:
        prefix = output.read(4096).decode("utf-8", errors="replace").lower()
    status = "OK"
    if timed_out:
        status = "TIMEOUT"
    elif truncated:
        status = "TRUNCATED"
    elif any(text in prefix for text in ("permission denial", "permission denied", "securityexception")):
        status = "DENIED"
    elif not prefix.strip() or "can't find service" in prefix or "service not found" in prefix:
        status = "UNAVAILABLE"
    elif process.returncode != 0:
        status = "ERROR"
    return {
        "file": path.name, "exit_code": process.returncode,
        "status": status,
        "timed_out": timed_out, "truncated": truncated,
        "duration_ms": round((monotonic() - started) * 1000),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="explicit authorized ADB device")
    parser.add_argument("--output", required=True, type=Path, help="new private directory")
    parser.add_argument("--package", default="org.carcallrouter.companion")
    parser.add_argument("--timeout", type=float, default=10, help="seconds per command, maximum 30")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", args.package):
        parser.error("invalid package ID")
    if not 0 < args.timeout <= 30:
        parser.error("timeout must be greater than zero and at most 30 seconds")
    if shutil.which("adb") is None:
        parser.error("adb is required")
    if args.output.exists():
        parser.error("output already exists; refusing to overwrite evidence")
    adb = ["adb", "-s", args.serial]
    try:
        state = subprocess.run(adb + ["get-state"], capture_output=True, text=True,
                               timeout=args.timeout, check=True)
        if state.stdout.strip() != "device":
            parser.error("selected device is not authorized and online")
    except (OSError, subprocess.SubprocessError):
        parser.error("selected device is not authorized and online")
    args.output.mkdir(parents=True, mode=0o700)
    args.output.chmod(0o700)
    report = {
        "captured_utc": datetime.now(timezone.utc).isoformat(),
        "redacted": False,
        "purpose": "local diagnosis only; review and redact before sharing",
        "captures": [],
    }
    commands = [(service.replace(".", "_") + ".txt", ["shell", "dumpsys", service])
                for service in SERVICES]
    commands += [
        ("app_package.txt", ["shell", "dumpsys", "package", args.package]),
        ("appops.txt", ["shell", "cmd", "appops", "get", "--uid", args.package, "MANAGE_ONGOING_CALLS"]),
    ]
    commands += [(prop + ".txt", ["shell", "getprop", prop]) for prop in PROPERTIES]
    for filename, command in commands:
        report["captures"].append(capture(adb, command, args.output / filename, args.timeout))
    manifest = args.output / "capture.json"
    with manifest.open("x") as output:
        manifest.chmod(0o600)
        json.dump(report, output, indent=2)
        output.write("\n")
    print("Saved local system snapshots. These files contain unredacted system data; review before sharing.")
    return 0 if all(item["status"] == "OK" for item in report["captures"]) else 1


if __name__ == "__main__":
    raise SystemExit(main())
