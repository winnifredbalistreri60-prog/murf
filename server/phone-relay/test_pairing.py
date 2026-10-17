"""Тест QR-привязки: поднимает фейковый WebUI и relay на случайных портах.
Запуск: python3 test_pairing.py"""
import json, os, socket, subprocess, sys, tempfile, threading, time, unittest, urllib.error, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
PASSWORD = "test-webui-pass"
SESSIONS = set()
LOGGED_OUT = []


class FakeWebUI(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(n) or b"{}")
        if self.path == "/api/auth/login":
            if body.get("password") != PASSWORD:
                self.send_response(401); self.end_headers(); return
            sid = "s%d" % (len(SESSIONS) + len(LOGGED_OUT) + 1)
            SESSIONS.add(sid)
            self.send_response(200)
            self.send_header("Set-Cookie", f"hermes_session={sid}; HttpOnly; Path=/; SameSite=Lax; Max-Age=86400")
            self.send_header("Content-Type", "application/json"); self.end_headers()
            self.wfile.write(b'{"ok": true}')
            return
        if self.path == "/api/auth/logout":
            sid = (self.headers.get("Cookie") or "").split("=", 1)[-1]
            SESSIONS.discard(sid); LOGGED_OUT.append(sid)
            self.send_response(200); self.end_headers(); self.wfile.write(b"{}")


def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close(); return p


def req(port, path, body=None, headers=None, method=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=data, method=method or ("POST" if data else "GET"),
                               headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            return resp.status, json.loads(resp.read() or b"{}"), resp.headers.get_all("Set-Cookie") or []
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}"), []


class PairingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.webui_port = free_port()
        cls.webui = ThreadingHTTPServer(("127.0.0.1", cls.webui_port), FakeWebUI)
        threading.Thread(target=cls.webui.serve_forever, daemon=True).start()
        cls.phone, cls.agent = free_port(), free_port()
        env = dict(os.environ, HOME=cls.tmp, PHONE_PORT=str(cls.phone), AGENT_PORT=str(cls.agent),
                   PUBLIC_BASE="https://murf.example", MURF_WEBUI_URL=f"http://127.0.0.1:{cls.webui_port}")
        cls.env = env
        p = subprocess.run([sys.executable, os.path.join(HERE, "murf"), "set-webui-password"],
                           input=PASSWORD + "\n", text=True, env=env, capture_output=True)
        assert p.returncode == 0, p.stderr
        cls.relay = subprocess.Popen([sys.executable, os.path.join(HERE, "phone_relay.py")], env=env,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(50):
            try:
                socket.create_connection(("127.0.0.1", cls.agent), 0.2).close(); break
            except OSError:
                time.sleep(0.1)

    @classmethod
    def tearDownClass(cls):
        cls.relay.terminate(); cls.webui.shutdown()

    def cli(self, *args):
        return subprocess.run([sys.executable, os.path.join(HERE, "murf"), *args], env=self.env,
                              capture_output=True, text=True)

    def test_full_flow(self):
        out = self.cli("pair", "--json", "--ttl", "120")
        self.assertEqual(out.returncode, 0, out.stderr)
        code = json.loads(out.stdout)
        self.assertTrue(code["payload"].startswith("murf://pair?v=1&u=https%3A%2F%2Fmurf.example&c="))
        st, res, _ = req(self.phone, "/phone/up/pair/claim", {"code": code["code"], "device_id": "dev1", "name": "Pixel"})
        self.assertEqual(st, 200, res)
        token = res["device_token"]
        # одноразовость
        st, res, _ = req(self.phone, "/phone/up/pair/claim", {"code": code["code"], "device_id": "dev2"})
        self.assertEqual(st, 403)
        # сессия
        st, res, cookies = req(self.phone, "/phone/up/pair/session", {"device_token": token})
        self.assertEqual(st, 200, res)
        self.assertTrue(cookies and cookies[0].startswith("hermes_session="))
        sid = cookies[0].split(";")[0].split("=")[1]
        self.assertIn(sid, SESSIONS)
        # хранится только хэш токена, файл 0600
        path = os.path.join(self.tmp, "agent-phone", "pairing", "devices.json")
        self.assertNotIn(token, open(path).read())
        self.assertEqual(os.stat(path).st_mode & 0o777, 0o600)
        # неверный токен
        st, _, _ = req(self.phone, "/phone/up/pair/session", {"device_token": "nope"})
        self.assertEqual(st, 401)
        # список и отзыв через /phone/api (без X-Agent-App нельзя)
        st, res, _ = req(self.phone, "/phone/api/pair/devices")
        self.assertEqual([d["id"] for d in res["devices"]], ["dev1"])
        self.assertNotIn("token_sha256", json.dumps(res))
        st, _, _ = req(self.phone, "/phone/api/pair/revoke", {"device_id": "dev1"})
        self.assertEqual(st, 403)
        st, res, _ = req(self.phone, "/phone/api/pair/revoke", {"device_id": "dev1"}, {"X-Agent-App": "1"})
        self.assertEqual(st, 200, res)
        self.assertIn(sid, LOGGED_OUT)
        st, _, _ = req(self.phone, "/phone/up/pair/session", {"device_token": token})
        self.assertEqual(st, 401)

    def test_new_code_from_app_requires_header(self):
        st, _, _ = req(self.phone, "/phone/api/pair/new", {})
        self.assertEqual(st, 403)
        st, res, _ = req(self.phone, "/phone/api/pair/new", {"ttl": 9999}, {"X-Agent-App": "1"})
        self.assertEqual(st, 200)
        self.assertEqual(res["expires_in"], 300)

    def test_expired_code(self):
        import importlib
        sys.path.insert(0, HERE)
        mp = importlib.import_module("murf_pairing")
        c = mp.new_code("https://x", ttl=60)
        mp._codes[mp._h(c["code"])]["expires"] = time.time() - 1
        with self.assertRaises(mp.PairError):
            mp.claim(c["code"], "d", "n", ip="t-exp")

    def test_upload_route_still_works_for_unknown_token(self):
        st, _, _ = req(self.phone, "/phone/up/abcdef", {"x": 1})
        self.assertEqual(st, 403)


if __name__ == "__main__":
    unittest.main(verbosity=2)
