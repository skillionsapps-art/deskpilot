# PyInstaller spec – builds DeskPilot Server for the OS it runs on.
#   pyinstaller packaging/deskpilot.spec --noconfirm      (run from server/)
# Windows -> dist/DeskPilotServer/DeskPilotServer.exe (windowed)
# macOS   -> dist/DeskPilot Server.app                  (menu-bar app, no Dock icon)
# Linux   -> dist/DeskPilotServer/DeskPilotServer
import os
import sys

ROOT = os.path.abspath(os.path.join(SPECPATH, ".."))
ICONS = os.path.join(ROOT, "build", "icons")
sys.path.insert(0, ROOT)
from deskpilot_server import __version__  # noqa: E402

hidden = ["PIL.ImageGrab", "pystray", "psutil"]
if sys.platform == "win32":
    hidden += ["pynput.keyboard._win32", "pynput.mouse._win32", "pystray._win32", "pycaw.pycaw", "comtypes"]
    icon = os.path.join(ICONS, "deskpilot.ico")
elif sys.platform == "darwin":
    hidden += ["pynput.keyboard._darwin", "pynput.mouse._darwin", "pystray._darwin"]
    icon = os.path.join(ICONS, "deskpilot.icns")
else:
    hidden += ["pynput.keyboard._xorg", "pynput.mouse._xorg", "pynput._util.xorg", "pystray._xorg", "Xlib"]
    icon = None

a = Analysis(
    [os.path.join(SPECPATH, "launcher.py")],
    pathex=[ROOT],
    datas=[
        (os.path.join(ROOT, "deskpilot_server", "remotes"), "deskpilot_server/remotes"),
        (os.path.join(ROOT, "deskpilot_server", "assets"), "deskpilot_server/assets"),
    ],
    hiddenimports=hidden,
    excludes=["tkinter", "unittest", "pydoc", "test", "gi"],
    noarchive=False,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz, a.scripts, [],
    exclude_binaries=True,
    name="DeskPilotServer",
    console=sys.platform.startswith("linux"),  # Windows/macOS: no console window
    icon=icon,
    upx=False,
    argv_emulation=False,
    target_arch=None,
)
coll = COLLECT(exe, a.binaries, a.datas, name="DeskPilotServer", upx=False)

if sys.platform == "darwin":
    app = BUNDLE(
        coll,
        name="DeskPilot Server.app",
        icon=icon,
        bundle_identifier="com.skillions.deskpilot.server",
        version=__version__,
        info_plist={
            "CFBundleName": "DeskPilot Server",
            "CFBundleDisplayName": "DeskPilot Server",
            "CFBundleShortVersionString": __version__,
            "CFBundleVersion": __version__,
            "LSUIElement": True,                      # lives in the menu bar
            "LSMinimumSystemVersion": "11.0",
            "NSHighResolutionCapable": True,
            "NSAppleEventsUsageDescription": "DeskPilot controls media and power on this Mac when you use your phone.",
        },
    )
