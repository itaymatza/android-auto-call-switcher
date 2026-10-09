#!/usr/bin/env python3
"""Fail when user-facing or release-critical version declarations drift."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def capture(path: str, pattern: str, label: str) -> str:
    match = re.search(pattern, read(path), re.MULTILINE | re.DOTALL)
    if not match:
        raise ValueError(f"{path}: could not find {label}")
    return match.group(1).strip()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", default="", help="when set, must exactly match the source version")
    args = parser.parse_args(argv)
    try:
        version_code = capture(
            "app/build.gradle.kts",
            r'gradleProperty\("APP_VERSION_CODE"\).*?\.getOrElse\((\d+)\)',
            "default version code",
        )
        version_name = capture(
            "app/build.gradle.kts",
            r'gradleProperty\("APP_VERSION_NAME"\).*?\.orElse\("([^\"]+)"\)',
            "default version name",
        )
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1

    if args.tag and args.tag != f"v{version_name}":
        print(f"Release tag {args.tag!r} does not match source v{version_name}", file=sys.stderr)
        return 1

    expected = {
        "README.md": [
            "installed app is named **Android Auto Call Switcher**",
            f"APP_VERSION_CODE={version_code}",
            f"APP_VERSION_NAME={version_name}",
            "https://github.com/itaymatza/android-auto-call-switcher/releases",
        ],
        "docs/CONFIGURATION.md": [f"`{version_code}`", f"`{version_name}`"],
        "docs/PRODUCTION-READINESS.md": [f"`{version_name}` is a production-hardening beta"],
        "docs/PUBLIC_RELEASE.md": [f"org.carcallrouter.companion {version_code} {version_name} 34 36 true"],
        "tools/install.sh": [
            f'VERSION_CODE="${{APP_VERSION_CODE:-{version_code}}}"',
            f'VERSION_NAME="${{APP_VERSION_NAME:-{version_name}}}"',
        ],
        "tools/install.ps1": [
            f"if (-not $VersionCode) {{ $VersionCode = '{version_code}' }}",
            f"if (-not $VersionName) {{ $VersionName = '{version_name}' }}",
        ],
        ".github/workflows/build.yml": [
            f"org.carcallrouter.companion {version_code} {version_name} 34 36 true",
            f"BASE_VERSION_CODE: '{version_code}'",
            f"BASE_VERSION_NAME: {version_name}",
            'echo "RELEASE_TAG=v${BASE_VERSION_NAME}-debug.${short_sha}"',
            'echo "APK_NAME=android-auto-call-switcher-${BASE_VERSION_NAME}-debug.${short_sha}.apk"',
        ],
        ".github/workflows/release.yml": [
            f"org.carcallrouter.companion {version_code} {version_name} 34 36 false",
            "name: android-auto-call-switcher-signed-beta"
        ],
        "docs/DEBUG-PRERELEASE-NOTES.md": ["the `.apk` attached to this release"],
        "app/src/main/AndroidManifest.xml": [
            'android:description="@string/app_description"',
            'android:icon="@mipmap/ic_launcher"',
            'android:label="@string/app_name"',
            'android:roundIcon="@mipmap/ic_launcher_round"',
            'android:usesCleartextTraffic="false"',
        ],
        "app/src/main/res/values/strings.xml": [
            '<string name="app_name">Android Auto Call Switcher</string>',
            "https://github.com/itaymatza/android-auto-call-switcher/releases",
        ],
        "docs/assets/social-preview.svg": ["Android Auto Call Switcher"],
        "fastlane/metadata/android/en-US/title.txt": ["Android Auto Call Switcher"],
        f"fastlane/metadata/android/en-US/changelogs/{version_code}.txt": [
            f"Version {version_name}",
        ],
    }

    errors: list[str] = []
    for path, markers in expected.items():
        content = read(path)
        for marker in markers:
            if marker not in content:
                errors.append(f"{path}: missing current-version marker: {marker}")

    if errors:
        print("Version declarations are inconsistent:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1

    print(f"Version declarations agree: versionCode={version_code}, versionName={version_name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
