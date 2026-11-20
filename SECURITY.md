# Security

## What the pieces can do
- The **relay** can read what Murmur pushes (replies, approvals, questions) and can answer approvals and questions and start chats **as you**. It holds a bearer token in `relay/data/relay.json`.
- The **pairing code** contains that token. Anyone with it can do what the phone can do. Treat it like a password; do not paste it into chats or screenshots.
- The **app** stores the token in app-private storage (backups disabled), and refuses plain http.

## Defaults that protect you
- The `/pair` page only loads for a direct request from the computer itself. It refuses anything that arrives through a proxy, tunnel or Tailscale (it checks forwarding headers).
- Everything under `/api` and `/events` needs the token. Only `/healthz` is open (it reveals nothing but that the relay is up).
- Nothing is sent to third parties by the relay. The optional chat scan sends chat text to **the model you configured in Hermes**; leave it off if you do not want that. The optional Todoist filing talks to Todoist.
- Secrets live only in `relay/data/` (git-ignored) and in environment variables.

## Exposure
Use Tailscale (or similar) so only your own devices can reach the relay. If you put the relay on the public internet, the token is your only protection: keep it long (it is), rotate it if it leaks (delete `relay.json`, restart, re-pair), and give Murmur its own password. Do not use Tailscale Funnel for this.

## Optional adapters
A `stat.adapter` module runs inside the relay with its full permissions. Only load code you wrote or have read.

## Reporting a vulnerability
Please do not open a public issue for a security problem. Use GitHub's **Report a vulnerability** (Security tab) on this repository.
