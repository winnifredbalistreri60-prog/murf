"""python3 test_murf_dsh.py — проверки murf_dsh без dsh: патч без ключей, мост телефона, stale install."""
import json, os, sys, tempfile, threading, time
os.environ["HOME"] = tempfile.mkdtemp()
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "inner"))
import murf_dsh as m

provs, env = m._clean_providers([{"id": "deepseek", "api": "openai-completions", "baseURL": "https://api.deepseek.com/v1", "key": "sk-SECRET", "models": ["deepseek-chat"]},
                                 {"id": "bad id", "api": "x"}, {"id": "loc", "api": "openai-completions", "baseURL": "http://127.0.0.1:1/v1"}])
assert [p["id"] for p in provs] == ["deepseek", "loc"], provs
assert env == {"MURF_KEY_DEEPSEEK": "sk-SECRET", "MURF_KEY_LOC": "none"}
patch = json.dumps(m.build_patch(provs, "deepseek", "deepseek-chat"))
assert "SECRET" not in patch and "MURF_KEY_DEEPSEEK" in patch and "session-telemetry-otel" in patch and "murf_phone" in patch
tok = m.phone_token(); assert oct(os.stat(m.TOKEN_FILE).st_mode & 0o777) == "0o600"
assert m.phone_call({"tool": "battery"}, "wrong")[0] == 403
m.phone_hello({"device": "t"})
def app():
    it = m.phone_poll(5); m.phone_result({"id": it["id"], "ok": True, "output": "77%"})
threading.Thread(target=app).start()
code, r = m.phone_call({"tool": "battery", "timeout": 20}, tok)
assert code == 200 and r["output"] == "77%", r

# --- stale install progress (залипание на npm 30%) ---
os.makedirs(m.MURF, exist_ok=True)
m._write_progress({"state": "running", "pct": 30, "msg": "npm install", "ts": int(time.time()) - 60})
# нет PID → после grace reconcile → error
st = m.status()
assert st["progress"]["state"] == "error", st
assert "прервана" in st["progress"]["msg"], st
assert st.get("install_alive") is False

# живой PID → running остаётся
m._write_progress({"state": "running", "pct": 30, "msg": "npm install", "ts": int(time.time())})
with open(m.INSTALL_PID_FILE, "w") as f:
    f.write(str(os.getpid()))
st = m.status()
assert st["progress"]["state"] == "running", st
assert st["install_alive"] is True
# install_async не стартует второй раз
# подменим Popen чтобы не запускать bash
calls = []
real_popen = m.subprocess.Popen
class FakeProc:
    def __init__(self, *a, **k):
        calls.append((a, k))
m.subprocess.Popen = FakeProc
try:
    r = m.install_async()
    assert r.get("already") is True, r
    assert calls == [], calls
    # мёртвый PID → можно перезапустить
    with open(m.INSTALL_PID_FILE, "w") as f:
        f.write("1")  # PID 1 обычно не принадлежит нам; kill(1,0) may succeed as same user on some systems
    # используем заведомо мёртвый PID
    dead = 2147483646
    with open(m.INSTALL_PID_FILE, "w") as f:
        f.write(str(dead))
    m._write_progress({"state": "running", "pct": 30, "msg": "npm", "ts": int(time.time()) - 60})
    # status reconcile marks error first
    st = m.status()
    assert st["progress"]["state"] == "error", st
    r = m.install_async()
    assert r.get("ok") is True and not r.get("already"), r
    assert len(calls) == 1, calls
    # после install_async прогресс снова running (стартовый)
    prog = json.load(open(m.PROGRESS_FILE))
    assert prog["state"] == "running" and prog["pct"] == 1, prog
finally:
    m.subprocess.Popen = real_popen
    try: os.unlink(m.INSTALL_PID_FILE)
    except OSError: pass

# свежий running без PID в grace window (<12s) не помечается error
m._write_progress({"state": "running", "pct": 1, "msg": "start", "ts": int(time.time())})
st = m.status()
assert st["progress"]["state"] == "running", st

print("ok")
