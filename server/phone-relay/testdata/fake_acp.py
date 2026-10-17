#!/usr/bin/env python3
"""Поддельный ACP-агент для тестов murf_bots (имитирует hermes-acp / grok agent stdio)."""
import json, os, sys, threading, time, uuid

out_lock = threading.Lock()
pending = {}
cancelled = threading.Event()
sessions = set()
LOG = os.environ.get("FAKE_ACP_LOG")


def send(o):
    with out_lock:
        sys.stdout.write(json.dumps(o) + "\n")
        sys.stdout.flush()


def upd(sid, u):
    send({"jsonrpc": "2.0", "method": "session/update", "params": {"sessionId": sid, "update": u}})


def chunk(sid, text, kind="agent_message_chunk", meta=None):
    u = {"sessionUpdate": kind, "content": {"type": "text", "text": text}}
    if meta:
        u["_meta"] = meta
    upd(sid, u)


nid = [1000]


def ask_permission(sid, title):
    nid[0] += 1
    rid = nid[0]
    ev = threading.Event()
    pending[rid] = {"ev": ev}
    send({"jsonrpc": "2.0", "id": rid, "method": "session/request_permission", "params": {
        "sessionId": sid, "toolCall": {"toolCallId": "perm1", "title": title, "rawInput": {"command": "rm -rf build"}},
        "options": [{"optionId": "allow-once", "name": "Allow once", "kind": "allow_once"},
                    {"optionId": "reject-once", "name": "Reject", "kind": "reject_once"}]}})
    ev.wait(60)
    return pending.pop(rid, {}).get("result")


def do_prompt(rid, params):
    sid = params["sessionId"]
    text = "".join(b.get("text", "") for b in params.get("prompt", []))
    if LOG:
        with open(LOG, "a") as f:
            f.write(json.dumps({"argv": sys.argv, "cwd": os.getcwd(), "prompt": text,
                                "hermes_home": os.environ.get("HERMES_HOME")}) + "\n")
    cancelled.clear()
    if "SLOW" in text:
        for _ in range(600):
            if cancelled.is_set():
                send({"jsonrpc": "2.0", "id": rid, "result": {"stopReason": "cancelled"}})
                return
            time.sleep(0.05)
    if "PERM" in text:
        upd(sid, {"sessionUpdate": "tool_call", "toolCallId": "perm1", "title": "Run rm -rf build", "kind": "execute",
                  "status": "pending", "rawInput": {"command": "rm -rf build"}})
        res = ask_permission(sid, "Run rm -rf build")
        outcome = (res or {}).get("outcome", {})
        ok = outcome.get("outcome") == "selected" and outcome.get("optionId") == "allow-once"
        upd(sid, {"sessionUpdate": "tool_call_update", "toolCallId": "perm1",
                  "status": "completed" if ok else "failed",
                  "content": [{"type": "content", "content": {"type": "text", "text": "removed" if ok else "denied"}}]})
        chunk(sid, "Разрешено." if ok else "Отклонено.")
        send({"jsonrpc": "2.0", "id": rid, "result": {"stopReason": "end_turn"}})
        return
    chunk(sid, "думаю", "agent_thought_chunk")
    upd(sid, {"sessionUpdate": "plan", "entries": [{"content": "шаг 1", "status": "completed", "priority": "high"}]})
    upd(sid, {"sessionUpdate": "tool_call", "toolCallId": "t1", "title": "Read README.md", "kind": "read",
              "status": "in_progress", "rawInput": {"path": "README.md"}})
    upd(sid, {"sessionUpdate": "tool_call_update", "toolCallId": "t1", "status": "completed",
              "content": [{"type": "content", "content": {"type": "text", "text": "# readme"}}]})
    upd(sid, {"sessionUpdate": "tool_call", "toolCallId": "d1", "title": "delegate_task: собрать отчёт",
              "kind": "other", "status": "in_progress", "rawInput": {"goal": "собрать отчёт"}})
    chunk(sid, "подагент работает", meta={"parentToolCallId": "d1"})
    upd(sid, {"sessionUpdate": "tool_call_update", "toolCallId": "d1", "status": "completed"})
    for w in ["При", "вет", ", ", "мир"]:
        chunk(sid, w)
    if "HANDOFF" in text:
        chunk(sid, "\n@@handoff Coder: напиши тест\n")
    send({"jsonrpc": "2.0", "id": rid, "result": {"stopReason": "end_turn"}})


def main():
    for line in sys.stdin:
        try:
            m = json.loads(line)
        except ValueError:
            continue
        if "method" not in m and m.get("id") in pending:
            pending[m["id"]]["result"] = m.get("result")
            pending[m["id"]]["ev"].set()
            continue
        meth, rid, p = m.get("method"), m.get("id"), m.get("params") or {}
        if meth == "initialize":
            send({"jsonrpc": "2.0", "id": rid, "result": {"protocolVersion": 1, "agentCapabilities": {"loadSession": True}}})
        elif meth == "session/new":
            sid = str(uuid.uuid4())
            sessions.add(sid)
            send({"jsonrpc": "2.0", "id": rid, "result": {"sessionId": sid, "modes": {
                "availableModes": [{"id": "default"}, {"id": "plan"}], "currentModeId": "default"}}})
        elif meth == "session/load":
            upd(p["sessionId"], {"sessionUpdate": "user_message_chunk", "content": {"type": "text", "text": "old"}})
            chunk(p["sessionId"], "СТАРАЯ ИСТОРИЯ")
            send({"jsonrpc": "2.0", "id": rid, "result": {}})
        elif meth == "session/set_mode":
            if LOG:
                with open(LOG, "a") as f:
                    f.write(json.dumps({"set_mode": p.get("modeId")}) + "\n")
            send({"jsonrpc": "2.0", "id": rid, "result": {}})
        elif meth == "session/prompt":
            threading.Thread(target=do_prompt, args=(rid, p), daemon=True).start()
        elif meth == "session/cancel":
            cancelled.set()
        elif rid is not None:
            send({"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "nope"}})


main()
