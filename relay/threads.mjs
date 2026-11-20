// Open threads: what you and your agents are in the middle of, read from recent chats.
// Pure helpers (prompt building, verdict parsing, ranking) so they can be tested without a model.

const clip = (s, n) => (s.length > n ? s.slice(0, n) + '…' : s)

const textOf = (m) => {
  let c = m.content
  if (Array.isArray(c)) c = c.map((p) => (typeof p === 'string' ? p : p?.text ?? '')).join(' ')
  return typeof c === 'string' ? c.trim() : ''
}

/** Last few human-visible turns (user + assistant text, no tool output). */
export function recentTurns(messages, max = 14) {
  const turns = []
  for (const m of messages) {
    if (m.role !== 'user' && m.role !== 'assistant') continue
    const t = textOf(m)
    if (!t) continue
    turns.push({ role: m.role, text: t })
  }
  return turns.slice(-max)
}

export function buildPrompt(title, turns, prevLine = '', userName = '') {
  const who = String(userName || '').trim() || 'the user'
  const tag = who === 'the user' ? 'USER' : who.toUpperCase()
  const convo = turns.map((t) => `${t.role === 'user' ? tag : 'ASSISTANT'}: ${clip(t.text, t.role === 'user' ? 600 : 900)}`).join('\n\n')
  return `You are triaging a chat between ${who} and their AI assistant so ${who} can remember what is still open. Chat title: "${title}".

Read the end of the conversation below and answer with ONE line of JSON, nothing else:
{"topic":"3-6 word name for what is being worked on","state":"waiting"|"open"|"done","line":"one plain sentence, max 110 chars"}

state:
- "waiting": the assistant finished a step and is waiting for ${who} to decide, approve, answer, test something, or give the go-ahead for the next step. line = what ${who} needs to decide or do.
- "open": real work is unfinished, parked, or has named follow-ups still to do, but nothing is waiting on ${who} right now. line = what is left.
- "done": the thing was completed, was a one-off question, or nothing is left. line = what was done.
Be strict: a finished answer to a question is "done". Do not invent work. Write line to ${who} in plain words, no markdown.

${prevLine ? `\nNOTE: ${who} already dealt with an earlier item in this chat and marked it complete (it was: \"${clip(prevLine, 140)}\"). Do not flag that again. Answer \"waiting\" or \"open\" only for something NEW and unfinished that came up after it; if the latest turns just continue or close out that item, answer \"done\".\n` : ''}
CONVERSATION (oldest first):
${convo}`
}

export function parseVerdict(out) {
  const m = /\{[\s\S]*\}/.exec(out ?? '')
  if (!m) return null
  try {
    const j = JSON.parse(m[0])
    const state = ['waiting', 'open', 'done'].includes(j.state) ? j.state : null
    if (!state) return null
    return { topic: clip(String(j.topic ?? '').trim(), 60) || 'Untitled', state, line: clip(String(j.line ?? '').trim(), 140) }
  } catch { return null }
}

/**
 * Sessions worth a look: recent, real conversation, newer than what we last summarised.
 * Telegram compaction chains sessions (parent_session_id): keep only the newest of each chain.
 */
export function candidates(sessions, cache, now, days = 10) {
  const cutoff = now / 1000 - days * 86400
  const parents = new Set(sessions.map((s) => s.parent_session_id).filter(Boolean))
  return sessions
    .filter((s) => (s.last_active ?? 0) >= cutoff && (s.message_count ?? 0) >= 4 && !parents.has(s.id))
    .filter((s) => !(cache[s.id] && cache[s.id].lastActive >= s.last_active))
    .sort((a, b) => b.last_active - a.last_active)
}

/**
 * Is this thread hidden by something the user did?
 * done: complete until the chat has NEW activity (then it is re-read with the done note and only comes back for new work).
 * snoozed (Later): hidden until the wake time, or until the chat moves. dismissed: old "Not now", same as a snooze with no clock.
 */
export function isHidden(v, st, now) {
  const d = st.done?.[v.id]
  if (d && d.lastActive >= v.lastActive) return true
  const z = st.snoozed?.[v.id]
  if (z && z.until > now && z.lastActive >= v.lastActive) return true
  const x = st.dismissed?.[v.id]
  return !!(x && x >= v.lastActive)
}

/** Next 8 AM local (tomorrow morning) as epoch ms. */
export function tomorrowMorning(nowMs) {
  const d = new Date(nowMs)
  d.setDate(d.getDate() + 1)
  d.setHours(8, 0, 0, 0)
  return d.getTime()
}

/** Needs-you list: waiting first, then open; newest first inside each; hidden ones (done / later) stay out. */
export function rankThreads(cache, st, now, days = 10) {
  const cutoff = now / 1000 - days * 86400
  const live = Object.entries(cache)
    .map(([id, v]) => ({ id, ...v }))
    .filter((v) => v.state !== 'done' && v.lastActive >= cutoff)
    .filter((v) => !isHidden(v, st, now))
  const rank = { waiting: 0, open: 1 }
  return live.sort((a, b) => rank[a.state] - rank[b.state] || b.lastActive - a.lastActive)
}

export function agoString(tsSec, nowMs) {
  const m = Math.max(0, Math.round((nowMs - tsSec * 1000) / 60000))
  if (m < 60) return m <= 1 ? 'just now' : `${m}m ago`
  const h = Math.round(m / 60)
  if (h < 24) return `${h}h ago`
  const d = Math.round(h / 24)
  return d === 1 ? 'yesterday' : `${d}d ago`
}
