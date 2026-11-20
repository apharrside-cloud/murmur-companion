# Troubleshooting

Always start with `node relay.mjs doctor` on the computer. It checks Murmur, Hermes, the profile name and both https addresses, and says what to fix.

| Symptom | Likely cause and fix |
|---|---|
| App opens on "Connect to your Murmur" and will not leave | It is not paired. Follow Setup step 6. The app does nothing without a relay. |
| "Could not reach ..." when pairing | The relay is not running, or the phone cannot reach its https address. On the phone's browser open `<relayPublicUrl>/healthz`. Tailscale on, on the phone? |
| "The relay refused this code" | The relay's identity changed (`relay.json` deleted). Copy a fresh code. |
| Pairing code not accepted | Copy the **whole** code. A link starting `murmurcompanion://pair?c=` also works. |
| Murmur does not load in the app | `murmurPublicUrl` is wrong or not published over https. Open it in the phone browser. |
| No notifications | Settings > Send test notification. If that works, Murmur is not pushing: check Murmur > Settings > Notifications lists the relay as a device, and that Murmur's server can reach `relayPublicUrl`. If the test does not work: battery setting, notification permission, or the push channel shows Disconnected. |
| Push works for a while, then stops | Vendor battery management. Allow background activity in the app's settings; also see dontkillmyapp.com for your phone. |
| `doctor`: Murmur answers at ... FAIL | Murmur is down or `murmurUrl` is wrong. |
| `doctor`: profile does not exist | Set `defaultProfile` to a name it lists. |
| Murmur answers 403 | Murmur only trusts certain host names. Add your domain to `MURMUR_ALLOWED_HOSTS` in Murmur. `*.ts.net` works by default. |
| Murmur sign-in error | You set `MURMUR_PASSWORD` in Murmur; put it in `murmurPassword`. |
| Needs you shows only live approvals | The chat scan is off (default). See MODULES.md. |
| Widget says "Not set up" | That module is not configured on the relay. |
| Voice capture button does nothing useful | The phone needs a speech recogniser (Google app). You can always type in the box. |
| Cannot update the app | The new APK was signed with a different key. Uninstall, install, pair again. |

Relay output is on its console (or in the log file you set up in AUTOSTART.md). To reset everything on the relay side, stop it and delete `relay/data/relay.json`; every phone then needs a new code.

Still stuck? Open an issue with the output of `node relay.mjs doctor` (it prints no secrets) and your Murmur version.
