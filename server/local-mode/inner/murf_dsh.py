"""MURF local mode: DeepSeek Harness (dsh headless --json) + мост инструментов телефона.
Подключается из murf_api.py. Ключи провайдеров приходят от приложения в теле запроса /phone/api/dsh/run
и передаются dsh только через переменные окружения процесса (apiKeyEnv) — на диск не пишутся и не логируются.
DeepSeek Harness — MIT, (c) 2026 DeepSeek, https://github.com/deepseek-ai/deepseek-harness ."""
import json, os, re, secrets, signal, subprocess, threading, time, uuid

HOME = os.path.expanduser("~")
MURF = os.path.join(HOME, ".murf")
DSH_DIR = os.path.join(MURF, "dsh")
DSH_HOME = os.path.join(DSH_DIR, "home")
RUNS = os.path.join(DSH_DIR, "runs")
DSH_BIN = os.environ.get("MURF_DSH_BIN", "/opt/dsh/bin/dsh")
NODE_BIN = os.environ.get("MURF_NODE_DIR", "/opt/node22/bin")
MCP = os.environ.get("MURF_PHONE_MCP", "/opt/murf/murf_phone_mcp.py")
TOKEN_FILE = os.path.join(MURF, "phone.token")
SID_RE = re.compile(r"^session-[0-9a-f-]{36}$")
PID_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,31}$")
APIS = {"openai-completions", "anthropic-messages", "openai-responses"}

# ---------------- мост инструментов телефона ----------------
_lock = threading.Condition()
_queue = []            # запросы, ещё не забранные приложением
_waiting = {}          # id -> {"event": Event, "result": dict}
_device = {"hello": None, "last_poll": 0}


def phone_token():
    os.makedirs(MURF, exist_ok=True)
    try:
        t = open(TOKEN_FILE).read().strip()
        if t: return t
    except OSError:
        pass
    t = secrets.token_urlsafe(32)
    fd = os.open(TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f: f.write(t)
    return t


def phone_call(req, token):
    """Вызов инструмента от MCP-сервера: ставит в очередь и ждёт ответа приложения."""
    if not token or not secrets.compare_digest(token, phone_token()):
        return 403, {"ok": False, "error": "bad token"}
    if time.time() - _device["last_poll"] > 90:
        return 200, {"ok": False, "error": "Приложение MURF не подключено к локальной среде (откройте MURF, «Телефон» → включить мост)"}
    tool = str(req.get("tool", ""))[:40]
    timeout = max(15, min(int(req.get("timeout") or 180), 600))
    rid = uuid.uuid4().hex[:16]
    ev = threading.Event()
    item = {"id": rid, "tool": tool, "args": req.get("args") if isinstance(req.get("args"), dict) else {},
            "timeout": timeout, "source": str(req.get("source", "dsh"))[:40], "ts": int(time.time())}
    with _lock:
        _waiting[rid] = {"event": ev, "result": None}
        _queue.append(item); _lock.notify_all()
    ev.wait(timeout)
    with _lock:
        w = _waiting.pop(rid, None)
        if item in _queue: _queue.remove(item)
    r = (w or {}).get("result")
    return 200, r if r else {"ok": False, "error": "Нет ответа от телефона вовремя"}


def phone_hello(j):
    _device["hello"] = {k: j.get(k) for k in ("device", "name", "app_version", "termux", "caps")}
    _device["last_poll"] = time.time()
    return {"ok": True}


def phone_poll(wait):
    _device["last_poll"] = time.time()
    end = time.time() + max(1, min(wait, 30))
    with _lock:
        while not _queue and time.time() < end:
            _lock.wait(end - time.time())
        if _queue:
            _device["last_poll"] = time.time()
            return _queue.pop(0)
    _device["last_poll"] = time.time()
    return None


def phone_result(j):
    with _lock:
        w = _waiting.get(str(j.get("id", "")))
        if not w: return False
        w["result"] = {k: v for k, v in j.items() if k != "id"}
        w["event"].set()
    return True


# ---------------- DeepSeek Harness ----------------
_runs = {}  # run_id -> {"proc", "sid"}


PROGRESS_FILE = os.path.join(MURF, "dsh-progress.json")
INSTALL_PID_FILE = os.path.join(MURF, "dsh-install.pid")


def _pid_alive(pid):
    if not pid:
        return False
    try:
        os.kill(int(pid), 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True  # процесс есть, но чужой uid
    except (OSError, ValueError, TypeError):
        return False


def _read_install_pid():
    try:
        return int(open(INSTALL_PID_FILE).read().strip())
    except (OSError, ValueError):
        return None


def _write_progress(prog):
    os.makedirs(MURF, exist_ok=True)
    tmp = PROGRESS_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(prog, f, ensure_ascii=False)
    os.replace(tmp, PROGRESS_FILE)


def _reconcile_progress(prog):
    """Если state=running, но PID установки мёртв — пометить error, иначе UI залипает на 30% без кнопки «Повторить»."""
    if not isinstance(prog, dict) or prog.get("state") != "running":
        return prog
    pid = _read_install_pid()
    if _pid_alive(pid):
        return prog
    age = time.time() - int(prog.get("ts") or 0)
    # окно на запись PID сразу после Popen
    if pid is None and age < 12:
        return prog
    stale = {
        "state": "error",
        "pct": int(prog.get("pct") or 0),
        "msg": "установка прервана (процесс не работает). Держите Termux открытым и нажмите «Повторить».",
        "ts": int(time.time()),
    }
    try:
        _write_progress(stale)
    except OSError:
        pass
    try:
        os.unlink(INSTALL_PID_FILE)
    except OSError:
        pass
    return stale


def status():
    v = None
    try:
        v = open(os.path.join(DSH_DIR, "version")).read().strip()
    except OSError:
        pass
    prog = None
    try:
        prog = json.load(open(PROGRESS_FILE))
    except Exception:
        pass
    prog = _reconcile_progress(prog)
    return {"installed": bool(v) and os.path.exists(DSH_BIN), "version": v, "progress": prog,
            "running": [k for k, r in _runs.items() if r["proc"].poll() is None],
            "phone_bridge": time.time() - _device["last_poll"] < 90,
            "install_alive": bool(_pid_alive(_read_install_pid()))}


def install_async():
    prog = (status().get("progress") or {})
    if prog.get("state") == "running" and _pid_alive(_read_install_pid()):
        return {"ok": True, "already": True}
    os.makedirs(os.path.join(MURF, "logs"), exist_ok=True)
    # сбросить залипший running до старта, чтобы UI сразу показал новую попытку
    _write_progress({"state": "running", "pct": 1, "msg": "запуск установки DeepSeek Harness…", "ts": int(time.time())})
    lf = open(os.path.join(MURF, "logs", "dsh-install.out"), "a")
    subprocess.Popen(["bash", "/opt/murf/dsh-install.sh"], stdin=subprocess.DEVNULL, stdout=lf, stderr=subprocess.STDOUT,
                     start_new_session=True)
    return {"ok": True}


def _clean_providers(provs):
    out, env = [], {}
    for p in provs or []:
        pid = str(p.get("id", "")).lower()
        api = p.get("api")
        if not PID_RE.match(pid) or api not in APIS: continue
        base = str(p.get("baseURL") or "")
        if base and not re.match(r"^https?://[^\s\"']+$", base): continue
        models = [str(m)[:120] for m in (p.get("models") or []) if re.match(r"^[\w.:/@+-]{1,120}$", str(m))][:200]
        key_env = "MURF_KEY_" + re.sub(r"[^A-Z0-9]", "_", pid.upper())
        env[key_env] = str(p.get("key") or "none")  # pi-ai требует ключ даже у шлюзов без авторизации
        out.append({"id": pid, "api": api, "baseURL": base, "models": models, "keyEnv": key_env,
                    "name": str(p.get("name") or pid)[:60]})
    return out, env


def build_patch(provs, provider, model, phone_tools=True):
    """Слой --patch для профиля headless (JSON — тоже YAML). Без ключей: только имена переменных."""
    routes = {}
    for p in provs:
        r = {"displayName": p["name"], "api": p["api"], "apiKeyEnv": p["keyEnv"]}
        if p["baseURL"]: r["baseURL"] = p["baseURL"]
        if p["models"]: r["models"] = [{"id": m} for m in p["models"]]
        routes["murf-" + p["id"]] = r
    patch = [
        # телеметрия dsh по умолчанию шлёт данные на серверы DeepSeek — в MURF выключаем
        {"id": "session-telemetry-otel", "disabled": True},
        {"id": "llm-pi-ai", "config": {"providers": routes}},
    ]
    if provider and model:
        patch.append({"id": "agent-default-model", "config": {"provider": "murf-" + provider, "model": model}})
    if phone_tools:
        patch.append({"insert": [{"id": "murf-phone", "name": "@deepseek-ai/dsh-mcp-client", "config": {
            "transport": "stdio", "serverName": "murf_phone", "command": "python3", "args": [MCP],
            "env": {"MURF_PHONE_TOKEN_FILE": TOKEN_FILE,
                    "MURF_PHONE_API": "http://127.0.0.1:%s/murf/phone/call" % os.environ.get("MURF_API_PORT", "18083")}, "cwd": HOME, "toolCallTimeoutMs": 300000,
            "failOnStartupError": False}}]})
    return patch


def run(j, write):
    """POST /phone/api/dsh/run: {prompt, session_id?, providers[], provider, model, phone_tools?, cwd?}.
    Пишет в write() строки NDJSON: события dsh + поле _t (мс от старта), начало {"type":"murf_run"}, конец {"type":"murf_exit"}."""
    if not status()["installed"]:
        write({"type": "error", "error": "DeepSeek Harness не установлен (MURF → Локальная среда → DeepSeek Harness → Установить)"}); return
    prompt = str(j.get("prompt") or "")[:200_000]
    sid = j.get("session_id")
    if sid and not SID_RE.match(str(sid)):
        write({"type": "error", "error": "bad session id"}); return
    provs, keys = _clean_providers(j.get("providers"))
    provider, model = str(j.get("provider") or ""), str(j.get("model") or "")
    if not provs or provider not in {p["id"] for p in provs}:
        write({"type": "error", "error": "Не выбран провайдер (MURF → Настройки → Провайдеры)"}); return
    os.makedirs(RUNS, exist_ok=True); os.makedirs(DSH_HOME, exist_ok=True)
    os.chmod(DSH_DIR, 0o700)
    phone_token()
    run_id = uuid.uuid4().hex[:12]
    pf = os.path.join(DSH_DIR, f"patch-{run_id}.yml")
    with open(pf, "w") as f: json.dump(build_patch(provs, provider, model, j.get("phone_tools", True)), f)
    cwd = j.get("cwd") or os.path.join(HOME, "murf-dsh")
    if not os.path.isabs(cwd) or ".." in cwd: cwd = os.path.join(HOME, "murf-dsh")
    os.makedirs(cwd, exist_ok=True)
    env = {"HOME": HOME, "LANG": "C.UTF-8", "LC_ALL": "C.UTF-8", "DSH_HOME": DSH_HOME, "MURF_RUN_ID": run_id,
           "PATH": f"{NODE_BIN}:/opt/dsh/bin:/usr/local/bin:/usr/bin:/bin", "DSH_TELEMETRY_MODE": "OFF"}
    for k in ("HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "NO_PROXY"):
        if os.environ.get(k): env[k] = os.environ[k]
    env.update(keys)
    args = [DSH_BIN, "--profile", "headless", "--patch", pf, "--json"]
    if sid: args += ["--session-id", sid]
    args += ["--", prompt] if prompt else ["-"]
    t0 = time.time()
    meta = {"type": "murf_run", "run_id": run_id, "provider": provider, "model": model, "prompt": prompt[:2000],
            "ts": int(t0), "resume": bool(sid)}
    write(meta)
    try:
        proc = subprocess.Popen(args, cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True, bufsize=1, start_new_session=True)
    except OSError as e:
        write({"type": "error", "error": str(e)[:300]}); os.unlink(pf); return
    _runs[run_id] = {"proc": proc, "sid": sid}
    errbuf = []
    threading.Thread(target=lambda: errbuf.extend(proc.stderr.readlines()[-40:]), daemon=True).start()
    log = None; pending = [meta]
    try:
        for line in proc.stdout:
            try:
                ev = json.loads(line)
            except ValueError:
                continue
            ev["_t"] = int((time.time() - t0) * 1000)
            if ev.get("type") == "session" and SID_RE.match(str(ev.get("sessionId", ""))):
                sid = ev["sessionId"]; _runs[run_id]["sid"] = sid
                log = open(os.path.join(RUNS, sid + ".jsonl"), "a")
                for p in pending: log.write(json.dumps(p, ensure_ascii=False) + "\n")
                pending = []
            if log: log.write(json.dumps(ev, ensure_ascii=False) + "\n"); log.flush()
            else: pending.append(ev)
            try:
                write(ev)
            except Exception:
                pass  # клиент отключился — прогон продолжается и пишется в журнал
        proc.wait()
    finally:
        rc = proc.poll()
        err = "".join(errbuf)[-3000:]
        for k in keys.values():  # на всякий случай — вычистить ключи из текста ошибки
            if k: err = err.replace(k, "***")
        end = {"type": "murf_exit", "code": rc, "_t": int((time.time() - t0) * 1000)}
        if rc: end["stderr"] = err
        if log: log.write(json.dumps(end, ensure_ascii=False) + "\n"); log.close()
        try: write(end)
        except Exception: pass
        _runs.pop(run_id, None)
        try: os.unlink(pf)
        except OSError: pass


def stop(run_id=None):
    n = 0
    for k, r in list(_runs.items()):
        if run_id and k != run_id: continue
        try:
            os.killpg(r["proc"].pid, signal.SIGINT); n += 1
        except OSError:
            pass
    return {"ok": True, "stopped": n}


def sessions():
    out = []
    try:
        names = os.listdir(RUNS)
    except OSError:
        names = []
    for n in names:
        if not n.endswith(".jsonl") or not SID_RE.match(n[:-6]): continue
        p = os.path.join(RUNS, n)
        title, turns, last, prov = "", 0, 0, ""
        tot = {"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0}
        try:
            for line in open(p):
                try: e = json.loads(line)
                except ValueError: continue
                if e.get("type") == "murf_run":
                    title = title or e.get("prompt", "")[:80]; last = e.get("ts", last); turns += 1
                    prov = f'{e.get("provider")}/{e.get("model")}'
                u = e.get("usage") if e.get("phase") == "step_end" else None
                if isinstance(u, dict):
                    tot["input"] += u.get("inputTokens", 0); tot["output"] += u.get("outputTokens", 0)
                    tot["cacheRead"] += u.get("cacheReadTokens", 0); tot["cacheWrite"] += u.get("cacheWriteTokens", 0)
        except OSError:
            continue
        out.append({"id": n[:-6], "title": title, "turns": turns, "updated": last or int(os.path.getmtime(p)),
                    "route": prov, "usage": tot})
    out.sort(key=lambda s: -s["updated"])
    return {"sessions": out[:200]}


def session_log(sid):
    if not SID_RE.match(sid or ""): return None
    try:
        return [json.loads(l) for l in open(os.path.join(RUNS, sid + ".jsonl")) if l.strip()][-5000:]
    except (OSError, ValueError):
        return None
