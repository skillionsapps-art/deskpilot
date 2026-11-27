"""DeskPilot Server – desktop app entry point.

Double-click (packaged app): starts the server, shows a tray icon and opens the settings page on
first run. On servers / Raspberry Pi without a desktop use --headless.

    deskpilot-server                      # tray app
    deskpilot-server --headless           # no tray, no browser (systemd, SSH, Raspberry Pi)
    deskpilot-server --password secret    # set & save a password
    deskpilot-server --dry-run            # log input instead of injecting it
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import logging.handlers
import socket
import sys
import threading
import webbrowser
from pathlib import Path

from . import __version__
from .config import CONFIG_DIR, CONFIG_FILE, Config
from .discovery import serve_discovery
from .inputs import create_backend
from .remotes import RemoteRegistry
from .server import Context, serve_bluetooth, serve_tcp
from .webui import WebUI, local_ips

log = logging.getLogger("deskpilot")


def asset(name: str) -> Path:
    if hasattr(sys, "_MEIPASS"):
        return Path(sys._MEIPASS) / "deskpilot_server" / "assets" / name
    return Path(__file__).resolve().parent / "assets" / name


def setup_logging(verbose: bool):
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    handlers: list[logging.Handler] = [logging.handlers.RotatingFileHandler(
        CONFIG_DIR / "server.log", maxBytes=1_000_000, backupCount=2, encoding="utf-8")]
    if sys.stderr is not None:  # windowed builds have no console
        handlers.append(logging.StreamHandler())
    logging.basicConfig(level=logging.DEBUG if verbose else logging.INFO,
                        format="%(asctime)s %(name)s %(levelname)s %(message)s", handlers=handlers)


def already_running(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


class ServerApp:
    def __init__(self, cfg: Config, backend: str):
        self.cfg = cfg
        self.backend = backend
        self.loop = asyncio.new_event_loop()
        self.ready = threading.Event()
        self.stop_event: asyncio.Event | None = None
        self.web: WebUI | None = None
        self.error: BaseException | None = None
        self.on_stopped = lambda: None

    async def _main(self):
        self.stop_event = asyncio.Event()
        ctx = Context(self.cfg, create_backend(self.backend), RemoteRegistry())
        tcp = await serve_tcp(ctx)
        if self.cfg.discovery:
            try:
                await serve_discovery(self.cfg)
            except OSError as e:
                log.warning("auto-discovery disabled: %s", e)
        if self.cfg.bluetooth:
            asyncio.get_running_loop().create_task(serve_bluetooth(ctx))
        self.web = WebUI(ctx, self.cfg.web_port, self.request_stop)
        try:
            await self.web.start()
        except OSError as e:
            log.warning("settings page unavailable: %s", e)
            self.web = None
        self.ready.set()
        log.info("DeskPilot Server %s '%s' on %s:%d", __version__, self.cfg.name, ", ".join(local_ips()), self.cfg.port)
        await self.stop_event.wait()
        tcp.close()

    def run(self):
        """Runs the asyncio loop (call in a background thread, or directly for headless)."""
        asyncio.set_event_loop(self.loop)
        try:
            self.loop.run_until_complete(self._main())
        except BaseException as e:  # noqa: BLE001 - surfaced to the caller
            self.error = e
            log.exception("server stopped with an error")
        finally:
            self.ready.set()
            self.on_stopped()

    def request_stop(self):
        if self.stop_event is not None:
            self.loop.call_soon_threadsafe(self.stop_event.set)


def open_settings(app: ServerApp):
    if app.web:
        webbrowser.open(app.web.url)


def run_tray(app: ServerApp) -> bool:
    """Shows the tray icon. Blocks until Quit. Returns False if no tray is available."""
    try:
        import pystray
        from PIL import Image
    except Exception as e:  # no display / no tray support (e.g. some Linux desktops)
        log.info("tray unavailable: %s", e)
        return False

    from . import autostart
    addr = f"{local_ips()[0]}:{app.cfg.port}"

    def toggle_autostart(icon, item):
        autostart.set_enabled(not autostart.is_enabled())

    def quit_app(icon, item):
        app.request_stop()
        icon.stop()

    menu = pystray.Menu(
        pystray.MenuItem("Open DeskPilot", lambda i, it: open_settings(app), default=True),
        pystray.MenuItem(f"Address: {addr}", None, enabled=False),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("Start when I log in", toggle_autostart, checked=lambda it: autostart.is_enabled()),
        pystray.MenuItem("Quit DeskPilot", quit_app),
    )
    try:
        img = Image.open(asset("tray.png"))
    except OSError:
        img = Image.new("RGBA", (64, 64), (47, 107, 255, 255))
    icon = pystray.Icon("deskpilot", img, "DeskPilot Server", menu)
    app.on_stopped = icon.stop  # quitting from the settings page also removes the icon
    try:
        icon.run()
    except Exception as e:
        log.info("tray failed: %s", e)
        return False
    return True


def main():
    ap = argparse.ArgumentParser(prog="deskpilot-server", description="DeskPilot Server " + __version__)
    ap.add_argument("--password")
    ap.add_argument("--name")
    ap.add_argument("--port", type=int)
    ap.add_argument("--bluetooth", action="store_true")
    ap.add_argument("--dry-run", action="store_true", help="log input instead of injecting it")
    ap.add_argument("--headless", action="store_true", help="no tray icon, no browser (servers, Raspberry Pi)")
    ap.add_argument("--minimized", action="store_true", help="start in the tray without opening the settings page")
    ap.add_argument("--configure", action="store_true", help="save the given options (e.g. --password) and exit")
    ap.add_argument("--version", action="version", version=__version__)
    ap.add_argument("-v", "--verbose", action="store_true")
    a, _unknown = ap.parse_known_args()  # macOS may pass -psn_... arguments

    setup_logging(a.verbose)
    cfg = Config.load()
    if a.password is not None:
        cfg.password = a.password
    if a.name:
        cfg.name = a.name
    if a.port:
        cfg.port = a.port
    if a.bluetooth:
        cfg.bluetooth = True
    cfg.save()
    if a.configure:
        print(f"saved to {CONFIG_FILE}")
        return

    # second launch: just bring up the existing instance's settings page
    if already_running(cfg.web_port):
        if not a.headless:
            webbrowser.open(f"http://127.0.0.1:{cfg.web_port}/")
        return

    app = ServerApp(cfg, "dryrun" if a.dry_run else cfg.input_backend)

    if a.headless:
        if sys.stdout is not None:
            print(f"\n  DeskPilot Server {__version__} '{cfg.name}'")
            for ip in local_ips():
                print(f"  -> connect the app to {ip}:{cfg.port}")
            print(f"  password protected: {'yes' if cfg.password else 'NO (set one with --password)'}")
            print(f"  config: {CONFIG_FILE}\n")
        try:
            app.run()
        except KeyboardInterrupt:
            pass
        return

    t = threading.Thread(target=app.run, name="deskpilot-server", daemon=True)
    t.start()
    app.ready.wait(15)
    if app.error:
        raise SystemExit(f"DeskPilot Server could not start: {app.error}")
    if not a.minimized:
        open_settings(app)  # opening the app shows its window; autostart (--minimized) stays in the tray
    if not run_tray(app):
        # No tray (e.g. GNOME without the AppIndicator extension): keep running, settings page is the UI.
        try:
            t.join()
        except KeyboardInterrupt:
            app.request_stop()
    t.join(5)


if __name__ == "__main__":
    main()
