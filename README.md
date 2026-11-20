# Murmur Companion

<p align="center"><img src="docs/icon.png" width="128" alt="Murmur Companion icon"></p>

**Website and download page: [murmur.pharrlabs.com](https://murmur.pharrlabs.com)** · [Releases](https://github.com/apharrside-cloud/murmur-companion/releases)

An **unofficial Android companion app for [Murmur](https://github.com/getumbrel/murmur)**, the web interface for [Hermes Agent](https://github.com/NousResearch/hermes-agent). It adds what a browser tab on a phone does badly: push notifications you can answer from the notification shade, home-screen widgets, Quick Settings tiles and a voice-capture button.

> ## You need Murmur first
> **Murmur Companion does not include Murmur or Hermes, and it does nothing on its own.** It is the phone half of a pair. The other half is a small **relay** program in this repo that has to run next to **your own, already working Murmur**.
>
> If Murmur and Hermes Agent are not running yet, start there: [Murmur](https://github.com/getumbrel/murmur) (umbrelOS, Docker, Mac, Windows or Linux) and [Hermes Agent](https://github.com/NousResearch/hermes-agent). Come back when Murmur opens in your browser and you can chat with an agent.
>
> This project is independent. It is not made by, endorsed by or affiliated with the makers of Murmur, Umbrel or Nous Research.

**Status: v0.x beta.** It is used daily by its author, on one setup (see [What has been tested](#what-has-been-tested)). Expect rough edges, and please report them.

## What you get

| | |
|---|---|
| **Murmur on your phone** | Full-screen Murmur with "Share to Murmur", microphone, file upload and an optional fingerprint / screen-lock gate. |
| **Push notifications** | Replies, questions and approvals arrive even when the app is closed. No Google or Firebase account, no cloud service. |
| **Answer from the notification** | Approve or reject an agent's request, or tap an answer to its question, without opening the app. |
| **Needs you** | One list of what is waiting on you: live approvals and questions, plus (optional) chats that are waiting on a decision or left unfinished, each with Done, Later and Undo. |
| **Widgets** | Ask your agent, Last reply, Status cards, Morning brief, a One-number widget, Voice capture. |
| **Quick Settings tiles** | New chat, voice chat, capture, and an Approvals counter. |
| **Voice capture** | Say it, review the text, send. Start with your agent's name ("Atlas, ...") and it opens a new chat with that agent. |

Everything that needs extra setup (task lists, Todoist or notes filing, the chat scan, the One-number widget) is **optional and off by default**. Out of the box you get chats, push, approvals and the Ask / Last reply widgets.

## How it works

```
 your phone                           your computer or home server
┌───────────────────┐    https    ┌──────────────────┐  localhost  ┌────────┐    ┌────────┐
│ Murmur Companion  │ ──────────▶ │ Companion relay  │ ──────────▶ │ Murmur │ ─▶ │ Hermes │
│ (this Android app)│ ◀────────── │ (relay/, Node.js)│ ◀────────── │        │    │ Agent  │
└───────────────────┘   events    └──────────────────┘   Web Push  └────────┘    └────────┘
```

The relay registers with Murmur as one more notification "device". When Murmur has something for you it pushes to the relay, which decrypts it and streams it to your phone over a held-open connection. **Murmur is not modified.** Your data stays on your own machines.

Your phone has to reach both the relay and Murmur over **https** (the app refuses plain http). The easy free way is [Tailscale](https://tailscale.com), which keeps everything private to your own devices and issues real certificates. [docs/SETUP.md](docs/SETUP.md) walks through it, and covers a Cloudflare Tunnel or your own reverse proxy too.

## Setup in short

1. Have Murmur and Hermes working in a browser.
2. Install Node.js 20+ and download this repo.
3. Publish Murmur and the relay over https (with Tailscale, two `tailscale serve` commands).
4. In `relay/`: `node relay.mjs init --murmur-public https://... --relay-public https://...`, then `node relay.mjs doctor`, then `npm start`.
5. Install the app on your phone, open it, and paste the pairing code from `http://127.0.0.1:3010/pair`.

The full version, with every command and what you should see, is **[docs/SETUP.md](docs/SETUP.md)**. If something fails, run `node relay.mjs doctor` and read [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).

## Requirements

- A working **Murmur** connected to a working **Hermes Agent**.
- A computer that stays on and runs **Node.js 20+**. The relay has no npm dependencies.
- An **Android 12+** phone.
- **https addresses** for Murmur and the relay that your phone can reach (and that Murmur's own server can reach, for the relay).
- Optional: a small, cheap model for the chat scan; a Todoist token for filing captured tasks.

## What has been tested

| Tested | Not tested |
|---|---|
| Murmur 0.15.0 standalone server on Windows 11 with Hermes Agent, Tailscale Serve, relay on the same PC | Murmur on umbrelOS, in Docker, or the Mac/Linux desktop apps |
| One Pixel phone | Other phones and Android versions, vendor battery managers |
| Pairing, push, approvals, Needs you, widgets, tiles, typed capture | Voice capture through every speech recogniser; the fingerprint gate on every device |
| Murmur without a password | Murmur with `MURMUR_PASSWORD` (implemented from Murmur's source, not exercised live) |

If you try an untested setup, an issue saying what happened, good or bad, helps.

## Security in one paragraph

Whoever holds the **pairing code** can read your notifications and answer your agents' approvals, so treat it like a password. The pairing page only opens on the computer running the relay (never through a proxy or tunnel). The relay's token lives in `relay/data/relay.json`; keep that private. The app keeps it in app-private storage. Read [SECURITY.md](SECURITY.md) before exposing anything beyond your own devices.

## Build the app yourself

Needs JDK 17 and the Android SDK (platform 35):

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk   (set MN_BUILD_DIR to build elsewhere)
```

Release APKs on the Releases page are signed by CI with the project's key. Your own build uses Android's debug key unless you set `MN_KEYSTORE_PROPS` to a `.properties` file (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). An APK signed with a different key cannot update over another; uninstall first.

## Develop

```sh
cd relay && npm test               # relay tests (no dependencies)
./gradlew :app:testDebugUnitTest   # app tests
```

[CONTRIBUTING.md](CONTRIBUTING.md) · [Setup](docs/SETUP.md) · [Configuration](docs/CONFIG.md) · [Optional modules](docs/MODULES.md) · [Run at startup](docs/AUTOSTART.md) · [Troubleshooting](docs/TROUBLESHOOTING.md)

## License

MIT, see [LICENSE](LICENSE). Third-party code: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). "Murmur", "Umbrel", "Hermes" and "Nous Research" belong to their owners and are used only to say what this works with.

Another offering from [Pharr Labs](https://apps.pharrlabs.com).
