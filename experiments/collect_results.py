"""Collect existing pilot artifacts without starting Java, Docker or k6."""

import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import statistics

from pilot_results import OUTCOMES, STRATEGIES, validate_config


ROOT = Path(__file__).resolve().parent
RUNTIME_FIELDS = ("java", "k6", "docker", "compose", "platform", "cpu_model", "logical_cpus",
                  "memory_total", "git_commit", "jar_sha256", "postgres_image_id", "postgres")
RATES = ("requests_per_wave_active_second", "http_successes_per_wave_active_second",
         "requests_per_elapsed_second_including_gaps")
LATENCY_FIELDS = ("p50_ms", "p95_ms", "p99_ms")


def read_json(path):
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path.name} must contain an object")
    return value


def number(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def fingerprint(config, manifest):
    """Keep strategy as a separate dimension, but separate different controlled conditions."""
    runtime = {key: manifest[key] for key in RUNTIME_FIELDS}
    if any(value is None or value == "" for value in runtime.values()):
        raise ValueError("Incomplete runtime metadata")
    hashes = manifest["harness_sha256"]
    if not isinstance(hashes, dict) or not hashes:
        raise ValueError("Missing harness hashes")
    # The orchestration scripts and measured artifact are fingerprinted, rather than Git status text.
    conditions = {"config": {key: value for key, value in config.items() if key != "strategy"},
                  "runtime": runtime, "harness_sha256": hashes}
    encoded = json.dumps(conditions, sort_keys=True, allow_nan=False).encode()
    return hashlib.sha256(encoded).hexdigest(), conditions


def load_run(directory):
    row = {"run_id": directory.name, "source": str(directory.resolve()), "status": "unreadable",
           "included": False, "issues": "", "condition_id": ""}
    conditions = None
    issues = []
    try:
        config = read_json(directory / "config.json")
        config.pop("base_url", None)
        validate_config(config)
        row.update(config)
        manifest = read_json(directory / "manifest.json")
        if manifest.get("run_id") != directory.name:
            raise ValueError("Manifest run_id does not match its directory")
        row.update(started_utc=manifest.get("started_utc", ""),
                   git_commit=manifest.get("git_commit", ""),
                   dirty_tree=bool(manifest.get("git_status", "")))
        row["condition_id"], conditions = fingerprint(config, manifest)
        if (directory / "failure.json").exists():
            issues.append("failure.json: " + str(read_json(directory / "failure.json").get("error", "Unknown failure")))
        result = read_json(directory / "result.json")
        for field in ("protocol_valid", "correctness_passed", "pilot_passed"):
            if type(result[field]) is not bool:
                raise ValueError(f"{field} must be boolean")
            row[field] = result[field]
        row["manifest_pilot_passed"] = manifest.get("pilot_passed") is True
        if not result["protocol_valid"]:
            issues.append("Protocol invalid: " + str(result.get("protocol_errors", [])))
        if manifest.get("pilot_passed") is not True or manifest.get("cleanup_exit_code") != 0:
            issues.append("Manifest does not confirm successful completion and cleanup")
        overall = result["integrity"]
        row.update(invariant_holds=overall["invariant_holds"],
                   overall_extra_active_bookings=overall["extra_active_bookings"],
                   overall_violating_seats=len(overall["violating_seats"]),
                   overall_seat_status_mismatches=len(overall["seat_status_mismatches"]))
        phase = result["phases"]["measurement"]
        outcomes = phase["outcomes"]
        if (set(outcomes) - OUTCOMES or any(type(n) is not int or n < 0 for n in outcomes.values()) or
                phase["requests"] != config["vus"] * config["measured_waves"] or
                sum(outcomes.values()) != phase["requests"] or
                phase["latency"]["n"] != phase["requests"]):
            raise ValueError("Measurement request counts or outcomes are inconsistent")
        row["requests"] = phase["requests"]
        for outcome in sorted(OUTCOMES):
            row[outcome] = outcomes.get(outcome, 0)
        row["technical_conflicts"] = sum(row[key] for key in
                                         ("optimistic_conflict", "serialization_failure", "lock_timeout"))
        row["technical_conflict_rate"] = row["technical_conflicts"] / row["requests"]
        row["http_success_rate"] = row["success"] / row["requests"]
        for field in LATENCY_FIELDS:
            value = phase["latency"][field]
            if not number(value):
                raise ValueError(f"Invalid latency {field}")
            row[field] = value
        for outcome in sorted(OUTCOMES):
            sample = phase["latency_by_outcome"].get(outcome, {"n": 0})
            if sample["n"] != row[outcome]:
                raise ValueError(f"Latency sample count does not match {outcome}")
            row[f"{outcome}_latency_n"] = sample["n"]
            for field in LATENCY_FIELDS:
                value = sample.get(field)
                if sample["n"] and not number(value):
                    raise ValueError(f"Invalid {outcome} latency")
                row[f"{outcome}_{field}"] = value
        for field in RATES:
            value = phase[field]
            if value is not None and not number(value):
                raise ValueError(f"Invalid rate {field}")
            row[field] = value
        integrity = phase["integrity"]
        row.update(extra_active_bookings=integrity["extra_active_bookings"],
                   violating_seats=len(integrity["violating_seats"]),
                   seat_status_mismatches=len(integrity["seat_status_mismatches"]))
        if overall["seat_status_mismatches"]:
            issues.append("Seat status mismatch")
        expected_correctness = not (overall["violating_seats"] or overall["seat_status_mismatches"])
        if overall["invariant_holds"] != (not overall["violating_seats"]):
            issues.append("Inconsistent invariant flag")
        if result["protocol_valid"] and result["correctness_passed"] != expected_correctness:
            issues.append("Inconsistent correctness flag")
        if config["strategy"] != "naive" and not result["correctness_passed"]:
            issues.append("Protected strategy failed correctness checks")
        if not result["pilot_passed"]:
            issues.append("Pilot checks failed")
        row["included"] = not issues
        row["status"] = ("naive_violation" if not overall["invariant_holds"] else "valid") if not issues else "invalid"
    except (OSError, ValueError, KeyError, TypeError, AttributeError, OverflowError) as error:
        issues.append(str(error))
        row["included"] = False
        row["status"] = "incomplete_or_malformed"
    row["issues"] = " | ".join(issues)
    return row, conditions


def spread(values):
    values = [value for value in values if value is not None]
    return {"n": len(values), "median": statistics.median(values) if values else None,
            "min": min(values) if values else None, "max": max(values) if values else None}


def collect(input_root, expected_repeats):
    rows, conditions = [], {}
    for directory in sorted(input_root.iterdir()):
        if not directory.is_dir() or not any((directory / name).exists() for name in
                                            ("config.json", "manifest.json", "result.json", "failure.json")):
            continue
        row, condition = load_run(directory)
        rows.append(row)
        if condition is not None:
            conditions[row["condition_id"]] = condition
    groups = []
    for condition_id, condition in sorted(conditions.items()):
        for strategy in sorted(STRATEGIES):
            attempts = [row for row in rows if row["condition_id"] == condition_id and row.get("strategy") == strategy]
            included = [row for row in attempts if row["included"]]
            metrics = ("requests", *sorted(OUTCOMES), "technical_conflicts", "technical_conflict_rate",
                       "http_success_rate", "extra_active_bookings", "violating_seats", *LATENCY_FIELDS, *RATES)
            groups.append({"condition_id": condition_id, "strategy": strategy,
                           "attempts": len(attempts), "included_runs": len(included),
                           "excluded_runs": len(attempts) - len(included),
                           "expected_repeats": expected_repeats,
                           "missing_repeats": max(0, expected_repeats - len(included)),
                           "extra_repeats": max(0, len(included) - expected_repeats),
                           "run_ids": [row["run_id"] for row in attempts],
                           "metrics_across_runs": {key: spread([row[key] for row in included]) for key in metrics},
                           "latency_by_outcome_across_runs": {
                               outcome: {field: spread([row[f"{outcome}_{field}"] for row in included])
                                         for field in ("latency_n", *LATENCY_FIELDS)} for outcome in sorted(OUTCOMES)}})
    return rows, {"expected_repeats": expected_repeats, "runs_found": len(rows),
                  "included_runs": sum(row["included"] for row in rows),
                  "conditions": conditions, "groups": groups,
                  "notes": ["Only measurement phases are summarized; overall integrity also includes warmup.",
                            "Median/min/max describe per-run metrics, not pooled percentiles or confidence intervals.",
                            "Missing repeats are reported only for observed condition sets, not an entire planned matrix.",
                            "Grouping metadata cannot capture background machine load or prove identical conditions.",
                            "Naive integrity violations remain visible and may be valid observations."]}


def write_reports(output, rows, report):
    output.mkdir(parents=True, exist_ok=True)
    first = ["run_id", "condition_id", "strategy", "vus", "seats_per_wave", "measured_waves", "status", "included", "issues"]
    fields = first + sorted(set().union(*(row.keys() for row in rows)) - set(first))
    with (output / "runs.csv").open("w", encoding="utf-8-sig", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    (output / "groups.json").write_text(json.dumps(report, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")
    lines = ["# Сводка существующих экспериментов", "",
             f"Найдено запусков: {len(rows)}. В описательную сводку включено: {report['included_runs']}.", "",
             "Количество повторов не является оценкой статистической достаточности.", "",
             "| Условия | Стратегия | Попытки | Учтено | Не хватает до цели | Сверх цели |",
             "|---|---|---:|---:|---:|---:|"]
    for group in report["groups"]:
        lines.append(f"| {group['condition_id'][:12]} | {group['strategy']} | {group['attempts']} | "
                     f"{group['included_runs']} | {group['missing_repeats']} | {group['extra_repeats']} |")
    lines.extend(["", "## Условия группировки", ""])
    for key, condition in report["conditions"].items():
        config = condition["config"]
        lines.append(f"- `{key[:12]}`: клиентов={config['vus']}, мест={config['seats_per_wave']}, "
                     f"измеряемых волн={config['measured_waves']}, прогрев={config['warmup_waves']}. "
                     f"Git={condition['runtime']['git_commit'][:12]}, "
                     f"JAR={condition['runtime']['jar_sha256'][:12]}. "
                     "Полные параметры, версии и хеши находятся в groups.json.")
    lines.extend(["", "## Запуски с замечаниями", ""])
    for row in rows:
        if row["issues"] or row.get("dirty_tree") or row["status"] == "naive_violation":
            message = row["issues"] or ("Naive: обнаружено нарушение инварианта." if row["status"] == "naive_violation" else "")
            if row.get("dirty_tree"):
                message += " Рабочее дерево Git содержало изменения."
            lines.append(f"- `{row['run_id']}`: {message}")
    lines.extend(["", "Медианы в groups.json рассчитаны по метрикам отдельных запусков. "
                  "Это не p95/p99 объединённой выборки и не доверительные интервалы. "
                  "Отсутствие записи о сценарии не доказывает полноту запланированной серии.", ""])
    (output / "summary.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=ROOT / "raw-results")
    parser.add_argument("--output", type=Path, default=ROOT / "processed-results")
    parser.add_argument("--expected-repeats", type=int, default=3)
    args = parser.parse_args()
    if args.expected_repeats < 1:
        parser.error("--expected-repeats must be positive")
    if not args.input.is_dir():
        parser.error(f"Input directory not found: {args.input}")
    source, output = args.input.resolve(), args.output.resolve()
    if source == output or source in output.parents or output in source.parents:
        parser.error("Input and output must be separate directories, not ancestors of each other")
    rows, report = collect(source, args.expected_repeats)
    write_reports(output, rows, report)
    print(f"Runs found: {len(rows)}; included: {report['included_runs']}; reports: {output}")
    print("Collection does not confirm completion of a planned matrix or statistical significance.")
    return 0 if rows and all(row["included"] for row in rows) else 1


if __name__ == "__main__":
    raise SystemExit(main())
