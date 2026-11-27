"""LAN discovery: the app broadcasts DESKPILOT_DISCOVER to UDP 9511, servers answer with a JSON card."""
from __future__ import annotations

import asyncio
import json
import logging

from .config import Config, current_os, mac_address

log = logging.getLogger("deskpilot.discovery")
MAGIC = b"DESKPILOT_DISCOVER"


class _Proto(asyncio.DatagramProtocol):
    def __init__(self, cfg: Config):
        self.cfg = cfg

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        if data.strip() != MAGIC:
            return
        card = {"app": "deskpilot", "id": self.cfg.server_id, "name": self.cfg.name, "os": current_os(),
                "port": self.cfg.port, "mac": mac_address(), "protected": bool(self.cfg.password)}
        self.transport.sendto(json.dumps(card).encode(), addr)


async def serve_discovery(cfg: Config):
    loop = asyncio.get_running_loop()
    transport, _ = await loop.create_datagram_endpoint(lambda: _Proto(cfg), local_addr=("0.0.0.0", cfg.discovery_port))
    log.info("discovery on udp %d", cfg.discovery_port)
    return transport
