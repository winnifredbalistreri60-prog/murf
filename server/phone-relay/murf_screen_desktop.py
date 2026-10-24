#!/usr/bin/env python3
"""MURF: старт / статус / keep-alive Bot Desktop (tools.bot_desktop.runtime).

Вызов (Hermes tools python -I, HERMES_HOME задан):
    murf_screen_desktop.py start
    murf_screen_desktop.py status
    murf_screen_desktop.py watch   # systemd keep-alive: start + следить, при падении — снова start

Печатает одну строку JSON (start/status). watch — в journal пишет события, не JSON-луп.
"""
from __future__ import annotations

import json
import os
import sys
import time


def out(obj, code=0):
    print(json.dumps(obj, ensure_ascii=False), flush=True)
    raise SystemExit(code)


def _bootstrap():
    home = os.environ.get("HERMES_HOME") or os.path.expanduser("~/.hermes")
    agent = os.environ.get("HERMES_AGENT_DIR") or os.path.join(home, "hermes-agent")
    os.environ.setdefault("HERMES_HOME", home)
    sys.path.insert(0, agent)
    # pin home before hermes config resolves
    sys._hermes_pin_default_home = True  # type: ignore[attr-defined]
    import hermes_bootstrap  # noqa: E402
    hermes_bootstrap.harden_import_path()
    from tools.bot_desktop import runtime  # noqa: E402
    return runtime


def _payload(st, err=None):
    d = {
        "ok": err is None and bool(getattr(st, "running", False) if st is not None else False),
        "running": bool(getattr(st, "running", False)) if st is not None else False,
        "display": getattr(st, "display", None) if st is not None else None,
        "socket": getattr(st, "socket", None) if st is not None else None,
    }
    if st is not None and getattr(st, "pid", None) is not None:
        d["pid"] = st.pid
    if err:
        d["ok"] = False
        d["error"] = str(err)[:400]
        # still include status fields if we have them
        if st is not None and not d.get("running"):
            d["running"] = bool(st.running)
    elif st is not None and not st.running:
        d["ok"] = False
        d["error"] = getattr(st, "blocker", None) or "экран агента не запущен"
    return d


def cmd_status():
    runtime = _bootstrap()
    try:
        st = runtime.status()
    except Exception as e:
        out({"ok": False, "running": False, "display": None, "socket": None, "error": type(e).__name__ + ": " + str(e)[:300]}, 1)
    out(_payload(st))


def cmd_start():
    runtime = _bootstrap()
    try:
        st = runtime.start(wait_seconds=60)
    except Exception as e:
        st = None
        try:
            st = runtime.status()
        except Exception:
            pass
        out(_payload(st, err=str(e)[:400]), 1)
    out(_payload(st))


def cmd_watch():
    """Keep Bot Desktop up. Idempotent start; exit non-zero only on fatal bootstrap failure after retries."""
    interval = float(os.environ.get("BOT_DESKTOP_WATCH_SEC", "15"))
    runtime = _bootstrap()
    print(time.strftime("%F %T"), "bot-desktop watch started", flush=True)
    while True:
        try:
            st = runtime.start(wait_seconds=60)
            print(time.strftime("%F %T"), "ok running=", st.running, "display=", st.display, flush=True)
            # park while launcher / DISPLAY alive
            while True:
                time.sleep(interval)
                if not runtime.is_running():
                    print(time.strftime("%F %T"), "desktop down — restarting", flush=True)
                    break
        except KeyboardInterrupt:
            raise
        except Exception as e:
            print(time.strftime("%F %T"), "start error:", type(e).__name__, str(e)[:300], flush=True)
            time.sleep(min(60.0, max(5.0, interval)))


def main(argv):
    if not argv:
        out({"ok": False, "error": "usage: start|status|watch"}, 2)
    cmd = argv[0]
    if cmd == "status":
        return cmd_status()
    if cmd == "start":
        return cmd_start()
    if cmd == "watch":
        return cmd_watch()
    out({"ok": False, "error": "usage: start|status|watch"}, 2)


if __name__ == "__main__":
    main(sys.argv[1:])
