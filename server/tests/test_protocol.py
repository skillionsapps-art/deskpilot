"""End-to-end protocol test: real TCP server (dry-run input) + a client that speaks the same protocol as the app."""
import asyncio
import base64
import json
import os
import sys
import tempfile

os.environ["DESKPILOT_HOME"] = tempfile.mkdtemp()
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from deskpilot_server.config import Config  # noqa: E402
from deskpilot_server.crypto import SessionCipher, derive_key  # noqa: E402
from deskpilot_server.inputs import DryRunBackend  # noqa: E402
from deskpilot_server.remotes import RemoteRegistry  # noqa: E402
from deskpilot_server.server import Context, serve_tcp  # noqa: E402
from deskpilot_server.discovery import serve_discovery, MAGIC  # noqa: E402


async def frame(reader):
    n = int.from_bytes(await reader.readexactly(4), "big")
    b = await reader.readexactly(n)
    return b[0], b[1:]


def send(writer, flag, payload):
    writer.write((len(payload) + 1).to_bytes(4, "big") + bytes([flag]) + payload)


async def connect(port, password):
    r, w = await asyncio.open_connection("127.0.0.1", port)
    flag, hello = await frame(r)
    assert flag == 0
    hello = json.loads(hello)
    c = SessionCipher(derive_key(password, base64.b64decode(hello["salt"])))
    send(w, 1, c.seal(json.dumps({"type": "auth", "nonce": hello["nonce"], "device": "test"}).encode()))
    flag, body = await frame(r)
    return r, w, c, flag, body, hello


async def main():
    cfg = Config(password="s3cret", port=19512, discovery_port=19511)
    inp = DryRunBackend()
    ctx = Context(cfg, inp, RemoteRegistry())
    srv = await serve_tcp(ctx, "127.0.0.1")
    await serve_discovery(cfg)

    # discovery
    loop = asyncio.get_running_loop()
    fut = loop.create_future()

    class P(asyncio.DatagramProtocol):
        def datagram_received(self, d, a):
            fut.set_result(json.loads(d))
    t, _ = await loop.create_datagram_endpoint(P, remote_addr=("127.0.0.1", 19511))
    t.sendto(MAGIC)
    card = await asyncio.wait_for(fut, 2)
    assert card["port"] == 19512 and card["protected"] is True
    print("discovery ok:", card["name"])

    # wrong password
    r, w, c, flag, body, _ = await connect(19512, "nope")
    assert flag == 0 and json.loads(body)["code"] == "auth"
    w.close()
    print("wrong password rejected ok")

    # right password
    r, w, c, flag, body, hello = await connect(19512, "s3cret")
    assert flag == 1
    welcome = json.loads(c.open(body))
    assert welcome["type"] == "welcome" and len(welcome["remotes"]) > 90
    print("auth ok, remotes:", len(welcome["remotes"]))

    async def req(i, msg):
        msg["id"] = i
        send(w, 1, c.seal(json.dumps(msg).encode()))
        await w.drain()
        f, b = await frame(r)
        return json.loads(c.open(b))

    res = await req(1, {"type": "get_remote", "remote": "vlc"})
    assert res["ok"] and res["data"]["name"] == "VLC" and "actions" not in res["data"]
    res = await req(2, {"type": "action", "remote": "vlc", "action": "play_pause"})
    assert res["ok"], res
    res = await req(3, {"type": "action", "remote": "chrome", "action": "go", "args": {"value": "anthropic.com"}})
    assert res["ok"]
    res = await req(4, {"type": "action", "remote": "send_text", "action": "send", "args": {"value": "héllo"}})
    res = await req(5, {"type": "exec", "steps": [{"keys": ["ctrl", "c"]}]})
    res = await req(6, {"type": "action", "remote": "performance", "action": "stats"})
    assert res["ok"] and "cpu" in res["data"], res
    res = await req(7, {"type": "action", "remote": "task_manager", "action": "list"})
    assert res["ok"] and res["data"]["items"]
    res = await req(8, {"type": "action", "remote": "file_manager", "action": "list", "args": {"path": "/"}})
    assert res["ok"] and res["data"]["items"]
    res = await req(9, {"type": "action", "remote": "command", "action": "run", "args": {"value": "echo hi"}})
    assert res["ok"] and res["data"]["stdout"].strip() == "hi"
    res = await req(10, {"type": "action", "remote": "vlc", "action": "nope"})
    assert not res["ok"]
    # fire-and-forget mouse
    send(w, 1, c.seal(json.dumps({"type": "mouse", "op": "move", "dx": 5, "dy": -3}).encode()))
    res = await req(11, {"type": "ping"})
    assert res["ok"]

    ev = inp.events
    assert ("tap", "space") in ev
    assert ("combo", ("primary", "l")) in ev and ("type", "anthropic.com") in ev and ("tap", "enter") in ev
    assert ("type", "héllo") in ev and ("combo", ("ctrl", "c")) in ev and ("move", 5, -3) in ev
    print("actions ok:", len(ev), "input events")
    w.close()
    srv.close()
    print("ALL PROTOCOL TESTS PASSED")


asyncio.run(main())
