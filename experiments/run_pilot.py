#!/usr/bin/env python3
"""Run one isolated, finite k6 pilot. No production data or admin HTTP endpoints."""

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import urlopen
import uuid
import xml.etree.ElementTree as ET
import zipfile

from pilot_results import analyze, read_samples, validate_config


ROOT = Path(__file__).resolve().parents[1]


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def capture(command, **kwargs):
    result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=kwargs.pop("timeout", 60), **kwargs)
    if result.returncode:
        raise RuntimeError(f"{command[0]} failed ({result.returncode}): {result.stderr[-3000:]}")
    return result.stdout.strip()


def logged(command, path, **kwargs):
    with path.open("w", encoding="utf-8") as log:
        timeout = kwargs.pop("timeout", None)
        process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True, **kwargs)
        try:
            return process.wait(timeout=timeout)
        except BaseException:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=10)
            raise


def java_test_gate(directory):
    reports = {p.name: ET.parse(p).getroot() for p in directory.glob("TEST-*.xml")}
    totals = {key: sum(int(r.get(key, 0)) for r in reports.values())
              for key in ("tests", "failures", "errors", "skipped")}
    lifecycle = reports.get("TEST-com.example.concurrent_booking_system.booking.service.BookingLifecycleIntegrationTest.xml")
    if (totals["tests"] < 100 or any(totals[key] for key in ("failures", "errors", "skipped")) or
            lifecycle is None or int(lifecycle.get("tests", 0)) < 16):
        raise RuntimeError(f"Full Java suite is not confirmed (at least 100 tests, including lifecycle=16): {totals}")
    return totals


def ensure_port_available(port):
    if not 1024 <= port <= 65535:
        raise ValueError("Ports must be between 1024 and 65535")
    with socket.socket() as check:
        check.bind(("127.0.0.1", port))


def seed_sql(run_id, config):
    # run_id is generated internally; all interpolated numeric values passed strict validation.
    prefix = f"pilot-{run_id}"
    return f"""BEGIN;
INSERT INTO events (name, description, start_time)
VALUES ('{prefix}', 'Isolated research pilot', CURRENT_TIMESTAMP + INTERVAL '1 day');
INSERT INTO users (email, password_hash)
SELECT '{prefix}-' || n || '@example.test', 'research-placeholder-no-auth'
FROM generate_series(1, {config['vus']}) AS n;
INSERT INTO seats (event_id, row_number, seat_number, status, price)
SELECT e.id, 'warmup', n, 'FREE', 100.00 FROM events e
CROSS JOIN generate_series(1, {config['warmup_waves'] * config['seats_per_wave']}) AS n
WHERE e.name = '{prefix}';
INSERT INTO seats (event_id, row_number, seat_number, status, price)
SELECT e.id, 'measurement', n, 'FREE', 100.00 FROM events e
CROSS JOIN generate_series(1, {config['measured_waves'] * config['seats_per_wave']}) AS n
WHERE e.name = '{prefix}';
COMMIT;
SELECT json_build_object(
  'event_id', e.id,
  'user_ids', (SELECT json_agg(u.id ORDER BY u.id)
               FROM users u WHERE u.email LIKE '{prefix}-%@example.test'),
  'warmup_seat_ids', (SELECT json_agg(s.id ORDER BY s.seat_number) FROM seats s
                     WHERE s.event_id = e.id AND s.row_number = 'warmup'),
  'measured_seat_ids', (SELECT json_agg(s.id ORDER BY s.seat_number) FROM seats s
                       WHERE s.event_id = e.id AND s.row_number = 'measurement')
) FROM events e WHERE e.name = '{prefix}';
"""


def database_sql(event_id):
    return f"""SELECT json_build_object(
  'seats', COALESCE((SELECT json_agg(s ORDER BY s.id) FROM seats s WHERE event_id = {int(event_id)}), '[]'::json),
  'bookings', COALESCE((SELECT json_agg(b ORDER BY b.id) FROM bookings b
      WHERE b.seat_id IN (SELECT id FROM seats WHERE event_id = {int(event_id)})), '[]'::json)
);"""


def stop_application(process):
    if process is None or process.poll() is not None:
        return False
    process.terminate()
    try:
        process.wait(timeout=25)
        return False
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)
        return True


def boot_jar():
    candidates = []
    for path in (ROOT / "build/libs").glob("*.jar"):
        with zipfile.ZipFile(path) as archive:
            if any(name.startswith("BOOT-INF/classes/") for name in archive.namelist()):
                candidates.append(path)
    if len(candidates) != 1:
        raise RuntimeError("Expected one bootJar in build/libs; move old application jars out of that directory")
    return candidates[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "experiments/configs/pilot.json")
    parser.add_argument("--strategy", choices=sorted({"naive", "optimistic", "pessimistic", "atomic", "serializable"}))
    parser.add_argument("--vus", type=int)
    parser.add_argument("--seats-per-wave", type=int)
    parser.add_argument("--measured-waves", type=int)
    parser.add_argument("--java", default="/usr/lib/jvm/java-21-openjdk/bin/java" if
                        Path("/usr/lib/jvm/java-21-openjdk/bin/java").exists() else "java")
    parser.add_argument("--app-port", type=int, default=8090)
    parser.add_argument("--db-port", type=int, default=5433)
    parser.add_argument("--output-root", type=Path, default=ROOT / "experiments/raw-results")
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    for key in ("strategy", "vus", "seats_per_wave", "measured_waves"):
        if getattr(args, key) is not None:
            config[key] = getattr(args, key)
    validate_config(config)
    if args.app_port == args.db_port:
        raise ValueError("Application and database ports must differ")
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:10]
    output = args.output_root.resolve() / run_id
    output.mkdir(parents=True, exist_ok=False)
    config["base_url"] = f"http://127.0.0.1:{args.app_port}"
    write_json(output / "config.json", config)
    project = "booking-pilot-" + run_id.lower()
    compose = ["docker", "compose", "--project-name", project, "--file", str(ROOT / "experiments/docker-compose.yml")]
    child_env = os.environ.copy()
    # Only the experiment child processes are affected, not the developer's shell.
    for key in list(child_env):
        if key.startswith(("K6_", "SPRING_")) or key in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}:
            del child_env[key]
    child_env.update(PILOT_DB_PORT=str(args.db_port), PILOT_POSTGRES_IMAGE=config["postgres_image"],
                     NO_PROXY="127.0.0.1,localhost", no_proxy="127.0.0.1,localhost")
    application = None
    application_log = None
    compose_requested = False
    dataset = None
    passed = False
    manifest = {"run_id": run_id, "purpose": "pilot", "compose_project": project,
                "app_port": args.app_port, "db_port": args.db_port, "started_utc": datetime.now(timezone.utc).isoformat()}
    write_json(output / "manifest.json", manifest)

    def sql(statement):
        return capture(compose + ["exec", "-T", "postgres", "psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1",
                                  "-U", "pilot_user", "-d", "pilot_db"], input=statement, env=child_env)

    try:
        print(f"[pilot] Artifacts: {output}", flush=True)
        for command in ("docker", "k6", args.java):
            if not shutil.which(command):
                raise RuntimeError(f"Required executable not found: {command}")
        java_version = subprocess.run([args.java, "-version"], capture_output=True, text=True, env=child_env, check=True)
        java_text = java_version.stdout + java_version.stderr
        if not re.search(r'version "21[.\"]', java_text):
            raise RuntimeError("Java 21 is required; set --java to its executable")
        manifest.update(java=java_text.strip(), k6=capture(["k6", "version"], env=child_env),
                        docker=capture(["docker", "version", "--format", "{{.Server.Version}}"], env=child_env),
                        compose=capture(["docker", "compose", "version"], env=child_env),
                        git_commit=capture(["git", "rev-parse", "HEAD"]),
                        git_status=capture(["git", "status", "--short"]),
                        platform=platform.platform(), logical_cpus=os.cpu_count(),
                        cpu_model=next((line.split(":", 1)[1].strip() for line in Path("/proc/cpuinfo").read_text().splitlines()
                                        if line.startswith("model name")), "unknown"),
                        memory_total=next((line for line in Path("/proc/meminfo").read_text().splitlines()
                                           if line.startswith("MemTotal:")), "unknown"))
        write_json(output / "manifest.json", manifest)
        print("[pilot] Running the complete Java test suite and bootJar (see build.log)", flush=True)
        if logged([str(ROOT / "gradlew"), "--no-daemon", "test", "bootJar"], output / "build.log", env=child_env, timeout=900):
            raise RuntimeError("Java build/tests failed; load was not started. See build.log")
        manifest["java_tests"] = java_test_gate(ROOT / "build/test-results/test")
        jar = boot_jar()
        manifest["jar_sha256"] = hashlib.sha256(jar.read_bytes()).hexdigest()
        manifest["harness_sha256"] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                      for p in [Path(__file__).resolve(), ROOT / "experiments/pilot_results.py",
                                                ROOT / "load-tests/booking-pilot.js", ROOT / "load-tests/pilot-model.mjs",
                                                ROOT / "experiments/docker-compose.yml"]}
        ensure_port_available(args.app_port)
        ensure_port_available(args.db_port)
        print("[pilot] Starting an isolated PostgreSQL instance", flush=True)
        compose_requested = True
        if logged(compose + ["up", "-d", "--wait", "--wait-timeout", "90"], output / "database-start.log",
                  env=child_env, timeout=120):
            raise RuntimeError("Experiment PostgreSQL failed to start; see database-start.log")
        manifest["postgres"] = json.loads(sql("SELECT json_build_object('version', version(), 'max_connections', "
                                              "current_setting('max_connections'), 'timezone', current_setting('TimeZone'))"))
        container_id = capture(compose + ["ps", "-q", "postgres"], env=child_env)
        manifest["postgres_image_id"] = capture(["docker", "inspect", "--format", "{{.Image}}", container_id], env=child_env)
        app_command = [args.java, f"-Xms{config['jvm_heap_mb']}m", f"-Xmx{config['jvm_heap_mb']}m", "-jar", str(jar),
                       "--server.address=127.0.0.1", f"--server.port={args.app_port}",
                       f"--spring.datasource.url=jdbc:postgresql://127.0.0.1:{args.db_port}/pilot_db",
                       "--spring.datasource.username=pilot_user", "--spring.datasource.password=pilot_password",
                       "--spring.jpa.hibernate.ddl-auto=validate", "--spring.jpa.open-in-view=false", "--spring.flyway.enabled=true",
                       f"--booking.strategy={config['strategy']}", "--booking.optimistic.max-retries=0",
                       "--booking.serializable.max-retries=0", f"--booking.pessimistic.lock-timeout-ms={config['lock_timeout_ms']}",
                       f"--spring.datasource.hikari.maximum-pool-size={config['pool_size']}",
                       f"--spring.datasource.hikari.minimum-idle={config['pool_size']}",
                       "--spring.jpa.show-sql=false", "--logging.level.org.hibernate=OFF", "--server.shutdown=graceful",
                       "--spring.lifecycle.timeout-per-shutdown-phase=15s"]
        manifest["application_command"] = app_command
        write_json(output / "manifest.json", manifest)
        application_log = (output / "application.log").open("w", encoding="utf-8")
        application = subprocess.Popen(app_command, cwd=ROOT, env=child_env,
                                       stdout=application_log, stderr=subprocess.STDOUT, start_new_session=True)
        deadline = time.monotonic() + 90
        while True:
            if application.poll() is not None:
                raise RuntimeError("Application exited before readiness; see application.log")
            try:
                with urlopen(config["base_url"] + "/api/events", timeout=1) as response:
                    if response.status == 200:
                        break
            except (URLError, TimeoutError, ConnectionError):
                pass
            if time.monotonic() > deadline:
                raise RuntimeError("Application readiness timed out; see application.log")
            time.sleep(.25)
        seed = seed_sql(run_id, config)
        (output / "seed.sql").write_text(seed, encoding="utf-8")
        dataset = json.loads(sql(seed))
        write_json(output / "dataset.json", dataset)
        print("[pilot] Running warm-up and measured waves in the same JVM", flush=True)
        write_json(output / "k6-options.json", {})
        command = ["k6", "run", "--config", str(output / "k6-options.json"), "--out", f"json={output / 'raw-metrics.jsonl'}",
                   "-e", f"PILOT_CONFIG={output / 'config.json'}", "-e", f"PILOT_DATASET={output / 'dataset.json'}",
                   "-e", f"PILOT_SUMMARY={output / 'summary.json'}", str(ROOT / "load-tests/booking-pilot.js")]
        total_seconds = (config["warmup_waves"] + config["measured_waves"]) * config["wave_interval_ms"] / 1000
        k6_code = logged(command, output / "k6.log", env=child_env, timeout=total_seconds + 60)
        app_alive = application.poll() is None
        forced_stop = stop_application(application)
        database = json.loads(sql(database_sql(dataset["event_id"])))
        write_json(output / "database.json", database)
        # The raw stream drives analysis; the independent k6 summary must also be preserved.
        json.loads((output / "summary.json").read_text(encoding="utf-8"))
        records = read_samples(output / "raw-metrics.jsonl")
        result = analyze(config, dataset, records, database)
        if k6_code or forced_stop or not app_alive:
            result["protocol_valid"] = result["pilot_passed"] = result["correctness_passed"] = False
            result["protocol_errors"].append(
                f"k6 exit={k6_code}; forced application kill={forced_stop}; application was alive={app_alive}")
        write_json(output / "result.json", result)
        with (output / "requests.csv").open("w", newline="", encoding="utf-8") as target:
            writer = csv.DictWriter(target, fieldnames=["phase", "wave", "client", "outcome", "status", "booking_id", "api_code",
                                                        "started_ms", "duration_ms", "start_lag_ms", "slot_missed", "unexpected"])
            writer.writeheader()
            writer.writerows(sorted(records, key=lambda r: (r["started_ms"], r["client"])))
        passed = result["pilot_passed"]
        print(f"[pilot] protocol_valid={result['protocol_valid']}; invariant_holds={result['integrity']['invariant_holds']}", flush=True)
    except (Exception, KeyboardInterrupt) as failure:
        write_json(output / "failure.json", {"error": str(failure) or type(failure).__name__, "load_results_confirmed": False})
        print(f"[pilot] Failed: {failure}. See {output}", file=sys.stderr, flush=True)
    finally:
        stop_application(application)
        if application is not None:
            manifest["application_exit_code"] = application.poll()
        if application_log:
            application_log.close()
        if compose_requested:
            try:
                if dataset is not None and not (output / "database.json").exists():
                    write_json(output / "database.json", json.loads(sql(database_sql(dataset["event_id"]))))
                logged(compose + ["logs", "--no-color"], output / "postgres.log", env=child_env, timeout=30)
            except Exception as failure:
                write_json(output / "snapshot-error.json", {"error": str(failure)})
            try:
                # Only this run's unique project and volume are removed; the normal Compose DB is unrelated.
                cleanup = logged(compose + ["down", "--volumes"], output / "cleanup.log", env=child_env, timeout=60)
                manifest["cleanup_exit_code"] = cleanup
                if cleanup:
                    passed = False
            except Exception as failure:
                passed = False
                write_json(output / "cleanup-error.json", {"error": str(failure)})
        manifest["finished_utc"] = datetime.now(timezone.utc).isoformat()
        manifest["pilot_passed"] = passed
        write_json(output / "manifest.json", manifest)
    return 0 if passed else 1


if __name__ == "__main__":
    def interrupted(_signum, _frame):
        raise KeyboardInterrupt()

    signal.signal(signal.SIGTERM, interrupted)
    sys.exit(main())
