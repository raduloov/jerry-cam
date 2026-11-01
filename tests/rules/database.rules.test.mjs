import { test, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} from '@firebase/rules-unit-testing';
import { renderRules } from '../../scripts/render-rules.mjs';

const template = readFileSync(new URL('../../database.rules.template.json', import.meta.url), 'utf8');
const OWNER = 'owner@example.com';
const SECOND = 'second@example.com';
let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-jerry-cam',
    database: { rules: renderRules(template, [OWNER, SECOND]), host: '127.0.0.1', port: 9000 },
  });
});
after(() => env?.cleanup());
beforeEach(() => env.clearDatabase());

const owner = () => env.authenticatedContext('owner', { email: OWNER, email_verified: true }).database();

test('owner can write and read session and camera', async () => {
  const db = owner();
  await assertSucceeds(db.ref('session').set({ id: 'abc' }));
  await assertSucceeds(db.ref('session').get());
  await assertSucceeds(db.ref('camera/online').set(true));
  await assertSucceeds(db.ref('camera/online').get());
});

test('a second allowed account can write and read session and camera', async () => {
  const db = env.authenticatedContext('second', { email: SECOND, email_verified: true }).database();
  await assertSucceeds(db.ref('session').set({ id: 'abc' }));
  await assertSucceeds(db.ref('session').get());
  await assertSucceeds(db.ref('camera').get());
});

test('other verified Google account is denied', async () => {
  const db = env.authenticatedContext('x', { email: 'stranger@example.com', email_verified: true }).database();
  await assertFails(db.ref('session').get());
  await assertFails(db.ref('session').set({ id: 'abc' }));
  await assertFails(db.ref('camera/online').get());
  await assertFails(db.ref('camera/online').set(true));
});

test('owner email but email_verified false is denied', async () => {
  const db = env.authenticatedContext('owner', { email: OWNER, email_verified: false }).database();
  await assertFails(db.ref('session').get());
  await assertFails(db.ref('camera/online').set(true));
});

test('unauthenticated is denied', async () => {
  const db = env.unauthenticatedContext().database();
  await assertFails(db.ref('session').get());
  await assertFails(db.ref('session').set({ id: 'abc' }));
  await assertFails(db.ref('camera/online').get());
});

test('root and unknown paths are denied for owner', async () => {
  const db = owner();
  await assertFails(db.ref('/').get());
  await assertFails(db.ref('other').set(1));
});

test('renderRules rejects an email containing a quote', () => {
  assert.throws(() => renderRules(template, [OWNER, "a'b@x.com"]));
});

test('renderRules rejects an empty list', () => {
  assert.throws(() => renderRules(template, []));
});
