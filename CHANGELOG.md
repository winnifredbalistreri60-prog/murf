# Changelog

Notable changes to Hermex. Version headings correspond to App Store releases;
unreleased changes accumulate at the top. Format follows
[Keep a Changelog](https://keepachangelog.com/) with Added / Changed / Fixed /
Security sections per release.

## [Unreleased]

## MURF 1.10.2 — 2026-10-24
- **Simplified menu**: primary sidebar is Screen → Harness → Bots → Devices → Local → Drive → Own VDS → Tasks → Kanban → More. Rare items (Phone & Termux, Providers, Bot tasks, Server CLIs, Skills, Memory, Insights) live under More.
- **Harness hub**: one «Harness» entry with tabs On phone | On Hermes (remote pairing / open web UI).
- **Упрощённое меню**: основной список — Экран → Harness → Боты → Устройства → Локальная среда → Google Диск → Свой VDS → Задачи → Канбан → Ещё. Редкие пункты — в «Ещё».
- **Единый Harness**: вкладки «На телефоне» и «На Hermes» (удалённое сопряжение / открытие веб-UI).

## MURF 1.10.1 — 2026-10-24
- **DeepSeek Harness remote pairing**: full `?auth=` link or address + 8-char code → `dsh_remote_session` cookie for Harness web UI.
- **Сопряжение с DeepSeek Harness**: «Ввести код» принимает ссылку `?auth=` или адрес + 8-символьный код.

## MURF 1.5.0 — 2026-10-17
- **Боты (несколько агентов)**: меню → «Боты». У бота имя, эмодзи, инструкции (персона), рабочая папка, бэкенд (Hermes по профилю или установленный CLI), модель и режим доступа. У каждого бота свой список чатов; боты работают на сервере независимо (до 4 запусков одновременно). Бот может передать задачу другому боту: вручную («Передать») или сам, строкой `@@handoff <имя бота>: <задача>` (глубина цепочки до 3).
- **Установленные CLI**: экран «CLI на сервере» показывает Codex, Grok, Claude Code, Gemini, opencode и Hermes: установлен ли, версия, выполнен ли вход (без токенов — MURF их не читает, CLI запускаются как есть от пользователя сервера). Hermes, Grok, Gemini и opencode подключаются по ACP (подтверждения действий приходят в MURF кнопками), Codex — `codex exec --json`, Claude Code — `claude -p --output-format stream-json`; шаги, мысли, команды и правки видны в чате бота.
- **Безопасность CLI**: включить CLI для бота можно только после подтверждения (сервер повторно спрашивает при смене бэкенда или режима). По умолчанию режим «Безопасный»: Codex `sandbox_mode=workspace-write`, Claude `--permission-mode default`, ACP-режим default; «Только чтение» — read-only/plan; «Как настроено в CLI» — отдельно и с подтверждением. Флаги вида `--dangerously-skip-permissions`/`--yolo`/`danger-full-access` MURF не передаёт никогда (проверка перед каждым запуском). Каждый запуск пишется в `~/agent-phone/bots/runs.jsonl` (команда, папка, итог, длительность, подтверждения; без текста запроса).
- **Подзадачи отдельными блоками**: в обычном чате вызовы delegate_task (и Task/Agent у CLI в чатах ботов) — сворачиваемые карточки со статусом, временем и своей прокруткой; кнопка ⚡ в заголовке чата открывает панель «Задачи» (идут/завершены). «Задачи ботов» в меню — все запуски ботов на сервере.
- Сервер: новая служба `murf-bots` (`server/phone-relay/murf_bots.py`, 127.0.0.1:8792, systemd user unit `murf-bots.service`), relay проксирует `/phone/api/bots/*` (cookie WebUI + X-Agent-App), включая поток SSE. Установщик «Свой VDS» ставит и её (`murf-bots.service`).

## MURF 1.4.0 — 2026-10-17
- **Вход по QR без пароля.** На сервере `murf pair` показывает QR (адрес сервера + одноразовый код на 3 минуты, одно использование); в MURF на экране входа — «QR-код» или «Код вручную». Код меняется на токен устройства, который хранится только в зашифрованном хранилище (AES-GCM, ключ в Android Keystore); на сервере — только его sha256. Когда сессия WebUI истекает, MURF сам получает новую по токену, без пароля. Вход паролем остался запасным.
- **Экран «Устройства»**: список привязанных устройств (имя, последний вход, IP), «Отозвать» (сразу закрывает сессии устройства), «Показать QR» для второго телефона/планшета, «Привязать это устройство» для тех, кто вошёл паролем. На сервере то же: `murf devices`, `murf revoke <id>`.
- Сервер: relay MURF (`phone_relay.py` + `murf_pairing.py`) получил эндпоинты `/phone/up/pair/claim|session` (публичные, с ограничением частоты) и `/phone/api/pair/new|devices|revoke` (за cookie WebUI + X-Agent-App). WebUI не менялся.
- **Свой VDS из меню** («Свой VDS» в меню и на экране входа): адрес, порт, пользователь, пароль или ключ SSH, домен (необязательно, иначе `<ip>.sslip.io`), e-mail для Let's Encrypt. MURF проверяет сервер, показывает отпечаток ключа SSH и что будет установлено, после подтверждения ставит Hermes Agent (официальный установщик), hermes-webui, nginx + HTTPS, службы systemd и relay, показывает ход по шагам и сам подключается по QR-коду. Установка идёт на сервере в фоне: если связь оборвётся, MURF переподключится и дочитает журнал. Пароль/ключ не сохраняются и стираются из памяти после установки. Скрипт: `server/vds-deploy/murf-install.sh` (идемпотентный).
- **Планшеты**: на широком экране (WindowSizeClass Expanded) экран агента открывается справа от чата, ширину меняет перетаскиваемый разделитель; на средней ширине (Medium) — нижним листом. При смене раскладки, повороте и изменении размера окна остаётся одно и то же VNC-подключение.
- Аудит хранения паролей: пароль WebUI в приложении не сохраняется (только cookie сессии и токен устройства в зашифрованном хранилище); пароль в форме не попадает в сохранённое состояние экрана.

## MURF 1.3.0 — 2026-10-17
- **Чат больше не зависает** при большом пинге и длинном выводе агента. Найденные причины: каждый кусок потока перерисовывал весь экран чата (обновление состояния каждые 48 мс, рассуждения — на каждый токен); незакрытый блок кода во время печати целиком прогонялся через Markwon + подсветку Prism4j 10 раз в секунду, а разбор не отменялся и копился в фоне; огромные блоки кода выводились одним Text; текст ответа склеивался строками (O(n²)) и пересчитывался по словам на каждом шаге.
- Исправлено: буфер потока (StringBuilder), строка ответа обновляется отдельно от экрана не чаще 80 мс, весь экран — примерно раз в секунду; рассуждения — раз в 120 мс; разбор Markdown в фоне, по одному за раз и по готовым блокам (~2000 символов); незакрытый блок кода показывается простым кодом без подсветки.
- **Длинный код сворачивается** после 40 строк: «Показать всё (N строк)», при печати видны последние строки; подсветка синтаксиса в фоне и только для показанных строк.
- Ровно одно SSE-подключение к потоку: события от старого подключения отбрасываются.
- **Мягкое переподключение**: NetworkCallback — переподключение сразу, как появилась сеть или сменилась (Wi-Fi ↔ мобильная); без сети попытки не тратятся; после разрыва догружается только пропущенное (replay с курсора); 5 секунд без баннера «Подключение…»; список чатов обновляется после открытого чата.
- **Очередь неотправленных**: сообщение без сети остаётся в чате бледным с пометкой «отправится при подключении», удержание — «Отправить сейчас», «Изменить», «Отменить отправку»; очередь хранится в файле и переживает перезапуск, отправляется сама по порядку. Повторно отправляется только то, что точно не дошло до сервера (ошибка DNS/соединения), чтобы не было дублей.
- Сессия из Telegram (только чтение): вместо «The server refused access» — «Это сессия из Telegram, только чтение. Начните новый чат.», сессия помечается как только для чтения.


## [1.5.0] - TBA

### Added
- Kanban boards: browse cards by status column, view card detail with comments
  and operational history, create and edit cards, move cards through their
  workflow, act on many cards at once with accessible bulk actions, manage
  boards with shared active-board controls, preview and run the dispatcher from
  a toolbar sheet, and stay current through live updates with offline
  reconciliation.
- Settings toggles to hide unused parts of the app: session-list entries
  (Tasks, Kanban, Skills, Memory, Insights, Active Profile, Projects) and chat
  controls (Files button, Git actions). Everything stays visible by default.
- Opt-in response speed metrics.
- Public open-source release of the Hermex codebase.

### Fixed
- Interrupted backend streams now recover instead of stalling the response.
- Opening a transcript no longer jitters.
- Reopening a chat bounds the cached transcript instead of loading it all.
- Deep-linked sessions no longer lose the race to last-session restore.
- The file browser keeps the latest directory navigation.
- The session list refreshes after returning from a new chat.
