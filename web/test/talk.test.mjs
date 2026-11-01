import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createTalkController } from '../public/js/talk.js';
import { flush } from './fakes.mjs';

function setup({ deny = false } = {}) {
  const track = { enabled: true, stopped: false, stop() { this.stopped = true; } };
  let resolveMic;
  let micRequests = 0;
  const attached = [];
  const muted = [];
  const states = [];
  const talk = createTalkController({
    requestMic: () => {
      micRequests++;
      return deny ? Promise.reject(new Error('NotAllowedError')) : new Promise((r) => { resolveMic = () => r(track); });
    },
    attachMic: async (t) => { attached.push(t); },
    setRoomMuted: (m) => muted.push(m),
    onState: (s) => states.push(s),
  });
  return { talk, track, attached, muted, states, resolve: () => resolveMic(), requests: () => micRequests };
}

test('first press requests mic once, attaches, enables, mutes room', async () => {
  const t = setup();
  const pressed = t.talk.press();
  t.resolve();
  await pressed;
  assert.equal(t.requests(), 1);
  assert.deepEqual(t.attached, [t.track]);
  assert.equal(t.track.enabled, true);
  assert.equal(t.muted.at(-1), true);
  assert.equal(t.states.at(-1), 'talking');
});

test('release disables mic and unmutes room', async () => {
  const t = setup();
  const pressed = t.talk.press();
  t.resolve();
  await pressed;
  t.talk.release();
  assert.equal(t.track.enabled, false);
  assert.equal(t.muted.at(-1), false);
  assert.equal(t.states.at(-1), 'idle');
});

test('second press reuses the same track', async () => {
  const t = setup();
  const first = t.talk.press();
  t.resolve();
  await first;
  t.talk.release();
  await t.talk.press();
  assert.equal(t.requests(), 1);
  assert.equal(t.track.enabled, true);
  assert.equal(t.states.at(-1), 'talking');
});

test('release before mic resolves leaves mic disabled and room unmuted', async () => {
  const t = setup();
  const pressed = t.talk.press();
  t.talk.release();
  t.resolve();
  await pressed;
  await flush();
  assert.equal(t.track.enabled, false);
  assert.notEqual(t.muted.at(-1), true);
  assert.equal(t.states.at(-1), 'idle');
});

test('mic denied → blocked, room unmuted', async () => {
  const t = setup({ deny: true });
  await t.talk.press();
  assert.equal(t.states.at(-1), 'blocked');
  assert.notEqual(t.muted.at(-1), true);
  t.talk.release();
  assert.equal(t.states.at(-1), 'blocked');
});

test('dispose stops the track', async () => {
  const t = setup();
  const pressed = t.talk.press();
  t.resolve();
  await pressed;
  t.talk.dispose();
  assert.equal(t.track.stopped, true);
});
