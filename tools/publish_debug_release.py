#!/usr/bin/env python3
"""Publish a complete, verified APK asset set; retries never mix APK/report generations."""
from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path


def run(*args: str) -> str:
    try:
        return subprocess.run(args, check=True, text=True, capture_output=True).stdout
    except subprocess.CalledProcessError as error:
        if error.stderr:
            print(error.stderr, file=sys.stderr, end="")
        raise


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


def find_release(repo: str, tag: str, execute) -> dict | None:
    try:
        return json.loads(execute("gh", "api", f"repos/{repo}/releases/tags/{tag}"))
    except subprocess.CalledProcessError as error:
        if "HTTP 404" not in (error.stderr or ""):
            raise
    # The by-tag endpoint exposes published releases only. Authenticated listing includes
    # drafts; paginate so an interrupted older draft is not mistaken for a missing release.
    pages = json.loads(execute("gh", "api", f"repos/{repo}/releases?per_page=100", "--paginate", "--slurp"))
    matches = [item for page in pages for item in page if item["tag_name"] == tag]
    if len(matches) > 1:
        raise ValueError("Multiple releases have this tag; refusing ambiguous publication")
    return matches[0] if matches else None


def publish(repo: str, tag: str, sha: str, apk: Path, notes: Path, execute=run) -> None:
    identity = verify_assets(apk.parent, apk.name)
    release = find_release(repo, tag, execute)
    if release is None:
        # The draft can be absent from an immediately repeated listing. Use the
        # authoritative create response instead of rediscovering our own mutation.
        release = json.loads(execute(
            "gh", "api", f"repos/{repo}/releases", "--method", "POST",
            "-f", f"tag_name={tag}", "-f", f"target_commitish={sha}",
            "-F", "draft=true", "-F", "prerelease=true",
            "-f", f"name=Android Auto Call Switcher {identity['version_name']} (debug beta)",
            "-f", f"body={notes.read_text()}",
        ))
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
