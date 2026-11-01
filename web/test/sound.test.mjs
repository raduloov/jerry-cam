import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadSoundOn, saveSoundOn } from '../public/js/sound.js';

function fakeStorage() {
  const items = new Map();
  return { getItem: (k) => items.get(k) ?? null, setItem: (k, v) => items.set(k, String(v)) };
}

const throwingStorage = {
  getItem() { throw new Error('SecurityError'); },
  setItem() { throw new Error('QuotaExceededError'); },
};

test('sound is on when nothing is saved', () => {
  assert.equal(loadSoundOn(fakeStorage()), true);
});

test('the last sound choice is remembered', () => {
  const storage = fakeStorage();
  saveSoundOn(storage, false);
  assert.equal(loadSoundOn(storage), false);
  saveSoundOn(storage, true);
  assert.equal(loadSoundOn(storage), true);
});

test('missing or blocked storage leaves sound on without throwing', () => {
  assert.equal(loadSoundOn(null), true);
  assert.equal(loadSoundOn(throwingStorage), true);
  assert.doesNotThrow(() => saveSoundOn(null, false));
  assert.doesNotThrow(() => saveSoundOn(throwingStorage, false));
});
