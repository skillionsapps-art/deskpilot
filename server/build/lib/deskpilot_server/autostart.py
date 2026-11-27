"""'Start DeskPilot when I log in' for Windows (registry Run key), macOS (LaunchAgent) and Linux (XDG autostart)."""
from __future__ import annotations

import os
import plistlib
import sys
from pathlib import Path

from .config import current_os

APP_ID = "com.skillions.deskpilot.server"
OS = current_os()


def launch_command() -> list[str]:
    if getattr(sys, "frozen", False):
        return [sys.executable, "--minimized"]
    return [sys.executable, "-m", "deskpilot_server", "--minimized"]


def _mac_plist() -> Path:
    return Path.home() / "Library" / "LaunchAgents" / f"{APP_ID}.plist"


def _linux_desktop() -> Path:
    base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return base / "autostart" / "deskpilot-server.desktop"


def is_enabled() -> bool:
    try:
        if OS == "win":
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run") as k:
                winreg.QueryValueEx(k, "DeskPilot Server")
                return True
        if OS == "mac":
            return _mac_plist().exists()
        return _linux_desktop().exists()
    except OSError:
        return False


def set_enabled(on: bool) -> None:
    cmd = launch_command()
    if OS == "win":
        import subprocess
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run", 0, winreg.KEY_SET_VALUE) as k:
            if on:
                winreg.SetValueEx(k, "DeskPilot Server", 0, winreg.REG_SZ, subprocess.list2cmdline(cmd))
            else:
                try:
                    winreg.DeleteValue(k, "DeskPilot Server")
                except FileNotFoundError:
                    pass
    elif OS == "mac":
        p = _mac_plist()
        if on:
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(plistlib.dumps({"Label": APP_ID, "ProgramArguments": cmd, "RunAtLoad": True,
                                          "ProcessType": "Interactive"}))
        else:
            p.unlink(missing_ok=True)
    else:
        p = _linux_desktop()
        if on:
            p.parent.mkdir(parents=True, exist_ok=True)
            exec_line = " ".join(f'"{c}"' if " " in c else c for c in cmd)
            p.write_text("[Desktop Entry]\nType=Application\nName=DeskPilot Server\n"
                         f"Exec={exec_line}\nIcon=deskpilot-server\nX-GNOME-Autostart-enabled=true\n", "utf-8")
        else:
            p.unlink(missing_ok=True)
