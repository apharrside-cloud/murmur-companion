// Relay settings: one JSON file (data/config.json) plus a few environment overrides.
// Pure functions only (no network, no globals) so they can be tested.

import { readFileSync, existsSync } from 'node:fs'

export const DEFAULTS = Object.freeze({
  port: 3010,
  // Where the relay (running next to Murmur) reaches Murmur's server.
  murmurUrl: 'http://127.0.0.1:3000',
  // The https address your PHONE uses for Murmur. Murmur checks the Origin header against it.
  murmurPublicUrl: '',
  // The https address your phone AND Murmur's server use to reach this relay.
  relayPublicUrl: '',
  // Only if you gave Murmur a password (MURMUR_PASSWORD).
  murmurPassword: '',
  // What the app calls your agent, and the word that starts a chat from voice capture ("Hermes, ...").
  assistantName: 'Hermes',
  // The Hermes profile that new capture chats go to.
  defaultProfile: 'default',
  // Used in the chat triage prompt ("waiting on <name>"). Empty = "the user".
  userName: '',
  hermesBin: 'hermes',
  // Needs-you chat scan: asks a model to label recent chats waiting/open/done. Off by default: it spends model calls.
  threads: { enabled: false, model: '', provider: '' },
  capture: {
    // Optional filing targets for voice capture. With neither, every capture starts a chat.
    todoist: null, // { tokenEnv: 'TODOIST_API_TOKEN', envFile: '' }
    notesDir: '', // folder that voice-notes.md files are appended to
    // [{ name, match (regex source), label, todoistProject, todoistSection, notesDir }]
    areas: [],
  },
  // [{ name, file }] markdown lists: "## Active" then "- [ ] task — due YYYY-MM-DD" lines. Feeds the Status widget counts.
  tasklists: [],
  // One headline number on a widget. { label, file } reads a JSON file, or { label, adapter } loads your own module (docs/MODULES.md).
  stat: null,
})

const isObj = (v) => v && typeof v === 'object' && !Array.isArray(v)

function merge(base, over) {
  const out = { ...base }
  for (const [k, v] of Object.entries(over ?? {})) {
    if (v === undefined || v === null) { if (k in base && base[k] === null) out[k] = null; continue }
    out[k] = isObj(base[k]) && isObj(v) ? merge(base[k], v) : v
  }
  return out
}

const trimUrl = (s) => String(s ?? '').trim().replace(/\/+$/, '')

/** Defaults < config file < environment. Never throws on a missing file; validate() reports what is wrong. */
export function loadConfig({ env = process.env, file } = {}) {
  let fromFile = {}
  if (file && existsSync(file)) {
    try { fromFile = JSON.parse(readFileSync(file, 'utf8')) } catch (e) { fromFile = { __error: `config.json is not valid JSON: ${e.message}` } }
  }
  const cfg = merge(DEFAULTS, fromFile)
  if (env.RELAY_PORT) cfg.port = Number(env.RELAY_PORT)
  if (env.MURMUR_URL) cfg.murmurUrl = env.MURMUR_URL
  if (env.MURMUR_PUBLIC) cfg.murmurPublicUrl = env.MURMUR_PUBLIC
  if (env.RELAY_PUBLIC) cfg.relayPublicUrl = env.RELAY_PUBLIC
  if (env.MURMUR_PASSWORD) cfg.murmurPassword = env.MURMUR_PASSWORD
  if (env.HERMES_BIN) cfg.hermesBin = env.HERMES_BIN
  cfg.murmurUrl = trimUrl(cfg.murmurUrl)
  cfg.murmurPublicUrl = trimUrl(cfg.murmurPublicUrl)
  cfg.relayPublicUrl = trimUrl(cfg.relayPublicUrl)
  cfg.assistantName = String(cfg.assistantName || 'Hermes').trim() || 'Hermes'
  cfg.__error = fromFile.__error
  return cfg
}

/** { errors, warnings } as plain sentences a person can act on. */
export function validate(cfg) {
  const errors = []
  const warnings = []
  if (cfg.__error) errors.push(cfg.__error)
  const https = (u) => /^https:\/\/[^/\s]+/i.test(u)
  if (!cfg.murmurPublicUrl) errors.push('murmurPublicUrl is empty. Set it to the https address your phone uses for Murmur (see docs/SETUP.md, step 3).')
  else if (!https(cfg.murmurPublicUrl)) errors.push('murmurPublicUrl must start with https:// (the phone app refuses plain http).')
  if (!cfg.relayPublicUrl) errors.push('relayPublicUrl is empty. Set it to the https address your phone and Murmur use to reach this relay (docs/SETUP.md, step 3).')
  else if (!https(cfg.relayPublicUrl)) errors.push('relayPublicUrl must start with https:// (the phone app refuses plain http).')
  if (!/^https?:\/\/[^/\s]+/i.test(cfg.murmurUrl)) errors.push('murmurUrl must look like http://127.0.0.1:3000.')
  if (!Number.isInteger(cfg.port) || cfg.port < 1 || cfg.port > 65535) errors.push('port must be a number between 1 and 65535.')
  for (const a of cfg.capture.areas) {
    if (!a?.name) { errors.push('every capture.areas entry needs a name.'); continue }
    try { if (a.match) new RegExp(a.match, 'i') } catch { errors.push(`capture.areas "${a.name}": match is not a valid regular expression.`) }
  }
  for (const t of cfg.tasklists) if (!t?.file) errors.push('every tasklists entry needs a file path.')
  if (cfg.stat && !cfg.stat.file && !cfg.stat.adapter) errors.push('stat needs a file or an adapter.')
  if (cfg.threads.enabled && !cfg.threads.model) warnings.push('threads is on with no model set: Hermes will use its default model for the chat scan, which may be a costly one.')
  if (!cfg.capture.todoist && !cfg.capture.notesDir) warnings.push('No capture filing target set: every voice capture will start a chat with your agent. That is a fine default.')
  return { errors, warnings }
}

/** What the phone is told it can use. */
export function capabilities(cfg, version = '') {
  const tasks = cfg.capture.todoist ? true : false
  const notes = Boolean(cfg.capture.notesDir) || cfg.capture.areas.some((a) => a.notesDir)
  return {
    app: 'murmur-native-relay',
    version,
    assistantName: cfg.assistantName,
    defaultProfile: cfg.defaultProfile,
    murmurUrl: cfg.murmurPublicUrl,
    capture: { chat: true, tasks, notes },
    tasklists: cfg.tasklists.length > 0,
    stat: cfg.stat ? { label: cfg.stat.label || 'Stat' } : null,
    threads: cfg.threads.enabled,
  }
}

/** The one string that pairs a phone: relay address, token and Murmur address. */
export function pairingCode(cfg, token) {
  const payload = { v: 1, r: cfg.relayPublicUrl, t: token, m: cfg.murmurPublicUrl, n: cfg.assistantName }
  return Buffer.from(JSON.stringify(payload)).toString('base64url')
}

export const pairingLink = (cfg, token) => `murmurcompanion://pair?c=${pairingCode(cfg, token)}`

/** Inverse of pairingCode(), used by tests (the app has its own Kotlin copy). */
export function readPairingCode(code) {
  try {
    const j = JSON.parse(Buffer.from(String(code).trim(), 'base64url').toString('utf8'))
    if (j?.v !== 1 || !j.r || !j.t) return null
    return j
  } catch { return null }
}

/** True for a request that came straight from this machine (no proxy in between), the only one allowed to see the pairing page. */
export function isLocalDirect(req) {
  const a = req.socket?.remoteAddress ?? ''
  const loop = a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1'
  if (!loop) return false
  const via = ['x-forwarded-for', 'forwarded', 'x-real-ip', 'cf-connecting-ip', 'cf-ray', 'tailscale-user-login', 'x-forwarded-host']
  return !via.some((h) => req.headers?.[h] !== undefined)
}
