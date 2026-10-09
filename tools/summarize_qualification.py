#!/usr/bin/env python3
"""Aggregate parked-call evidence and evaluate the production stability matrix."""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path


INVARIANT_FIELDS = (
    "manufacturer",
    "model",
    "android_api",
    "build_fingerprint",
    "package",
    "version_name",
    "version_code",
    "installed_apk_sha256",
)

TAG_REQUIREMENTS = (
    ("cold start", "cold-start", 5),
    ("warm start", "warm-start", 5),
    ("screen off", "screen-off", 5),
    ("post reboot", "post-reboot", 5),
    ("battery unrestricted", "battery-unrestricted", 5),
    ("battery optimized", "battery-optimized", 5),
    ("battery restricted", "battery-restricted", 5),
    ("Android Auto connected first", "android-auto-first", 10),
    ("Preferred Bluetooth connected first", "target-first", 10),
    ("consecutive calls", "consecutive", 5),
    ("calls after idle", "after-idle", 5),
    ("speaker override", "override-speaker", 3),
    ("handset override", "override-handset", 3),
    ("wired override", "override-wired", 3),
    ("other Bluetooth override", "override-other-bluetooth", 3),
    ("Preferred Bluetooth disconnect", "target-disconnect", 5),
    ("projection disconnect", "projection-disconnect", 5),
)

# Older captures used the initial test vehicle in their tag names.
LEGACY_TAG_ALIASES = {"bmw-first": "target-first", "bmw-disconnect": "target-disconnect"}

OBSERVATION_TAGS = {"projection-unknown", "hfp-unknown"}
ALLOWED_TAGS = {tag for _, tag, _ in TAG_REQUIREMENTS} | OBSERVATION_TAGS


@dataclass(frozen=True)
class RunEvidence:
    path: str
    scenario: str
    tags: tuple[str, ...]
    verdict: str
    metadata: dict[str, str]


@dataclass(frozen=True)
class RequirementResult:
    name: str
    observed: int
    required: int
    complete: bool


@dataclass
class BatchSummary:
    ready: bool
    runs: int
    passed: int
    failed: int
    consistent: bool
    upper_failure_bound_95: float | None
    requirements: list[RequirementResult]
    observed_optional_tags: dict[str, int]
    errors: list[str]
    evidence: list[RunEvidence]


def read_key_values(path: Path) -> tuple[dict[str, str], list[str]]:
    values: dict[str, str] = {}
    errors: list[str] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        return values, [f"{path}: {error}"]
    for line_number, line in enumerate(lines, start=1):
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            errors.append(f"{path}:{line_number}: expected key=value")
            continue
        key, value = line.split("=", 1)
        if not key or key in values:
            errors.append(f"{path}:{line_number}: empty or duplicate key")
            continue
        values[key] = value
    return values, errors


def load_evidence(root: Path) -> tuple[list[RunEvidence], list[str]]:
    evidence: list[RunEvidence] = []
    errors: list[str] = []
    for device_path in sorted(root.rglob("device.txt")):
        run_dir = device_path.parent
        device, device_errors = read_key_values(device_path)
        verdict, verdict_errors = read_key_values(run_dir / "verdict.txt")
        errors.extend(device_errors + verdict_errors)
        missing = [field for field in ("scenario", *INVARIANT_FIELDS) if not device.get(field)]
        if missing:
            errors.append(f"{run_dir}: missing metadata: {', '.join(missing)}")
        result = verdict.get("verdict", "")
        if result not in {"PASS", "FAIL"}:
            errors.append(f"{run_dir}: verdict must be PASS or FAIL")
        tags = tuple(sorted({
            LEGACY_TAG_ALIASES.get(tag, tag)
            for tag in filter(None, device.get("qualification_tags", "").split(","))
        }))
        unknown = sorted(set(tags) - ALLOWED_TAGS)
        if unknown:
            errors.append(f"{run_dir}: unknown qualification tags: {', '.join(unknown)}")
        override_tags = [tag for tag in tags if tag.startswith("override-")]
        disconnect_tags = [tag for tag in tags if tag.endswith("-disconnect")]
        if (device.get("scenario") == "override") != (len(override_tags) == 1):
            errors.append(f"{run_dir}: override scenario/tag mismatch")
        if (device.get("scenario") == "reconnect") != (len(disconnect_tags) == 1):
            errors.append(f"{run_dir}: reconnect scenario/tag mismatch")
        evidence.append(RunEvidence(str(run_dir), device.get("scenario", ""), tags, result, device))
    if not evidence:
        errors.append(f"{root}: no device-run evidence found")
    return evidence, errors


def summarize(root: Path) -> BatchSummary:
    evidence, errors = load_evidence(root)
    passed = [run for run in evidence if run.verdict == "PASS"]
    failed = [run for run in evidence if run.verdict != "PASS"]

    consistent = True
    for field in INVARIANT_FIELDS:
        values = sorted({run.metadata.get(field, "") for run in evidence})
        if len(values) != 1 or not values[0]:
            consistent = False
            errors.append(f"batch invariant {field} has {len(values)} values")

    requirements = [
        RequirementResult("incoming calls", sum(run.scenario == "incoming" for run in passed), 20, False),
        RequirementResult("outgoing calls", sum(run.scenario == "outgoing" for run in passed), 20, False),
        RequirementResult("hold and resume", sum(run.scenario == "hold-resume" for run in passed), 3, False),
        RequirementResult("second call", sum(run.scenario == "second-call" for run in passed), 3, False),
        RequirementResult("conference", sum(run.scenario == "conference" for run in passed), 3, False),
    ]
    for name, tag, minimum in TAG_REQUIREMENTS:
        requirements.append(
            RequirementResult(name, sum(tag in run.tags for run in passed), minimum, False)
        )
    requirements = [
        RequirementResult(item.name, item.observed, item.required, item.observed >= item.required)
        for item in requirements
    ]
    optional = {
        tag: sum(tag in run.tags for run in evidence)
        for tag in sorted(OBSERVATION_TAGS)
    }
    ready = bool(evidence) and not errors and not failed and all(item.complete for item in requirements)
    upper_bound = 3 / len(passed) if passed and not failed else None
    return BatchSummary(
        ready=ready,
        runs=len(evidence),
        passed=len(passed),
        failed=len(failed),
        consistent=consistent,
        upper_failure_bound_95=upper_bound,
        requirements=requirements,
        observed_optional_tags=optional,
        errors=errors,
        evidence=evidence,
    )


def render_text(summary: BatchSummary) -> None:
    print(f"Qualification batch: {'READY' if summary.ready else 'NOT READY'}")
    print(f"Runs: {summary.runs}; pass={summary.passed}; fail={summary.failed}")
    print(f"One APK/build fingerprint: {'yes' if summary.consistent else 'no'}")
    for item in summary.requirements:
        marker = "PASS" if item.complete else "MISS"
        print(f"[{marker}] {item.name}: {item.observed}/{item.required}")
    for tag, count in summary.observed_optional_tags.items():
        print(f"[INFO] observed {tag}: {count}")
    if summary.upper_failure_bound_95 is not None:
        print(f"Approximate one-sided 95% failure-rate upper bound: {summary.upper_failure_bound_95:.2%}")
    for error in summary.errors:
        print(f"Error: {error}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", type=Path, default=Path("verification/device-runs"))
    parser.add_argument("--format", choices=("text", "json"), default="text")
    parser.add_argument("--require-ready", action="store_true")
    args = parser.parse_args(argv)

    summary = summarize(args.root)
    if args.format == "json":
        json.dump(asdict(summary), sys.stdout, indent=2, sort_keys=True)
        print()
    else:
        render_text(summary)
    return 1 if args.require_ready and not summary.ready else 0


if __name__ == "__main__":
    raise SystemExit(main())
