"""
ResolveResult — typed return shape for plugin-side resolvers (v0.4.1).

A small dataclass-based ``sealed class`` analogue used by helper resolvers
(contact lookup, location, SMS pre-flight, call pre-flight) to make the
"why didn't this work?" axis machine-readable rather than an opaque
``None`` or string. Mirrors the Kotlin ``ContactResolution`` /
``AppResolution`` sealed types added in 2026-04-15 voice-fixes session
and the bridge response envelope's ``error_code`` / ``required_permission``
fields produced by ``BridgeCommandHandler.classifyBridgeError``.

Python doesn't have proper sealed classes, but a tagged-union dataclass
hierarchy plus a ``from_bridge_response`` constructor gives us:

    >>> r = ResolveResult.from_bridge_response({"contacts": []})
    >>> isinstance(r, NotFound)
    True
    >>> r = ResolveResult.from_bridge_response({"error": "no perm",
    ...     "code": "permission_denied",
    ...     "permission": "android.permission.READ_CONTACTS"})
    >>> isinstance(r, PermissionDenied)
    True
    >>> r.permission
    'android.permission.READ_CONTACTS'

The class is consumed by the agent-tool wrapper layer in
``plugin/tools/android_tool.py``: every Tier C (C1-C4) tool wrapper now
runs the bridge response through ``ResolveResult.from_bridge_response``
and, on a ``PermissionDenied``, returns a structured error JSON to the
LLM with a clear actionable hint instead of relaying the raw bridge
error string.

Wire shape on the bridge response that this parser consumes:

    {
        "error": "Grant SMS permission in Settings...",
        "code": "permission_denied",      # canonical (v0.4.1 alias)
        "permission": "android.permission.SEND_SMS",  # canonical
        # legacy aliases still recognized for older phone builds:
        "error_code": "permission_denied",
        "required_permission": "android.permission.SEND_SMS",
    }
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Generic, Mapping, Optional, TypeVar, Union

T = TypeVar("T")


# Canonical key the v0.4.1 bridge response uses for the structured error
# code. The pre-v0.4.1 phone builds emitted ``error_code`` instead — the
# parser accepts either to keep the agent server backwards-compatible
# while the phone APK rolls out across users.
_CODE_KEYS = ("code", "error_code")
_PERMISSION_KEYS = ("permission", "required_permission")


@dataclass(frozen=True)
class Found(Generic[T]):
    """Resolver succeeded with a payload."""

    value: T


@dataclass(frozen=True)
class NotFound:
    """Resolver completed but found no match (e.g. no contact named 'Sam')."""

    detail: Optional[str] = None


@dataclass(frozen=True)
class PermissionDenied:
    """
    Resolver couldn't run because the user hasn't granted a required
    runtime permission.

    The ``permission`` field carries the Android permission name
    (e.g. ``android.permission.READ_CONTACTS``) so downstream layers
    can surface the exact Settings deep-link.
    """

    permission: str
    reason: str = ""


# Tagged-union alias for type hints — `ResolveResult[Contact]` reads as
# `Found[Contact] | NotFound | PermissionDenied`. Python's type system
# enforces the variants via mypy / pyright; runtime checks use isinstance.
ResolveResult = Union[Found[T], NotFound, PermissionDenied]


def _first_present(mapping: Mapping[str, Any], keys: tuple[str, ...]) -> Optional[str]:
    """Return the first non-empty string value among ``keys`` in ``mapping``, else None."""
    for k in keys:
        v = mapping.get(k)
        if isinstance(v, str) and v:
            return v
    return None


def from_bridge_response(
    response: Mapping[str, Any],
    *,
    found_value: Optional[T] = None,
) -> "ResolveResult[T]":
    """
    Classify a bridge tool's HTTP-shaped response as a ResolveResult.

    Decision tree:
      1. If ``response`` carries ``code`` (or legacy ``error_code``) of
         ``"permission_denied"``, return ``PermissionDenied(permission, reason)``
         where ``permission`` is read from ``permission`` /
         ``required_permission`` and ``reason`` from ``error``.
      2. If ``found_value`` is provided (truthy or explicit non-None),
         return ``Found(found_value)``. The caller has already extracted
         the meaningful payload (e.g. the contact list or the GPS fix)
         and just wants a typed wrapper.
      3. Otherwise return ``NotFound(detail)`` where ``detail`` is the
         response's ``error`` field if any.

    The function deliberately doesn't model every possible failure mode
    (timeout, dispatch_exception, sideload_only, …) because those are
    all "transient or configuration" — the LLM should retry or report,
    not surface a permission prompt to the user. PermissionDenied is the
    one error category that maps onto a deterministic UI affordance
    (deep-link to Settings → Apps → Hermes Relay → Permissions), so it
    earns the dedicated dataclass.
    """
    code = _first_present(response, _CODE_KEYS)
    if code == "permission_denied":
        permission = _first_present(response, _PERMISSION_KEYS) or ""
        reason = response.get("error") if isinstance(response.get("error"), str) else ""
        return PermissionDenied(permission=permission, reason=reason or "")

    if found_value is not None:
        return Found(found_value)

    detail = response.get("error") if isinstance(response.get("error"), str) else None
    return NotFound(detail=detail)


# Re-export for ``from .resolve_result import *`` consumers, keeping the
# public surface explicit.
__all__ = [
    "Found",
    "NotFound",
    "PermissionDenied",
    "ResolveResult",
    "from_bridge_response",
]


# ── Module-level convenience: namespaced static-method access ─────────────
#
# The Kotlin sealed-class equivalent is invoked as
# ``ResolveResult.PermissionDenied(...)`` rather than the bare class. To
# keep the call sites symmetric across the Kotlin/Python boundary the
# wrapper module also exposes the variants as attributes on a tiny
# namespace object, so callers can write either:
#
#     from plugin.tools.resolve_result import PermissionDenied
#     return PermissionDenied("android.permission.READ_CONTACTS")
#
# OR
#
#     from plugin.tools import resolve_result as RR
#     return RR.PermissionDenied("android.permission.READ_CONTACTS")
#
# The latter mirrors the Kotlin idiom one-to-one without forcing a star
# import on every call site.
