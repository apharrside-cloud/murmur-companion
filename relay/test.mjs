// Decrypt test: a push built the way Murmur's own encrypt() builds it must come out of the relay's decryptPush() intact.
// Needs a Murmur checkout for its encrypt(): MURMUR_SRC=/path/to/murmur node relay/test.mjs (skipped when unset).
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'

if (!process.env.MURMUR_SRC) { console.log('decrypt test skipped (set MURMUR_SRC to a Murmur checkout)'); process.exit(0) }
process.env.RELAY_NO_LISTEN = '1'
process.env.RELAY_DATA = mkdtempSync(join(process.env.TMPDIR ?? tmpdir(), 'relay-test-'))
writeFileSync(join(process.env.RELAY_DATA, 'config.json'), JSON.stringify({ murmurPublicUrl: 'https://m.example.test', relayPublicUrl: 'https://r.example.test' }))
const { decryptPush } = await import('./relay.mjs')
const { encrypt } = await import(pathToFileURL(join(process.env.MURMUR_SRC, 'server', 'web-push.ts')).href)
const id = JSON.parse(readFileSync(join(process.env.RELAY_DATA, 'relay.json'), 'utf8'))

const payload = JSON.stringify({ notification: { title: 'Assistant', body: 'Réponse ✓ with unicode', tag: 'murmur:chat:x', data: { href: '/chat/default/abc', hermes: 'h1', ask: true, id: 'n1' } } })
const body = encrypt({ endpoint: 'https://x', keys: { p256dh: id.publicKey, auth: id.auth } }, Buffer.from(payload))
assert.equal(decryptPush(body), payload)
console.log('decrypt ok', body.length, 'bytes')
