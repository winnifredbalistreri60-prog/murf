"""MURF — привязка устройств по QR (одноразовый код -> токен устройства -> сессия WebUI).

Схема (WebUI не меняется):
  1. `murf pair` (или POST /phone/api/pair/new из уже вошедшего приложения) создаёт
     одноразовый код (по умолчанию 180 с, одно использование) и печатает QR
     с полезной нагрузкой  murf://pair?v=1&u=<адрес сервера>&c=<код>.
  2. Приложение: POST /phone/up/pair/claim {code, device_id, name} -> {device_token}.
     На сервере хранится только sha256 токена.
  3. Приложение: POST /phone/up/pair/session {device_token} -> relay сам входит в WebUI
     (127.0.0.1) паролем из файла webui.secret (0600) и отдаёт cookie hermes_session
     через Set-Cookie. Пароль WebUI на телефон не попадает.
  4. Список устройств и отзыв: GET /phone/api/pair/devices, POST /phone/api/pair/revoke
     (за cookie WebUI + X-Agent-App). Отзыв удаляет токен и разлогинивает выданные сессии.

Только stdlib.
"""
import base64, hashlib, hmac, json, os, secrets, threading, time, urllib.error, urllib.request
from urllib.parse import quote

HOME = os.path.expanduser("~")
STATE_DIR = os.environ.get("MURF_PAIR_DIR", os.path.join(HOME, "agent-phone", "pairing"))
DEVICES_FILE = os.path.join(STATE_DIR, "devices.json")
SECRET_FILE = os.environ.get("MURF_WEBUI_SECRET_FILE", os.path.join(STATE_DIR, "webui.secret"))
WEBUI_URL = os.environ.get("MURF_WEBUI_URL", "http://127.0.0.1:8787").rstrip("/")
CODE_TTL_DEFAULT = int(os.environ.get("MURF_PAIR_TTL", "180"))
CODE_TTL_MIN, CODE_TTL_MAX = 60, 300
MAX_DEVICES = 50
MAX_SESSIONS_PER_DEVICE = 4
FAIL_WINDOW, FAIL_LIMIT = 300, 20

_lock = threading.RLock()
_codes = {}          # sha256(code) -> {"expires": ts, "label": str}
_fails = {}          # ip -> [timestamps]


class PairError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.message = message


def _h(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _now():
    return time.time()


def _ensure_dir():
    os.makedirs(STATE_DIR, mode=0o700, exist_ok=True)
    try:
        os.chmod(STATE_DIR, 0o700)
    except OSError:
        pass


def _load():
    try:
        with open(DEVICES_FILE, encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict) and isinstance(data.get("devices"), dict):
            return data
    except (OSError, ValueError):
        pass
    return {"version": 1, "devices": {}}


def _save(data):
    _ensure_dir()
    tmp = DEVICES_FILE + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    os.replace(tmp, DEVICES_FILE)
    os.chmod(DEVICES_FILE, 0o600)


def _gc_codes():
    now = _now()
    for k in [k for k, v in _codes.items() if v["expires"] < now]:
        _codes.pop(k, None)


def _check_rate(ip):
    now = _now()
    hits = [t for t in _fails.get(ip, []) if now - t < FAIL_WINDOW]
    _fails[ip] = hits
    if len(hits) >= FAIL_LIMIT:
        raise PairError(429, "слишком много неудачных попыток, подождите несколько минут")


def _fail(ip):
    _fails.setdefault(ip, []).append(_now())


def new_code(public_base, label="", ttl=None):
    """Одноразовый код привязки. Возвращает код, срок и строку для QR."""
    ttl = int(ttl or CODE_TTL_DEFAULT)
    ttl = max(CODE_TTL_MIN, min(ttl, CODE_TTL_MAX))
    # 20 символов base32 = 100 бит; без похожих символов не заморачиваемся — код вводится QR-ом
    code = base64.b32encode(secrets.token_bytes(15)).decode("ascii").rstrip("=")
    with _lock:
        _gc_codes()
        if len(_codes) > 20:   # не копим мусор
            oldest = sorted(_codes.items(), key=lambda kv: kv[1]["expires"])[:len(_codes) - 20]
            for k, _ in oldest:
                _codes.pop(k, None)
        _codes[_h(code)] = {"expires": _now() + ttl, "label": str(label or "")[:80]}
    base = public_base.rstrip("/")
    payload = "murf://pair?v=1&u=" + quote(base, safe="") + "&c=" + code
    return {"ok": True, "code": code, "expires_in": ttl, "server": base, "payload": payload}


def claim(code, device_id, name, ip="?"):
    code = str(code or "").strip().upper()
    device_id = "".join(ch for ch in str(device_id or "") if ch.isalnum() or ch in "-_")[:64]
    name = str(name or "").strip()[:80] or "Android"
    with _lock:
        _check_rate(ip)
        _gc_codes()
        entry = _codes.pop(_h(code), None) if code else None   # одно использование
        if not entry:
            _fail(ip)
            raise PairError(403, "код недействителен или истёк — покажите новый QR")
        if not device_id:
            device_id = secrets.token_hex(6)
        token = secrets.token_urlsafe(32)
        data = _load()
        devs = data["devices"]
        old = devs.get(device_id)
        if old:   # повторная привязка того же устройства: старые сессии закрываем
            _logout_sessions(old.get("sessions", []))
        if len(devs) >= MAX_DEVICES and device_id not in devs:
            raise PairError(409, "слишком много устройств — отзовите лишние")
        devs[device_id] = {
            "id": device_id, "name": name, "label": entry.get("label", ""),
            "token_sha256": _h(token), "created": int(_now()), "last_used": int(_now()),
            "last_ip": str(ip)[:64], "sessions": [],
        }
        _save(data)
    return {"ok": True, "device_id": device_id, "device_token": token}


def _webui_password():
    try:
        with open(SECRET_FILE, encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


def _webui_login():
    """Вход в WebUI. Возвращает полное значение Set-Cookie для hermes_session (или '' если auth выключен)."""
    password = _webui_password()
    body = json.dumps({"password": password}).encode()
    req = urllib.request.Request(WEBUI_URL + "/api/auth/login", data=body, method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            cookies = r.headers.get_all("Set-Cookie") or []
            payload = json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        if e.code == 401:
            raise PairError(502, "сервер не смог войти в WebUI: проверьте файл webui.secret (murf pair --set-webui-password)")
        if e.code == 429:
            raise PairError(429, "WebUI временно ограничил входы, попробуйте через минуту")
        raise PairError(502, f"WebUI ответил HTTP {e.code}")
    except (urllib.error.URLError, OSError) as e:
        raise PairError(503, f"WebUI недоступен: {getattr(e, 'reason', e)}")
    for c in cookies:
        if c.startswith("hermes_session="):
            return c
    if payload.get("ok"):
        return ""   # авторизация в WebUI выключена — cookie не нужна
    raise PairError(502, "WebUI не выдал сессию")


def _logout_sessions(cookie_values):
    for value in cookie_values or []:
        try:
            req = urllib.request.Request(WEBUI_URL + "/api/auth/logout", data=b"{}", method="POST",
                                         headers={"Content-Type": "application/json",
                                                  "Cookie": "hermes_session=" + value})
            urllib.request.urlopen(req, timeout=10).close()
        except Exception:
            pass


def _find_by_token(devs, token):
    th = _h(token)
    for d in devs.values():
        if hmac.compare_digest(d.get("token_sha256", ""), th):
            return d
    return None


def session(device_token, ip="?"):
    """Новая сессия WebUI для привязанного устройства. Возвращает (set_cookie, ответ)."""
    token = str(device_token or "").strip()
    with _lock:
        _check_rate(ip)
        data = _load()
        dev = _find_by_token(data["devices"], token) if token else None
        if not dev:
            _fail(ip)
            raise PairError(401, "устройство не привязано или доступ отозван")
    set_cookie = _webui_login()
    value = set_cookie.split(";", 1)[0].split("=", 1)[1] if set_cookie else ""
    with _lock:
        data = _load()
        dev = data["devices"].get(dev["id"])
        if not dev or not hmac.compare_digest(dev.get("token_sha256", ""), _h(token)):
            _logout_sessions([value] if value else [])
            raise PairError(401, "доступ отозван")
        sessions = (dev.get("sessions") or []) + ([value] if value else [])
        stale = sessions[:-MAX_SESSIONS_PER_DEVICE]
        dev["sessions"] = sessions[-MAX_SESSIONS_PER_DEVICE:]
        dev["last_used"] = int(_now())
        dev["last_ip"] = str(ip)[:64]
        _save(data)
    if stale:
        _logout_sessions(stale)
    return set_cookie, {"ok": True, "device_id": dev["id"], "name": dev.get("name", "")}


def list_devices():
    with _lock:
        devs = _load()["devices"].values()
        out = [{k: d.get(k) for k in ("id", "name", "label", "created", "last_used", "last_ip")} for d in devs]
    out.sort(key=lambda d: d.get("last_used") or 0, reverse=True)
    return {"ok": True, "devices": out, "webui_secret_configured": bool(_webui_password())}


def revoke(device_id):
    device_id = str(device_id or "")
    with _lock:
        data = _load()
        dev = data["devices"].pop(device_id, None)
        if not dev:
            raise PairError(404, "нет такого устройства")
        _save(data)
    _logout_sessions(dev.get("sessions", []))
    return {"ok": True, "revoked": device_id}


def set_webui_password(password):
    _ensure_dir()
    fd = os.open(SECRET_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(password.strip() + "\n")
    os.chmod(SECRET_FILE, 0o600)
