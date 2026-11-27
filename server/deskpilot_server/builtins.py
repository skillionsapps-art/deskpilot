"""Built-in server functions that remotes can call with {"builtin": "<name>", "args": {...}}.

Each function receives (ctx, args) and returns JSON-serialisable data (or None).
"""
from __future__ import annotations

import base64
import io
import json
import os
import platform
import shlex
import shutil
import string
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

from .config import SCRIPTS_DIR, current_os

OS = current_os()
REGISTRY: dict = {}


class BuiltinError(Exception):
    pass


def builtin(name):
    def deco(fn):
        REGISTRY[name] = fn
        return fn
    return deco


def _sh(cmd: str, timeout: float = 15, capture: bool = False):
    if capture:
        p = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return {"code": p.returncode, "stdout": p.stdout[-20000:], "stderr": p.stderr[-5000:]}
    subprocess.Popen(cmd, shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return None


def _per_os(spec):
    if isinstance(spec, dict):
        return spec.get(OS) or spec.get("all") or ""
    return spec or ""


def _open_path(target: str):
    if OS == "win":
        os.startfile(target)  # type: ignore[attr-defined]
    elif OS == "mac":
        subprocess.Popen(["open", target])
    else:
        subprocess.Popen(["xdg-open", target])


# ---------------------------------------------------------------- power
_POWER = {
    "shutdown": {"win": "shutdown /s /t 0", "mac": "osascript -e 'tell app \"System Events\" to shut down'", "linux": "systemctl poweroff"},
    "restart": {"win": "shutdown /r /t 0", "mac": "osascript -e 'tell app \"System Events\" to restart'", "linux": "systemctl reboot"},
    "sleep": {"win": "rundll32.exe powrprof.dll,SetSuspendState 0,1,0", "mac": "pmset sleepnow", "linux": "systemctl suspend"},
    "hibernate": {"win": "shutdown /h", "mac": "pmset sleepnow", "linux": "systemctl hibernate"},
    "lock": {"win": "rundll32.exe user32.dll,LockWorkStation", "mac": "pmset displaysleepnow", "linux": "loginctl lock-session"},
    "logoff": {"win": "shutdown /l", "mac": "osascript -e 'tell app \"System Events\" to log out'", "linux": "loginctl terminate-user $USER"},
    "abort": {"win": "shutdown /a", "mac": "", "linux": "shutdown -c"},
}


@builtin("power")
def power(ctx, args):
    op = args.get("op", "")
    if op not in _POWER:
        raise BuiltinError(f"unknown power op {op}")
    delay = int(args.get("delay", 0))
    cmd = _per_os(_POWER[op])
    if not cmd:
        raise BuiltinError(f"{op} not supported on {OS}")
    if delay > 0 and op in ("shutdown", "restart") and OS == "win":
        cmd = cmd.replace("/t 0", f"/t {delay}")
    _sh(cmd)
    return {"ok": True, "op": op}


# ---------------------------------------------------------------- monitor
@builtin("monitor")
def monitor(ctx, args):
    op = args.get("op", "off")
    if OS == "win":
        import ctypes
        HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, SC_SCREENSAVE = 0xFFFF, 0x0112, 0xF170, 0xF140
        user32 = ctypes.windll.user32  # type: ignore[attr-defined]
        if op == "off":
            user32.PostMessageW(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, 2)
        elif op == "on":
            user32.PostMessageW(HWND_BROADCAST, WM_SYSCOMMAND, SC_MONITORPOWER, -1)
            ctx.input.mouse_move(1, 0); ctx.input.mouse_move(-1, 0)
        elif op == "screensaver":
            user32.PostMessageW(HWND_BROADCAST, WM_SYSCOMMAND, SC_SCREENSAVE, 0)
    elif OS == "mac":
        _sh({"off": "pmset displaysleepnow", "on": "caffeinate -u -t 2",
             "screensaver": "open -a ScreenSaverEngine"}.get(op, "pmset displaysleepnow"))
    else:
        _sh({"off": "xset dpms force off", "on": "xset dpms force on",
             "screensaver": "xdg-screensaver activate"}.get(op, "xset dpms force off"))
    return {"ok": True}


# ---------------------------------------------------------------- volume
@builtin("volume")
def volume(ctx, args):
    op = args.get("op", "set")
    if op in ("up", "down", "mute"):
        ctx.input.tap({"up": "volume_up", "down": "volume_down", "mute": "volume_mute"}[op])
        return None
    level = max(0, min(100, int(float(args.get("value", 50)))))
    if OS == "mac":
        _sh(f"osascript -e 'set volume output volume {level}'")
    elif OS == "linux":
        _sh(f"pactl set-sink-volume @DEFAULT_SINK@ {level}% || amixer -q set Master {level}%")
    else:
        try:  # optional dependency
            from ctypes import POINTER, cast
            from comtypes import CLSCTX_ALL  # type: ignore
            from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume  # type: ignore
            dev = AudioUtilities.GetSpeakers()
            iface = dev.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
            cast(iface, POINTER(IAudioEndpointVolume)).SetMasterVolumeLevelScalar(level / 100.0, None)
        except Exception:
            # Fallback: 50 steps of "down" then step up (each key = 2%)
            for _ in range(50):
                ctx.input.tap("volume_down")
            for _ in range(level // 2):
                ctx.input.tap("volume_up")
    return {"level": level}


# ---------------------------------------------------------------- files
def _files_root(ctx) -> Path | None:
    return Path(ctx.config.files_root).expanduser().resolve() if ctx.config.files_root else None


def _check_path(ctx, p: Path) -> Path:
    if not ctx.config.allow_file_manager:
        raise BuiltinError("File Manager is disabled in the server settings")
    p = p.expanduser().resolve()
    root = _files_root(ctx)
    if root and root != p and root not in p.parents:
        raise BuiltinError("path outside the allowed folder")
    return p


@builtin("files.roots")
def files_roots(ctx, args):
    root = _files_root(ctx)
    if root:
        return {"items": [{"name": root.name or str(root), "path": str(root), "dir": True}]}
    items = [{"name": "Home", "path": str(Path.home()), "dir": True}]
    if OS == "win":
        for letter in string.ascii_uppercase:
            d = f"{letter}:\\"
            if os.path.exists(d):
                items.append({"name": d, "path": d, "dir": True})
    else:
        items.append({"name": "/", "path": "/", "dir": True})
        for m in ("/Volumes", "/media", "/mnt"):
            if os.path.isdir(m):
                items.append({"name": m, "path": m, "dir": True})
    return {"items": items}


@builtin("files.list")
def files_list(ctx, args):
    p = _check_path(ctx, Path(args.get("path") or Path.home()))
    if not p.is_dir():
        raise BuiltinError("not a folder")
    items = []
    for e in sorted(p.iterdir(), key=lambda x: (not x.is_dir(), x.name.lower())):
        if e.name.startswith("."):
            continue
        try:
            st = e.stat()
            items.append({"name": e.name, "path": str(e), "dir": e.is_dir(),
                          "size": 0 if e.is_dir() else st.st_size, "mtime": int(st.st_mtime)})
        except OSError:
            continue
        if len(items) >= 1000:
            break
    parent = str(p.parent) if p.parent != p else None
    return {"path": str(p), "parent": parent, "items": items}


@builtin("files.open")
def files_open(ctx, args):
    p = _check_path(ctx, Path(args["path"]))
    _open_path(str(p))
    return {"ok": True}


# ---------------------------------------------------------------- tasks & performance
@builtin("tasks.list")
def tasks_list(ctx, args):
    import psutil
    procs = []
    for pr in psutil.process_iter(["pid", "name", "memory_info", "cpu_percent"]):
        try:
            mi = pr.info["memory_info"]
            procs.append({"pid": pr.info["pid"], "name": pr.info["name"] or "?",
                          "mem": mi.rss if mi else 0, "cpu": pr.info["cpu_percent"] or 0})
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue
    procs.sort(key=lambda x: x["mem"], reverse=True)
    return {"items": procs[: int(args.get("limit", 60))]}


@builtin("tasks.kill")
def tasks_kill(ctx, args):
    import psutil
    psutil.Process(int(args["pid"])).terminate()
    return {"ok": True}


@builtin("performance")
def performance(ctx, args):
    import psutil
    vm = psutil.virtual_memory()
    du = psutil.disk_usage(str(Path.home()))
    net = psutil.net_io_counters()
    batt = psutil.sensors_battery() if hasattr(psutil, "sensors_battery") else None
    return {
        "cpu": psutil.cpu_percent(interval=0.2), "cores": psutil.cpu_count(),
        "ram_used": vm.used, "ram_total": vm.total, "ram_pct": vm.percent,
        "disk_used": du.used, "disk_total": du.total, "disk_pct": du.percent,
        "net_sent": net.bytes_sent, "net_recv": net.bytes_recv,
        "battery": None if not batt else {"pct": batt.percent, "plugged": batt.power_plugged},
        "uptime": int(time.time() - psutil.boot_time()),
    }


# ---------------------------------------------------------------- screen mirroring / pixel colour
@builtin("screen.capture")
def screen_capture(ctx, args):
    try:
        from PIL import ImageGrab
    except ImportError as e:
        raise BuiltinError("install Pillow for screen mirroring") from e
    img = ImageGrab.grab()
    w = int(args.get("width", 960))
    if img.width > w:
        img = img.resize((w, int(img.height * w / img.width)))
    buf = io.BytesIO()
    img.convert("RGB").save(buf, "JPEG", quality=int(args.get("quality", 55)))
    return {"jpeg": base64.b64encode(buf.getvalue()).decode(), "w": img.width, "h": img.height}


@builtin("screen.pixel")
def screen_pixel(ctx, args):
    from PIL import ImageGrab
    x, y = ctx.input.mouse_position()
    r, g, b = ImageGrab.grab(bbox=(int(x), int(y), int(x) + 1, int(y) + 1)).convert("RGB").getpixel((0, 0))
    return {"hex": f"#{r:02X}{g:02X}{b:02X}", "rgb": [r, g, b], "x": x, "y": y}


# ---------------------------------------------------------------- launcher / run / open / scripts
@builtin("launcher.list")
def launcher_list(ctx, args):
    return {"items": [{"name": a["name"], "id": i} for i, a in enumerate(ctx.config.launcher) if a.get(OS)]}


@builtin("launcher.run")
def launcher_run(ctx, args):
    app = ctx.config.launcher[int(args["id"])]
    _sh(app[OS])
    return {"ok": True}


@builtin("run")
def run(ctx, args):
    if not ctx.config.allow_commands:
        raise BuiltinError("Commands are disabled in the server settings")
    cmd = args.get("cmd", "")
    if not cmd:
        raise BuiltinError("empty command")
    return _sh(cmd, timeout=float(args.get("timeout", 20)), capture=bool(args.get("capture", True)))


@builtin("open")
def open_target(ctx, args):
    target = args["target"]
    if target.startswith(("http://", "https://")):
        import webbrowser
        webbrowser.open(target)
    else:
        _open_path(target)
    return {"ok": True}


@builtin("scripts.list")
def scripts_list(ctx, args):
    SCRIPTS_DIR.mkdir(parents=True, exist_ok=True)
    return {"items": [{"name": p.name, "path": str(p)} for p in sorted(SCRIPTS_DIR.iterdir()) if p.is_file()]}


@builtin("scripts.run")
def scripts_run(ctx, args):
    if not ctx.config.allow_commands:
        raise BuiltinError("Commands are disabled in the server settings")
    p = (SCRIPTS_DIR / Path(args["name"]).name).resolve()
    if not p.is_file():
        raise BuiltinError("script not found")
    if p.suffix == ".py":
        cmd = f'"{sys.executable}" "{p}"'
    elif OS == "win":
        cmd = f'"{p}"'
    else:
        cmd = f"sh {shlex.quote(str(p))}" if p.suffix == ".sh" else shlex.quote(str(p))
    return _sh(cmd, timeout=60, capture=True)


@builtin("http")
def http_request(ctx, args):
    req = urllib.request.Request(args["url"], method=args.get("method", "GET").upper(),
                                 data=args.get("body", "").encode() or None,
                                 headers=args.get("headers") or {})
    with urllib.request.urlopen(req, timeout=10) as r:
        return {"status": r.status, "body": r.read(4000).decode("utf-8", "replace")}


@builtin("eject")
def eject(ctx, args):
    if OS == "win":
        import ctypes
        ctypes.windll.winmm.mciSendStringW("set cdaudio door open wait", None, 0, None)  # type: ignore[attr-defined]
    elif OS == "mac":
        _sh("drutil tray eject")
    else:
        _sh("eject")
    return {"ok": True}


@builtin("system.info")
def system_info(ctx, args):
    return {"name": ctx.config.name, "os": OS, "platform": platform.platform(),
            "python": sys.version.split()[0], "input": ctx.input.name}


@builtin("clipboard.get")
def clipboard_get(ctx, args):
    cmd = {"win": "powershell -command Get-Clipboard", "mac": "pbpaste", "linux": "xclip -o -selection clipboard"}[OS]
    return {"text": _sh(cmd, capture=True)["stdout"]}
