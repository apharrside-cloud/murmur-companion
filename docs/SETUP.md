# Setup guide

Read this top to bottom the first time. About 20 minutes if Murmur already works.

**What you are building:** Murmur Companion is two pieces. The **relay** runs on the same computer as your Murmur. The **app** runs on your phone and talks to the relay. Neither piece contains Murmur or Hermes; both need yours.

## Step 0. Check what you have

| You need | How to check |
|---|---|
| Murmur running | Open its address in a browser on the computer (usually `http://127.0.0.1:3000`). You should see your agents. |
| Hermes connected to it | Open an agent and send "hi". You should get a reply. |
| Node.js 20+ | `node --version` prints `v20` or higher. Get it at nodejs.org. |
| An Android 12+ phone | Settings > About phone. |

**If you do not have Murmur yet, stop here** and install it from [github.com/getumbrel/murmur](https://github.com/getumbrel/murmur) (it can bring its own Hermes, or use one you have). Murmur Companion cannot work without it.

## Step 1. Get the relay

```sh
git clone https://github.com/apharrside-cloud/murmur-companion
cd murmur-companion/relay
node relay.mjs doctor      # expect a config error for now: that is normal
```

(No clone? Download the ZIP from GitHub and open a terminal in its `relay` folder.) There is nothing to `npm install`.

## Step 2. Decide how your phone reaches your computer

The phone needs **https** addresses for **Murmur** and for the **relay**. Pick one:

### Option A: Tailscale (recommended: free, private)
1. Install [Tailscale](https://tailscale.com/download) on the computer **and** on the phone, and sign in to the same account on both.
2. In the Tailscale admin console (login.tailscale.com/admin/dns) turn on **MagicDNS** and **HTTPS Certificates**.
3. Find your computer's name: `tailscale status` shows something like `my-pc`, and the full name is `my-pc.tailXXXX.ts.net`.
4. Publish both services (the numbers 8444 and 8445 are just free ports):
   ```sh
   tailscale serve --bg --https=8444 http://127.0.0.1:3000   # Murmur (change 3000 if yours differs)
   tailscale serve --bg --https=8445 http://127.0.0.1:3010   # the relay
   ```
5. Your addresses are now `https://my-pc.tailXXXX.ts.net:8444` (Murmur) and `https://my-pc.tailXXXX.ts.net:8445` (relay). Open the first one in the phone's browser, with Tailscale on, to confirm Murmur loads.

Only devices on your tailnet can reach these. Do **not** use `tailscale funnel` for this.

### Option B: Cloudflare Tunnel or your own reverse proxy
Any setup works that gives you two https addresses reachable from your phone, and reachable from the computer itself (Murmur's server calls the relay's address to deliver pushes). Murmur only accepts some host names by default: if you use your own domain, add it to Murmur's `MURMUR_ALLOWED_HOSTS` (see Murmur's STANDALONE.md). If the addresses are reachable from the public internet, read [SECURITY.md](../SECURITY.md) first: the relay's token is the only thing protecting it, and your Murmur should have its own password.

### Option C: Same Wi-Fi only
Plain `http://192.168...` addresses do **not** work. The app requires https. Use Option A.

## Step 3. Configure the relay

```sh
node relay.mjs init --murmur-public https://my-pc.tailXXXX.ts.net:8444 --relay-public https://my-pc.tailXXXX.ts.net:8445 --name Atlas
```

- `--name` is what your agent is called, e.g. `Hermes` or the name you gave it in Murmur. It is used in the app and as the trigger word for voice capture.
- `--profile` is the Hermes profile that new voice chats go to (default `default`).
- Add `--password '...'` only if you protected Murmur with `MURMUR_PASSWORD`.

This writes `relay/data/config.json`. Every setting is explained in [CONFIG.md](CONFIG.md).

## Step 4. Check, then start

```sh
node relay.mjs doctor
```

Every line should say `ok`. Each `FAIL` prints what to do. The usual ones: Murmur is not running; the https addresses are not published yet (Step 2); the profile name does not exist.

Then start the relay and leave it running:

```sh
npm start
```

You should see `relay on 127.0.0.1:3010`. Within a minute the relay registers itself in Murmur (Murmur > Settings > Notifications shows an extra device). To keep it running after a reboot, see [AUTOSTART.md](AUTOSTART.md).

## Step 5. Install the app

- **From a release:** on your phone open the repo's **Releases** page and download the `.apk`. Android will ask you to allow installs from your browser. This is a sideloaded app; it is not on Google Play.
- **Or build it:** see the README ("Build the app yourself") and install with `adb install -r app-debug.apk`.

## Step 6. Pair the phone

1. On the **computer**, open `http://127.0.0.1:3010/pair` in a browser. You see a QR code and a long pairing code. (This page refuses to load from any other device. That is on purpose.)
2. On the phone, open **Murmur Companion**. It starts on a "Connect to your Murmur" screen.
3. Either tap **Paste from clipboard** after copying the code (send it to yourself in a note, or copy it from the page), or open the pairing link on the phone. Tap **Pair**.
4. The app checks the relay. On success it opens Murmur.

If pairing says it cannot reach the relay: is Tailscale on, on the phone? Does the relay's https address load in the phone's browser (`.../healthz` should show `{"ok":true...}`)?

## Step 7. Make notifications reliable

Settings in the app (long-press the launcher icon > Companion settings):
- Allow notifications when Android asks.
- Tap **Allow background activity (battery)**. Without it, some phones kill the connection after a while.
- **Send test notification** should pop a notification within seconds. If so, push works end to end.

## What now works, and what is off

Working now: Murmur on the phone, push, approve/reject from notifications, Needs you (live approvals and questions), Ask your agent and Last reply widgets, tiles, and voice capture that starts a chat.

Off until you configure them (all optional, see [MODULES.md](MODULES.md)): the chat scan in Needs you, task-list counts in the Status/Brief widgets, filing captures to Todoist or notes, the One-number widget.

## Updating
- **Relay:** `git pull`, restart it.
- **App:** install the newer APK over the old one (same signing key required).
- **Murmur:** updating Murmur is fine; if it is reset, the relay re-registers within a minute.
