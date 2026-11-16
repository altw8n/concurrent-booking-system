import csv
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from collect_results import collect, write_reports
from pilot_results import analyze
from test_pilot_results import fixture


def write_run(root, name, strategy="atomic", updates=None, violation=False):
    config, dataset, records, database = fixture()
    config["strategy"] = strategy
    if updates:
        config.update(updates)
    if violation:
        records[-1].update(outcome="success", booking_id="22", status="201", api_code="")
        database["bookings"].append({"id": 22, "user_id": 2, "seat_id": 11, "status": "ACTIVE"})
    result = analyze(config, dataset, records, database)
    manifest = {
        "run_id": name, "java": "Java 21", "k6": "k6 test", "docker": "test", "compose": "test",
        "platform": "Linux", "cpu_model": "test CPU", "logical_cpus": 4, "memory_total": "16 GB",
        "git_commit": "commit-a", "jar_sha256": "jar-a", "postgres_image_id": "image-a",
        "postgres": {"version": "17", "max_connections": "100", "timezone": "UTC"},
        "harness_sha256": {"script": "hash-a"}, "git_status": "", "started_utc": name,
        "pilot_passed": result["pilot_passed"], "cleanup_exit_code": 0,
    }
    directory = root / name
    directory.mkdir()
    for filename, value in (("config.json", config), ("manifest.json", manifest), ("result.json", result)):
        (directory / filename).write_text(json.dumps(value), encoding="utf-8")
    return directory


def change(directory, filename, mutate):
    path = directory / filename
    value = json.loads(path.read_text())
    mutate(value)
    path.write_text(json.dumps(value))


class CollectResultsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "raw"
        self.root.mkdir()

    def test_strategies_share_conditions_but_have_separate_repeat_counts(self):
        write_run(self.root, "one")
        write_run(self.root, "two")
        write_run(self.root, "three", "optimistic")
        rows, report = collect(self.root, 3)
        self.assertEqual(3, report["included_runs"])
        self.assertEqual(1, len(report["conditions"]))
        groups = {group["strategy"]: group for group in report["groups"]}
        self.assertEqual(1, groups["atomic"]["missing_repeats"])
        self.assertEqual(2, groups["optimistic"]["missing_repeats"])
        self.assertEqual(3, groups["serializable"]["missing_repeats"])
        self.assertEqual(1, rows[0]["success"])
        self.assertEqual(1, rows[0]["occupied"])

    def test_config_artifact_and_runtime_changes_separate_groups(self):
        write_run(self.root, "baseline")
        write_run(self.root, "config", updates={"pool_size": 11})
        jar = write_run(self.root, "jar")
        change(jar, "manifest.json", lambda m: m.update(jar_sha256="different"))
        postgres = write_run(self.root, "postgres")
        change(postgres, "manifest.json", lambda m: m.update(postgres_image_id="different"))
        harness = write_run(self.root, "harness")
        change(harness, "manifest.json", lambda m: m.update(harness_sha256={"script": "different"}))
        _, report = collect(self.root, 3)
        self.assertEqual(5, len(report["conditions"]))

    def test_different_pilot_and_main_wave_counts_do_not_merge(self):
        write_run(self.root, "pilot")
        directory = write_run(self.root, "main")
        change(directory, "config.json", lambda c: c.update(measured_waves=10))
        # The stale result is retained as malformed, not included as a main-series repeat.
        rows, report = collect(self.root, 3)
        self.assertEqual(2, len(report["conditions"]))
        self.assertEqual(1, report["included_runs"])
        self.assertIn("inconsistent", next(r for r in rows if r["run_id"] == "main")["issues"])

    def test_naive_violation_is_visible_and_protected_failure_is_excluded(self):
        write_run(self.root, "naive", "naive", violation=True)
        write_run(self.root, "protected", violation=True)
        rows, report = collect(self.root, 3)
        naive, protected = rows
        self.assertTrue(naive["included"])
        self.assertEqual("naive_violation", naive["status"])
        self.assertFalse(naive["correctness_passed"])
        self.assertEqual(1, naive["extra_active_bookings"])
        self.assertFalse(protected["included"])
        self.assertEqual(1, protected["extra_active_bookings"])
        self.assertEqual(1, report["included_runs"])

    def test_failed_incomplete_and_malformed_runs_remain_in_inventory(self):
        failed = write_run(self.root, "failed")
        (failed / "failure.json").write_text('{"error":"k6 failed"}')
        incomplete = write_run(self.root, "incomplete")
        (incomplete / "result.json").unlink()
        malformed = write_run(self.root, "malformed")
        (malformed / "result.json").write_text("{broken")
        _, report = collect(self.root, 3)
        self.assertEqual(3, report["runs_found"])
        self.assertEqual(0, report["included_runs"])
        self.assertEqual(3, sum(g["excluded_runs"] for g in report["groups"]))

    def test_invalid_protocol_cleanup_and_unknown_runtime_do_not_count_as_repeats(self):
        protocol = write_run(self.root, "protocol")
        change(protocol, "result.json", lambda r: r.update(protocol_valid=False, protocol_errors=["late request"]))
        cleanup = write_run(self.root, "cleanup")
        change(cleanup, "manifest.json", lambda m: m.update(cleanup_exit_code=1))
        metadata = write_run(self.root, "metadata")
        change(metadata, "manifest.json", lambda m: m.pop("jar_sha256"))
        rows, report = collect(self.root, 3)
        self.assertEqual(0, report["included_runs"])
        self.assertTrue(all(row["issues"] for row in rows))

    def test_spread_describes_run_percentiles_without_pooling_or_dropping_extras(self):
        for index, value in enumerate((10, 20, 90, 100)):
            directory = write_run(self.root, str(index))
            change(directory, "result.json", lambda r, v=value: r["phases"]["measurement"]["latency"].update(p95_ms=v))
        _, report = collect(self.root, 3)
        group = next(g for g in report["groups"] if g["strategy"] == "atomic")
        self.assertEqual(1, group["extra_repeats"])
        self.assertEqual({"n": 4, "median": 55, "min": 10, "max": 100}, group["metrics_across_runs"]["p95_ms"])

    def test_csv_and_json_are_written_without_changing_raw_artifacts(self):
        directory = write_run(self.root, "one")
        before = {path.name: path.read_bytes() for path in directory.iterdir()}
        rows, report = collect(self.root, 3)
        output = Path(self.temp.name) / "processed"
        write_reports(output, rows, report)
        with (output / "runs.csv").open(encoding="utf-8-sig", newline="") as source:
            saved = list(csv.DictReader(source))
        self.assertEqual("1", saved[0]["success"])
        self.assertEqual("True", saved[0]["included"])
        self.assertEqual(report, json.loads((output / "groups.json").read_text()))
        self.assertTrue((output / "summary.md").exists())
        self.assertEqual(before, {path.name: path.read_bytes() for path in directory.iterdir()})

    def test_cli_exit_status_distinguishes_bad_artifacts_and_input_is_protected(self):
        write_run(self.root, "one")
        script = Path(__file__).resolve().parents[1] / "collect_results.py"
        output = Path(self.temp.name) / "processed"
        command = [sys.executable, str(script), "--input", str(self.root), "--output", str(output)]
        self.assertEqual(0, subprocess.run(command, capture_output=True).returncode)
        (self.root / "one" / "result.json").write_text("broken")
        self.assertEqual(1, subprocess.run(command, capture_output=True).returncode)
        self.assertEqual(2, subprocess.run(command[:-1] + [str(self.root)], capture_output=True).returncode)


if __name__ == "__main__":
    unittest.main()
