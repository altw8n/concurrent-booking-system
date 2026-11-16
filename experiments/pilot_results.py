"""Offline accounting for the local k6 pilot; standard library only."""

from collections import Counter, defaultdict
import json
import math
import re


STRATEGIES = {"naive", "optimistic", "pessimistic", "atomic", "serializable"}
OUTCOMES = {"success", "occupied", "optimistic_conflict", "serialization_failure", "lock_timeout",
            "transport_error", "unexpected_response"}
FIELDS = {
    "booking_operation_ms": "duration_ms",
    "booking_started_at_ms": "started_ms",
    "booking_start_lag_ms": "start_lag_ms",
    "booking_slot_missed": "slot_missed",
    "booking_unexpected": "unexpected",
}


def validate_config(config):
    bounds = {
        "vus": (1, 100), "seats_per_wave": (1, 100), "warmup_waves": (1, 100),
        "measured_waves": (1, 100), "wave_interval_ms": (100, 60000),
        "request_timeout_ms": (1, 60000), "max_start_lag_ms": (1, 60000),
        "pool_size": (1, 50), "jvm_heap_mb": (256, 4096), "lock_timeout_ms": (1, 60000),
    }
    expected = set(bounds) | {"strategy", "postgres_image"}
    if set(config) != expected:
        raise ValueError(f"Configuration keys differ: {set(config) ^ expected}")
    if config["strategy"] not in STRATEGIES:
        raise ValueError("Unknown booking strategy")
    for key, (low, high) in bounds.items():
        if type(config[key]) is not int or not low <= config[key] <= high:
            raise ValueError(f"{key} must be an integer between {low} and {high}")
    if config["seats_per_wave"] > config["vus"]:
        raise ValueError("seats_per_wave must not exceed vus")
    if config["request_timeout_ms"] + config["max_start_lag_ms"] >= config["wave_interval_ms"]:
        raise ValueError("The wave must accommodate request timeout and allowed start lag")
    if not isinstance(config["postgres_image"], str) or not re.match(r"^postgres:17($|[.@-])", config["postgres_image"]):
        raise ValueError("Use a PostgreSQL 17 image; record the resolved image ID for every run")


def read_samples(path):
    records = {}
    with open(path, encoding="utf-8") as source:
        for number, line in enumerate(source, 1):
            point = json.loads(line)
            if point.get("type") != "Point" or point.get("metric") not in FIELDS:
                continue
            data = point["data"]
            tags = data["tags"]
            key = (tags["phase"], int(tags["wave"]), int(tags["client"]))
            identity = {name: tags.get(name, "") for name in ("outcome", "status", "booking_id", "api_code")}
            record = records.setdefault(key, {"phase": key[0], "wave": key[1], "client": key[2], **identity})
            if any(record[name] != value for name, value in identity.items()):
                raise ValueError(f"Inconsistent sample identity at line {number}")
            field = FIELDS[point["metric"]]
            if field in record:
                raise ValueError(f"Duplicate {field} sample for {key}")
            value = data["value"]
            if not isinstance(value, (int, float)) or not math.isfinite(value):
                raise ValueError(f"Invalid numeric sample at line {number}")
            record[field] = value
    for key, record in records.items():
        if not set(FIELDS.values()).issubset(record):
            raise ValueError(f"Incomplete samples for {key}")
    return list(records.values())


def latency(values):
    values = sorted(values)
    if not values:
        return {"n": 0, "p50_ms": None, "p95_ms": None, "p99_ms": None}

    def percentile(p):
        position = (len(values) - 1) * p
        low = math.floor(position)
        high = math.ceil(position)
        return values[low] + (values[high] - values[low]) * (position - low)

    return {"n": len(values), "p50_ms": percentile(.5), "p95_ms": percentile(.95), "p99_ms": percentile(.99)}


def peak_in_flight(records):
    # End events precede start events at the same millisecond; zero-duration samples add no interval.
    events = []
    for row in records:
        if row["duration_ms"] > 0:
            events.extend([(row["started_ms"], 1), (row["started_ms"] + row["duration_ms"], -1)])
    active = peak = 0
    for _, change in sorted(events):
        active += change
        peak = max(peak, active)
    return peak


def summarize_phase(records):
    by_wave = defaultdict(list)
    for row in records:
        by_wave[row["wave"]].append(row)
    waves = []
    for wave, rows in sorted(by_wave.items()):
        start = min(r["started_ms"] for r in rows)
        end = max(r["started_ms"] + r["duration_ms"] for r in rows)
        waves.append({"wave": wave, "requests": len(rows), "started_ms": start, "ended_ms": end,
                      "active_window_ms": end - start,
                      "dispatch_spread_ms": max(r["started_ms"] for r in rows) - start,
                      "observed_peak_http_requests": peak_in_flight(rows)})
    active_ms = sum(w["active_window_ms"] for w in waves)
    wall_ms = waves[-1]["ended_ms"] - waves[0]["started_ms"] if waves else 0
    successes = sum(r["outcome"] == "success" for r in records)
    return {
        "requests": len(records), "outcomes": dict(Counter(r["outcome"] for r in records)),
        "latency": latency([r["duration_ms"] for r in records]),
        "latency_by_outcome": {outcome: latency([r["duration_ms"] for r in records if r["outcome"] == outcome])
                               for outcome in sorted({r["outcome"] for r in records})},
        "sum_wave_active_windows_ms": active_ms,
        "requests_per_wave_active_second": len(records) * 1000 / active_ms if active_ms else None,
        "http_successes_per_wave_active_second": successes * 1000 / active_ms if active_ms else None,
        "requests_per_elapsed_second_including_gaps": len(records) * 1000 / wall_ms if wall_ms else None,
        "waves": waves,
    }


def analyze(config, dataset, records, database):
    errors = []
    expected_keys = {(phase, wave, client)
                     for phase, count in (("warmup", config["warmup_waves"]),
                                          ("measurement", config["measured_waves"]))
                     for wave in range(count) for client in range(config["vus"])}
    actual_keys = [(r["phase"], r["wave"], r["client"]) for r in records]
    if set(actual_keys) != expected_keys or len(actual_keys) != len(expected_keys):
        errors.append("Missing, duplicate or unexpected request identities")
    expected_seats = set(dataset["warmup_seat_ids"] + dataset["measured_seat_ids"])
    if len(expected_seats) != (config["warmup_waves"] + config["measured_waves"]) * config["seats_per_wave"]:
        errors.append("Dataset reuses seats between waves or has the wrong size")
    if len(dataset["user_ids"]) != config["vus"] or len(set(dataset["user_ids"])) != config["vus"]:
        errors.append("Dataset must contain one distinct user per client")

    by_booking = {row["id"]: row for row in database["bookings"]}
    seats = {row["id"]: row for row in database["seats"]}
    if set(seats) != expected_seats:
        errors.append("Database seats do not match the dataset")
    successes = []
    for row in records:
        key = (row["phase"], row["wave"], row["client"])
        if key not in expected_keys:
            continue
        if row["outcome"] not in OUTCOMES or row["outcome"] in {"transport_error", "unexpected_response"}:
            errors.append(f"Unexpected outcome for {key}")
        expected_response = {
            "success": ("201", "", None), "occupied": ("409", "SEAT_ALREADY_BOOKED", None),
            "optimistic_conflict": ("409", "OPTIMISTIC_LOCK_CONFLICT", "optimistic"),
            "serialization_failure": ("409", "SERIALIZATION_FAILURE", "serializable"),
            "lock_timeout": ("503", "PESSIMISTIC_LOCK_TIMEOUT", "pessimistic"),
        }.get(row["outcome"])
        if expected_response and ((row["status"], row["api_code"]) != expected_response[:2] or
                                  expected_response[2] not in (None, config["strategy"])):
            errors.append(f"HTTP status/code/strategy does not match the outcome for {key}")
        if (row["slot_missed"] != 0 or row["unexpected"] != 0 or row["duration_ms"] < 0 or
                row["start_lag_ms"] > config["max_start_lag_ms"] or row["start_lag_ms"] < -2 or
                row["duration_ms"] + row["start_lag_ms"] > config["wave_interval_ms"]):
            errors.append(f"Invalid timing or failed protocol check for {key}")
        if row["outcome"] == "success":
            try:
                booking_id = int(row["booking_id"])
            except ValueError:
                errors.append(f"Invalid success ID for {key}")
                continue
            successes.append(booking_id)
            ids = dataset["warmup_seat_ids"] if row["phase"] == "warmup" else dataset["measured_seat_ids"]
            seat_id = ids[row["wave"] * config["seats_per_wave"] + row["client"] % config["seats_per_wave"]]
            expected = (dataset["user_ids"][row["client"]], seat_id, "ACTIVE")
            booking = by_booking.get(booking_id)
            if not booking or (booking["user_id"], booking["seat_id"], booking["status"]) != expected:
                errors.append(f"Success response does not match a committed booking for {key}")
    if len(set(successes)) != len(successes) or set(successes) != set(by_booking):
        errors.append("Committed bookings and successful response IDs differ")

    active = Counter(row["seat_id"] for row in database["bookings"] if row["status"] == "ACTIVE")
    violations = [{"seat_id": seat_id, "active_bookings": count}
                  for seat_id, count in sorted(active.items()) if count > 1]
    mismatches = [row["id"] for row in database["seats"]
                  if row["status"] != ("BOOKED" if active[row["id"]] else "FREE")]
    if any(row["seat_id"] not in expected_seats for row in database["bookings"]):
        errors.append("Booking references a seat outside the dataset")
    phases = {phase: summarize_phase([r for r in records if r["phase"] == phase])
              for phase in ("warmup", "measurement")}
    for phase, seat_ids in (("warmup", dataset["warmup_seat_ids"]), ("measurement", dataset["measured_seat_ids"])):
        phase_violations = [row for row in violations if row["seat_id"] in set(seat_ids)]
        phases[phase]["integrity"] = {
            "violating_seats": phase_violations,
            "extra_active_bookings": sum(row["active_bookings"] - 1 for row in phase_violations),
            "seat_status_mismatches": [seat_id for seat_id in mismatches if seat_id in set(seat_ids)],
        }
    windows = phases["warmup"]["waves"] + phases["measurement"]["waves"]
    if any(a["ended_ms"] > b["started_ms"] for a, b in zip(windows, windows[1:])):
        errors.append("Wave windows overlap; scheduled waves did not remain separate")
    invariant_holds = not violations
    return {
        "pilot_only": True, "protocol_valid": not errors, "protocol_errors": errors,
        "correctness_passed": not errors and invariant_holds and not mismatches,
        "pilot_passed": not errors and not mismatches and (config["strategy"] == "naive" or invariant_holds),
        "integrity": {"invariant_holds": invariant_holds, "violating_seats": violations,
                      "extra_active_bookings": sum(row["active_bookings"] - 1 for row in violations),
                      "seat_status_mismatches": mismatches, "committed_bookings": len(by_booking)},
        "phases": phases,
        "limitations": ["Finite paced waves; active-window throughput is not sustained server capacity",
                        "Client wall-clock timings have millisecond resolution and include connection overhead",
                        "The small pilot sample cannot establish stable tail percentiles",
                        "Internal conflicts/retries are not instrumented; server retries are explicitly disabled",
                        "Observed HTTP overlap does not measure concurrent PostgreSQL transactions"],
    }
