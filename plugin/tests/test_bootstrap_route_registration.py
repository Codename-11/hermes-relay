"""Tests for bootstrap compatibility route registration."""

from __future__ import annotations

import unittest

from aiohttp import web

from hermes_relay_bootstrap import _handlers


async def _handler(_request: web.Request) -> web.Response:
    return web.json_response({"ok": True})


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


if __name__ == "__main__":
    unittest.main()
