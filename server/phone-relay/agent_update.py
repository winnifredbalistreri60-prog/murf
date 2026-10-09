#!/usr/bin/env python3
"""Обновление Hermes Agent для приложения «Агент»: проверка, бэкап, `hermes update`, рестарт, проверка здоровья, автооткат.

Режимы:
  --check            только проверить, есть ли обновление (пишет agent-update-check.json)
  --mode dry-run     пробный прогон: проверка + НАСТОЯЩИЙ бэкап + проверка архивов + проверка здоровья, БЕЗ обновления и рестартов
  --mode apply       настоящее обновление (с автооткатом из бэкапа при ошибке)

Запускается relay'ем через `systemd-run --user`, чтобы пережить рестарты шлюза/relay.
Состояние для приложения: ~/hermes-backups/agent-update-state.json. Только stdlib.
"""
import argparse, datetime, fcntl, json, os, re, shutil, sqlite3, subprocess, sys, time, urllib.request

HOME = os.path.expanduser("~")
HH = os.path.join(HOME, ".hermes")
AGENT_DIR = os.path.join(HH, "hermes-agent")
BK_ROOT = os.path.join(HOME, "hermes-backups")
STATE = os.path.join(BK_ROOT, "agent-update-state.json")
CHECK = os.path.join(BK_ROOT, "agent-update-check.json")
LOCK = os.path.join(BK_ROOT, ".agent-update.lock")
HERMES = os.path.join(HOME, ".local/bin/hermes")
NPMRC = os.path.join(HOME, "agent-phone", "npmrc-noproxy")
GLOG = os.path.join(HH, "logs", "gateway.log")
KEEP_BACKUPS = 3
# В бэкап домашней папки Hermes не берём большие кэши/рантаймы (их восстанавливает сам hermes).
HOME_EXCLUDES = ["./hermes-agent", "./cache", "./logs", "./tools", "./node", "./bot-desktop", "./state-snapshots",
                 "./audio_cache", "./image_cache", "./sandboxes", "./backups", "./.curator_backups",
                 "./state.db", "./state.db-wal", "./state.db-shm", "*.lock", "./gateway.sock"]
os.umask(0o077)
os.makedirs(BK_ROOT, exist_ok=True)


def now():
    return time.strftime("%Y-%m-%d %H:%M:%S")


def env():
    e = dict(os.environ)
    e["PATH"] = os.path.join(HOME, ".local/bin") + ":" + e.get("PATH", "/usr/bin:/bin")
    e.setdefault("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}")
    # ~/.npmrc пользователя содержит мёртвый прокси 127.0.0.1:8899 — не трогаем его, а подменяем на время обновления.
    try:
        src = open(os.path.join(HOME, ".npmrc"), encoding="utf-8").read().splitlines()
    except OSError:
        src = []
    os.makedirs(os.path.dirname(NPMRC), exist_ok=True)
    with open(NPMRC, "w", encoding="utf-8") as f:
        f.write("\n".join(l for l in src if not re.match(r"\s*(https?-)?proxy\s*=", l)) + "\n")
    e["NPM_CONFIG_USERCONFIG"] = NPMRC
    for k in ("npm_config_proxy", "npm_config_https_proxy", "NPM_CONFIG_PROXY", "NPM_CONFIG_HTTPS_PROXY"):
        e.pop(k, None)
    e["HERMES_NONINTERACTIVE"] = "1"
    return e


def run(cmd, timeout=120, cwd=None, logf=None, check=False):
    p = subprocess.run(cmd, cwd=cwd, env=env(), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       text=True, timeout=timeout, stdin=subprocess.DEVNULL)
    if logf:
        logf.write(f"\n$ {' '.join(cmd)}\n{p.stdout}\n[exit {p.returncode}]\n")
        logf.flush()
    if check and p.returncode != 0:
        raise RuntimeError(f"{cmd[0]} {cmd[1] if len(cmd) > 1 else ''}: exit {p.returncode}: {p.stdout[-400:].strip()}")
    return p.returncode, p.stdout


def git(*a, timeout=60):
    return run(["git", "-c", "safe.directory=*", "-C", AGENT_DIR, *a], timeout=timeout)[1].strip()


def atomic_write(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


# ---------- проверка ----------

def version_info():
    rc, out = run([HERMES, "--version"], timeout=90)
    line = next((l for l in out.splitlines() if l.startswith("Hermes Agent")), out.strip().splitlines()[-1] if out.strip() else "")
    m = re.search(r"\((\d{4}\.\d+\.\d+)\)", line)
    return {
        "version_line": line.strip(),
        "release": m.group(1) if m else None,
        "sha": git("rev-parse", "--short=10", "HEAD"),
        "branch": git("rev-parse", "--abbrev-ref", "HEAD"),
        "pending_tail": "could not be finished automatically" in out or "finishing an interrupted source update" in out,
    }


def do_check():
    info = {"checked_at": time.time(), "checked": now(), "ok": False}
    try:
        info.update(version_info())
        rc, out = run([HERMES, "update", "--check"], timeout=240)
        info["raw"] = out[-800:]
        info["pending_tail"] = info.get("pending_tail") or "could not be finished automatically" in out
        m = re.search(r"(\d+)\s+commits?\s+behind\s+(\S+)", out)
        if m:
            info.update(behind=int(m.group(1)), upstream=m.group(2).rstrip("."), update_available=True)
        elif re.search(r"up to date|already (on the )?latest|no update", out, re.I):
            info.update(behind=0, update_available=False)
        else:
            # запасной путь: git напрямую
            run(["git", "-c", "safe.directory=*", "-C", AGENT_DIR, "fetch", "--quiet", "origin"], timeout=120)
            n = int(git("rev-list", "--count", "HEAD..origin/main") or 0)
            info.update(behind=n, upstream="origin/main", update_available=n > 0, via="git")
        if info.get("update_available"):
            info["latest_sha"] = git("rev-parse", "--short=10", info.get("upstream", "origin/main"))
            subj = git("log", "--format=%s", "-n", "8", f"HEAD..{info.get('upstream', 'origin/main')}")
            info["recent"] = [s for s in subj.splitlines() if s][:8]
        info["ok"] = True
    except Exception as e:  # noqa
        info["error"] = str(e)[:400]
    atomic_write(CHECK, info)
    return info


# ---------- состояние задания ----------

class Job:
    def __init__(self, mode):
        self.ts = time.strftime("%Y%m%d-%H%M%S")
        self.dir = os.path.join(BK_ROOT, f"agent-update-{self.ts}" + ("-dryrun" if mode == "dry-run" else ""))
        os.makedirs(self.dir, mode=0o700, exist_ok=True)
        self.logpath = os.path.join(self.dir, "update.log")
        self.log = open(self.logpath, "a", encoding="utf-8")
        self.s = {"id": self.ts, "mode": mode, "phase": "start", "running": True, "ok": None, "started": now(),
                  "finished": None, "backup_dir": self.dir, "log": self.logpath, "steps": [], "before": {}, "after": {},
                  "rolled_back": False, "message": "", "pid": os.getpid()}
        self.save()

    def save(self):
        atomic_write(STATE, self.s)

    def step(self, name, title, fn):
        st = {"name": name, "title": title, "status": "running", "t": now(), "detail": ""}
        self.s["steps"].append(st)
        self.s["phase"] = name
        self.save()
        self.log.write(f"\n=== {now()} {title}\n")
        self.log.flush()
        try:
            d = fn()
            st["status"] = "ok"
            st["detail"] = (d or "")[:600] if isinstance(d, str) else json.dumps(d, ensure_ascii=False)[:600]
            return d
        except Exception as e:
            st["status"] = "failed"
            st["detail"] = str(e)[:600]
            self.log.write(f"!!! {e}\n")
            raise
        finally:
            self.save()

    def skip(self, name, title, why):
        self.s["steps"].append({"name": name, "title": title, "status": "skipped", "t": now(), "detail": why})
        self.save()

    def done(self, ok, msg):
        self.s.update(running=False, ok=ok, finished=now(), phase="done", message=msg)
        self.save()
        self.log.write(f"\n=== {now()} ИТОГ: {'OK' if ok else 'ОШИБКА'} — {msg}\n")
        self.log.close()


# ---------- шаги ----------

def preflight(job):
    free = shutil.disk_usage(BK_ROOT).free
    need = 3 * 1024 ** 3
    if free < need:
        raise RuntimeError(f"мало места для бэкапа: свободно {free // 1024 ** 2} МБ, нужно ≥ {need // 1024 ** 2} МБ")
    v = version_info()
    job.s["before"] = v
    for name, cmd in [("git-head.txt", ["rev-parse", "HEAD"]), ("git-status.txt", ["status", "--porcelain=v1", "-b"]),
                      ("git-stash-list.txt", ["stash", "list", "--format=%H %gd %cI %gs"]), ("local-changes.diff", ["diff", "HEAD"])]:
        open(os.path.join(job.dir, name), "w").write(git(*cmd) + "\n")
    # сохранённые локальные патчи (stash) — каждый отдельным .patch
    stashes = [l.split()[1] for l in git("stash", "list", "--format=%H %gd").splitlines() if l.strip()]
    for i, ref in enumerate(stashes[:10]):
        open(os.path.join(job.dir, f"stash-{i}.patch"), "w").write(
            run(["git", "-c", "safe.directory=*", "-C", AGENT_DIR, "stash", "show", "-p", "--include-untracked", ref], timeout=60)[1])
    open(os.path.join(job.dir, "units.txt"), "w").write(
        run(["systemctl", "--user", "is-active", "hermes-gateway", "hermes-webui", "hermes-watchdog.timer"], timeout=20)[1])
    atomic_write(os.path.join(job.dir, "meta.json"), {"before": v, "created": now(), "mode": job.s["mode"]})
    return f"{v['version_line']} · HEAD {v['sha']} · stash: {len(stashes)} · свободно {free // 1024 ** 3} ГБ"


def backup(job):
    a1 = os.path.join(job.dir, "hermes-agent.tar.zst")
    rc, out = run(["tar", "-C", HH, "--exclude=__pycache__", "--warning=no-file-changed", "-I", "zstd -T0 -3", "-cf", a1, "hermes-agent"],
                  timeout=1800, logf=job.log)
    if rc not in (0, 1):  # 1 = «файл изменился во время чтения» — не фатально
        raise RuntimeError(f"tar hermes-agent: exit {rc}: {out[-300:]}")
    # state.db — консистентная копия через sqlite backup API
    db = os.path.join(HH, "state.db")
    if os.path.exists(db):
        src = sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=30)
        dst = sqlite3.connect(os.path.join(job.dir, "state.db"))
        src.backup(dst)
        dst.close(); src.close()
    a2 = os.path.join(job.dir, "hermes-home.tar.zst")
    # чужие (root) бэкапы .env.bak-* и сокеты пропускаем с предупреждением в журнале
    cmd = (["tar", "-C", HH, "--ignore-failed-read", "--warning=no-file-changed", "--warning=no-file-ignored"]
           + [f"--exclude={x}" for x in HOME_EXCLUDES] + ["-I", "zstd -T0 -3", "-cf", a2, "."])
    rc, out = run(cmd, timeout=1800, logf=job.log)
    if rc not in (0, 1):
        raise RuntimeError(f"tar ~/.hermes: exit {rc}: {out[-300:]}")
    for a in (a1, a2):
        run(["zstd", "-q", "-t", a], timeout=900, check=True)
    # важные файлы обязаны попасть в копию
    rc, listing = run(["bash", "-c", f"tar -I zstd -tf '{a2}' | grep -xE '\\./(config\\.yaml|\\.env|auth\\.json)'"], timeout=300)
    missing = [f for f in ("./config.yaml", "./.env", "./auth.json") if os.path.exists(os.path.join(HH, f[2:])) and f not in listing.split()]
    if missing:
        raise RuntimeError("в копию не попали: " + ", ".join(missing))
    for f in os.listdir(job.dir):
        os.chmod(os.path.join(job.dir, f), 0o600)
    size = lambda p: os.path.getsize(p) // 1024 ** 2
    return f"hermes-agent {size(a1)} МБ, ~/.hermes {size(a2)} МБ, state.db; архивы проверены (zstd -t)"


def health(job, since=None, wait=150):
    """since: время (epoch) рестарта — тогда ждём свежую строку «Connected to Telegram» в логе."""
    deadline = time.time() + wait
    last = {}
    while True:
        r = {}
        r["gateway"] = run(["systemctl", "--user", "is-active", "hermes-gateway"], timeout=20)[1].strip()
        r["webui"] = run(["systemctl", "--user", "is-active", "hermes-webui"], timeout=20)[1].strip()
        r["watchdog_timer"] = run(["systemctl", "--user", "is-active", "hermes-watchdog.timer"], timeout=20)[1].strip()
        try:
            req = urllib.request.Request("http://127.0.0.1:8642/health")
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            r["api"] = json.loads(opener.open(req, timeout=5).read().decode()).get("status")
        except Exception as e:
            r["api"] = f"нет ответа ({type(e).__name__})"
        r["telegram"] = telegram_state(since)
        if r["gateway"] == "active" and r["api"] == "ok" and r["telegram"] == "connected":
            try:
                r["watchdog"] = watchdog_probe()
            except Exception as e:
                r["watchdog"] = f"ошибка: {e}"
        last = r
        ok = r["gateway"] == "active" and r["webui"] == "active" and r["api"] == "ok" and r["telegram"] == "connected"
        if ok or time.time() > deadline:
            break
        time.sleep(5)
    try:
        last["cli"] = version_info()["version_line"]
    except Exception as e:
        last["cli"] = f"ошибка: {e}"
    job.s["after"] = last
    if not ok:
        raise RuntimeError("проверка не пройдена: " + json.dumps(last, ensure_ascii=False))
    return last


def telegram_state(since):
    """connected — если после `since` (или вообще в хвосте лога) есть подключение и нет последующего отключения."""
    try:
        with open(GLOG, "rb") as f:
            f.seek(0, 2)
            f.seek(max(0, f.tell() - 400_000))
            lines = f.read().decode("utf-8", "ignore").splitlines()
    except OSError:
        return "нет лога"
    state = "unknown"
    for l in lines:
        m = re.match(r"(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d)", l)
        if since and m and time.mktime(time.strptime(m.group(1), "%Y-%m-%d %H:%M:%S")) < since - 2:
            continue
        if "Connected to Telegram" in l or "Telegram polling confirmed healthy" in l or "Telegram API transport recovered" in l:
            state = "connected"
        elif "Disconnected from Telegram" in l or "telegram disconnected" in l:
            state = "disconnected"
    if state == "unknown" and not since:
        # долго без событий — значит стабильно работает с прошлого подключения
        state = "connected" if run(["systemctl", "--user", "is-active", "hermes-gateway"], timeout=20)[1].strip() == "active" else "unknown"
    return state


def watchdog_timer(action, job=None):
    """Пауза watchdog на время обновления, чтобы он не перезапускал шлюз посреди `hermes update`."""
    run(["systemctl", "--user", action, "hermes-watchdog.timer"], timeout=30, logf=job.log if job else None)


def watchdog_probe():
    """Один прогон watchdog в режиме без рестартов: видит ли он шлюз."""
    wd = os.path.join(HOME, ".local/bin/hermes-watchdog.sh")
    if not os.path.exists(wd):
        return "нет скрипта"
    rc, out = run(["env", "WATCHDOG_DRY_RUN=1", "bash", wd], timeout=60)
    line = (out.strip().splitlines() or [""])[-1]
    return "ok" if " OK active=1" in line else line[-160:]


def update(job):
    watchdog_timer("stop", job)
    rc, out = run([HERMES, "update", "--yes"], timeout=3600, cwd=HOME, logf=job.log)
    if rc != 0:
        raise RuntimeError(f"hermes update завершился с кодом {rc}: {out[-500:].strip()}")
    return out[-500:].strip()


def restart(job):
    t0 = time.time()
    run(["systemctl", "--user", "restart", "hermes-gateway"], timeout=180, logf=job.log, check=True)
    run(["systemctl", "--user", "restart", "hermes-webui"], timeout=120, logf=job.log, check=True)
    job.s["restarted_at"] = t0
    return "hermes-gateway и hermes-webui перезапущены"


def rollback(job):
    a1 = os.path.join(job.dir, "hermes-agent.tar.zst")
    a2 = os.path.join(job.dir, "hermes-home.tar.zst")
    if not os.path.exists(a1):
        raise RuntimeError("архива нет — откат невозможен")
    run(["systemctl", "--user", "stop", "hermes-gateway"], timeout=180, logf=job.log)
    failed = os.path.join(HH, f"hermes-agent.failed-{job.ts}")
    if os.path.exists(AGENT_DIR):
        os.rename(AGENT_DIR, failed)
    run(["tar", "-C", HH, "-I", "zstd -T0", "-xf", a1], timeout=1800, logf=job.log, check=True)
    # конфиги, которые `hermes update` мог мигрировать
    run(["tar", "-C", HH, "-I", "zstd -T0", "-xf", a2, "./config.yaml", "./.env"], timeout=300, logf=job.log)
    t0 = time.time()
    run(["systemctl", "--user", "start", "hermes-gateway"], timeout=180, logf=job.log)
    run(["systemctl", "--user", "restart", "hermes-webui"], timeout=120, logf=job.log)
    job.s["rolled_back"] = True
    health(job, since=t0)
    return f"восстановлено из {a1}; неудачная версия сохранена в {failed}"


def verify_restore_plan(job):
    a1 = os.path.join(job.dir, "hermes-agent.tar.zst")
    rc, out = run(["bash", "-c", f"tar -I zstd -tf '{a1}' | head -3; tar -I zstd -tf '{a1}' | wc -l"], timeout=900)
    lines = out.strip().splitlines()
    return f"архив читается, файлов: {lines[-1] if lines else '?'}; откат = stop шлюза → hermes-agent → .failed → распаковка → config.yaml/.env → start → проверка"


def prune():
    dirs = sorted(d for d in os.listdir(BK_ROOT) if d.startswith("agent-update-2") and os.path.isdir(os.path.join(BK_ROOT, d)))
    for d in dirs[:-KEEP_BACKUPS]:
        shutil.rmtree(os.path.join(BK_ROOT, d), ignore_errors=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--mode", choices=["dry-run", "apply"])
    a = ap.parse_args()
    if a.check:
        print(json.dumps(do_check(), ensure_ascii=False, indent=1))
        return 0
    lockf = open(LOCK, "w")
    try:
        fcntl.flock(lockf, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        print("уже выполняется", file=sys.stderr)
        return 2
    job = Job(a.mode)
    dry = a.mode == "dry-run"
    try:
        job.step("preflight", "Проверка перед обновлением", lambda: preflight(job))
        job.step("backup", "Резервная копия (hermes-agent, ~/.hermes, state.db, патчи)", lambda: backup(job))
    except Exception as e:
        job.done(False, f"Остановлено до обновления, ничего не изменено: {e}")
        prune()
        return 1
    if dry:
        job.skip("update", "hermes update --yes", "пробный запуск — обновление не выполнялось")
        job.skip("restart", "Перезапуск шлюза и WebUI", "пробный запуск — сервисы не перезапускались")
        try:
            job.step("health", "Проверка здоровья (API, Telegram, WebUI, watchdog)", lambda: health(job, since=None, wait=30))
            job.step("rollback_check", "Проверка отката (архив читается)", lambda: verify_restore_plan(job))
            prune()
            job.done(True, "Пробный запуск успешен: бэкап создан и проверен, сервисы здоровы. Обновление не выполнялось.")
            return 0
        except Exception as e:
            job.done(False, f"Пробный запуск: {e}")
            return 1
    try:
        job.step("update", "hermes update --yes", lambda: update(job))
        job.step("restart", "Перезапуск шлюза и WebUI", lambda: restart(job))
        watchdog_timer("start", job)
        job.step("health", "Проверка здоровья (API, Telegram, WebUI, watchdog)", lambda: health(job, since=job.s.get("restarted_at")))
        prune()
        try:
            do_check()
        except Exception:
            pass
        job.done(True, f"Обновлено: {job.s['before'].get('sha')} → {version_info().get('sha')}")
        return 0
    except Exception as e:
        err = str(e)
        try:
            job.step("rollback", "Автооткат из резервной копии", lambda: rollback(job))
            job.done(False, f"Обновление не удалось ({err[:200]}). Выполнен откат, агент работает на прежней версии.")
        except Exception as e2:
            job.done(False, f"Обновление не удалось ({err[:200]}), и откат тоже: {e2}. Нужна ручная помощь: см. {job.logpath}")
        finally:
            watchdog_timer("start", job)
        return 1


if __name__ == "__main__":
    sys.exit(main())
