// Status shown on the page: whether the camera phone is reachable, how the stream travels, and its battery.

const LOW_BATTERY_PERCENT = 20;
// The camera phone writes camera/seenAt every minute; this much silence means its connection is dead.
export const HEARTBEAT_STALE_MS = 150_000;

/** `now` is the server's clock in ms (local time plus .info/serverTimeOffset). */
export function cameraOnline(camera, now) {
  if (!camera?.online) return false;
  return camera.seenAt == null || now - camera.seenAt <= HEARTBEAT_STALE_MS;
}

export function connectionKind(report) {
  let pair = null;
  for (const stat of report.values()) {
    if (stat.type === 'transport' && stat.selectedCandidatePairId) pair = report.get(stat.selectedCandidatePairId);
  }
  if (!pair) {
    for (const stat of report.values()) {
      if (stat.type === 'candidate-pair' && stat.nominated && stat.state === 'succeeded') { pair = stat; break; }
    }
  }
  const local = pair && report.get(pair.localCandidateId);
  if (!local) return 'unknown';
  return local.candidateType === 'relay' ? 'relay' : 'direct';
}

export function batteryLabel(battery) {
  if (!battery) return null;
  return {
    text: `${battery.level}%${battery.charging ? ' ⚡' : ''}`,
    low: !battery.charging && battery.level < LOW_BATTERY_PERCENT,
  };
}
