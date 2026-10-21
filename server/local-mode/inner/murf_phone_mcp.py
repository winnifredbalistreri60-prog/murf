#!/usr/bin/env python3
"""MURF phone tools — MCP-сервер (stdio, JSON-RPC построчно) для DeepSeek Harness в локальном режиме.
Каждый вызов уходит в murf_api (127.0.0.1:18083/murf/phone/call, токен из ~/.murf/phone.token),
оттуда — в приложение MURF на телефоне (тот же протокол phone/api/poll|result, что и PhoneBridge с сервером).
Подтверждение рискованных действий делает приложение (карточка/уведомление «Разрешить/Отклонить»)."""
import json, os, sys, urllib.request

API = os.environ.get("MURF_PHONE_API", "http://127.0.0.1:18083/murf/phone/call")
TOKEN_FILE = os.environ.get("MURF_PHONE_TOKEN_FILE", os.path.expanduser("~/.murf/phone.token"))

def S(props=None, req=()):
    return {"type": "object", "properties": props or {}, "required": list(req)}
STR = {"type": "string"}; INT = {"type": "integer"}; NUM = {"type": "number"}

TOOLS = [
    ("battery", "Заряд батареи и зарядка.", S()),
    ("network_info", "Тип сети (Wi-Fi/моб.), есть ли интернет, VPN.", S()),
    ("notify", "Показать уведомление на телефоне.", S({"title": STR, "text": STR}, ["text"])),
    ("clipboard_get", "Прочитать буфер обмена (нужно подтверждение).", S()),
    ("clipboard_set", "Записать текст в буфер обмена.", S({"text": STR}, ["text"])),
    ("list_apps", "Список установленных приложений (метка и пакет).", S({"query": STR})),
    ("open_app", "Открыть приложение по пакету (или метке).", S({"package": STR}, ["package"])),
    ("open_url", "Открыть ссылку (браузер/приложение) — нужно подтверждение.", S({"url": STR}, ["url"])),
    ("share_text", "Открыть системное «Поделиться» с текстом.", S({"text": STR}, ["text"])),
    ("list_files", "Список файлов в общей памяти (через Termux, ~/storage/shared).", S({"path": STR})),
    ("file_read", "Прочитать текстовый файл (≤1 МБ). Вне папки MURF — с подтверждением.", S({"path": STR}, ["path"])),
    ("file_write", "Записать текстовый файл. По умолчанию в Download/MURF; другое место — с подтверждением.", S({"path": STR, "text": STR, "append": {"type": "boolean"}}, ["path", "text"])),
    ("notifications_list", "Последние уведомления (нужен доступ к уведомлениям для MURF).", S({"limit": INT})),
    ("screenshot", "Снимок экрана (через спец. возможности MURF). Возвращает путь и описание.", S()),
    ("screen_view", "Элементы на экране: текст, кнопки, координаты (спец. возможности MURF).", S({"max": INT})),
    ("tap", "Нажать в точку экрана или на элемент с текстом — нужно подтверждение.", S({"x": NUM, "y": NUM, "text": STR})),
    ("swipe", "Свайп — нужно подтверждение.", S({"x1": NUM, "y1": NUM, "x2": NUM, "y2": NUM, "ms": INT}, ["x1", "y1", "x2", "y2"])),
    ("type_text", "Ввести текст в поле в фокусе — нужно подтверждение.", S({"text": STR}, ["text"])),
    ("press_key", "Системная кнопка: back, home, recents, notifications.", S({"key": STR}, ["key"])),
    ("get_location", "Геопозиция (Termux:API) — нужно подтверждение.", S()),
    ("run_shell", "Команда в Termux (не в Ubuntu) — нужно подтверждение.", S({"command": STR, "workdir": STR}, ["command"])),
    ("sms_list", "SMS (по умолчанию выключено в MURF).", S({"limit": INT})),
]

def call(tool, args):
    try:
        tok = open(TOKEN_FILE).read().strip()
    except OSError:
        return {"ok": False, "error": "MURF: нет токена телефона (среда MURF не запущена?)"}
    body = json.dumps({"tool": tool, "args": args, "timeout": 180, "source": os.environ.get("MURF_RUN_ID", "dsh")}).encode()
    rq = urllib.request.Request(API, data=body, headers={"Content-Type": "application/json", "X-Murf-Phone-Token": tok})
    try:
        with urllib.request.urlopen(rq, timeout=200) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        try: return json.loads(e.read())
        except Exception: return {"ok": False, "error": f"HTTP {e.code}"}
    except Exception as e:
        return {"ok": False, "error": str(e)[:300]}

def reply(id_, result=None, error=None):
    m = {"jsonrpc": "2.0", "id": id_}
    if error: m["error"] = error
    else: m["result"] = result
    sys.stdout.write(json.dumps(m, ensure_ascii=False) + "\n"); sys.stdout.flush()

def main():
    for line in sys.stdin:
        line = line.strip()
        if not line: continue
        try: msg = json.loads(line)
        except ValueError: continue
        mid, meth = msg.get("id"), msg.get("method")
        if mid is None: continue  # уведомления
        if meth == "initialize":
            reply(mid, {"protocolVersion": msg.get("params", {}).get("protocolVersion", "2025-06-18"),
                        "capabilities": {"tools": {"listChanged": False}},
                        "serverInfo": {"name": "murf-phone", "version": "1.10.0"},
                        "instructions": "Инструменты управляют телефоном пользователя через приложение MURF. "
                                        "Рискованные действия пользователь подтверждает в MURF; при отказе не повторяй тот же запрос."})
        elif meth == "tools/list":
            reply(mid, {"tools": [{"name": n, "description": d, "inputSchema": s} for n, d, s in TOOLS]})
        elif meth == "tools/call":
            p = msg.get("params", {}); name = p.get("name")
            if name not in {t[0] for t in TOOLS}:
                reply(mid, {"content": [{"type": "text", "text": f"неизвестный инструмент {name}"}], "isError": True}); continue
            r = call(name, p.get("arguments") or {})
            text = r.get("output") or r.get("error") or json.dumps(r, ensure_ascii=False)
            reply(mid, {"content": [{"type": "text", "text": str(text)[:60000]}], "isError": not r.get("ok", False)})
        elif meth == "ping":
            reply(mid, {})
        else:
            reply(mid, error={"code": -32601, "message": "method not found"})

if __name__ == "__main__":
    main()
