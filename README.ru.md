<p align="center"><img src="android/app/src/main/res/drawable-nodpi/hermex_app_icon.png" width="96" alt="MURF"></p>

<h1 align="center">MURF — Android-приложение для своего ИИ-агента</h1>

<p align="center"><a href="README.md">English</a> · <b>Русский</b></p>

<p align="center">
  <a href="https://github.com/winnifredbalistreri60-prog/murf/releases/latest"><img alt="Релиз" src="https://img.shields.io/github/v/release/winnifredbalistreri60-prog/murf?label=release&color=ffb300"></a>
  <a href="https://github.com/winnifredbalistreri60-prog/murf/releases"><img alt="Скачивания" src="https://img.shields.io/github/downloads/winnifredbalistreri60-prog/murf/total?color=ff8f00"></a>
  <a href="https://github.com/winnifredbalistreri60-prog/murf/actions/workflows/android.yml"><img alt="Android CI" src="https://github.com/winnifredbalistreri60-prog/murf/actions/workflows/android.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="Лицензия MIT" src="https://img.shields.io/github/license/winnifredbalistreri60-prog/murf"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android&logoColor=white">
  <a href="https://github.com/winnifredbalistreri60-prog/murf/stargazers"><img alt="Звёзды" src="https://img.shields.io/github/stars/winnifredbalistreri60-prog/murf?style=social"></a>
</p>

<p align="center">
  <b>Android-приложение с открытым кодом для своего ИИ-агента на базе <a href="https://github.com/NousResearch/hermes-agent">Hermes Agent</a>:</b> чат с агентом, живой экран его рабочего стола с управлением, мост к телефону через Termux и локальный режим — весь агент на телефоне (Ubuntu в proot). Свой сервер, любой провайдер модели — открытая альтернатива облачным агентам вроде Manus.
</p>

<p align="center">
  <a href="https://github.com/winnifredbalistreri60-prog/murf/releases/latest"><b>⬇️ Скачать APK</b></a> ·
  <a href="https://winnifredbalistreri60-prog.github.io/murf/ru/">Сайт</a> ·
  <a href="#вопросы-и-ответы">Вопросы и ответы</a>
</p>

<p align="center"><img src="docs/assets/demo-ru.gif" width="270" alt="MURF: главное меню, чат, экран агента, мост к телефону, локальный режим"></p>

## Быстрый старт

1. Скачайте `murf-X.Y.Z.apk` из [последнего релиза](https://github.com/winnifredbalistreri60-prog/murf/releases/latest) и установите (Android 8.0+). Сверьте SHA-256 из описания релиза.
2. **Свой сервер** — введите адрес и пароль hermes-webui (примеры nginx и служб — в [`server/`](server/README.md)).
3. **Или без сервера** — «Локальная среда» → «Установить» (нужен Termux, arm64, ≥ 4,5 ГБ).

## Сравнение

| | **MURF** | Manus (приложение) | OpenManus | Hermes Agent в Telegram |
|---|---|---|---|---|
| Открытый код | ✅ MIT | ❌ | ✅ | ✅ |
| Свой сервер | ✅ | ❌ облако | ✅ | ✅ |
| Нативное Android-приложение | ✅ | ✅ | ❌ | через Telegram |
| Живой экран агента | ✅ просмотр и управление | ✅ просмотр | ❌ | ❌ |
| Агент действует на телефоне (Termux, камера, файлы) | ✅ с подтверждением | ❌ | ❌ | ❌ |
| Весь агент на телефоне | ✅ локальный режим | ❌ | ❌ | ❌ |
| Модель | любой провайдер Hermes Agent | Manus | любая (в настройках) | любой провайдер Hermes Agent |

<sub>По публичной информации на октябрь 2026; поправки приветствуются.</sub>

Сравнение с Codex и Claude Desktop — [ниже](#murf-и-десктоп-приложения-codex-и-claude).

## О проекте

**MURF** — Android-клиент для своего ИИ-агента на базе [Hermes Agent](https://github.com/NousResearch/hermes-agent) и [hermes-webui](https://github.com/nesquena/hermes-webui).
Это форк [Hermex](https://github.com/uzairansaruzi/hermex) (нативный клиент hermes-webui) с упором на работу с телефона: живой экран агента, мост к телефону через Termux, локальный режим «весь агент на телефоне».


| Главное меню | Экран агента в чате | Экспорт беседы | Локальный режим |
|---|---|---|---|
| ![](docs/screenshots/ru/home.png) | ![](docs/screenshots/ru/live-screen.png) | ![](docs/screenshots/ru/chat-export.png) | ![](docs/screenshots/ru/local-mode.png) |

| Телефон и Termux | Google Диск | Размер шрифта | Язык |
|---|---|---|---|
| ![](docs/screenshots/ru/phone-bridge.png) | ![](docs/screenshots/ru/google-drive.png) | ![](docs/screenshots/ru/font-size.png) | ![](docs/screenshots/ru/language.png) |

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

## MURF и десктоп-приложения Codex и Claude

MURF похож на [приложение Codex](https://openai.com/index/introducing-the-codex-app/) от OpenAI и [Claude Desktop](https://code.claude.com/docs/en/desktop) от Anthropic: это тоже клиент для ИИ-агента, который сам работает на компьютере, показывает свои шаги и вызовы инструментов, спрашивает подтверждение перед рискованными действиями и работает с файлами.

Разница:

- **Мобильный клиент.** MURF — Android-приложение. Codex и Claude Desktop — приложения для компьютера.
- **Свой агент.** MURF подключается к [Hermes Agent](https://github.com/NousResearch/hermes-agent) (открытый код) на вашем Ubuntu-сервере или прямо на телефоне (локальный режим). Модель — любой провайдер, которого поддерживает Hermes Agent.
- **Свой рабочий стол.** Агент работает на отдельном рабочем столе сервера, а вы смотрите на него вживую с телефона или управляете им. Codex и Claude управляют приложениями на вашем же компьютере (computer use).
- **Агент и телефон.** Агент может попросить телефон выполнить команду в Termux, сделать фото или передать файл — каждое действие вы подтверждаете.

| | **MURF** | Codex (приложение) | Claude Desktop |
|---|---|---|---|
| Где работает приложение | Android | macOS, Windows; Linux — превью | macOS, Windows; Linux — бета |
| Показывает шаги и вызовы инструментов | ✅ | ✅ | ✅ |
| Подтверждение действий | ✅ | ✅ правила одобрения и песочница | ✅ режимы разрешений |
| Агент работает в графическом интерфейсе | ✅ свой рабочий стол Ubuntu на сервере | ✅ computer use (macOS) | ✅ computer use (бета, Pro/Max, macOS и Windows) |
| Живой экран агента на телефоне | ✅ просмотр и управление | — | — |
| MCP-инструменты | ✅ через Hermes Agent | ✅ плагины и MCP-серверы | ✅ коннекторы = MCP-серверы |
| Агент выполняет действия на телефоне | ✅ Termux, камера, файлы — с подтверждением | — | — |
| Свой сервер / полностью на своём устройстве | ✅ сервер или телефон | — | — |
| Выбор модели | любой провайдер Hermes Agent | модели OpenAI (вход через ChatGPT) | модели Claude |
| Открытый код приложения | ✅ MIT | — (открыт [Codex CLI](https://github.com/openai/codex)) | — |

<sub>✅ — подтверждено официальной документацией на октябрь 2026 ([Codex app](https://openai.com/index/introducing-the-codex-app/), [Codex: computer use и др.](https://openai.com/index/codex-for-almost-everything/), [Codex: одобрения и песочница](https://developers.openai.com/codex/agent-approvals-security), [Claude Desktop](https://code.claude.com/docs/en/desktop), [Claude: computer use](https://support.claude.com/en/articles/14128542-let-claude-use-your-computer-in-cowork), [установка Claude Desktop](https://support.claude.com/en/articles/10065433-install-claude-desktop)); «—» — нет в этих источниках. MURF не связан с OpenAI и Anthropic и не одобрен ими; Codex, ChatGPT, Claude — товарные знаки их владельцев.</sub>

## Планшеты

**Версия для планшетов: в разработке.** План: чат и живой экран агента рядом, альбомная ориентация. Обсуждение — [issue «Tablet version»](https://github.com/winnifredbalistreri60-prog/murf/issues/6).

## ТВ-приставка как сервер (экспериментально)

> **Не проверено на ТВ-приставке.** Ниже — что мы измерили сами, что взято из официальных источников и что является нашей оценкой.

Два варианта:

1. **Приставка с Linux** (прошита Armbian/Debian): Hermes Agent ставится обычным [установщиком для Linux](https://github.com/NousResearch/hermes-agent#linux-macos-wsl2), hermes-webui — по [инструкции](https://github.com/nesquena/hermes-webui#quick-start), nginx и экран — по примерам из [`server/`](server/README.md). MURF подключается к ней как к серверу. proot не нужен.
2. **Приставка с Android**: Termux + скрипты локального режима MURF (Ubuntu 24.04 в proot), как на телефоне.

| Требование | Значение | Откуда |
|---|---|---|
| Архитектура | aarch64 (arm64) | Hermes для Termux — только aarch64 ([README Hermes Agent](https://github.com/NousResearch/hermes-agent)); локальный режим проверен на arm64 |
| Android (вариант 2) | ≥ 7 для Termux | [README Termux](https://github.com/termux/termux-app) |
| Ядро (вариант 2) | эмулятор Android 9 с ядром 4.4 **не работает**: apt в Ubuntu 24.04 падает с `realpath: ENOSYS`; на Android 16 (vivo X300) работает. Точный минимум не определён | наши измерения |
| Место | установка локального режима занимает ~4 ГБ; README требует ≥ 4,5 ГБ свободно. Рекомендуем ≥ 8 ГБ свободно под журналы, кэш браузера и обновления | ~4 ГБ — измерено; 8 ГБ — **оценка** |
| ОЗУ | не измеряли. Рекомендуем ≥ 4 ГБ, если нужен рабочий стол XFCE + браузер | **оценка** |
| Питание и охлаждение | работа 24/7: радиатор/обдув, проводной Ethernet | **рекомендация** |

Типичные aarch64-чипы приставок: Amlogic S905X3 / S905X4 / S922X, Rockchip RK3566 / RK3588 — это не список проверенных устройств. Обсуждение — [issue «TV box server mode»](https://github.com/winnifredbalistreri60-prog/murf/issues/7).

## Планы

- Версия для планшетов (чат и экран агента рядом, альбомная ориентация) — в разработке.
- ТВ-приставка как сервер — экспериментально.
- Больше языков интерфейса ([#4](https://github.com/winnifredbalistreri60-prog/murf/issues/4)), английские скриншоты (готово), проверка локального режима на других телефонах ([#1](https://github.com/winnifredbalistreri60-prog/murf/issues/1)).

## Вопросы и ответы

**Это замена Manus?**
MURF — открытый клиент для вашего собственного агента (Hermes Agent): вы сами выбираете сервер и провайдера модели, экран агента — ваш рабочий стол.

**Нужен ли сервер?**
Нет: локальный режим запускает Hermes Agent, WebUI и рабочий стол XFCE прямо на телефоне (Termux + Ubuntu в proot). Сервер быстрее и удобнее для долгих задач.

**Какие модели?**
Любые, которые поддерживает Hermes Agent (настраиваются в агенте, не в приложении).

**Агент может что-то сделать с телефоном без спроса?**
Нет: каждое действие (команда Termux, фото, файлы, геопозиция) подтверждается, кроме тех, что вы сами разрешили «всегда».

**Где Google Play?**
Пока только APK из [Releases](https://github.com/winnifredbalistreri60-prog/murf/releases) (подписан, SHA-256 в описании релиза).

**Работает на планшете?**
Версия для планшетов в разработке.

**Как помочь?**
⭐ Звезда репозиторию, отчёты «Диагностика» с других телефонов, переводы, issues с метками `good first issue` / `help wanted`. См. [CONTRIBUTING.md](CONTRIBUTING.md).

## Благодарности

[Hermex](https://github.com/uzairansaruzi/hermex) (Uzair Ansar) · [Hermes Agent](https://github.com/NousResearch/hermes-agent) (Nous Research) · [hermes-webui](https://github.com/nesquena/hermes-webui) · [noVNC](https://github.com/novnc/noVNC) · [Termux](https://termux.dev). Подробнее — [NOTICE.md](NOTICE.md).

Лицензия — [MIT](LICENSE).
