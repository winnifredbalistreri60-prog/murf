# NOTICE / Благодарности

MURF is a fork of **Hermex** — native client for hermes-webui — Copyright (c) 2026 Uzair Ansar, MIT License.
https://github.com/uzairansaruzi/hermex

MURF talks to, installs or bundles scripts for the following projects (each under its own license):

- **Hermes Agent** — Nous Research — https://github.com/NousResearch/hermes-agent (installed on the server / in local mode, not bundled)
- **hermes-webui** — https://github.com/nesquena/hermes-webui (server; downloaded in local mode, not bundled)
- **noVNC** — https://github.com/novnc/noVNC (MPL-2.0; used by the agent screen page, downloaded at install time)
- **websockify**, **TigerVNC**, **XFCE**, **nginx**, **Ubuntu base** — installed from distribution packages in local mode
- **Termux**, **Termux:API**, **proot-distro** — https://termux.dev (optional fallback path for local mode)
- **PRoot** (Termux fork, GPL-2.0-or-later) — https://github.com/termux/proot — **bundled** in the APK as `libproot.so` / `libproot-loader*.so` for the embedded local mode; built from source by `android/native/build-proot.sh` (commit 52d65a4d). Source and license: `android/app/src/main/assets/licenses/`
- **talloc** 2.5.0 (LGPL-3.0-or-later) — https://talloc.samba.org — statically linked into `libproot.so`
- Android libraries: AndroidX, Jetpack Compose, Kotlin coroutines/serialization, Room, OkHttp, Markwon, Haze (see `android/gradle/libs.versions.toml`)
- Icons: Lucide (ISC) — https://lucide.dev

"Hermes" and other names belong to their respective owners. MURF is not affiliated with Nous Research.
