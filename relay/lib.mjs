// Pure helpers for the relay (no network, no globals) so they can be tested:
// task-list counts, voice-capture parsing/routing, the stat widget's data shape.

export const pad = (n) => String(n).padStart(2, '0')
export const isoDay = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
export const addDays = (d, n) => { const x = new Date(d.getFullYear(), d.getMonth(), d.getDate() + n, 12); return x }
const MON = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
const DAYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday']

// ── Task lists ──────────────────────────────────────────────────────────────

/** Active, top-level, unchecked lines of one markdown task list. */
export function parseTasklist(text, list) {
  const out = []
  const active = text.split(/^## /m).find((s) => s.startsWith('Active')) ?? ''
  for (const line of active.split(/\r?\n/)) {
    const m = /^- \[ \] (.*)$/.exec(line) // top level only: subtasks are indented
    if (!m) continue
    let body = m[1]
    const id = /\btd:([A-Za-z0-9]+)\s*$/.exec(body)?.[1] ?? ''
    body = body.replace(/\s*—?\s*td:[A-Za-z0-9]+\s*$/, '')
    const due = /\s—\sdue (\d{4}-\d{2}-\d{2})/.exec(body)?.[1] ?? ''
    body = body.replace(/\s—\sdue \d{4}-\d{2}-\d{2}/, '')
    const repeats = /\(repeats\)/i.test(body)
    const pri = /^\[P([1-4])\]\s*/.exec(body)?.[1]
    body = body.replace(/^\[P[1-4]\]\s*/, '').replace(/\s*\(repeats\)/i, '').trim()
    out.push({ id, title: body, due, repeats, priority: pri ? Number(pri) : 4, list })
  }
  return out
}

// ── Voice capture ───────────────────────────────────────────────────────────

const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** The phrases that start a chat ("Hermes, ...") or a note, built around the assistant's name. */
export function captureRules(name = 'Hermes') {
  const n = esc(String(name).trim().toLowerCase()).replace(/\s+/g, '\\s+')
  return {
    chat: new RegExp(`^(?:(?:hey|ok|okay|yo)\\s+)?${n}(?!['\\u2019]s\\b)\\b[\\s,.:;!\\-]*`, 'i'),
    filler: new RegExp(`^(?:(?:hey|ok|okay)\\s+)?(?:${n}[,.]?\\s+)?(?:please\\s+)?(?:(?:can you\\s+)?(?:add|create|make)\\s+(?:me\\s+)?(?:a\\s+|an\\s+)?(?:new\\s+)?(?:task|to-?do|reminder)\\s*(?:to|for|about|that)?\\s*|remind me\\s+(?:to|about|that)?\\s*|i (?:need|have|got|want) to\\s+|i should\\s+|don'?t forget\\s+(?:to\\s+)?|(?:we|i) need to\\s+)`, 'i'),
    note: new RegExp(`^(?:hey\\s+)?(?:${n}[,.]?\\s+)?(?:make a note(?: that| of)?|note to self|note that|note|ideas?|remember(?: that)?|thought)\\b[:,]?\\s*`, 'i'),
  }
}

/** Where a capture belongs: the first configured area whose pattern matches, else Inbox. */
export function routeArea(text, areas = []) {
  for (const a of areas) {
    if (!a?.match) continue
    let re
    try { re = new RegExp(a.match, 'i') } catch { continue }
    if (re.test(text)) return a.name
  }
  return 'Inbox'
}

const timeOf = (m) => {
  if (!m) return null
  let h = Number(m[1]); const mi = m[2] ? Number(m[2]) : 0
  const ap = (m[3] ?? '').toLowerCase().replace(/\./g, '')
  if (ap === 'pm' && h < 12) h += 12
  if (ap === 'am' && h === 12) h = 0
  if (!ap && h >= 1 && h <= 6) h += 12 // "at 3" in a to-do means afternoon
  if (h > 23 || mi > 59) return null
  return { h, mi }
}

/**
 * Pull a due date (and time) out of spoken text. Dates are computed here, not
 * left to Todoist's parser, which has resolved "next monday" a week late.
 * Returns {date, time, rest} where rest is the text with the date words removed.
 */
export function pullDue(text, now = new Date()) {
  let rest = text
  let date = null
  let time = null
  const take = (re, f) => { const m = re.exec(rest); if (!m) return false; const v = f(m); if (v === null) return false; date = v; rest = (rest.slice(0, m.index) + ' ' + rest.slice(m.index + m[0].length)); return true }
  const dow = (name, forceNext) => {
    const want = DAYS.indexOf(name.toLowerCase())
    let diff = (want - now.getDay() + 7) % 7
    if (diff === 0) diff = 7
    return isoDay(addDays(now, diff))
  }
  take(/\b(?:by|on|for|due)?\s*\b(today|tonight)\b/i, (m) => { if (/tonight/i.test(m[1])) time = time ?? { h: 19, mi: 0 }; return isoDay(now) }) ||
    take(/\b(?:by|on|for|due)?\s*\btomorrow(?: (morning|afternoon|evening|night))?\b/i, (m) => { if (m[1]) time = time ?? { h: { morning: 9, afternoon: 14, evening: 18, night: 20 }[m[1].toLowerCase()], mi: 0 }; return isoDay(addDays(now, 1)) }) ||
    take(/\b(?:by|on|for|due)?\s*\bthis (morning|afternoon|evening)\b/i, (m) => { time = time ?? { h: { morning: 9, afternoon: 14, evening: 18 }[m[1].toLowerCase()], mi: 0 }; return isoDay(now) }) ||
    take(/\b(?:by|on|for|due)?\s*\b(?:next |this |coming )?(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\b/i, (m) => dow(m[1])) ||
    take(/\b(?:by|on|for|due)?\s*\bnext week\b/i, () => isoDay(addDays(now, ((8 - now.getDay()) % 7) || 7))) ||
    take(/\b(?:by|on|for|due)?\s*\bin (\d+|a|an|one|two|three|four|five) (day|days|week|weeks)\b/i, (m) => {
      const n = ({ a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5 })[m[1].toLowerCase()] ?? Number(m[1])
      return isoDay(addDays(now, /week/i.test(m[2]) ? n * 7 : n))
    })
  const tm = /\bat (\d{1,2})(?::(\d{2}))?\s*(a\.?m\.?|p\.?m\.?)?(?=\s|$|[.,!?])/i.exec(rest)
  const t = timeOf(tm)
  if (t) { time = t; rest = rest.slice(0, tm.index) + ' ' + rest.slice(tm.index + tm[0].length); date = date ?? isoDay(now) }
  rest = rest.replace(/\s+(?:by|on|for|due)\s*$/i, '').replace(/\s{2,}/g, ' ').replace(/^[\s,.;:-]+|[\s,;:-]+$/g, '').trim()
  return { date, time, rest }
}

const cap = (s) => (s ? s[0].toUpperCase() + s.slice(1) : s)

/**
 * Spoken text -> what to do with it.
 * opts: { name, areas, tasks, notes }. Saying the assistant's name first (or having no filing target at all)
 * hands the line to the agent as a new chat; otherwise it is a task or a note, routed to an area.
 */
export function parseCapture(raw, now = new Date(), opts = {}) {
  const { name = 'Hermes', areas = [], tasks = true, notes = true } = opts
  const text = String(raw ?? '').replace(/\s+/g, ' ').trim()
  if (!text) return null
  const rules = captureRules(name)
  if (rules.chat.test(text)) {
    const request = text.replace(rules.chat, '').trim()
    return request ? { kind: 'chat', request: cap(request), title: cap(request), raw: text } : null
  }
  if (!tasks && !notes) return { kind: 'chat', request: cap(text), title: cap(text), raw: text }
  const area = routeArea(text, areas)
  const asNote = tasks ? notes && rules.note.test(text) : true
  if (asNote) {
    return { kind: 'note', area, title: cap(text.replace(rules.note, '').trim() || text), raw: text }
  }
  let t = (notes ? text : text.replace(rules.note, '')).replace(rules.filler, '').trim()
  const urgent = /\b(urgent|asap|top priority|high priority)\b/i.test(t)
  if (urgent) t = t.replace(/\b(urgent|asap|top priority|high priority)\b[,.]?/ig, '').trim()
  const { date, time, rest } = pullDue(t, now)
  let title = rest.replace(/^(?:to|that)\s+/i, '').trim()
  if (!title) title = text
  title = cap(title.replace(/[.\s]+$/, ''))
  return { kind: 'task', area, title, due: date, time, priority: urgent ? 4 : 1, raw: text }
}

/** "Nov 21 at 9am" style string Todoist reads reliably (absolute date, never relative). */
export function dueString(date, time) {
  if (!date) return ''
  const [y, m, d] = date.split('-').map(Number)
  const day = `${MON[m - 1]} ${d}`
  if (!time) return day
  const h12 = ((time.h + 11) % 12) + 1
  return `${day} at ${h12}${time.mi ? ':' + pad(time.mi) : ''}${time.h < 12 ? 'am' : 'pm'}`
}

// ── Stat widget ─────────────────────────────────────────────────────────────

const TRENDS = new Set(['up', 'down', 'flat'])

/** Whatever a stat file or adapter produced -> the small, bounded shape the phone widget draws. */
export function normalizeStat(raw) {
  if (!raw || typeof raw !== 'object') return null
  const clip = (v, n) => String(v ?? '').slice(0, n)
  const problems = Array.isArray(raw.problems) ? raw.problems.map((p) => clip(p, 60)).slice(0, 4) : []
  return {
    label: clip(raw.label || 'Stat', 24),
    headline: clip(raw.headline, 24),
    delta: clip(raw.delta, 40),
    trend: TRENDS.has(raw.trend) ? raw.trend : 'flat',
    note: clip(raw.note, 80),
    ok: raw.ok !== false && problems.length === 0,
    problems,
  }
}
