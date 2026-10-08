import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "analyze_device_trace.py"
SPEC = importlib.util.spec_from_file_location("analyze_device_trace", MODULE_PATH)
trace = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = trace
SPEC.loader.exec_module(trace)


def line(session, seq, elapsed, event, fields=""):
    suffix = f" {fields}" if fields else ""
    return (f"2026-09-19T12:00:00Z +123ms ROUTING_TRACE schema=1 session={session} "
            f"seq={seq} elapsed_ms={elapsed} event={event}{suffix}\n")


class TraceAnalyzerTest(unittest.TestCase):
    def parse(self, content):
        events, warnings = trace.parse_lines(io.StringIO(content))
        return trace.summarize(events), warnings

    def test_call_relative_metrics_do_not_use_session_start_as_answer(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "trigger=call_added")
            + line("abc", 2, 2000, "TARGET_HFP_AUDIO_CONFIRMED",
                   "since_dialing_ms=1900 since_answer_ms=400")
            + line("abc", 3, 12000, "CALL_DURATION_SUMMARY", "connected_duration_ms=10000")
            + line("abc", 4, 12001, "SESSION_FINISHED", "max_observation_gap_ms=2100")
        )
        summaries, _ = self.parse(content)
        self.assertEqual(2000, summaries[0].hfp_latency_ms)
        self.assertEqual(400, summaries[0].answer_to_hfp_ms)
        self.assertEqual(1900, summaries[0].dialing_to_hfp_ms)
        self.assertEqual(10000, summaries[0].connected_duration_ms)
        self.assertEqual(2100, summaries[0].max_observation_gap_ms)

    def test_old_export_does_not_invent_call_relative_metrics(self):
        summaries, _ = self.parse(line("abc", 1, 0, "SESSION_STARTED"))
        self.assertIsNone(summaries[0].answer_to_hfp_ms)
        self.assertIsNone(summaries[0].connected_duration_ms)

    def test_slow_operation_and_unknown_evidence_are_explained(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED")
            + line("abc", 2, 0, "OPERATION_STARTED", "operation=authorization")
            + line("abc", 3, 24400, "OPERATION_FINISHED", "operation=authorization duration_ms=24400")
            + line("abc", 4, 24400, "EVALUATION_TIMING", "duration_ms=24400 queue_delay_ms=15")
            + line("abc", 5, 24401, "TIMER_FIRED", "late_ms=24600 dispatch_late_ms=24100")
            + line("abc", 6, 24402, "EVIDENCE_SNAPSHOT", "audio_quality=STALE projection_status=ERROR")
            + line("abc", 7, 24403, "REQUEST_ACCEPTED")
            + line("abc", 8, 24404, "OPERATION_STARTED", "operation=call_safety")
        )
        summaries, warnings = self.parse(content)
        self.assertEqual([], warnings)
        item = summaries[0]
        self.assertEqual("OPEN", item.status)
        self.assertEqual({"authorization": 24400}, item.operation_max_ms)
        self.assertEqual(["call_safety"], item.unfinished_operations)
        self.assertEqual(24400, item.max_evaluation_ms)
        self.assertEqual(15, item.max_evaluation_queue_ms)
        self.assertEqual(24100, item.max_timer_dispatch_late_ms)
        self.assertEqual(1, item.audio_unknown_snapshots)
        self.assertEqual("ERROR", item.final_projection_status)
        self.assertTrue(item.accepted_without_hfp_confirmation)

    def test_user_observation_prevents_platform_confirmation_being_called_pass(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED")
            + line("abc", 2, 500, "TARGET_HFP_AUDIO_CONFIRMED")
            + line("abc", 3, 1000, "USER_AUDIO_REPORT", "observation=wrong_audio")
            + line("abc", 4, 1001, "DIAGNOSTIC_INCIDENT", "trigger=user_report")
            + line("abc", 5, 2000, "SESSION_FINISHED", "phase=RELEASED best_confirmation=TARGET_HFP_AUDIO")
        )
        summaries, _ = self.parse(content)
        self.assertEqual("USER_REPORTED_FAILURE", summaries[0].status)
        self.assertTrue(summaries[0].hfp_audio_confirmed)
        self.assertEqual(1, summaries[0].user_audio_reports)
        self.assertEqual(1, summaries[0].diagnostic_incidents)

    def test_dual_confirmation_finished_session_passes(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=manual trigger=route_now")
            + line("abc", 2, 10, "REQUEST_SUBMITTED", "attempt=1")
            + line("abc", 3, 50, "TELECOM_ENDPOINT_CONFIRMED")
            + line("abc", 4, 80, "TARGET_HFP_AUDIO_CONFIRMED")
            + line("abc", 5, 900, "SESSION_FINISHED",
                   "phase=SUCCEEDED reason=TARGET_VERIFIED termination=call_removed best_confirmation=TARGET_HFP_AUDIO")
        )
        summaries, warnings = self.parse(content)
        self.assertEqual([], warnings)
        self.assertEqual("PASS", summaries[0].status)
        self.assertEqual(1, summaries[0].requests)
        self.assertEqual(50, summaries[0].endpoint_latency_ms)
        self.assertEqual(80, summaries[0].hfp_latency_ms)

    def test_explicit_observation_result_separates_gap_teardown_and_takeover(self):
        prefix = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
            + line("abc", 2, 500, "TARGET_HFP_AUDIO_CONFIRMED")
            + line("abc", 3, 26000, "CONFIRMED_AUDIO_CHANGED", "target_sco=false")
        )
        for incomplete, loss, expected in [
            ("true", "false", "INCOMPLETE"),
            ("false", "false", "PASS"),
            ("false", "true", "UNSTABLE"),
            ("true", "true", "UNSTABLE"),
        ]:
            with self.subTest(incomplete=incomplete, loss=loss):
                summaries, warnings = self.parse(prefix + line(
                    "abc", 4, 26017, "SESSION_FINISHED",
                    "phase=RELEASED observation_incomplete=" + incomplete
                    + " post_confirmation_loss_observed=" + loss,
                ))
                self.assertEqual([], warnings)
                self.assertEqual(expected, summaries[0].status)
                self.assertEqual(1, summaries[0].audio_confirmation_losses)

    def test_endpoint_only_is_incomplete(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
            + line("abc", 2, 20, "TELECOM_ENDPOINT_CONFIRMED")
            + line("abc", 3, 100, "SESSION_FINISHED",
                   "phase=VERIFYING reason=AWAITING_TARGET_HFP_AUDIO termination=call_removed best_confirmation=TELECOM_ENDPOINT")
        )
        summaries, _ = self.parse(content)
        self.assertEqual("INCOMPLETE", summaries[0].status)
        self.assertFalse(summaries[0].hfp_audio_confirmed)

    def test_selector_recovery_is_reported_without_masking_route_failure(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
            + line("abc", 2, 500, "REQUEST_SUBMITTED", "attempt=1")
            + line("abc", 3, 4500, "SELECTOR_RECOVERY_SUBMITTED", "displayed_route=TARGET target_sco=false")
            + line("abc", 4, 4600, "ENDPOINT_CHANGED", "route=COMPETING_DEVICE")
            + line("abc", 5, 5000, "SESSION_FINISHED",
                   "phase=FAILED reason=SELECTOR_RECOVERY_CONFIRMED termination=call_removed "
                   "best_confirmation=TELECOM_ENDPOINT final_route=COMPETING_DEVICE")
        )
        summaries, _ = self.parse(content)
        self.assertEqual("FAIL", summaries[0].status)
        self.assertEqual(1, summaries[0].requests)
        self.assertEqual(1, summaries[0].selector_recoveries)

    def test_structured_diagnostics_explain_split_brain_and_recovery(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
            + line("abc", 2, 1, "SESSION_ENVIRONMENT", "sdk=37 model=SM-Test")
            + line("abc", 3, 500, "EVIDENCE_SNAPSHOT",
                   "route=TARGET hfp_audio_owner=COMPETITOR target_sco=false projection=true")
            + line("abc", 4, 600, "REQUEST_SUBMITTED", "attempt=1")
            + line("abc", 5, 601, "REQUEST_CONTEXT", "attempt=1 request=1")
            + line("abc", 6, 4500, "SELECTOR_RECOVERY_SUBMITTED", "displayed_route=TARGET")
            + line("abc", 7, 4501, "SELECTOR_RECOVERY_CONTEXT", "request=2")
            + line("abc", 8, 4600, "SELECTOR_RECOVERY_CONFIRMED",
                   "route=COMPETING_DEVICE hfp_audio_owner=COMPETITOR")
            + line("abc", 9, 5000, "SESSION_FINISHED",
                   "phase=FAILED reason=SELECTOR_RECOVERY_CONFIRMED termination=call_removed")
        )
        summaries, warnings = self.parse(content)
        self.assertEqual([], warnings)
        item = summaries[0]
        self.assertTrue(item.diagnostic_complete)
        self.assertTrue(item.split_brain_observed)
        self.assertTrue(item.selector_recovery_confirmed)
        self.assertEqual("COMPETITOR", item.final_hfp_audio_owner)
        self.assertEqual(1, item.request_contexts)
        self.assertEqual(1, item.selector_recovery_contexts)

    def test_diagnostic_gaps_do_not_reclassify_behavioral_result(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=manual trigger=route_now")
            + line("abc", 2, 10, "TARGET_HFP_AUDIO_CONFIRMED")
            + line("abc", 3, 20, "SESSION_FINISHED",
                   "phase=RELEASED reason=TARGET_AUDIO_CONFIRMED termination=call_removed")
        )
        summaries, _ = self.parse(content)
        self.assertEqual("PASS", summaries[0].status)
        self.assertFalse(summaries[0].diagnostic_complete)
        self.assertIn("missing SESSION_ENVIRONMENT", summaries[0].diagnostic_gaps)

    def test_hfp_audio_passes_when_telecom_display_remains_split_brain(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
            + line("abc", 2, 500, "REQUEST_SUBMITTED", "attempt=1")
            + line("abc", 3, 780, "TARGET_HFP_AUDIO_CONFIRMED")
            + line("abc", 4, 900, "SESSION_FINISHED",
                   "phase=RELEASED reason=TARGET_AUDIO_CONFIRMED termination=call_removed "
                   "best_confirmation=TARGET_HFP_AUDIO final_route=STREAMING")
        )
        summaries, warnings = self.parse(content)
        self.assertEqual([], warnings)
        self.assertEqual("PASS", summaries[0].status)
        self.assertFalse(summaries[0].endpoint_confirmed)
        self.assertTrue(summaries[0].hfp_audio_confirmed)

    def test_route_oscillation_is_unstable_even_with_dual_confirmation(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
            + line("abc", 2, 10, "ENDPOINT_CHANGED", "route=TARGET")
            + line("abc", 3, 20, "TELECOM_ENDPOINT_CONFIRMED")
            + line("abc", 4, 25, "TARGET_HFP_AUDIO_CONFIRMED")
            + line("abc", 5, 30, "ENDPOINT_CHANGED", "route=HANDSET")
            + line("abc", 6, 40, "ENDPOINT_CHANGED", "route=TARGET")
            + line("abc", 7, 100, "SESSION_FINISHED",
                   "phase=SUSPENDED reason=CALL_NOT_ACTIVE termination=call_removed "
                   "best_confirmation=TARGET_HFP_AUDIO final_route=TARGET")
        )
        summaries, _ = self.parse(content)
        self.assertEqual("UNSTABLE", summaries[0].status)
        self.assertEqual(1, summaries[0].route_oscillations)

    def test_watch_loss_keeps_hfp_confirmation_from_being_reported_as_pass(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
            + line("abc", 2, 700, "TARGET_HFP_AUDIO_CONFIRMED")
            + line("abc", 3, 3700, "POST_CONFIRMATION_WATCH_FINISHED",
                   "reason=deadline loss_observed=true final_target_sco=false")
            + line("abc", 4, 5000, "SESSION_FINISHED",
                   "phase=SUSPENDED reason=CALL_NOT_ACTIVE termination=call_removed "
                   "best_confirmation=TARGET_HFP_AUDIO post_confirmation_loss_observed=true")
        )
        summaries, _ = self.parse(content)
        self.assertEqual("UNSTABLE", summaries[0].status)
        self.assertEqual(1, summaries[0].audio_confirmation_losses)

    def test_startup_replay_is_reported_but_does_not_fail_session(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
            + line("abc", 2, 5, "ENDPOINT_REQUEST_OBSERVED", "classification=STARTUP_REPLAY")
            + line("abc", 3, 20, "TELECOM_ENDPOINT_CONFIRMED")
            + line("abc", 4, 25, "TARGET_HFP_AUDIO_CONFIRMED")
            + line("abc", 5, 100, "SESSION_FINISHED",
                   "phase=SUSPENDED reason=CALL_NOT_ACTIVE termination=call_removed "
                   "best_confirmation=TARGET_HFP_AUDIO final_route=TARGET")
        )
        summaries, _ = self.parse(content)
        self.assertEqual("PASS", summaries[0].status)
        self.assertEqual(1, summaries[0].startup_replays)

    def test_external_callback_is_observational_and_does_not_fail_session(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
            + line("abc", 2, 7, "ENDPOINT_REQUEST_OBSERVED", "classification=EXTERNAL observational=true")
            + line("abc", 3, 20, "TELECOM_ENDPOINT_CONFIRMED")
            + line("abc", 4, 25, "TARGET_HFP_AUDIO_CONFIRMED")
            + line("abc", 5, 100, "SESSION_FINISHED",
                   "phase=SUCCEEDED reason=TARGET_AUDIO_CONFIRMED termination=call_removed "
                   "best_confirmation=TARGET_HFP_AUDIO final_route=TARGET")
        )
        summaries, _ = self.parse(content)
        self.assertEqual("PASS", summaries[0].status)
        self.assertEqual(1, summaries[0].external_callbacks)

    def test_explicit_policy_failure_overrides_endpoint_only_observation(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
            + line("abc", 2, 8, "EVIDENCE_SNAPSHOT",
                   "route=TARGET hfp_audio_owner=OTHER target_sco=false")
            + line("abc", 3, 9, "TELECOM_ENDPOINT_CONFIRMED")
            + line("abc", 4, 500, "TIMER_SCHEDULED", "delay_ms=500")
            + line("abc", 5, 13800, "TIMER_FIRED", "late_ms=13300 sleep_delta_ms=13000")
            + line("abc", 6, 13810, "POLICY_STATE", "phase=FAILED reason=EVIDENCE_DEADLINE_EXPIRED")
            + line("abc", 7, 13820, "SESSION_FINISHED",
                   "phase=SUSPENDED reason=CALL_NOT_ACTIVE best_confirmation=TELECOM_ENDPOINT")
        )
        summaries, _ = self.parse(content)
        item = summaries[0]
        self.assertEqual("FAIL", item.status)
        self.assertTrue(item.split_brain_observed)
        self.assertEqual(13300, item.max_timer_late_ms)
        self.assertEqual(0, item.requests)

    def test_late_policy_failure_overrides_earlier_hfp_confirmation(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
            + line("abc", 2, 20, "TARGET_HFP_AUDIO_CONFIRMED")
            + line("abc", 3, 500, "POLICY_STATE", "phase=FAILED reason=EVIDENCE_DEADLINE_EXPIRED")
            + line("abc", 4, 700, "SESSION_FINISHED",
                   "phase=SUSPENDED reason=CALL_NOT_ACTIVE termination=call_removed "
                   "best_confirmation=TARGET_HFP_AUDIO")
        )
        summaries, warnings = self.parse(content)
        self.assertEqual([], warnings)
        self.assertTrue(summaries[0].hfp_audio_confirmed)
        self.assertEqual("FAIL", summaries[0].status)

    def test_terminal_failure_overrides_earlier_hfp_confirmation(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
            + line("abc", 2, 20, "TARGET_HFP_AUDIO_CONFIRMED")
            + line("abc", 3, 700, "SESSION_FINISHED",
                   "phase=FAILED reason=SELECTOR_RECOVERY_FAILED termination=call_removed "
                   "best_confirmation=TARGET_HFP_AUDIO")
        )
        summaries, _ = self.parse(content)
        self.assertEqual("FAIL", summaries[0].status)

    def test_confirmed_audio_loss_is_unstable_without_endpoint_oscillation(self):
        content = (
            line("abc", 1, 0, "SESSION_STARTED", "mode=automatic trigger=fresh_active_transition")
            + line("abc", 2, 100, "TARGET_HFP_AUDIO_CONFIRMED")
            + line("abc", 3, 120000, "CONFIRMED_AUDIO_CHANGED",
                   "target_sco=false hfp_audio_owner=OTHER telecom_route=TARGET")
            + line("abc", 4, 120100, "SESSION_FINISHED",
                   "phase=SUSPENDED reason=CALL_NOT_ACTIVE best_confirmation=TARGET_HFP_AUDIO")
        )
        summaries, _ = self.parse(content)
        self.assertEqual("UNSTABLE", summaries[0].status)
        self.assertEqual(1, summaries[0].audio_confirmation_losses)

    def test_sequence_gap_is_invalid(self):
        content = line("abc", 1, 0, "SESSION_STARTED") + line("abc", 3, 10, "SESSION_FINISHED")
        summaries, _ = self.parse(content)
        self.assertEqual("INVALID", summaries[0].status)
        self.assertIn("expected seq 2", summaries[0].anomalies[0])

    def test_open_session_is_not_a_pass(self):
        content = (line("abc", 1, 0, "SESSION_STARTED")
                   + line("abc", 2, 5, "TELECOM_ENDPOINT_CONFIRMED")
                   + line("abc", 3, 10, "TARGET_HFP_AUDIO_CONFIRMED"))
        summaries, _ = self.parse(content)
        self.assertEqual("OPEN", summaries[0].status)

    def test_multiple_sessions_and_exclusion(self):
        content = line("old", 1, 0, "SESSION_STARTED") + line("new", 1, 0, "SESSION_STARTED")
        events, _ = trace.parse_lines(io.StringIO(content))
        summaries = trace.summarize(events, {"old"})
        self.assertEqual(["new"], [item.session for item in summaries])

    def test_nontrace_ignored_and_malformed_warned(self):
        content = "ordinary app message\nROUTING_TRACE schema=1 session=abc seq=x elapsed_ms=0 event=START\n"
        events, warnings = trace.parse_lines(io.StringIO(content))
        self.assertEqual([], events)
        self.assertEqual(1, len(warnings))

    def test_json_cli_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trace.log"
            path.write_text(line("abc", 1, 0, "SESSION_STARTED"), encoding="utf-8")
            output = io.StringIO()
            original = trace.sys.stdout
            try:
                trace.sys.stdout = output
                self.assertEqual(0, trace.main([str(path), "--format", "json"]))
            finally:
                trace.sys.stdout = original
            parsed = json.loads(output.getvalue())
            self.assertEqual("abc", parsed["sessions"][0]["session"])

    def test_filtered_trace_omits_prior_sessions(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "filtered.log"
            events, _ = trace.parse_lines(io.StringIO(
                line("old", 1, 0, "SESSION_STARTED") + line("new", 1, 0, "SESSION_STARTED")
            ))
            trace.write_filtered_trace(output, events, {"old"})
            content = output.read_text(encoding="utf-8")
            self.assertNotIn("session=old", content)
            self.assertIn("session=new", content)


if __name__ == "__main__":
    unittest.main()
