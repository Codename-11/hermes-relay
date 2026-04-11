"""HMAC-SHA256 signing for QR pairing payloads.

The operator runs ``hermes-pair`` (or the ``/hermes-relay-pair`` skill) on
the host. It generates a QR payload containing the API endpoint + the
relay URL + a pre-registered pairing code. A malicious LAN peer who
captures or forges a QR with a different host/code could trick a phone
into connecting to the attacker instead — so we sign the canonical form
of the payload with a host-local secret and include the signature in
the QR.

When the phone later re-scans a payload (e.g. to "import a saved QR")
we can verify the signature against the same secret. The phone does NOT
get the secret — verification is server-side only, so its value is for
the operator's own audit trail + defense against the phone trusting a
forged payload that was pasted into the QR input screen rather than
scanned off a trusted display. Phone-side verification is not currently
wired up (the brief calls for the server side only in this pass).

File layout:

    ~/.hermes/hermes-relay-qr-secret       mode 0o600, 32 random bytes
"""

from __future__ import annotations

import base64
import binascii
import hmac
import json
import logging
import os
import secrets
from hashlib import sha256
from pathlib import Path

logger = logging.getLogger("hermes_relay.qr_sign")

_DEFAULT_SECRET_FILENAME = "hermes-relay-qr-secret"
_SECRET_BYTES = 32
_SIG_FIELD = "sig"


def default_secret_path() -> Path:
    """Return the canonical on-disk location of the QR secret."""
    home = Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes"))
    return home / _DEFAULT_SECRET_FILENAME


def load_or_create_secret(path: Path | None = None) -> bytes:
    """Read the host-local HMAC secret, generating it on first call.

    The file is written with mode 0o600 (owner read/write only) so other
    local users cannot forge signatures. The directory is created with
    mode 0o700 if it didn't exist.

    On platforms where chmod is a no-op (Windows), the umask and best-
    effort chmod still run — the file lands in the user's home
    directory which is already ACL-isolated to them on Windows.
    """
    if path is None:
        path = default_secret_path()

    if path.exists():
        try:
            data = path.read_bytes()
        except OSError as exc:
            raise RuntimeError(
                f"Failed to read QR secret at {path}: {exc}"
            ) from exc
        if len(data) < 16:
            # Degenerate / truncated; regenerate to be safe.
            logger.warning(
                "QR secret at %s was shorter than 16 bytes — regenerating", path
            )
        else:
            return data

    # Generate a fresh secret.
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(path.parent, 0o700)
    except OSError:
        pass

    secret = secrets.token_bytes(_SECRET_BYTES)
    # Use a umask dance to ensure the file is created 0o600 even if the
    # inherited umask is more permissive.
    old_umask = os.umask(0o077)
    try:
        # O_EXCL would race with a concurrent writer; accept a last-writer-
        # wins race since this file is created once per host.
        with open(path, "wb") as fh:
            fh.write(secret)
    finally:
        os.umask(old_umask)
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass
    logger.info("Generated new QR secret at %s", path)
    return secret


def canonicalize(payload: dict) -> bytes:
    """Return the canonical byte form of ``payload`` for signing.

    * ``sig`` is stripped — the signature is taken over the *unsigned*
      payload so verification is a pure round-trip.
    * Keys are sorted recursively, whitespace is stripped, and the
      output is ASCII-escaped for maximum cross-platform stability
      (the phone parses JSON as UTF-8 but the HMAC input has to match
      exactly, so we lock down the serialization).

    Raises ``TypeError`` if the payload contains values that don't
    round-trip through ``json.dumps`` (e.g. ``math.inf`` — callers
    must serialize never-expiry as ``None`` or 0 before signing).
    """
    if not isinstance(payload, dict):
        raise TypeError("canonicalize() requires a dict payload")
    clean = {k: v for k, v in payload.items() if k != _SIG_FIELD}
    return json.dumps(
        clean,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
        allow_nan=False,
    ).encode("ascii")


def sign_payload(payload: dict, secret: bytes) -> str:
    """Return a base64-encoded HMAC-SHA256 signature over ``payload``."""
    canon = canonicalize(payload)
    mac = hmac.new(secret, canon, sha256).digest()
    return base64.b64encode(mac).decode("ascii")


def verify_payload(payload: dict, signature: str, secret: bytes) -> bool:
    """Constant-time verify a signature against ``payload``.

    Returns True on match, False on any decoding failure or mismatch.
    Never raises — callers just want a boolean.
    """
    try:
        canon = canonicalize(payload)
        expected = hmac.new(secret, canon, sha256).digest()
        provided = base64.b64decode(signature, validate=True)
    except (TypeError, ValueError, binascii.Error):
        return False
    return hmac.compare_digest(expected, provided)


__all__ = [
    "canonicalize",
    "default_secret_path",
    "load_or_create_secret",
    "sign_payload",
    "verify_payload",
]
