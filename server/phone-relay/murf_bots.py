#!/usr/bin/env python3
"""MURF bots — несколько именованных агентов поверх Hermes Agent и уже установленных CLI.

Отдельная служба (systemd --user murf-bots.service), слушает только 127.0.0.1:MURF_BOTS_PORT (8792).
Relay MURF (phone_relay.py) проксирует к ней /phone/api/bots/* — снаружи доступ только
с cookie WebUI (nginx auth_request) и заголовком X-Agent-App: 1.

Бэкенды:
  hermes   — `hermes-acp` (ACP по stdio), профиль Hermes через HERMES_HOME;
  grok     — `grok agent stdio` (ACP);
  gemini   — `gemini --experimental-acp` (ACP, если установлен);
  opencode — `opencode acp` (ACP, если установлен);
  codex    — `codex exec --json` (+ `codex exec resume <thread>`);
  claude   — `claude -p --output-format stream-json --verbose`.
CLI запускаются как текущий пользователь с его входами; токены не читаются и не копируются
(проверка входа — только наличие файла или статусная команда самого CLI).

Безопасность:
  * CLI-бэкенд включается только с подтверждением (confirm_cli), оно запоминается для бота;
  * режим доступа по умолчанию «safe»: MURF явно передаёт стандартные безопасные режимы CLI
    (codex: --sandbox workspace-write; claude: --permission-mode default; ACP: запрос разрешений),
    флаги вроде --dangerously-*/--yolo/--always-approve/bypassPermissions не передаются никогда;
    «cli» — как настроено в конфиге самого CLI (может быть полный доступ) — только явным выбором;
  * каждый запуск пишется в ~/agent-phone/bots/runs.jsonl (без текста запроса).
"""
import hashlib, hmac, json, os, re, secrets, shutil, signal, subprocess, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

VERSION = "1.8.0"
HOME = os.path.expanduser("~")
PORT = int(os.environ.get("MURF_BOTS_PORT", "8792"))
ROOT = os.environ.get("MURF_BOTS_DIR", os.path.join(HOME, "agent-phone", "bots"))
CHATS = os.path.join(ROOT, "chats")
LOGS = os.path.join(ROOT, "logs")
BOTS_FILE = os.path.join(ROOT, "bots.json")
RUNS_FILE = os.path.join(ROOT, "runs.jsonl")
PROPOSALS_FILE = os.path.join(ROOT, "proposals.json")
SECRET_FILE = os.path.join(ROOT, "token-secret")
MAX_PENDING_PROPOSALS = int(os.environ.get("MURF_BOTS_MAX_PROPOSALS", "5"))
MAX_PENDING_PER_BOT = 3
MAX_BOT_DEPTH = 2  # бот пользователя = 0, его «ребёнок» = 1, «внук» = 2 (только если пользователь разрешил ребёнку)
ACCESS_RANK = {"readonly": 0, "safe": 1, "cli": 2}
PROPOSE_RE = re.compile(r"^\s*@@propose_bot\s+(\{.*\})\s*$", re.M)
WORK_ROOT = os.environ.get("MURF_BOTS_WORKROOT", os.path.join(HOME, "murf-bots"))
PATH_EXTRA = [os.path.join(HOME, ".local", "bin"), os.path.join(HOME, ".opencode", "bin"),
              os.path.join(HOME, ".npm-global", "bin"), os.path.join(HOME, ".bun", "bin"), "/usr/local/bin"]
MAX_RUNS = int(os.environ.get("MURF_BOTS_MAX_RUNS", "4"))
APPROVAL_TIMEOUT = int(os.environ.get("MURF_BOTS_APPROVAL_TIMEOUT", str(15 * 60)))
IDLE_ACP = 15 * 60
HANDOFF_MAX_DEPTH = 3
CLI_BACKENDS = ("codex", "grok", "claude", "gemini", "opencode")
BACKENDS = ("hermes",) + CLI_BACKENDS
ACP_BACKENDS = ("hermes", "grok", "gemini", "opencode")
ACCESS = ("safe", "readonly", "cli")
SUBTASK_RE = re.compile(r"delegate|subagent|sub-agent|spawn_agent|\btask\b|^agent\b", re.I)
HANDOFF_RE = re.compile(r"^\s*@@handoff\s+@?([^:\n]{1,60}):\s*(.+)$", re.I | re.M)
# флаги, которые MURF никогда не передаёт CLI (проверяется перед каждым запуском)
FORBIDDEN_FLAGS = ("--dangerously-skip-permissions", "--dangerously-bypass-approvals-and-sandbox",
                   "--yolo", "--always-approve", "bypassPermissions", "danger-full-access", "--approve-for-me")

lock = threading.RLock()
runs_sem = threading.BoundedSemaphore(MAX_RUNS)


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


def now():
    return time.time()


def env_for(extra=None):
    e = dict(os.environ)
    e["PATH"] = os.pathsep.join(PATH_EXTRA + [e.get("PATH", "/usr/bin:/bin")])
    e.setdefault("HOME", HOME)
    e["NO_COLOR"] = "1"
    e["TERM"] = "dumb"
    if extra:
        e.update(extra)
    return e


def which(name):
    return shutil.which(name, path=env_for()["PATH"])


def ensure_dirs():
    for d in (ROOT, CHATS, LOGS):
        os.makedirs(d, mode=0o700, exist_ok=True)
        try:
            os.chmod(d, 0o700)
        except OSError:
            pass


def write_json(path, obj):
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


def read_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def append_line(path, obj):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
    with os.fdopen(fd, "a", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False) + "\n")


def clip(s, n):
    s = "" if s is None else str(s)
    return s if len(s) <= n else s[: n - 1] + "…"


def new_id(prefix):
    return prefix + secrets.token_hex(6)


class ApiError(Exception):
    def __init__(self, status, message, **extra):
        super().__init__(message)
        self.status, self.message, self.extra = status, message, extra


# ───────────────────────────── обнаружение CLI ─────────────────────────────

CLI_INFO = {
    "hermes": {"name": "Hermes Agent", "bins": ["hermes"]},
    "codex": {"name": "Codex CLI", "bins": ["codex"]},
    "grok": {"name": "Grok CLI (Grok Build)", "bins": ["grok"]},
    "claude": {"name": "Claude Code", "bins": ["claude"]},
    "gemini": {"name": "Gemini CLI", "bins": ["gemini"]},
    "opencode": {"name": "opencode", "bins": ["opencode"]},
}
_cli_cache = {"at": 0, "data": None}


def _run_quiet(argv, timeout=20):
    try:
        p = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, env=env_for(),
                           cwd=HOME, stdin=subprocess.DEVNULL)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except (OSError, subprocess.TimeoutExpired) as e:
        return -1, type(e).__name__


def _first_line(text):
    for line in (text or "").splitlines():
        line = line.strip()
        if line:
            return clip(re.sub(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+", "<email>", line), 120)
    return ""


def _exists_nonempty(*rel):
    for r in rel:
        p = os.path.join(HOME, r)
        try:
            if os.path.isfile(p) and os.path.getsize(p) > 2:
                return True
        except OSError:
            pass
    return False


def login_status(cid, path):
    """Только «вошёл/нет» и способ входа; файлы с токенами не открываются."""
    if cid == "codex":
        rc, out = _run_quiet([path, "login", "status"])
        low = out.lower()
        if rc == 0 and "logged in" in low:
            return True, "ChatGPT" if "chatgpt" in low else ("API key" if "api key" in low else "")
        return False, ""
    if cid == "claude":
        rc, out = _run_quiet([path, "auth", "status"])
        try:
            d = json.loads(out[out.index("{"): out.rindex("}") + 1])
            return bool(d.get("loggedIn")), clip(d.get("authMethod") or "", 20)
        except ValueError:
            return None, ""
    if cid == "grok":
        return _exists_nonempty(".grok/auth.json"), "файл входа"
    if cid == "gemini":
        if os.environ.get("GEMINI_API_KEY"):
            return True, "API key"
        return _exists_nonempty(".gemini/oauth_creds.json"), "oauth"
    if cid == "opencode":
        return _exists_nonempty(".local/share/opencode/auth.json"), "файл входа"
    if cid == "hermes":
        return _exists_nonempty(".hermes/auth.json", ".hermes/.env"), "настроен"
    return None, ""


def detect_clis(refresh=False):
    with lock:
        if not refresh and _cli_cache["data"] is not None and now() - _cli_cache["at"] < 120:
            return _cli_cache["data"]
    out = []
    for cid, info in CLI_INFO.items():
        path = next((p for p in (which(b) for b in info["bins"]) if p), None)
        item = {"id": cid, "name": info["name"], "installed": bool(path), "path": path or "",
                "version": "", "logged_in": None, "login_method": "", "backend": cid in BACKENDS,
                "approvals": cid in ACP_BACKENDS,
                "mode": "ACP" if cid in ACP_BACKENDS else ("exec --json" if cid == "codex" else "-p stream-json")}
        if path:
            rc, txt = _run_quiet([path, "--version"])
            item["version"] = _first_line(txt)
            item["logged_in"], item["login_method"] = login_status(cid, path)
            if cid == "hermes":
                item["acp"] = bool(which("hermes-acp"))
                if not item["acp"]:
                    item["backend"] = False
        out.append(item)
    with lock:
        _cli_cache.update(at=now(), data=out)
    return out


def hermes_profiles():
    names = ["default"]
    d = os.path.join(HOME, ".hermes", "profiles")
    try:
        for n in sorted(os.listdir(d)):
            if re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", n) and os.path.isdir(os.path.join(d, n)):
                names.append(n)
    except OSError:
        pass
    return names


# ───────────────────────────── боты ─────────────────────────────

def load_bots():
    return read_json(BOTS_FILE, {"bots": []}).get("bots", [])


def save_bots(bots):
    write_json(BOTS_FILE, {"version": 1, "bots": bots})


def get_bot(bid):
    for b in load_bots():
        if b["id"] == bid:
            return b
    raise ApiError(404, "бот не найден")


def find_bot_by_name(name):
    key = name.strip().lower()
    for b in load_bots():
        if b["id"] == key or b["name"].strip().lower() == key:
            return b
    return None


def slug(name):
    s = re.sub(r"[^a-z0-9а-яё_-]+", "-", name.lower()).strip("-")[:32]
    return s or "bot"


def validate_workdir(path, name):
    if not path:
        path = os.path.join(WORK_ROOT, slug(name))
    path = os.path.realpath(os.path.expanduser(path))
    if not (path == HOME or path.startswith(HOME + os.sep)):
        raise ApiError(400, "рабочая папка должна быть внутри домашней папки")
    return path


def bot_public(b):
    return {k: b.get(k) for k in ("id", "name", "emoji", "instructions", "workdir", "backend", "model",
                                    "profile", "access", "cli_confirmed", "created", "updated",
                                    "created_by", "created_by_name", "depth", "allow_spawn")}


def needs_confirm(backend, access):
    return backend in CLI_BACKENDS or access == "cli"


def cli_notice(backend, access):
    name = CLI_INFO.get(backend, {}).get("name", backend)
    parts = []
    if backend in CLI_BACKENDS:
        parts.append(f"{name} будет запускаться на сервере от вашего пользователя и с вашим входом в {name}: "
                     "он может читать и менять файлы в рабочей папке бота и тратить лимиты вашего аккаунта.")
    if access == "cli":
        parts.append("Режим «как в конфиге CLI»: MURF не задаёт безопасный режим, действуют настройки самого "
                     "агента — если там полный доступ без подтверждений, бот получит полный доступ.")
    elif access == "readonly":
        parts.append("Режим «только чтение»: агент запускается без права изменений там, где он это поддерживает.")
    else:
        parts.append("Режим «безопасный»: MURF передаёт стандартные режимы — песочница рабочей папки и запрос "
                     "разрешений там, где агент это умеет.")
    parts.append("Каждый запуск записывается в журнал на сервере.")
    return " ".join(parts)


def upsert_bot(data, bid=None):
    with lock:
        bots = load_bots()
        cur = next((b for b in bots if b["id"] == bid), None) if bid else None
        if bid and not cur:
            raise ApiError(404, "бот не найден")
        b = dict(cur or {})
        name = str(data.get("name", b.get("name", ""))).strip()[:60]
        if not name:
            raise ApiError(400, "нужно имя бота")
        if any(o["name"].strip().lower() == name.lower() and o["id"] != b.get("id") for o in bots):
            raise ApiError(409, "бот с таким именем уже есть")
        backend = str(data.get("backend", b.get("backend", "hermes")))
        if backend not in BACKENDS:
            raise ApiError(400, "неизвестный бэкенд")
        access = str(data.get("access", b.get("access", "safe")))
        if access not in ACCESS:
            raise ApiError(400, "неизвестный режим доступа")
        profile = str(data.get("profile", b.get("profile", "default")) or "default")
        if backend == "hermes" and profile not in hermes_profiles():
            raise ApiError(400, "нет такого профиля Hermes")
        model = str(data.get("model", b.get("model", "")) or "").strip()[:120]
        if model and not re.fullmatch(r"[A-Za-z0-9_.:/@+-]{1,120}", model):
            raise ApiError(400, "недопустимое имя модели")
        confirmed = b.get("cli_confirmed") or {}
        needs = needs_confirm(backend, access)
        if needs and (confirmed.get("backend") != backend or confirmed.get("access") != access):
            if data.get("confirm_cli") is not True:
                raise ApiError(409, "нужно подтверждение", confirm_required=True, backend=backend,
                               access=access, notice=cli_notice(backend, access))
            confirmed = {"backend": backend, "access": access, "at": now()}
        workdir = validate_workdir(str(data.get("workdir", b.get("workdir", "")) or ""), name)
        os.makedirs(workdir, mode=0o700, exist_ok=True)
        b.update({
            "id": b.get("id") or new_id("b"), "name": name,
            "emoji": clip(str(data.get("emoji", b.get("emoji", "🤖")) or "🤖"), 8),
            "instructions": clip(str(data.get("instructions", b.get("instructions", "")) or ""), 8000),
            "workdir": workdir, "backend": backend, "model": model, "profile": profile,
            "access": access, "cli_confirmed": confirmed if needs else None,
            "created": b.get("created") or now(), "updated": now(),
            "allow_spawn": bool(data.get("allow_spawn", b.get("allow_spawn", False))),
        })
        for k in ("created_by", "created_by_name", "depth"):
            if k in data and data.get("_internal") is True:
                b[k] = data[k]
        save_bots([o for o in bots if o["id"] != b["id"]] + [b])
        log("bot saved", b["id"], backend, access)
        return {"ok": True, "bot": bot_public(b)}


def delete_bot(bid):
    with lock:
        bots = load_bots()
        if not any(b["id"] == bid for b in bots):
            raise ApiError(404, "бот не найден")
        chats = list_chats(bid)
        if any(c.get("running") for c in chats):
            raise ApiError(409, "у бота идёт запуск — сначала остановите")
        save_bots([b for b in bots if b["id"] != bid])
        for c in chats:
            drop_chat_files(c["id"])
        return {"ok": True}


# ───────────── чаты: метаданные + журнал событий (seq, ts, type, …) ─────────────

class Chat:
    _cache = {}

    def __init__(self, cid):
        self.id = cid
        self.meta_path = os.path.join(CHATS, cid + ".json")
        self.log_path = os.path.join(CHATS, cid + ".jsonl")
        self.meta = read_json(self.meta_path, None)
        if self.meta is None:
            raise ApiError(404, "чат не найден")
        self.events = []
        try:
            with open(self.log_path, encoding="utf-8") as f:
                for line in f:
                    try:
                        self.events.append(json.loads(line))
                    except ValueError:
                        pass
        except OSError:
            pass
        self.cond = threading.Condition(threading.RLock())
        self.run = None
        self.acp = None
        # запуск, оборванный перезапуском службы, помечаем завершённым
        open_runs = {}
        for e in self.events:
            if e["type"] == "run":
                if e.get("status") == "running":
                    open_runs[e["run"]] = e
                else:
                    open_runs.pop(e.get("run"), None)
        for rid in open_runs:
            self.emit("run", run=rid, status="failed", error="служба MURF перезапускалась во время ответа")

    @classmethod
    def get(cls, cid):
        if not re.fullmatch(r"c[0-9a-f]{12}", cid or ""):
            raise ApiError(404, "чат не найден")
        with lock:
            c = cls._cache.get(cid)
            if c is None:
                c = cls(cid)
                cls._cache[cid] = c
            return c

    @classmethod
    def create(cls, bot, title="", parent=None):
        cid = new_id("c")
        meta = {"id": cid, "bot": bot["id"], "title": clip(title, 80) or "Новый чат", "created": now(),
                "updated": now(), "last_text": "", "handoff_depth": 0, "parent": parent,
                "backend_session": None, "backend": bot["backend"]}
        write_json(os.path.join(CHATS, cid + ".json"), meta)
        return cls.get(cid)

    def save_meta(self):
        with self.cond:
            self.meta["updated"] = now()
            write_json(self.meta_path, self.meta)

    @property
    def seq(self):
        return self.events[-1]["seq"] if self.events else 0

    def emit(self, type_, **kw):
        with self.cond:
            ev = {"seq": self.seq + 1, "ts": round(now(), 3), "type": type_}
            ev.update({k: v for k, v in kw.items() if v is not None})
            self.events.append(ev)
            append_line(self.log_path, ev)
            self.cond.notify_all()
            return ev

    def public(self):
        m = dict(self.meta)
        m["running"] = bool(self.run and self.run.active)
        m["seq"] = self.seq
        return m


def drop_chat_files(cid):
    with lock:
        c = Chat._cache.pop(cid, None)
    if c and c.acp:
        threading.Thread(target=c.acp.close, daemon=True).start()
    for ext in (".json", ".jsonl"):
        try:
            os.remove(os.path.join(CHATS, cid + ext))
        except OSError:
            pass


def list_chats(bid):
    out = []
    try:
        names = os.listdir(CHATS)
    except OSError:
        names = []
    for n in names:
        if re.fullmatch(r"c[0-9a-f]{12}\.json", n):
            m = read_json(os.path.join(CHATS, n), None)
            if m and m.get("bot") == bid:
                c = Chat._cache.get(m["id"])
                m["running"] = bool(c and c.run and c.run.active)
                out.append(m)
    out.sort(key=lambda m: m.get("updated", 0), reverse=True)
    return out


# ───────────────────────────── запуски ─────────────────────────────

recent_runs = []          # последние завершённые запуски (для панели «Задачи»)


def load_recent_runs():
    try:
        with open(RUNS_FILE, encoding="utf-8") as f:
            lines = f.readlines()[-300:]
    except OSError:
        return
    for line in lines:
        try:
            r = json.loads(line)
        except ValueError:
            continue
        if r.get("event") == "end":
            recent_runs.append(r)
    del recent_runs[:-200]


class Coalescer:
    """Склеивает дельты текста/рассуждений в одно событие (не чаще ~4 раз в секунду)."""

    def __init__(self, chat, run_id):
        self.chat, self.run_id = chat, run_id
        self.key, self.buf, self.first = None, [], 0.0
        self.lk = threading.Lock()
        self.alive = True
        threading.Thread(target=self._tick, daemon=True).start()

    def _tick(self):
        while self.alive:
            time.sleep(0.3)
            with self.lk:
                if self.buf and now() - self.first > 0.25:
                    self._flush()

    def add(self, kind, text, parent=None):
        if not text:
            return
        with self.lk:
            if self.key and self.key != (kind, parent):
                self._flush()
            self.key = (kind, parent)
            if not self.buf:
                self.first = now()
            self.buf.append(text)
            if sum(map(len, self.buf)) > 4000:
                self._flush()

    def _flush(self):
        if self.buf and self.key:
            kind, parent = self.key
            self.chat.emit(kind, run=self.run_id, text="".join(self.buf), parent=parent)
        self.buf, self.key = [], None

    def flush(self):
        with self.lk:
            self._flush()

    def close(self):
        self.flush()
        self.alive = False


class Run:
    def __init__(self, chat, bot, text, origin=None):
        self.id = new_id("r")
        self.chat, self.bot, self.prompt, self.origin = chat, bot, text, origin
        self.started = now()
        self.active = True
        self.status = "running"
        self.stop_requested = False
        self.proc = None
        self.acp_conn = None
        self.argv = []
        self.approvals = {}
        self.answer_log = []
        self.assistant = []
        self.co = Coalescer(chat, self.id)
        self.steps = {}
        self.slk = threading.Lock()

    def text(self, t, parent=None):
        if not t:
            return
        if parent is None:
            self.assistant.append(t)
        self.co.add("text", t, parent)

    def thinking(self, t, parent=None):
        self.co.add("thinking", t, parent)

    def step(self, sid, subtask=None, **kw):
        """Шаг (инструмент/команда) или подзадача — upsert по id."""
        self.co.flush()
        with self.slk:
            st = dict(self.steps.get(sid, {}))
            if subtask is not None and "subtask" not in st:
                st["subtask"] = bool(subtask)
            st.update({k: v for k, v in kw.items() if v is not None})
            st.setdefault("subtask", False)
            st.setdefault("started", round(now(), 3))
            st.setdefault("status", "running")
            if st["status"] in ("done", "failed") and not st.get("ended"):
                st["ended"] = round(now(), 3)
            self.steps[sid] = st
        payload = {k: st.get(k) for k in ("title", "status", "kind", "detail", "parent", "started", "ended")}
        self.chat.emit("subtask" if st["subtask"] else "step", run=self.id, id=sid, **payload)

    def notice(self, t):
        self.co.flush()
        self.chat.emit("notice", run=self.id, text=clip(t, 2000))

    def ask(self, title, options, detail=None):
        """Запрос разрешения: ждём ответа из приложения. Возвращает optionId или None (отказ/таймаут)."""
        self.co.flush()
        aid = new_id("a")
        slot = {"event": threading.Event(), "answer": None, "title": title, "options": options}
        self.approvals[aid] = slot
        self.chat.emit("approval", run=self.id, id=aid, title=clip(title, 300),
                       detail=clip(detail, 1500) if detail else None,
                       options=[{"id": o["id"], "name": clip(o.get("name"), 60), "kind": o.get("kind")}
                                for o in options], status="pending")
        got = slot["event"].wait(APPROVAL_TIMEOUT)
        ans = slot["answer"] if got else None
        self.approvals.pop(aid, None)
        self.answer_log.append({"title": clip(title, 120), "answer": ans or "cancelled"})
        self.chat.emit("approval", run=self.id, id=aid, status="resolved" if ans else "cancelled", chosen=ans)
        return ans

    def answer(self, aid, option):
        slot = self.approvals.get(aid)
        if not slot:
            raise ApiError(404, "запрос разрешения не найден или уже закрыт")
        if option is not None and not any(o["id"] == option for o in slot["options"]):
            raise ApiError(400, "нет такого варианта ответа")
        slot["answer"] = option
        slot["event"].set()

    def cancel_approvals(self):
        for slot in list(self.approvals.values()):
            slot["event"].set()


def bot_instructions(bot):
    others = [b["name"] for b in load_bots() if b["id"] != bot["id"]]
    parts = [f"Ты — бот «{bot['name']}» в приложении MURF. Рабочая папка: {bot['workdir']}."]
    if bot.get("instructions"):
        parts.append(bot["instructions"].strip())
    if others:
        parts.append("Если задачу лучше выполнит другой бот (" + ", ".join(others[:12]) + "), закончи ответ "
                     "отдельной строкой `@@handoff <имя бота>: <задача>` — MURF передаст ему задачу.")
    if can_propose(bot):
        parts.append(
            "Ты можешь предложить создать нового бота-помощника (MURF сам ничего не создаёт: пользователь увидит "
            "карточку и нажмёт «Создать» или «Отклонить»). Для этого закончи ответ отдельной строкой\n"
            '`@@propose_bot {"name": "...", "backend": "hermes", "instructions": "...", "hat": "beret", '
            '"color": 3, "workdir": "", "reason": "зачем он нужен"}`\n'
            "или вызови `curl -s -X POST \"$MURF_BOTS_URL/propose\" -H \"X-Murf-Bot-Token: $MURF_BOT_TOKEN\" "
            "-H 'Content-Type: application/json' -d '<тот же JSON>'`. backend: hermes или твой собственный ("
            + bot["backend"] + "). Доступ у нового бота не больше твоего. hat: wide_brim, cap, wizard, beret, flower, "
            "helmet, top_hat, beanie, chef, boater; color: 0–9. Предлагай, только если это действительно нужно.")
    return "\n\n".join(parts)


# ───────────── боты создают ботов: предложения (только с согласия пользователя) ─────────────
HATS = ("wide_brim", "cap", "wizard", "beret", "flower", "helmet", "top_hat", "beanie", "chef", "boater")


def _secret():
    try:
        with open(SECRET_FILE, "rb") as f:
            k = f.read()
            if len(k) >= 32:
                return k
    except OSError:
        pass
    k = secrets.token_bytes(32)
    fd = os.open(SECRET_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(k)
    return k


def chat_token(cid):
    return hmac.new(_secret(), ("chat:" + cid).encode(), hashlib.sha256).hexdigest()[:40]


def chat_by_token(token):
    m = re.fullmatch(r"([0-9a-f]{40})\.(c[0-9a-f]{12})", token or "")
    if not m or not hmac.compare_digest(chat_token(m.group(2)), m.group(1)):
        raise ApiError(403, "неверный токен бота")
    return Chat.get(m.group(2))


def bot_env(chat):
    return {"MURF_BOT_TOKEN": chat_token(chat.id) + "." + chat.id, "MURF_BOTS_URL": f"http://127.0.0.1:{PORT}"}


def can_propose(bot):
    d = int(bot.get("depth") or 0)
    return d == 0 or (bool(bot.get("allow_spawn")) and d + 1 <= MAX_BOT_DEPTH)


def load_proposals():
    return read_json(PROPOSALS_FILE, {"proposals": []}).get("proposals", [])


def save_proposals(ps):
    write_json(PROPOSALS_FILE, {"version": 1, "proposals": ps[-200:]})


def propose_bot(chat, data, via="marker"):
    parent = get_bot(chat.meta["bot"])
    if not can_propose(parent):
        raise ApiError(403, "этому боту нельзя создавать ботов (глубина " + str(parent.get("depth") or 0) + ")")
    name = str(data.get("name") or "").strip()[:60]
    if not name:
        raise ApiError(400, "нужно имя бота")
    backend = str(data.get("backend") or "hermes")
    if backend not in BACKENDS:
        raise ApiError(400, "неизвестный бэкенд")
    if backend != "hermes" and backend != parent["backend"]:
        raise ApiError(403, "новый бот может быть только на Hermes или на бэкенде родителя")
    req_access = str(data.get("access") or parent.get("access") or "safe")
    if req_access not in ACCESS:
        req_access = "safe"
    pa = parent.get("access") or "safe"
    access = req_access if ACCESS_RANK[req_access] <= ACCESS_RANK[pa] else pa
    hat = str(data.get("hat") or "")
    hat_i = HATS.index(hat) if hat in HATS else (int(hat) if hat.isdigit() and int(hat) < 10 else None)
    try:
        color = max(0, min(9, int(data.get("color"))))
    except (TypeError, ValueError):
        color = None
    emoji = f"k{hat_i}-{color if color is not None else hat_i}" if hat_i is not None else "🤖"
    wd = str(data.get("workdir") or "").strip()
    workdir = validate_workdir(wd, name)
    if not (workdir.startswith(os.path.realpath(WORK_ROOT) + os.sep) or workdir.startswith(parent["workdir"] + os.sep)):
        raise ApiError(403, "папка нового бота должна быть внутри папки ботов или папки родителя")
    with lock:
        ps = load_proposals()
        pend = [p for p in ps if p["status"] == "pending"]
        if len(pend) >= MAX_PENDING_PROPOSALS:
            raise ApiError(429, "слишком много предложений ждут решения пользователя")
        if sum(1 for p in pend if p["parent"] == parent["id"]) >= MAX_PENDING_PER_BOT:
            raise ApiError(429, "у этого бота уже есть предложения, которые ждут решения")
        if any(b["name"].strip().lower() == name.lower() for b in load_bots()) or \
                any(p["name"].strip().lower() == name.lower() for p in pend):
            raise ApiError(409, "бот с таким именем уже есть")
        pr = {"id": new_id("p"), "status": "pending", "created": now(), "parent": parent["id"],
              "parent_name": parent["name"], "chat": chat.id, "run": chat.run.id if chat.run else None,
              "name": name, "backend": backend, "access": access, "emoji": emoji,
              "instructions": clip(str(data.get("instructions") or ""), 8000), "workdir": workdir,
              "model": "", "profile": parent.get("profile") or "default" if backend == "hermes" else "default",
              "reason": clip(str(data.get("reason") or ""), 400), "depth": int(parent.get("depth") or 0) + 1,
              "via": via, "notice": cli_notice(backend, access) if needs_confirm(backend, access) else None}
        if backend == "hermes" and parent["backend"] != "hermes":
            pr["profile"] = "default"
        ps.append(pr)
        save_proposals(ps)
        append_line(RUNS_FILE, {"event": "bot_proposal", "ts": now(), "proposal": pr["id"], "parent": parent["id"],
                                "chat": chat.id, "name": name, "backend": backend, "access": access, "via": via})
    chat.emit("bot_proposal", proposal=pr["id"], name=name, backend=backend, status="pending")
    log("bot proposal", pr["id"], parent["id"], backend, access)
    return {"ok": True, "proposal": pr}


def decide_proposal(pid, approve):
    with lock:
        ps = load_proposals()
        pr = next((p for p in ps if p["id"] == pid), None)
        if not pr:
            raise ApiError(404, "предложение не найдено")
        if pr["status"] != "pending":
            raise ApiError(409, "решение уже принято")
        bot = None
        if approve:
            parent = get_bot(pr["parent"])  # родителя могли удалить
            res = upsert_bot({"name": pr["name"], "backend": pr["backend"], "access": pr["access"],
                              "emoji": pr["emoji"], "instructions": pr["instructions"], "workdir": pr["workdir"],
                              "profile": pr["profile"], "model": "", "confirm_cli": True, "_internal": True,
                              "created_by": parent["id"], "created_by_name": parent["name"], "depth": pr["depth"]})
            bot = res["bot"]
        pr.update({"status": "approved" if approve else "rejected", "decided": now(), "bot": bot["id"] if bot else None})
        save_proposals(ps)
        append_line(RUNS_FILE, {"event": "bot_proposal_" + pr["status"], "ts": now(), "proposal": pid,
                                "parent": pr["parent"], "bot": pr.get("bot"), "name": pr["name"]})
    try:
        Chat.get(pr["chat"]).emit("bot_proposal", proposal=pid, name=pr["name"], backend=pr["backend"], status=pr["status"])
        Chat.get(pr["chat"]).emit("notice", text=(f"Пользователь создал бота «{pr['name']}»." if approve
                                                  else f"Пользователь отклонил бота «{pr['name']}»."))
    except ApiError:
        pass
    return {"ok": True, "proposal": pr, "bot": bot}


def maybe_propose(run):
    for m in list(PROPOSE_RE.finditer("".join(run.assistant)))[-2:]:
        try:
            data = json.loads(m.group(1))
            if isinstance(data, dict):
                propose_bot(run.chat, data, via="marker")
        except ValueError:
            run.chat.emit("notice", text="Предложение бота не разобрано: неверный JSON.")
        except ApiError as e:
            run.chat.emit("notice", text="Предложение бота отклонено MURF: " + e.message)


def recap(chat, limit=4000):
    """Краткая история для новой сессии бэкенда (если прежнюю продолжить нельзя)."""
    lines, cur = [], []
    for ev in chat.events:
        if ev["type"] == "user":
            if cur:
                lines.append("Ассистент: " + "".join(cur).strip())
                cur = []
            lines.append("Пользователь: " + ev.get("text", ""))
        elif ev["type"] == "text" and not ev.get("parent"):
            cur.append(ev.get("text", ""))
    if cur:
        lines.append("Ассистент: " + "".join(cur).strip())
    text = "\n".join(lines[:-1])
    return text[-limit:] if text else ""


def check_argv(argv):
    for a in argv:
        for bad in FORBIDDEN_FLAGS:
            if bad in a:
                raise ApiError(500, "MURF отказался передавать небезопасный флаг " + bad)


def start_run(chat, text, origin=None):
    with chat.cond:
        if chat.run and chat.run.active:
            raise ApiError(409, "бот ещё отвечает — дождитесь или остановите")
        bot = get_bot(chat.meta["bot"])
        if needs_confirm(bot["backend"], bot.get("access")):
            c = bot.get("cli_confirmed") or {}
            if c.get("backend") != bot["backend"] or c.get("access") != bot.get("access"):
                raise ApiError(409, "CLI для этого бота не подтверждён", confirm_required=True)
        if not runs_sem.acquire(blocking=False):
            raise ApiError(429, f"одновременно можно не больше {MAX_RUNS} запусков")
        run = Run(chat, bot, text, origin)
        chat.run = run
        if not chat.meta.get("last_text") and chat.meta.get("title") in ("", "Новый чат", None):
            first = text.strip().splitlines()[0] if text.strip() else "Новый чат"
            chat.meta["title"] = clip(first, 60)
        chat.meta["last_text"] = clip(text, 140)
        chat.meta["backend"] = bot["backend"]
        chat.save_meta()
        chat.emit("user", run=run.id, text=text, from_bot=origin)
        chat.emit("run", run=run.id, status="running", backend=bot["backend"], started=run.started)
    threading.Thread(target=_run_thread, args=(run,), daemon=True, name="run-" + run.id).start()
    return run


def _run_thread(run):
    chat, bot = run.chat, run.bot
    err, exit_code = None, None
    try:
        exit_code = BACKEND_IMPL[bot["backend"]](run)
        if run.stop_requested:
            run.status = "stopped"
        elif exit_code not in (0, None):
            run.status = "failed"
            err = f"процесс завершился с кодом {exit_code}"
        else:
            run.status = "done"
    except ApiError as e:
        run.status, err = ("stopped" if run.stop_requested else "failed"), e.message
    except Exception as e:  # noqa: BLE001
        log("run error", run.id, type(e).__name__, e)
        run.status, err = "failed", clip(f"{type(e).__name__}: {e}", 400)
    finally:
        run.co.close()
        for sid, st in list(run.steps.items()):
            if st.get("status") not in ("done", "failed"):
                run.step(sid, status="done" if run.status == "done" else "failed")
        run.active = False
        ended = now()
        chat.emit("run", run=run.id, status=run.status, backend=bot["backend"], started=run.started,
                  ended=ended, error=err)
        rec = {"event": "end", "ts": ended, "run": run.id, "bot": bot["id"], "bot_name": bot["name"],
               "chat": chat.id, "chat_title": chat.meta.get("title"), "backend": bot["backend"],
               "access": bot.get("access"), "argv": run.argv, "cwd": bot["workdir"], "status": run.status,
               "exit": exit_code, "started": run.started, "duration": round(ended - run.started, 1),
               "prompt_chars": len(run.prompt), "approvals": run.answer_log, "error": err,
               "from_bot": run.origin, "subtasks": sum(1 for s in run.steps.values() if s.get("subtask"))}
        with lock:
            # в журнал на диске не пишем ни текст запроса, ни заголовок чата (он из запроса)
            append_line(RUNS_FILE, {k: v for k, v in rec.items() if k != "chat_title"})
            recent_runs.append(rec)
            del recent_runs[:-200]
        runs_sem.release()
        chat.save_meta()
        log("run end", run.id, bot["backend"], run.status, rec["duration"])
        if run.status == "done":
            try:
                maybe_propose(run)
            except Exception as e:  # noqa: BLE001
                log("propose error", type(e).__name__, e)
            try:
                maybe_handoff(run)
            except ApiError:
                pass
            except Exception as e:  # noqa: BLE001
                log("handoff error", type(e).__name__, e)


def maybe_handoff(run):
    found = list(HANDOFF_RE.finditer("".join(run.assistant)))
    if found:
        do_handoff(run.chat, found[-1].group(1).strip(), found[-1].group(2).strip(), automatic=True)


def do_handoff(chat, target_name, task, automatic=False):
    src_bot = get_bot(chat.meta["bot"])
    depth = int(chat.meta.get("handoff_depth") or 0) + 1
    target = find_bot_by_name(target_name)
    if not target or target["id"] == src_bot["id"]:
        chat.emit("notice", text=f"Передача не выполнена: бот «{clip(target_name, 60)}» не найден.")
        raise ApiError(404, "бот для передачи не найден")
    if automatic and depth > HANDOFF_MAX_DEPTH:
        chat.emit("notice", text="Передача остановлена: слишком длинная цепочка передач между ботами.")
        raise ApiError(409, "слишком длинная цепочка передач")
    task = clip(task.strip(), 6000)
    if not task:
        raise ApiError(400, "пустая задача")
    nc = Chat.create(target, title=f"От {src_bot['name']}: {clip(task, 50)}", parent=chat.id)
    nc.meta["handoff_depth"] = depth
    nc.save_meta()
    chat.emit("handoff", to_bot=target["id"], to_name=target["name"], to_chat=nc.id, task=clip(task, 400))
    nc.emit("handoff", from_bot=src_bot["id"], from_name=src_bot["name"], from_chat=chat.id, task=clip(task, 400))
    try:
        start_run(nc, task, origin=src_bot["name"])
    except ApiError as e:
        nc.emit("notice", text="Запуск не начат: " + e.message)
    return {"ok": True, "chat": nc.public(), "bot": bot_public(target)}


# ───────────────────────────── ACP-клиент ─────────────────────────────

class AcpError(Exception):
    pass


class AcpConn:
    """JSON-RPC 2.0 по stdio (Agent Client Protocol). Один процесс на чат, живёт до простоя."""

    def __init__(self, argv, cwd, env, stderr_path):
        self.argv = argv
        errf = open(stderr_path, "ab")
        os.chmod(stderr_path, 0o600)
        self.proc = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=errf, start_new_session=True)
        errf.close()
        self.next_id = 0
        self.pending = {}
        self.lk = threading.Lock()
        self.wlk = threading.Lock()
        self.on_update = None
        self.on_request = None
        self.used = now()
        self.caps = {}
        self.session = None
        self.first_prompt = True
        threading.Thread(target=self._reader, daemon=True).start()

    def alive(self):
        return self.proc.poll() is None

    def _send(self, obj):
        data = (json.dumps(obj, ensure_ascii=False) + "\n").encode()
        with self.wlk:
            self.proc.stdin.write(data)
            self.proc.stdin.flush()

    def _reader(self):
        for raw in self.proc.stdout:
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            if not isinstance(msg, dict):
                continue
            if "method" in msg and "id" in msg:
                threading.Thread(target=self._handle_request, args=(msg,), daemon=True).start()
            elif "method" in msg:
                cb = self.on_update
                if cb and msg.get("method") == "session/update":
                    try:
                        cb(msg.get("params") or {})
                    except Exception as e:  # noqa: BLE001
                        log("acp update cb", type(e).__name__, e)
            elif "id" in msg:
                with self.lk:
                    slot = self.pending.pop(msg["id"], None)
                if slot:
                    slot["msg"] = msg
                    slot["ev"].set()
        with self.lk:
            for slot in self.pending.values():
                slot["ev"].set()
            self.pending.clear()

    def _handle_request(self, msg):
        result, error = None, None
        try:
            if self.on_request:
                result = self.on_request(msg["method"], msg.get("params") or {})
            else:
                error = {"code": -32601, "message": "method not found"}
        except AcpError as e:
            error = {"code": -32601, "message": str(e)}
        except Exception as e:  # noqa: BLE001
            error = {"code": -32603, "message": type(e).__name__}
        reply = {"jsonrpc": "2.0", "id": msg["id"]}
        if error:
            reply["error"] = error
        else:
            reply["result"] = result
        try:
            self._send(reply)
        except OSError:
            pass

    def request(self, method, params, timeout=None):
        with self.lk:
            self.next_id += 1
            rid = self.next_id
            slot = {"ev": threading.Event(), "msg": None}
            self.pending[rid] = slot
        self.used = now()
        try:
            self._send({"jsonrpc": "2.0", "id": rid, "method": method, "params": params})
        except OSError as e:
            raise AcpError("процесс агента не отвечает") from e
        slot["ev"].wait(timeout)
        msg = slot["msg"]
        self.used = now()
        if msg is None:
            with self.lk:
                self.pending.pop(rid, None)
            raise AcpError("агент завершился" if not self.alive() else "агент не ответил вовремя")
        if "error" in msg:
            e = msg["error"] or {}
            raise AcpError(clip(e.get("message") or "ошибка агента", 300))
        return msg.get("result") or {}

    def notify(self, method, params):
        try:
            self._send({"jsonrpc": "2.0", "method": method, "params": params})
        except OSError:
            pass

    def close(self):
        try:
            self.proc.stdin.close()
        except OSError:
            pass
        try:
            self.proc.wait(3)
        except subprocess.TimeoutExpired:
            for sig in (signal.SIGTERM, signal.SIGKILL):
                try:
                    os.killpg(self.proc.pid, sig)
                    self.proc.wait(5)
                    break
                except (OSError, subprocess.TimeoutExpired):
                    continue


def acp_argv(bot):
    b, model = bot["backend"], bot.get("model")
    if b == "hermes":
        exe = which("hermes-acp")
        if not exe:
            raise ApiError(500, "hermes-acp не найден")
        prof = bot.get("profile") or "default"
        return [exe], ({"HERMES_HOME": os.path.join(HOME, ".hermes", "profiles", prof)} if prof != "default" else {})
    exe = which(b)
    if not exe:
        raise ApiError(500, f"{b} не установлен")
    if b == "grok":
        return [exe, "agent"] + (["-m", model] if model else []) + ["stdio"], {}
    if b == "gemini":
        return [exe, "--experimental-acp"] + (["-m", model] if model else []), {}
    if b == "opencode":
        return [exe, "acp"], {}
    raise ApiError(500, "бэкенд не поддерживает ACP")


def _acp_text(content):
    if isinstance(content, dict):
        t = content.get("type")
        if t == "text":
            return content.get("text") or ""
        if t == "content":
            return _acp_text(content.get("content"))
        if t == "diff":
            return f"✎ {content.get('path', '')}"
        if t == "terminal":
            return "⌨ терминал"
        if t == "resource_link":
            return content.get("uri") or ""
    if isinstance(content, list):
        return "\n".join(filter(None, (_acp_text(c) for c in content)))
    return ""


def _acp_status(s):
    return {"pending": "running", "in_progress": "running", "completed": "done", "failed": "failed"}.get(s or "")


def brief_input(raw):
    if raw is None or raw == {}:
        return None
    if isinstance(raw, dict):
        for k in ("command", "cmd", "goal", "prompt", "task", "description", "path", "file_path", "query", "url"):
            v = raw.get(k)
            if v:
                return clip(" ".join(map(str, v)) if isinstance(v, list) else v, 600)
    return clip(json.dumps(raw, ensure_ascii=False), 600)


def run_acp(run):
    chat, bot = run.chat, run.bot
    ses = chat.meta.get("backend_session")
    loading = {"on": False}
    tool_meta = {}
    conn_ref = {}

    def on_update(params):
        conn = conn_ref.get("c")
        if loading["on"] or (conn and params.get("sessionId") not in (None, conn.session)):
            return
        u = params.get("update") or {}
        kind = u.get("sessionUpdate")
        meta = u.get("_meta") or {}
        parent = meta.get("parentToolCallId") or meta.get("x.ai/parentToolCallId")
        if kind == "agent_message_chunk":
            run.text(_acp_text(u.get("content")), parent=parent)
        elif kind == "agent_thought_chunk":
            run.thinking(_acp_text(u.get("content")), parent=parent)
        elif kind in ("tool_call", "tool_call_update"):
            tid = u.get("toolCallId") or new_id("t")
            m = tool_meta.setdefault(tid, {})
            if u.get("title"):
                m["title"] = u["title"]
            if "sub" not in m and (u.get("title") or u.get("kind")):
                m["sub"] = bool(SUBTASK_RE.search(f"{u.get('title') or ''} {u.get('kind') or ''}"))
            body = _acp_text(u.get("content"))
            detail = clip(body, 3000) if body else brief_input(u.get("rawInput"))
            run.step(tid, title=clip(m.get("title") or u.get("kind") or "инструмент", 200),
                     status=_acp_status(u.get("status")) or ("running" if kind == "tool_call" else None),
                     kind=u.get("kind"), detail=detail, parent=parent, subtask=m.get("sub", False))
        elif kind == "plan":
            entries = [{"text": clip(e.get("content"), 200), "status": e.get("status")}
                       for e in (u.get("entries") or [])][:30]
            run.co.flush()
            chat.emit("plan", run=run.id, entries=entries)

    def on_request(method, params):
        if method == "session/request_permission":
            tc = params.get("toolCall") or {}
            opts = [{"id": o.get("optionId"), "name": o.get("name") or o.get("optionId"), "kind": o.get("kind")}
                    for o in (params.get("options") or []) if o.get("optionId")]
            title = tc.get("title") or tool_meta.get(tc.get("toolCallId"), {}).get("title") or "Действие агента"
            ans = run.ask(title, opts, detail=brief_input(tc.get("rawInput")))
            if ans:
                return {"outcome": {"outcome": "selected", "optionId": ans}}
            return {"outcome": {"outcome": "cancelled"}}
        raise AcpError("MURF не поддерживает " + clip(method, 60))

    conn = chat.acp
    if conn is None or not conn.alive():
        argv, extra_env = acp_argv(bot)
        check_argv(argv)
        run.argv = argv
        extra_env = dict(extra_env or {}, **bot_env(chat))
        conn = AcpConn(argv, bot["workdir"], env_for(extra_env), os.path.join(LOGS, chat.id + ".stderr"))
        conn_ref["c"] = conn
        chat.acp = conn
        conn.on_update, conn.on_request = on_update, on_request
        try:
            init = conn.request("initialize", {"protocolVersion": 1, "clientCapabilities": {
                "fs": {"readTextFile": False, "writeTextFile": False}, "terminal": False},
                "clientInfo": {"name": "murf", "version": VERSION}}, timeout=120)
        except AcpError as e:
            chat.acp = None
            conn.close()
            raise ApiError(500, f"{CLI_INFO[bot['backend']]['name']} не запустился в режиме ACP: {e}. "
                           + clip(_stderr_tail(chat), 300))
        conn.caps = init.get("agentCapabilities") or {}
        conn.first_prompt = True
        if ses and conn.caps.get("loadSession"):
            loading["on"] = True
            conn.session = ses
            try:
                conn.request("session/load", {"sessionId": ses, "cwd": bot["workdir"], "mcpServers": []}, timeout=180)
                conn.first_prompt = False
            except AcpError as e:
                log("acp load failed", chat.id, e)
                conn.session = None
            finally:
                loading["on"] = False
        if conn.session is None:
            try:
                res = conn.request("session/new", {"cwd": bot["workdir"], "mcpServers": []}, timeout=180)
            except AcpError as e:
                chat.acp = None
                conn.close()
                if "auth" in str(e).lower():
                    raise ApiError(401, f"{CLI_INFO[bot['backend']]['name']}: нужен вход в CLI на сервере ({e})")
                raise ApiError(500, f"не удалось начать сессию: {e}")
            conn.session = res.get("sessionId")
            chat.meta["backend_session"] = conn.session
            chat.save_meta()
            acp_configure(conn, res, bot, run)
    else:
        conn_ref["c"] = conn
        conn.on_update, conn.on_request = on_update, on_request
        run.argv = conn.argv
    run.proc = conn.proc
    run.acp_conn = conn
    prompt = run.prompt
    if conn.first_prompt:
        pre = bot_instructions(bot)
        hist = recap(chat) if ses else ""
        if hist:
            pre += "\n\nКраткая история этого чата:\n" + hist
        prompt = f"<murf-bot-instructions>\n{pre}\n</murf-bot-instructions>\n\n{prompt}"
        conn.first_prompt = False
    try:
        res = conn.request("session/prompt", {"sessionId": conn.session,
                                              "prompt": [{"type": "text", "text": prompt}]}, timeout=None)
    except AcpError as e:
        if run.stop_requested:
            return None
        chat.acp = None
        raise ApiError(500, str(e))
    stop = res.get("stopReason")
    if stop == "cancelled":
        run.stop_requested = True
    elif stop == "refusal":
        run.notice("Агент отказался выполнять запрос.")
    elif stop in ("max_tokens", "max_turn_requests"):
        run.notice("Агент остановился по лимиту (" + stop + ").")
    return 0


def acp_configure(conn, res, bot, run):
    """Режим доступа и модель — через стандартные методы ACP, если агент их предлагает."""
    sid = conn.session
    modes = res.get("modes") or {}
    available = {m.get("id") for m in (modes.get("availableModes") or [])}
    access = bot.get("access") or "safe"
    want = {"safe": "default", "readonly": "plan"}.get(access)
    if want and want in available:
        if modes.get("currentModeId") != want:
            try:
                conn.request("session/set_mode", {"sessionId": sid, "modeId": want}, timeout=30)
            except AcpError as e:
                run.notice(f"Режим {want} не включён: {e}")
    elif access == "readonly":
        run.notice("Агент не предлагает режим «только чтение» — действуют его обычные запросы разрешений.")
    if access == "safe" and bot["backend"] == "grok":
        run.notice("Grok: если в ~/.grok/config.toml включено always-approve, Grok выполняет действия без "
                   "запроса — MURF этот режим не включает, но и выключить его в конфиге не может.")
    model = bot.get("model")
    if model and bot["backend"] not in ("grok", "gemini"):
        opts = res.get("configOptions") or []
        try:
            if any(o.get("id") == "model" for o in opts):
                conn.request("session/set_config_option", {"sessionId": sid, "configId": "model", "value": model}, timeout=30)
            elif res.get("models"):
                conn.request("session/set_model", {"sessionId": sid, "modelId": model}, timeout=30)
        except AcpError as e:
            run.notice(f"Модель {model} не выбрана: {e}")


def acp_reaper():
    while True:
        time.sleep(60)
        with lock:
            chats = list(Chat._cache.values())
        for c in chats:
            conn = c.acp
            if conn and not (c.run and c.run.active) and (now() - conn.used > IDLE_ACP or not conn.alive()):
                c.acp = None
                threading.Thread(target=conn.close, daemon=True).start()


# ───────────────────── codex exec --json / claude -p stream-json ─────────────────────

def _spawn(run, argv, stdin_text, extra_env=None):
    check_argv(argv)
    run.argv = [a if len(a) < 120 else a[:40] + "…" for a in argv]
    errp = os.path.join(LOGS, run.chat.id + ".stderr")
    errf = open(errp, "ab")
    os.chmod(errp, 0o600)
    extra_env = dict(extra_env or {}, **bot_env(run.chat))
    p = subprocess.Popen(argv, cwd=run.bot["workdir"], env=env_for(extra_env), stdin=subprocess.PIPE,
                         stdout=subprocess.PIPE, stderr=errf, start_new_session=True)
    errf.close()
    run.proc = p
    try:
        p.stdin.write(stdin_text.encode())
        p.stdin.close()
    except OSError:
        pass
    return p


def _stderr_tail(chat):
    try:
        path = os.path.join(LOGS, chat.id + ".stderr")
        with open(path, "rb") as f:
            f.seek(max(0, os.path.getsize(path) - 800))
            lines = [l for l in f.read().decode("utf-8", "ignore").splitlines() if l.strip()][-3:]
        return re.sub(r"(?i)(token|key|secret|bearer|password)([=: ]+)\S+", r"\1\2<скрыто>", " | ".join(lines))
    except OSError:
        return ""


def codex_argv(exe, bot, thread):
    access = bot.get("access") or "safe"
    argv = [exe, "exec"]
    if thread:
        argv += ["resume", thread]
    argv += ["--json", "--skip-git-repo-check"]
    sandbox = {"safe": "workspace-write", "readonly": "read-only"}.get(access)
    if sandbox:
        # `exec resume` не принимает -s, поэтому песочница всегда через -c (работает в обоих случаях)
        argv += ["-c", f'sandbox_mode="{sandbox}"']
    if bot.get("model"):
        argv += ["-m", bot["model"]]
    return argv + ["-"]


def run_codex(run):
    bot, chat = run.bot, run.chat
    exe = which("codex")
    if not exe:
        raise ApiError(500, "codex не установлен")
    thread = chat.meta.get("backend_session")
    access = bot.get("access") or "safe"
    prompt = run.prompt
    if not thread:
        prompt = f"<murf-bot-instructions>\n{bot_instructions(bot)}\n</murf-bot-instructions>\n\n{prompt}"
        run.notice("Codex в режиме exec не задаёт вопросов: что выходит за песочницу, он не выполнит. Режим бота: "
                   + {"safe": "песочница рабочей папки", "readonly": "только чтение",
                      "cli": "как в конфиге Codex"}[access] + ".")
    p = _spawn(run, codex_argv(exe, bot, thread), prompt)
    for raw in p.stdout:
        try:
            ev = json.loads(raw)
        except ValueError:
            continue
        t = ev.get("type", "")
        if t == "thread.started" and ev.get("thread_id"):
            chat.meta["backend_session"] = ev["thread_id"]
            chat.save_meta()
        elif t.startswith("item."):
            codex_item(run, t, ev.get("item") or {})
        elif t == "turn.failed":
            run.notice("Codex: " + clip((ev.get("error") or {}).get("message"), 400))
        elif t == "error":
            run.notice("Codex: " + clip(ev.get("message"), 400))
    rc = p.wait()
    if rc and not run.stop_requested:
        tail = _stderr_tail(chat)
        if tail:
            run.notice("stderr: " + clip(tail, 500))
    return rc


def codex_item(run, t, item):
    it = item.get("type") or item.get("item_type") or ""
    iid = item.get("id") or new_id("i")
    done = t == "item.completed"
    failed = item.get("status") == "failed"
    end_status = ("failed" if failed else "done") if done else "running"
    if it in ("agent_message", "assistant_message"):
        if done:
            run.text(item.get("text") or "")
    elif it == "reasoning":
        if done:
            run.thinking(item.get("text") or "")
    elif it == "command_execution":
        if done and item.get("exit_code") not in (0, None):
            end_status = "failed"
        run.step(iid, title="$ " + clip(item.get("command"), 160), kind="execute", status=end_status,
                 detail=clip(item.get("aggregated_output"), 3000) if done else None)
    elif it == "file_change":
        files = ", ".join(f"{c.get('kind', '')} {c.get('path', '')}".strip() for c in (item.get("changes") or []))
        run.step(iid, title="Изменение файлов", kind="edit", detail=clip(files, 1000), status=end_status)
    elif it == "mcp_tool_call":
        run.step(iid, title=f"{item.get('server', '')}.{item.get('tool', '')}", kind="mcp", status=end_status)
    elif it == "web_search":
        run.step(iid, title="Поиск: " + clip(item.get("query"), 120), kind="search", status=end_status)
    elif it == "todo_list":
        entries = [{"text": clip(x.get("text"), 200), "status": "completed" if x.get("completed") else "pending"}
                   for x in (item.get("items") or [])][:30]
        run.co.flush()
        run.chat.emit("plan", run=run.id, entries=entries)
    elif it in ("collab_tool_call", "spawn_agent", "subagent"):
        run.step(iid, title=clip(item.get("prompt") or item.get("tool") or "подзадача", 160), subtask=True,
                 status=end_status, detail=clip(item.get("result") or item.get("output"), 3000) if done else None)
    elif it == "error":
        run.notice("Codex: " + clip(item.get("message"), 400))
    elif it:
        run.step(iid, title=it, status=end_status)


def claude_argv(exe, bot, session):
    access = bot.get("access") or "safe"
    argv = [exe, "-p", "--output-format", "stream-json", "--verbose"]
    mode = {"safe": "default", "readonly": "plan"}.get(access)
    if mode:
        argv += ["--permission-mode", mode]
    if bot.get("model"):
        argv += ["--model", bot["model"]]
    argv += ["--append-system-prompt", bot_instructions(bot)]
    if session:
        argv += ["--resume", session]
    return argv


def run_claude(run):
    bot, chat = run.bot, run.chat
    exe = which("claude")
    if not exe:
        raise ApiError(500, "claude не установлен")
    p = _spawn(run, claude_argv(exe, bot, chat.meta.get("backend_session")), run.prompt)
    for raw in p.stdout:
        try:
            ev = json.loads(raw)
        except ValueError:
            continue
        claude_event(run, ev)
    rc = p.wait()
    if rc and not run.stop_requested:
        tail = _stderr_tail(chat)
        if tail:
            run.notice("stderr: " + clip(tail, 500))
    return rc


def claude_event(run, ev):
    chat = run.chat
    t = ev.get("type")
    parent = ev.get("parent_tool_use_id")
    if t == "system" and ev.get("subtype") == "init" and ev.get("session_id"):
        chat.meta["backend_session"] = ev["session_id"]
        chat.save_meta()
    elif t == "assistant":
        for c in (ev.get("message") or {}).get("content") or []:
            ct = c.get("type")
            if ct == "text":
                run.text(c.get("text") or "", parent=parent)
            elif ct == "thinking":
                run.thinking(c.get("thinking") or "", parent=parent)
            elif ct == "tool_use":
                name = c.get("name") or "tool"
                is_sub = name in ("Task", "Agent") or bool(SUBTASK_RE.search(name))
                inp = c.get("input") or {}
                title = (inp.get("description") or inp.get("prompt") or name) if is_sub else \
                    f"{name}: {brief_input(inp) or ''}".rstrip(": ")
                run.step(c.get("id") or new_id("t"), title=clip(title, 200), status="running", kind=name,
                         parent=parent, subtask=is_sub, detail=brief_input(inp))
    elif t == "user":
        for c in (ev.get("message") or {}).get("content") or []:
            if isinstance(c, dict) and c.get("type") == "tool_result":
                body = c.get("content")
                if isinstance(body, list):
                    body = "\n".join(x.get("text", "") for x in body if isinstance(x, dict))
                run.step(c.get("tool_use_id") or new_id("t"), status="failed" if c.get("is_error") else "done",
                         detail=clip(body, 3000))
    elif t == "result":
        denials = ev.get("permission_denials") or []
        if denials:
            names = ", ".join(sorted({str(d.get("tool_name", "?")) for d in denials}))
            run.notice("Claude Code в режиме -p не может спросить разрешение; отклонено: " + clip(names, 300))
        if ev.get("is_error"):
            run.notice("Claude Code: " + clip(ev.get("result") or ev.get("subtype"), 400))


BACKEND_IMPL = {"hermes": run_acp, "grok": run_acp, "gemini": run_acp, "opencode": run_acp,
                "codex": run_codex, "claude": run_claude}


def stop_run(chat):
    run = chat.run
    if not run or not run.active:
        return {"ok": True, "running": False}
    run.stop_requested = True
    run.cancel_approvals()
    conn = run.acp_conn
    if conn and conn.session:
        conn.notify("session/cancel", {"sessionId": conn.session})
    elif run.proc and run.proc.poll() is None:
        try:
            os.killpg(run.proc.pid, signal.SIGINT)
        except OSError:
            pass

    def hard():
        time.sleep(10)
        if run.active and run.proc and run.proc.poll() is None:
            try:
                os.killpg(run.proc.pid, signal.SIGTERM)
            except OSError:
                pass
            if conn:
                chat.acp = None
    threading.Thread(target=hard, daemon=True).start()
    return {"ok": True, "running": True}


def runs_view():
    out = []
    emoji = {b["id"]: b.get("emoji") for b in load_bots()}
    with lock:
        live = [c for c in Chat._cache.values() if c.run and c.run.active]
        done = list(reversed(recent_runs[-60:]))
    for c in live:
        r = c.run
        out.append({"run": r.id, "bot": r.bot["id"], "bot_name": r.bot["name"], "emoji": emoji.get(r.bot["id"]),
                    "chat": c.id, "chat_title": c.meta.get("title"), "backend": r.bot["backend"],
                    "status": "waiting" if r.approvals else "running", "started": r.started,
                    "duration": round(now() - r.started, 1), "from_bot": r.origin,
                    "subtasks": [{"id": k, "title": v.get("title"), "status": v.get("status"),
                                  "started": v.get("started"), "ended": v.get("ended")}
                                 for k, v in list(r.steps.items()) if v.get("subtask")][-20:]})
    for r in done:
        out.append({"run": r["run"], "bot": r["bot"], "bot_name": r.get("bot_name"), "emoji": emoji.get(r["bot"]),
                    "chat": r["chat"], "chat_title": r.get("chat_title"), "backend": r["backend"],
                    "status": r["status"], "started": r["started"], "duration": r.get("duration"),
                    "error": r.get("error"), "from_bot": r.get("from_bot"), "subtasks_count": r.get("subtasks", 0)})
    return {"runs": out}


# ───────────────────────────── HTTP ─────────────────────────────

class Handler(BaseHTTPRequestHandler):
    server_version = "murf-bots/" + VERSION

    def log_message(self, fmt, *a):
        pass

    def send_json(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b)

    def jbody(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n > 256 * 1024:
            raise ApiError(413, "слишком большой запрос")
        try:
            data = json.loads(self.rfile.read(n) or b"{}") if n else {}
        except ValueError:
            raise ApiError(400, "неверный JSON")
        if not isinstance(data, dict):
            raise ApiError(400, "ожидается объект JSON")
        return data

    def dispatch(self, method):
        try:
            if self.client_address[0] not in ("127.0.0.1", "::1"):
                raise ApiError(403, "только локально")
            u = urlparse(self.path)
            p = u.path.rstrip("/") or "/"
            if p.startswith("/phone/api/bots"):
                p = p[len("/phone/api/bots"):] or "/"
            if p == "/propose" and method == "POST":
                chat = chat_by_token(self.headers.get("X-Murf-Bot-Token"))
                return self.send_json(200, propose_bot(chat, self.jbody(), via="http"))
            if p != "/health" and self.headers.get("X-Agent-App") != "1":
                raise ApiError(403, "нужен заголовок X-Agent-App")
            body = self.jbody() if method == "POST" else {}
            res = route(method, p, parse_qs(u.query), body, self)
            if res is not None:
                self.send_json(200, res)
        except ApiError as e:
            obj = {"ok": False, "error": e.message}
            obj.update(e.extra)
            try:
                self.send_json(e.status, obj)
            except OSError:
                pass
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:  # noqa: BLE001
            log("http error", type(e).__name__, e)
            try:
                self.send_json(500, {"ok": False, "error": "внутренняя ошибка"})
            except OSError:
                pass

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def stream(self, chat, after):
        """SSE: сначала пропущенные события (seq > after), затем новые. Закрывается через 60 с простоя."""
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()
        self.close_connection = True
        last_out, idle_since = now(), None
        while True:
            with chat.cond:
                if chat.seq <= after:
                    chat.cond.wait(15)
                evs = [e for e in chat.events if e["seq"] > after] if chat.seq > after else []
            out = b""
            for e in evs:
                after = e["seq"]
                out += b"id: %d\nevent: murf\ndata: " % e["seq"] + json.dumps(e, ensure_ascii=False).encode() + b"\n\n"
            if not out and now() - last_out >= 14:
                out = b": ping\n\n"
            if out:
                self.wfile.write(out)
                self.wfile.flush()
                last_out = now()
            if chat.run and chat.run.active:
                idle_since = None
            elif idle_since is None:
                idle_since = now()
            elif now() - idle_since > 60:
                return


def route(method, p, q, body, h):
    body.pop("_internal", None)  # только для внутренних вызовов (создание по одобренному предложению)
    if method == "GET" and p == "/health":
        return {"ok": True, "version": VERSION}
    if method == "GET" and p == "/clis":
        return {"clis": detect_clis(refresh=(q.get("refresh") or ["0"])[0] == "1"),
                "profiles": hermes_profiles(), "workroot": WORK_ROOT,
                "backends": list(BACKENDS), "access": list(ACCESS)}
    if method == "GET" and p == "/runs":
        return runs_view()
    if method == "GET" and p == "/proposals":
        ps = load_proposals()
        return {"proposals": [x for x in ps if x["status"] == "pending"] +
                [x for x in reversed(ps) if x["status"] != "pending"][:10]}
    m = re.fullmatch(r"/proposals/(p[0-9a-f]{12})/(approve|reject)", p)
    if m and method == "POST":
        return decide_proposal(m.group(1), m.group(2) == "approve")
    if p == "/":
        if method == "GET":
            bots = [bot_public(b) for b in load_bots()]
            for b in bots:
                cs = list_chats(b["id"])
                b["chats"] = len(cs)
                b["running"] = sum(1 for c in cs if c.get("running"))
                b["last_active"] = cs[0]["updated"] if cs else None
            return {"bots": sorted(bots, key=lambda b: b.get("created") or 0)}
        return upsert_bot(body)
    m = re.fullmatch(r"/(b[0-9a-f]{12})(/[a-z]+)?", p)
    if m:
        bid, sub = m.group(1), m.group(2) or ""
        if method == "GET" and sub == "":
            return {"bot": bot_public(get_bot(bid))}
        if method == "POST" and sub == "":
            return upsert_bot(body, bid)
        if method == "POST" and sub == "/delete":
            return delete_bot(bid)
        if sub == "/chats":
            if method == "GET":
                get_bot(bid)
                return {"chats": list_chats(bid)}
            c = Chat.create(get_bot(bid), title=str(body.get("title") or ""))
            text = str(body.get("text") or "").strip()
            if text:
                start_run(c, clip(text, 20000))
            return {"ok": True, "chat": c.public()}
    m = re.fullmatch(r"/chats/(c[0-9a-f]{12})(/[a-z]+)?", p)
    if m:
        chat = Chat.get(m.group(1))
        sub = m.group(2) or ""
        if method == "GET" and sub == "":
            after = int((q.get("after") or ["0"])[0] or 0)
            return {"chat": chat.public(), "bot": bot_public(get_bot(chat.meta["bot"])),
                    "events": [e for e in chat.events if e["seq"] > after][-5000:]}
        if method == "GET" and sub == "/stream":
            h.stream(chat, int((q.get("after") or ["0"])[0] or 0))
            return None
        if method != "POST":
            raise ApiError(405, "метод не поддерживается")
        if sub == "/send":
            text = str(body.get("text") or "").strip()
            if not text:
                raise ApiError(400, "пустое сообщение")
            run = start_run(chat, clip(text, 20000))
            return {"ok": True, "run": run.id, "chat": chat.public()}
        if sub == "/approve":
            if not chat.run or not chat.run.active:
                raise ApiError(409, "запуск уже завершён")
            opt = body.get("option")
            chat.run.answer(str(body.get("id") or ""), str(opt) if opt else None)
            return {"ok": True}
        if sub == "/stop":
            return stop_run(chat)
        if sub == "/handoff":
            return do_handoff(chat, str(body.get("to") or ""), str(body.get("task") or ""))
        if sub == "/delete":
            if chat.run and chat.run.active:
                raise ApiError(409, "идёт запуск — сначала остановите")
            drop_chat_files(chat.id)
            return {"ok": True}
    raise ApiError(404, "not found")


def main():
    ensure_dirs()
    os.makedirs(WORK_ROOT, mode=0o700, exist_ok=True)
    load_recent_runs()
    threading.Thread(target=acp_reaper, daemon=True).start()
    threading.Thread(target=detect_clis, daemon=True).start()
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    srv.daemon_threads = True
    log("murf-bots", VERSION, "on 127.0.0.1:%d" % PORT)
    srv.serve_forever()


if __name__ == "__main__":
    main()
