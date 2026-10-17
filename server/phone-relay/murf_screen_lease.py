#!/usr/bin/env python3
"""MURF «Взять экран»: аренда управления экраном бота Hermes (tools/bot_desktop/lease.py).

Запускается relay'ем через python из venv Hermes Agent (cwd = ~/.hermes/hermes-agent):
    murf_screen_lease.py status [viewer]
    murf_screen_lease.py take <viewer> [reason]
    murf_screen_lease.py release <viewer> [--force]
Печатает одну строку JSON. Пока человек держит аренду, computer_use и браузерные инструменты
агента отказываются действовать (human_has_control) — это родной механизм Hermes «Take over / Hand back».
"""
import json, re, sys

VIEWER_RE = re.compile(r"^murf-[A-Za-z0-9_-]{4,64}$")


def out(obj, code=0):
    print(json.dumps(obj, ensure_ascii=False))
    sys.exit(code)


def view(L, lease, viewer):
    d = L.public_view(lease)
    d["mine"] = bool(viewer) and lease.holder == L.HUMAN and lease.viewer_id == viewer
    d["supported"] = True
    return d


def main(argv):
    if not argv:
        out({"ok": False, "error": "usage"}, 2)
    cmd, args = argv[0], argv[1:]
    viewer = args[0] if args else ""
    if viewer and not VIEWER_RE.match(viewer):
        out({"ok": False, "error": "bad viewer id"}, 2)
    try:
        sys.path.insert(0, ".")
        from tools.bot_desktop import lease as L  # noqa
    except Exception as e:  # нет Hermes Agent с Bot Desktop
        out({"ok": True, "supported": False, "holder": "agent", "error": type(e).__name__})
    if cmd == "status":
        out(dict(view(L, L.get(), viewer), ok=True))
    if cmd == "take":
        if not viewer:
            out({"ok": False, "error": "viewer required"}, 2)
        reason = (args[1] if len(args) > 1 else "MURF: управление с телефона")[:200]
        out(dict(view(L, L.acquire(viewer, reason=reason), viewer), ok=True))
    if cmd == "release":
        force = "--force" in args
        lease = L.release(None if force else viewer)
        out(dict(view(L, lease, viewer), ok=lease.holder == L.AGENT))
    out({"ok": False, "error": "unknown command"}, 2)


if __name__ == "__main__":
    main(sys.argv[1:])
