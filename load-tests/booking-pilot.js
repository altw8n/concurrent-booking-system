import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { assignment, classify } from './pilot-model.mjs';

const config = JSON.parse(open(__ENV.PILOT_CONFIG));
const dataset = JSON.parse(open(__ENV.PILOT_DATASET));
const totalWaves = config.warmup_waves + config.measured_waves;
const operationMs = new Trend('booking_operation_ms', true);
const startedAtMs = new Trend('booking_started_at_ms');
const startLagMs = new Trend('booking_start_lag_ms', true);
const missedSlot = new Rate('booking_slot_missed');
const unexpected = new Rate('booking_unexpected');

export const options = {
  scenarios: {
    pilot: {
      executor: 'per-vu-iterations',
      vus: config.vus,
      iterations: totalWaves,
      maxDuration: `${totalWaves * config.wave_interval_ms + 1000}ms`,
      gracefulStop: '0s',
    },
  },
  // Avoid a separate built-in time series for every seat URL.
  systemTags: ['status', 'method', 'name', 'scenario', 'expected_response', 'error_code'],
  summaryTrendStats: ['count', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  thresholds: {
    booking_unexpected: ['rate==0'],
    booking_slot_missed: ['rate==0'],
  },
};

// These HTTP statuses still undergo strict JSON/outcome validation below.
http.setResponseCallback(http.expectedStatuses(201, 409, 503));

export default function () {
  const client = exec.vu.idInTest - 1;
  const iteration = exec.vu.iterationInScenario;
  const target = exec.scenario.startTime + iteration * config.wave_interval_ms;
  const waitMs = target - Date.now();
  if (waitMs > 0) sleep(waitMs / 1000);
  const request = assignment(config, dataset, iteration, client);
  const started = Date.now();
  const response = http.post(
    `${config.base_url}/api/events/${dataset.event_id}/seats/${request.seatId}/book`,
    JSON.stringify({ userId: request.userId }),
    {
      headers: { 'Content-Type': 'application/json' },
      timeout: `${config.request_timeout_ms}ms`,
      redirects: 0,
      tags: { name: 'book_seat', phase: request.phase },
    },
  );
  const ended = Date.now();
  const result = classify(response.status, response.body, request, config.strategy);
  // Request identity is retained in the local raw JSON only, not exported to Prometheus.
  const tags = {
    phase: request.phase, wave: String(request.wave), client: String(client),
    outcome: result.outcome, booking_id: result.bookingId, status: String(response.status), api_code: result.apiCode,
  };
  operationMs.add(ended - started, tags);
  startedAtMs.add(started, tags);
  startLagMs.add(started - target, tags);
  missedSlot.add(started - target > config.max_start_lag_ms ||
    ended > target + config.wave_interval_ms, tags);
  unexpected.add(result.outcome === 'unexpected_response' || result.outcome === 'transport_error', tags);
}

export function handleSummary(data) {
  return {
    [__ENV.PILOT_SUMMARY]: JSON.stringify(data, null, 2),
    stdout: 'Pilot finished. Raw metrics and the database check are processed by experiments/run_pilot.py.\n',
  };
}
