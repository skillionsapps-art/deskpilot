"""Generates the bundled remote definitions in deskpilot_server/remotes/*.json.

Coverage is based on the public remote list at unifiedremote.com/remotes/all. Hardware-specific
(TellStick, USB-UIRT, Arduino) and long-dead legacy remotes are intentionally left out.

Run:  python tools/gen_remotes.py
"""
from __future__ import annotations

import json
import re
import shutil
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "deskpilot_server" / "remotes"
ALL = ["win", "mac", "linux"]
REMOTES: list[dict] = []

# ------------------------------------------------------------------ layout helpers
def btn(icon, action, text="", repeat=False, style=None, weight=1):
    b = {"type": "button", "icon": icon, "text": text, "action": action}
    if repeat:
        b["repeat"] = True
    if style:
        b["style"] = style
    if weight != 1:
        b["weight"] = weight
    return b


def row(*items):
    return {"type": "row", "items": list(items)}


def label(t):
    return {"type": "label", "text": t}


def K(*keys):          # one combo / key
    return [{"keys": list(keys)}] if len(keys) > 1 else [{"key": keys[0]}]


def media(name):
    return [{"key": name}]


def per_os(win=None, mac=None, linux=None, do=None):
    a = {}
    if do is not None:
        a["do"] = do
    if win is not None:
        a["win"] = win
    if mac is not None:
        a["mac"] = mac
    if linux is not None:
        a["linux"] = linux
    return a


def slug(name):
    return re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")


def add(name, category, icon, layout, actions, platforms=ALL, tier="free", description="", rid=None):
    REMOTES.append({"id": rid or slug(name), "name": name, "category": category, "icon": icon,
                    "platforms": platforms, "tier": tier, "description": description,
                    "layout": layout, "actions": actions})


def launch_action(win=None, mac=None, linux=None, url=None):
    if url:
        return [{"open": url}]
    return [{"run": {k: v for k, v in (("win", win), ("mac", mac), ("linux", linux)) if v}}]


# ------------------------------------------------------------------ standard blocks
def transport_layout(extra_rows=(), fullscreen=True, seek=True, with_launch=False):
    rows = []
    if with_launch:
        rows.append(row(btn("launch", "launch", "Open"), btn("fullscreen", "fullscreen", "Fullscreen") if fullscreen else btn("close", "close", "Close")))
    rows.append(row(btn("prev", "prev"), btn("play_pause", "play_pause", style="primary", weight=1.4), btn("next", "next")))
    if seek:
        rows.append(row(btn("rewind", "rewind", "Back", repeat=True), btn("stop", "stop"), btn("forward", "forward", "Fwd", repeat=True)))
    else:
        rows.append(row(btn("stop", "stop")))
    rows.append(row(btn("vol_down", "vol_down", repeat=True), btn("mute", "mute"), btn("vol_up", "vol_up", repeat=True)))
    if fullscreen and not with_launch:
        rows.append(row(btn("fullscreen", "fullscreen", "Fullscreen")))
    rows.extend(extra_rows)
    return rows


MEDIA_KEYS = {
    "play_pause": media("media_play_pause"), "next": media("media_next"), "prev": media("media_previous"),
    "stop": per_os(do=media("media_stop"), mac=media("media_play_pause")),
    "vol_up": media("volume_up"), "vol_down": media("volume_down"), "mute": media("volume_mute"),
}


def player(name, category, icon, hot: dict, launch=None, platforms=ALL, tier="full", extra_rows=(), extra_actions=None, desc=""):
    """hot overrides MEDIA_KEYS; missing transport actions fall back to global media keys."""
    actions = dict(MEDIA_KEYS)
    actions.setdefault("rewind", K("left"))
    actions.setdefault("forward", K("right"))
    actions.setdefault("fullscreen", K("f"))
    actions.update(hot)
    if launch:
        actions["launch"] = launch
    actions.update(extra_actions or {})
    add(name, category, icon,
        transport_layout(extra_rows=extra_rows, seek=True, with_launch=bool(launch)),
        actions, platforms=platforms, tier=tier, description=desc)


# ================================================================== ESSENTIALS (free)
add("Basic Input", "Essentials", "mouse",
    [{"type": "touchpad"}, {"type": "keyboard"}], {}, description="Mouse and keyboard on one screen")
add("Mouse", "Essentials", "mouse", [{"type": "touchpad", "fill": True}], {}, rid="mouse",
    description="Multi-touch trackpad: tap, two-finger scroll, two-finger tap right-click, long-press drag")
add("Keyboard", "Essentials", "keyboard", [{"type": "keyboard", "fill": True}], {},
    description="Type in any language your phone keyboard supports, plus special keys")
add("Keyboard (Function)", "Essentials", "keyboard",
    [row(*[btn("", f"f{i}", f"F{i}") for i in range(r, r + 4)]) for r in (1, 5, 9, 13, 17, 21)],
    {f"f{i}": K(f"f{i}") for i in range(1, 25)}, tier="full")
add("Navigation", "Essentials", "navigation",
    [row(btn("", "pgup", "PgUp", repeat=True), btn("up", "up", repeat=True), btn("", "pgdn", "PgDn", repeat=True)),
     row(btn("left", "left", repeat=True), btn("ok", "enter", style="primary"), btn("right", "right", repeat=True)),
     row(btn("", "home", "Home"), btn("down", "down", repeat=True), btn("", "end", "End")),
     row(btn("back", "esc", "Esc"), btn("tab", "tab", "Tab"), btn("backspace", "backspace", "Bksp", repeat=True))],
    {"up": K("up"), "down": K("down"), "left": K("left"), "right": K("right"), "enter": K("enter"),
     "pgup": K("pageup"), "pgdn": K("pagedown"), "home": K("home"), "end": K("end"), "esc": K("esc"),
     "tab": K("tab"), "backspace": K("backspace")})
add("Numpad", "Essentials", "dialpad",
    [row(btn("", "n7", "7"), btn("", "n8", "8"), btn("", "n9", "9"), btn("", "div", "/")),
     row(btn("", "n4", "4"), btn("", "n5", "5"), btn("", "n6", "6"), btn("", "mul", "*")),
     row(btn("", "n1", "1"), btn("", "n2", "2"), btn("", "n3", "3"), btn("", "sub", "-")),
     row(btn("", "n0", "0", weight=2), btn("", "dot", "."), btn("", "add", "+")),
     row(btn("backspace", "bksp", repeat=True), btn("ok", "enter", "Enter", style="primary", weight=2))],
    {**{f"n{i}": K(f"num{i}") for i in range(10)}, "div": K("num/"), "mul": K("num*"), "sub": K("num-"),
     "add": K("num+"), "dot": K("num."), "enter": K("enter"), "bksp": K("backspace")})
add("Scroll Wheel", "Essentials", "mouse",
    [row(btn("up", "up", "Scroll up", repeat=True)), row(btn("down", "down", "Scroll down", repeat=True)),
     row(btn("left", "left", "Left", repeat=True), btn("right", "right", "Right", repeat=True)),
     row(btn("mouse", "click", "Click"), btn("mouse", "rclick", "Right click"))],
    {"up": [{"mouse": "scroll", "dy": 3}], "down": [{"mouse": "scroll", "dy": -3}],
     "left": [{"mouse": "scroll", "dx": -3}], "right": [{"mouse": "scroll", "dx": 3}],
     "click": [{"mouse": "click"}], "rclick": [{"mouse": "right"}]})
add("Send Text", "Essentials", "text",
    [{"type": "text", "action": "send", "hint": "Text to type on the computer", "text": "Send", "multiline": True},
     {"type": "text", "action": "send_enter", "hint": "Send and press Enter", "text": "Send + Enter"}],
    {"send": [{"type": "{value}"}], "send_enter": [{"type": "{value}"}, {"key": "enter"}]})
add("Volume", "Essentials", "volume",
    [{"type": "slider", "action": "set", "min": 0, "max": 100, "text": "Master volume"},
     row(btn("vol_down", "down", repeat=True), btn("mute", "mute"), btn("vol_up", "up", repeat=True))],
    {"set": [{"builtin": "volume", "args": {"op": "set", "value": "{value}"}}],
     "up": media("volume_up"), "down": media("volume_down"), "mute": media("volume_mute")})
add("Media", "Essentials", "music", transport_layout(fullscreen=False, seek=False), dict(MEDIA_KEYS),
    description="Global media keys - works with most players")
add("Power", "Essentials", "power",
    [row(btn("power", "shutdown", "Shut down", style="danger"), btn("restart", "restart", "Restart")),
     row(btn("sleep", "sleep", "Sleep"), btn("hibernate", "hibernate", "Hibernate")),
     row(btn("lock", "lock", "Lock"), btn("logout", "logoff", "Log off")),
     row(btn("close", "abort", "Abort shutdown")),
     {"type": "wol"}],
    {op: [{"builtin": "power", "args": {"op": op}}] for op in ("shutdown", "restart", "sleep", "hibernate", "lock", "logoff", "abort")},
    description="Shutdown, restart, sleep, lock. Wake On LAN is sent from the phone.")
add("Monitor", "Essentials", "monitor",
    [row(btn("eye_off", "off", "Monitor off"), btn("eye", "on", "Monitor on")), row(btn("star", "saver", "Screensaver"))],
    {"off": [{"builtin": "monitor", "args": {"op": "off"}}], "on": [{"builtin": "monitor", "args": {"op": "on"}}],
     "saver": [{"builtin": "monitor", "args": {"op": "screensaver"}}]})
add("Screen", "Essentials", "screen",
    [{"type": "screen", "source": "capture", "refresh": 700}, {"type": "touchpad"}],
    {"capture": [{"builtin": "screen.capture", "args": {"width": 960}}]}, tier="full",
    description="Screen mirroring with a trackpad underneath (needs Pillow on the server)")

# ================================================================== FILES / SYSTEM
add("File Manager", "Files", "folder",
    [{"type": "list", "source": "list", "roots": "roots", "item_action": "open", "nav": True, "title": "Files"}],
    {"roots": [{"builtin": "files.roots"}], "list": [{"builtin": "files.list", "args": {"path": "{path}"}}],
     "open": [{"builtin": "files.open", "args": {"path": "{path}"}}]})
add("Task Manager", "System", "tasks",
    [{"type": "list", "source": "list", "item_action": "kill", "confirm": "End this process?", "title": "Processes", "refresh": 5000},
     row(btn("apps", "taskmgr", "Open Task Manager"))],
    {"list": [{"builtin": "tasks.list"}], "kill": [{"builtin": "tasks.kill", "args": {"pid": "{pid}"}}],
     "taskmgr": per_os(win=K("ctrl", "shift", "esc"), mac=[{"run": "open -a 'Activity Monitor'"}], linux=[{"run": "gnome-system-monitor"}])},
    tier="full")
add("Performance", "System", "speed",
    [{"type": "stats", "source": "stats", "refresh": 2000}], {"stats": [{"builtin": "performance"}]}, tier="full")
add("Command", "System", "terminal",
    [{"type": "text", "action": "run", "hint": "Shell command (output shown on phone)", "text": "Run"}],
    {"run": [{"builtin": "run", "args": {"cmd": "{value}", "capture": True}}]}, tier="full",
    description="Terminal: run a command on the computer and see its output")
add("Custom Run", "System", "launch",
    [{"type": "text", "action": "run", "hint": "Program, file or URL to open", "text": "Open"}],
    {"run": [{"builtin": "open", "args": {"target": "{value}"}}]}, tier="full")
add("Custom Scripts", "System", "code",
    [{"type": "list", "source": "list", "item_action": "run", "title": "Scripts in ~/.deskpilot/scripts"}],
    {"list": [{"builtin": "scripts.list"}], "run": [{"builtin": "scripts.run", "args": {"name": "{name}"}}]}, tier="full")
add("Custom HTTP", "System", "web",
    [{"type": "text", "action": "get", "hint": "http://192.168.1.20/api/toggle", "text": "GET"}],
    {"get": [{"builtin": "http", "args": {"url": "{value}"}}]}, tier="full",
    description="Fire an HTTP request from the computer (smart-home bridges, webhooks)")
add("Launcher", "System", "apps",
    [{"type": "list", "source": "list", "item_action": "run", "title": "Apps (edit in config.json)"}],
    {"list": [{"builtin": "launcher.list"}], "run": [{"builtin": "launcher.run", "args": {"id": "{id}"}}]})
add("CD/DVD Tray", "System", "eject", [row(btn("eject", "eject", "Eject tray"))], {"eject": [{"builtin": "eject"}]})
add("Pixel Color", "System", "palette", [row(btn("palette", "pick", "Colour under mouse"))],
    {"pick": [{"builtin": "screen.pixel"}]}, tier="full")
add("Magnifier", "System", "zoom_in",
    [row(btn("zoom_in", "in", "Zoom in", repeat=True), btn("zoom_out", "out", "Zoom out", repeat=True)), row(btn("close", "close", "Close"))],
    {"in": per_os(do=K("win", "plus"), mac=K("alt", "cmd", "=")), "out": per_os(do=K("win", "minus"), mac=K("alt", "cmd", "-")),
     "close": per_os(do=K("win", "esc"), mac=K("alt", "cmd", "8"))}, platforms=["win", "mac"])
add("Server Manager", "System", "settings",
    [row(btn("info", "info", "Server info"), btn("refresh", "reload", "Reload remotes"))],
    {"info": [{"builtin": "system.info"}], "reload": []}, description="Inspect the server; reload remotes via the app menu")
add("Start", "System", "windows", [row(btn("apps", "start", "Start menu"), btn("search", "search", "Search"))],
    {"start": K("win"), "search": K("win", "s")}, platforms=["win"])
add("Windows", "System", "windows",
    [row(btn("desktop", "desktop", "Desktop"), btn("folder", "explorer", "Explorer"), btn("settings", "settings", "Settings")),
     row(btn("tab", "alttab", "Alt+Tab"), btn("apps", "taskview", "Task view"), btn("close", "close", "Close window")),
     row(btn("left", "snapl", "Snap left"), btn("fullscreen", "max", "Maximise"), btn("right", "snapr", "Snap right")),
     row(btn("lock", "lock", "Lock"), btn("tasks", "taskmgr", "Task Mgr"), btn("search", "search", "Search"))],
    {"desktop": K("win", "d"), "explorer": K("win", "e"), "settings": K("win", "i"), "alttab": K("alt", "tab"),
     "taskview": K("win", "tab"), "close": K("alt", "f4"), "snapl": K("win", "left"), "snapr": K("win", "right"),
     "max": K("win", "up"), "lock": K("win", "l"), "taskmgr": K("ctrl", "shift", "esc"), "search": K("win", "s")},
    platforms=["win"], tier="full")
add("Mac OS X", "System", "apple",
    [row(btn("apps", "mission", "Mission Control"), btn("search", "spotlight", "Spotlight"), btn("desktop", "desktop", "Desktop")),
     row(btn("tab", "cmdtab", "Cmd+Tab"), btn("close", "quit", "Quit app"), btn("fullscreen", "fs", "Full screen")),
     row(btn("lock", "lock", "Lock"), btn("apps", "launchpad", "Launchpad"), btn("screen", "shot", "Screenshot"))],
    {"mission": K("ctrl", "up"), "spotlight": K("cmd", "space"), "desktop": K("f11"), "cmdtab": K("cmd", "tab"),
     "quit": K("cmd", "q"), "fs": K("ctrl", "cmd", "f"), "lock": K("ctrl", "cmd", "q"),
     "launchpad": [{"run": "open -a Launchpad"}], "shot": K("cmd", "shift", "3")}, platforms=["mac"], tier="full")

# ================================================================== MUSIC
for nm in ("Spotify",):
    player(nm, "Music", "music", {
        "play_pause": K("space"), "next": K("primary", "right"), "prev": K("primary", "left"),
        "vol_up": K("primary", "up"), "vol_down": K("primary", "down"), "shuffle": K("primary", "s"), "repeat": K("primary", "r"),
        "rewind": K("shift", "left"), "forward": K("shift", "right")},
        launch=launch_action(win="start spotify:", mac="open -a Spotify", linux="spotify"),
        extra_rows=[row(btn("shuffle", "shuffle", "Shuffle"), btn("repeat", "repeat", "Repeat"))], tier="free")
player("iTunes", "Music", "music", {"play_pause": K("space"), "next": K("primary", "right"), "prev": K("primary", "left"),
       "vol_up": K("primary", "up"), "vol_down": K("primary", "down")}, launch=launch_action(win="start itunes", mac="open -a Music"), platforms=["win", "mac"])
player("Music", "Music", "music", {"play_pause": K("space"), "next": K("cmd", "right"), "prev": K("cmd", "left"),
       "vol_up": K("cmd", "up"), "vol_down": K("cmd", "down")}, launch=launch_action(mac="open -a Music"), platforms=["mac"])
player("Winamp", "Music", "music", {"play_pause": K("c"), "stop": K("v"), "next": K("b"), "prev": K("z"),
       "vol_up": K("up"), "vol_down": K("down"), "play": K("x")}, platforms=["win"])
player("foobar2000", "Music", "music", {}, platforms=["win"], launch=launch_action(win="start foobar2000"))
for nm in ("MusicBee", "Media Monkey", "J River", "Groove"):
    player(nm, "Music", "music", {}, platforms=["win"])
for nm, url in (("Pandora", "https://www.pandora.com"), ("Napster", "https://web.napster.com"), ("Google Music", "https://music.youtube.com")):
    player(nm, "Music", "music", {"play_pause": K("space")}, launch=launch_action(url=url))
player("SoundCloud", "Music", "music", {"play_pause": K("space"), "next": K("shift", "right"), "prev": K("shift", "left"),
       "vol_up": K("shift", "up"), "vol_down": K("shift", "down"), "mute": K("m"), "like": K("l")},
       launch=launch_action(url="https://soundcloud.com"), extra_rows=[row(btn("star", "like", "Like"))])

# ================================================================== VIDEO PLAYERS
player("VLC", "Video", "video", {
    "play_pause": K("space"), "stop": K("s"), "next": K("n"), "prev": K("p"), "fullscreen": K("f"), "mute": K("m"),
    "vol_up": K("primary", "up"), "vol_down": K("primary", "down"), "rewind": K("alt", "left"), "forward": K("alt", "right"),
    "subs": K("v"), "audio": K("b"), "faster": K("]"), "slower": K("[")},
    launch=launch_action(win="start vlc", mac="open -a VLC", linux="vlc"),
    extra_rows=[row(btn("", "subs", "Subtitles"), btn("", "audio", "Audio track")), row(btn("", "slower", "Slower"), btn("", "faster", "Faster"))],
    tier="free")
add("VLC Advanced", "Video", "video",
    transport_layout(extra_rows=[
        row(btn("", "jump_back_long", "-1 min"), btn("", "jump_fwd_long", "+1 min")),
        row(btn("", "subs", "Subs"), btn("", "audio", "Audio"), btn("", "aspect", "Aspect")),
        row(btn("", "snapshot", "Snapshot"), btn("", "loop", "Loop"), btn("", "random", "Shuffle"))]),
    {**MEDIA_KEYS, "play_pause": K("space"), "stop": K("s"), "next": K("n"), "prev": K("p"), "fullscreen": K("f"), "mute": K("m"),
     "vol_up": K("primary", "up"), "vol_down": K("primary", "down"), "rewind": K("shift", "left"), "forward": K("shift", "right"),
     "jump_back_long": K("primary", "left"), "jump_fwd_long": K("primary", "right"), "subs": K("v"), "audio": K("b"),
     "aspect": K("a"), "snapshot": K("shift", "s"), "loop": K("l"), "random": K("r")}, tier="full")
player("MPC-HC", "Video", "video", {"play_pause": K("space"), "stop": K("."), "next": K("pagedown"), "prev": K("pageup"),
       "fullscreen": K("alt", "enter"), "vol_up": K("up"), "vol_down": K("down"), "mute": K("ctrl", "m")}, platforms=["win"])
player("MPC-BE", "Video", "video", {"play_pause": K("space"), "stop": K("."), "next": K("pagedown"), "prev": K("pageup"),
       "fullscreen": K("alt", "enter"), "vol_up": K("up"), "vol_down": K("down"), "mute": K("ctrl", "m")}, platforms=["win"])
player("PotPlayer", "Video", "video", {"play_pause": K("space"), "stop": K("f4"), "next": K("pagedown"), "prev": K("pageup"),
       "fullscreen": K("enter"), "vol_up": K("up"), "vol_down": K("down"), "mute": K("m")}, platforms=["win"])
for nm in ("KMPlayer", "KMPlayer X64"):
    player(nm, "Video", "video", {"play_pause": K("space"), "stop": K("ctrl", "space"), "next": K("pagedown"), "prev": K("pageup"),
           "fullscreen": K("alt", "enter"), "vol_up": K("up"), "vol_down": K("down"), "mute": K("m")}, platforms=["win"])
player("GOM Player", "Video", "video", {"play_pause": K("space"), "next": K("pagedown"), "prev": K("pageup"),
       "fullscreen": K("enter"), "vol_up": K("up"), "vol_down": K("down"), "mute": K("m")}, platforms=["win"])
player("BSPlayer", "Video", "video", {"play_pause": K("space"), "fullscreen": K("f"), "vol_up": K("up"), "vol_down": K("down")}, platforms=["win"])
player("Windows Media Player", "Video", "video", {"play_pause": K("ctrl", "p"), "stop": K("ctrl", "s"), "next": K("ctrl", "f"),
       "prev": K("ctrl", "b"), "vol_up": K("f9"), "vol_down": K("f8"), "mute": K("f7"), "fullscreen": K("alt", "enter"),
       "rewind": K("ctrl", "shift", "b"), "forward": K("ctrl", "shift", "f")}, platforms=["win"])
player("Movies & TV", "Video", "video", {"play_pause": K("space"), "fullscreen": K("alt", "enter"), "next": K("ctrl", "right"), "prev": K("ctrl", "left")}, platforms=["win"])
player("QuickTime Player", "Video", "video", {"play_pause": K("space"), "fullscreen": K("cmd", "f"), "vol_up": K("cmd", "up"),
       "vol_down": K("cmd", "down"), "rewind": K("left"), "forward": K("right")}, platforms=["mac"])
player("MPlayerX", "Video", "video", {"play_pause": K("space"), "fullscreen": K("f"), "vol_up": K("="), "vol_down": K("-"), "mute": K("m")}, platforms=["mac"])
for nm in ("Popcorn Time", "Stremio", "Powder Player", "Porthole"):
    player(nm, "Video", "video", {"play_pause": K("space"), "fullscreen": K("f"), "vol_up": K("up"), "vol_down": K("down"), "mute": K("m")})

# ================================================================== STREAMING (browser based)
def web_video(name, url, extra=None, platforms=ALL, tier="full"):
    hot = {"play_pause": K("space"), "fullscreen": K("f"), "mute": K("m"), "vol_up": K("up"), "vol_down": K("down"),
           "rewind": K("left"), "forward": K("right"), "next": K("shift", "n"), "prev": K("shift", "p"), "stop": K("esc")}
    hot.update(extra or {})
    player(name, "Streaming", "tv", hot, launch=launch_action(url=url), platforms=platforms, tier=tier)


web_video("YouTube (Web)", "https://www.youtube.com", {"play_pause": K("k"), "rewind": K("j"), "forward": K("l"), "captions": K("c")}, tier="free")
REMOTES[-1]["layout"].append(row(btn("", "captions", "Captions"), btn("", "theater", "Theater")))
REMOTES[-1]["actions"]["theater"] = K("t")
web_video("Netflix (Web)", "https://www.netflix.com")
web_video("Netflix (App)", "netflix:", platforms=["win"])
REMOTES[-1]["actions"]["launch"] = [{"run": {"win": "start netflix:"}}]
web_video("Netflix (App W10)", "netflix:", platforms=["win"])
REMOTES[-1]["actions"]["launch"] = [{"run": {"win": "start netflix:"}}]
web_video("Hulu (Web)", "https://www.hulu.com")
web_video("Hulu (Desktop)", "https://www.hulu.com", platforms=["win"])
web_video("Amazon Prime Video", "https://www.primevideo.com")
web_video("Disney+", "https://www.disneyplus.com")
web_video("HBO GO", "https://www.max.com")
web_video("Twitch (Web)", "https://www.twitch.tv")
web_video("Crunchyroll (Web)", "https://www.crunchyroll.com")

# ================================================================== MEDIA CENTERS
kodi_actions = {"up": K("up"), "down": K("down"), "left": K("left"), "right": K("right"), "select": K("enter"),
                "back": K("backspace"), "home": K("esc"), "context": K("c"), "info": K("i"), "osd": K("m"),
                "play_pause": K("space"), "stop": K("x"), "next": K("."), "prev": K(","), "rewind": K("r"), "forward": K("f"),
                "vol_up": K("+"), "vol_down": K("-"), "mute": K("f8"), "fullscreen": K("\\"), "subs": K("t"), "power": K("s")}


def dpad_layout(extra=()):
    return [row(btn("menu", "context", "Menu"), btn("up", "up", repeat=True), btn("info", "info", "Info")),
            row(btn("left", "left", repeat=True), btn("ok", "select", style="primary"), btn("right", "right", repeat=True)),
            row(btn("back", "back", "Back"), btn("down", "down", repeat=True), btn("home", "home", "Home")),
            row(btn("prev", "prev"), btn("rewind", "rewind"), btn("play_pause", "play_pause", style="primary"), btn("forward", "forward"), btn("next", "next")),
            row(btn("vol_down", "vol_down", repeat=True), btn("stop", "stop"), btn("mute", "mute"), btn("vol_up", "vol_up", repeat=True)),
            *extra]


add("Kodi (Keyboard)", "Media Center", "tv", dpad_layout([row(btn("", "subs", "Subtitles"), btn("fullscreen", "fullscreen", "Fullscreen"), btn("power", "power", "Power menu"))]),
    kodi_actions, tier="free", description="Keyboard-based Kodi control")
add("Kodi (Keyboard) Dpad", "Media Center", "tv", dpad_layout()[:3], kodi_actions)
add("Kodi Advanced", "Media Center", "tv", dpad_layout([row(btn("", "subs", "Subtitles"), btn("", "audio", "Audio"), btn("", "osd", "OSD")),
    row(btn("", "queue", "Queue"), btn("", "playlist", "Playlist"), btn("fullscreen", "fullscreen", "Fullscreen")),
    {"type": "keyboard"}]), {**kodi_actions, "audio": K("a"), "queue": K("q"), "playlist": K("p")}, tier="full")
add("Kodi for video", "Media Center", "tv", dpad_layout([row(btn("", "subs", "Subtitles"), btn("", "audio", "Audio"), btn("fullscreen", "fullscreen", "Fullscreen"))]),
    {**kodi_actions, "audio": K("a")}, tier="full")
plex_actions = {"up": K("up"), "down": K("down"), "left": K("left"), "right": K("right"), "select": K("enter"), "back": K("esc"),
                "home": K("h"), "context": K("c"), "info": K("i"), "play_pause": K("space"), "stop": K("x"),
                "next": K("."), "prev": K(","), "rewind": K("left"), "forward": K("right"), "vol_up": K("+"),
                "vol_down": K("-"), "mute": K("m"), "fullscreen": K("f")}
add("Plex Media Player", "Media Center", "tv", dpad_layout(), plex_actions)
add("Plex (Win 10)", "Media Center", "tv", dpad_layout(), plex_actions, platforms=["win"])
add("Windows Media Center", "Media Center", "tv", dpad_layout(),
    {"up": K("up"), "down": K("down"), "left": K("left"), "right": K("right"), "select": K("enter"), "back": K("backspace"),
     "home": K("win", "alt", "enter"), "context": K("ctrl", "d"), "info": K("ctrl", "d"), "play_pause": K("ctrl", "p"),
     "stop": K("ctrl", "shift", "s"), "next": K("ctrl", "f"), "prev": K("ctrl", "b"), "rewind": K("ctrl", "shift", "b"),
     "forward": K("ctrl", "shift", "f"), "vol_up": K("f10"), "vol_down": K("f9"), "mute": K("f8")}, platforms=["win"])
add("Media Portal", "Media Center", "tv", dpad_layout(),
    {"up": K("up"), "down": K("down"), "left": K("left"), "right": K("right"), "select": K("enter"), "back": K("esc"),
     "home": K("h"), "context": K("f9"), "info": K("f3"), "play_pause": K("space"), "stop": K("b"), "next": K("f8"),
     "prev": K("f7"), "rewind": K("f5"), "forward": K("f6"), "vol_up": K("+"), "vol_down": K("-"), "mute": K("f8")}, platforms=["win"])
add("DVBViewer EN", "Media Center", "tv", dpad_layout(),
    {**{k: v for k, v in MEDIA_KEYS.items()}, "up": K("up"), "down": K("down"), "left": K("left"), "right": K("right"),
     "select": K("enter"), "back": K("backspace"), "home": K("esc"), "context": K("ctrl", "m"), "info": K("i"),
     "rewind": K("left"), "forward": K("right")}, platforms=["win"])
add("EyeTV", "Media Center", "tv", dpad_layout(),
    {**{k: v for k, v in MEDIA_KEYS.items()}, "up": K("up"), "down": K("down"), "left": K("left"), "right": K("right"),
     "select": K("enter"), "back": K("esc"), "home": K("cmd", "1"), "context": K("cmd", "i"), "info": K("cmd", "i"),
     "play_pause": K("space"), "rewind": K("left"), "forward": K("right")}, platforms=["mac"])

# ================================================================== PRESENTATIONS
def presentation(name, start, start_current, extra_rows=(), extra_actions=None, platforms=ALL, launch=None, tier="free"):
    layout = [row(btn("prev", "prev", "Previous", weight=1), btn("next", "next", "Next", style="primary", weight=1.6)),
              row(btn("play", "start", "Start"), btn("play", "start_current", "From current"), btn("stop", "end", "End")),
              row(btn("black", "black", "Black"), btn("white", "white", "White")),
              {"type": "text", "action": "goto", "hint": "Go to slide #", "text": "Go", "number": True},
              *extra_rows]
    acts = {"next": K("right"), "prev": K("left"), "start": start, "start_current": start_current, "end": K("esc"),
            "black": K("b"), "white": K("w"), "goto": [{"type": "{value}"}, {"key": "enter"}]}
    acts.update(extra_actions or {})
    if launch:
        acts["launch"] = launch
        layout.insert(0, row(btn("launch", "launch", "Open app")))
    add(name, "Presentations", "slides", layout, acts, platforms=platforms, tier=tier)


presentation("PowerPoint Basic", per_os(do=K("f5"), mac=K("cmd", "shift", "enter")), per_os(do=K("shift", "f5"), mac=K("cmd", "enter")),
             platforms=["win", "mac"])
presentation("PowerPoint Advanced", per_os(do=K("f5"), mac=K("cmd", "shift", "enter")), per_os(do=K("shift", "f5"), mac=K("cmd", "enter")),
             extra_rows=[row(btn("laser", "laser", "Laser"), btn("pen", "pen", "Pen"), btn("delete", "erase", "Erase ink")),
                         row(btn("zoom_in", "zoom_in", "Zoom +"), btn("zoom_out", "zoom_out", "Zoom -"), btn("mouse", "arrow", "Arrow")),
                         {"type": "touchpad"}],
             extra_actions={"laser": K("ctrl", "l"), "pen": K("ctrl", "p"), "erase": K("e"), "arrow": K("ctrl", "a"),
                            "zoom_in": K("plus"), "zoom_out": K("minus")}, platforms=["win", "mac"], tier="full")
presentation("Keynote", K("alt", "cmd", "p"), K("alt", "cmd", "p"), platforms=["mac"], launch=[{"run": "open -a Keynote"}])
presentation("Google Presentation", K("primary", "enter"), K("primary", "shift", "enter"))
presentation("Slide Show", K("f5"), K("shift", "f5"))
presentation("Beamer", K("primary", "l"), K("primary", "l"), extra_actions={"end": K("primary", "l")})

# ================================================================== BROWSERS
def browser(name, win=None, mac=None, linux=None, platforms=ALL, tier="free"):
    layout = [row(btn("back", "back", "Back"), btn("refresh", "refresh", "Reload"), btn("right", "forward", "Forward")),
              row(btn("add", "new_tab", "New tab"), btn("close", "close_tab", "Close tab"), btn("tab", "next_tab", "Next tab")),
              row(btn("zoom_out", "zoom_out", repeat=True), btn("", "zoom_reset", "100%"), btn("zoom_in", "zoom_in", repeat=True)),
              row(btn("up", "page_up", "Page up", repeat=True), btn("fullscreen", "fullscreen", "Fullscreen"), btn("down", "page_down", "Page down", repeat=True)),
              {"type": "text", "action": "go", "hint": "Search or type URL", "text": "Go"},
              {"type": "touchpad"}]
    acts = {"back": per_os(do=K("alt", "left"), mac=K("cmd", "[")), "forward": per_os(do=K("alt", "right"), mac=K("cmd", "]")),
            "refresh": per_os(do=K("f5"), mac=K("cmd", "r")), "new_tab": K("primary", "t"), "close_tab": K("primary", "w"),
            "next_tab": K("ctrl", "tab"), "zoom_in": K("primary", "="), "zoom_out": K("primary", "-"), "zoom_reset": K("primary", "0"),
            "page_up": K("pageup"), "page_down": K("pagedown"), "fullscreen": per_os(do=K("f11"), mac=K("ctrl", "cmd", "f")),
            "go": [{"keys": ["primary", "l"]}, {"delay": 80}, {"type": "{value}"}, {"key": "enter"}]}
    if any((win, mac, linux)):
        acts["launch"] = launch_action(win=win, mac=mac, linux=linux)
        layout.insert(0, row(btn("launch", "launch", "Open browser")))
    add(name, "Browsers", "web", layout, acts, platforms=platforms, tier=tier)


browser("Chrome", win="start chrome", mac="open -a 'Google Chrome'", linux="google-chrome")
browser("Firefox", win="start firefox", mac="open -a Firefox", linux="firefox")
browser("Edge", win="start msedge", mac="open -a 'Microsoft Edge'", linux="microsoft-edge")
browser("Opera", win="start opera", mac="open -a Opera", linux="opera", tier="full")
browser("Safari", mac="open -a Safari", platforms=["mac"], tier="full")
browser("Internet Explorer", win="start iexplore", platforms=["win"], tier="full")

# ================================================================== PHOTOS
def photo(name, platforms, rotate=("ctrl", "."), slideshow=("f11",), launch=None):
    layout = [row(btn("prev", "prev", "Previous"), btn("next", "next", "Next", style="primary")),
              row(btn("zoom_out", "zoom_out", repeat=True), btn("rotate", "rotate", "Rotate"), btn("zoom_in", "zoom_in", repeat=True)),
              row(btn("play", "slideshow", "Slideshow"), btn("delete", "delete", "Delete", style="danger"), btn("close", "close", "Close"))]
    acts = {"prev": K("left"), "next": K("right"), "zoom_in": K("primary", "="), "zoom_out": K("primary", "-"),
            "rotate": K(*rotate), "slideshow": K(*slideshow), "delete": K("delete"), "close": K("esc")}
    if launch:
        acts["launch"] = launch
        layout.insert(0, row(btn("launch", "launch", "Open")))
    add(name, "Photos", "photo", layout, acts, platforms=platforms, tier="full")


photo("Photos", ["mac"], rotate=("cmd", "r"), slideshow=("cmd", "alt", "f"), launch=[{"run": "open -a Photos"}])
photo("Windows Photos", ["win"], rotate=("ctrl", "r"), slideshow=("f5",))
photo("Windows Photo Viewer", ["win"], rotate=("ctrl", "."), slideshow=("f11",))
photo("IrfanView'er", ["win"], rotate=("r",), slideshow=("w",))
photo("iPhoto", ["mac"], rotate=("cmd", "r"), slideshow=("cmd", "alt", "f"))

# ================================================================== APPS / GAMES / CREATIVE
add("Zoom", "Apps", "video_call",
    [row(btn("mic", "mute", "Mute mic"), btn("video", "video", "Camera")),
     row(btn("share", "share", "Share screen"), btn("record", "record", "Record")),
     row(btn("star", "hand", "Raise hand"), btn("apps", "gallery", "Gallery view")),
     row(btn("close", "end", "Leave meeting", style="danger"))],
    {"mute": per_os(do=K("alt", "a"), mac=K("cmd", "shift", "a")), "video": per_os(do=K("alt", "v"), mac=K("cmd", "shift", "v")),
     "share": per_os(do=K("alt", "s"), mac=K("cmd", "shift", "s")), "record": per_os(do=K("alt", "r"), mac=K("cmd", "shift", "r")),
     "hand": per_os(do=K("alt", "y"), mac=K("alt", "y")), "gallery": per_os(do=K("alt", "f2"), mac=K("cmd", "shift", "w")),
     "end": per_os(do=K("alt", "q"), mac=K("cmd", "w"))}, tier="full")
add("Anki", "Apps", "school",
    [row(btn("eye", "show", "Show answer", style="primary")),
     row(btn("", "again", "Again"), btn("", "hard", "Hard"), btn("", "good", "Good"), btn("", "easy", "Easy")),
     row(btn("back", "undo", "Undo"), btn("pen", "edit", "Edit"), btn("star", "flag", "Flag"))],
    {"show": K("space"), "again": K("1"), "hard": K("2"), "good": K("3"), "easy": K("4"), "undo": K("primary", "z"),
     "edit": K("e"), "flag": K("primary", "1")})
add("Minecraft", "Games", "game",
    [row(btn("", "q", "Drop"), btn("up", "w", "W", repeat=True), btn("", "e", "Inventory")),
     row(btn("left", "a", "A", repeat=True), btn("down", "s", "S", repeat=True), btn("right", "d", "D", repeat=True)),
     row(btn("up", "jump", "Jump"), btn("down", "sneak", "Sneak"), btn("", "f5", "View")),
     row(btn("back", "esc", "Menu"), btn("", "chat", "Chat")), {"type": "touchpad"}],
    {"w": K("w"), "a": K("a"), "s": K("s"), "d": K("d"), "q": K("q"), "e": K("e"), "jump": K("space"),
     "sneak": K("shift"), "f5": K("f5"), "esc": K("esc"), "chat": K("t")}, tier="full")
blender = {"front": K("num1"), "right": K("num3"), "top": K("num7"), "back": K("ctrl", "num1"), "left": K("ctrl", "num3"),
           "bottom": K("ctrl", "num7"), "camera": K("num0"), "ortho": K("num5"), "frame": K("num."),
           "orbit_l": K("num4"), "orbit_r": K("num6"), "orbit_u": K("num8"), "orbit_d": K("num2"),
           "zoom_in": K("num+"), "zoom_out": K("num-"), "local": K("num/")}
add("Blender Numpad", "Creative", "3d",
    [row(btn("", "top", "Top"), btn("", "front", "Front"), btn("", "right", "Right")),
     row(btn("", "camera", "Camera"), btn("", "ortho", "Persp/Ortho"), btn("", "frame", "Frame sel."))],
    blender, description="Blender viewport numpad (full numpad emulation on Windows)")
add("Blender Numpad Advanced", "Creative", "3d",
    [row(btn("", "top", "Top"), btn("", "front", "Front"), btn("", "right", "Right")),
     row(btn("", "bottom", "Bottom"), btn("", "back", "Back"), btn("", "left", "Left")),
     row(btn("", "orbit_l", "Orbit L", repeat=True), btn("", "orbit_u", "Orbit Up", repeat=True), btn("", "orbit_d", "Orbit Dn", repeat=True), btn("", "orbit_r", "Orbit R", repeat=True)),
     row(btn("zoom_out", "zoom_out", repeat=True), btn("", "camera", "Camera"), btn("zoom_in", "zoom_in", repeat=True)),
     row(btn("", "ortho", "Persp/Ortho"), btn("", "frame", "Frame"), btn("", "local", "Local view"))], blender, tier="full")
add("Pyxel Edit-H", "Creative", "palette",
    [row(btn("", "pen", "Pen"), btn("", "eraser", "Eraser"), btn("", "fill", "Fill"), btn("", "picker", "Picker")),
     row(btn("back", "undo", "Undo"), btn("refresh", "redo", "Redo"), btn("zoom_in", "zin"), btn("zoom_out", "zout"))],
    {"pen": K("b"), "eraser": K("e"), "fill": K("f"), "picker": K("i"), "undo": K("ctrl", "z"), "redo": K("ctrl", "y"),
     "zin": K("ctrl", "="), "zout": K("ctrl", "-")}, platforms=["win", "mac"], tier="full")
add("Pyxel Edit-V", "Creative", "palette",
    [row(btn("", "pen", "Pen")), row(btn("", "eraser", "Eraser")), row(btn("", "fill", "Fill")), row(btn("", "picker", "Picker")),
     row(btn("back", "undo", "Undo")), row(btn("refresh", "redo", "Redo"))],
    {"pen": K("b"), "eraser": K("e"), "fill": K("f"), "picker": K("i"), "undo": K("ctrl", "z"), "redo": K("ctrl", "y")},
    platforms=["win", "mac"], tier="full")


# ------------------------------------------------------------------ write
def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    ids = set()
    for r in REMOTES:
        assert r["id"] not in ids, r["id"]
        ids.add(r["id"])
        used = set()

        def walk(c):
            if c.get("type") == "row":
                for i in c["items"]:
                    walk(i)
            for k in ("action", "source", "item_action", "roots"):
                if c.get(k):
                    used.add(c[k])
        for c in r["layout"]:
            walk(c)
        missing = used - set(r["actions"])
        assert not missing, f"{r['id']} missing actions {missing}"
        (OUT / f"{r['id']}.json").write_text(json.dumps(r, indent=1), "utf-8")
    print(f"wrote {len(REMOTES)} remotes to {OUT}")


if __name__ == "__main__":
    main()
