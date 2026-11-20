# Keep the relay running

The relay must be running for push and widgets to work. Run `npm start` in `relay/` once to test, then pick one of these.

## Windows
Create `start-relay.vbs` in your Startup folder (`Win+R`, `shell:startup`) so there is no console window:

```vb
CreateObject("WScript.Shell").Run "cmd /c cd /d C:\path\to\murmur-companion\relay && node relay.mjs >> relay.log 2>&1", 0, False
```

Make sure `node` is on PATH for that user, or put the full path to `node.exe`.

## macOS (launchd)
`~/Library/LaunchAgents/com.example.murmur-companion.plist`:
```xml
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict>
  <key>Label</key><string>com.example.murmur-companion</string>
  <key>ProgramArguments</key><array><string>/usr/local/bin/node</string><string>relay.mjs</string></array>
  <key>WorkingDirectory</key><string>/path/to/murmur-companion/relay</string>
  <key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>/tmp/murmur-companion.log</string>
  <key>StandardErrorPath</key><string>/tmp/murmur-companion.log</string>
</dict></plist>
```
Then `launchctl load ~/Library/LaunchAgents/com.example.murmur-companion.plist`.

## Linux (systemd user service)
`~/.config/systemd/user/murmur-companion.service`:
```ini
[Unit]
Description=Murmur Companion relay
After=network-online.target

[Service]
WorkingDirectory=/path/to/murmur-companion/relay
ExecStart=/usr/bin/node relay.mjs
Restart=always

[Install]
WantedBy=default.target
```
`systemctl --user enable --now murmur-companion` (and `loginctl enable-linger $USER` to run without logging in).

If Murmur starts later than the relay, that is fine: the relay retries and registers once Murmur is up.
