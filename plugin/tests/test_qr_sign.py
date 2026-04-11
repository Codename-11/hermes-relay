"""Tests for plugin.relay.qr_sign — HMAC signing of QR pairing payloads."""

from __future__ import annotations

import base64
import json
import os
import tempfile
import unittest
from pathlib import Path

from plugin.relay.qr_sign import (
    canonicalize,
    load_or_create_secret,
    sign_payload,
    verify_payload,
)


class CanonicalizeTests(unittest.TestCase):
    def test_drops_sig_field(self) -> None:
        """The sig field must NOT participate in its own input."""
        payload = {"hermes": 2, "host": "192.168.1.1", "sig": "bogus"}
        canon = canonicalize(payload)
        # sig should not appear in canonical form
        self.assertNotIn(b"bogus", canon)
        self.assertNotIn(b"sig", canon)

    def test_order_independent(self) -> None:
        """Dict ordering must not affect the canonical bytes."""
        a = {"hermes": 2, "host": "192.168.1.1", "port": 8642, "tls": False}
        b = {"tls": False, "port": 8642, "host": "192.168.1.1", "hermes": 2}
        self.assertEqual(canonicalize(a), canonicalize(b))

    def test_nested_dict_sorted(self) -> None:
        """Nested dicts (relay block) must also be sorted recursively
        because ``sort_keys=True`` is recursive."""
        a = {"relay": {"url": "ws://h:1", "code": "ABC123", "ttl_seconds": 0}}
        b = {"relay": {"ttl_seconds": 0, "code": "ABC123", "url": "ws://h:1"}}
        self.assertEqual(canonicalize(a), canonicalize(b))

    def test_rejects_non_dict(self) -> None:
        with self.assertRaises(TypeError):
            canonicalize([1, 2, 3])  # type: ignore[arg-type]

    def test_rejects_nan_inf(self) -> None:
        """``allow_nan=False`` — never-expire must be serialized as None/0
        BEFORE reaching canonicalize, not as math.inf."""
        import math

        with self.assertRaises(ValueError):
            canonicalize({"ttl": math.inf})


class SignVerifyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.secret = b"x" * 32
        self.payload = {
            "hermes": 2,
            "host": "192.168.1.100",
            "port": 8642,
            "key": "test-api-key",
            "tls": False,
            "relay": {
                "url": "ws://192.168.1.100:8767",
                "code": "ABC123",
                "ttl_seconds": 2592000,
                "transport_hint": "ws",
            },
        }

    def test_round_trip(self) -> None:
        sig = sign_payload(self.payload, self.secret)
        self.assertTrue(verify_payload(self.payload, sig, self.secret))

    def test_rejects_tampered_payload(self) -> None:
        sig = sign_payload(self.payload, self.secret)
        tampered = json.loads(json.dumps(self.payload))
        tampered["host"] = "evil.example"
        self.assertFalse(verify_payload(tampered, sig, self.secret))

    def test_rejects_tampered_relay_field(self) -> None:
        """Tampering a nested field must invalidate the signature."""
        sig = sign_payload(self.payload, self.secret)
        tampered = json.loads(json.dumps(self.payload))
        tampered["relay"]["code"] = "XYZXYZ"
        self.assertFalse(verify_payload(tampered, sig, self.secret))

    def test_rejects_wrong_secret(self) -> None:
        sig = sign_payload(self.payload, self.secret)
        other = b"y" * 32
        self.assertFalse(verify_payload(self.payload, sig, other))

    def test_rejects_malformed_signature(self) -> None:
        self.assertFalse(verify_payload(self.payload, "!!!not-base64!!!", self.secret))
        self.assertFalse(verify_payload(self.payload, "", self.secret))

    def test_sig_is_ignored_on_input(self) -> None:
        """Adding an existing sig field to the payload should not change
        what gets signed — otherwise re-signing a signed payload would
        produce a different signature each round."""
        signed = dict(self.payload)
        signed["sig"] = sign_payload(self.payload, self.secret)
        # Re-sign: the resulting signature should match because sig is excluded
        resigned = sign_payload(signed, self.secret)
        self.assertEqual(signed["sig"], resigned)

    def test_signature_is_base64(self) -> None:
        sig = sign_payload(self.payload, self.secret)
        # Should decode cleanly to 32 bytes (SHA-256 output size)
        raw = base64.b64decode(sig, validate=True)
        self.assertEqual(len(raw), 32)


class LoadOrCreateSecretTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.mkdtemp(prefix="hermes_qr_secret_test_")

    def tearDown(self) -> None:
        import shutil

        shutil.rmtree(self._tmp, ignore_errors=True)

    def test_creates_file_on_first_call(self) -> None:
        target = Path(self._tmp) / "secret"
        self.assertFalse(target.exists())
        secret = load_or_create_secret(target)
        self.assertTrue(target.exists())
        self.assertEqual(len(secret), 32)

    def test_is_idempotent(self) -> None:
        target = Path(self._tmp) / "secret"
        first = load_or_create_secret(target)
        second = load_or_create_secret(target)
        self.assertEqual(first, second)

    def test_regenerates_truncated_file(self) -> None:
        target = Path(self._tmp) / "secret"
        target.write_bytes(b"short")
        # File exists but < 16 bytes — should regenerate
        secret = load_or_create_secret(target)
        self.assertEqual(len(secret), 32)
        self.assertNotEqual(secret, b"short")

    @unittest.skipIf(os.name == "nt", "chmod is a no-op on Windows")
    def test_file_permissions_restricted(self) -> None:
        target = Path(self._tmp) / "secret"
        load_or_create_secret(target)
        mode = target.stat().st_mode & 0o777
        # Should be 0o600 (owner read/write only)
        self.assertEqual(mode, 0o600)


if __name__ == "__main__":
    unittest.main()
