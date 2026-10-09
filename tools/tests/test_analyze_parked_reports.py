import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("parked_reports", Path(__file__).parents[1] / "analyze_parked_reports.py")
reports = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reports)


def report(**changes):
    fields = dict(source="user_report", speaker="PASS", microphone="PASS",
                  aa_navigation="PASS", aa_media_resumed="PASS",
                  physical_audio_automatically_verified="false", universal_qualification="false")
    fields.update(changes)
    return "2026-10-09T14:00:00Z +1ms USER_PARKED_TEST " + "; ".join(f"{k}={v}" for k, v in fields.items())


class ParkedReportTest(unittest.TestCase):
    def test_positive_reports_remain_user_evidence_only(self):
        result = reports.analyze([report()])
        self.assertTrue(result["acceptable"])
        self.assertEqual(1, len(result["reports"]))
        self.assertFalse(result["automatic_audio_confirmation"])
        self.assertFalse(result["universal_qualification"])

    def test_every_failed_or_unchecked_dimension_blocks_acceptance(self):
        for field in reports.OBSERVATIONS:
            for answer in ("FAIL", "NOT_CHECKED"):
                with self.subTest(field=field, answer=answer):
                    self.assertFalse(reports.analyze([report(**{field: answer})])["acceptable"])
        self.assertFalse(reports.analyze([report(speaker="FAIL"), report()])["acceptable"])

    def test_malformed_or_misrepresented_evidence_is_rejected(self):
        for raw in ("USER_PARKED_TEST", report(source="automatic"), report(speaker="yes"), report() + "; speaker=PASS",
                    report() + "; malformed", report(universal_qualification="true"),
                    report(physical_audio_automatically_verified="true")):
            with self.subTest(raw=raw):
                result = reports.analyze([raw])
                self.assertFalse(result["acceptable"])
                self.assertTrue(result["errors"])

    def test_no_reports_preserve_legacy_operator_gate(self):
        result = reports.analyze(["ROUTING_TRACE event=SESSION_FINISHED", "Other app event"])
        self.assertTrue(result["acceptable"])
        self.assertEqual([], result["reports"])
        self.assertFalse(result["automatic_audio_confirmation"])

    def test_report_cannot_span_calls_or_qualify_an_absent_call(self):
        for start, end in (("NONE", "NONE"), ("1", "2"), ("1", ""), ("0", "0")):
            self.assertFalse(reports.analyze([report(sessionAtStart=start, sessionAtEnd=end)])["acceptable"])
        self.assertTrue(reports.analyze([report(sessionAtStart="1234", sessionAtEnd="1234")])["acceptable"])


if __name__ == "__main__":
    unittest.main()
