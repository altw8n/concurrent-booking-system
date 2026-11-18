"""Render descriptive figures from one collected series, without running any load."""

import argparse
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import statistics
import tempfile

from collect_results import LATENCY_FIELDS, OUTCOMES, RATES, fingerprint


ROOT = Path(__file__).resolve().parent
STRATEGIES = ("naive", "optimistic", "pessimistic", "atomic", "serializable")
COLORS = ("#B45309", "#2563EB", "#0F766E", "#7C3AED", "#BE185D")
OUTCOME_LABELS = {
    "success": "Успех (HTTP 201)", "occupied": "Место занято",
    "optimistic_conflict": "Конфликт версии", "serialization_failure": "Ошибка сериализации",
    "lock_timeout": "Тайм-аут блокировки",
}
OUTCOME_COLORS = ("#15803D", "#CBD5E1", "#EAB308", "#A855F7", "#DC2626")
COUNT_FIELDS = ("vus", "seats_per_wave", "measured_waves", "warmup_waves", "requests",
                "extra_active_bookings", "violating_seats", "seat_status_mismatches",
                "overall_extra_active_bookings", "overall_violating_seats", "overall_seat_status_mismatches",
                *sorted(OUTCOMES), *(f"{outcome}_latency_n" for outcome in sorted(OUTCOMES)))


def numeric(value, field, integer=False, optional=False):
    if optional and value == "":
        return None
    number = int(value) if integer else float(value)
    if not math.isfinite(number) or number < 0:
        raise ValueError(f"Invalid nonnegative number: {field}")
    return number


def prepare(input_directory, expected_repeats):
    """Fail instead of silently choosing runs or merging incompatible conditions."""
    report = json.loads((input_directory / "groups.json").read_text(encoding="utf-8"))
    conditions = report["conditions"]
    if not conditions:
        raise ValueError("No conditions found")
    baseline = None
    for condition_id, condition in conditions.items():
        manifest = {**condition["runtime"], "harness_sha256": condition["harness_sha256"]}
        actual_id, _ = fingerprint(condition["config"], manifest)
        if actual_id != condition_id:
            raise ValueError("Condition fingerprint does not match metadata")
        controlled = {**condition, "config": {key: value for key, value in condition["config"].items()
                                              if key != "seats_per_wave"}}
        if baseline is not None and controlled != baseline:
            raise ValueError("Mixed controlled conditions: only seats_per_wave may vary within one figure set")
        baseline = controlled
    with (input_directory / "runs.csv").open(encoding="utf-8-sig", newline="") as source:
        records = list(csv.DictReader(source))
    if not records or len({row["run_id"] for row in records}) != len(records):
        raise ValueError("Empty series or duplicate run IDs")
    if report["runs_found"] != len(records) or report["included_runs"] != len(records):
        raise ValueError("Collection contains excluded runs or stale counts; inspect summary.md")
    buckets = {}
    for row in records:
        run_id = row["run_id"]
        strategy = row["strategy"]
        if strategy not in STRATEGIES or row["condition_id"] not in conditions:
            raise ValueError(f"Unknown strategy or condition: {run_id}")
        if row["included"] != "True" or row["protocol_valid"] != "True" or row["pilot_passed"] != "True":
            raise ValueError(f"Run not eligible: {run_id}; inspect runs.csv")
        if row["status"] not in ("valid", "naive_violation") or row["issues"]:
            raise ValueError(f"Run has issues: {run_id}")
        if strategy != "naive" and row["correctness_passed"] != "True":
            raise ValueError(f"Protected strategy failed correctness: {run_id}")
        config = conditions[row["condition_id"]]["config"]
        if any(row[key] != str(value) for key, value in config.items()):
            raise ValueError(f"CSV parameters do not match condition metadata: {run_id}")
        for field in COUNT_FIELDS:
            row[field] = numeric(row[field], field, integer=True)
        for field in (*LATENCY_FIELDS, *RATES):
            row[field] = numeric(row[field], field)
        if (row["requests"] != row["vus"] * row["measured_waves"] or
                sum(row[outcome] for outcome in OUTCOMES) != row["requests"] or
                row["transport_error"] or row["unexpected_response"]):
            raise ValueError(f"Invalid request accounting: {run_id}")
        if row["seat_status_mismatches"] or row["overall_seat_status_mismatches"]:
            raise ValueError(f"Seat status mismatch: {run_id}")
        if strategy != "naive" and (row["extra_active_bookings"] or row["overall_extra_active_bookings"]):
            raise ValueError(f"Protected strategy has extra active bookings: {run_id}")
        if list(row[field] for field in LATENCY_FIELDS) != sorted(row[field] for field in LATENCY_FIELDS):
            raise ValueError(f"Latency percentiles are out of order: {run_id}")
        for outcome in OUTCOMES:
            if row[f"{outcome}_latency_n"] != row[outcome]:
                raise ValueError(f"Latency count mismatch: {run_id}/{outcome}")
            for field in LATENCY_FIELDS:
                key = f"{outcome}_{field}"
                row[key] = numeric(row[key], key, optional=True)
                if (row[key] is None) != (row[outcome] == 0):
                    raise ValueError(f"Missing or unexpected outcome latency: {run_id}/{outcome}")
        row["repeat"] = 0
        buckets.setdefault((row["condition_id"], strategy), []).append(row)
    for condition_id in conditions:
        for strategy in STRATEGIES:
            runs = buckets.get((condition_id, strategy), [])
            if len(runs) != expected_repeats:
                raise ValueError(f"Expected {expected_repeats} runs for {condition_id[:12]}/{strategy}, got {len(runs)}")
            for index, row in enumerate(sorted(runs, key=lambda r: (r["started_utc"], r["run_id"])), 1):
                row["repeat"] = index
    used_groups = {(g["condition_id"], g["strategy"]): g for g in report["groups"]}
    if len(used_groups) != len(report["groups"]) or set(used_groups) != set(buckets):
        raise ValueError("CSV and groups.json have different groups")
    for key, runs in buckets.items():
        group = used_groups[key]
        if (group["included_runs"] != len(runs) or group["excluded_runs"] or
                set(group["run_ids"]) != {row["run_id"] for row in runs}):
            raise ValueError("CSV and groups.json have different run inventories")
    scenarios = sorted(conditions, key=lambda key: conditions[key]["config"]["seats_per_wave"], reverse=True)
    return records, conditions, scenarios


def scenario_title(condition):
    config = condition["config"]
    seats = config['seats_per_wave']
    noun = "место" if seats % 10 == 1 and seats % 100 != 11 else "места" if seats % 10 in (2, 3, 4) and seats % 100 not in (12, 13, 14) else "мест"
    return f"{config['vus']} клиентов / {seats} {noun}"


def runs_for(records, condition_id, strategy):
    return sorted((row for row in records if row["condition_id"] == condition_id and row["strategy"] == strategy),
                  key=lambda row: row["repeat"])


def draw_points(ax, records, condition_id, field, expected_repeats, sample_counts=False):
    labels = []
    for x, (strategy, color) in enumerate(zip(STRATEGIES, COLORS)):
        rows = runs_for(records, condition_id, strategy)
        values = [row[field] for row in rows]
        labels.append(strategy)
        if sample_counts:
            counts = [row["success_latency_n"] for row in rows]
            labels[-1] += f"\nn={min(counts)}" + (f"–{max(counts)}" if min(counts) != max(counts) else "")
        for index, value in enumerate(values):
            if value is not None:
                offset = (index - (expected_repeats - 1) / 2) * .14
                ax.scatter(x + offset, value, color=color, s=32, alpha=.85, zorder=3)
        available = [value for value in values if value is not None]
        if available:
            median = statistics.median(available)
            ax.plot([x - .26, x + .26], [median, median], color="#111827", linewidth=2, zorder=4)
    ax.set_xticks(range(len(STRATEGIES)), labels, rotation=20, ha="right", fontsize=9)
    ax.grid(axis="y", color="#E5E7EB", linewidth=.8, zorder=0)
    ax.set_axisbelow(True)
    ax.spines[["top", "right"]].set_visible(False)
    # Use the same complete range across scenarios; shared axes must not clip later points.
    top = max((row[field] for row in records if row[field] is not None), default=1)
    ax.set_ylim(-max(top * .035, .05), max(top * 1.15, 1))


def render(records, conditions, scenarios, expected_repeats, output):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.ticker import MaxNLocator

    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10,
                         "svg.fonttype": "none", "axes.titleweight": "bold"})
    figures = []
    columns = len(scenarios)
    common = "Точки — отдельные прогоны; чёрная линия — медиана. Только измеряемые волны."

    def save(fig, name, title, note):
        fig.suptitle(title, fontsize=15, fontweight="bold", y=.99)
        fig.text(.02, .018, note, fontsize=9, va="bottom")
        fig.tight_layout(rect=(0, .22 if name == "02_outcomes" else .13, 1, .94))
        for suffix in ("png", "svg"):
            fig.savefig(output / f"{name}.{suffix}", dpi=180, facecolor="white")
        plt.close(fig)
        figures.append(name)

    fig, axes = plt.subplots(1, columns, figsize=(5.3 * columns, 5.4), squeeze=False)
    for ax, condition_id in zip(axes[0], scenarios):
        draw_points(ax, records, condition_id, "extra_active_bookings", expected_repeats)
        ax.set_title(scenario_title(conditions[condition_id]))
        ax.set_ylabel("Лишние ACTIVE-бронирования")
        ax.yaxis.set_major_locator(MaxNLocator(integer=True))
    save(fig, "01_integrity", "Нарушения инварианта: не более одной активной брони на место", common)

    fig, axes = plt.subplots(1, columns, figsize=(5.3 * columns, 6), squeeze=False)
    for ax, condition_id in zip(axes[0], scenarios):
        for x, strategy in enumerate(STRATEGIES):
            for index, row in enumerate(runs_for(records, condition_id, strategy)):
                position = x + (index - (expected_repeats - 1) / 2) * (.8 / expected_repeats)
                bottom = 0
                for (outcome, label), color in zip(OUTCOME_LABELS.items(), OUTCOME_COLORS):
                    height = 100 * row[outcome] / row["requests"]
                    ax.bar(position, height, bottom=bottom, width=.72 / expected_repeats, color=color,
                           label=label if x == 0 and index == 0 else None)
                    bottom += height
        ax.set_title(scenario_title(conditions[condition_id]))
        ax.set_xticks(range(5), STRATEGIES, rotation=20, ha="right", fontsize=9)
        ax.set_ylabel("Доля запросов, %")
        ax.set_ylim(0, 100)
        ax.spines[["top", "right"]].set_visible(False)
    handles, labels = axes[0][0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", bbox_to_anchor=(.5, .075), ncol=3, frameon=False)
    save(fig, "02_outcomes", "Исходы HTTP-запросов", "Столбцы внутри каждой стратегии — отдельные прогоны по времени запуска.\n"
         "HTTP 201 у naive может соответствовать нарушению инварианта: см. график 01.")

    for name, prefix, title in (("03_latency_all", "", "Время ответа: все исходы вместе"),
                                ("04_latency_success", "success_", "Время ответа: только успешные HTTP 201")):
        fig, axes = plt.subplots(2, columns, figsize=(5.3 * columns, 9.2), squeeze=False, sharey="row")
        for column, condition_id in enumerate(scenarios):
            for index, field in enumerate(("p50_ms", "p95_ms")):
                ax = axes[index][column]
                draw_points(ax, records, condition_id, prefix + field, expected_repeats, sample_counts=bool(prefix))
                ax.set_title(scenario_title(conditions[condition_id]))
                ax.set_ylabel(f"{field[:3]}, мс")
        note = common + "\n" + ("n — число успешных запросов в одном прогоне (диапазон по повторам). Малые n ограничивают выводы о хвосте."
                                               if prefix else "Состав исходов различается между стратегиями: сравнивайте вместе с графиком 02.")
        if prefix:
            note += "\nHTTP 201 у naive не гарантирует целостность бронирования: см. график 01."
        save(fig, name, title, note)

    fig, axes = plt.subplots(2, columns, figsize=(5.3 * columns, 9.2), squeeze=False, sharey="row")
    for column, condition_id in enumerate(scenarios):
        for index, (field, label) in enumerate(((RATES[0], "Все запросы / с активных окон"),
                                              (RATES[1], "HTTP 201 / с активных окон"))):
            ax = axes[index][column]
            draw_points(ax, records, condition_id, field, expected_repeats)
            ax.set_title(scenario_title(conditions[condition_id]))
            ax.set_ylabel(label)
    save(fig, "05_wave_rates", "Обработка конечных волн запросов", common + "\n"
         "Знаменатель — сумма активных окон волн. Это не предельная устойчивая пропускная способность.\n"
         "HTTP 201 у naive не гарантирует корректного бронирования.")
    return figures, matplotlib.__version__


def write_provenance(input_directory, output, records, conditions, figures, version, expected_repeats):
    fields = ["run_id", "condition_id", "strategy", "repeat", "vus", "seats_per_wave", "requests",
              "extra_active_bookings", "violating_seats", *OUTCOME_LABELS, *LATENCY_FIELDS,
              "success_latency_n", "success_p50_ms", "success_p95_ms", *RATES]
    with (output / "plot-data.csv").open("w", encoding="utf-8-sig", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(sorted(records, key=lambda row: (-row["seats_per_wave"], row["strategy"], row["repeat"])))
    provenance = {"input": str(input_directory), "input_sha256": {
        name: hashlib.sha256((input_directory / name).read_bytes()).hexdigest() for name in ("runs.csv", "groups.json")},
        "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "matplotlib": version, "expected_repeats": expected_repeats, "run_ids": [row["run_id"] for row in records],
        "conditions": conditions, "figures": figures,
        "notes": ["Repeat numbers are chronological labels within each strategy/scenario, not paired observations.",
                  "Black segments are medians of run metrics, not pooled percentiles or confidence intervals.",
                  "No performance ranking, significance test or automatic causal conclusion is produced."]}
    (output / "plot-manifest.json").write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = ["# Графики экспериментальной серии", "", f"Учтено {len(records)} запусков; {expected_repeats} на стратегию и сценарий.", "",
             "Изображения сохранены в PNG (180 dpi) и SVG. SVG подходит для масштабирования и печати.", "",
             "Точки и столбцы показывают отдельные прогоны; чёрные отрезки — медианы. "
             "Порядковые номера повторов заданы по времени внутри каждой конфигурации, а не как парные наблюдения.", ""]
    for name in figures:
        lines.append(f"- [{name}.png]({name}.png) / [{name}.svg]({name}.svg)")
    lines.extend(["", "Числа и исходные run ID: [plot-data.csv](plot-data.csv). "
                  "Версии, условия и SHA-256 входных файлов: [plot-manifest.json](plot-manifest.json).", "",
                  "Сравниваются только измеряемые волны. На графиках latency показаны p50/p95 каждого запуска; "
                  "это не процентили объединённой выборки. p99 сохранены в таблице, но не изображены из-за малых выборок.", "",
                  "Три повтора не доказывают статистическую значимость. Успех HTTP у naive не означает целостность. "
                  "Показатели активных окон волн не являются оценкой максимальной устойчивой пропускной способности. "
                  "Совпадение версий не контролирует фоновую нагрузку; достаточность прогрева отдельно не доказана.", ""])
    (output / "README.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="Collected directory containing runs.csv and groups.json")
    parser.add_argument("--output", type=Path, help="Defaults to experiments/graphs/<input directory name>")
    parser.add_argument("--expected-repeats", type=int, default=3)
    args = parser.parse_args()
    if args.expected_repeats < 1:
        parser.error("--expected-repeats must be positive")
    source = args.input.resolve()
    output = (args.output or ROOT / "graphs" / source.name).resolve()
    raw = (ROOT / "raw-results").resolve()
    if source == output or source in output.parents or output in source.parents or output == raw or raw in output.parents:
        parser.error("Output must be separate from inputs and raw artifacts")
    try:
        records, conditions, scenarios = prepare(source, args.expected_repeats)
        output.mkdir(parents=True, exist_ok=True)
        # A temporary font cache permits rendering in restricted environments without writing to HOME.
        with tempfile.TemporaryDirectory(prefix="booking-plots-") as cache:
            os.environ.setdefault("MPLCONFIGDIR", cache)
            figures, version = render(records, conditions, scenarios, args.expected_repeats, output)
        write_provenance(source, output, records, conditions, figures, version, args.expected_repeats)
    except ImportError as error:
        parser.exit(1, f"Missing plotting dependency: {error}. Use .venv/bin/python and install experiments/requirements-plotting.txt\n")
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"Cannot plot this series: {error}\n")
    print(f"Rendered {len(figures)} figures as PNG and SVG from {len(records)} runs: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
