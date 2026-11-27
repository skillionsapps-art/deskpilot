"""One command to build the DeskPilot Server installer for the current OS.

    cd server
    pip install -r packaging/requirements-build.txt
    python packaging/build.py

Results land in server/dist/release/:
  Windows : DeskPilotServer-Setup-Windows-x64.exe (+ portable .zip)   needs Inno Setup 6
  macOS   : DeskPilotServer-macOS-AppleSilicon.dmg | -Intel.dmg
  Linux   : deskpilot-server-linux-x64.deb / -arm64.deb (Raspberry Pi) + portable .tar.gz
"""
from __future__ import annotations

import os
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent          # server/
PKG = ROOT / "packaging"
DIST = ROOT / "dist"
RELEASE = DIST / "release"
ICONS = ROOT / "build" / "icons"
VERSION = re.search(r'__version__\s*=\s*"([^"]+)"', (ROOT / "deskpilot_server" / "__init__.py").read_text()).group(1)
ARCH = "arm64" if platform.machine().lower() in ("arm64", "aarch64") else "x64"


def run(cmd, **kw):
    print("+", " ".join(map(str, cmd)), flush=True)
    subprocess.run(cmd, check=True, cwd=ROOT, **kw)


def make_icons():
    from PIL import Image
    ICONS.mkdir(parents=True, exist_ok=True)
    src = Image.open(ROOT / "deskpilot_server" / "assets" / "icon.png").convert("RGBA")
    big = src.resize((1024, 1024), Image.LANCZOS)
    big.resize((256, 256), Image.LANCZOS).save(ICONS / "deskpilot-256.png")
    big.save(ICONS / "deskpilot.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    big.save(ICONS / "deskpilot.icns")


def pyinstaller():
    for d in (DIST / "DeskPilotServer", DIST / "DeskPilot Server.app"):
        shutil.rmtree(d, ignore_errors=True)
    run([sys.executable, "-m", "PyInstaller", str(PKG / "deskpilot.spec"), "--noconfirm",
         "--distpath", str(DIST), "--workpath", str(ROOT / "build" / "pyi")])


def find_iscc() -> str | None:
    for c in (shutil.which("iscc"), shutil.which("ISCC"),
              r"C:\Program Files (x86)\Inno Setup 6\ISCC.exe", r"C:\Program Files\Inno Setup 6\ISCC.exe"):
        if c and Path(c).exists():
            return c
    return None


def build_windows():
    shutil.make_archive(str(RELEASE / "DeskPilotServer-Windows-x64-portable"), "zip", DIST, "DeskPilotServer")
    iscc = find_iscc()
    if not iscc:
        print("!! Inno Setup 6 not found - only the portable zip was built. Install: choco install innosetup")
        return
    run([iscc, f"/DAppVersion={VERSION}", str(PKG / "windows" / "installer.iss")])


def build_macos():
    name = "DeskPilotServer-macOS-AppleSilicon.dmg" if ARCH == "arm64" else "DeskPilotServer-macOS-Intel.dmg"
    run(["bash", str(PKG / "macos" / "build_dmg.sh"), name])


def build_linux():
    run(["bash", str(PKG / "linux" / "build_deb.sh"), VERSION, "arm64" if ARCH == "arm64" else "amd64", ARCH])


def main():
    RELEASE.mkdir(parents=True, exist_ok=True)
    print(f"DeskPilot Server {VERSION} - {sys.platform} {ARCH}")
    make_icons()
    pyinstaller()
    {"win32": build_windows, "darwin": build_macos}.get(sys.platform, build_linux)()
    print("\nRelease files:")
    for f in sorted(RELEASE.iterdir()):
        print(f"  {f.name}  ({f.stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    os.environ.setdefault("PYTHONHASHSEED", "0")
    main()
