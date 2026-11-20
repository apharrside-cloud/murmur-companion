// Murmur Native relay: the PC half of the Murmur Android app.
//
// Murmur's server already notices what agents do (replies finished, questions
// waiting) and tells every registered "device" by Web Push. This relay
// registers itself as one such device, so Murmur needs no patch, then:
//   - decrypts each push and hands it to the phone app over SSE (/events)
//   - answers the app's widget calls (/api/agents, /api/last, /api/cards)
// It is reached only through Tailscale Serve (tailnet only) and every call
// except the push inbox needs the bearer token in data/relay.json.
//
// Run: node relay.mjs            (settings: data/config.json, see docs/CONFIG.md)
//      node relay.mjs init ...   write a first config.json
//      node relay.mjs pair       print the pairing code for the phone app
//      node relay.mjs doctor     check Murmur, Hermes and the public address

import http from 'node:http'
import { createECDH, createDecipheriv, createHash, hkdfSync, randomBytes, timingSafeEqual } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync, existsSync, appendFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { recentTurns, buildPrompt, parseVerdict, candidates, rankThreads, tomorrowMorning, agoString } from './threads.mjs'
import { execFile } from 'node:child_process'
import { parseTasklist, parseCapture, dueString, normalizeStat, isoDay } from './lib.mjs'
import { loadConfig, validate, capabilities, pairingCode, pairingLink, isLocalDirect } from './config.mjs'
import { pairHtml } from './pair-page.mjs'
import { pathToFileURL } from 'node:url'
import { resolve as resolvePath } from 'node:path'

const HERE = dirname(fileURLToPath(import.meta.url))
const DATA = process.env.RELAY_DATA ?? join(HERE, 'data')
mkdirSync(DATA, { recursive: true })
const CFG = loadConfig({ env: process.env, file: join(DATA, 'config.json') })
const PORT = CFG.port
const MURMUR = CFG.murmurUrl
const MURMUR_PUBLIC = CFG.murmurPublicUrl
const RELAY_PUBLIC = CFG.relayPublicUrl
const MURMUR_HOST = (() => { try { return new URL(MURMUR_PUBLIC).host } catch { return '' } })()
const VERSION = (() => { try { return JSON.parse(readFileSync(join(HERE, 'package.json'), 'utf8')).version } catch { return '' } })()
const KEEP = 100
const ASK_TTL = 60 * 60 * 1000

const b64u = (buf) => Buffer.from(buf).toString('base64url')
const log = (...a) => console.log(new Date().toISOString(), ...a)

// ── Identity: token + the push keys Murmur encrypts to ──────────────────────

mkdirSync(DATA, { recursive: true })
const identityFile = join(DATA, 'relay.json')
let identity
if (existsSync(identityFile)) identity = JSON.parse(readFileSync(identityFile, 'utf8'))
else {
  const ecdh = createECDH('prime256v1')
  ecdh.generateKeys()
  identity = {
    token: randomBytes(24).toString('hex'),
    inbox: randomBytes(16).toString('hex'),
    privateKey: b64u(ecdh.getPrivateKey()),
    publicKey: b64u(ecdh.getPublicKey()),
    auth: b64u(randomBytes(16)),
  }
  writeFileSync(identityFile, JSON.stringify(identity, null, 2), { mode: 0o600 })
  log('created identity')
}
const endpoint = `${RELAY_PUBLIC}/inbox/${identity.inbox}`
const deviceId = createHash('sha256').update(endpoint).digest('base64url').slice(0, 22)

// ── Web Push (RFC 8291, aes128gcm) decrypt ──────────────────────────────────

export function decryptPush(body, id = identity) {
  const salt = body.subarray(0, 16)
  const idlen = body[20]
  const asPublic = body.subarray(21, 21 + idlen)
  const cipher = body.subarray(21 + idlen)
  const ecdh = createECDH('prime256v1')
  ecdh.setPrivateKey(Buffer.from(id.privateKey, 'base64url'))
  const uaPublic = Buffer.from(id.publicKey, 'base64url')
  const secret = ecdh.computeSecret(asPublic)
  const keyInfo = Buffer.concat([Buffer.from('WebPush: info\0'), uaPublic, asPublic])
  const ikm = Buffer.from(hkdfSync('sha256', secret, Buffer.from(id.auth, 'base64url'), keyInfo, 32))
  const cek = Buffer.from(hkdfSync('sha256', ikm, salt, Buffer.from('Content-Encoding: aes128gcm\0'), 16))
  const nonce = Buffer.from(hkdfSync('sha256', ikm, salt, Buffer.from('Content-Encoding: nonce\0'), 12))
  const d = createDecipheriv('aes-128-gcm', cek, nonce)
  d.setAuthTag(cipher.subarray(cipher.length - 16))
  const plain = Buffer.concat([d.update(cipher.subarray(0, cipher.length - 16)), d.final()])
  let end = plain.length
  while (end > 0 && plain[end - 1] === 0) end--
  return plain.subarray(0, end - 1).toString('utf8') // drop the 0x02 delimiter
}

// ── Notes: kept for catch-up, streamed to the phone ─────────────────────────

let seq = Number(existsSync(join(DATA, 'seq')) ? readFileSync(join(DATA, 'seq'), 'utf8') : 0) || Date.now()
const notes = [] // newest last
const clients = new Set()
const openAsks = new Map() // tag -> { at, note }

function publish(note) {
  const n = { id: ++seq, at: Date.now(), ...note }
  notes.push(n)
  while (notes.length > KEEP) notes.shift()
  if (n.ask) openAsks.set(n.tag, { at: n.at, note: n })
  else openAsks.delete(n.tag)
  try { writeFileSync(join(DATA, 'seq'), String(seq)) } catch { /* ignore */ }
  const frame = `id: ${n.id}\nevent: note\ndata: ${JSON.stringify(n)}\n\n`
  for (const res of clients) res.write(frame)
  log('note', n.id, n.ask ? 'ASK' : '', JSON.stringify(n.title), `-> ${clients.size} client(s)`)
  return n
}

function fromPush(json) {
  const p = JSON.parse(json)
  const n = p.notification ?? p
  return {
    title: String(n.title ?? 'Murmur'),
    body: String(n.body ?? ''),
    tag: String(n.tag ?? ''),
    href: String(n.data?.href ?? '/'),
    hermes: String(n.data?.hermes ?? ''),
    ask: Boolean(n.data?.ask),
    sentId: String(n.data?.id ?? ''),
  }
}

// ── Murmur registration ─────────────────────────────────────────────────────

let session = '' // Murmur's sign-in cookie; only used when Murmur has a password
async function signIn() {
  if (!CFG.murmurPassword) return ''
  const res = await fetch(MURMUR + '/murmur/login', {
    method: 'POST',
    headers: { origin: MURMUR_PUBLIC, 'x-forwarded-host': MURMUR_HOST, 'x-forwarded-proto': 'https', 'content-type': 'application/json' },
    body: JSON.stringify({ password: CFG.murmurPassword }),
    signal: AbortSignal.timeout(15000),
  })
  if (!res.ok) throw new Error(`Murmur sign-in failed (HTTP ${res.status}). Check murmurPassword in config.json.`)
  const cookies = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : [res.headers.get('set-cookie') ?? '']
  session = cookies.map((c) => c.split(';')[0]).find((c) => c.startsWith('murmur_session=')) ?? ''
  if (!session) throw new Error('Murmur sign-in gave no session cookie.')
  return session
}
const ensureSession = async () => (CFG.murmurPassword && !session ? signIn() : session)
const proxyHeaders = () => ({ origin: MURMUR_PUBLIC, 'x-forwarded-host': MURMUR_HOST, 'x-forwarded-proto': 'https', ...(session ? { cookie: session } : {}) })

async function murmur(path, init = {}, retry = true) {
  await ensureSession()
  const res = await fetch(MURMUR + path, { ...init, headers: { ...proxyHeaders(), 'content-type': 'application/json', ...init.headers }, signal: AbortSignal.timeout(15000) })
  if ((res.status === 401 || res.status === 403) && CFG.murmurPassword && retry) { session = ''; return murmur(path, init, false) }
  const text = await res.text()
  let json
  try { json = JSON.parse(text) } catch { json = undefined }
  return { status: res.status, json, text }
}

async function ensureRegistered() {
  try {
    const known = await murmur(`/murmur/notifications?device=${deviceId}`)
    if (known.json?.known) return
    const reg = await murmur('/murmur/notifications/devices', {
      method: 'POST',
      body: JSON.stringify({ subscription: { endpoint, keys: { p256dh: identity.publicKey, auth: identity.auth } } }),
    })
    log('registered with Murmur', reg.status, reg.text.slice(0, 120))
  } catch (e) {
    log('registration check failed:', e.message)
  }
}

// ── Widget data ─────────────────────────────────────────────────────────────

const H = (p) => murmur('/hermes' + p).then((r) => r.json)

async function agents() {
  const d = await H('/api/profiles')
  return (d?.profiles ?? []).map((p) => ({ id: p.name, name: p.bot_title || p.display_name || p.name, isDefault: !!p.is_default }))
}

const plain = (md) =>
  md
    .replace(/```[\s\S]*?(```|$)/g, ' ')
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/^\s*(#{1,6}\s+|>\s?|[-*+]\s+|\d+[.)]\s+)/gm, '')
    .replace(/(\*\*|__|~~|`)/g, '')
    .replace(/\|/g, ' ')
    .replace(/\n{2,}/g, '\n')
    .trim()

async function lastReply(agent = CFG.defaultProfile) {
  const list = await H(`/api/sessions?profile=${encodeURIComponent(agent)}&limit=8&order=recent&min_messages=1&exclude_sources=cron,kanban,tool,oneshot`)
  for (const s of list?.sessions ?? []) {
    const total = s.message_count ?? 0
    const offset = Math.max(0, total - 30)
    const m = await H(`/api/sessions/${s.id}/messages?profile=${encodeURIComponent(agent)}&limit=30&offset=${offset}`)
    const msgs = (m?.messages ?? []).filter((x) => x.role === 'assistant' && typeof x.content === 'string' && x.content.trim())
    const last = msgs[msgs.length - 1]
    if (last) {
      const at = last.timestamp ?? last.created_at ?? s.last_activity_at
      return { agent, text: plain(last.content).slice(0, 700), at: at ? Math.round(Number(at) * (Number(at) > 1e12 ? 1 : 1000)) : Date.now(), title: s.title ?? '', href: `/chat/${encodeURIComponent(agent)}/${encodeURIComponent(s.id)}` }
    }
  }
  return { agent, text: '', at: 0, title: '', href: `/chat/${encodeURIComponent(agent)}/new` }
}

function tasks() {
  const out = { open: 0, overdue: 0, today: 0 }
  const iso = isoDay(new Date())
  for (const l of CFG.tasklists) {
    let text = ''
    try { text = readFileSync(l.file, 'utf8') } catch { continue }
    for (const t of parseTasklist(text, l.name ?? '')) {
      out.open++
      if (t.due && t.due < iso) out.overdue++
      else if (t.due === iso) out.today++
    }
  }
  return out
}

async function cards() {
  const now = Date.now()
  for (const [tag, v] of openAsks) if (now - v.at > ASK_TTL) openAsks.delete(tag)
  let working = 0
  let gateway = 'unknown'
  let chatsToday = 0
  try {
    const status = await H('/api/status')
    gateway = status?.gateway_state ?? 'unknown'
    const recent = await H(`/api/sessions?profile=${CFG.defaultProfile}&limit=40&order=recent&min_messages=1&exclude_sources=cron,kanban,tool,oneshot`)
    for (const s of recent?.sessions ?? []) {
      const la = (s.last_activity_at ?? 0) * 1000
      if (now - la < 60_000 && !s.ended_at) working++
      if (la > now - 86_400_000) chatsToday++
    }
  } catch { /* leave defaults */ }
  const t = tasks()
  let approvals = openAsks.size
  try { approvals = (await waitingAsks()).length } catch { /* fall back to the pushes seen */ }
  return {
    approvals,
    working,
    chatsToday,
    gateway,
    tasksOpen: t.open,
    tasksOverdue: t.overdue,
    tasksToday: t.today,
    at: now,
  }
}

// ── Hermes socket: what is waiting on the person, and answering approvals ───
// Murmur's own server only reads this (it never answers for the person). The
// relay is the person's own app talking, so it may answer an approval.

const LATEST = Number.MAX_SAFE_INTEGER
const SECRET_ASKS = new Set(['vault.save_login', 'vault.code', 'secret'])

async function hermesWs(work, timeoutMs = 10_000) {
  await ensureSession()
  return new Promise((resolve, reject) => {
    let ws
    let settled = false
    const waiting = new Map()
    let n = 0
    const end = (fn, v) => { if (settled) return; settled = true; clearTimeout(timer); try { ws.close() } catch { /* gone */ } fn(v) }
    const timer = setTimeout(() => end(reject, new Error('hermes socket timeout')), timeoutMs)
    try { ws = new WebSocket(MURMUR.replace(/^http/, 'ws') + '/hermes/api/ws', { headers: proxyHeaders() }) } catch (e) { clearTimeout(timer); return reject(e) }
    const call = (method, params = {}) => new Promise((res, rej) => { const id = ++n; waiting.set(id, { res, rej }); ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params })) })
    ws.onmessage = (e) => {
      let d; try { d = JSON.parse(String(e.data)) } catch { return }
      const w = d.id != null ? waiting.get(d.id) : undefined
      if (!w) return
      waiting.delete(d.id)
      if (d.error) w.rej(new Error(d.error.message ?? 'rpc error')); else w.res(d.result)
    }
    ws.onerror = () => end(reject, new Error('hermes socket error'))
    ws.onclose = () => end(reject, new Error('hermes socket closed'))
    const send = (frame) => ws.send(JSON.stringify(frame))
    ws.onopen = () => work(call, send).then((v) => end(resolve, v), (e) => end(reject, e))
  })
}

/** Every approval / question / login the agents are waiting on right now. */
function waitingAsks() {
  return hermesWs(async (call) => {
    const live = await call('session.active_list', {})
    const out = []
    for (const s of live?.sessions ?? []) {
      if (s.status !== 'waiting') continue
      const ev = await call('session.events.since', { session_id: s.id, last_seen: LATEST })
      for (const r of ev?.open_requests ?? []) {
        if (r.method === 'approval' || r.method === 'clarify' || SECRET_ASKS.has(r.method)) {
          out.push({ sessionId: s.id, key: s.session_key, title: s.title ?? '', requestId: r.id, method: r.method, params: r.params ?? {}, choices: choicesOf(r) })
        }
      }
    }
    return out
  })
}

/** The tappable answers of a plain one-question clarify (3 at most): what a notification button can send. */
function choicesOf(r) {
  if (r.method !== 'clarify') return []
  const qs = r.params?.questions
  if (!Array.isArray(qs) || qs.length !== 1 || qs[0]?.multi_select) return []
  const c = Array.isArray(qs[0].choices) ? qs[0].choices.filter((x) => typeof x === 'string' && x.trim()) : []
  return c.length && c.length <= 3 ? c : []
}
const choiceLabel = (c) => clip(c.replace(/\s*\(recommended\)\s*$/i, '').trim(), 24)
const clip = (t, n) => (t.length > n ? t.slice(0, n - 1) + '…' : t)
function askSummary(a) {
  if (a.method === 'approval') {
    const cmd = typeof a.params.command === 'string' ? a.params.command.trim() : ''
    return cmd ? `Wants to run “${clip(cmd.split('\n')[0], 160)}”` : 'Needs your OK'
  }
  if (a.method === 'clarify') {
    const q = Array.isArray(a.params.questions) ? a.params.questions[0]?.question : a.params.question
    return clip(String(q ?? 'Has a question for you'), 200)
  }
  return 'Needs a login or key in Murmur'
}
const kindOf = (a) => (a.method === 'approval' ? 'approval' : a.method === 'clarify' ? 'question' : 'secret')

/** Answer the oldest approval a chat is waiting on. choice: once | deny */
function respond(key, choice) {
  return hermesWs(async (call) => {
    const live = await call('session.active_list', {})
    const s = (live?.sessions ?? []).find((x) => x.session_key === key || x.id === key)
    if (!s) return { ok: false, error: 'not_waiting' }
    const ev = await call('session.events.since', { session_id: s.id, last_seen: LATEST })
    const r = (ev?.open_requests ?? []).find((x) => x.method === 'approval')
    if (!r) return { ok: false, error: 'no_approval' }
    const params = { session_id: s.id, choice }
    if (typeof r.params?.request_id === 'string' && r.params.request_id) params.request_id = r.params.request_id
    const res = await call('approval.respond', params)
    return { ok: (res?.resolved ?? 0) > 0, resolved: res?.resolved ?? 0 }
  })
}

/** Tap one of a clarify's choices: answered by replying to Hermes's own request, as the Murmur card does. */
function pick(key, index) {
  return hermesWs(async (call, send) => {
    const live = await call('session.active_list', {})
    const s = (live?.sessions ?? []).find((x) => x.session_key === key || x.id === key)
    if (!s) return { ok: false, error: 'not_waiting' }
    const ev = await call('session.events.since', { session_id: s.id, last_seen: LATEST })
    const r = (ev?.open_requests ?? []).find((x) => x.method === 'clarify')
    const choices = r ? choicesOf(r) : []
    if (!r || !choices[index]) return { ok: false, error: 'no_such_choice' }
    send({ jsonrpc: '2.0', id: r.id, result: { answers: { [r.params.questions[0].qid]: choices[index] } } })
    await new Promise((res) => setTimeout(res, 1500))
    const after = await call('session.events.since', { session_id: s.id, last_seen: LATEST })
    const still = (after?.open_requests ?? []).some((x) => x.id === r.id)
    return { ok: !still, answered: choices[index] }
  })
}

const chatKeyOf = (tag) => (tag.startsWith('murmur:chat:') ? tag.slice('murmur:chat:'.length) : '')
const hrefParts = (href) => { const m = /^\/chat\/([^/]+)\/([^/?]+)/.exec(href); return m ? { profile: decodeURIComponent(m[1]), id: decodeURIComponent(m[2]) } : null }

/** A note from a routine's own run (source "cron"): those are collapsed into one quiet line on the phone. */
async function isRunNote(note) {
  const p = hrefParts(note.href)
  if (!p) return false
  try {
    const s = await H(`/api/sessions/${encodeURIComponent(p.id)}?profile=${encodeURIComponent(p.profile)}`)
    return s?.source === 'cron'
  } catch { return false }
}

async function classifyAsk(note) {
  if (note.tag.startsWith('murmur:group:')) return { kind: 'group' }
  const key = chatKeyOf(note.tag)
  try {
    const found = (await waitingAsks()).find((a) => a.key === key)
    return { kind: found ? kindOf(found) : 'unknown', choices: (found?.choices ?? []).map(choiceLabel) }
  } catch { return { kind: 'unknown' } }
}

// ── Todoist (optional filing target for voice capture) ──────────────────────

let todoistTok = ''
function todoistToken() {
  if (todoistTok) return todoistTok
  const t = CFG.capture.todoist
  if (!t) return ''
  const name = t.tokenEnv || 'TODOIST_API_TOKEN'
  todoistTok = process.env[name] ?? ''
  if (!todoistTok && t.envFile) {
    try { todoistTok = new RegExp(`^${name}=(.*)$`, 'm').exec(readFileSync(t.envFile, 'utf8'))?.[1]?.trim().replace(/^["']|["']$/g, '') ?? '' } catch { /* none */ }
  }
  return todoistTok
}
async function todo(path, init = {}) {
  const tok = todoistToken()
  if (!tok) throw new Error('no Todoist token')
  const res = await fetch('https://api.todoist.com/api/v1' + path, { ...init, headers: { authorization: 'Bearer ' + tok, 'content-type': 'application/json', ...init.headers }, signal: AbortSignal.timeout(15000) })
  const text = await res.text()
  let json; try { json = text ? JSON.parse(text) : undefined } catch { json = undefined }
  return { status: res.status, ok: res.ok, json }
}

let targetCache = null
async function todoTargets() {
  if (targetCache && Date.now() - targetCache.at < 3_600_000) return targetCache.map
  const list = (r) => (Array.isArray(r.json) ? r.json : r.json?.results ?? [])
  const projects = list(await todo('/projects?limit=100'))
  const sections = list(await todo('/sections?limit=100'))
  const map = {}
  const inbox = projects.find((x) => x.inbox_project)
  if (inbox) map.Inbox = { projectId: inbox.id, label: 'Inbox' }
  for (const a of CFG.capture.areas) {
    if (!a.todoistProject) continue
    const p = projects.find((x) => x.name === a.todoistProject)
    if (!p) continue
    const sec = a.todoistSection ? sections.find((x) => x.project_id === p.id && x.name === a.todoistSection) : undefined
    map[a.name] = { projectId: p.id, sectionId: sec?.id, sectionName: sec?.name, label: a.label || a.name }
  }
  targetCache = { at: Date.now(), map }
  return map
}

// ── Needs you ───────────────────────────────────────────────────────────────

const ownerCache = new Map()
async function ownerOf(key) {
  if (ownerCache.has(key)) return ownerCache.get(key)
  for (const a of await agents().catch(() => [])) {
    const list = await H(`/api/sessions?profile=${encodeURIComponent(a.id)}&limit=40&order=recent&min_messages=0`).catch(() => null)
    if ((list?.sessions ?? []).some((s) => s.id === key)) { const o = { profile: a.id, name: a.name }; ownerCache.set(key, o); return o }
  }
  return { profile: CFG.defaultProfile, name: CFG.assistantName }
}

// ── Open threads: what we're in the middle of, read from recent chats ───────────

const threadFile = join(DATA, 'threads.json')
const readThreads = () => { try { return JSON.parse(readFileSync(threadFile, 'utf8')) } catch { return { cache: {}, dismissed: {}, done: {}, snoozed: {} } } }
let threadState = readThreads()
threadState.done ??= {}
threadState.snoozed ??= {}
const saveThreads = () => writeFileSync(threadFile, JSON.stringify(threadState))

function askModel(prompt) {
  const args = ['-z', prompt, '-t', '']
  if (CFG.threads.model) args.push('-m', CFG.threads.model)
  if (CFG.threads.provider) args.push('--provider', CFG.threads.provider)
  return new Promise((resolve) => {
    const child = execFile(CFG.hermesBin, args, { timeout: 90_000, windowsHide: true, maxBuffer: 1 << 20 }, (err, out) => resolve(err ? null : String(out)))
    child.stdin?.end()
  })
}

let scanning = false
async function scanThreads() {
  if (scanning || !CFG.threads.enabled) return
  scanning = true
  try {
    const all = []
    for (const a of await agents()) {
      const d = await H(`/api/sessions?profile=${encodeURIComponent(a.id)}&order=recent&min_messages=4&exclude_sources=cron,oneshot,kanban&limit=40`).catch(() => null)
      for (const x of d?.sessions ?? []) all.push({ ...x, profile: a.id, agent: a.name })
    }
    // keep only the newest chat of each compaction chain
    const todo = candidates(all, threadState.cache, Date.now()).slice(0, 6)
    for (const sess of todo) {
      const total = sess.message_count ?? 40
      const m = await H(`/api/sessions/${encodeURIComponent(sess.id)}/messages?profile=${encodeURIComponent(sess.profile)}&limit=60&offset=${Math.max(0, total - 60)}`).catch(() => null)
      const turns = recentTurns(m?.messages ?? [])
      if (!turns.length) continue
      const v = parseVerdict(await askModel(buildPrompt(sess.title || sess.preview || 'Chat', turns, threadState.done[sess.id]?.line ?? '', CFG.userName)))
      if (!v) { log('threads: no verdict for', sess.id.slice(0, 22)); continue }
      threadState.cache[sess.id] = { ...v, lastActive: sess.last_active, profile: sess.profile, agent: sess.agent, title: sess.title || '' }
      saveThreads()
      log('threads:', sess.id.slice(0, 22), v.state, v.topic)
    }
    const cutoff = Date.now() / 1000 - 14 * 86400
    for (const [k, v] of Object.entries(threadState.cache)) if (v.lastActive < cutoff) delete threadState.cache[k]
    for (const bag of [threadState.done, threadState.snoozed, threadState.dismissed]) for (const k of Object.keys(bag)) if (!threadState.cache[k]) delete bag[k]
    saveThreads()
    if (todo.length === 6) setTimeout(scanThreads, 20_000).unref() // more chats to read
  } catch (e) { log('threads scan failed', e.message) } finally { scanning = false }
}

async function needs() {
  const items = []
  let partial = false
  let asks = []
  try { asks = await waitingAsks() } catch { partial = true }
  for (const a of asks) {
    const seen = openAsks.get(`murmur:chat:${a.key}`)
    const owner = seen ? { name: seen.note.title, profile: hrefParts(seen.note.href)?.profile ?? CFG.defaultProfile } : await ownerOf(a.key)
    const kind = kindOf(a)
    items.push({
      id: 'ask:' + a.key,
      kind,
      title: seen?.note.body || askSummary(a),
      sub: `${owner.name} · waiting now`,
      at: seen?.at ?? Date.now(),
      href: seen?.note.href ?? `/chat/${encodeURIComponent(owner.profile)}/${encodeURIComponent(a.key)}`,
      actions: kind === 'approval' ? [{ k: 'approve', label: 'Approve' }, { k: 'deny', label: 'Reject' }]
        : a.choices.length ? [...a.choices.map((c, i) => ({ k: 'pick:' + i, label: choiceLabel(c) })), { k: 'open', label: 'Open' }]
        : [{ k: 'open', label: 'Open' }],
    })
  }
  const live = asks.map((a) => a.key)
  const threads = rankThreads(threadState.cache, threadState, Date.now()).filter((t) => !live.includes(t.id))
  for (const t of threads.slice(0, THREAD_CAP)) {
    items.push({
      id: 'thread:' + t.id,
      kind: t.state === 'waiting' ? 'waiting' : 'open',
      title: t.topic,
      sub: `${t.line}\n${t.agent} · ${agoString(t.lastActive, Date.now())}`,
      at: t.lastActive * 1000,
      href: `/chat/${encodeURIComponent(t.profile)}/${encodeURIComponent(t.id)}`,
      actions: [{ k: 'done', label: 'Done' }, { k: 'open', label: 'Open chat' }, { k: 'later', label: 'Later' }],
    })
  }
  return {
    items,
    asks: asks.length,
    waiting: threads.filter((t) => t.state === 'waiting').length,
    open: threads.filter((t) => t.state === 'open').length,
    more: Math.max(0, threads.length - THREAD_CAP),
    partial,
    at: Date.now(),
  }
}
const THREAD_CAP = 12

async function act(id, action) {
  if (id.startsWith('ask:')) {
    const key = id.slice(4)
    const m = /^pick:(\d)$/.exec(action)
    if (action !== 'approve' && action !== 'deny' && !m) return { ok: false, error: 'bad_action' }
    const r = m ? await pick(key, Number(m[1])) : await respond(key, action === 'approve' ? 'once' : 'deny')
    if (r.ok) openAsks.delete(`murmur:chat:${key}`)
    log('act', id.slice(0, 24), action, JSON.stringify(r))
    return r
  }
  if (id.startsWith('thread:')) {
    const tid = id.slice(7)
    const t = threadState.cache[tid]
    if (!t) return { ok: false, error: 'not_found' }
    if (action === 'done') {
      threadState.done[tid] = { lastActive: t.lastActive, line: t.line, at: Date.now() }
      delete threadState.snoozed[tid]
    } else if (action === 'later' || action === 'dismiss') {
      threadState.snoozed[tid] = { lastActive: t.lastActive, until: tomorrowMorning(Date.now()) }
    } else if (action === 'undo') {
      delete threadState.done[tid]; delete threadState.snoozed[tid]; delete threadState.dismissed[tid]
    } else return { ok: false, error: 'bad_action' }
    saveThreads()
    log('act', id.slice(0, 30), action)
    return { ok: true }
  }
  return { ok: false, error: 'bad_request' }
}

// ── Voice dump ──────────────────────────────────────────────────────────────

const capFile = join(DATA, 'captures.json')
const readCaps = () => { try { return JSON.parse(readFileSync(capFile, 'utf8')) } catch { return {} } }
const writeCaps = (c) => {
  const cutoff = Date.now() - 7 * 86_400_000
  for (const k of Object.keys(c)) if (c[k].at < cutoff) delete c[k]
  writeFileSync(capFile, JSON.stringify(c))
}

/**
 * "<assistantName>, ..." hands the line to your agent as a brand-new chat (default profile) and lets it start on it.
 * The socket stays open until the turn finishes (or 20 min) so the run is never cut short; the reply then reaches
 * the phone like any other Murmur push. Returns once the prompt is accepted.
 */
async function startChat(request) {
  await ensureSession()
  return new Promise((resolve, reject) => {
    let ws, sid = '', n = 0, settled = false, closed = false
    const pend = new Map()
    const close = () => { if (closed) return; closed = true; clearTimeout(life); clearTimeout(setup); try { ws.close() } catch { /* gone */ } }
    const fail = (e) => { if (!settled) { settled = true; reject(e) } close() }
    const life = setTimeout(close, 20 * 60_000)
    const setup = setTimeout(() => fail(new Error('hermes socket timeout')), 20_000)
    try { ws = new WebSocket(MURMUR.replace(/^http/, 'ws') + '/hermes/api/ws', { headers: proxyHeaders() }) } catch (e) { clearTimeout(life); clearTimeout(setup); return reject(e) }
    const call = (method, params) => new Promise((res, rej) => { const id = ++n; pend.set(id, { res, rej }); ws.send(JSON.stringify({ jsonrpc: '2.0', id, method, params })) })
    ws.onmessage = (e) => {
      let d; try { d = JSON.parse(String(e.data)) } catch { return }
      const w = d.id != null ? pend.get(d.id) : undefined
      if (w) { pend.delete(d.id); if (d.error) w.rej(new Error(d.error.message ?? 'rpc error')); else w.res(d.result); return }
      const p = d.params ?? {}
      if (sid && p.session_id === sid && (p.type === 'message.complete' || p.type === 'error')) { log('chat done', p.type, sid); close() }
    }
    ws.onerror = () => fail(new Error('hermes socket error'))
    ws.onclose = () => fail(new Error('hermes socket closed'))
    ws.onopen = async () => {
      try {
        const c = await call('session.create', { profile: CFG.defaultProfile, close_on_disconnect: false })
        sid = c.session_id
        const text = `${request}\n\n(Sent by voice from the Capture button on my phone, so the wording may have small transcription errors. Treat it as a normal request.)`
        await call('prompt.submit', { session_id: sid, text, title_preview: request.slice(0, 60) })
        settled = true; clearTimeout(setup)
        resolve({ sessionId: sid, storedId: c.stored_session_id })
      } catch (e) { fail(e) }
    }
  })
}

async function capture(text, cid) {
  const caps = readCaps()
  if (cid && caps[cid]) return { ...caps[cid].result, repeat: true } // a retry of one already filed
  const c = parseCapture(text, new Date(), { name: CFG.assistantName, areas: CFG.capture.areas, tasks: Boolean(CFG.capture.todoist), notes: Boolean(CFG.capture.notesDir) })
  if (!c) return { ok: false, error: 'empty' }
  const id = cid || randomBytes(6).toString('hex')
  const stamp = new Date()
  let result
  if (c.kind === 'chat') {
    const s = await startChat(c.request)
    result = { ok: true, id, kind: 'chat', where: `new chat with ${CFG.assistantName}`, title: c.title, noUndo: true }
    caps[id] = { at: Date.now(), kind: 'chat', sessionId: s.storedId, result }
  } else if (c.kind === 'note') {
    const area = CFG.capture.areas.find((a) => a.name === c.area)
    const dir = area?.notesDir || CFG.capture.notesDir
    mkdirSync(dir, { recursive: true })
    const file = `${dir}/voice-notes.md`
    appendFileSync(file, `- ${isoDay(stamp)} ${String(stamp.getHours()).padStart(2, '0')}:${String(stamp.getMinutes()).padStart(2, '0')} — ${c.title} <!--cap:${id}-->\n`)
    result = { ok: true, id, kind: 'note', where: `${area?.label || (c.area === 'Inbox' ? 'Inbox' : c.area)} notes`, title: c.title }
    caps[id] = { at: Date.now(), kind: 'note', file, result }
  } else {
    const targets = await todoTargets()
    const t = targets[c.area] ?? targets.Inbox
    if (!t) throw new Error('no Todoist project')
    const body = { content: c.title, description: `Voice capture ${isoDay(stamp)}: “${c.raw}”`, project_id: t.projectId, priority: c.priority }
    if (t.sectionId) body.section_id = t.sectionId
    if (c.due && c.time) body.due_string = dueString(c.due, c.time)
    else if (c.due) body.due_date = c.due
    const r = await todo('/tasks', { method: 'POST', body: JSON.stringify(body) })
    if (!r.ok) throw new Error('Todoist ' + r.status)
    let due = r.json?.due?.date ?? ''
    if (c.due && due.slice(0, 10) !== c.due) { // never trust a parsed date: read it back and fix
      const fix = await todo(`/tasks/${r.json.id}`, { method: 'POST', body: JSON.stringify({ due_date: c.due }) })
      due = fix.json?.due?.date ?? due
    }
    result = { ok: true, id, kind: 'task', where: `${t.label}${t.sectionName ? ' › ' + t.sectionName : ''}`, title: c.title, due }
    caps[id] = { at: Date.now(), kind: 'task', todoId: r.json.id, result }
  }
  writeCaps(caps)
  log('capture', result.kind, result.where, JSON.stringify(result.title))
  return result
}

async function undoCapture(id) {
  const caps = readCaps()
  const c = caps[id]
  if (!c) return { ok: false, error: 'unknown' }
  if (c.kind === 'chat') return { ok: false, error: 'chat_started' } // he is already working on it; stop him in the chat
  if (c.kind === 'task') { const r = await todo(`/tasks/${c.todoId}`, { method: 'DELETE' }); if (!r.ok && r.status !== 404) return { ok: false, error: 'todoist ' + r.status } }
  else {
    try {
      const lines = readFileSync(c.file, 'utf8').split('\n').filter((l) => !l.includes(`<!--cap:${id}-->`))
      writeFileSync(c.file, lines.join('\n'))
    } catch { /* file gone */ }
  }
  delete caps[id]
  writeCaps(caps)
  return { ok: true }
}

// ── Stat widget ─────────────────────────────────────────────────────────────

let adapter = null
async function stat() {
  const c = CFG.stat
  if (!c) return null
  try {
    if (c.adapter) {
      adapter ??= await import(pathToFileURL(resolvePath(c.adapter)).href)
      return normalizeStat(await adapter.default(CFG))
    }
    return normalizeStat(JSON.parse(readFileSync(c.file, 'utf8')))
  } catch (e) {
    return normalizeStat({ label: c.label || 'Stat', ok: false, problems: [`can't read it: ${String(e.message).slice(0, 40)}`] })
  }
}

// ── Snooze an approval ──────────────────────────────────────────────────────

const snoozes = new Map()
function snooze(tag, minutes) {
  const note = openAsks.get(tag)?.note
  if (!note) return { ok: false, error: 'not_open' }
  clearTimeout(snoozes.get(tag))
  snoozes.set(tag, setTimeout(async () => {
    snoozes.delete(tag)
    const key = chatKeyOf(tag)
    const still = key ? await waitingAsks().then((l) => l.some((a) => a.key === key)).catch(() => true) : true
    if (still) publish({ title: note.title, body: note.body, tag, href: note.href, hermes: note.hermes, ask: true, kind: note.kind, snoozed: true })
  }, minutes * 60_000))
  return { ok: true, minutes }
}

// ── HTTP ────────────────────────────────────────────────────────────────────

const tokenOk = (req) => {
  const given = Buffer.from((req.headers.authorization ?? '').replace(/^Bearer /, ''))
  const want = Buffer.from(identity.token)
  return given.length === want.length && timingSafeEqual(given, want)
}
const send = (res, status, body) => {
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' })
  res.end(JSON.stringify(body))
}
const readBody = (req) => new Promise((resolve) => { const c = []; req.on('data', (d) => c.push(d)); req.on('end', () => resolve(Buffer.concat(c))) })

/** The pairing page: only for a browser on this very computer, never through a proxy or tunnel. */
let qrSource = null
function pairRoute(req, res, url) {
  if (!isLocalDirect(req)) {
    res.writeHead(403, { 'content-type': 'text/plain; charset=utf-8' })
    return res.end(`The pairing page only opens on the computer that runs the relay.\nOpen http://127.0.0.1:${PORT}/pair there, or run: node relay.mjs pair`)
  }
  if (url.pathname === '/pair/qr.js') {
    qrSource ??= readFileSync(join(HERE, 'vendor', 'qrcode.js'), 'utf8')
    res.writeHead(200, { 'content-type': 'text/javascript; charset=utf-8', 'cache-control': 'no-store' })
    return res.end(qrSource)
  }
  const html = pairHtml({ link: pairingLink(CFG, identity.token), code: pairingCode(CFG, identity.token), name: CFG.assistantName, relay: CFG.relayPublicUrl, murmur: CFG.murmurPublicUrl })
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store', 'referrer-policy': 'no-referrer', 'content-security-policy': "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:" })
  res.end(html)
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', 'http://x')
  try {
    if (req.method === 'POST' && url.pathname === `/inbox/${identity.inbox}`) {
      const body = await readBody(req)
      res.writeHead(201).end()
      try {
        const note = fromPush(decryptPush(body))
        if (note.ask) Object.assign(note, await classifyAsk(note))
        else if (await isRunNote(note)) note.run = true
        publish(note)
      } catch (e) { log('bad push:', e.message) }
      return
    }
    if (url.pathname === '/healthz') return send(res, 200, { ok: true, clients: clients.size, last: notes.at(-1)?.id ?? 0 })
    if (url.pathname === '/pair' || url.pathname.startsWith('/pair/')) return pairRoute(req, res, url)
    if (!tokenOk(req)) return send(res, 401, { error: 'token' })

    if (url.pathname === '/events') {
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-store', connection: 'keep-alive', 'x-accel-buffering': 'no' })
      const since = Number(req.headers['last-event-id'] ?? url.searchParams.get('since') ?? 0)
      res.write('retry: 3000\n\n')
      // Catch-up: whatever the phone missed while it was away (never an old one it has).
      for (const n of notes) if (n.id > since && (since || Date.now() - n.at < 10 * 60_000)) res.write(`id: ${n.id}\nevent: note\ndata: ${JSON.stringify(n)}\n\n`)
      res.write(`event: hello\ndata: ${JSON.stringify({ last: seq, at: Date.now() })}\n\n`)
      clients.add(res)
      const ping = setInterval(() => res.write(`: ping ${Date.now()}\n\n`), 25_000)
      req.on('close', () => { clearInterval(ping); clients.delete(res) })
      return
    }
    if (url.pathname === '/api/capabilities') return send(res, 200, capabilities(CFG, VERSION))
    if (url.pathname === '/api/agents') return send(res, 200, { agents: await agents() })
    if (url.pathname === '/api/last') return send(res, 200, await lastReply(url.searchParams.get('agent') ?? CFG.defaultProfile))
    if (url.pathname === '/api/cards') return send(res, 200, await cards())
    if (url.pathname === '/api/needs') return send(res, 200, await needs())
    if (url.pathname === '/api/stat') { const st = await stat(); return st ? send(res, 200, st) : send(res, 404, { error: 'stat_off' }) }
    if (req.method === 'POST' && url.pathname === '/api/act') {
      const b = JSON.parse((await readBody(req)).toString('utf8') || '{}')
      return send(res, 200, await act(String(b.id ?? ''), String(b.action ?? '')))
    }
    if (req.method === 'POST' && url.pathname === '/api/respond') {
      const tag = url.searchParams.get('tag') ?? ''
      const key = chatKeyOf(tag)
      const want = url.searchParams.get('choice') ?? 'once'
      const choice = want === 'deny' ? 'deny' : 'once'
      if (!key) return send(res, 400, { ok: false, error: 'bad_tag' })
      const pm = /^pick:(\d)$/.exec(want)
      const r = pm ? await pick(key, Number(pm[1])) : await respond(key, choice)
      if (r.ok) openAsks.delete(tag)
      log('respond', choice, JSON.stringify(r))
      return send(res, 200, r)
    }
    if (req.method === 'POST' && url.pathname === '/api/snooze') return send(res, 200, snooze(url.searchParams.get('tag') ?? '', Math.min(240, Math.max(1, Number(url.searchParams.get('min') ?? 15)))))
    if (req.method === 'POST' && url.pathname === '/api/capture') {
      const b = JSON.parse((await readBody(req)).toString('utf8') || '{}')
      return send(res, 200, await capture(String(b.text ?? ''), b.cid ? String(b.cid) : ''))
    }
    if (req.method === 'POST' && url.pathname === '/api/undo') {
      const b = JSON.parse((await readBody(req)).toString('utf8') || '{}')
      return send(res, 200, await undoCapture(String(b.id ?? '')))
    }
    if (url.pathname === '/api/test' && req.method === 'POST') {
      return send(res, 200, publish({ title: 'Murmur test', body: 'Native push channel works.', tag: 'murmur:test', href: '/', hermes: '', ask: url.searchParams.get('ask') === '1' }))
    }
    send(res, 404, { error: 'not_found' })
  } catch (e) {
    log('error', url.pathname, e.message)
    if (!res.headersSent) send(res, 500, { error: e.message })
  }
})

// ── Command line: init / pair / doctor ─────────────────────────────────────

const flag = (n) => { const i = process.argv.indexOf('--' + n); return i > 0 ? process.argv[i + 1] : undefined }
const isMain = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
const cmd = isMain ? process.argv[2] : undefined

async function probe(url, init = {}) {
  try {
    const r = await fetch(url, { ...init, signal: AbortSignal.timeout(8000) })
    return { ok: r.ok, status: r.status }
  } catch (e) { return { ok: false, status: 0, error: e.cause?.code || e.message } }
}

async function doctor() {
  const line = (ok, msg, fix = '') => console.log(`${ok ? 'ok  ' : 'FAIL'} ${msg}${!ok && fix ? '\n       -> ' + fix : ''}`)
  const v = validate(CFG)
  for (const e of v.errors) line(false, e)
  for (const w of v.warnings) console.log('note ' + w)
  if (v.errors.length) return 1
  let bad = 0
  const check = (ok, msg, fix) => { line(ok, msg, fix); if (!ok) bad++ }
  const m = await probe(MURMUR + '/healthz')
  check(m.ok, `Murmur answers at ${MURMUR}`, 'Start Murmur first (see docs/SETUP.md, step 1), or fix murmurUrl in config.json.')
  if (m.ok) {
    try {
      await ensureSession()
      const p = await murmur('/hermes/api/profiles')
      const names = (p.json?.profiles ?? []).map((x) => x.name)
      check(p.status === 200, 'Murmur can see Hermes', 'Hermes must be running; Murmur shows its own error page at its address if it is not.')
      if (p.status === 200) check(names.includes(CFG.defaultProfile), `Hermes profile "${CFG.defaultProfile}" exists (found: ${names.join(', ') || 'none'})`, 'Set defaultProfile in config.json to one of those names.')
    } catch (e) { check(false, 'Murmur sign-in: ' + e.message, 'Set murmurPassword in config.json, or remove the password from Murmur.') }
  }
  const r = await probe(RELAY_PUBLIC + '/healthz')
  check(r.ok, `This relay is reachable at ${RELAY_PUBLIC}`, 'Start the relay (npm start) and publish it over https (docs/SETUP.md, step 3). A plain-http or unpublished address will not work.')
  const mp = await probe(MURMUR_PUBLIC + '/healthz')
  check(mp.ok, `Murmur is reachable at ${MURMUR_PUBLIC}`, 'Publish Murmur over https (docs/SETUP.md, step 3).')
  if (CFG.threads.enabled) {
    const h = await new Promise((res) => execFile(CFG.hermesBin, ['--version'], { timeout: 15000, windowsHide: true }, (err) => res(!err)))
    check(h, `Hermes command works (${CFG.hermesBin})`, 'Set hermesBin in config.json to the full path of the hermes program.')
  }
  console.log(bad ? `\n${bad} problem(s) above.` : '\nAll good. Pair your phone: node relay.mjs pair')
  return bad ? 1 : 0
}

if (cmd === 'init') {
  const file = join(DATA, 'config.json')
  if (existsSync(file) && !process.argv.includes('--force')) { console.error(`${file} already exists. Edit it, or add --force to overwrite.`); process.exit(1) }
  const out = {
    murmurPublicUrl: flag('murmur-public') ?? 'https://REPLACE-ME.example.ts.net:8444',
    relayPublicUrl: flag('relay-public') ?? 'https://REPLACE-ME.example.ts.net:8445',
    assistantName: flag('name') ?? 'Hermes',
    defaultProfile: flag('profile') ?? 'default',
  }
  if (flag('password')) out.murmurPassword = flag('password')
  writeFileSync(file, JSON.stringify(out, null, 2) + '\n', { mode: 0o600 })
  console.log(`Wrote ${file}. Next: node relay.mjs doctor`)
  process.exit(0)
}
if (cmd === 'pair') {
  const v = validate(CFG)
  if (v.errors.length) { console.error(v.errors.join('\n')); process.exit(1) }
  console.log(`Pairing code (treat it like a password):\n\n${pairingCode(CFG, identity.token)}\n\nOr open http://127.0.0.1:${PORT}/pair on this computer for a QR code (the relay must be running).`)
  process.exit(0)
}
if (cmd === 'doctor') process.exit(await doctor())

if (!process.env.RELAY_NO_LISTEN) {
  const v = validate(CFG)
  for (const w of v.warnings) log('note:', w)
  if (v.errors.length) {
    for (const e of v.errors) console.error('config error: ' + e)
    console.error(`\nFix ${join(DATA, 'config.json')} (see docs/CONFIG.md), or run: node relay.mjs init --murmur-public https://... --relay-public https://...`)
    process.exit(1)
  }
  server.listen(PORT, '127.0.0.1', () => log(`relay on 127.0.0.1:${PORT}, inbox ${endpoint}, device ${deviceId}`))
  ensureRegistered()
  setInterval(ensureRegistered, 60_000).unref()
  if (CFG.threads.enabled) {
    setTimeout(scanThreads, 5_000).unref()
    setInterval(scanThreads, 10 * 60_000).unref()
  }
}
