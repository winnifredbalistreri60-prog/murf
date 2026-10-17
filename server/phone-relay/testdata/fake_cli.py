#!/usr/bin/env python3
"""Поддельные codex / claude / grok / hermes для тестов murf_bots. Имя CLI — в FAKE_CLI."""
import json, os, sys

name = os.environ.get("FAKE_CLI") or os.path.basename(sys.argv[0])
args = sys.argv[1:]
here = os.path.dirname(os.path.abspath(__file__))
LOG = os.environ.get("FAKE_CLI_LOG")


def log(obj):
    if LOG:
        with open(LOG, "a") as f:
            f.write(json.dumps(obj) + "\n")


def out(o):
    print(json.dumps(o), flush=True)


if args[:1] in (["--version"], ["-V"]):
    print({"codex": "codex-cli 0.0-test", "claude": "9.9.9 (Claude Code)", "grok": "grok 0.0-test",
           "hermes": "Hermes Agent v0.0-test"}.get(name, name + " 0"))
    sys.exit(0)
if name in ("grok", "hermes-acp") and (name == "hermes-acp" or args[:1] == ["agent"]):
    os.execv(sys.executable, [sys.executable, os.path.join(here, "fake_acp.py")] + args)
if name == "codex":
    if args[:2] == ["login", "status"]:
        print("Logged in using ChatGPT")
        sys.exit(0)
    prompt = sys.stdin.read()
    log({"cli": "codex", "argv": args, "cwd": os.getcwd(), "prompt": prompt})
    if "resume" not in args:
        out({"type": "thread.started", "thread_id": "0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b"})
    out({"type": "turn.started"})
    out({"type": "item.started", "item": {"id": "i1", "type": "command_execution", "command": "ls", "status": "in_progress"}})
    out({"type": "item.completed", "item": {"id": "i1", "type": "command_execution", "command": "ls",
                                            "aggregated_output": "a.txt\n", "exit_code": 0, "status": "completed"}})
    out({"type": "item.completed", "item": {"id": "i2", "type": "agent_message", "text": "codex ok"}})
    out({"type": "turn.completed", "usage": {"input_tokens": 1, "output_tokens": 1}})
    sys.exit(0)
if name == "claude":
    if args[:2] == ["auth", "status"]:
        out({"loggedIn": True, "authMethod": "claude.ai", "email": "x@example.com"})
        sys.exit(0)
    prompt = sys.stdin.read()
    log({"cli": "claude", "argv": args, "cwd": os.getcwd(), "prompt": prompt})
    out({"type": "system", "subtype": "init", "session_id": "cs-1"})
    out({"type": "assistant", "parent_tool_use_id": None, "message": {"content": [
        {"type": "tool_use", "id": "tu1", "name": "Task", "input": {"description": "исследовать код", "prompt": "..."}}]}})
    out({"type": "assistant", "parent_tool_use_id": "tu1", "message": {"content": [
        {"type": "tool_use", "id": "tu2", "name": "Read", "input": {"file_path": "a.py"}}]}})
    out({"type": "user", "parent_tool_use_id": "tu1", "message": {"content": [
        {"type": "tool_result", "tool_use_id": "tu2", "content": "print(1)"}]}})
    out({"type": "user", "parent_tool_use_id": None, "message": {"content": [
        {"type": "tool_result", "tool_use_id": "tu1", "content": [{"type": "text", "text": "готово"}]}]}})
    out({"type": "assistant", "parent_tool_use_id": None, "message": {"content": [{"type": "text", "text": "claude ok"}]}})
    out({"type": "result", "subtype": "success", "is_error": False, "result": "claude ok",
         "permission_denials": [{"tool_name": "Bash"}]})
    sys.exit(0)
print("unknown", name, args, file=sys.stderr)
sys.exit(2)
