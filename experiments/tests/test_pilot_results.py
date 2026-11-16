import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pilot_results import analyze, read_samples, validate_config
from run_pilot import java_test_gate


def fixture():
    config = json.loads((Path(__file__).resolve().parents[1] / "configs/pilot.json").read_text())
    config.update(vus=2, seats_per_wave=1, warmup_waves=1, measured_waves=1, wave_interval_ms=1000,
                  request_timeout_ms=500, max_start_lag_ms=100)
    dataset = {"event_id": 1, "user_ids": [1, 2], "warmup_seat_ids": [10], "measured_seat_ids": [11]}
    records = []
    for phase, start, booking_id in (("warmup", 100000, 20), ("measurement", 101000, 21)):
        for client in range(2):
            records.append({"phase": phase, "wave": 0, "client": client, "started_ms": start,
                            "duration_ms": 10 + client * 10, "start_lag_ms": 0, "slot_missed": 0, "unexpected": 0,
                            "status": "201" if client == 0 else "409",
                            "api_code": "" if client == 0 else "SEAT_ALREADY_BOOKED",
                            "outcome": "success" if client == 0 else "occupied",
                            "booking_id": str(booking_id) if client == 0 else ""})
    database = {
        "seats": [{"id": seat, "status": "BOOKED"} for seat in (10, 11)],
        "bookings": [{"id": booking, "user_id": 1, "seat_id": seat, "status": "ACTIVE"}
                     for booking, seat in ((20, 10), (21, 11))],
    }
    return config, dataset, records, database


class PilotAccountingTest(unittest.TestCase):
    def test_warmup_is_separate_and_wave_throughput_has_an_explicit_denominator(self):
        config, dataset, records, database = fixture()
        result = analyze(config, dataset, records, database)
        self.assertTrue(result["pilot_passed"])
        measured = result["phases"]["measurement"]
        self.assertEqual(2, measured["requests"])
        self.assertEqual({"success": 1, "occupied": 1}, measured["outcomes"])
        self.assertEqual(20, measured["sum_wave_active_windows_ms"])
        self.assertEqual(100, measured["requests_per_wave_active_second"])
        self.assertEqual(50, measured["http_successes_per_wave_active_second"])
        self.assertEqual(2, measured["waves"][0]["observed_peak_http_requests"])
        self.assertEqual(15, measured["latency"]["p50_ms"])
        self.assertEqual(19.5, measured["latency"]["p95_ms"])
        self.assertEqual(1, measured["latency_by_outcome"]["success"]["n"])

    def test_real_duplicate_is_a_correctness_failure_but_valid_naive_observation(self):
        config, dataset, records, database = fixture()
        records[-1].update(outcome="success", booking_id="22", status="201", api_code="")
        database["bookings"].append({"id": 22, "user_id": 2, "seat_id": 11, "status": "ACTIVE"})
        protected = analyze(config, dataset, records, database)
        self.assertTrue(protected["protocol_valid"])
        self.assertFalse(protected["pilot_passed"])
        self.assertEqual(1, protected["integrity"]["extra_active_bookings"])
        self.assertEqual([], protected["phases"]["warmup"]["integrity"]["violating_seats"])
        self.assertEqual(1, len(protected["phases"]["measurement"]["integrity"]["violating_seats"]))
        config["strategy"] = "naive"
        baseline = analyze(config, dataset, records, database)
        self.assertTrue(baseline["pilot_passed"])
        self.assertFalse(baseline["correctness_passed"])

    def test_success_response_must_match_the_committed_user_seat_and_booking_id(self):
        for field, value in (("user_id", 9), ("seat_id", 10), ("status", "CANCELLED")):
            config, dataset, records, database = fixture()
            database["bookings"][-1][field] = value
            self.assertFalse(analyze(config, dataset, records, database)["protocol_valid"])

    def test_unreported_insert_and_repeated_success_id_are_detected(self):
        config, dataset, records, database = fixture()
        database["bookings"].append({"id": 22, "user_id": 2, "seat_id": 11, "status": "ACTIVE"})
        self.assertFalse(analyze(config, dataset, records, database)["protocol_valid"])
        config, dataset, records, database = fixture()
        records[-1].update(outcome="success", booking_id="21", status="201", api_code="")
        self.assertFalse(analyze(config, dataset, records, database)["protocol_valid"])

    def test_missing_requests_late_dispatch_and_overlap_cannot_silently_become_valid_runs(self):
        for mutation in ("missing", "late", "overlap", "unexpected", "wrong_status", "wrong_code"):
            config, dataset, records, database = fixture()
            if mutation == "missing":
                records.pop()
            elif mutation == "late":
                records[-1]["start_lag_ms"] = 200
            elif mutation == "overlap":
                records[0]["duration_ms"] = 1100
            elif mutation == "unexpected":
                records[-1]["outcome"] = "transport_error"
            elif mutation == "wrong_status":
                records[-1]["status"] = "500"
            else:
                records[-1]["api_code"] = "UNKNOWN"
            with self.subTest(mutation=mutation):
                self.assertFalse(analyze(config, dataset, records, database)["pilot_passed"])

    def test_seat_status_mismatch_is_not_excused_for_naive(self):
        config, dataset, records, database = fixture()
        config["strategy"] = "naive"
        database["seats"][-1]["status"] = "FREE"
        self.assertFalse(analyze(config, dataset, records, database)["pilot_passed"])

    def test_reusing_seats_between_warmup_and_measurement_is_detected(self):
        config, dataset, records, database = fixture()
        dataset["measured_seat_ids"] = [10]
        self.assertFalse(analyze(config, dataset, records, database)["protocol_valid"])

    def test_config_rejects_invalid_or_uncontrolled_workloads(self):
        config = fixture()[0]
        validate_config(config)
        for key, value in (("vus", True), ("seats_per_wave", 3), ("request_timeout_ms", 1000),
                           ("strategy", "unknown"), ("postgres_image", "postgres:18")):
            invalid = {**config, key: value}
            with self.subTest(key=key):
                self.assertRaises(ValueError, validate_config, invalid)
        self.assertRaises(ValueError, validate_config, {**config, "typo": 1})

    def test_raw_parser_preserves_identifiers_and_rejects_duplicate_or_incomplete_samples(self):
        names = {"duration_ms": "booking_operation_ms", "started_ms": "booking_started_at_ms",
                 "start_lag_ms": "booking_start_lag_ms", "slot_missed": "booking_slot_missed",
                 "unexpected": "booking_unexpected"}
        record = fixture()[2][0]
        tags = {key: str(record[key]) for key in ("phase", "wave", "client", "outcome", "status", "booking_id", "api_code")}
        points = [{"type": "Point", "metric": metric, "data": {"value": record[field], "tags": tags}}
                  for field, metric in names.items()]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "raw.jsonl"
            path.write_text("\n".join(json.dumps(p) for p in points))
            self.assertEqual([record], read_samples(path))
            for bad in (points + [points[0]], points[:-1]):
                path.write_text("\n".join(json.dumps(p) for p in bad))
                self.assertRaises(ValueError, read_samples, path)

    def test_build_gate_rejects_old_69_reports_and_skips_even_with_zero_failures(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            main = directory / "TEST-example.xml"
            main.write_text('<testsuite tests="69" failures="0" errors="0" skipped="0"/>')
            self.assertRaises(RuntimeError, java_test_gate, directory)
            main.write_text('<testsuite tests="84" failures="0" errors="0" skipped="0"/>')
            lifecycle = directory / "TEST-com.example.concurrent_booking_system.booking.service.BookingLifecycleIntegrationTest.xml"
            lifecycle.write_text('<testsuite tests="16" failures="0" errors="0" skipped="0"/>')
            self.assertEqual(100, java_test_gate(directory)["tests"])
            main.write_text('<testsuite tests="84" failures="0" errors="0" skipped="1"/>')
            self.assertRaises(RuntimeError, java_test_gate, directory)


if __name__ == "__main__":
    unittest.main()
