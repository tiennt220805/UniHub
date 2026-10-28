#!/usr/bin/env node
// Mints one HS256 access token per user id, offline, for the K6 scripts (performance-load.md §2.3).
//
//   UNIHUB_SECURITY_JWT_SECRET=... PERMISSIONS=REGISTRATION_CREATE node mint-tokens.mjs ids.txt > tokens.json
//
// ids.txt: one users.id per line (the order is the order of the VUs). The secret is read from the
// environment at run time and is never written anywhere (INV-18). Claims exactly as
// auth-and-identity.md §2: sub, permissions[], iat, exp. Lifetime 30 minutes, no refresh: mint right
// before the run.
import { createHmac } from 'node:crypto';
import { readFileSync } from 'node:fs';

const secret = process.env.UNIHUB_SECURITY_JWT_SECRET;
if (!secret) {
  console.error('UNIHUB_SECURITY_JWT_SECRET is required');
  process.exit(1);
}
const permissions = (process.env.PERMISSIONS || '').split(',').filter(Boolean);
if (permissions.length === 0) {
  console.error('PERMISSIONS is required (read it from the Student role in the database)');
  process.exit(1);
}
const idsFile = process.argv[2];
if (!idsFile) {
  console.error('usage: mint-tokens.mjs <ids-file>');
  process.exit(1);
}

const b64 = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
const issuedAt = Math.floor(Date.now() / 1000);
const header = b64({ alg: 'HS256', typ: 'JWT' });

const ids = readFileSync(idsFile, 'utf8').split('\n').map((l) => l.trim()).filter(Boolean);
const tokens = ids.map((sub) => {
  const unsigned = `${header}.${b64({ sub, permissions, iat: issuedAt, exp: issuedAt + 30 * 60 })}`;
  const signature = createHmac('sha256', secret).update(unsigned).digest('base64url');
  return `${unsigned}.${signature}`;
});
process.stdout.write(JSON.stringify(tokens));
