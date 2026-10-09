#!/usr/bin/env python3
"""Read user-reported physical/Android Auto observations, separate from route evidence."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Iterable

OBSERVATIONS = ("speaker", "microphone", "aa_navigation", "aa_media_resumed")
ANSWERS = {"PASS", "FAIL", "NOT_CHECKED"}


def analyze(lines: Iterable[str]) -> dict:
    reports = []
    errors = []
    for number, raw in enumerate(lines, 1):
        match = re.search(r"(?:^|\s)USER_PARKED_TEST(?:\s|$)", raw)
        if match is None:
            continue
        fields = {}
        malformed = False
        for token in raw[match.end():].replace(";", " ").split():
            if "=" not in token:
                malformed = True
                continue
            key, value = token.split("=", 1)
            if not key or key in fields:
                malformed = True
            fields[key] = value
        if (malformed or fields.get("source") != "user_report"
                or fields.get("physical_audio_automatically_verified") != "false"
                or fields.get("universal_qualification") != "false"
                or any(fields.get(key) not in ANSWERS for key in OBSERVATIONS)):
            errors.append(f"line {number}: invalid parked-test report")
            continue
        # Newer apps bind the answers to one completed call. A dialog spanning another
        # call, or one with no completed call, cannot qualify either session.
        if "sessionAtStart" in fields or "sessionAtEnd" in fields:
            start, end = fields.get("sessionAtStart", ""), fields.get("sessionAtEnd", "")
            if not start.isdigit() or int(start) <= 0 or start != end:
                errors.append(f"line {number}: parked-test report is not tied to one completed call")
                continue
        reports.append({key: fields[key] for key in OBSERVATIONS})
    return {
        "source": "user_report",
        "reports": reports,
        "errors": errors,
        # No report is backward-compatible: capture still requires operator observations.
        "acceptable": not errors and all(all(value == "PASS" for value in report.values()) for report in reports),
        "automatic_audio_confirmation": False,
        "universal_qualification": False,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="new app events from one isolated capture")
    parser.add_argument("--require-acceptable-reports", action="store_true",
                        help="reject malformed, failed, or unchecked reports; never replace route/operator gates")
    args = parser.parse_args(argv)
    try:
        with args.input.open(encoding="utf-8", errors="replace") as source:
            result = analyze(source)
    except OSError as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2, sort_keys=True))
    return 1 if args.require_acceptable_reports and not result["acceptable"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
