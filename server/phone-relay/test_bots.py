#!/usr/bin/env python3
"""Тесты службы MURF bots с поддельными CLI (ACP-агент, codex, claude). Запуск: python3 test_bots.py"""
import json, os, socket, subprocess, sys, tempfile, threading, time, unittest, urllib.request, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
TD = os.path.join(HERE, "testdata")


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class BotsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="murf-bots-test-")
        home = cls.home = os.path.join(cls.tmp, "home")
        binp = os.path.join(home, ".local", "bin")
        os.makedirs(binp)
        for name in ("codex", "claude", "grok", "hermes", "hermes-acp"):
            p = os.path.join(binp, name)
            with open(p, "w") as f:
                f.write(f'#!/bin/sh\nFAKE_CLI={name} exec {sys.executable} {TD}/fake_cli.py "$@"\n')
            os.chmod(p, 0o755)
        os.makedirs(os.path.join(home, ".grok"))
        with open(os.path.join(home, ".grok", "auth.json"), "w") as f:
            f.write('{"secret":"must-not-leak"}')
        os.makedirs(os.path.join(home, ".hermes", "profiles", "work"))
        with open(os.path.join(home, ".hermes", ".env"), "w") as f:
            f.write("X=1\n")
        cls.cli_log = os.path.join(cls.tmp, "cli.jsonl")
        cls.acp_log = os.path.join(cls.tmp, "acp.jsonl")
        cls.port = free_port()
        env = {"HOME": home, "PATH": "/usr/bin:/bin", "MURF_BOTS_PORT": str(cls.port),
               "FAKE_CLI_LOG": cls.cli_log, "FAKE_ACP_LOG": cls.acp_log, "MURF_BOTS_APPROVAL_TIMEOUT": "20"}
        cls.proc = subprocess.Popen([sys.executable, os.path.join(HERE, "murf_bots.py")], env=env,
                                    stderr=open(os.path.join(cls.tmp, "svc.log"), "w"))
        for _ in range(100):
            try:
                cls.get("/health")
                break
            except OSError:
                time.sleep(0.1)
        cls.phone, cls.agent = free_port(), free_port()
        renv = dict(os.environ, HOME=cls.home, PHONE_PORT=str(cls.phone), AGENT_PORT=str(cls.agent),
                    MURF_BOTS_PORT=str(cls.port), PUBLIC_BASE="https://murf.example")
        cls.relay = subprocess.Popen([sys.executable, os.path.join(HERE, "phone_relay.py")], env=renv,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(50):
            try:
                socket.create_connection(("127.0.0.1", cls.phone), 0.2).close()
                break
            except OSError:
                time.sleep(0.1)

    @classmethod
    def tearDownClass(cls):
        cls.relay.terminate()
        cls.proc.terminate()
        cls.proc.wait(5)

    @classmethod
    def req(cls, method, path, body=None, app=True, port=None):
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(f"http://127.0.0.1:{port or cls.port}/phone/api/bots{path}", data=data, method=method)
        r.add_header("Content-Type", "application/json")
        if app:
            r.add_header("X-Agent-App", "1")
        try:
            with urllib.request.urlopen(r, timeout=30) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    @classmethod
    def get(cls, path):
        return cls.req("GET", path)

    def wait_run(self, cid, timeout=20, on_approval=None):
        end = time.time() + timeout
        answered = set()
        while time.time() < end:
            st, d = self.get(f"/chats/{cid}")
            evs = d["events"]
            for e in evs:
                if e["type"] == "approval" and e.get("status") == "pending" and e["id"] not in answered and on_approval:
                    answered.add(e["id"])
                    on_approval(e)
            runs = [e for e in evs if e["type"] == "run"]
            if runs and runs[-1]["status"] != "running":
                return evs
            time.sleep(0.1)
        self.fail("run did not finish")

    def make_bot(self, **kw):
        st, d = self.req("POST", "/", kw)
        if st == 409 and d.get("confirm_required"):
            st, d = self.req("POST", "/", dict(kw, confirm_cli=True))
        self.assertEqual(st, 200, d)
        return d["bot"]

    def test_01_header_and_clis(self):
        st, _ = self.req("GET", "/", app=False)
        self.assertEqual(st, 403)
        st, d = self.get("/clis")
        self.assertEqual(st, 200)
        by = {c["id"]: c for c in d["clis"]}
        self.assertTrue(by["codex"]["installed"] and by["codex"]["logged_in"])
        self.assertEqual(by["codex"]["login_method"], "ChatGPT")
        self.assertTrue(by["claude"]["logged_in"])
        self.assertTrue(by["grok"]["logged_in"])
        self.assertFalse(by["gemini"]["installed"])
        self.assertIn("work", d["profiles"])
        blob = json.dumps(d)
        self.assertNotIn("must-not-leak", blob)
        self.assertNotIn("x@example.com", blob)

    def test_02_cli_needs_confirmation(self):
        st, d = self.req("POST", "/", {"name": "Кодер", "backend": "codex"})
        self.assertEqual(st, 409)
        self.assertTrue(d["confirm_required"])
        self.assertIn("Codex", d["notice"])
        st, d = self.req("POST", "/", {"name": "Кодер", "backend": "codex", "confirm_cli": True})
        self.assertEqual(st, 200)
        bid = d["bot"]["id"]
        # смена режима на «как в конфиге CLI» требует нового подтверждения
        st, d = self.req("POST", f"/{bid}", {"access": "cli"})
        self.assertEqual(st, 409)
        self.assertIn("полный доступ", d["notice"])
        st, d = self.req("POST", "/", {"name": "Вне дома", "backend": "hermes", "workdir": "/etc"})
        self.assertEqual(st, 400)
        self.req("POST", f"/{bid}/delete", {})

    def test_03_hermes_acp_steps_subtasks_approval(self):
        bot = self.make_bot(name="Хермес", emoji="🦉", backend="hermes", profile="work", instructions="Отвечай кратко.")
        st, d = self.req("POST", f"/{bot['id']}/chats", {"text": "привет"})
        self.assertEqual(st, 200, d)
        cid = d["chat"]["id"]
        evs = self.wait_run(cid)
        text = "".join(e["text"] for e in evs if e["type"] == "text" and not e.get("parent"))
        self.assertEqual(text, "Привет, мир")
        steps = {e["id"]: e for e in evs if e["type"] == "step"}
        self.assertEqual(steps["t1"]["status"], "done")
        subs = [e for e in evs if e["type"] == "subtask"]
        self.assertEqual(subs[-1]["id"], "d1")
        self.assertEqual(subs[-1]["status"], "done")
        self.assertTrue(any(e["type"] == "text" and e.get("parent") == "d1" for e in evs))
        self.assertTrue(any(e["type"] == "plan" for e in evs))
        self.assertEqual([e for e in evs if e["type"] == "run"][-1]["status"], "done")
        acp = [json.loads(l) for l in open(self.acp_log)]
        first = [a for a in acp if "prompt" in a][-1]
        self.assertIn("Отвечай кратко.", first["prompt"])
        self.assertTrue(first["hermes_home"].endswith("/.hermes/profiles/work"))
        # разрешение: запрос показывается, ответ «Allow once» уходит агенту
        self.req("POST", f"/chats/{cid}/send", {"text": "PERM"})
        evs = self.wait_run(cid, on_approval=lambda e: self.req(
            "POST", f"/chats/{cid}/approve", {"id": e["id"], "option": "allow-once"}))
        appr = [e for e in evs if e["type"] == "approval"]
        self.assertEqual(appr[0]["options"][0]["id"], "allow-once")
        self.assertEqual(appr[-1]["status"], "resolved")
        self.assertIn("Разрешено.", "".join(e.get("text", "") for e in evs if e["type"] == "text"))
        # отказ
        self.req("POST", f"/chats/{cid}/send", {"text": "PERM"})
        evs = self.wait_run(cid, on_approval=lambda e: self.req(
            "POST", f"/chats/{cid}/approve", {"id": e["id"], "option": "reject-once"}))
        self.assertTrue("".join(e.get("text", "") for e in evs if e["type"] == "text").endswith("Отклонено."))
        # журнал запусков: есть, без текста запроса
        runs = [json.loads(l) for l in open(os.path.join(self.home, "agent-phone", "bots", "runs.jsonl"))]
        mine = [r for r in runs if r["chat"] == cid]
        self.assertEqual(len(mine), 3)
        self.assertNotIn("PERM", json.dumps(mine))
        self.assertEqual(mine[1]["approvals"][0]["answer"], "allow-once")
        st, d = self.get("/runs")
        self.assertTrue(any(r["chat"] == cid for r in d["runs"]))

    def test_04_codex_safe_flags_and_resume(self):
        bot = self.make_bot(name="Codex-бот", backend="codex")
        st, d = self.req("POST", f"/{bot['id']}/chats", {"text": "сделай ls"})
        cid = d["chat"]["id"]
        evs = self.wait_run(cid)
        self.assertIn("codex ok", "".join(e.get("text", "") for e in evs if e["type"] == "text"))
        self.assertTrue(any(e["type"] == "step" and e["title"] == "$ ls" and e["status"] == "done" for e in evs))
        self.req("POST", f"/chats/{cid}/send", {"text": "ещё"})
        self.wait_run(cid)
        calls = [json.loads(l) for l in open(self.cli_log) if '"codex"' in l]
        a1, a2 = calls[-2]["argv"], calls[-1]["argv"]
        self.assertIn('sandbox_mode="workspace-write"', a1)
        self.assertEqual(a2[:3], ["exec", "resume", "0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"])
        for a in a1 + a2:
            self.assertNotIn("danger", a)
            self.assertNotIn("bypass", a)
        self.assertTrue(calls[-1]["cwd"].endswith("murf-bots/codex-бот"))

    def test_05_claude_subtask_parent_steps(self):
        bot = self.make_bot(name="Клод", backend="claude")
        st, d = self.req("POST", f"/{bot['id']}/chats", {"text": "исследуй"})
        cid = d["chat"]["id"]
        evs = self.wait_run(cid)
        subs = [e for e in evs if e["type"] == "subtask"]
        self.assertEqual(subs[0]["title"], "исследовать код")
        self.assertEqual(subs[-1]["status"], "done")
        child = [e for e in evs if e["type"] == "step" and e.get("parent") == "tu1"]
        self.assertTrue(child)
        self.assertTrue(any(e["type"] == "notice" and "Bash" in e["text"] for e in evs))
        argv = [json.loads(l) for l in open(self.cli_log) if '"claude"' in l][-1]["argv"]
        self.assertIn("--permission-mode", argv)
        self.assertEqual(argv[argv.index("--permission-mode") + 1], "default")

    def test_06_handoff_between_bots(self):
        self.make_bot(name="Coder", backend="hermes")
        boss = self.make_bot(name="Boss", backend="hermes")
        st, d = self.req("POST", f"/{boss['id']}/chats", {"text": "HANDOFF please"})
        cid = d["chat"]["id"]
        evs = self.wait_run(cid)
        for _ in range(50):
            evs = self.get(f"/chats/{cid}")[1]["events"]
            if any(e["type"] == "handoff" for e in evs):
                break
            time.sleep(0.1)
        h = [e for e in evs if e["type"] == "handoff"][0]
        self.assertEqual(h["to_name"], "Coder")
        evs2 = self.wait_run(h["to_chat"])
        u = [e for e in evs2 if e["type"] == "user"][0]
        self.assertEqual(u["text"], "напиши тест")
        self.assertEqual(u["from_bot"], "Boss")

    def test_09_bot_proposes_bot(self):
        boss = self.make_bot(name="Lead", backend="hermes", access="safe")
        st, d = self.req("POST", f"/{boss['id']}/chats", {"text": "PROPOSE_MARK"})
        cid = d["chat"]["id"]
        self.wait_run(cid)
        for _ in range(50):
            ps = self.get("/proposals")[1]["proposals"]
            if ps:
                break
            time.sleep(0.1)
        pr = [p for p in ps if p["name"] == "Helper"][0]
        self.assertEqual(pr["status"], "pending")
        self.assertEqual(pr["access"], "safe")  # не больше, чем у родителя (просил cli)
        self.assertEqual(pr["emoji"], "k3-3")
        self.assertFalse(any(b["name"] == "Helper" for b in self.get("/")[1]["bots"]))  # без согласия не создан
        # токен: без него / с поддельным — 403
        r = urllib.request.Request(f"http://127.0.0.1:{self.port}/propose", data=b"{}", method="POST")
        r.add_header("X-Murf-Bot-Token", "0" * 40 + "." + cid)
        with self.assertRaises(urllib.error.HTTPError) as cm:
            urllib.request.urlopen(r, timeout=5)
        self.assertEqual(cm.exception.code, 403)
        st, d = self.req("POST", f"/proposals/{pr['id']}/approve", {})
        self.assertEqual(st, 200, d)
        child = d["bot"]
        self.assertEqual((child["created_by"], child["depth"], child["access"]), (boss["id"], 1, "safe"))
        st, _ = self.req("POST", f"/proposals/{pr['id']}/reject", {})
        self.assertEqual(st, 409)
        # ребёнок без разрешения не может предлагать дальше
        st, d = self.req("POST", f"/{child['id']}/chats", {"text": "PROPOSE_MARK"})
        evs = self.wait_run(d["chat"]["id"])
        time.sleep(0.5)
        evs = self.get(f"/chats/{d['chat']['id']}")[1]["events"]
        self.assertTrue(any("нельзя создавать" in (e.get("text") or "") for e in evs if e["type"] == "notice"))
        # HTTP-путь с токеном из окружения
        st, d = self.req("POST", f"/{boss['id']}/chats", {"text": "PROPOSE_HTTP"})
        self.wait_run(d["chat"]["id"])
        ps = self.get("/proposals")[1]["proposals"]
        p2 = [p for p in ps if p["name"] == "Viahttp"][0]
        self.assertEqual(p2["via"], "http")
        st, d = self.req("POST", f"/proposals/{p2['id']}/reject", {})
        self.assertEqual(d["proposal"]["status"], "rejected")
        self.assertFalse(any(b["name"] == "Viahttp" for b in self.get("/")[1]["bots"]))
        log = open(os.path.join(self.home, "agent-phone", "bots", "runs.jsonl")).read()
        self.assertIn("bot_proposal_approved", log)
        self.assertIn("bot_proposal_rejected", log)

    def test_07_stop_and_stream(self):
        bot = self.make_bot(name="Медленный", backend="grok")
        st, d = self.req("POST", f"/{bot['id']}/chats", {"text": "SLOW"})
        cid = d["chat"]["id"]
        got = []

        def reader():
            r = urllib.request.Request(f"http://127.0.0.1:{self.port}/phone/api/bots/chats/{cid}/stream?after=0")
            r.add_header("X-Agent-App", "1")
            with urllib.request.urlopen(r, timeout=30) as resp:
                for line in resp:
                    if line.startswith(b"data: "):
                        e = json.loads(line[6:])
                        got.append(e)
                        if e["type"] == "run" and e["status"] != "running":
                            return
        t = threading.Thread(target=reader)
        t.start()
        time.sleep(1.0)
        st, d = self.req("POST", f"/chats/{cid}/stop", {})
        self.assertEqual(st, 200)
        t.join(20)
        self.assertEqual(got[-1]["status"], "stopped")
        self.assertEqual(got[0]["type"], "user")
        seqs = [e["seq"] for e in got]
        self.assertEqual(seqs, sorted(seqs))

    def test_08_via_relay_proxy(self):
        st, _ = self.req("GET", "/", app=False, port=self.phone)
        self.assertEqual(st, 403)
        st, d = self.req("POST", "/", {"name": "Через relay", "backend": "hermes"}, port=self.phone)
        self.assertEqual(st, 200, d)
        st, d = self.req("POST", f"/{d['bot']['id']}/chats", {"text": "привет"}, port=self.phone)
        cid = d["chat"]["id"]
        self.wait_run(cid)
        r = urllib.request.Request(f"http://127.0.0.1:{self.phone}/phone/api/bots/chats/{cid}/stream?after=0")
        r.add_header("X-Agent-App", "1")
        types = []
        with urllib.request.urlopen(r, timeout=30) as resp:
            self.assertTrue(resp.headers["Content-Type"].startswith("text/event-stream"))
            for line in resp:
                if line.startswith(b"data: "):
                    e = json.loads(line[6:])
                    types.append(e["type"])
                    if e["type"] == "run" and e["status"] == "done":
                        break
        self.assertIn("subtask", types)
        self.assertEqual(types[0], "user")


if __name__ == "__main__":
    unittest.main(verbosity=2)
