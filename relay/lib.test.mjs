// Unit tests for relay/lib.mjs:  node --test relay/lib.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { parseTasklist, parseCapture, routeArea, dueString, normalizeStat, pullDue, captureRules } from './lib.mjs'

// Thursday 2026-11-19, 14:00 local
const NOW = new Date(2026, 10, 19, 14, 0)

const AREAS = [
  { name: 'Hobby', match: '\\b(club|garden|pottery|kiln)\\b' },
  { name: 'Work', match: '\\b(customer|quote|invoice)\\b' },
  { name: 'Home', match: '\\b(groceries|diapers|dentist|crib)\\b' },
]
const OPTS = { name: 'Atlas', areas: AREAS, tasks: true, notes: true }

test('parseTasklist keeps top-level active lines with id/due/repeats/priority', () => {
  const md = `# X\n\n## Active\n\n### Group\n- [ ] Read the report (repeats) — due 2026-11-19 — td:AAA\n- [ ] [P2] Send out the newsletter (repeats) — due 2026-11-20 — td:BBB\n- [ ] Research taxes — due 2026-11-18 — td:CCC\n  - [ ] Call the accountant — td:DDD\n- [ ] Write the devo — td:EEE\n\n## Done (recent)\n- [x] old — td:ZZZ\n`
  const t = parseTasklist(md, 'Home')
  assert.equal(t.length, 4)
  assert.deepEqual(t[0], { id: 'AAA', title: 'Read the report', due: '2026-11-19', repeats: true, priority: 4, list: 'Home' })
  assert.equal(t[1].priority, 2)
  assert.equal(t[1].title, 'Send out the newsletter')
  assert.equal(t[3].due, '')
})

test('parseTasklist works on a plain list with no ids or dates', () => {
  const t = parseTasklist('## Active\n- [ ] one\n- [ ] two — due 2026-12-01\n', 'L')
  assert.deepEqual(t.map((x) => [x.title, x.due]), [['one', ''], ['two', '2026-12-01']])
})

test('routeArea uses the configured patterns, first match wins, Inbox otherwise', () => {
  assert.equal(routeArea('Finish the garden outline', AREAS), 'Hobby')
  assert.equal(routeArea('Email the customer about the quote', AREAS), 'Work')
  assert.equal(routeArea('Pick up diapers', AREAS), 'Home')
  assert.equal(routeArea('Buy a new belt', AREAS), 'Inbox')
  assert.equal(routeArea('anything', []), 'Inbox')
  assert.equal(routeArea('anything', [{ name: 'Bad', match: '(' }]), 'Inbox') // an invalid pattern never throws
})

test('pullDue computes absolute dates', () => {
  assert.equal(pullDue('call the dentist tomorrow', NOW).date, '2026-11-20')
  assert.equal(pullDue('call the dentist tomorrow', NOW).rest, 'call the dentist')
  assert.equal(pullDue('send it on friday', NOW).date, '2026-11-20') // Thursday -> next day Friday
  assert.equal(pullDue('send it next monday at 9am', NOW).date, '2026-11-23')
  assert.deepEqual(pullDue('send it next monday at 9am', NOW).time, { h: 9, mi: 0 })
  assert.equal(pullDue('do it on thursday', NOW).date, '2026-11-26') // same weekday means next week
  assert.equal(pullDue('thing in 3 days', NOW).date, '2026-11-22')
  assert.equal(pullDue('thing next week', NOW).date, '2026-11-23')
  assert.equal(pullDue('plain text', NOW).date, null)
})

test('parseCapture: starting with the assistant name opens a chat, everything else is filed', () => {
  let c = parseCapture('Atlas, look into the backup and tell me what is left', NOW, OPTS)
  assert.equal(c.kind, 'chat')
  assert.equal(c.request, 'Look into the backup and tell me what is left')
  c = parseCapture('Hey Atlas can you draft the pottery lesson for Sunday', NOW, OPTS)
  assert.equal(c.kind, 'chat')
  assert.equal(c.request, 'Can you draft the pottery lesson for Sunday')
  c = parseCapture('okay atlas: research car seats', NOW, OPTS)
  assert.equal(c.kind, 'chat')
  assert.equal(c.request, 'Research car seats')
  // Not a trigger: the name is mid-sentence or possessive, or nothing follows it
  assert.equal(parseCapture('Remind me to call Atlas tomorrow', NOW, OPTS).kind, 'task')
  assert.equal(parseCapture("Atlas's birthday is next week", NOW, OPTS).kind, 'task')
  assert.equal(parseCapture('Atlas', NOW, OPTS), null)
  assert.equal(parseCapture('Atlas, ', NOW, OPTS), null)
})

test('parseCapture: a two-word assistant name still works', () => {
  const o = { ...OPTS, name: 'Mr Bot' }
  assert.equal(parseCapture('Mr Bot, summarise my week', NOW, o).kind, 'chat')
  assert.equal(parseCapture('mr   bot summarise my week', NOW, o).kind, 'chat')
  assert.equal(parseCapture('Mr Botany is a class', NOW, o).kind, 'task')
})

test('parseCapture: tasks, notes, fillers, priority', () => {
  let c = parseCapture('Remind me to buy the crib tomorrow at 5 pm', NOW, OPTS)
  assert.equal(c.kind, 'task'); assert.equal(c.area, 'Home'); assert.equal(c.title, 'Buy the crib')
  assert.equal(c.due, '2026-11-20'); assert.deepEqual(c.time, { h: 17, mi: 0 })
  c = parseCapture('add a task to print the pottery lesson urgent', NOW, OPTS)
  assert.equal(c.area, 'Hobby'); assert.equal(c.priority, 4); assert.equal(c.title, 'Print the pottery lesson')
  c = parseCapture('idea: garden series on the minor perennials', NOW, OPTS)
  assert.equal(c.kind, 'note'); assert.equal(c.area, 'Hobby'); assert.equal(c.title, 'Garden series on the minor perennials')
  c = parseCapture('note to self the van needs an oil change', NOW, OPTS)
  assert.equal(c.kind, 'note'); assert.equal(c.title, 'The van needs an oil change')
  c = parseCapture('buy a new belt', NOW, OPTS)
  assert.equal(c.area, 'Inbox'); assert.equal(c.due, null); assert.equal(c.priority, 1)
  assert.equal(parseCapture('   ', NOW, OPTS), null)
})

test('parseCapture follows what the relay can file: no targets = chat, tasks-only turns notes into tasks, notes-only turns tasks into notes', () => {
  assert.equal(parseCapture('buy a new belt', NOW, { ...OPTS, tasks: false, notes: false }).kind, 'chat')
  assert.equal(parseCapture('buy a new belt', NOW, { ...OPTS, tasks: false, notes: false }).request, 'Buy a new belt')
  assert.equal(parseCapture('idea: a new belt', NOW, { ...OPTS, notes: false }).kind, 'task')
  assert.equal(parseCapture('buy a new belt tomorrow', NOW, { ...OPTS, tasks: false }).kind, 'note')
  assert.equal(parseCapture('Atlas, hi there', NOW, { name: 'Atlas', tasks: false, notes: false }).kind, 'chat')
  assert.equal(parseCapture('Atlas, hi there', NOW, { name: 'Atlas', tasks: false, notes: false }).request, 'Hi there')
})

test('captureRules default name is Hermes', () => {
  assert.ok(captureRules().chat.test('hermes, do it'))
  assert.ok(!captureRules().chat.test('someone else, do it'))
})

test('dueString is absolute', () => {
  assert.equal(dueString('2026-11-21', null), 'Nov 21')
  assert.equal(dueString('2026-11-21', { h: 9, mi: 0 }), 'Nov 21 at 9am')
  assert.equal(dueString('2026-11-21', { h: 17, mi: 30 }), 'Nov 21 at 5:30pm')
  assert.equal(dueString('2026-11-21', { h: 0, mi: 0 }), 'Nov 21 at 12am')
})

test('normalizeStat bounds and defaults what a file or adapter gives', () => {
  assert.equal(normalizeStat(null), null)
  assert.equal(normalizeStat('x'), null)
  const s = normalizeStat({ label: 'Balance', headline: '$1,204.10', delta: '+$3.20', trend: 'up', note: '2 accounts' })
  assert.deepEqual(s, { label: 'Balance', headline: '$1,204.10', delta: '+$3.20', trend: 'up', note: '2 accounts', ok: true, problems: [] })
  const bad = normalizeStat({ headline: 'x'.repeat(100), trend: 'sideways', problems: ['a', 'b', 'c', 'd', 'e'].map((x) => x.repeat(100)) })
  assert.equal(bad.label, 'Stat'); assert.equal(bad.headline.length, 24); assert.equal(bad.trend, 'flat')
  assert.equal(bad.problems.length, 4); assert.equal(bad.problems[0].length, 60); assert.equal(bad.ok, false)
  assert.equal(normalizeStat({ ok: false }).ok, false)
})
