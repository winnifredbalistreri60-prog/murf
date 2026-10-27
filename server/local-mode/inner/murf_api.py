#!/usr/bin/env python3
"""MURF local mode: служебный API на 127.0.0.1:18083 внутри proot.
GET  /murf/health          — состояние служб (без секретов), открыт через nginx без авторизации
POST /phone/api/screen/type — ввод текста в экран бота (через nginx только с cookie WebUI)
"""
import json, os, socket, subprocess, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs
import murf_dsh

HOME = os.path.expanduser("~")
HERMES = os.path.join(HOME, ".hermes")
BD = os.path.join(HERMES, "bot-desktop")
MURF = os.path.join(HOME, ".murf")
VERSION = "murf-local/1.3.1"


def port_open(port, host="127.0.0.1"):
    s = socket.socket(); s.settimeout(0.5)
    try:
        s.connect((host, port)); return True
    except OSError:
        return False
    finally:
        s.close()


def rfb_banner(path):
    """Отвечает ли VNC на UNIX-сокете (первые байты «RFB 003.00x»)."""
    s = socket.socket(socket.AF_UNIX); s.settimeout(2)
    try:
        s.connect(path); return s.recv(12).startswith(b"RFB ")
    except OSError:
        return False
    finally:
        s.close()


def novnc_ok():
    a = os.path.join(HOME, "agent-screen")
    return all(os.path.isfile(os.path.join(a, f)) for f in ("index.html", "core/rfb.js", "core/crypto/des.js", "vendor/pako/lib/zlib/inflate.js"))


def xvnc_running():
    try:
        r = subprocess.run(["pgrep", "-x", "Xvnc|Xtigervnc"], capture_output=True, timeout=3)
        return r.returncode == 0
    except Exception:
        return False


def screen_details():
    """Подробности для диагностики экрана (вызывается по ?full=1 — подключается к VNC)."""
    return {"novnc": novnc_ok(), "xvnc": xvnc_running(),
            "rfb_sock": rfb_banner(os.path.join(BD, "rfb.sock")),
            "view_sock": rfb_banner(os.path.join(HOME, ".cache", "agent-screen", "view.sock"))}


def provider_logged_in():
    """Есть ли сохранённая авторизация xai-oauth (смотрим только наличие ключа, не значения)."""
    try:
        d = json.load(open(os.path.join(HERMES, "auth.json")))
    except Exception:
        return False
    blob = json.dumps(list(_keys(d)))
    return "xai" in blob


def _keys(o, depth=0):
    if depth > 4: return
    if isinstance(o, dict):
        for k, v in o.items():
            yield str(k)
            if isinstance(v, (dict, list)): yield from _keys(v, depth + 1)
    elif isinstance(o, list):
        for v in o: yield from _keys(v, depth + 1)


def proxy_set():
    try:
        return any(l.startswith(("HTTPS_PROXY=", "ALL_PROXY=")) and l.split("=", 1)[1].strip()
                   for l in open(os.path.join(HERMES, ".env")))
    except OSError:
        return False


def health():
    disp = None
    try:
        disp = open(os.path.join(BD, "display")).read().strip()
    except OSError:
        pass
    try:
        commit = open(os.path.join(MURF, "hermes-commit")).read().strip()[:12]
    except OSError:
        commit = ""
    return {
        "ok": True, "version": VERSION, "hermes_commit": commit, "ts": int(time.time()),
        "webui": port_open(8787), "api": port_open(8642), "screen_view": port_open(18081),
        "screen_control": port_open(18082), "desktop": bool(disp) and os.path.exists(os.path.join(BD, "rfb.sock")),
        "provider_logged_in": provider_logged_in(), "proxy": proxy_set(),
        "dsh": {k: v for k, v in murf_dsh.status().items() if k in ("installed", "version", "phone_bridge")},
    }


def screen_type(text, enter=False):
    text = (text or "")[:4000]
    try:
        disp = open(os.path.join(BD, "display")).read().strip()
    except OSError:
        return {"ok": False, "error": "экран агента не запущен"}
    env = dict(os.environ, DISPLAY=":" + disp.lstrip(":"), XAUTHORITY=os.path.join(BD, "Xauthority"))
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
    return {"ok": True, "typed": len(text)}


class H(BaseHTTPRequestHandler):
    server_version = "murf-local/1"
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def send_json(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(b)

    def read_json(self, limit=1024 * 1024):
        n = min(int(self.headers.get("Content-Length") or 0), limit)
        try:
            return json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return None

    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query)
        if u.path == "/phone/api/poll":
            item = murf_dsh.phone_poll(int((q.get("wait") or ["25"])[0] or 25))
            if item is None:
                self.send_response(204); self.end_headers(); return
            return self.send_json(200, {"request": item})
        if u.path == "/phone/api/dsh/status":
            return self.send_json(200, murf_dsh.status())
        if u.path == "/phone/api/dsh/sessions":
            return self.send_json(200, murf_dsh.sessions())
        if u.path == "/phone/api/dsh/session":
            ev = murf_dsh.session_log((q.get("id") or [""])[0])
            return self.send_json(200 if ev is not None else 404, {"events": ev or []})
        if self.path.split("?")[0] == "/murf/health":
            h = health()
            if "full=1" in self.path:
                h["screen"] = screen_details()
            else:
                h["novnc"] = novnc_ok()
            return self.send_json(200, h)
        self.send_json(404, {"error": "not found"})

    def do_POST(self):
        u = urlparse(self.path).path
        if u == "/murf/phone/call":
            j = self.read_json()
            if j is None: return self.send_json(400, {"ok": False, "error": "bad json"})
            code, r = murf_dsh.phone_call(j, self.headers.get("X-Murf-Phone-Token", ""))
            return self.send_json(code, r)
        if u.startswith("/phone/api/") and u not in ("/phone/api/screen/type", "/phone/api/screen/start"):
            if self.headers.get("X-Agent-App") != "1" and u != "/phone/api/result" and u != "/phone/api/hello":
                return self.send_json(403, {"ok": False, "error": "нужен заголовок X-Agent-App"})
            j = self.read_json(4 * 1024 * 1024)
            if j is None: return self.send_json(400, {"ok": False, "error": "bad json"})
            if u == "/phone/api/hello": return self.send_json(200, murf_dsh.phone_hello(j))
            if u == "/phone/api/result": return self.send_json(200 if murf_dsh.phone_result(j) else 404, {"ok": True})
            if u == "/phone/api/dsh/install": return self.send_json(200, murf_dsh.install_async())
            if u == "/phone/api/dsh/stop": return self.send_json(200, murf_dsh.stop(j.get("run_id")))
            if u == "/phone/api/dsh/run":
                self.send_response(200)
                self.send_header("Content-Type", "application/x-ndjson; charset=utf-8")
                self.send_header("Cache-Control", "no-store"); self.send_header("X-Accel-Buffering", "no")
                self.send_header("Transfer-Encoding", "chunked"); self.end_headers()
                def write(o):
                    b = (json.dumps(o, ensure_ascii=False) + "\n").encode()
                    self.wfile.write(b"%x\r\n%s\r\n" % (len(b), b)); self.wfile.flush()
                murf_dsh.run(j, write)
                try:
                    self.wfile.write(b"0\r\n\r\n"); self.wfile.flush()
                except OSError:
                    pass
                return
            return self.send_json(404, {"error": "not found"})
        if self.path.split("?")[0] == "/phone/api/screen/type":
            if self.headers.get("X-Agent-App") != "1":
                return self.send_json(403, {"ok": False, "error": "нужен заголовок X-Agent-App"})
            n = min(int(self.headers.get("Content-Length") or 0), 64 * 1024)
            try:
                j = json.loads(self.rfile.read(n) or b"{}")
            except ValueError:
                return self.send_json(400, {"ok": False, "error": "bad json"})
            r = screen_type(j.get("text", ""), bool(j.get("enter")))
            return self.send_json(200 if r.get("ok") else 409, r)
        if self.path.split("?")[0] == "/phone/api/screen/start":
            if self.headers.get("X-Agent-App") != "1":
                return self.send_json(403, {"ok": False, "error": "нужен заголовок X-Agent-App"})
            try:
                # вывод — в файл: процессы рабочего стола наследуют дескрипторы и держали бы pipe открытым
                with open(os.path.join(MURF, "logs", "desktop.log"), "a") as lf:
                    r = subprocess.run(["bash", "/opt/murf/desktop.sh", "start"], stdin=subprocess.DEVNULL, stdout=lf,
                                       stderr=subprocess.STDOUT, timeout=90, start_new_session=True)
                return self.send_json(200 if r.returncode == 0 else 500, {"ok": r.returncode == 0, "desktop": health()["desktop"]})
            except Exception as e:
                return self.send_json(500, {"ok": False, "error": str(e)[:200]})
        self.send_json(404, {"error": "not found"})


if __name__ == "__main__":
    murf_dsh.phone_token()
    ThreadingHTTPServer(("127.0.0.1", int(os.environ.get("MURF_API_PORT", "18083"))), H).serve_forever()
