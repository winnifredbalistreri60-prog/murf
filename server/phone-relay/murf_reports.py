#!/usr/bin/env python3
"""MURF «Сообщить о проблеме»: приём отчётов из приложения.

Отчёт (описание пользователя + журнал приложения + сведения об устройстве) сохраняется в
~/agent-phone/reports/<время>-<id>.json и .md (права 600), строка добавляется в INDEX.jsonl.
Отсюда их читает бот «MURF Отзывы» (или любой агент Hermes): `murf reports`, `murf report <id>`.
Никуда наружу (GitHub и т. п.) отчёты не отправляются.

Секреты вычищаются повторно на сервере (приложение уже чистит перед отправкой):
токены/ключи/пароли, cookie, Bearer, адреса/хосты/IP, e-mail.
"""
import json, os, re, secrets, threading, time

HOME = os.path.expanduser("~")
REPORTS_DIR = os.environ.get("MURF_REPORTS_DIR", os.path.join(HOME, "agent-phone", "reports"))
MAX_LOG = 400 * 1024
MAX_TEXT = 8000
RATE_N, RATE_WINDOW = 10, 3600
_rate = []
_lock = threading.Lock()


class ReportError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status, self.message = status, message


_PATTERNS = [
    (re.compile(r"(?i)\b(authorization|cookie|set-cookie|x-api-key|proxy-authorization)\s*[:=]\s*[^\n]+"), r"\1: <скрыто>"),
    (re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{8,}"), "Bearer <скрыто>"),
    (re.compile(r"(?i)\b([\w.-]*(?:pass(?:word|wd)?|secret|token|api[_-]?key|session|sid|auth|key|pin|otp|пароль|парол\w*|токен|ключ|секрет|код))(\"?\s*[:=]\s*\"?)([^\s\",;&]+)"), r"\1\2<скрыто>"),
    (re.compile(r"\b(?:sk|pk|rk|ghp|gho|ghs|ghu|github_pat|xox[abpr]|glpat|AKIA)[-_A-Za-z0-9]{12,}\b"), "<ключ>"),
    (re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b"), "<jwt>"),
    (re.compile(r"\b[0-9a-fA-F]{32,}\b"), "<hex>"),
    (re.compile(r"\b[A-Za-z0-9+/_-]{40,}={0,2}"), "<b64>"),
    (re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+"), "<email>"),
    (re.compile(r"(?i)\b([a-z][a-z0-9+.-]*://)(?:[^\s/@]+@)?[^\s/:?#]+"), r"\1<host>"),
    (re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}(?::\d+)?\b"), "<ip>"),
    (re.compile(r"(?i)\b(?:[a-z0-9-]+\.)+(?:ru|com|net|org|io|dev|app|me|su|рф|info|xyz|cloud|online|site|pro)\b"), "<host>"),
]


def redact(text):
    text = str(text or "")
    for rx, repl in _PATTERNS:
        text = rx.sub(repl, text)
    return text


def _clip(s, n):
    s = str(s or "")
    return s if len(s) <= n else s[-n:]


def save_report(j, client_ip=""):
    now = time.time()
    with _lock:
        _rate[:] = [t for t in _rate if now - t < RATE_WINDOW]
        if len(_rate) >= RATE_N:
            raise ReportError(429, "слишком много отчётов, попробуйте позже")
        _rate.append(now)
    desc = redact(_clip(j.get("description"), MAX_TEXT)).strip()
    log = redact(_clip(j.get("log"), MAX_LOG))
    if not desc and not log.strip():
        raise ReportError(400, "пустой отчёт")
    dev = j.get("device") if isinstance(j.get("device"), dict) else {}
    device = {k: redact(str(dev.get(k, ""))[:120]) for k in ("app_version", "version_code", "model", "android", "sdk", "locale", "screen", "server_kind")}
    rid = time.strftime("%Y%m%d-%H%M%S", time.localtime(now)) + "-" + secrets.token_hex(3)
    rep = {"id": rid, "created": time.strftime("%Y-%m-%dT%H:%M:%S%z", time.localtime(now)),
           "category": re.sub(r"[^\w-]", "", str(j.get("category", "bug")))[:24] or "bug",
           "description": desc, "device": device, "log": log, "status": "new"}
    os.makedirs(REPORTS_DIR, mode=0o700, exist_ok=True)
    base = os.path.join(REPORTS_DIR, rid)
    old = os.umask(0o077)
    try:
        with open(base + ".json", "w", encoding="utf-8") as f:
            json.dump(rep, f, ensure_ascii=False, indent=1)
        with open(base + ".md", "w", encoding="utf-8") as f:
            f.write(f"# Отчёт MURF {rid}\n\n- Время: {rep['created']}\n- Тип: {rep['category']}\n")
            for k, v in device.items():
                if v:
                    f.write(f"- {k}: {v}\n")
            f.write(f"\n## Описание\n\n{desc or '(нет)'}\n\n## Журнал приложения\n\n```\n{log}\n```\n")
        with open(os.path.join(REPORTS_DIR, "INDEX.jsonl"), "a", encoding="utf-8") as f:
            f.write(json.dumps({"id": rid, "created": rep["created"], "category": rep["category"],
                                "app_version": device.get("app_version"), "summary": desc[:140]}, ensure_ascii=False) + "\n")
        readme = os.path.join(REPORTS_DIR, "README.md")
        if not os.path.exists(readme):
            with open(readme, "w", encoding="utf-8") as f:
                f.write("# Отчёты MURF («Сообщить о проблеме»)\n\nКаждый отчёт: <id>.md (читать) и <id>.json (данные).\n"
                        "Список: INDEX.jsonl или `murf reports`. Секреты и адреса уже вычищены.\n"
                        "Бот «MURF Отзывы»: разобрать новые отчёты, найти причину, подготовить исправление;\n"
                        "публикация (GitHub, релизы) — только после согласия владельца.\n")
    finally:
        os.umask(old)
    return {"ok": True, "id": rid, "redacted": True}


def list_reports(limit=20):
    p = os.path.join(REPORTS_DIR, "INDEX.jsonl")
    if not os.path.exists(p):
        return []
    with open(p, encoding="utf-8") as f:
        rows = [json.loads(l) for l in f if l.strip()]
    return rows[-limit:]
