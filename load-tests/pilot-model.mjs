// Pure functions shared by k6 and offline Node tests. No HTTP or database side effects.
export function assignment(config, dataset, iteration, client) {
  if (!Number.isInteger(client) || client < 0 || client >= config.vus ||
      !Number.isInteger(iteration) || iteration < 0 ||
      iteration >= config.warmup_waves + config.measured_waves) {
    throw new Error('Only a single local scenario with VU IDs 1..vus is supported');
  }
  const warmup = iteration < config.warmup_waves;
  const phase = warmup ? 'warmup' : 'measurement';
  const wave = warmup ? iteration : iteration - config.warmup_waves;
  const seats = warmup ? dataset.warmup_seat_ids : dataset.measured_seat_ids;
  const seatId = seats[wave * config.seats_per_wave + client % config.seats_per_wave];
  const userId = dataset.user_ids[client];
  if (!Number.isSafeInteger(seatId) || !Number.isSafeInteger(userId)) {
    throw new Error('Dataset does not match the workload configuration');
  }
  return { phase, wave, seatId, userId };
}

export function classify(status, body, request, strategy) {
  if (status === 0) return { outcome: 'transport_error', bookingId: '', apiCode: '' };
  let data;
  try {
    data = JSON.parse(body);
  } catch (_) {
    return { outcome: 'unexpected_response', bookingId: '', apiCode: 'INVALID_JSON' };
  }
  const apiCode = data && typeof data.code === 'string' ? data.code.slice(0, 100) : '';
  if (status === 201 && data && Number.isSafeInteger(data.id) && data.id > 0 &&
      data.seatId === request.seatId && data.userId === request.userId && data.status === 'ACTIVE') {
    return { outcome: 'success', bookingId: String(data.id), apiCode };
  }
  const allowed = {
    SEAT_ALREADY_BOOKED: [409, 'occupied', null],
    OPTIMISTIC_LOCK_CONFLICT: [409, 'optimistic_conflict', 'optimistic'],
    SERIALIZATION_FAILURE: [409, 'serialization_failure', 'serializable'],
    PESSIMISTIC_LOCK_TIMEOUT: [503, 'lock_timeout', 'pessimistic'],
  };
  const match = data && allowed[data.code];
  if (match && match[0] === status && (!match[2] || match[2] === strategy)) {
    return { outcome: match[1], bookingId: '', apiCode };
  }
  return { outcome: 'unexpected_response', bookingId: '', apiCode };
}
