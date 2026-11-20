# Configuration

The relay reads `relay/data/config.json` (created by `node relay.mjs init`). `relay/data/` is git-ignored: it holds your settings and the relay's secrets. Environment variables override the file.

Run `node relay.mjs doctor` after any change. Restart the relay for changes to take effect.

## Required

| Key | Meaning |
|---|---|
| `murmurPublicUrl` | The https address your **phone** uses for Murmur. |
| `relayPublicUrl` | The https address your phone **and Murmur's server** use for the relay. |

## Common

| Key | Default | Meaning |
|---|---|---|
| `murmurUrl` | `http://127.0.0.1:3000` | Where the relay reaches Murmur from the same computer. |
| `port` | `3010` | The relay's local port (what `tailscale serve` points at). |
| `murmurPassword` | none | Only if Murmur has `MURMUR_PASSWORD`. |
| `assistantName` | `Hermes` | What the app calls your agent; the trigger word for voice-started chats. |
| `defaultProfile` | `default` | Hermes profile that voice-started chats go to. |
| `userName` | none | Used in the chat scan ("waiting on <name>"). |
| `hermesBin` | `hermes` | Full path to the `hermes` program, if it is not on PATH. Only the chat scan needs it. |

## Environment overrides
`RELAY_PORT`, `MURMUR_URL`, `MURMUR_PUBLIC`, `RELAY_PUBLIC`, `MURMUR_PASSWORD`, `HERMES_BIN`, `RELAY_DATA` (folder for config and secrets).

## Optional modules
`threads`, `capture`, `tasklists` and `stat` are documented in [MODULES.md](MODULES.md).

## Files in `relay/data/`
| File | What |
|---|---|
| `config.json` | Your settings. |
| `relay.json` | The relay's token and push keys. **Secret.** Delete it to reset: every phone must pair again. |
| `threads.json`, `captures.json`, `seq` | Caches and state. Safe to delete. |
