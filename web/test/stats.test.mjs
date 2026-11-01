import { test } from 'node:test';
import assert from 'node:assert/strict';
import { connectionKind, batteryLabel, cameraOnline, HEARTBEAT_STALE_MS } from '../public/js/stats.js';

const report = (entries) => new Map(entries.map((e) => [e.id, e]));
const withTransport = (candidateType) => report([
  { id: 'T1', type: 'transport', selectedCandidatePairId: 'P1' },
  { id: 'P1', type: 'candidate-pair', localCandidateId: 'L1', remoteCandidateId: 'R1' },
  { id: 'L1', type: 'local-candidate', candidateType },
  { id: 'R1', type: 'remote-candidate', candidateType: 'host' },
]);

test('relay when selected pair local candidate is relay', () => {
  assert.equal(connectionKind(withTransport('relay')), 'relay');
});

test('direct for host/srflx/prflx local candidate', () => {
  for (const type of ['host', 'srflx', 'prflx']) assert.equal(connectionKind(withTransport(type)), 'direct');
});

test('unknown when no selected pair', () => {
  assert.equal(connectionKind(report([{ id: 'T1', type: 'transport' }])), 'unknown');
  assert.equal(connectionKind(new Map()), 'unknown');
});

test('falls back to the nominated succeeded pair when transport has no selection', () => {
  const r = report([
    { id: 'P0', type: 'candidate-pair', nominated: false, state: 'succeeded', localCandidateId: 'L0' },
    { id: 'P1', type: 'candidate-pair', nominated: true, state: 'succeeded', localCandidateId: 'L1' },
    { id: 'L0', type: 'local-candidate', candidateType: 'host' },
    { id: 'L1', type: 'local-candidate', candidateType: 'relay' },
  ]);
  assert.equal(connectionKind(r), 'relay');
});

test('batteryLabel shows percent, a bolt while charging, and flags low battery', () => {
  assert.equal(batteryLabel(null), null);
  assert.deepEqual(batteryLabel({ level: 82, charging: false }), { text: '82%', low: false });
  assert.deepEqual(batteryLabel({ level: 100, charging: true }), { text: '100% ⚡', low: false });
  assert.deepEqual(batteryLabel({ level: 15, charging: false }), { text: '15%', low: true });
  assert.deepEqual(batteryLabel({ level: 15, charging: true }), { text: '15% ⚡', low: false });
});

test('cameraOnline needs the online flag and a heartbeat that is not stale', () => {
  const now = 1_800_000_000_000;
  assert.equal(cameraOnline(null, now), false);
  assert.equal(cameraOnline({ online: false, seenAt: now }, now), false);
  assert.equal(cameraOnline({ online: true, seenAt: now - 60_000 }, now), true);
  assert.equal(cameraOnline({ online: true, seenAt: now - HEARTBEAT_STALE_MS - 1 }, now), false);
});

test('cameraOnline trusts the online flag from an app that sends no heartbeat yet', () => {
  assert.equal(cameraOnline({ online: true }, 1_800_000_000_000), true);
});
