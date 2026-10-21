"""python3 test_murf_dsh.py — проверки murf_dsh без dsh: патч без ключей, мост телефона."""
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
print("ok")
