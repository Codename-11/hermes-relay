"""Phone platform adapter (Hermes-Relay plugin).

Makes the paired Hermes-Relay phone a first-class Hermes *platform* — a
peer of Discord / Telegram / ntfy that the agent can **proactively push
to** via ``send_message(target="phone", ...)``, cron ``deliver=phone``,
and channel-directory presence.

This is an additive relay capability delivered through the upstream plugin
API (``ctx.register_platform``) — **NO fork, NO upstream core change.**
The upstream platform-plugin registry (``gateway/platform_registry.py``)
consults plugin-registered adapters first, and the ``Platform`` enum
auto-extends for unknown names via ``_missing_``. The registration shape
mirrors the bundled ntfy adapter (``plugins/platforms/ntfy/adapter.py``),
the canonical "push platform" template.

Delivery model (two-way):
    Outbound (agent → phone): ``send()`` does NOT open a socket — it POSTs
    **loopback** to the already-running relay server (``:8767``), exactly like
    ``plugin/tools/android_tool.py``. The relay's ProactiveChannel forwards the
    message over the live phone WSS (the same connection that carries bridge
    commands), and the app surfaces it as a notification / inbox entry /
    session injection.

    Inbound (phone → agent, Phase 2c): ``connect()`` starts a background loop
    that long-polls the relay's loopback ``GET /phone/replies``. The relay and
    this adapter run in *different processes*, so a reply the phone sends over
    its WSS (``proactive.reply``) is buffered by the relay and drained here.
    Each reply is turned into an inbound ``MessageEvent`` and dispatched via
    ``handle_message`` — exactly how the bundled platform adapters feed inbound
    messages to the agent. The agent's answer rides the existing ``send()``
    back to the phone, so the conversation continues in the same thread
    (keyed by ``chat_id`` / ``reply_to`` carried on the original message).

    "Is a phone actually reachable?" is answered per-send by the relay (HTTP
    503 when no phone is subscribed), not at connect time.

Off by default:
    Nothing is pushed unless ``PHONE_ENABLED`` is truthy **and** a phone is
    paired + connected to the relay. ``check_fn`` enforces the env gate so
    an un-configured install never advertises the platform.

Environment variables (env wins over config.yaml ``extra``):
    PHONE_ENABLED              "1"/"true"/"yes"/"on" enables the platform (required)
    PHONE_RELAY_URL            Relay base URL. Default: reuse ANDROID_BRIDGE_URL,
                               else http://localhost:{ANDROID_RELAY_PORT|RELAY_PORT|8767}
    PHONE_RELAY_TOKEN          Optional bearer for the relay POST (loopback is
                               unauthenticated by default; sent only if set)
    PHONE_HOME_CHANNEL         Default chat_id for cron / home-channel delivery
                               (default: "phone")
    PHONE_HOME_CHANNEL_NAME    Human label for the home channel (default: "Phone")
    PHONE_ALLOWED_USERS        Allowlist (gateway _is_user_authorized integration)
    PHONE_ALLOW_ALL_USERS      Allow any user — dev only
"""

from __future__ import annotations

import asyncio
import logging
import os
import uuid
from dataclasses import dataclass
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

try:
    import httpx

    HTTPX_AVAILABLE = True
except ImportError:  # pragma: no cover - httpx is a Hermes dependency
    HTTPX_AVAILABLE = False
    httpx = None  # type: ignore[assignment]

# ``gateway.*`` is only importable inside the live hermes-agent (gateway)
# process. The plugin's own ``python -m unittest`` environment does not have
# it, so we guard the import and fall back to a minimal local ``SendResult``
# / sentinel base class. This keeps the pure helpers (URL resolution, payload
# building, the standalone sender) unit-testable without the gateway, while
# the real adapter binds to the real base class on the live box.
try:
    from gateway.config import Platform, PlatformConfig  # type: ignore
    from gateway.platforms.base import (  # type: ignore
        BasePlatformAdapter,
        MessageEvent,
        MessageType,
        SendResult,
    )

    GATEWAY_AVAILABLE = True
except Exception:  # pragma: no cover - exercised only off the live box
    GATEWAY_AVAILABLE = False
    Platform = None  # type: ignore[assignment]
    PlatformConfig = Any  # type: ignore[assignment,misc]
    BasePlatformAdapter = object  # type: ignore[assignment,misc]
    MessageEvent = None  # type: ignore[assignment,misc]
    MessageType = None  # type: ignore[assignment,misc]

    @dataclass
    class SendResult:  # type: ignore[no-redef]
        """Minimal stand-in mirroring the gateway's SendResult fields.

        Only the fields the phone adapter actually returns are modelled.
        Used solely when the gateway package is absent (tests).
        """

        success: bool
        message_id: Optional[str] = None
        error: Optional[str] = None
        retryable: bool = False


# Stable identity for the single paired phone "chat". A relay serves one
# phone at a time (per the bridge single-client model), so one chat_id is
# sufficient; the value is opaque and only used for routing/labels.
DEFAULT_CHAT_ID = "phone"

# Generous cap — the app can render long text in the inbox / injected
# session even though a system notification will visually truncate. Picked
# to match the ntfy/Telegram family so cross-platform agents behave
# consistently when a message is also fanned out elsewhere.
MAX_MESSAGE_LENGTH = 4096

# Truthy spellings accepted for boolean env flags.
_TRUTHY = ("1", "true", "yes", "on")

# Relay routes that the ProactiveChannel serves (see plugin/relay/server.py).
_RELAY_MESSAGE_PATH = "/phone/message"  # outbound: agent → phone
_RELAY_REPLIES_PATH = "/phone/replies"  # inbound long-poll: phone → agent

# Inbound reply long-poll tuning. The adapter asks the relay to hold the poll
# open for ``_REPLY_POLL_TIMEOUT`` seconds; the HTTP read timeout adds margin
# so a normal empty poll returns from the server, not a client timeout.
_REPLY_POLL_TIMEOUT = 25.0
_REPLY_READ_TIMEOUT = _REPLY_POLL_TIMEOUT + 10.0
# Backoff (seconds) when the relay is unreachable / erroring, so a down relay
# doesn't spin the loop. Resets to fast polling once a poll succeeds.
_REPLY_BACKOFF = (2.0, 5.0, 10.0, 30.0)


# ---------------------------------------------------------------------------
# Pure helpers (no gateway dependency — unit-testable in isolation)
# ---------------------------------------------------------------------------


def _phone_enabled() -> bool:
    """Return True when the operator has opted the phone platform in."""
    return os.getenv("PHONE_ENABLED", "").strip().lower() in _TRUTHY


def _relay_base_url() -> str:
    """Resolve the loopback relay base URL.

    Honors ``PHONE_RELAY_URL`` first, then reuses the same convention as
    ``plugin/tools/android_tool.py`` (``ANDROID_BRIDGE_URL`` /
    ``ANDROID_RELAY_PORT`` / ``RELAY_PORT``) so a single override flips both
    the android tools and this adapter. Defaults to ``http://localhost:8767``.
    """
    explicit = os.getenv("PHONE_RELAY_URL", "").strip()
    if explicit:
        return explicit.rstrip("/")
    bridge = os.getenv("ANDROID_BRIDGE_URL", "").strip()
    if bridge:
        return bridge.rstrip("/")
    port = os.getenv("ANDROID_RELAY_PORT", os.getenv("RELAY_PORT", "8767")).strip() or "8767"
    return f"http://localhost:{port}"


def _home_channel() -> str:
    """chat_id used for cron / home-channel delivery."""
    return os.getenv("PHONE_HOME_CHANNEL", "").strip() or DEFAULT_CHAT_ID


def _home_channel_name() -> str:
    """Human label for the home channel / default notification title."""
    return os.getenv("PHONE_HOME_CHANNEL_NAME", "").strip() or "Phone"


def _relay_url_and_headers() -> tuple[str, Dict[str, str]]:
    """Return the ``/phone/message`` URL and request headers.

    An ``Authorization`` header is only attached when ``PHONE_RELAY_TOKEN``
    is set — the relay route is loopback-unauthenticated by default, exactly
    like the bridge HTTP routes.
    """
    headers = {"Content-Type": "application/json"}
    token = os.getenv("PHONE_RELAY_TOKEN", "").strip()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return f"{_relay_base_url()}{_RELAY_MESSAGE_PATH}", headers


def _build_message_payload(
    chat_id: Optional[str],
    content: str,
    *,
    title: Optional[str] = None,
    surfacing: Optional[str] = None,
    reply_to: Optional[str] = None,
    metadata: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Build the JSON body POSTed to the relay's ``/phone/message`` route.

    ``surfacing`` is an optional route hint (``"notification"`` /
    ``"inbox"`` / ``"session"``); ``None`` means "let the app decide"
    (default = notification + inbox). It can also be supplied inside
    ``metadata["surfacing"]`` — send_message callers pass arbitrary
    metadata, so we lift it out if present.
    """
    md = dict(metadata or {})
    surf = surfacing or md.pop("surfacing", None)
    resolved_title = title or md.pop("title", None) or _home_channel_name()
    return {
        "chat_id": chat_id or _home_channel(),
        "text": (content or "")[:MAX_MESSAGE_LENGTH],
        "title": resolved_title,
        "surfacing": surf,
        "reply_to": reply_to,
        "metadata": md,
    }


def _replies_url_and_headers() -> tuple[str, Dict[str, str]]:
    """Return the ``/phone/replies`` long-poll URL and request headers.

    GET has no body, so unlike :func:`_relay_url_and_headers` no
    ``Content-Type`` is set. ``Authorization`` is attached only when
    ``PHONE_RELAY_TOKEN`` is configured (loopback is unauthenticated by
    default).
    """
    headers: Dict[str, str] = {}
    token = os.getenv("PHONE_RELAY_TOKEN", "").strip()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return f"{_relay_base_url()}{_RELAY_REPLIES_PATH}", headers


def _normalize_reply(
    reply: Any, default_chat_id: str
) -> Optional[Dict[str, Any]]:
    """Normalize one raw reply record from ``/phone/replies`` for dispatch.

    The relay already validates/normalizes on the way in, but the adapter is
    defensive against a malformed or older relay: a non-dict or empty-text
    record yields ``None`` (skipped). ``chat_id`` falls back to the home
    channel so a reply with no thread still continues the default
    conversation; ``message_id`` is synthesized when absent. Pure + gateway-
    independent so it is unit-testable without the gateway package.
    """
    if not isinstance(reply, dict):
        return None
    text = reply.get("text")
    if not isinstance(text, str) or not text.strip():
        return None
    return {
        "text": text,
        "chat_id": reply.get("chat_id") or default_chat_id,
        "reply_to": reply.get("reply_to"),
        "message_id": str(reply.get("message_id") or uuid.uuid4().hex[:12]),
    }


# ---------------------------------------------------------------------------
# Adapter
# ---------------------------------------------------------------------------


class PhoneAdapter(BasePlatformAdapter):  # type: ignore[misc,valid-type]
    """Two-way platform adapter for the paired Hermes-Relay phone.

    The four abstract methods of :class:`BasePlatformAdapter` are
    implemented; everything else uses the base defaults. ``send()`` forwards
    to the relay over loopback (outbound); ``connect()`` additionally starts
    a background long-poll loop that drains the user's replies from the relay
    and dispatches them inbound via ``handle_message`` (Phase 2c).
    """

    MAX_MESSAGE_LENGTH = MAX_MESSAGE_LENGTH

    def __init__(self, config: "PlatformConfig"):
        super().__init__(config=config, platform=Platform("phone"))
        extra = getattr(config, "extra", {}) or {}
        # Cached for get_chat_info(); send-time URL/title always re-read env
        # so a live `.env` edit takes effect without a reconnect.
        self._home_channel_name: str = extra.get("home_channel_name") or _home_channel_name()
        self._http_client: Optional["httpx.AsyncClient"] = None
        # Inbound reply long-poll task (Phase 2c). Spawned in connect(),
        # cancelled in disconnect().
        self._poll_task: Optional["asyncio.Task[Any]"] = None

    # -- Connection lifecycle ----------------------------------------------

    async def connect(self, *, is_reconnect: bool = False) -> bool:
        """Open the outbound HTTP client and start the inbound reply loop.

        Outbound is loopback POSTs (no socket held). Inbound is a background
        long-poll against the relay's ``/phone/replies`` — the phone's replies
        are buffered by the relay (different process) and drained here.

        The gateway's platform supervisor invokes ``connect(is_reconnect=...)``
        (the ``BasePlatformAdapter.connect`` contract, forwarded from
        ``gateway/run.py``), so the keyword MUST be accepted. A bare
        ``connect(self)`` raises ``TypeError`` at connect time — the adapter
        never comes up and the inbound reply loop never starts, so the relay
        buffers replies that nothing drains (the Phase 2c round-trip silently
        dies here). ``is_reconnect`` does not change behavior today: we always
        drain the relay's small bounded reply buffer. Using it to drop stale
        replies on a cold boot is a possible future refinement.
        """
        logger.warning("[phone-diag] %s.connect() CALLED (is_reconnect=%s)", self.name, is_reconnect)
        if not HTTPX_AVAILABLE:
            logger.warning("[%s] httpx not installed — cannot push to phone", self.name)
            return False
        if not _phone_enabled():
            logger.warning("[phone-diag] %s PHONE_ENABLED falsy at connect — staying offline", self.name)
            return False
        try:
            self._http_client = httpx.AsyncClient(timeout=15.0)
            self._mark_connected()
            self._poll_task = asyncio.create_task(self._run_reply_loop())
            logger.warning(
                "[phone-diag] [%s] Connected (two-way), reply loop spawned — relay=%s", self.name, _relay_base_url()
            )
            return True
        except Exception as e:  # pragma: no cover - defensive
            logger.error("[%s] Failed to connect: %s", self.name, e)
            return False

    async def disconnect(self) -> None:
        """Stop the reply loop and tear down the HTTP client."""
        self._running = False
        self._mark_disconnected()
        if self._poll_task is not None:
            self._poll_task.cancel()
            try:
                await self._poll_task
            except asyncio.CancelledError:
                pass
            except Exception:  # pragma: no cover - defensive
                pass
            self._poll_task = None
        if self._http_client is not None:
            await self._http_client.aclose()
            self._http_client = None
        logger.info("[%s] Disconnected", self.name)

    # -- Inbound reply loop (phone → agent) --------------------------------

    async def _run_reply_loop(self) -> None:
        """Long-poll the relay for phone replies and dispatch them inbound.

        Mirrors the streaming-receive loop pattern of the bundled adapters
        (e.g. ntfy ``_run_stream``): a ``self._running``-guarded loop with
        backoff on transient failures. Each drained reply becomes a
        ``MessageEvent`` handed to ``handle_message`` — the agent then
        processes it on the ``phone`` platform and answers via ``send()``.
        """
        url, headers = _replies_url_and_headers()
        params = {"timeout": str(int(_REPLY_POLL_TIMEOUT))}
        backoff_idx = 0
        logger.warning("[phone-diag] reply loop ENTERED; running=%s url=%s", self._running, url)

        while self._running:
            client = self._http_client
            if client is None:
                return
            try:
                resp = await client.get(
                    url, headers=headers, params=params, timeout=_REPLY_READ_TIMEOUT
                )
            except asyncio.CancelledError:
                return
            except Exception as e:
                if not self._running:
                    return
                delay = _REPLY_BACKOFF[min(backoff_idx, len(_REPLY_BACKOFF) - 1)]
                logger.debug(
                    "[%s] reply poll failed: %s — retrying in %.0fs",
                    self.name, e, delay,
                )
                try:
                    await asyncio.sleep(delay)
                except asyncio.CancelledError:
                    return
                backoff_idx += 1
                continue

            # A successful poll resets backoff; a non-200 is transient — pause
            # briefly then re-poll rather than hammering.
            backoff_idx = 0
            if resp.status_code != 200:
                logger.debug("[%s] /phone/replies HTTP %d", self.name, resp.status_code)
                try:
                    await asyncio.sleep(_REPLY_BACKOFF[0])
                except asyncio.CancelledError:
                    return
                continue

            try:
                data = resp.json()
                replies = data.get("replies", []) if isinstance(data, dict) else []
            except Exception:
                replies = []

            for reply in replies:
                if not self._running:
                    return
                try:
                    await self._dispatch_reply(reply)
                except Exception as e:
                    logger.warning("[%s] failed to dispatch reply: %s", self.name, e)

    async def _dispatch_reply(self, reply: Dict[str, Any]) -> None:
        """Turn one relay reply record into an inbound agent message.

        The reply continues the originating conversation: ``chat_id`` keys the
        session and ``reply_to`` anchors it to the answered message. The phone
        is the single paired user already authenticated by the relay's pairing
        layer, so the source is built ``role_authorized=True`` — it bypasses
        the gateway's per-platform user allowlist without requiring the
        operator to set ``PHONE_ALLOW_ALL_USERS`` (the relay session token IS
        the auth check the gateway is asking about).
        """
        normalized = _normalize_reply(reply, _home_channel())
        if normalized is None:
            return
        chat_id = normalized["chat_id"]
        source = self.build_source(
            chat_id=chat_id,
            chat_name=self._home_channel_name,
            chat_type="dm",
            user_id=chat_id,
            user_name=self._home_channel_name,
            message_id=normalized["reply_to"],
            role_authorized=True,
        )
        event = MessageEvent(
            text=normalized["text"],
            message_type=MessageType.TEXT,
            source=source,
            message_id=normalized["message_id"],
            reply_to_message_id=normalized["reply_to"],
            raw_message=reply,
        )
        logger.info(
            "[%s] inbound reply chat=%s reply_to=%s len=%d",
            self.name, chat_id, normalized["reply_to"], len(normalized["text"]),
        )
        await self.handle_message(event)

    # -- Outbound messaging -------------------------------------------------

    async def send(
        self,
        chat_id: str,
        content: str,
        reply_to: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> "SendResult":
        """Forward a proactive message to the phone via the relay."""
        if not content or not content.strip():
            return SendResult(success=False, error="Refusing to send empty message")

        client = self._http_client
        if client is None:
            return SendResult(
                success=False, error="HTTP client not initialized", retryable=True
            )

        payload = _build_message_payload(
            chat_id, content, reply_to=reply_to, metadata=metadata
        )
        url, headers = _relay_url_and_headers()

        try:
            resp = await client.post(url, json=payload, headers=headers, timeout=15.0)
        except Exception as e:
            logger.warning("[%s] relay POST failed: %s", self.name, e)
            return SendResult(
                success=False, error=f"relay unreachable: {e}", retryable=True
            )

        if resp.status_code == 503:
            return SendResult(
                success=False,
                error="No phone connected. Open the Hermes app on your phone and connect.",
                retryable=True,
            )
        if resp.status_code >= 300:
            body = resp.text[:200]
            logger.warning("[%s] send failed HTTP %d: %s", self.name, resp.status_code, body)
            return SendResult(success=False, error=f"relay HTTP {resp.status_code}: {body}")

        message_id = None
        try:
            data = resp.json()
            if isinstance(data, dict):
                message_id = data.get("message_id")
        except Exception:
            pass
        return SendResult(success=True, message_id=message_id or uuid.uuid4().hex[:12])

    async def send_typing(self, chat_id: str, metadata=None) -> None:
        """The phone has no typing-indicator primitive for proactive pushes."""
        return None

    async def get_chat_info(self, chat_id: str) -> Dict[str, Any]:
        """Return basic info about the phone "chat"."""
        return {"name": self._home_channel_name or "Phone", "type": "dm"}


# ---------------------------------------------------------------------------
# Registry hooks (mirror ntfy)
# ---------------------------------------------------------------------------


def check_requirements() -> bool:
    """Return True when the phone platform is installable + opted in.

    Reads env directly to stay cheap (no full config load). httpx is a hard
    requirement for the outbound POST.
    """
    return HTTPX_AVAILABLE and _phone_enabled()


def validate_config(config) -> bool:
    """Validate that the phone platform is enabled (env or config.yaml)."""
    if _phone_enabled():
        return True
    extra = getattr(config, "extra", {}) or {}
    return bool(extra.get("enabled"))


def is_connected(config) -> bool:
    """Reflect env-only configuration in ``hermes gateway status``."""
    return _phone_enabled()


def _env_enablement() -> Optional[dict]:
    """Seed ``PlatformConfig.extra`` from env during gateway config load.

    Returns ``None`` when the platform isn't opted in so the registry skips
    auto-enabling. The special ``home_channel`` key is promoted to a proper
    ``HomeChannel`` by the core hook (same contract ntfy relies on).
    """
    if not _phone_enabled():
        return None
    seed: dict = {
        "enabled": True,
        "relay_url": _relay_base_url(),
        "home_channel_name": _home_channel_name(),
    }
    home = _home_channel()
    seed["home_channel"] = {"chat_id": home, "name": _home_channel_name()}
    return seed


async def _standalone_send(
    pconfig,
    chat_id: str,
    message: str,
    *,
    thread_id: Optional[str] = None,
    media_files: Optional[List[str]] = None,
    force_document: bool = False,
) -> Dict[str, Any]:
    """Out-of-process push for cron / send_message_tool fallbacks.

    Used by ``tools/send_message_tool`` and the cron scheduler when the
    gateway runner is not in this process (e.g. ``hermes cron`` running
    standalone). Without this hook, ``deliver=phone`` cron jobs fail with
    "No live adapter for platform".

    ``thread_id`` / ``media_files`` / ``force_document`` are accepted for
    signature parity; the proactive push is text-first in v1.
    """
    if not HTTPX_AVAILABLE:
        return {"error": "phone standalone send: httpx not installed"}
    if not _phone_enabled():
        return {"error": "phone platform disabled (set PHONE_ENABLED=1)"}

    payload = _build_message_payload(chat_id, message or "")
    url, headers = _relay_url_and_headers()
    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post(url, json=payload, headers=headers)
    except Exception as e:
        return {"error": f"phone standalone send failed: relay unreachable: {e}"}

    if resp.status_code == 503:
        return {"error": "phone standalone send: no phone connected"}
    if resp.status_code >= 300:
        return {"error": f"phone HTTP {resp.status_code}: {resp.text[:200]}"}

    message_id = None
    try:
        data = resp.json()
        if isinstance(data, dict):
            message_id = data.get("message_id")
    except Exception:
        pass
    return {
        "success": True,
        "platform": "phone",
        "chat_id": payload["chat_id"],
        "message_id": message_id or uuid.uuid4().hex[:12],
    }


def register_phone_platform(ctx) -> None:
    """Register the ``phone`` platform with the Hermes plugin system.

    Called from ``plugin/__init__.py:register`` (guarded). Safe to call on
    hosts without ``register_platform`` — the caller swallows AttributeError.
    """
    ctx.register_platform(
        name="phone",
        label="Phone",
        adapter_factory=lambda cfg: PhoneAdapter(cfg),
        check_fn=check_requirements,
        validate_config=validate_config,
        is_connected=is_connected,
        required_env=["PHONE_ENABLED"],
        install_hint=(
            "Set PHONE_ENABLED=1 in ~/.hermes/.env, run the relay, and pair "
            "the Hermes-Relay app with 'Let Hermes message me' enabled."
        ),
        env_enablement_fn=_env_enablement,
        # Cron home-channel delivery — `deliver=phone` routes to
        # PHONE_HOME_CHANNEL when set.
        cron_deliver_env_var="PHONE_HOME_CHANNEL",
        # Out-of-process cron / send_message delivery. Without this hook,
        # deliver=phone cron jobs fail with "No live adapter".
        standalone_sender_fn=_standalone_send,
        # Auth env vars for _is_user_authorized() integration.
        allowed_users_env="PHONE_ALLOWED_USERS",
        allow_all_env="PHONE_ALLOW_ALL_USERS",
        max_message_length=MAX_MESSAGE_LENGTH,
        emoji="📱",
        # No publisher-controlled identity to redact — the phone is the
        # paired single user.
        pii_safe=True,
        allow_update_command=True,
        platform_hint=(
            "You are messaging the user's paired phone via Hermes-Relay. This "
            "is a proactive push channel — the user may not be looking at the "
            "screen. Keep messages concise and high-signal; lead with what "
            "matters. Plain text and light markdown render fine."
        ),
    )
    logger.info("Registered 'phone' platform (proactive push via relay)")
