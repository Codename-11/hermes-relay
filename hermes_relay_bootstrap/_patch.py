"""Import-hook plumbing + Application monkey-patch.

When `aiohttp.web` is imported anywhere in the running interpreter, our
`_AioHttpWebFinder` intercepts the import, lets the real loader finish,
then swaps `web.Application` for a `_PatchedApplication` subclass.

The subclass overrides `__setitem__` so we can detect the moment hermes-agent's
`APIServerAdapter` does `self._app["api_server_adapter"] = self` — that's the
single line in the upstream `connect()` method that gives us a reference to
the adapter while the app is still being built (router not yet frozen).

At that point, we feature-detect by route path and call `_register_routes()`
to bind our handlers to the same router the gateway is in the middle of
populating. We also install the slash-command middleware that intercepts
``/v1/chat/completions`` and ``/v1/runs`` to handle gateway commands like
``/help``, ``/commands``, ``/profile``, and ``/provider`` without forwarding
them to the LLM.  The gateway then continues with its own route registrations
and starts the server normally.

Failure modes are silent-but-logged: if anything in this chain breaks
(unexpected upstream refactor, aiohttp version incompatibility, missing
SessionDB methods, etc.), we log a warning and let the gateway start without
the injected routes. The Android client's capability detection will fall back
to the standard upstream endpoints automatically.
"""

from __future__ import annotations

import logging
import sys

logger = logging.getLogger(__name__)

# Routes we inject. The keys MUST match the path strings registered by
# `_register_routes()` in `_handlers.py`. The values are unused — only the
# set of paths matters for feature detection.
_INJECTED_PATHS: frozenset[str] = frozenset({
    "/api/sessions",
    "/api/sessions/search",
    "/api/sessions/{session_id}",
    "/api/sessions/{session_id}/messages",
    "/api/sessions/{session_id}/fork",
    "/api/memory",
    "/api/skills",
    "/api/skills/{name}",
    "/api/config",
    "/api/available-models",
})


class _AioHttpWebFinder:
    """sys.meta_path finder that wraps the loader for `aiohttp.web`.

    Removes itself from `sys.meta_path` after firing once, so subsequent
    imports of unrelated modules don't pay the find-spec cost.
    """

    def find_spec(self, fullname, path, target=None):
        if fullname != "aiohttp.web":
            return None

        # Remove ourselves so this only runs on the first import.
        try:
            sys.meta_path.remove(self)
        except ValueError:
            pass

        # Resolve the real spec via the remaining finders. We can't recurse
        # back into our own find_spec because we just removed ourselves.
        for finder in sys.meta_path:
            if not hasattr(finder, "find_spec"):
                continue
            spec = finder.find_spec(fullname, path, target)
            if spec is None:
                continue

            original_loader = spec.loader
            spec.loader = _PatchingLoader(original_loader)
            return spec

        # No finder could resolve aiohttp.web — fall through, normal import
        # error will surface.
        return None


class _PatchingLoader:
    """Loader wrapper that runs `_apply_patch()` after the module is exec'd."""

    def __init__(self, wrapped):
        self._wrapped = wrapped

    def create_module(self, spec):
        if hasattr(self._wrapped, "create_module"):
            return self._wrapped.create_module(spec)
        return None

    def exec_module(self, module):
        self._wrapped.exec_module(module)
        try:
            _apply_patch(module)
        except Exception as exc:
            logger.warning(
                "hermes_relay_bootstrap: failed to install Application patch: %s",
                exc,
            )


def install_finder() -> None:
    """Insert the import-hook finder at the front of `sys.meta_path`."""
    finder = _AioHttpWebFinder()
    sys.meta_path.insert(0, finder)


def _apply_patch(web_module) -> None:
    """Replace `web_module.Application` with `_PatchedApplication`.

    No-op if `web_module` lacks the expected `Application` attribute (e.g.
    aiohttp version skew or stripped-down build).
    """
    original_application = getattr(web_module, "Application", None)
    if original_application is None:
        logger.warning(
            "hermes_relay_bootstrap: aiohttp.web has no Application attribute "
            "— version mismatch? skipping injection."
        )
        return

    if getattr(original_application, "_hermes_relay_patched", False):
        return  # Idempotent — already patched in a previous import.

    class _PatchedApplication(original_application):
        """Subclass of aiohttp.web.Application with adapter-detection hook.

        Hermes-agent's `APIServerAdapter.connect()` does
        `self._app["api_server_adapter"] = self` immediately after building
        the application. We catch that and use the adapter reference to
        register our extra routes on `self.router` while it's still mutable.
        """

        _hermes_relay_patched = True

        def __setitem__(self, key, value):
            super().__setitem__(key, value)
            if key != "api_server_adapter":
                return
            try:
                _maybe_register_routes(self, value)
            except Exception as exc:  # pragma: no cover - defensive logging
                logger.warning(
                    "hermes_relay_bootstrap: route injection failed: %s",
                    exc,
                )
            try:
                _maybe_install_command_middleware(self, value)
            except Exception as exc:  # pragma: no cover - defensive logging
                logger.warning(
                    "hermes_relay_bootstrap: command middleware "
                    "installation failed: %s",
                    exc,
                )

    web_module.Application = _PatchedApplication


def _maybe_register_routes(app, adapter) -> None:
    """Feature-detect on route paths and register if absent.

    The fork's PR #8556 head branch (`feat/session-api`) and the
    upstream-merged version (post-PR-#8556) both register the same paths. If
    any of our injected paths are already on the router, we no-op so we don't
    double-register.
    """
    existing_paths: set[str] = set()
    try:
        for resource in app.router.resources():
            canonical = getattr(resource, "canonical", None)
            if canonical:
                existing_paths.add(canonical)
    except Exception:
        # If router introspection fails, err on the side of NOT injecting —
        # double-registration would crash the whole gateway startup.
        logger.warning(
            "hermes_relay_bootstrap: cannot inspect existing routes; "
            "skipping injection (gateway will run with whatever routes "
            "it natively provides)."
        )
        return

    if existing_paths & _INJECTED_PATHS:
        logger.info(
            "hermes_relay_bootstrap: detected existing /api/* routes "
            "(fork or upstream-merged); skipping injection."
        )
        return

    # Defer the heavy import until we're actually going to register. This
    # keeps the bootstrap cheap for `python -c "1+1"` style invocations
    # that never use aiohttp meaningfully.
    try:
        from . import _handlers
    except Exception as exc:
        logger.warning(
            "hermes_relay_bootstrap: cannot import _handlers (%s); "
            "skipping injection.",
            exc,
        )
        return

    try:
        _handlers.register_routes(app, adapter)
    except Exception as exc:
        logger.warning(
            "hermes_relay_bootstrap: register_routes raised %s; "
            "the gateway will run without injected sessions API.",
            exc,
        )
        return

    logger.info(
        "hermes_relay_bootstrap: injected %d /api/* routes onto upstream "
        "hermes-agent gateway",
        len(_INJECTED_PATHS),
    )


def _maybe_install_command_middleware(app, adapter) -> None:
    """Install the slash-command middleware on the app if not already present.

    The middleware intercepts ``/v1/chat/completions`` and ``/v1/runs``
    to handle gateway commands (``/help``, ``/commands``, ``/profile``,
    ``/provider``) and return decline notices for stateful commands,
    preventing the LLM from hallucinating responses for them.

    If the upstream ``api_server_slash`` module already exists (meaning
    the PR has been merged or the fork is deployed), the middleware is
    skipped — the native handler handles interception.
    """
    try:
        from . import _command_middleware
    except Exception as exc:
        logger.warning(
            "hermes_relay_bootstrap: cannot import _command_middleware (%s); "
            "skipping middleware installation.",
            exc,
        )
        return

    try:
        _command_middleware.maybe_install_middleware(app, adapter)
    except Exception as exc:
        logger.warning(
            "hermes_relay_bootstrap: maybe_install_middleware raised %s; "
            "slash commands will fall through to the LLM.",
            exc,
        )
