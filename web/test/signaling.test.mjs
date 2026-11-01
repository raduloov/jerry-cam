import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ViewerSession, OFFER_TIMEOUT_MS, viewerName } from '../public/js/signaling.js';
import { createFakeDb, FakePeer, flush } from './fakes.mjs';

const OFFER = { type: 'offer', sdp: 'OFFER' };
const ICE = [{ urls: ['turn:turn.example:3478'], username: 'u', credential: 'c' }];
const CAND = { candidate: 'candidate:1 1 udp 1 1.2.3.4 5 typ host', sdpMid: '0', sdpMLineIndex: 0 };

function setup(extra = {}) {
  const db = createFakeDb();
  const peers = [];
  const states = [];
  const streams = [];
  const timers = [];
  const session = new ViewerSession({
    db,
    id: 'v1',
    createPeerConnection: (iceServers) => { const p = new FakePeer(iceServers); peers.push(p); return p; },
    onState: (state, reason) => states.push(reason ? `${state}:${reason}` : state),
    onTrack: (stream) => streams.push(stream),
    setTimeout: (fn, ms) => timers.push({ fn, ms, cleared: false }),
    clearTimeout: (handle) => { timers[handle - 1].cleared = true; },
    ...extra,
  });
  return { db, session, peers, states, streams, timers };
}

async function withOffer(extra = {}) {
  const ctx = setup();
  await ctx.session.start();
  ctx.db.external.update('session', { offer: OFFER, iceServers: ICE, ...extra });
  await flush();
  return ctx;
}

test('start writes session with id, startedAt and live/<id>', async () => {
  const { db, session, states } = setup();
  await session.start();
  assert.deepEqual(db.log[0], ['set', 'session', { id: 'v1', startedAt: 'TS', live: { v1: true } }]);
  assert.equal(states[0], 'starting');
});

test('start tells the camera phone who is watching', async () => {
  const { db, session } = setup({ viewer: 'Ana' });
  await session.start();
  assert.deepEqual(db.log[0], ['set', 'session', { id: 'v1', startedAt: 'TS', live: { v1: true }, viewer: 'Ana' }]);
});

test('viewerName is the first name, else the email before the @', () => {
  assert.equal(viewerName({ displayName: 'Ana Petrova', email: 'ana.petrova@example.com' }), 'Ana');
  assert.equal(viewerName({ displayName: null, email: 'ana.petrova@example.com' }), 'ana.petrova');
  assert.equal(viewerName({ displayName: '  ', email: 'a@b.com' }), 'a');
  assert.equal(viewerName(null), null);
});

test('onDisconnect is scoped to session/live/<id>', async () => {
  const { db, session } = setup();
  await session.start();
  const scoped = db.log.filter(([op]) => op === 'onDisconnectRemove');
  assert.deepEqual(scoped, [['onDisconnectRemove', 'session/live/v1']]);
});

test('offer creates peer with iceServers, answers sendrecv audio', async () => {
  const { db, peers, states, streams } = await withOffer();
  assert.equal(peers.length, 1);
  assert.deepEqual(peers[0].iceServers, ICE);
  assert.deepEqual(peers[0].remoteDescription, OFFER);
  assert.equal(peers[0].audioDirectionAtAnswer, 'sendrecv');
  assert.deepEqual(db.get('session/answer'), { type: 'answer', sdp: 'ANSWER' });
  assert.ok(states.includes('connecting'));
  peers[0].ontrack({ streams: ['STREAM'] });
  assert.deepEqual(streams, ['STREAM']);
});

test('local candidates are pushed to viewerCandidates', async () => {
  const { db, peers } = await withOffer();
  peers[0].emitCandidate(CAND);
  peers[0].emitCandidate(null);
  assert.deepEqual(Object.values(db.get('session/viewerCandidates')), [CAND]);
});

test('camera candidates added only after remote description', async () => {
  const { db, peers } = await withOffer({ cameraCandidates: { c1: CAND } });
  assert.deepEqual(peers[0].addedCandidates, [CAND]);
  const later = { ...CAND, candidate: 'candidate:2' };
  db.external.set('session/cameraCandidates/c2', later);
  await flush();
  assert.deepEqual(peers[0].addedCandidates, [CAND, later]);
});

test('connected peer → live; failed peer → ended connection-failed', async () => {
  const { db, peers, states } = await withOffer();
  peers[0].setState('connected');
  assert.equal(states.at(-1), 'live');
  peers[0].setState('failed');
  await flush();
  assert.equal(states.at(-1), 'ended:connection-failed');
  assert.equal(db.get('session'), null);
});

test('session removed by camera → ended camera-ended, pc closed', async () => {
  const { db, peers, states } = await withOffer();
  db.external.set('session', null);
  await flush();
  assert.equal(states.at(-1), 'ended:camera-ended');
  assert.equal(peers[0].closed, true);
});

test('session id replaced → ended replaced, cancelOnDisconnect called, session not removed', async () => {
  const { db, peers, states } = await withOffer();
  db.external.set('session', { id: 'v2', live: { v2: true } });
  await flush();
  assert.equal(states.at(-1), 'ended:replaced');
  assert.equal(peers[0].closed, true);
  assert.ok(db.log.some(([op, path]) => op === 'cancelOnDisconnect' && path === 'session/live/v1'));
  assert.equal(db.get('session/id'), 'v2');
});

test('no offer within OFFER_TIMEOUT_MS → ended no-camera and session removed', async () => {
  const { db, session, states, timers } = setup();
  await session.start();
  assert.equal(timers.length, 1);
  assert.equal(timers[0].ms, OFFER_TIMEOUT_MS);
  assert.equal(OFFER_TIMEOUT_MS, 20_000);
  timers[0].fn();
  await flush();
  assert.equal(states.at(-1), 'ended:no-camera');
  assert.equal(db.get('session'), null);
});

test('offer arriving clears the no-camera timer', async () => {
  const { timers } = await withOffer();
  assert.equal(timers[0].cleared, true);
});

test('end() does not remove a newer session', async () => {
  const { db, session } = setup();
  await session.start();
  await session.end();
  const [, path, predicate] = db.log.find(([op]) => op === 'removeIf');
  assert.equal(path, 'session');
  assert.equal(predicate({ id: 'other' }), false);
  assert.equal(predicate(null), false);
  assert.equal(predicate({ id: 'v1' }), true);
});

test('end() twice is a no-op the second time', async () => {
  const { db, session, states } = setup();
  await session.start();
  await session.end();
  await session.end();
  assert.equal(db.log.filter(([op]) => op === 'removeIf').length, 1);
  assert.deepEqual(states.filter((s) => s.startsWith('ended')), ['ended:closed-by-viewer']);
});

test('getAudioSender returns the audio transceiver sender once connected', async () => {
  const { session, peers } = await withOffer();
  assert.equal(session.getAudioSender(), peers[0].audioSender);
});

test('getStats returns the peer stats, or an empty report before the offer', async () => {
  const { session } = setup();
  assert.equal((await session.getStats()).size, 0);
  const ctx = await withOffer();
  ctx.peers[0].getStats = async () => new Map([['T1', { type: 'transport' }]]);
  assert.equal((await ctx.session.getStats()).size, 1);
});

test('default timers are called without a receiver, as browsers require', async (t) => {
  // Browsers throw "Illegal invocation" when setTimeout/clearTimeout get a `this` other than the global object.
  const strict = (native) => function (...args) {
    if (this !== undefined && this !== globalThis) throw new TypeError('Illegal invocation');
    return native(...args);
  };
  t.mock.method(globalThis, 'setTimeout', strict(globalThis.setTimeout));
  t.mock.method(globalThis, 'clearTimeout', strict(globalThis.clearTimeout));
  const db = createFakeDb();
  const session = new ViewerSession({ db, id: 'v1', createPeerConnection: (ice) => new FakePeer(ice) });
  await session.start();
  await session.end();
  assert.equal(db.get('session'), null);
});

test('setTorch writes session/torch while the session is ours', async () => {
  const { db, session } = await withOffer();
  await session.setTorch(true);
  assert.equal(db.get('session/torch'), true);
  await session.setTorch(false);
  assert.equal(db.get('session/torch'), false);
});

test('setTorch does nothing after the session ended', async () => {
  const { db, session } = await withOffer();
  db.external.set('session', { id: 'v2', live: { v2: true } });
  await flush();
  await session.setTorch(true);
  assert.equal(db.get('session/torch'), null);
});
