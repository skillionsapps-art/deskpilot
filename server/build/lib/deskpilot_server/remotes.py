"""Loads remote definitions (JSON) and executes their actions.

Remote file format
------------------
{
  "id": "vlc", "name": "VLC", "category": "Video", "icon": "video",
  "platforms": ["win", "mac", "linux"], "tier": "free" | "full",
  "layout": [ <control>, ... ],                 # rendered by the Android app
  "actions": { "<action-id>": <action>, ... }
}

<action>   = {"do": [<step>...], "win": [...], "mac": [...], "linux": [...]}   (OS lists override "do")
             or simply a list of steps.
<step>     = {"key": "space"} | {"keys": ["primary","t"]} | {"type": "text {value}"}
           | {"down": "alt"} | {"up": "alt"}
           | {"mouse": "click"|"double"|"right"|"middle"|"down"|"up"|"move"|"scroll", ...}
           | {"builtin": "power", "args": {"op": "sleep"}} | {"run": "cmd" | {"win":..}}
           | {"open": "https://..."} | {"delay": 150}

Strings may contain {placeholders} that are filled from the request args
(e.g. a slider sends {"value": 42}, a list item sends its own fields).
"""
from __future__ import annotations

import json
import logging
import re
import time
from pathlib import Path

from . import builtins as B
from .config import USER_REMOTES_DIR, current_os

log = logging.getLogger("deskpilot.remotes")
OS = current_os()
BUNDLED_DIR = Path(__file__).parent / "remotes"
_PLACEHOLDER = re.compile(r"\{(\w+)\}")


class ActionError(Exception):
    pass


def _fill(value, args: dict):
    if isinstance(value, str):
        def rep(m):
            return str(args.get(m.group(1), m.group(0)))
        full = _PLACEHOLDER.fullmatch(value)
        if full and full.group(1) in args:      # keep non-string types intact
            return args[full.group(1)]
        return _PLACEHOLDER.sub(rep, value)
    if isinstance(value, list):
        return [_fill(v, args) for v in value]
    if isinstance(value, dict):
        return {k: _fill(v, args) for k, v in value.items()}
    return value


class RemoteRegistry:
    def __init__(self):
        self.remotes: dict[str, dict] = {}
        self.reload()

    def reload(self):
        self.remotes.clear()
        for folder in (BUNDLED_DIR, USER_REMOTES_DIR):
            if not folder.is_dir():
                continue
            for f in sorted(folder.glob("*.json")):
                try:
                    r = json.loads(f.read_text("utf-8"))
                    r.setdefault("platforms", ["win", "mac", "linux"])
                    r.setdefault("category", "Other")
                    r.setdefault("tier", "free")
                    self.remotes[r["id"]] = r
                except Exception as e:
                    log.error("bad remote %s: %s", f, e)
        log.info("loaded %d remotes", len(self.remotes))

    def summaries(self):
        out = []
        for r in self.remotes.values():
            out.append({k: r.get(k) for k in ("id", "name", "category", "icon", "platforms", "tier", "description")}
                       | {"supported": OS in r["platforms"]})
        return sorted(out, key=lambda x: (x["category"], x["name"].lower()))

    def get(self, rid: str) -> dict:
        if rid not in self.remotes:
            raise ActionError(f"unknown remote {rid}")
        r = self.remotes[rid]
        return {k: v for k, v in r.items() if k != "actions"} | {"supported": OS in r["platforms"]}

    def steps_for(self, rid: str, aid: str) -> list:
        r = self.remotes.get(rid)
        if not r:
            raise ActionError(f"unknown remote {rid}")
        act = r.get("actions", {}).get(aid)
        if act is None:
            raise ActionError(f"unknown action {rid}.{aid}")
        if isinstance(act, list):
            return act
        return act.get(OS) or act.get("do") or []


class Executor:
    def __init__(self, ctx):
        self.ctx = ctx

    def run_steps(self, steps: list, args: dict | None = None):
        args = args or {}
        result = None
        for raw in steps:
            step = _fill(raw, args)
            r = self._step(step)
            if r is not None:
                result = r
        return result

    def _step(self, s: dict):
        inp = self.ctx.input
        if "key" in s:
            for _ in range(int(s.get("repeat", 1))):
                inp.tap(s["key"])
        elif "keys" in s:
            inp.combo(list(s["keys"]))
        elif "type" in s:
            text = str(s["type"])
            if text:
                inp.type_text(text)
        elif "down" in s:
            inp.key_down(s["down"])
        elif "up" in s:
            inp.key_up(s["up"])
        elif "mouse" in s:
            op = s["mouse"]
            if op == "move":
                inp.mouse_move(int(s.get("dx", 0)), int(s.get("dy", 0)))
            elif op == "scroll":
                inp.mouse_scroll(int(s.get("dx", 0)), int(s.get("dy", 0)))
            elif op == "click":
                inp.mouse_click(s.get("button", "left"), int(s.get("count", 1)))
            elif op == "double":
                inp.mouse_click("left", 2)
            elif op == "right":
                inp.mouse_click("right", 1)
            elif op == "middle":
                inp.mouse_click("middle", 1)
            elif op == "down":
                inp.mouse_down(s.get("button", "left"))
            elif op == "up":
                inp.mouse_up(s.get("button", "left"))
            else:
                raise ActionError(f"bad mouse op {op}")
        elif "builtin" in s:
            fn = B.REGISTRY.get(s["builtin"])
            if not fn:
                raise ActionError(f"unknown builtin {s['builtin']}")
            return fn(self.ctx, s.get("args", {}))
        elif "run" in s:
            cmd = B._per_os(s["run"])
            if cmd:
                return B.REGISTRY["run"](self.ctx, {"cmd": cmd, "capture": s.get("capture", False)})
        elif "open" in s:
            return B.REGISTRY["open"](self.ctx, {"target": B._per_os(s["open"])})
        elif "delay" in s:
            time.sleep(min(float(s["delay"]), 5000) / 1000.0)
        else:
            raise ActionError(f"bad step {s}")
        return None
