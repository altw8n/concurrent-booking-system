import csv
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from collect_results import collect, write_reports
from plot_results import prepare, write_provenance
from test_collect_results import write_run, change


class PlotResultsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.raw = self.base / "raw"
        self.raw.mkdir()
        self.processed = self.base / "processed"

    def populate(self, strategies=("naive", "optimistic", "pessimistic", "atomic", "serializable"), repeats=3):
        for strategy in strategies:
            for repeat in range(repeats):
                write_run(self.raw, f"{strategy}-{repeat}", strategy, violation=strategy == "naive")

    def reports(self):
        rows, report = collect(self.raw, 3)
        write_reports(self.processed, rows, report)

    def mutate_csv(self, mutate):
        path = self.processed / "runs.csv"
        with path.open(encoding="utf-8-sig", newline="") as source:
            reader = csv.DictReader(source)
            fields = reader.fieldnames
            rows = list(reader)
        mutate(rows)
        with path.open("w", encoding="utf-8-sig", newline="") as target:
            writer = csv.DictWriter(target, fieldnames=fields)
            writer.writeheader()
            writer.writerows(rows)

    def test_valid_runs_keep_naive_violations_and_assign_repeat_labels(self):
        self.populate()
        self.reports()
        rows, conditions, scenarios = prepare(self.processed, 3)
        self.assertEqual(15, len(rows))
        self.assertEqual(1, len(scenarios))
        naive = [row for row in rows if row["strategy"] == "naive"]
        self.assertEqual([1, 2, 3], [row["repeat"] for row in naive])
        self.assertEqual([1, 1, 1], [row["extra_active_bookings"] for row in naive])
        output = self.base / "plots"
        output.mkdir()
        write_provenance(self.processed, output, rows, conditions, ["figure"], "test-version", 3)
        provenance = json.loads((output / "plot-manifest.json").read_text())
        self.assertEqual(15, len(provenance["run_ids"]))
        self.assertEqual(64, len(provenance["input_sha256"]["runs.csv"]))
        with (output / "plot-data.csv").open(encoding="utf-8-sig", newline="") as source:
            saved = list(csv.DictReader(source))
        self.assertEqual(15, len(saved))

    def test_missing_strategy_or_repeat_and_extra_runs_are_rejected(self):
        self.populate(strategies=("atomic",))
        self.reports()
        with self.assertRaisesRegex(ValueError, "Expected 3 runs"):
            prepare(self.processed, 3)
        self.populate(strategies=("naive", "optimistic", "pessimistic", "serializable"))
        write_run(self.raw, "extra", "atomic")
        self.reports()
        with self.assertRaisesRegex(ValueError, "Expected 3 runs"):
            prepare(self.processed, 3)

    def test_different_runtime_or_pool_settings_cannot_share_figures(self):
        self.populate()
        directory = write_run(self.raw, "different-runtime", "atomic")
        change(directory, "manifest.json", lambda m: m.update(jar_sha256="another-jar"))
        self.reports()
        with self.assertRaisesRegex(ValueError, "Mixed controlled conditions"):
            prepare(self.processed, 3)

    def test_ineligible_run_is_not_silently_dropped(self):
        self.populate()
        (self.raw / "atomic-0" / "failure.json").write_text('{"error":"interrupted"}')
        self.reports()
        with self.assertRaisesRegex(ValueError, "excluded runs"):
            prepare(self.processed, 3)

    def test_modified_csv_counts_nonfinite_latency_and_duplicate_ids_are_rejected(self):
        self.populate()
        mutations = (
            lambda rows: rows[0].update(success="999"),
            lambda rows: rows[0].update(p95_ms="NaN"),
            lambda rows: rows[0].update(run_id=rows[1]["run_id"]),
            lambda rows: rows[0].update(success_latency_n="0"),
            lambda rows: rows[0].update(p95_ms=""),
            lambda rows: rows[0].update(seats_per_wave="2"),
        )
        for mutate in mutations:
            with self.subTest(mutate=mutate):
                self.reports()
                self.mutate_csv(mutate)
                with self.assertRaises((ValueError, KeyError)):
                    prepare(self.processed, 3)

    def test_stale_group_inventory_is_rejected(self):
        self.populate()
        self.reports()
        path = self.processed / "groups.json"
        report = json.loads(path.read_text())
        report["groups"][0]["run_ids"][0] = "not-in-csv"
        path.write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError, "different run inventories"):
            prepare(self.processed, 3)

    def test_fingerprint_cannot_be_reused_for_changed_metadata(self):
        self.populate()
        self.reports()
        path = self.processed / "groups.json"
        report = json.loads(path.read_text())
        next(iter(report["conditions"].values()))["config"]["warmup_waves"] = 100
        path.write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError, "fingerprint"):
            prepare(self.processed, 3)


if __name__ == "__main__":
    unittest.main()
