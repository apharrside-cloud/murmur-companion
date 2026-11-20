# Optional modules

All off by default. The app only shows what the relay says it can do (`GET /api/capabilities`), so a module you do not configure simply does not appear.

## Chat scan ("Needs you" threads)
Every 10 minutes the relay reads your recent chats (last 10 days, a few per pass) through the `hermes` command and asks a model to label each one: **waiting** on you, **open** (unfinished) or **done** (hidden). Only chats that changed are re-read.

```json
"threads": { "enabled": true, "model": "a-small-cheap-model", "provider": "your-provider" }
```

**It spends model calls.** Pick a small model. Without `model`, Hermes uses its default, which may be an expensive one (`doctor` warns about this). `hermesBin` must work from the relay's environment. Chat text is sent to that model, so only enable it if you are comfortable with that.

Cards have **Done** (hidden until the chat has new activity), **Later** (hidden until 8 AM tomorrow or until the chat moves) and **Undo** (6 seconds).

## Task lists (Status and Brief widgets)
Counts of overdue and due-today tasks from markdown files:

```json
"tasklists": [ { "name": "Home", "file": "/path/to/tasklist.md" } ]
```

File format: a `## Active` heading, then lines like `- [ ] Pay the bill — due 2026-12-01` (an em dash or hyphen, date `YYYY-MM-DD`). Checked boxes and other sections are ignored.

## Voice capture filing
By default every voice capture **starts a chat** with your agent when it begins with its name, and otherwise asks nothing of any file. To also file tasks and notes:

```json
"capture": {
  "notesDir": "/path/to/notes",
  "todoist": { "tokenEnv": "TODOIST_API_TOKEN", "envFile": "/path/to/.env" },
  "areas": [
    { "name": "Home", "match": "\\b(groceries|dinner|kids)\\b", "label": "Home",
      "todoistProject": "Home", "todoistSection": "Inbox", "notesDir": "/path/to/home-notes" }
  ]
}
```

- Without `todoist` and `notesDir`, there is no filing and no Undo to worry about.
- `todoist.tokenEnv` names an environment variable holding your Todoist API token; `envFile` (optional) is a `KEY=value` file to read it from. The token is never stored in config.json.
- `areas` route a capture by regular expression (case-insensitive) to a Todoist project/section and a notes folder. First match wins; no match goes to `notesDir` / your Todoist Inbox.
- Dates and times ("tomorrow at 3", "Friday") are computed by the relay, not by Todoist. "Urgent" becomes priority 1.
- Captures never file by themselves from the app: speech fills the box, you tap Send.

## One-number widget
One headline number on the home screen (a balance, a count, a build status).

From a JSON file you keep up to date:
```json
"stat": { "label": "Balance", "file": "/path/to/stat.json" }
```
```json
{ "label": "Balance", "headline": "$1,234", "delta": "+$12", "trend": "up", "note": "since Monday", "ok": true, "problems": [] }
```
`trend` is `up`, `down` or `flat`. Add strings to `problems` (or set `ok: false`) and the widget turns red and shows them. Fields are length-limited.

From your own code: `"stat": { "label": "Build", "adapter": "./my-stat.mjs" }` where the module's default export is `async (config) => ({ ...same shape... })`. The adapter runs inside the relay with its full permissions, so only load code you wrote or read.
