#!/usr/bin/env python3
"""MCP-сервер (stdio) «agent_tools»: телефон пользователя (через приложение «Агент» + Termux)
и Google Диск (через rclone, remote gdrive:). Без внешних зависимостей."""
import json, os, shutil, subprocess, sys, urllib.request

RELAY = os.environ.get("PHONE_RELAY_URL", "http://127.0.0.1:8791")
RCLONE_CONF = os.environ.get("RCLONE_CONFIG", os.path.expanduser("~/.config/rclone/rclone.conf"))
REMOTE = os.environ.get("DRIVE_REMOTE", "gdrive:")
DRIVE_HINT = ("Google Диск не подключён. Пользователь должен открыть в приложении «Агент» → «Google Диск» → "
              "«Подключить» (rclone authorize в Termux) или прислать токен в том же экране.")

def S(props=None, req=None):
    return {"type": "object", "properties": props or {}, "required": req or []}

STR = {"type": "string"}
INT = {"type": "integer"}

TOOLS = [
    ("phone_status", "Статус телефона пользователя: подключён ли мост, возможности, Termux. Вызывай перед другими phone_* при сомнениях.", S()),
    ("phone_run_shell", "Выполнить shell-команду в Termux на телефоне пользователя (bash). Пользователь подтверждает на телефоне. Вывод до ~100 КБ.",
     S({"command": dict(STR, description="команда bash"), "workdir": dict(STR, description="каталог, по умолчанию ~ Termux"),
        "timeout": dict(INT, description="секунд, по умолчанию 120")}, ["command"])),
    ("phone_get_location", "Получить геопозицию телефона (Termux:API termux-location).", S()),
    ("phone_take_photo", "Сделать фото камерой телефона (Termux:API) и загрузить на сервер; вернёт путь к файлу на сервере.",
     S({"camera": dict(STR, description="back или front")})),
    ("phone_list_files", "Список файлов на телефоне (по умолчанию общая память ~/storage/shared).", S({"path": STR})),
    ("phone_pull_file", "Скопировать файл с телефона на сервер (до 50 МБ); вернёт путь на сервере.", S({"path": STR}, ["path"])),
    ("phone_push_file", "Отправить файл с сервера на телефон в Загрузки/Agent.", S({"local_path": STR, "name": STR}, ["local_path"])),
    ("phone_clipboard_get", "Прочитать буфер обмена телефона.", S()),
    ("phone_clipboard_set", "Записать текст в буфер обмена телефона.", S({"text": STR}, ["text"])),
    ("phone_notify", "Показать уведомление на телефоне пользователя.", S({"title": STR, "text": STR}, ["text"])),
    ("phone_battery", "Заряд батареи телефона.", S()),
    ("phone_sms_list", "Последние SMS (только если пользователь включил SMS в приложении).", S({"limit": INT})),
    ("drive_status", "Подключён ли Google Диск (rclone remote gdrive:).", S()),
    ("drive_list", "Список файлов/папок на Google Диске.", S({"path": dict(STR, description="папка, например Документы/2026; пусто = корень")})),
    ("drive_search", "Поиск файлов на Google Диске по части имени.", S({"query": STR, "path": STR}, ["query"])),
    ("drive_upload", "Загрузить файл/папку с сервера на Google Диск.", S({"local_path": STR, "remote_dir": STR}, ["local_path"])),
    ("drive_download", "Скачать файл с Google Диска на сервер.", S({"remote_path": STR, "local_dir": STR}, ["remote_path"])),
    ("drive_link", "Получить ссылку для общего доступа к файлу на Google Диске (создаёт публичную ссылку — только по прямой просьбе пользователя).",
     S({"remote_path": STR}, ["remote_path"])),
]

TIMEOUTS = {"phone_run_shell": 120, "phone_take_photo": 120, "phone_pull_file": 300, "phone_push_file": 300}


def relay_call(tool, args, timeout):
    data = json.dumps({"tool": tool, "args": args, "timeout": timeout}).encode()
    req = urllib.request.Request(RELAY + "/call", data=data, headers={"Content-Type": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=timeout + 30) as r:
        return json.loads(r.read())


def relay_status():
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(RELAY + "/status", timeout=10) as r:
        return json.loads(r.read())


def rclone(*args, timeout=600):
    if not shutil.which("rclone"):
        return False, "rclone не установлен на сервере"
    if not (os.path.exists(RCLONE_CONF) and "[gdrive]" in open(RCLONE_CONF, encoding="utf-8", errors="ignore").read()):
        return False, DRIVE_HINT
    env = dict(os.environ, RCLONE_CONFIG=RCLONE_CONF)
    p = subprocess.run(["rclone", *args], env=env, capture_output=True, text=True, timeout=timeout)
    out = (p.stdout or "") + (("\n" + p.stderr) if p.returncode else "")
    return p.returncode == 0, out[-20000:]


def rpath(p):
    return REMOTE + (p or "").lstrip("/")


def call_tool(name, a):
    if name == "phone_status":
        return True, json.dumps(relay_status(), ensure_ascii=False, indent=1)
    if name.startswith("phone_"):
        tool = name[len("phone_"):]
        t = int(a.get("timeout") or TIMEOUTS.get(name, 90))
        res = relay_call(tool, a, t)
        return bool(res.get("ok")), json.dumps(res, ensure_ascii=False, indent=1)
    if name == "drive_status":
        st = relay_status().get("drive", {})
        return True, json.dumps(st, ensure_ascii=False) + ("" if st.get("configured") else "\n" + DRIVE_HINT)
    if name == "drive_list":
        return rclone("lsf", "--format", "pst", "--max-depth", "1", rpath(a.get("path")), timeout=120)
    if name == "drive_search":
        return rclone("lsf", "-R", "--files-only", "--include", f"*{a['query']}*", "--ignore-case", rpath(a.get("path")), timeout=300)
    if name == "drive_upload":
        src = os.path.expanduser(a["local_path"])
        dst = rpath(a.get("remote_dir") or "Agent")
        if os.path.isdir(src):
            dst = dst.rstrip("/") + "/" + os.path.basename(src.rstrip("/"))
        ok, out = rclone("copy", src, dst, "--stats-one-line", "-v")
        return ok, (f"Загружено в {dst}\n" if ok else "") + out[-3000:]
    if name == "drive_download":
        dst = os.path.expanduser(a.get("local_dir") or "~/drive-downloads")
        os.makedirs(dst, exist_ok=True)
        ok, out = rclone("copy", rpath(a["remote_path"]), dst, "-v")
        return ok, (f"Скачано в {dst}\n" if ok else "") + out[-3000:]
    if name == "drive_link":
        return rclone("link", rpath(a["remote_path"]), timeout=120)
    return False, f"неизвестный инструмент {name}"


def send(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except Exception:
            continue
        mid, method = msg.get("id"), msg.get("method")
        if mid is None:
            continue  # notifications
        try:
            if method == "initialize":
                pv = (msg.get("params") or {}).get("protocolVersion") or "2024-11-05"
                send({"jsonrpc": "2.0", "id": mid, "result": {"protocolVersion": pv, "capabilities": {"tools": {}},
                      "serverInfo": {"name": "agent_tools", "version": "1.0"}}})
            elif method == "tools/list":
                send({"jsonrpc": "2.0", "id": mid, "result": {"tools": [
                    {"name": n, "description": d, "inputSchema": s} for n, d, s in TOOLS]}})
            elif method == "tools/call":
                p = msg.get("params") or {}
                try:
                    ok, text = call_tool(p.get("name"), p.get("arguments") or {})
                except Exception as e:
                    ok, text = False, f"Ошибка: {e}"
                send({"jsonrpc": "2.0", "id": mid, "result": {"content": [{"type": "text", "text": text}], "isError": not ok}})
            elif method == "ping":
                send({"jsonrpc": "2.0", "id": mid, "result": {}})
            else:
                send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": "method not found"}})
        except Exception as e:
            send({"jsonrpc": "2.0", "id": mid, "error": {"code": -32603, "message": str(e)}})


if __name__ == "__main__":
    main()
