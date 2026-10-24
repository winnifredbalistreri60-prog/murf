#!/usr/bin/env python3
"""Агент — мост «Телефон» (relay) между агентом Hermes и Android-приложением.

Два слушателя (оба только на 127.0.0.1):
  PHONE_PORT (8790) — проброшен на VDS и отдаётся nginx'ом как https://your-server.example/phone/
      /phone/api/*  — только с cookie WebUI (nginx auth_request)
      /phone/up/<token>, /phone/dl/<token> — одноразовые токены (для curl из Termux)
  AGENT_PORT (8791) — только локально, для MCP-сервера агента: POST /call, GET /status

Без внешних зависимостей (stdlib Python 3.10+).
"""
import json, os, re, secrets, shutil, subprocess, sys, threading, time, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, quote

HOME = os.path.expanduser("~")
PHONE_PORT = int(os.environ.get("PHONE_PORT", "8790"))
AGENT_PORT = int(os.environ.get("AGENT_PORT", "8791"))
BOTS_PORT = int(os.environ.get("MURF_BOTS_PORT", "8792"))  # служба MURF bots (murf_bots.py)
PUBLIC_BASE = os.environ.get("PUBLIC_BASE", "https://your-server.example")
FILES_DIR = os.environ.get("PHONE_FILES_DIR", os.path.join(HOME, "phone-files"))
MAX_UPLOAD = int(os.environ.get("PHONE_MAX_UPLOAD", str(50 * 1024 * 1024)))
RCLONE_CONF = os.environ.get("RCLONE_CONFIG", os.path.join(HOME, ".config/rclone/rclone.conf"))
os.makedirs(FILES_DIR, exist_ok=True)
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import murf_pairing as pairing  # noqa: E402  (QR-привязка устройств)
import murf_reports as reports  # noqa: E402  («Сообщить о проблеме»)

lock = threading.Condition()
pending = []            # запросы, ещё не забранные телефоном
inflight = {}           # id -> request (забраны, ждём результат)
results = {}            # id -> result
devices = {}            # device_id -> {last_seen, name, caps, ...}
up_tokens = {}          # token -> {request_id, expires, name}
dl_tokens = {}          # token -> {path, expires, name}
history = []            # последние 50 запросов (для статуса)


def log(*a):
    print(time.strftime("%F %T"), *a, flush=True)


def safe_name(name, default="file"):
    name = os.path.basename(name or "") or default
    name = re.sub(r"[^\w.\-() ]+", "_", name, flags=re.U).strip()[:120]
    return name or default


def phone_online():
    now = time.time()
    return {d: v for d, v in devices.items() if now - v.get("last_seen", 0) < 70}


def gc_tokens():
    now = time.time()
    for t in [t for t, v in up_tokens.items() if v["expires"] < now]:
        up_tokens.pop(t, None)
    for t in [t for t, v in dl_tokens.items() if v["expires"] < now]:
        dl_tokens.pop(t, None)


def make_call(tool, args, timeout):
    """Ставит запрос в очередь для телефона и ждёт результат."""
    rid = uuid.uuid4().hex[:12]
    req = {"id": rid, "tool": tool, "args": args or {}, "created": time.time(), "timeout": timeout}
    # для файловых операций заранее выдаём одноразовые токены
    if tool in ("pull_file", "take_photo"):
        tok = secrets.token_urlsafe(24)
        up_tokens[tok] = {"request_id": rid, "expires": time.time() + timeout + 60, "name": None}
        req["upload_url"] = f"{PUBLIC_BASE}/phone/up/{tok}"
    if tool == "push_file":
        src = os.path.realpath(os.path.expanduser(args.get("local_path", "")))
        if not os.path.isfile(src):
            return {"ok": False, "error": f"Файл на сервере не найден: {src}"}
        tok = secrets.token_urlsafe(24)
        nm = safe_name(args.get("name") or os.path.basename(src))
        dl_tokens[tok] = {"path": src, "expires": time.time() + timeout + 60, "name": nm}
        req["download_url"] = f"{PUBLIC_BASE}/phone/api/dl/{tok}"
        req["args"] = dict(args, name=nm, size=os.path.getsize(src))
    with lock:
        if not phone_online():
            return {"ok": False, "error": "Телефон не подключён: включите «Телефон → Мост» в приложении «Агент»."}
        pending.append(req)
        history.append({"id": rid, "tool": tool, "t": time.strftime("%F %T"), "state": "queued"})
        del history[:-50]
        lock.notify_all()
        deadline = time.time() + timeout
        while rid not in results:
            left = deadline - time.time()
            if left <= 0:
                break
            lock.wait(min(left, 5))
        res = results.pop(rid, None)
        if res is None:
            if req in pending:
                pending.remove(req)
            inflight.pop(rid, None)
            res = {"ok": False, "error": f"Нет ответа от телефона за {timeout} с (не подтверждено или телефон спит)."}
        for h in history:
            if h["id"] == rid:
                h["state"] = "ok" if res.get("ok") else "error"
    # если телефон загрузил файл — добавим путь
    for t, v in list(up_tokens.items()):
        if v["request_id"] == rid:
            if v.get("saved"):
                res["saved_path"] = v["saved"]
            up_tokens.pop(t, None)
    return res


def drive_status():
    rc = shutil.which("rclone")
    configured = False
    if os.path.exists(RCLONE_CONF):
        configured = "[gdrive]" in open(RCLONE_CONF, encoding="utf-8", errors="ignore").read()
    return {"rclone": bool(rc), "configured": configured, "remote": "gdrive:" if configured else None}


def drive_set_token(token_text):
    m = re.search(r"\{.*\}", token_text or "", re.S)
    if not m:
        return {"ok": False, "error": "В тексте нет JSON-токена rclone ({...})."}
    try:
        tok = json.loads(m.group(0))
        assert "access_token" in tok or "refresh_token" in tok
    except Exception:
        return {"ok": False, "error": "Токен не похож на вывод `rclone authorize \"drive\"`."}
    if not shutil.which("rclone"):
        return {"ok": False, "error": "rclone не установлен на сервере."}
    os.makedirs(os.path.dirname(RCLONE_CONF), exist_ok=True)
    if os.path.exists(RCLONE_CONF):
        shutil.copy2(RCLONE_CONF, RCLONE_CONF + ".bak-" + time.strftime("%Y%m%d-%H%M%S"))
    env = dict(os.environ, RCLONE_CONFIG=RCLONE_CONF)
    subprocess.run(["rclone", "config", "delete", "gdrive"], env=env, capture_output=True)
    p = subprocess.run(["rclone", "config", "create", "gdrive", "drive", "scope=drive",
                        "token=" + json.dumps(tok), "--non-interactive"], env=env, capture_output=True, text=True)
    os.chmod(RCLONE_CONF, 0o600)
    if p.returncode != 0:
        return {"ok": False, "error": (p.stderr or p.stdout)[-400:]}
    t = subprocess.run(["rclone", "about", "gdrive:", "--json"], env=env, capture_output=True, text=True, timeout=60)
    return {"ok": t.returncode == 0, "about": t.stdout[-300:], "error": None if t.returncode == 0 else t.stderr[-400:]}


# ---------- обновления Hermes Agent (для экрана настроек приложения) ----------
UPD_SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "agent_update.py")
BK_ROOT = os.path.join(HOME, "hermes-backups")
UPD_STATE = os.path.join(BK_ROOT, "agent-update-state.json")
UPD_CHECK = os.path.join(BK_ROOT, "agent-update-check.json")
UPD_CHECK_EVERY = 3 * 3600
WEBUI_DIR = os.path.join(HOME, "hermes-webui")
_check_proc = None
_webui_cache = {}


def _read_json(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return None


def _job_running(st):
    if not st or not st.get("running"):
        return False
    try:
        os.kill(int(st.get("pid") or 0), 0)
        return True
    except Exception:
        return False


def start_agent_check():
    global _check_proc
    if _check_proc is not None and _check_proc.poll() is None:
        return False
    _check_proc = subprocess.Popen(["/usr/bin/python3", UPD_SCRIPT, "--check"], stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, start_new_session=True)
    return True


def webui_info():
    """WebUI установлен из архива (без git): версия из CHANGELOG, последний релиз — из GitHub (кэш 6 ч)."""
    if _webui_cache.get("t", 0) > time.time() - 6 * 3600:
        return _webui_cache["v"]
    ver = None
    try:
        for line in open(os.path.join(WEBUI_DIR, "CHANGELOG.md"), encoding="utf-8"):
            m = re.match(r"##\s*\[(v[\d.]+)\]", line)
            if m:
                ver = m.group(1)
                break
    except OSError:
        pass
    latest = None
    try:
        import urllib.request
        req = urllib.request.Request("https://api.github.com/repos/nesquena/hermes-webui/releases/latest",
                                     headers={"User-Agent": "agent-phone-relay", "Accept": "application/vnd.github+json"})
        latest = json.loads(urllib.request.urlopen(req, timeout=10).read().decode()).get("tag_name")
    except Exception:
        pass

    def key(v):
        return [int(x) for x in re.findall(r"\d+", v or "")]
    v = {"version": ver, "latest": latest, "git": os.path.isdir(os.path.join(WEBUI_DIR, ".git")),
         "update_available": bool(ver and latest and key(latest) > key(ver)),
         "note": "установлен из архива — обновляется вручную"}
    _webui_cache.update(t=time.time(), v=v)
    return v


def agent_update_status(kick=True):
    chk = _read_json(UPD_CHECK)
    st = _read_json(UPD_STATE)
    checking = _check_proc is not None and _check_proc.poll() is None
    if kick and not checking and (not chk or time.time() - chk.get("checked_at", 0) > UPD_CHECK_EVERY):
        checking = start_agent_check()
    running = _job_running(st)
    if st and st.get("running") and not running:
        st["running"] = False
        st["ok"] = False if st.get("ok") is None else st["ok"]
        st["message"] = st.get("message") or "процесс обновления прервался"
    return {"check": chk, "checking": checking, "job": st, "running": running, "webui": webui_info(),
            "check_every_hours": UPD_CHECK_EVERY // 3600}


def start_agent_update(dry_run):
    st = _read_json(UPD_STATE)
    if _job_running(st):
        return {"ok": False, "error": "обновление уже выполняется"}
    mode = "dry-run" if dry_run else "apply"
    unit = "agent-update-" + time.strftime("%Y%m%d-%H%M%S")
    cmd = ["systemd-run", "--user", "--collect", "--quiet", f"--unit={unit}", "--description=Agent app: Hermes update " + mode,
           "/usr/bin/python3", UPD_SCRIPT, "--mode", mode]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    if p.returncode != 0:
        # запасной путь — отдельная сессия (переживёт рестарт relay не всегда, но рестарт шлюза — да)
        subprocess.Popen(["/usr/bin/python3", UPD_SCRIPT, "--mode", mode], stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, start_new_session=True)
        unit = None
    log("agent-update start", mode, unit)
    return {"ok": True, "mode": mode, "unit": unit}



def screen_type(text, enter=False):
    """Набирает текст на дисплее бот-десктопа Hermes (Xvnc) через xdotool."""
    import subprocess
    text = text[:4000]
    bd = os.path.expanduser("~/.hermes/bot-desktop")
    try:
        disp = open(os.path.join(bd, "display")).read().strip()
    except OSError:
        return {"ok": False, "error": "экран агента не запущен"}
    env = dict(os.environ, DISPLAY=":" + disp.lstrip(":"), XAUTHORITY=os.path.join(bd, "Xauthority"))
    try:
        if text:
            r = subprocess.run(["xdotool", "type", "--clearmodifiers", "--delay", "12", "--", text],
                               env=env, capture_output=True, text=True, timeout=120)
            if r.returncode != 0:
                return {"ok": False, "error": (r.stderr or "xdotool error").strip()[:300]}
        if enter:
            subprocess.run(["xdotool", "key", "--clearmodifiers", "Return"], env=env, capture_output=True, timeout=10)
    except Exception as e:
        return {"ok": False, "error": str(e)[:300]}
    log("screen-type", len(text), "enter" if enter else "")
    return {"ok": True, "typed": len(text)}

HERMES_AGENT_DIR = os.environ.get("HERMES_AGENT_DIR", os.path.join(HOME, ".hermes", "hermes-agent"))
HERMES_HOME = os.environ.get("HERMES_HOME", os.path.join(HOME, ".hermes"))

# Bot Desktop / «Экран агента»: проверено на Linux x86_64 + systemd + TigerVNC(Xvnc)+Xfce (как Hermes).
# ARM, Docker-only без Xvnc, хосты без display-стека — не поддерживаются (явная ошибка, не тихий 404).
_SCREEN_SUPPORTED_MACHINES = ("x86_64", "amd64")
_SCREEN_NEED_RU = (
    "экран агента требует Linux x86_64, systemd, TigerVNC (Xvnc) и Xfce "
    "(xfwm4, xfce4-panel, xfdesktop, xfsettingsd), плюс Hermes tools/bot_desktop"
)


def _hermes_tools_python():
    """Python из tools Hermes (с bot_desktop); не venv агента — там модулей может не быть."""
    env = os.environ.get("HERMES_PYTHON")
    if env and os.path.isfile(env):
        return env
    tools = os.path.join(HERMES_HOME, "tools")
    try:
        cands = sorted(
            (os.path.join(tools, d, "bin", "python3")
             for d in os.listdir(tools) if d.startswith("python-")),
            key=lambda path: os.path.getmtime(path) if os.path.isfile(path) else 0,
        )
        for path in reversed(cands):
            if os.path.isfile(path):
                return path
    except OSError:
        pass
    return os.path.join(HERMES_AGENT_DIR, "venv", "bin", "python")


def _screen_desktop_blocker():
    """None если можно звать runtime; иначе русский текст для клиента."""
    helper = os.path.join(os.path.dirname(os.path.abspath(__file__)), "murf_screen_desktop.py")
    if not os.path.isfile(helper):
        return "экран агента: нет murf_screen_desktop.py рядом с phone_relay"
    machine = ""
    try:
        machine = os.uname().machine
    except Exception:
        pass
    if machine and machine not in _SCREEN_SUPPORTED_MACHINES:
        return (
            f"экран агента не поддерживается на {machine} — проверено только на x86_64 "
            f"(Ubuntu/Debian + TigerVNC/Xvnc + Xfce)"
        )
    if not os.path.isdir(os.path.join(HERMES_AGENT_DIR, "tools", "bot_desktop")):
        return (
            "экран агента требует Hermes Agent с tools/bot_desktop "
            "(переустановите агент; murf-install ставит desktop-стек отдельно от computer-use)"
        )
    if not (shutil.which("Xvnc") or shutil.which("Xtigervnc")):
        return (
            "экран агента требует TigerVNC (Xvnc): пакет tigervnc-standalone-server "
            "и Xfce core — murf-install ставит их на x86_64"
        )
    for b in ("xfwm4", "xfce4-panel", "xfdesktop", "xfsettingsd"):
        if not shutil.which(b):
            return f"экран агента требует Xfce ({b} не найден). {_SCREEN_NEED_RU}"
    py = _hermes_tools_python()
    if not os.path.isfile(py):
        return "экран агента: нет Python Hermes tools (HERMES_HOME/tools/python-*/bin/python3)"
    return None


def screen_desktop(cmd):
    """Старт/статус Bot Desktop через murf_screen_desktop.py + hermes tools python -I."""
    helper = os.path.join(os.path.dirname(os.path.abspath(__file__)), "murf_screen_desktop.py")
    blocked = _screen_desktop_blocker()
    if blocked:
        return {"ok": False, "running": False, "display": None, "socket": None, "error": blocked, "supported": False}
    py = _hermes_tools_python()
    env = dict(os.environ, HERMES_HOME=HERMES_HOME, HERMES_AGENT_DIR=HERMES_AGENT_DIR)
    try:
        r = subprocess.run(
            [py, "-I", helper, cmd],
            cwd=HERMES_AGENT_DIR,
            capture_output=True,
            text=True,
            timeout=90 if cmd == "start" else 20,
            env=env,
        )
        line = (r.stdout or "").strip().splitlines()
        if not line:
            err = (r.stderr or "").strip()[:300] or f"exit {r.returncode}"
            return {"ok": False, "running": False, "display": None, "socket": None, "error": err}
        res = json.loads(line[-1])
    except Exception as e:
        log("screen-desktop error", cmd, type(e).__name__)
        return {"ok": False, "running": False, "display": None, "socket": None,
                "error": "не удалось обратиться к Bot Desktop"}
    if cmd == "start":
        log("screen-desktop", cmd, res.get("running"), res.get("display"))
    return res


HANDOFF_STATE = os.path.join(HOME, "agent-phone", "screen-handoff.json")


def screen_lease(cmd, viewer="", extra=()):
    """«Взять экран» / «Я закончила»: аренда экрана Hermes через murf_screen_lease.py в venv агента."""
    py = os.path.join(HERMES_AGENT_DIR, "venv", "bin", "python")
    helper = os.path.join(os.path.dirname(os.path.abspath(__file__)), "murf_screen_lease.py")
    if not os.path.isfile(py) or not os.path.isdir(os.path.join(HERMES_AGENT_DIR, "tools", "bot_desktop")):
        return {"ok": True, "supported": False, "holder": "agent"}
    args = [py, helper, cmd] + ([viewer] if viewer else []) + list(extra)
    try:
        r = subprocess.run(args, cwd=HERMES_AGENT_DIR, capture_output=True, text=True, timeout=20)
        res = json.loads((r.stdout or "").strip().splitlines()[-1])
    except Exception as e:
        log("screen-lease error", type(e).__name__)
        return {"ok": False, "error": "не удалось обратиться к экрану Hermes"}
    if cmd in ("take", "release") and res.get("ok"):
        try:
            with open(HANDOFF_STATE, "w") as f:
                json.dump({"holder": res.get("holder"), "since": res.get("since"), "by": viewer[:80]}, f)
        except OSError:
            pass
        log("screen-lease", cmd, res.get("holder"))
    return res


EXPORT_DIR = os.path.join(HOME, ".cache", "agent-phone", "exports")
EXPORT_TTL = 900
ex_tokens = {}          # token -> {path, expires}  (одноразовые ссылки на архив настроек для локального режима)
_SECRET_KEY = re.compile(r"(^|_)(api_?key|access_token|refresh_token|bot_token|token|secret|client_secret|password|passwd)$", re.I)


def _strip_secrets(o):
    if isinstance(o, dict):
        return {k: ("" if isinstance(v, str) and v and _SECRET_KEY.search(str(k)) else _strip_secrets(v)) for k, v in o.items()}
    if isinstance(o, list):
        return [_strip_secrets(x) for x in o]
    return o


def gc_exports():
    now = time.time()
    for t, v in list(ex_tokens.items()):
        if v["expires"] < now:
            ex_tokens.pop(t, None)
            try: os.remove(v["path"])
            except OSError: pass


def local_export(with_secrets=False):
    """Архив настроек Hermes для локального режима MURF: config.yaml, skills, memories, SOUL.md.
    Секреты (auth.json, *_API_KEY) — только при with_secrets=True (явное согласие в приложении)."""
    import io, tarfile, yaml
    gc_exports()
    hh = os.path.join(HOME, ".hermes")
    os.makedirs(EXPORT_DIR, mode=0o700, exist_ok=True)
    for t, v in list(ex_tokens.items()):          # одна активная выгрузка
        ex_tokens.pop(t, None)
        try: os.remove(v["path"])
        except OSError: pass
    tok = secrets.token_urlsafe(32)
    path = os.path.join(EXPORT_DIR, tok[:16] + ".tgz")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    included = []
    with os.fdopen(fd, "wb") as f, tarfile.open(fileobj=f, mode="w:gz") as tar:
        def add_bytes(name, data):
            ti = tarfile.TarInfo(name); ti.size = len(data); ti.mtime = int(time.time()); ti.mode = 0o600
            tar.addfile(ti, io.BytesIO(data))
        try:
            cfg = yaml.safe_load(open(os.path.join(hh, "config.yaml"))) or {}
            if isinstance(cfg.get("mcp_servers"), dict):
                cfg["mcp_servers"].pop("agent_tools", None)
            if not with_secrets:
                cfg = _strip_secrets(cfg)
            add_bytes("config.yaml", yaml.safe_dump(cfg, allow_unicode=True, sort_keys=False).encode())
            included.append("config.yaml")
        except Exception as e:
            log("export config error", e)
        for d in ("skills", "memories"):
            src = os.path.join(hh, d)
            if os.path.isdir(src):
                tar.add(src, arcname=d, filter=lambda ti: None if "__pycache__" in ti.name else ti)
                included.append(d)
        if os.path.isfile(os.path.join(hh, "SOUL.md")):
            tar.add(os.path.join(hh, "SOUL.md"), arcname="SOUL.md"); included.append("SOUL.md")
        if with_secrets:
            if os.path.isfile(os.path.join(hh, "auth.json")):
                tar.add(os.path.join(hh, "auth.json"), arcname="secrets/auth.json"); included.append("auth.json")
            keys = []
            try:
                for l in open(os.path.join(hh, ".env")):
                    l = l.strip()
                    k = l.split("=", 1)[0]
                    if re.fullmatch(r"[A-Z0-9_]+_API_KEY", k) and k != "API_SERVER_KEY" and l.split("=", 1)[1].strip():
                        keys.append(l)
            except OSError:
                pass
            if keys:
                add_bytes("secrets/keys.env", ("\n".join(keys) + "\n").encode()); included.append(f"{len(keys)} API keys")
    ex_tokens[tok] = {"path": path, "expires": time.time() + EXPORT_TTL}
    log("local-export", "secrets" if with_secrets else "plain", os.path.getsize(path))
    return {"ok": True, "url": f"{PUBLIC_BASE}/phone/up/x/{tok}", "size": os.path.getsize(path),
            "expires_in": EXPORT_TTL, "secrets": bool(with_secrets), "included": included}


class Base(BaseHTTPRequestHandler):
    server_version = "agent-phone-relay/1"

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

    def body(self, limit=2 * 1024 * 1024):
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            raise ValueError("too large")
        return self.rfile.read(n) if n else b""

    def jbody(self):
        try:
            return json.loads(self.body() or b"{}")
        except Exception:
            return {}

    def client_ip(self):
        fwd = (self.headers.get("X-Real-IP") or self.headers.get("X-Forwarded-For") or "").split(",")[0].strip()
        return fwd or self.client_address[0]

    def pair_call(self, fn, *args, set_cookie=False):
        try:
            res = fn(*args)
        except pairing.PairError as e:
            return self.send_json(e.status, {"ok": False, "error": e.message})
        except Exception as e:
            log("pair error", type(e).__name__)
            return self.send_json(500, {"ok": False, "error": "внутренняя ошибка привязки"})
        if not set_cookie:
            return self.send_json(200, res)
        cookie, obj = res
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(b)


    def proxy_bots(self, method):
        """/phone/api/bots/* → служба MURF bots на 127.0.0.1:BOTS_PORT (включая поток SSE)."""
        import http.client
        if self.headers.get("X-Agent-App") != "1":
            return self.send_json(403, {"ok": False, "error": "нужен заголовок X-Agent-App"})
        try:
            body = self.body(limit=256 * 1024) if method == "POST" else None
        except ValueError:
            return self.send_json(413, {"ok": False, "error": "слишком большой запрос"})
        stream = "/stream" in urlparse(self.path).path
        conn = http.client.HTTPConnection("127.0.0.1", BOTS_PORT, timeout=60 if stream else 180)
        try:
            conn.request(method, self.path, body=body,
                         headers={"X-Agent-App": "1", "Content-Type": "application/json"})
            resp = conn.getresponse()
        except OSError:
            conn.close()
            return self.send_json(503, {"ok": False, "error": "служба MURF bots не запущена на сервере"})
        try:
            self.send_response(resp.status)
            for h in ("Content-Type", "Content-Length", "Cache-Control", "X-Accel-Buffering"):
                v = resp.getheader(h)
                if v:
                    self.send_header(h, v)
            self.end_headers()
            if (resp.getheader("Content-Type") or "").startswith("text/event-stream"):
                while True:
                    chunk = resp.read1(8192)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
            else:
                self.wfile.write(resp.read())
        except (OSError, http.client.HTTPException):
            pass
        finally:
            conn.close()


class PhoneHandler(Base):
    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        p = u.path
        if p == "/phone/api/bots" or p.startswith("/phone/api/bots/"):
            return self.proxy_bots("GET")
        if p == "/phone/api/poll":
            dev = (q.get("device") or ["phone"])[0][:64]
            wait = min(int((q.get("wait") or ["25"])[0]), 50)
            with lock:
                d = devices.setdefault(dev, {})
                d["last_seen"] = time.time()
                end = time.time() + wait
                while not pending and time.time() < end:
                    lock.wait(min(end - time.time(), 5))
                    devices[dev]["last_seen"] = time.time()
                if pending:
                    req = pending.pop(0)
                    inflight[req["id"]] = req
                    return self.send_json(200, {"request": req})
            self.send_response(204)
            self.end_headers()
            return
        if p.startswith("/phone/up/x/"):
            gc_exports()
            v = ex_tokens.pop(p.rsplit("/", 1)[-1], None)
            if not v or not os.path.isfile(v["path"]):
                return self.send_json(404, {"error": "ссылка недействительна или уже использована"})
            size = os.path.getsize(v["path"])
            self.send_response(200)
            self.send_header("Content-Type", "application/gzip")
            self.send_header("Content-Length", str(size))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            try:
                with open(v["path"], "rb") as f:
                    shutil.copyfileobj(f, self.wfile)
            finally:
                try: os.remove(v["path"])
                except OSError: pass
            return
        if p.startswith("/phone/api/dl/"):
            gc_tokens()
            v = dl_tokens.pop(p.rsplit("/", 1)[-1], None)
            if not v or not os.path.isfile(v["path"]):
                return self.send_json(404, {"error": "нет файла"})
            size = os.path.getsize(v["path"])
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Disposition", "attachment; filename*=UTF-8''" + quote(v["name"]))
            self.end_headers()
            with open(v["path"], "rb") as f:
                shutil.copyfileobj(f, self.wfile)
            return
        if p == "/phone/api/status":
            return self.send_json(200, {"devices": phone_online(), "pending": len(pending),
                                        "history": history[-20:], "drive": drive_status()})
        if p == "/phone/api/drive-status":
            return self.send_json(200, drive_status())
        if p == "/phone/api/screen/handoff":
            if self.headers.get("X-Agent-App") != "1":
                return self.send_json(403, {"ok": False, "error": "нужен заголовок X-Agent-App"})
            return self.send_json(200, screen_lease("status", (q.get("viewer") or [""])[0][:80]))
        if p == "/phone/api/screen/status":
            if self.headers.get("X-Agent-App") != "1":
                return self.send_json(403, {"ok": False, "error": "нужен заголовок X-Agent-App"})
            res = screen_desktop("status")
            # 503 = стек не поддержан / не запущен; 200 = ок (running или детальный статус)
            code = 200 if res.get("ok") or res.get("running") else (501 if res.get("supported") is False else 503)
            return self.send_json(code, res)
        if p == "/phone/api/pair/devices":
            return self.pair_call(pairing.list_devices)
        if p == "/phone/api/ops/agent-update":
            return self.send_json(200, agent_update_status())
        if p == "/phone/api/ops/agent-update/log":
            st = _read_json(UPD_STATE) or {}
            path = st.get("log") or ""
            if not path.startswith(BK_ROOT + "/") or not os.path.isfile(path):
                return self.send_json(404, {"error": "нет журнала"})
            with open(path, encoding="utf-8", errors="ignore") as f:
                f.seek(max(0, os.path.getsize(path) - 20000))
                return self.send_json(200, {"log": f.read()})
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        u = urlparse(self.path)
        p = u.path
        if p == "/phone/api/bots" or p.startswith("/phone/api/bots/"):
            return self.proxy_bots("POST")
        if p == "/phone/api/hello":
            j = self.jbody()
            dev = str(j.get("device") or "phone")[:64]
            with lock:
                devices[dev] = {"last_seen": time.time(), "name": str(j.get("name", ""))[:80],
                                "caps": j.get("caps", []), "termux": bool(j.get("termux")),
                                "app_version": str(j.get("app_version", ""))[:40]}
            log("hello", dev, j.get("name"), j.get("caps"))
            return self.send_json(200, {"ok": True})
        if p == "/phone/api/result":
            j = self.jbody()
            rid = str(j.get("id", ""))
            with lock:
                if rid in inflight:
                    inflight.pop(rid, None)
                    results[rid] = {k: j.get(k) for k in ("ok", "output", "error", "exit_code", "data") if k in j}
                    lock.notify_all()
                    return self.send_json(200, {"ok": True})
            return self.send_json(404, {"ok": False, "error": "unknown request"})
        if p == "/phone/api/local/export":
            if self.headers.get("X-Agent-App") != "1":
                return self.send_json(403, {"ok": False, "error": "нужен заголовок X-Agent-App"})
            j = self.jbody() or {}
            try:
                return self.send_json(200, local_export(bool(j.get("secrets"))))
            except Exception as e:
                log("local-export error", e)
                return self.send_json(500, {"ok": False, "error": str(e)[:300]})
        if p == "/phone/api/screen/type":
            # ввод текста на экран агента (режим «Управлять» в MURF); кириллица — через xdotool
            if self.headers.get("X-Agent-App") != "1":
                return self.send_json(403, {"ok": False, "error": "нужен заголовок X-Agent-App"})
            j = self.jbody()
            return self.send_json(200, screen_type(str(j.get("text", "")), bool(j.get("enter", False))))
        if p == "/phone/api/screen/start":
            # авто-старт Bot Desktop с «Экран агента» (index.html на open/disconnect)
            # authz: nginx auth_request (сессия WebUI) + X-Agent-App (анти-CSRF из браузера)
            if self.headers.get("X-Agent-App") != "1":
                log("screen-start denied: no X-Agent-App", self.client_ip())
                return self.send_json(403, {"ok": False, "error": "нужен заголовок X-Agent-App"})
            res = screen_desktop("start")
            if res.get("ok") or res.get("running"):
                return self.send_json(200, res)
            # не маскируем отказ под 200: клиент видит 501 (не поддерживается) / 503 (не поднялся)
            code = 501 if res.get("supported") is False else 503
            log("screen-start fail", code, (res.get("error") or "")[:120])
            return self.send_json(code, res)
        if p == "/phone/api/report":
            if self.headers.get("X-Agent-App") != "1":
                return self.send_json(403, {"ok": False, "error": "нужен заголовок X-Agent-App"})
            try:
                j = json.loads(self.body(limit=1024 * 1024) or b"{}")
                res = reports.save_report(j if isinstance(j, dict) else {}, self.client_ip())
            except reports.ReportError as e:
                return self.send_json(e.status, {"ok": False, "error": e.message})
            except ValueError:
                return self.send_json(413, {"ok": False, "error": "слишком большой отчёт"})
            log("report", res.get("id"))
            return self.send_json(200, res)
        if p == "/phone/api/screen/handoff":
            if self.headers.get("X-Agent-App") != "1":
                return self.send_json(403, {"ok": False, "error": "нужен заголовок X-Agent-App"})
            j = self.jbody()
            action, viewer = str(j.get("action", "")), str(j.get("viewer", ""))[:80]
            if action == "take":
                return self.send_json(200, screen_lease("take", viewer, [str(j.get("reason") or "MURF: управление с телефона")[:200]]))
            if action == "release":
                return self.send_json(200, screen_lease("release", viewer, ["--force"] if j.get("force") else []))
            return self.send_json(400, {"ok": False, "error": "action: take|release"})
        if p.startswith("/phone/api/ops/"):
            # защита от CSRF: обязательный заголовок приложения (браузер без preflight его не пошлёт)
            if self.headers.get("X-Agent-App") != "1":
                return self.send_json(403, {"ok": False, "error": "нужен заголовок X-Agent-App"})
            j = self.jbody()
            if p == "/phone/api/ops/agent-update/check":
                started = start_agent_check()
                return self.send_json(200, dict(agent_update_status(kick=False), started=started, checking=True))
            if p == "/phone/api/ops/agent-update/apply":
                dry = bool(j.get("dry_run", False))
                if not dry and j.get("confirm") != "update":
                    return self.send_json(400, {"ok": False, "error": "нужно подтверждение"})
                return self.send_json(200, start_agent_update(dry))
            return self.send_json(404, {"error": "not found"})
        if p in ("/phone/up/pair/claim", "/phone/up/pair/session"):
            j = self.jbody()
            ip = self.client_ip()
            if p.endswith("/claim"):
                log("pair claim", ip)
                return self.pair_call(pairing.claim, j.get("code"), j.get("device_id"), j.get("name"), ip)
            return self.pair_call(pairing.session, j.get("device_token"), ip, set_cookie=True)
        if p.startswith("/phone/api/pair/"):
            if self.headers.get("X-Agent-App") != "1":
                return self.send_json(403, {"ok": False, "error": "нужен заголовок X-Agent-App"})
            j = self.jbody()
            if p == "/phone/api/pair/new":
                log("pair new (app)")
                return self.pair_call(pairing.new_code, PUBLIC_BASE, str(j.get("label", "")), j.get("ttl"))
            if p == "/phone/api/pair/revoke":
                log("pair revoke", str(j.get("device_id", ""))[:64])
                return self.pair_call(pairing.revoke, j.get("device_id"))
            return self.send_json(404, {"error": "not found"})
        if p == "/phone/api/drive-token":
            j = self.jbody()
            return self.send_json(200, drive_set_token(str(j.get("token", ""))))
        if p.startswith("/phone/up/"):
            gc_tokens()
            tok = p.rsplit("/", 1)[-1]
            v = up_tokens.get(tok)
            if not v or v.get("saved"):
                return self.send_json(403, {"error": "токен недействителен"})
            n = int(self.headers.get("Content-Length") or 0)
            if n <= 0 or n > MAX_UPLOAD:
                return self.send_json(413, {"error": f"размер 1..{MAX_UPLOAD} байт"})
            name = safe_name((parse_qs(u.query).get("name") or ["upload.bin"])[0])
            dst = os.path.join(FILES_DIR, time.strftime("%Y%m%d-%H%M%S-") + name)
            left = n
            with open(dst, "wb") as f:
                while left > 0:
                    chunk = self.rfile.read(min(65536, left))
                    if not chunk:
                        break
                    f.write(chunk)
                    left -= len(chunk)
            v["saved"] = dst
            log("upload", dst, n)
            return self.send_json(200, {"ok": True, "path": dst, "size": n})
        self.send_json(404, {"error": "not found"})


class AgentHandler(Base):
    def do_GET(self):
        if urlparse(self.path).path == "/status":
            return self.send_json(200, {"devices": phone_online(), "pending": len(pending),
                                        "history": history[-20:], "drive": drive_status(), "files_dir": FILES_DIR})
        if urlparse(self.path).path == "/pair/devices":
            return self.pair_call(pairing.list_devices)
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/pair/new":   # для команды `murf pair` (только 127.0.0.1)
            j = self.jbody()
            log("pair new (cli)")
            return self.pair_call(pairing.new_code, str(j.get("public_base") or PUBLIC_BASE), str(j.get("label", "")), j.get("ttl"))
        if path == "/pair/revoke":
            return self.pair_call(pairing.revoke, self.jbody().get("device_id"))
        if urlparse(self.path).path == "/call":
            j = self.jbody()
            timeout = max(10, min(int(j.get("timeout", 120)), 600))
            res = make_call(str(j.get("tool")), j.get("args") or {}, timeout)
            return self.send_json(200, res)
        self.send_json(404, {"error": "not found"})


def serve(port, handler):
    s = ThreadingHTTPServer(("127.0.0.1", port), handler)
    s.daemon_threads = True
    s.serve_forever()


if __name__ == "__main__":
    log("start", PHONE_PORT, AGENT_PORT, FILES_DIR)
    threading.Thread(target=serve, args=(AGENT_PORT, AgentHandler), daemon=True).start()
    serve(PHONE_PORT, PhoneHandler)
