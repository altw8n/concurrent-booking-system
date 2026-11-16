import test from 'node:test';
import assert from 'node:assert/strict';
import { assignment, classify } from './pilot-model.mjs';

const config = { vus: 4, seats_per_wave: 2, warmup_waves: 1, measured_waves: 2 };
const dataset = { user_ids: [10, 11, 12, 13], warmup_seat_ids: [1, 2], measured_seat_ids: [3, 4, 5, 6] };

test('waves partition fresh seats and preserve an even contention pattern', () => {
  assert.deepEqual([0, 1, 2, 3].map(c => assignment(config, dataset, 0, c).seatId), [1, 2, 1, 2]);
  assert.deepEqual([0, 1, 2, 3].map(c => assignment(config, dataset, 1, c).seatId), [3, 4, 3, 4]);
  assert.deepEqual([0, 1, 2, 3].map(c => assignment(config, dataset, 2, c).seatId), [5, 6, 5, 6]);
  assert.equal(assignment(config, dataset, 1, 0).phase, 'measurement');
  assert.equal(assignment(config, dataset, 1, 0).wave, 0);
  assert.equal(assignment(config, dataset, 2, 3).userId, 13);
});

test('low contention gives every client its own seat; extreme contention shares one', () => {
  const low = { ...config, seats_per_wave: 4 };
  const data = { ...dataset, warmup_seat_ids: [1, 2, 3, 4] };
  assert.equal(new Set([0, 1, 2, 3].map(c => assignment(low, data, 0, c).seatId)).size, 4);
  const extreme = { ...config, seats_per_wave: 1 };
  assert.equal(new Set([0, 1, 2, 3].map(c => assignment(extreme, dataset, 0, c).seatId)).size, 1);
});

test('rejects unsupported client IDs and incomplete data instead of reusing a seat', () => {
  assert.throws(() => assignment(config, dataset, 0, 4));
  assert.throws(() => assignment(config, dataset, 3, 0));
  assert.throws(() => assignment(config, { ...dataset, measured_seat_ids: [] }, 1, 0));
});

test('201 must bind to the requested user, seat, ACTIVE status and a real booking ID', () => {
  const request = { userId: 10, seatId: 3 };
  const valid = { id: 99, userId: 10, seatId: 3, status: 'ACTIVE' };
  assert.deepEqual(classify(201, JSON.stringify(valid), request, 'atomic'),
    { outcome: 'success', bookingId: '99', apiCode: '' });
  for (const invalid of [{ ...valid, seatId: 4 }, { ...valid, userId: 11 }, { ...valid, status: 'CANCELLED' },
                         { ...valid, id: null }]) {
    assert.equal(classify(201, JSON.stringify(invalid), request, 'atomic').outcome, 'unexpected_response');
  }
});

test('same HTTP 409 is separated by the JSON error code and selected strategy', () => {
  const response = code => JSON.stringify({ code });
  assert.equal(classify(409, response('SEAT_ALREADY_BOOKED'), {}, 'atomic').outcome, 'occupied');
  assert.equal(classify(409, response('OPTIMISTIC_LOCK_CONFLICT'), {}, 'optimistic').outcome, 'optimistic_conflict');
  assert.equal(classify(409, response('SERIALIZATION_FAILURE'), {}, 'serializable').outcome, 'serialization_failure');
  assert.equal(classify(409, response('OPTIMISTIC_LOCK_CONFLICT'), {}, 'atomic').outcome, 'unexpected_response');
  assert.equal(classify(409, response('UNKNOWN'), {}, 'naive').outcome, 'unexpected_response');
  assert.equal(classify(409, response('UNKNOWN'), {}, 'naive').apiCode, 'UNKNOWN');
});

test('transport errors, malformed responses and server failures cannot look like an occupied seat', () => {
  assert.equal(classify(0, '', {}, 'atomic').outcome, 'transport_error');
  assert.equal(classify(409, 'broken', {}, 'atomic').outcome, 'unexpected_response');
  assert.equal(classify(500, '{"code":"SEAT_ALREADY_BOOKED"}', {}, 'atomic').outcome, 'unexpected_response');
  assert.equal(classify(503, '{"code":"PESSIMISTIC_LOCK_TIMEOUT"}', {}, 'pessimistic').outcome, 'lock_timeout');
});
