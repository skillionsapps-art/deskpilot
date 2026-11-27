"""Local settings page at http://127.0.0.1:9513 – the server's 'window'.

Only reachable from this computer (bound to 127.0.0.1). Every API call must carry the per-run
token embedded in the page, and the Host header must be localhost, which blocks other websites
(CSRF / DNS-rebinding) from changing settings.
"""
from __future__ import annotations

import asyncio
import json
import logging
import secrets
import socket
from typing import Callable

from . import __version__, autostart
from .config import current_os

log = logging.getLogger("deskpilot.webui")
PLAY_URL = "https://play.google.com/store/apps/details?id=com.skillions.deskpilot"


def local_ips() -> list[str]:
    ips: set[str] = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                ips.add(ip)
    except OSError:
        pass
    return sorted(ips) or ["127.0.0.1"]


class WebUI:
    def __init__(self, ctx, port: int, on_quit: Callable[[], None]):
        self.ctx, self.port, self.on_quit = ctx, port, on_quit
        self.token = secrets.token_urlsafe(24)

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}/"

    async def start(self):
        self.server = await asyncio.start_server(self._client, "127.0.0.1", self.port)
        log.info("settings page on %s", self.url)

    # ---------------------------------------------------------------- state
    def state(self) -> dict:
        c = self.ctx.config
        return {
            "version": __version__, "os": current_os(), "name": c.name, "port": c.port, "ips": local_ips(),
            "password_set": bool(c.password), "autostart": autostart.is_enabled(),
            "allow_commands": c.allow_commands, "allow_file_manager": c.allow_file_manager,
            "files_root": c.files_root, "input": self.ctx.input.name,
            "remotes": len(self.ctx.registry.remotes), "clients": list(self.ctx.clients.values()),
            "play_url": PLAY_URL,
        }

    def save(self, data: dict) -> dict:
        c = self.ctx.config
        if "name" in data and str(data["name"]).strip():
            c.name = str(data["name"]).strip()[:60]
        if data.get("password") is not None and data.get("password") != "":
            c.password = str(data["password"])
            self.ctx.rekey()
        if data.get("clear_password"):
            c.password = ""
            self.ctx.rekey()
        for k in ("allow_commands", "allow_file_manager"):
            if k in data:
                setattr(c, k, bool(data[k]))
        if "files_root" in data:
            c.files_root = str(data["files_root"]).strip()
        if "autostart" in data:
            autostart.set_enabled(bool(data["autostart"]))
        c.save()
        return self.state()

    # ---------------------------------------------------------------- tiny HTTP server
    async def _client(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        try:
            head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 10)
            lines = head.decode("latin-1").split("\r\n")
            method, path, _ = lines[0].split(" ", 2)
            headers = {k.strip().lower(): v.strip() for k, v in (l.split(":", 1) for l in lines[1:] if ":" in l)}
            body = b""
            n = int(headers.get("content-length", "0") or 0)
            if 0 < n < 100_000:
                body = await reader.readexactly(n)
            status, ctype, payload = self._route(method, path, headers, body)
        except Exception as e:  # noqa: BLE001
            status, ctype, payload = 400, "text/plain", str(e).encode()
        writer.write(f"HTTP/1.1 {status} OK\r\nContent-Type: {ctype}\r\nContent-Length: {len(payload)}\r\n"
                     "Cache-Control: no-store\r\nX-Frame-Options: DENY\r\nConnection: close\r\n\r\n".encode() + payload)
        await writer.drain()
        writer.close()

    def _route(self, method, path, headers, body):
        host = headers.get("host", "")
        if host not in (f"127.0.0.1:{self.port}", f"localhost:{self.port}"):
            return 403, "text/plain", b"forbidden host"
        if method == "GET" and path.split("?")[0] == "/":
            return 200, "text/html; charset=utf-8", PAGE.replace("__TOKEN__", self.token).encode()
        if not path.startswith("/api/") or headers.get("x-token") != self.token:
            return 403, "text/plain", b"forbidden"
        if method == "GET" and path == "/api/state":
            return 200, "application/json", json.dumps(self.state()).encode()
        if method == "POST" and path == "/api/save":
            return 200, "application/json", json.dumps(self.save(json.loads(body or b"{}"))).encode()
        if method == "POST" and path == "/api/quit":
            asyncio.get_running_loop().call_later(0.3, self.on_quit)
            return 200, "application/json", b'{"ok":true}'
        return 404, "text/plain", b"not found"


PAGE = r"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>DeskPilot Server</title>
<style>
:root{--b:#2F6BFF;--bg:#F5F6FB;--card:#fff;--ink:#121726;--mut:#5B6480;--ok:#15A06B}
@media(prefers-color-scheme:dark){:root{--bg:#0F131C;--card:#1A2130;--ink:#E8ECF6;--mut:#98A2BC}}
a{color:var(--b);font-weight:600}*{box-sizing:border-box}body{margin:0;font-family:Inter,Segoe UI,Roboto,system-ui,sans-serif;background:var(--bg);color:var(--ink)}
header{background:linear-gradient(120deg,#0B1020,#1C3FAA 60%,var(--b));color:#fff;padding:28px 32px}
header .t{display:flex;align-items:center;gap:14px;font-size:24px;font-weight:800}
.logo{width:46px;height:46px;border-radius:12px;background:linear-gradient(145deg,#4C82FF,#2F6BFF,#1B44C8);display:grid;place-items:center}
main{max-width:860px;margin:-18px auto 40px;padding:0 20px;display:grid;gap:16px}
.card{background:var(--card);border-radius:18px;padding:22px 24px;box-shadow:0 2px 10px rgba(20,30,60,.08)}
h2{margin:0 0 14px;font-size:17px}.mut{color:var(--mut);font-size:14px}
.addr{font-size:34px;font-weight:800;letter-spacing:-.01em;color:var(--b);margin:6px 0}
.steps{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-top:14px}
.step{background:var(--bg);border-radius:14px;padding:14px;font-size:14px}.step b{display:block;color:var(--b);margin-bottom:4px}
label{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:12px 0;border-top:1px solid rgba(128,128,128,.15);font-size:15px}
label:first-of-type{border-top:0}input[type=text],input[type=password]{font:inherit;padding:10px 12px;border-radius:10px;border:1px solid rgba(128,128,128,.35);background:var(--bg);color:var(--ink);min-width:240px}
.sw{appearance:none;width:44px;height:26px;border-radius:13px;background:#B9C0CF;position:relative;cursor:pointer;flex:none}
.sw:checked{background:var(--b)}.sw:after{content:"";position:absolute;top:3px;left:3px;width:20px;height:20px;border-radius:50%;background:#fff;transition:.15s}.sw:checked:after{left:21px}
button{font:inherit;font-weight:600;border:0;border-radius:12px;padding:11px 18px;background:var(--b);color:#fff;cursor:pointer}
button.ghost{background:transparent;color:var(--b);border:1px solid var(--b)}button.danger{background:#D32F45}
.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.pill{display:inline-block;padding:4px 10px;border-radius:999px;font-size:12px;font-weight:700}
.ok{background:#DDF5EA;color:#0E7A50}.warn{background:#FFF1D6;color:#8A5A00}
.dev{display:flex;justify-content:space-between;padding:10px 0;border-top:1px solid rgba(128,128,128,.15);font-size:14px}
#toast{position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:#121726;color:#fff;padding:10px 18px;border-radius:12px;opacity:0;transition:.2s}
@media(max-width:640px){.steps{grid-template-columns:1fr}.addr{font-size:26px}}
</style></head><body>
<header><div class="t"><div class="logo"><svg width="28" height="28" viewBox="0 0 24 24"><path fill="#fff" d="M15,9H9c-.55,0-1,.45-1,1v12c0,.55.45,1,1,1h6c.55,0,1-.45,1-1V10c0-.55-.45-1-1-1zM12,15c-1.1,0-2-.9-2-2s.9-2,2-2,2,.9,2,2-.9,2-2,2zM7.05,6.05l1.41,1.41C9.37,6.56,10.62,6,12,6s2.63.56,3.54,1.46l1.41-1.41C15.68,4.78,13.93,4,12,4S8.32,4.78,7.05,6.05zM12,0C8.96,0,6.21,1.23,4.22,3.22l1.41,1.41C7.26,3.01,9.51,2,12,2s4.74,1.01,6.36,2.64l1.41-1.41C17.79,1.23,15.04,0,12,0z"/></svg></div>DeskPilot Server</div>
<div class="mut" style="color:#C9D4FF;margin-top:6px" id="ver"></div></header>
<main>
 <section class="card"><div class="row" style="justify-content:space-between"><h2 style="margin:0">Connect your phone</h2><span id="status" class="pill ok">Running</span></div>
  <div class="addr" id="addr">…</div><div class="mut" id="other"></div>
  <div class="steps">
   <div class="step"><b>1 · Get the app</b>Install <a id="play" href="#" target="_blank">DeskPilot</a> from Google Play.</div>
   <div class="step"><b>2 · Same Wi-Fi</b>Connect your phone to the same network as this computer.</div>
   <div class="step"><b>3 · Tap your computer</b>It appears automatically. Enter the password below.</div>
  </div></section>
 <section class="card"><h2>Security</h2>
  <div id="pwwarn" class="pill warn" style="display:none;margin-bottom:10px">No password set – anyone on your Wi-Fi could connect</div>
  <label>Computer name<input type="text" id="name"></label>
  <label>Password<span class="row"><input type="password" id="pw" placeholder="New password"><button onclick="savePw()">Save</button></span></label>
 </section>
 <section class="card"><h2>Options</h2>
  <label>Start DeskPilot when I log in<input class="sw" type="checkbox" id="autostart"></label>
  <label>Allow Command / terminal remotes<input class="sw" type="checkbox" id="allow_commands"></label>
  <label>Allow File Manager<input class="sw" type="checkbox" id="allow_file_manager"></label>
  <label>Limit File Manager to folder<input type="text" id="files_root" placeholder="Whole computer"></label>
 </section>
 <section class="card"><h2>Connected devices</h2><div id="devs" class="mut">No phones connected yet.</div></section>
 <section class="card row" style="justify-content:space-between"><span class="mut" id="meta"></span><button class="danger" onclick="quit()">Quit server</button></section>
</main><div id="toast"></div>
<script>
const T="__TOKEN__";const $=id=>document.getElementById(id);
async function api(p,b){const r=await fetch(p,{method:b?"POST":"GET",headers:{"X-Token":T,"Content-Type":"application/json"},body:b?JSON.stringify(b):undefined});if(!r.ok)throw new Error(await r.text());return r.json()}
function toast(t){const e=$("toast");e.textContent=t;e.style.opacity=1;setTimeout(()=>e.style.opacity=0,1800)}
const OS={win:"Windows",mac:"macOS",linux:"Linux"};let first=true;
function render(s){
 $("addr").textContent=s.ips[0]+":"+s.port;$("other").textContent=s.ips.length>1?"Also: "+s.ips.slice(1).map(i=>i+":"+s.port).join(", "):"";
 $("ver").textContent="Version "+s.version+" · "+(OS[s.os]||s.os)+" · "+s.remotes+" remotes";$("play").href=s.play_url;
 $("pwwarn").style.display=s.password_set?"none":"inline-block";$("pw").placeholder=s.password_set?"•••••• (set – type to change)":"Choose a password";
 if(first){$("name").value=s.name;$("files_root").value=s.files_root;first=false}
 for(const k of["autostart","allow_commands","allow_file_manager"])$(k).checked=s[k];
 $("devs").innerHTML=s.clients.length?s.clients.map(c=>`<div class="dev"><b>${c.device.replace(/[<>&]/g,"")}</b><span>${c.address}</span></div>`).join(""):"No phones connected yet.";
 $("meta").textContent="Input: "+s.input+(s.input==="dryrun"?" (no display – input is only logged)":"");}
async function refresh(){try{render(await api("/api/state"));$("status").textContent="Running";$("status").className="pill ok"}catch(e){$("status").textContent="Stopped";$("status").className="pill warn"}}
async function save(b,msg){render(await api("/api/save",b));toast(msg||"Saved")}
function savePw(){const v=$("pw").value;if(v.length<4){toast("Use at least 4 characters");return}save({password:v},"Password saved");$("pw").value=""}
$("name").onchange=e=>save({name:e.target.value});$("files_root").onchange=e=>save({files_root:e.target.value});
for(const k of["autostart","allow_commands","allow_file_manager"])$(k).onchange=e=>save({[k]:e.target.checked});
async function quit(){if(confirm("Stop DeskPilot Server? Your phone will disconnect.")){await api("/api/quit",{});document.body.innerHTML="<main><div class='card' style='margin-top:40px'><h2>DeskPilot Server stopped</h2><p class='mut'>Start it again from your Start menu / Applications.</p></div></main>"}}
refresh();setInterval(refresh,3000);
</script></body></html>"""
