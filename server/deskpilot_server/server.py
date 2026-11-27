"""Connection handling for the DeskPilot protocol (TCP and Bluetooth RFCOMM).

Framing : 4-byte big-endian length, then 1 flag byte (0 = plain JSON, 1 = encrypted JSON), then payload.
Handshake:
  S -> C  plain   {"type":"hello","proto":1,"id","name","os","mac","salt":b64,"nonce":b64,"protected":bool}
  C -> S  sealed  {"type":"auth","nonce":<same b64>,"device":"Pixel 8"}
  S -> C  sealed  {"type":"welcome","remotes":[...summaries]}       or plain {"type":"error","code":"auth"}
Then every message is sealed. Requests carry "id" and get {"type":"result","id","ok",("data"|"error")}.
Messages without "id" are fire-and-forget (used for high-rate mouse movement).
"""
from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import secrets

from .config import Config, current_os, mac_address
from .crypto import SessionCipher, derive_key
from .remotes import ActionError, Executor, RemoteRegistry

log = logging.getLogger("deskpilot.server")
PROTO = 1
MAX_FRAME = 4 * 1024 * 1024
PLAIN, SEALED = 0, 1


class Context:
    def __init__(self, config: Config, input_backend, registry: RemoteRegistry):
        self.config = config
        self.input = input_backend
        self.registry = registry
        self.executor = Executor(self)
        self.clients: dict[int, dict] = {}   # live sessions, shown in the settings page
        self.rekey()

    def rekey(self):
        """Call after the password changes. New connections use the new key immediately."""
        self.salt = os.urandom(16)
        self.key = derive_key(self.config.password, self.salt)


async def read_frame(reader: asyncio.StreamReader) -> tuple[int, bytes]:
    n = int.from_bytes(await reader.readexactly(4), "big")
    if n < 1 or n > MAX_FRAME:
        raise ConnectionError("bad frame length")
    body = await reader.readexactly(n)
    return body[0], body[1:]


def write_frame(writer: asyncio.StreamWriter, flag: int, payload: bytes):
    writer.write((len(payload) + 1).to_bytes(4, "big") + bytes([flag]) + payload)


class Session:
    def __init__(self, ctx: Context, reader, writer, transport="tcp"):
        self.ctx, self.reader, self.writer, self.transport = ctx, reader, writer, transport
        self.cipher = SessionCipher(ctx.key)
        self.peer = writer.get_extra_info("peername")

    async def send(self, obj: dict):
        write_frame(self.writer, SEALED, self.cipher.seal(json.dumps(obj, separators=(",", ":")).encode()))
        await self.writer.drain()

    async def send_plain(self, obj: dict):
        write_frame(self.writer, PLAIN, json.dumps(obj).encode())
        await self.writer.drain()

    async def recv(self) -> dict:
        flag, payload = await read_frame(self.reader)
        if flag != SEALED:
            raise ConnectionError("plain frame after handshake")
        return json.loads(self.cipher.open(payload))

    async def run(self):
        nonce = base64.b64encode(secrets.token_bytes(16)).decode()
        cfg = self.ctx.config
        await self.send_plain({
            "type": "hello", "proto": PROTO, "id": cfg.server_id, "name": cfg.name, "os": current_os(),
            "mac": mac_address(), "salt": base64.b64encode(self.ctx.salt).decode(), "nonce": nonce,
            "protected": bool(cfg.password),
        })
        try:
            auth = await asyncio.wait_for(self.recv(), timeout=15)
            if auth.get("type") != "auth" or auth.get("nonce") != nonce:
                raise ValueError("bad auth message")
        except (ValueError, Exception) as e:  # InvalidTag lands here too
            log.warning("auth failed from %s: %s", self.peer, type(e).__name__)
            try:
                await self.send_plain({"type": "error", "code": "auth", "message": "Wrong password"})
            except Exception:
                pass
            return
        log.info("client %s (%s) connected via %s", auth.get("device", "?"), self.peer, self.transport)
        await self.send({"type": "welcome", "remotes": self.ctx.registry.summaries(),
                         "server": {"name": cfg.name, "os": current_os(), "mac": mac_address()}})
        import time as _t
        self.ctx.clients[id(self)] = {"device": str(auth.get("device", "Phone"))[:60],
                                      "address": str(self.peer[0]) if self.peer else self.transport,
                                      "since": int(_t.time())}
        try:
            while True:
                msg = await self.recv()
                rid = msg.get("id")
                try:
                    data = await self.handle(msg)
                    if rid is not None:
                        await self.send({"type": "result", "id": rid, "ok": True, "data": data})
                except (ActionError, Exception) as e:
                    log.warning("request failed: %s", e)
                    if rid is not None:
                        await self.send({"type": "result", "id": rid, "ok": False, "error": str(e)})
        finally:
            self.ctx.clients.pop(id(self), None)

    async def handle(self, msg: dict):
        t = msg.get("type")
        ctx = self.ctx
        loop = asyncio.get_running_loop()
        if t == "ping":
            return {"pong": True}
        if t == "list_remotes":
            return ctx.registry.summaries()
        if t == "get_remote":
            return ctx.registry.get(msg["remote"])
        if t == "action":
            steps = ctx.registry.steps_for(msg["remote"], msg["action"])
            return await loop.run_in_executor(None, ctx.executor.run_steps, steps, msg.get("args") or {})
        if t == "exec":  # ad-hoc steps from phone-side custom remotes, widgets, NFC tags, voice
            return await loop.run_in_executor(None, ctx.executor.run_steps, msg.get("steps") or [], msg.get("args") or {})
        if t == "mouse":  # fast path, executed inline to keep ordering
            op = msg.get("op")
            inp = ctx.input
            if op == "move":
                inp.mouse_move(msg.get("dx", 0), msg.get("dy", 0))
            elif op == "scroll":
                inp.mouse_scroll(msg.get("dx", 0), msg.get("dy", 0))
            elif op == "click":
                inp.mouse_click(msg.get("button", "left"), msg.get("count", 1))
            elif op == "down":
                inp.mouse_down(msg.get("button", "left"))
            elif op == "up":
                inp.mouse_up(msg.get("button", "left"))
            return None
        if t == "key":  # fast path for the keyboard screen
            inp = ctx.input
            if "text" in msg:
                inp.type_text(msg["text"])
            elif "keys" in msg:
                inp.combo(msg["keys"])
            elif "key" in msg:
                inp.tap(msg["key"])
            return None
        if t == "reload":
            ctx.registry.reload()
            return ctx.registry.summaries()
        raise ActionError(f"unknown message type {t}")


async def serve_tcp(ctx: Context, host: str = "0.0.0.0"):
    async def on_client(reader, writer):
        try:
            await Session(ctx, reader, writer).run()
        except (asyncio.IncompleteReadError, ConnectionError):
            pass
        except Exception:
            log.exception("session crashed")
        finally:
            writer.close()

    srv = await asyncio.start_server(on_client, host, ctx.config.port)
    log.info("listening on tcp %s:%d", host, ctx.config.port)
    return srv


async def serve_bluetooth(ctx: Context):
    """Experimental: RFCOMM server (Linux / Windows with Python 3.9+). Android connects by channel."""
    import socket
    if not hasattr(socket, "AF_BLUETOOTH"):
        log.warning("Bluetooth sockets not supported by this Python build")
        return
    s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
    s.bind((getattr(socket, "BDADDR_ANY", "00:00:00:00:00:00"), ctx.config.bluetooth_channel))
    s.listen(1)
    s.setblocking(False)
    loop = asyncio.get_running_loop()
    log.info("listening on bluetooth rfcomm channel %d", ctx.config.bluetooth_channel)
    while True:
        conn, addr = await loop.sock_accept(s)
        reader, writer = await asyncio.open_connection(sock=conn)
        loop.create_task(Session(ctx, reader, writer, "bluetooth").run())
