import { test } from 'node:test';
import assert from 'node:assert/strict';
import { showStream } from '../public/js/player.js';
import { flush } from './fakes.mjs';

function fakeVideo(rejectWith) {
  return {
    srcObject: null,
    plays: 0,
    play() {
      this.plays++;
      return rejectWith ? Promise.reject(Object.assign(new Error(rejectWith), { name: rejectWith })) : Promise.resolve();
    },
  };
}

test('showStream assigns the stream once when ontrack fires for each track', async () => {
  const video = fakeVideo();
  let blocked = 0;
  const stream = { id: 'jerry' };
  showStream(video, stream, { onSoundBlocked: () => blocked++ });
  showStream(video, stream, { onSoundBlocked: () => blocked++ });
  await flush();
  assert.equal(video.srcObject, stream);
  assert.equal(video.plays, 1);
  assert.equal(blocked, 0);
});

test('an aborted play() is not treated as blocked sound', async () => {
  const video = fakeVideo('AbortError');
  let blocked = 0;
  showStream(video, { id: 'jerry' }, { onSoundBlocked: () => blocked++ });
  await flush();
  assert.equal(blocked, 0);
});

test('a NotAllowedError from play() reports blocked sound', async () => {
  const video = fakeVideo('NotAllowedError');
  let blocked = 0;
  showStream(video, { id: 'jerry' }, { onSoundBlocked: () => blocked++ });
  await flush();
  assert.equal(blocked, 1);
});
