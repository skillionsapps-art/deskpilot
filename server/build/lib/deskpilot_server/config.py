"""Server configuration stored at ~/.deskpilot/config.json."""
from __future__ import annotations

import json
import os
import platform
import socket
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path

CONFIG_DIR = Path(os.environ.get("DESKPILOT_HOME", Path.home() / ".deskpilot"))
CONFIG_FILE = CONFIG_DIR / "config.json"
USER_REMOTES_DIR = CONFIG_DIR / "remotes"
SCRIPTS_DIR = CONFIG_DIR / "scripts"


def current_os() -> str:
    s = platform.system().lower()
    if s.startswith("win"):
        return "win"
    if s == "darwin":
        return "mac"
    return "linux"


def mac_address() -> str:
    node = uuid.getnode()
    return ":".join(f"{(node >> s) & 0xFF:02x}" for s in range(40, -1, -8))


@dataclass
class LauncherApp:
    name: str
    win: str = ""
    mac: str = ""
    linux: str = ""


@dataclass
class Config:
    server_id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    name: str = field(default_factory=socket.gethostname)
    password: str = ""                 # empty = encrypted with an empty password (no real protection)
    port: int = 9512
    discovery_port: int = 9511
    web_port: int = 9513               # local settings page (127.0.0.1 only)
    discovery: bool = True
    bluetooth: bool = False            # experimental RFCOMM transport
    bluetooth_channel: int = 4
    allow_commands: bool = True        # Command / Custom Run / terminal remotes
    allow_file_manager: bool = True
    files_root: str = ""               # optional: restrict File Manager to this folder
    input_backend: str = "auto"        # auto | pynput | dryrun
    launcher: list = field(default_factory=lambda: [
        asdict(LauncherApp("Browser", win="start https://www.google.com", mac="open https://www.google.com", linux="xdg-open https://www.google.com")),
        asdict(LauncherApp("VLC", win="start vlc", mac="open -a VLC", linux="vlc")),
        asdict(LauncherApp("Spotify", win="start spotify:", mac="open -a Spotify", linux="spotify")),
        asdict(LauncherApp("Calculator", win="calc", mac="open -a Calculator", linux="gnome-calculator")),
    ])

    @classmethod
    def load(cls) -> "Config":
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        USER_REMOTES_DIR.mkdir(exist_ok=True)
        SCRIPTS_DIR.mkdir(exist_ok=True)
        if CONFIG_FILE.exists():
            data = json.loads(CONFIG_FILE.read_text("utf-8"))
            known = {k: v for k, v in data.items() if k in cls.__dataclass_fields__}
            cfg = cls(**known)
        else:
            cfg = cls()
        cfg.save()
        return cfg

    def save(self) -> None:
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        CONFIG_FILE.write_text(json.dumps(asdict(self), indent=2), "utf-8")
