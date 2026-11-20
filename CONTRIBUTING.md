# Contributing

Issues and pull requests are welcome. For anything bigger than a fix, open an issue first.

- Relay: plain Node 20+, **no npm dependencies** (keep it that way). `cd relay && npm test`.
- App: Kotlin, no external libraries beyond the Android SDK. `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
- Keep it generic: no personal paths, names, hosts or secrets, no behavior that only makes sense for one setup. Optional modules stay off by default and are gated by `/api/capabilities`.
- Add a test for logic you change (`relay/*.test.mjs`, `app/src/test`).
- If you tested on a setup not listed in the README, say so in your PR.
