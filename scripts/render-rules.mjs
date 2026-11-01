// Renders database.rules.json from the template, allowing only the accounts in ALLOWED_EMAILS.
// ALLOWED_EMAILS (comma-separated) comes from the environment or from .env.local.
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const EMAIL = /^[^\s'"\\@]+@[^\s'"\\@]+\.[^\s'"\\@]+$/;

export function renderRules(template, emails) {
  if (!emails?.length) throw new Error('ALLOWED_EMAILS is empty');
  for (const email of emails) {
    if (!EMAIL.test(email)) throw new Error(`ALLOWED_EMAILS has an entry that is not a plain email address: ${email}`);
  }
  const allowed = emails.map((email) => `auth.token.email === '${email}'`).join(' || ');
  return template.replaceAll('__EMAIL_ALLOWED__', allowed);
}

function allowedEmails(root) {
  let value = process.env.ALLOWED_EMAILS;
  const env = new URL('.env.local', root);
  if (!value && existsSync(env)) {
    const line = readFileSync(env, 'utf8').split('\n').find((l) => l.startsWith('ALLOWED_EMAILS='));
    value = line?.slice('ALLOWED_EMAILS='.length);
  }
  return (value ?? '').split(',').map((email) => email.trim()).filter(Boolean);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = new URL('../', import.meta.url);
  const template = readFileSync(new URL('database.rules.template.json', root), 'utf8');
  writeFileSync(new URL('database.rules.json', root), renderRules(template, allowedEmails(root)));
  console.log('wrote database.rules.json');
}
