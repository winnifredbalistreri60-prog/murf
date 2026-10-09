# Contributing / Участие

- Issues and PRs are welcome (Russian or English). / Задачи и PR — на русском или английском.
- Build: `cd android && ./gradlew assembleDebug testDebugUnitTest`.
- Keep secrets out of the repo: no keystores, `.env`, tokens, real hostnames or IPs. Use `your-server.example` in examples.
- New user-visible MURF strings: use `tr("Русский", "English")` (see `agent/Lang.kt`) or the Hermex localization catalog.
- One logical change per PR; describe how you tested it (device / emulator / API level).
