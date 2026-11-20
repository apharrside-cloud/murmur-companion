import { test } from 'node:test'
import assert from 'node:assert/strict'
import { recentTurns, buildPrompt, parseVerdict, candidates, rankThreads, isHidden, tomorrowMorning, agoString } from './threads.mjs'

test('recentTurns drops tool output and empty turns, keeps the tail', () => {
  const msgs = [
    { role: 'user', content: 'hi' }, { role: 'assistant', content: '' }, { role: 'tool', content: 'x'.repeat(50) },
    { role: 'assistant', content: [{ text: 'a' }, { text: 'b' }] }, { role: 'user', content: 'ok' },
  ]
  assert.deepEqual(recentTurns(msgs, 2).map((t) => t.text), ['a b', 'ok'])
})

test('buildPrompt includes title and clips long turns', () => {
  const p = buildPrompt('Widget fix', [{ role: 'assistant', text: 'z'.repeat(5000) }, { role: 'user', text: 'go' }])
  assert.match(p, /Widget fix/); assert.match(p, /USER: go/); assert.doesNotMatch(p, /Pat/); assert.ok(p.length < 3000)
})

test('parseVerdict tolerates chatter, rejects junk', () => {
  assert.deepEqual(parseVerdict('Sure!\n{"topic":"Brief widget","state":"waiting","line":"Test it on your phone"}'), { topic: 'Brief widget', state: 'waiting', line: 'Test it on your phone' })
  assert.equal(parseVerdict('{"topic":"x","state":"maybe","line":"y"}'), null)
  assert.equal(parseVerdict('no json'), null)
  assert.equal(parseVerdict('{bad'), null)
})

test('candidates: recent, real, newest of a chain, not already summarised', () => {
  const now = 2_000_000_000_000
  const t = now / 1000
  const s = [
    { id: 'a', last_active: t - 100, message_count: 10 },
    { id: 'b', last_active: t - 50, message_count: 10, parent_session_id: 'a' },
    { id: 'c', last_active: t - 100, message_count: 2 },
    { id: 'd', last_active: t - 20 * 86400, message_count: 10 },
    { id: 'e', last_active: t - 10, message_count: 10 },
  ]
  assert.deepEqual(candidates(s, { e: { lastActive: t - 10 } }, now).map((x) => x.id), ['b'])
  assert.deepEqual(candidates(s, { e: { lastActive: t - 20 } }, now).map((x) => x.id), ['e', 'b'])
})

test('rankThreads: waiting before open, done hidden, dismissal lasts until new activity', () => {
  const now = 2_000_000_000_000
  const t = now / 1000
  const cache = {
    a: { state: 'open', lastActive: t - 10 }, b: { state: 'waiting', lastActive: t - 500 },
    c: { state: 'done', lastActive: t - 5 }, d: { state: 'open', lastActive: t - 100 }, e: { state: 'waiting', lastActive: t - 30 * 86400 },
  }
  assert.deepEqual(rankThreads(cache, {}, now).map((x) => x.id), ['b', 'a', 'd'])
  assert.deepEqual(rankThreads(cache, { dismissed: { b: t - 500, a: t - 10 } }, now).map((x) => x.id), ['d'])
})

test('agoString', () => {
  assert.equal(agoString(1000, 1000 * 1000 + 5 * 60000), '5m ago')
  assert.equal(agoString(1000, 1000 * 1000 + 3 * 3600000), '3h ago')
  assert.equal(agoString(1000, 1000 * 1000 + 30 * 3600000), 'yesterday')
})

test('done hides a thread until it has new activity; undo state (no record) shows it again', () => {
  const now = 2_000_000_000_000, t = now / 1000
  const v = { id: 'r', state: 'waiting', lastActive: t - 100 }
  assert.equal(isHidden(v, { done: { r: { lastActive: t - 100 } } }, now), true)
  assert.equal(isHidden({ ...v, lastActive: t - 5 }, { done: { r: { lastActive: t - 100 } } }, now), false)
  assert.equal(isHidden(v, { done: {} }, now), false)
})

test('later hides until the wake time or new activity', () => {
  const now = 2_000_000_000_000, t = now / 1000
  const v = { id: 'r', state: 'open', lastActive: t - 100 }
  const z = (until, la = t - 100) => ({ snoozed: { r: { until, lastActive: la } } })
  assert.equal(isHidden(v, z(now + 1000), now), true)
  assert.equal(isHidden(v, z(now - 1), now), false)
  assert.equal(isHidden({ ...v, lastActive: t - 1 }, z(now + 1000), now), false)
})

test('tomorrowMorning is 8:00 local on the next day', () => {
  const d = new Date(tomorrowMorning(new Date(2026, 10, 20, 15, 30).getTime()))
  assert.deepEqual([d.getMonth(), d.getDate(), d.getHours(), d.getMinutes()], [10, 21, 8, 0])
})

test('buildPrompt names the user when given one', () => {
  assert.match(buildPrompt('T', [{ role: 'user', text: 'go' }], '', 'Sam'), /SAM: go/)
  assert.match(buildPrompt('T', [{ role: 'user', text: 'go' }], '', 'Sam'), /waiting for Sam to decide/)
})

test('buildPrompt adds the done note only when something was marked done', () => {
  const turns = [{ role: 'user', text: 'hi' }]
  assert.doesNotMatch(buildPrompt('T', turns), /already dealt with/)
  assert.match(buildPrompt('T', turns, 'Test the clicker'), /already dealt with[\s\S]*Test the clicker/)
})
