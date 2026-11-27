"""Keyboard / mouse injection. pynput on real desktops, a logging dry-run backend for headless use and tests."""
from __future__ import annotations

import logging
import time

from .config import current_os

log = logging.getLogger("deskpilot.input")
OS = current_os()

# Windows virtual-key codes for the numeric keypad (Blender Numpad / Numpad remotes).
_WIN_NUMPAD = {f"num{i}": 0x60 + i for i in range(10)}
_WIN_NUMPAD.update({"num*": 0x6A, "num+": 0x6B, "num-": 0x6D, "num.": 0x6E, "num/": 0x6F})


class DryRunBackend:
    """Records every event. Used when no display is available, and by the test-suite."""

    name = "dryrun"

    def __init__(self):
        self.events: list[tuple] = []

    def _rec(self, *ev):
        self.events.append(ev)
        log.info("input %s", ev)

    def tap(self, key: str): self._rec("tap", key)
    def combo(self, keys: list[str]): self._rec("combo", tuple(keys))
    def type_text(self, text: str): self._rec("type", text)
    def key_down(self, key: str): self._rec("down", key)
    def key_up(self, key: str): self._rec("up", key)
    def mouse_move(self, dx: int, dy: int): self._rec("move", dx, dy)
    def mouse_click(self, button: str = "left", count: int = 1): self._rec("click", button, count)
    def mouse_down(self, button: str = "left"): self._rec("mdown", button)
    def mouse_up(self, button: str = "left"): self._rec("mup", button)
    def mouse_scroll(self, dx: int, dy: int): self._rec("scroll", dx, dy)
    def mouse_position(self): return (0, 0)


class PynputBackend:
    name = "pynput"

    def __init__(self):
        from pynput import keyboard, mouse  # imported lazily: fails without a display
        self._kb_mod = keyboard
        self.kb = keyboard.Controller()
        self.mouse = mouse.Controller()
        self._buttons = {"left": mouse.Button.left, "right": mouse.Button.right, "middle": mouse.Button.middle}
        K = keyboard.Key
        self._named = {
            "ctrl": K.ctrl, "control": K.ctrl, "alt": K.alt, "option": K.alt, "shift": K.shift,
            "cmd": K.cmd, "win": K.cmd, "super": K.cmd, "meta": K.cmd,
            "enter": K.enter, "return": K.enter, "esc": K.esc, "escape": K.esc, "tab": K.tab,
            "space": K.space, "backspace": K.backspace, "delete": K.delete, "del": K.delete,
            "up": K.up, "down": K.down, "left": K.left, "right": K.right,
            "home": K.home, "end": K.end, "pageup": K.page_up, "pagedown": K.page_down,
            "capslock": K.caps_lock,
            "media_play_pause": K.media_play_pause, "media_next": K.media_next,
            "media_previous": K.media_previous, "volume_up": K.media_volume_up,
            "volume_down": K.media_volume_down, "volume_mute": K.media_volume_mute,
        }
        for attr, nm in (("insert", "insert"), ("print_screen", "printscreen"), ("menu", "menu"),
                         ("num_lock", "numlock"), ("pause", "pause"), ("scroll_lock", "scrolllock")):
            if hasattr(K, attr):
                self._named[nm] = getattr(K, attr)
        for i in range(1, 25):
            if hasattr(K, f"f{i}"):
                self._named[f"f{i}"] = getattr(K, f"f{i}")
        # media_stop is not exposed by pynput on every platform
        if OS == "win":
            self._named["media_stop"] = keyboard.KeyCode.from_vk(0xB2)

    def _key(self, name: str):
        n = name.lower()
        if n == "primary":  # Cmd on macOS, Ctrl elsewhere
            n = "cmd" if OS == "mac" else "ctrl"
        if n in self._named:
            return self._named[n]
        if n.startswith("num") and len(n) > 3:
            if OS == "win" and n in _WIN_NUMPAD:
                return self._kb_mod.KeyCode.from_vk(_WIN_NUMPAD[n])
            return self._kb_mod.KeyCode.from_char(n[3:])  # fall back to the plain character
        if n == "plus":
            return self._kb_mod.KeyCode.from_char("+")
        if n == "minus":
            return self._kb_mod.KeyCode.from_char("-")
        if len(name) == 1:
            return self._kb_mod.KeyCode.from_char(name)
        raise ValueError(f"unknown key: {name}")

    def tap(self, key): k = self._key(key); self.kb.press(k); self.kb.release(k)

    def combo(self, keys):
        ks = [self._key(k) for k in keys]
        for k in ks:
            self.kb.press(k)
        time.sleep(0.02)
        for k in reversed(ks):
            self.kb.release(k)

    def type_text(self, text): self.kb.type(text)
    def key_down(self, key): self.kb.press(self._key(key))
    def key_up(self, key): self.kb.release(self._key(key))
    def mouse_move(self, dx, dy): self.mouse.move(int(dx), int(dy))
    def mouse_click(self, button="left", count=1): self.mouse.click(self._buttons[button], int(count))
    def mouse_down(self, button="left"): self.mouse.press(self._buttons[button])
    def mouse_up(self, button="left"): self.mouse.release(self._buttons[button])
    def mouse_scroll(self, dx, dy): self.mouse.scroll(int(dx), int(dy))
    def mouse_position(self): return self.mouse.position


def create_backend(kind: str = "auto"):
    if kind == "dryrun":
        return DryRunBackend()
    try:
        return PynputBackend()
    except Exception as e:  # no display, missing permissions, etc.
        if kind == "pynput":
            raise
        log.warning("pynput unavailable (%s) - using dry-run input backend", e)
        return DryRunBackend()
