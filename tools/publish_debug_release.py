#!/usr/bin/env python3
"""Publish a complete, verified APK asset set; retries never mix APK/report generations."""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import tempfile
from pathlib import Path


def run(*args: str) -> str:
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout


def verify_assets(directory: Path, apk_name: str, expected: dict | None = None) -> dict:
    apk = directory / apk_name
    if not apk.is_file() or apk.stat().st_size == 0:
        raise ValueError("APK missing or empty")
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    checksum = (directory / f"{apk_name}.sha256").read_text().split()
    if checksum != [digest, apk_name]:
        raise ValueError("APK digest or checksum filename mismatch")
    report = {}
    for line in (directory / "apk-verification.txt").read_text().splitlines():
        key, value = line.split("=", 1)
        if key in report:
            raise ValueError("Duplicate APK verification field")
        report[key] = value
    if report.get("verified") != "true" or report.get("apk_sha256") != digest:
        raise ValueError("APK verification report does not match the download")
    identity = {key: report[key] for key in (
        "package", "version_code", "version_name", "min_sdk", "target_sdk", "debuggable", "certificate_sha256"
    )}
    if expected is not None and identity != expected:
        raise ValueError("Published APK identity differs from this source build")
    return identity


def publish(repo: str, tag: str, sha: str, apk: Path, notes: Path, execute=run) -> None:
    identity = verify_assets(apk.parent, apk.name)
    endpoint = f"repos/{repo}/releases/tags/{tag}"
    try:
        release = json.loads(execute("gh", "api", endpoint))
    except subprocess.CalledProcessError as error:
        if "HTTP 404" not in (error.stderr or ""):
            raise
        execute("gh", "release", "create", tag, "--repo", repo, "--target", sha,
                "--draft", "--prerelease", "--title",
                f"Android Auto Call Switcher {identity['version_name']} (debug beta)", "--notes-file", str(notes))
        release = json.loads(execute("gh", "api", endpoint))
    if release["target_commitish"] != sha or not release["prerelease"]:
        raise ValueError("Existing release has unexpected source commit or channel")
    names = (apk.name, f"{apk.name}.sha256", "apk-verification.txt")
    if release["draft"]:
        execute("gh", "release", "upload", tag, "--repo", repo, "--clobber",
                *(str(apk.parent / name) for name in names))
    # Re-read the actual downloads before making a draft visible. On a published retry,
    # validate the existing trio instead of filling missing files with a different build.
    with tempfile.TemporaryDirectory() as temporary:
        execute("gh", "release", "download", tag, "--repo", repo, "--dir", temporary,
                *(arg for name in names for arg in ("--pattern", name)))
        verify_assets(Path(temporary), apk.name, identity)
    if release["draft"]:
        execute("gh", "release", "edit", tag, "--repo", repo, "--draft=false", "--prerelease")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--notes", type=Path, required=True)
    args = parser.parse_args()
    publish(args.repo, args.tag, args.sha, args.apk, args.notes)


if __name__ == "__main__":
    main()
