"""Encryption for the DeskPilot wire protocol.

Key  = PBKDF2-HMAC-SHA256("deskpilot:" + password, salt, 100_000 iterations, 32 bytes)
Seal = 12-byte random IV || AES-256-GCM(ciphertext || 16-byte tag)

The Android client (Crypto.kt) implements exactly the same scheme with javax.crypto.
"""
from __future__ import annotations

import hashlib
import os

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

PBKDF2_ITERATIONS = 100_000
KEY_BYTES = 32
IV_BYTES = 12


PASSWORD_PREFIX = "deskpilot:"   # keeps the PBKDF2 input non-empty (Android's HMAC rejects empty keys)


def derive_key(password: str, salt: bytes) -> bytes:
    pw = (PASSWORD_PREFIX + password).encode("utf-8")
    return hashlib.pbkdf2_hmac("sha256", pw, salt, PBKDF2_ITERATIONS, KEY_BYTES)


class SessionCipher:
    def __init__(self, key: bytes):
        self._aead = AESGCM(key)

    def seal(self, plaintext: bytes) -> bytes:
        iv = os.urandom(IV_BYTES)
        return iv + self._aead.encrypt(iv, plaintext, None)

    def open(self, blob: bytes) -> bytes:
        if len(blob) < IV_BYTES + 16:
            raise ValueError("frame too short")
        return self._aead.decrypt(blob[:IV_BYTES], blob[IV_BYTES:], None)
