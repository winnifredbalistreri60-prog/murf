<p align="center"><img src="android/app/src/main/res/drawable-nodpi/hermex_app_icon.png" width="96" alt="MURF"></p>

# MURF

**MURF** — Android-клиент для своего ИИ-агента на базе [Hermes Agent](https://github.com/NousResearch/hermes-agent) и [hermes-webui](https://github.com/nesquena/hermes-webui).
Это форк [Hermex](https://github.com/uzairansaruzi/hermex) (нативный клиент hermes-webui) с упором на работу с телефона: живой экран агента, мост к телефону через Termux, локальный режим «весь агент на телефоне».

> English summary is [below](#english).

| Главное меню | Экран агента в чате | Экспорт беседы | Локальный режим |
|---|---|---|---|
| ![](docs/screenshots/home.png) | ![](docs/screenshots/live-screen.png) | ![](docs/screenshots/chat-export.png) | ![](docs/screenshots/local-mode.png) |

| Телефон и Termux | Google Диск | Размер шрифта | Язык |
|---|---|---|---|
| ![](docs/screenshots/phone-bridge.png) | ![](docs/screenshots/google-drive.png) | ![](docs/screenshots/font-size.png) | ![](docs/screenshots/language.png) |

## Возможности

- **Чат с агентом** — потоковые ответы, шаги инструментов, мышление, **субагенты**, **подтверждения** опасных действий (в чате и уведомлением).
- **Живой экран агента** (noVNC) — открывается отдельно или поверх чата, два режима:
  - «Смотреть» — только просмотр, ввод отбрасывается **на сервере** (отдельный прокси «только просмотр»), а не только в приложении;
  - «Управлять» — тап = клик, удержание = правый клик, два пальца = прокрутка, зум и перемещение, панель клавиш (Esc/Tab/Ctrl/Alt/Shift/Enter/стрелки), ввод кириллицы, снимок экрана; управление разрешено только с вашего Origin.
- **Мост «Телефон»** — агент на сервере просит телефон выполнить действие (команда в Termux, фото, геопозиция, файлы, буфер обмена, уведомления, батарея, SMS по желанию); каждое действие подтверждается, кроме разрешённых «всегда».
- **Google Диск** — отправка сообщений, бесед, файлов и снимков экрана в Диск; подключение Диска к агенту через rclone (токен, без пароля).
- **Экспорт** — беседа в Markdown/PDF/буфер, «Поделиться», сохранение в Загрузки.
- **Проверка обновлений** сервера: Hermes Agent и WebUI, пробный запуск, резервная копия и автоматический откат.
- **Локальный режим** — весь агент на телефоне: Ubuntu (proot) в Termux, Hermes Agent, WebUI, рабочий стол XFCE + браузер, вход в провайдера (device code) прямо из среды; переключение профиля «Сервер / Телефон (локально)» и предложение переключиться, если сервер недоступен. **Проверено на реальном телефоне** (vivo X300, Android 16, arm64, MURF 1.2.4).
- Всё, что умеет Hermex: сессии, проекты, задачи, канбан, навыки, память, статистика, несколько серверов и профилей.

Новое в 1.2.x:

| Версия | Что появилось |
|---|---|
| 1.2.0 | Локальный режим: установка с прогрессом и продолжением после обрыва, вход в провайдера, прокси, перенос настроек с сервера, профиль «Телефон (локально)» |
| 1.2.1 | Запасная установка без proot-distro (чистый proot) |
| 1.2.2 | **Выбор языка** — «Как в системе» или любой встроенный (на Android 13+ и в системных настройках языка приложения); экраны MURF на русском и английском, для остальных языков — английский |
| 1.2.3 | Экран агента открывается в 2–3 раза быстрее (параллельная загрузка noVNC, кэш) |
| 1.2.4 | Локальный экран: исправлено вечное «Подключение…»; **«Диагностика»** локальной среды с отчётом; «Перезапустить экран» и «Показать журнал» прямо на экране |
| 1.2.5 | Экран агента в чате — **по кнопке** с монитором в шапке (зелёная точка — агент работает); мини-превью в углу чата — по желанию: «Настройки → Взаимодействие → Мини-превью экрана в чате» (перетаскивается, закрывается ×) |

Из 1.1.x остаются: **размер шрифта** для всего приложения, **свайпы** между разделами и к экрану агента (отключаются в настройках), режим «Управлять», экран поверх чата.

## Архитектура

```mermaid
flowchart LR
  subgraph Phone["Телефон"]
    App["MURF (Android)"]
    Termux["Termux (+ Termux:API)"]
    Local["Локальный режим:<br>Ubuntu proot → Hermes + WebUI + XFCE"]
  end
  subgraph Server["Сервер"]
    Nginx["nginx (TLS)<br>/  /agent/  /screen/  /phone/"]
    WebUI["hermes-webui :8787"]
    Agent["Hermes Agent<br>(gateway, API :8642)"]
    Desk["Рабочий стол агента<br>Xvnc + websockify/noVNC"]
    Relay["phone-relay :8790/:8791"]
  end
  App -- HTTPS / SSE --> Nginx
  App -- WebSocket (RFB) --> Nginx
  Nginx --> WebUI --> Agent
  Nginx --> Desk
  Nginx --> Relay
  Agent -- MCP agent_tools --> Relay
  Relay -. запросы к телефону .-> App
  App -- RUN_COMMAND --> Termux
  App -. http://127.0.0.1:18080 .-> Local
```

## Сборка

Нужны JDK 17 и Android SDK (platform 36).

```bash
cd android
./gradlew assembleDebug                      # app/build/outputs/apk/debug/
# адрес сервера по умолчанию на экране подключения (необязательно):
./gradlew assembleDebug -Pmurf.defaultServerUrl=https://your-server.example
```

Подписанный релиз: задайте `HERMEX_ANDROID_KEYSTORE_FILE`, `HERMEX_ANDROID_KEY_ALIAS` и пароли хранилища ключей через переменные окружения или `~/.gradle/gradle.properties` (никогда не в репозитории), затем `./gradlew assembleRelease`.

`applicationId` — `ru.dredd20.agent` (оставлен ради обновлений поверх уже установленных сборок). Для своей сборки смените его в `android/app/build.gradle.kts`.

## Сервер

Нужен свой сервер с Hermes Agent и hermes-webui; MURF подключается к нему по HTTPS с паролем WebUI. Примеры конфигураций (с заглушками `your-server.example`) — в [`server/`](server/README.md):

- `server/examples/nginx-murf.conf` — nginx: WebUI, API агента, экран, мост «Телефон»;
- `server/agent-screen/` — страница экрана (noVNC), прокси «только просмотр», unit-файлы systemd;
- `server/phone-relay/` — мост «Телефон» и MCP-сервер `agent_tools` для агента;
- `server/local-mode/` — скрипты локального режима (Termux + proot).

## Локальный режим: установка и решение проблем

Весь агент работает на телефоне, без сервера: Termux → Ubuntu 24.04 (proot) → Hermes Agent + WebUI + рабочий стол XFCE. Приложение подключается к `http://127.0.0.1:18080` (только локально).

**Требования:** телефон arm64 с современным ядром (проверено на Android 16; Android 9 и ядра 4.x не подходят), [Termux](https://f-droid.org/packages/com.termux/) из F-Droid или GitHub (не из Google Play), **≥ 4,5 ГБ** свободного места (среда занимает ~4 ГБ), интернет на время установки.

### Установка

1. Установите Termux, в MURF откройте «Телефон и Termux» и выдайте разрешение «Запуск команд в Termux».
2. Меню → «Локальная среда» → **«Установить»**. Откроется Termux с журналом; установка идёт 20–60 мин и продолжается с места остановки, если её прервать. Прогресс виден в MURF.
3. **«Запустить»** (WebUI поднимается 1–3 мин) → **«Войти в провайдера»** → откройте ссылку и подтвердите код.
4. В разделе «Подключение» выберите **«Телефон (локально)»**.

Вручную в Termux: `curl -fsSL https://github.com/winnifredbalistreri60-prog/murf/releases/latest/download/install.sh | bash`, затем `murf-local install && murf-local start`.

### Обновление

После обновления MURF: «Локальная среда» → **«Обновить»** (скрипты из приложения кладутся в Termux, уже установленное не скачивается заново) → **«Остановить»** → **«Запустить»**. В Termux то же самое: `murf-local update && murf-local restart`.

### Если что-то не работает

- **«Диагностика»** («Локальная среда» → «Диагностика») проверяет Ubuntu, службы, порты, файлы и WebSocket экрана, VNC и ограничения Android и выдаёт короткий отчёт ✅/⚠️/❌ с подсказками. Кнопка **«Отправить отчёт»** — поделиться им (или «Копировать»).
- **Экран не подключается** — через 15 с на экране появится причина и кнопки **«Перезапустить экран»** и **«Показать журнал»**.
- Команды Termux:

| Команда | Что делает |
|---|---|
| `murf-local status` | состояние в JSON |
| `murf-local doctor` | та же проверка, что «Диагностика» |
| `murf-local restart-screen` | перезапуск рабочего стола и websockify внутри работающей среды |
| `murf-local logs screen` | хвост журналов экрана (рабочий стол, websockify, прокси, nginx) |
| `murf-local logs gateway` / `webui` / `setup` | журналы агента, WebUI, установки |
| `murf-local restart` / `stop` | перезапуск / остановка среды |
| `murf-local proxy URL\|off` | прокси для провайдера |

### Чтобы Android не убивал среду

- **Для разработчиков → «Отключить ограничения дочерних процессов»** (Android 12+). Без этого Android убивает «лишние» процессы Termux (phantom process killer) — среда или экран внезапно пропадают. На Android 12–13 без этого пункта: `adb shell device_config put activity_manager max_phantom_processes 2147483647`.
- **Wakelock:** оставьте уведомление Termux и включите в нём «Acquire wakelock» (`murf-local start` делает это сам).
- **Батарея:** MURF и Termux — «Без ограничений». **vivo / OriginOS:** «i Менеджер» → управление приложениями → разрешить **автозапуск** и **высокое энергопотребление в фоне** для Termux и MURF (в MURF есть кнопка «vivo: автозапуск/фон»).

### Провайдер, VPN, прокси

Если `api.x.ai` недоступен из вашей сети (например, ответ 403), включите VPN на телефоне на весь трафик (включая Termux) или задайте прокси: «Локальная среда» → «Прокси» (например `socks5h://127.0.0.1:10808` от v2rayNG) или `murf-local proxy URL`. «Проверить доступ» покажет, работает ли прямое соединение и прокси.

### Проверенные устройства

| Устройство | Android | Архитектура | MURF | Результат |
|---|---|---|---|---|
| vivo X300 | 16 | arm64 | 1.2.4 | ✅ локальный режим: установка, агент, WebUI, экран агента |
| Тестовая машина (proot) | — | x86_64 | 1.2.4 | ✅ полная проверка скриптов: установка, службы, экран (просмотр/управление), диагностика |
| Эмулятор | 9 (ядро 4.4) | x86_64 | 1.2.1 | ❌ Ubuntu 24.04 в proot не работает (старое ядро) |

Будем рады отчётам о других телефонах — откройте issue и приложите отчёт «Диагностика».

### Ограничения

- Android 9 и старые ядра (4.x) не поддерживаются: Ubuntu 24.04 в proot там не работает.
- Docker и systemd недоступны (службы запускает собственный супервизор).
- Нужно ≥ 4,5 ГБ места; первая установка — 20–60 мин.
- Рабочий стол и браузер агента на телефоне медленнее, чем на сервере; при нехватке памяти Android может выгрузить среду.
- Мост «Телефон» и Google Диск в первую очередь рассчитаны на серверный профиль.

## Безопасность

- Пароль WebUI и cookie хранятся в зашифрованном хранилище (EncryptedSharedPreferences); секреты в репозитории не хранятся.
- Экран агента и API моста закрыты авторизацией WebUI (nginx `auth_request`), управление экраном — только с вашего Origin.
- Каждое действие на телефоне подтверждается; ссылки для файлов одноразовые и короткоживущие.
- Локальный режим слушает только `127.0.0.1`; перенос секретов с сервера — только после отдельного подтверждения.

Сообщить об уязвимости: см. [SECURITY.md](SECURITY.md).

## Благодарности

[Hermex](https://github.com/uzairansaruzi/hermex) (Uzair Ansar) · [Hermes Agent](https://github.com/NousResearch/hermes-agent) (Nous Research) · [hermes-webui](https://github.com/nesquena/hermes-webui) · [noVNC](https://github.com/novnc/noVNC) · [Termux](https://termux.dev). Подробнее — [NOTICE.md](NOTICE.md).

Лицензия — [MIT](LICENSE).

---

## English

**MURF** is an Android client for your own AI agent built on [Hermes Agent](https://github.com/NousResearch/hermes-agent) and [hermes-webui](https://github.com/nesquena/hermes-webui), forked from [Hermex](https://github.com/uzairansaruzi/hermex).

**Features:** streaming chat with tool steps, subagents and approvals; a live agent screen (noVNC) with a **view mode enforced server-side** (a view-only proxy drops input) and a control mode (tap/right-click/scroll, zoom, key bar, Cyrillic input); a phone bridge (the agent asks the phone to run Termux commands, take photos, get location, move files — each confirmed); Google Drive export and rclone hookup; Markdown/PDF export; app-wide **font size**; **swipe** navigation; **server update checker** with dry run and rollback; in-app **language picker** (system default or any bundled language; MURF screens in Russian and English, English fallback); **local mode** — the whole agent on the phone (Ubuntu 24.04 in proot via Termux) with built-in **diagnostics**. Since 1.2.5 the agent screen in chat opens **on demand** from the monitor button in the chat header (green dot = agent is working); the floating mini-preview is optional (Settings → Interaction, off by default; draggable, dismiss with ×).

**Local mode — tested on a real device:** vivo X300, Android 16, arm64, MURF 1.2.4.

- Requirements: an arm64 phone with a modern kernel (tested on Android 16), Termux from F-Droid/GitHub, ≥ 4.5 GB free space. Android 9 / 4.x kernels are not supported.
- Install: grant MURF “Run commands in Termux” → Local environment → Install → Start → Log in to provider → switch the profile to “Phone (local)”.
- Update: Local environment → Update → Stop → Start (or `murf-local update && murf-local restart`).
- Troubleshooting: Local environment → **Diagnostics** (send the report), or `murf-local doctor`, `murf-local restart-screen`, `murf-local logs screen`. If the screen does not connect within 15 s, the app shows the reason with “Restart screen” / “Show log”.
- Keep it alive: Developer options → **Disable child process restrictions** (Android 12+ phantom process killer), keep the Termux wakelock, set MURF and Termux battery to “Unrestricted”; on vivo allow autostart and high background power use.
- If the provider API is blocked in your network, use a phone-wide VPN or set a proxy (`murf-local proxy socks5h://127.0.0.1:10808`).

Build: `cd android && ./gradlew assembleDebug` (JDK 17, Android SDK 36). Optional default server: `-Pmurf.defaultServerUrl=https://your-server.example`. Server examples with placeholders live in [`server/`](server/README.md). License: MIT.
