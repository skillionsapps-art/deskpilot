"""Builds the downloadable server kits (zip) for the website.

    python kits/build_kits.py [outdir]      -> DeskPilotServer-Windows.zip, -macOS.zip, -Linux.zip, -RaspberryPi.zip

Each kit = the server source + a double-click installer that sets up Python (if needed),
a private environment, shortcuts / app bundle / menu entry, and starts the server.
"""
from __future__ import annotations

import io
import sys
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent      # server/
KITS = ROOT / "kits"
VERSION = "1.0.0"

README = {
    "windows": """DeskPilot Server {v} for Windows 10/11
=====================================

1. Unzip this folder (right-click > Extract All).
2. Double-click "Install DeskPilot Server".
   If Windows shows "Windows protected your PC", click More info > Run anyway.
3. The settings page opens in your browser. Choose a password.
4. If Windows Firewall asks about "pythonw", tick Private networks and click Allow.
5. On your Android phone open DeskPilot - your PC appears automatically.

Python 3.12 is downloaded from python.org automatically if you don't have it.
Remove it any time from Settings > Apps > DeskPilot Server.
""",
    "macos": """DeskPilot Server {v} for macOS 11+
==================================

1. Unzip, then right-click "Install DeskPilot Server.command" > Open > Open
   (first time only - macOS asks because the script is downloaded).
2. Follow the Terminal window. Python is installed from python.org if missing.
3. DeskPilot Server appears in the menu bar and in your Applications folder (~/Applications).
4. Choose a password on the settings page that opens.
5. System Settings > Privacy & Security > Accessibility: switch on DeskPilot Server
   (or "Python") so your phone can move the mouse and type.

To remove: run uninstall.command in ~/Library/Application Support/DeskPilot Server.
""",
    "linux": """DeskPilot Server {v} for Linux (Ubuntu, Debian, Mint, Fedora, Arch...)
====================================================================

1. Unzip, open a terminal in this folder and run:   ./install.sh
   (or right-click install.sh > Run as a Program)
2. DeskPilot Server starts and opens its settings page - choose a password.
3. It's in your app menu afterwards, or run:  deskpilot-server

Background service without a desktop:  ./install.sh --headless
Mouse/keyboard control needs an X11 session (on Ubuntu choose "Ubuntu on Xorg" at login).
To remove: ~/.local/share/deskpilot-server/uninstall.sh
""",
    "pi": """DeskPilot Server {v} for Raspberry Pi (Pi 3/4/5, Raspberry Pi OS)
===============================================================

1. Copy this folder to the Pi (or download it there), then in a terminal:
       unzip DeskPilotServer-RaspberryPi.zip
       cd "DeskPilot Server for Raspberry Pi"
       ./install.sh --headless
2. Type a password when asked. The server now starts automatically at every boot.
3. On your Android phone open DeskPilot - the Pi appears automatically.

With a desktop attached you can also run ./install.sh (no --headless) for the tray app.
Mouse/keyboard control needs X11:  sudo raspi-config > Advanced Options > Wayland > X11.
To remove: ~/.local/share/deskpilot-server/uninstall.sh
""",
}

KIT_FILES = {
    "windows": ["Install DeskPilot Server.cmd", "install.ps1", "uninstall.ps1"],
    "macos": ["Install DeskPilot Server.command", "uninstall.command"],
    "linux": ["install.sh", "uninstall.sh"],
}
FOLDER = {"windows": "DeskPilot Server for Windows", "macos": "DeskPilot Server for Mac",
          "linux": "DeskPilot Server for Linux", "pi": "DeskPilot Server for Raspberry Pi"}
ZIPNAME = {"windows": "DeskPilotServer-Windows.zip", "macos": "DeskPilotServer-macOS.zip",
           "linux": "DeskPilotServer-Linux.zip", "pi": "DeskPilotServer-RaspberryPi.zip"}


def server_files():
    pkg = ROOT / "deskpilot_server"
    for p in sorted(pkg.rglob("*")):
        if p.is_file() and "__pycache__" not in p.parts and p.suffix != ".pyc":
            yield p, Path("server") / p.relative_to(ROOT)
    for name in ("pyproject.toml", "README.md"):
        yield ROOT / name, Path("server") / name


def icon_bytes(kind: str) -> tuple[str, bytes]:
    src = Image.open(ROOT / "deskpilot_server" / "assets" / "icon.png").convert("RGBA").resize((1024, 1024), Image.LANCZOS)
    buf = io.BytesIO()
    if kind == "windows":
        src.save(buf, "ICO", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
        return "deskpilot.ico", buf.getvalue()
    if kind == "macos":
        src.save(buf, "ICNS")
        return "deskpilot.icns", buf.getvalue()
    src.resize((256, 256), Image.LANCZOS).save(buf, "PNG")
    return "deskpilot-server.png", buf.getvalue()


def add(z: zipfile.ZipFile, arc: str, data: bytes, executable=False):
    info = zipfile.ZipInfo(arc, date_time=(2026, 11, 27, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3  # unix, so permission bits survive on macOS/Linux
    info.external_attr = ((0o755 if executable else 0o644) | 0o100000) << 16
    z.writestr(info, data)


def build(kind: str, out: Path) -> Path:
    scripts = "linux" if kind == "pi" else kind
    folder = FOLDER[kind]
    target = out / ZIPNAME[kind]
    with zipfile.ZipFile(target, "w", compresslevel=9) as z:
        for name in KIT_FILES[scripts]:
            data = (KITS / scripts / name).read_bytes()
            if kind == "windows":   # cmd/ps1 want CRLF
                data = data.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
            add(z, f"{folder}/{name}", data, executable=name.endswith((".sh", ".command")))
        readme = README[kind].format(v=VERSION).encode()
        if kind == "windows":
            readme = readme.replace(b"\n", b"\r\n")
        add(z, f"{folder}/README.txt", readme)
        iname, idata = icon_bytes(scripts)
        add(z, f"{folder}/{iname}", idata)
        for src, rel in server_files():
            add(z, f"{folder}/{rel.as_posix()}", src.read_bytes())
    return target


if __name__ == "__main__":
    out = Path(sys.argv[1] if len(sys.argv) > 1 else ROOT / "dist" / "kits")
    out.mkdir(parents=True, exist_ok=True)
    for k in ("windows", "macos", "linux", "pi"):
        p = build(k, out)
        print(f"{p.name:34s} {p.stat().st_size / 1024:7.0f} KB")
