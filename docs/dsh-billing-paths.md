# DeepSeek Harness — payment from MURF (2026-10-25, 1.10.7)

## Product path (client integration)

1. MURF → Harness → **На Hermes**
2. Pair remote (`?auth=` / address + code) → cookie `dsh_remote_session` (does **not** set Hermes LoggedIn)
3. **Открыть клиент Harness** — in-app WebView with cookie sync (remote UI). Not Agent Screen / VNC.
4. **Пополнить DeepSeek** — opens https://platform.deepseek.com/top_up in the system browser.

## Desktop GUI (reference only)

Full dsh-tauri on Hermes still has Settings → Учётная запись → Пополнить. MURF no longer requires streaming that Desktop to pay.
