"""Tests for bootstrap compatibility route registration.

Covers two invariants:

1. `_add_route_if_missing` mechanics — native upstream routes win per
   method/path.
2. The HRUI-002 retirement split — `register_routes()` must inject only the
   compatibility-only surfaces (session search, memory, legacy skill
   detail/toggle, config, available-models) and must NOT inject the surfaces
   upstream serves natively (sessions CRUD/messages/fork via PR #33134, the
   legacy read-only skill list superseded by /v1/skills via PR #33016).
"""

from __future__ import annotations

import unittest
from unittest import mock

from aiohttp import web

from hermes_relay_bootstrap import _handlers

# Compatibility-only surfaces the bootstrap must still inject.
KEPT_ROUTES = [
    ("GET", "/api/sessions/search"),
    ("GET", "/api/memory"),
    ("POST", "/api/memory"),
    ("PATCH", "/api/memory"),
    ("DELETE", "/api/memory"),
    ("GET", "/api/skills/{name}"),
    ("PUT", "/api/skills/toggle"),
    ("GET", "/api/config"),
    ("PATCH", "/api/config"),
    ("GET", "/api/available-models"),
]

# Native-upstream surfaces the bootstrap retired and must never inject again.
RETIRED_ROUTES = [
    ("GET", "/api/sessions"),
    ("POST", "/api/sessions"),
    ("GET", "/api/sessions/{session_id}"),
    ("PATCH", "/api/sessions/{session_id}"),
    ("DELETE", "/api/sessions/{session_id}"),
    ("GET", "/api/sessions/{session_id}/messages"),
    ("POST", "/api/sessions/{session_id}/fork"),
    ("POST", "/api/sessions/{session_id}/chat"),
    ("POST", "/api/sessions/{session_id}/chat/stream"),
    ("GET", "/api/skills"),
]


async def _handler(_request: web.Request) -> web.Response:
    return web.json_response({"ok": True})


def _fake_upstream() -> dict:
    """Upstream symbol dict with just enough shape for handler factories.

    Only `web` is touched at registration time; the rest are request-time
    dependencies that never fire in these tests.
    """
    return {
        "web": web,
        "SessionDB": mock.MagicMock(),
        "AsyncSessionDB": None,
        "MemoryStore": None,
        "load_config": mock.MagicMock(),
        "save_config": mock.MagicMock(),
        "curated_models_for_provider": mock.MagicMock(),
        "list_available_providers": mock.MagicMock(),
        "skill_view": mock.MagicMock(),
    }


class BootstrapRouteRegistrationTest(unittest.TestCase):
    def test_add_route_if_missing_is_method_aware(self) -> None:
        app = web.Application()
        app.router.add_get("/api/sessions", _handler)

        self.assertFalse(
            _handlers._add_route_if_missing(
                app,
                "GET",
                "/api/sessions",
                _handler,
            )
        )
        self.assertTrue(
            _handlers._add_route_if_missing(
                app,
                "POST",
                "/api/sessions",
                _handler,
            )
        )
        self.assertFalse(
            _handlers._add_route_if_missing(
                app,
                "POST",
                "/api/sessions",
                _handler,
            )
        )

    def test_add_route_if_missing_keeps_compatibility_gaps(self) -> None:
        app = web.Application()
        app.router.add_get("/api/sessions", _handler)
        app.router.add_post("/api/sessions", _handler)

        self.assertTrue(
            _handlers._add_route_if_missing(
                app,
                "GET",
                "/api/config",
                _handler,
            )
        )
        self.assertTrue(_handlers._route_exists(app, "GET", "/api/sessions"))
        self.assertTrue(_handlers._route_exists(app, "HEAD", "/api/config"))
        self.assertTrue(_handlers._route_exists(app, "POST", "/api/sessions"))
        self.assertTrue(_handlers._route_exists(app, "GET", "/api/config"))


class BootstrapRetirementSplitTest(unittest.TestCase):
    """register_routes() injects the kept surfaces and only those."""

    def _register(self, app: web.Application) -> int:
        adapter = object()
        with mock.patch.object(
            _handlers, "_resolve_upstream", return_value=_fake_upstream()
        ):
            return _handlers.register_routes(app, adapter)

    def test_injects_exactly_the_compatibility_only_surfaces(self) -> None:
        app = web.Application()
        injected = self._register(app)

        self.assertEqual(injected, len(KEPT_ROUTES))
        for method, path in KEPT_ROUTES:
            self.assertTrue(
                _handlers._route_exists(app, method, path),
                f"expected kept compatibility route {method} {path}",
            )

    def test_retired_native_surfaces_are_not_injected(self) -> None:
        app = web.Application()
        self._register(app)

        for method, path in RETIRED_ROUTES:
            self.assertFalse(
                _handlers._route_exists(app, method, path),
                f"retired surface {method} {path} must not be injected",
            )

    def test_native_route_still_wins_for_kept_surfaces(self) -> None:
        # If a kept surface ever lands natively, the bootstrap must skip it.
        app = web.Application()
        app.router.add_get("/api/config", _handler)

        injected = self._register(app)

        self.assertEqual(injected, len(KEPT_ROUTES) - 1)


if __name__ == "__main__":
    unittest.main()
