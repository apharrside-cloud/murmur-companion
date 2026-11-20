// node --test relay/config.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { loadConfig, validate, capabilities, pairingCode, pairingLink, readPairingCode, isLocalDirect, DEFAULTS } from './config.mjs'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const GOOD = { murmurPublicUrl: 'https://box.example.ts.net:8444/', relayPublicUrl: 'https://box.example.ts.net:8445' }
const tmp = (obj) => { const f = join(mkdtempSync(join(tmpdir(), 'cfg-')), 'config.json'); writeFileSync(f, typeof obj === 'string' ? obj : JSON.stringify(obj)); return f }

test('defaults need setup: the two https addresses are required', () => {
  const c = loadConfig({ env: {}, file: undefined })
  const { errors } = validate(c)
  assert.equal(errors.length, 2)
  assert.match(errors.join(' '), /murmurPublicUrl/)
  assert.match(errors.join(' '), /relayPublicUrl/)
})

test('a minimal config is valid, trailing slashes trimmed, defaults filled in', () => {
  const c = loadConfig({ env: {}, file: tmp(GOOD) })
  assert.deepEqual(validate(c).errors, [])
  assert.equal(c.murmurPublicUrl, 'https://box.example.ts.net:8444')
  assert.equal(c.assistantName, 'Hermes')
  assert.equal(c.port, 3010)
  assert.equal(c.threads.enabled, false)
  assert.equal(c.capture.todoist, null)
})

test('environment overrides the file', () => {
  const c = loadConfig({ env: { RELAY_PORT: '4000', MURMUR_URL: 'http://127.0.0.1:9999/' }, file: tmp(GOOD) })
  assert.equal(c.port, 4000); assert.equal(c.murmurUrl, 'http://127.0.0.1:9999')
})

test('plain http, bad json, bad regex are reported in words', () => {
  assert.match(validate(loadConfig({ env: {}, file: tmp({ ...GOOD, relayPublicUrl: 'http://192.168.1.5:3010' }) })).errors[0], /https/)
  assert.match(validate(loadConfig({ env: {}, file: tmp('{nope') })).errors[0], /not valid JSON/)
  const bad = validate(loadConfig({ env: {}, file: tmp({ ...GOOD, capture: { areas: [{ name: 'X', match: '(' }] } }) }))
  assert.match(bad.errors.join(), /regular expression/)
})

test('nested settings merge instead of replacing the defaults', () => {
  const c = loadConfig({ env: {}, file: tmp({ ...GOOD, threads: { enabled: true } }) })
  assert.equal(c.threads.enabled, true); assert.equal(c.threads.model, '')
})

test('capabilities reflect what is configured', () => {
  const off = capabilities(loadConfig({ env: {}, file: tmp(GOOD) }))
  assert.deepEqual(off.capture, { chat: true, tasks: false, notes: false })
  assert.equal(off.threads, false); assert.equal(off.stat, null); assert.equal(off.tasklists, false)
  const on = capabilities(loadConfig({ env: {}, file: tmp({ ...GOOD, assistantName: 'Atlas', threads: { enabled: true }, tasklists: [{ file: 'x.md' }], stat: { label: 'Balance', file: 'b.json' }, capture: { notesDir: '/n' } }) }), '1.2.3')
  assert.equal(on.assistantName, 'Atlas'); assert.equal(on.version, '1.2.3'); assert.equal(on.capture.notes, true)
  assert.equal(on.threads, true); assert.deepEqual(on.stat, { label: 'Balance' }); assert.equal(on.tasklists, true)
})

test('pairing code round-trips and carries no more than the app needs', () => {
  const c = loadConfig({ env: {}, file: tmp({ ...GOOD, assistantName: 'Atlas', murmurPassword: 'secret', capture: { todoist: { envFile: 'C:/x/.env' } } }) })
  const code = pairingCode(c, 'tok123')
  assert.deepEqual(readPairingCode(code), { v: 1, r: 'https://box.example.ts.net:8445', t: 'tok123', m: 'https://box.example.ts.net:8444', n: 'Atlas' })
  assert.ok(!Buffer.from(code, 'base64url').toString().includes('secret'))
  assert.ok(pairingLink(c, 'tok123').startsWith('murmurcompanion://pair?c='))
  assert.equal(readPairingCode('garbage'), null)
  assert.equal(readPairingCode(Buffer.from('{"v":2}').toString('base64url')), null)
})

test('only a direct local request may open the pairing page', () => {
  const req = (addr, headers = {}) => ({ socket: { remoteAddress: addr }, headers })
  assert.equal(isLocalDirect(req('127.0.0.1')), true)
  assert.equal(isLocalDirect(req('::1')), true)
  assert.equal(isLocalDirect(req('::ffff:127.0.0.1')), true)
  assert.equal(isLocalDirect(req('192.168.1.9')), false)
  assert.equal(isLocalDirect(req('127.0.0.1', { 'x-forwarded-for': '9.9.9.9' })), false)
  assert.equal(isLocalDirect(req('127.0.0.1', { 'tailscale-user-login': 'a@b' })), false)
  assert.equal(isLocalDirect(req('127.0.0.1', { 'cf-connecting-ip': '1.1.1.1' })), false)
})

test('DEFAULTS is frozen at the top level', () => { assert.throws(() => { 'use strict'; DEFAULTS.port = 1 }) })
