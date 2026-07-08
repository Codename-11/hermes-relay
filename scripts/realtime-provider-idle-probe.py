#!/usr/bin/env python3
"""Phase 0 idle-tolerance probe for ADR 33 / the background-Hermes-runs plan.

Opens a provider-native realtime-agent socket (xAI or OpenAI) via the existing
relay adapters, configures it, then holds it QUIESCENT — no ``response.create``,
no input audio — for a series of idle windows. It logs whether the socket
survives, any server-side close codes/timeouts, and any turn/VAD artifacts on the
first post-idle ``response.create``.

This answers the only factual unknown that gates ADR 33 default-on: can a
provider session hold the floor while a background Hermes run completes, or must
that provider's Tier B path keep-alive / reopen the socket instead?

This is a DEV PROBE — it is not imported by the relay and ships nothing. Run it
on the relay host where provider credentials are configured (env or xAI OAuth
store), with the repo root on PYTHONPATH:

    python scripts/realtime-provider-idle-probe.py --provider xai
    python scripts/realtime-provider-idle-probe.py --provider openai --windows 30,60,120

xAI's server closes a conversation after 900s of inactivity (observed live
2026-07-08), so the interesting windows are around that deadline. To reproduce
the idle-close and then verify the broker's silent-PCM keepalive defeats it:

    # repro: should die at ~900s with "timed out ... due to inactivity"
    python scripts/realtime-provider-idle-probe.py --provider xai --windows 960

    # fix: same window, pinging 100ms of silence every 240s like the broker
    python scripts/realtime-provider-idle-probe.py --provider xai --windows 960 --keepalive-ms 240000

Record the per-provider verdict in docs/realtime-voice-poc.md ("Idle tolerance").
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import sys
import time
from pathlib import Path

# Allow running as a bare script from the repo root.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from plugin.relay.realtime_agent.models import (  # noqa: E402
    HERMES_TOOL_SCHEMAS,
    ProviderEventKind,
    RealtimeAgentSessionConfig,
)
from plugin.relay.realtime_agent.providers.openai import (  # noqa: E402
    OpenAIRealtimeAgentProvider,
)
from plugin.relay.realtime_agent.providers.xai import (  # noqa: E402
    XAIRealtimeAgentProvider,
)

_PROVIDERS = {
    "xai": (XAIRealtimeAgentProvider, "grok-voice-latest", "ember", 24000),
    "openai": (OpenAIRealtimeAgentProvider, "gpt-realtime-2", "alloy", 24000),
}


def _probe_provider_options(provider_id: str) -> dict:
    """Replicate the broker's credential injection (_provider_options).

    The provider adapters resolve auth from provider_options first; the live
    relay injects the Hermes xAI OAuth token there. Without this the probe
    only works where raw env keys exist — which is exactly not the relay host.
    """
    options: dict = {}
    if provider_id != "xai":
        return options
    try:
        from plugin.relay.config import RelayConfig  # noqa: E402
        from plugin.relay.realtime_voice import (  # noqa: E402
            _read_relay_xai_oauth_token,
        )
        from plugin.relay.realtime_agent.providers.xai import (  # noqa: E402
            _websocket_url_from_base,
        )

        token = _read_relay_xai_oauth_token(RelayConfig.from_env())
        if token is not None:
            options["oauth_access_token"] = token.access_token
            ws_url = _websocket_url_from_base(token.base_url)
            if ws_url:
                options["url"] = ws_url
            print(f"[probe] using relay xAI OAuth token (source: {token.source})")
    except Exception as exc:  # noqa: BLE001 - probe reports and falls through
        print(f"[probe] relay xAI OAuth lookup failed (env fallback): {exc!r}")
    return options


def _session_config(provider_id: str) -> RealtimeAgentSessionConfig:
    _, model, voice, rate = _PROVIDERS[provider_id]
    return RealtimeAgentSessionConfig(
        provider=provider_id,
        model=model,
        voice=voice,
        sample_rate=rate,
        profile=None,
        hermes_session_id=None,
        instructions="You are an idle-tolerance probe. Do not speak unless asked.",
        provider_options=_probe_provider_options(provider_id),
        tools=HERMES_TOOL_SCHEMAS,
    )


async def _drain_events(connection, stop: asyncio.Event, sink: list[str]) -> None:
    """Record provider events (esp. ERROR/close) while we idle."""
    try:
        async for event in connection.events():
            sink.append(f"{time.monotonic():.1f}s {event.kind.value}")
            if event.kind == ProviderEventKind.ERROR:
                sink.append(f"  ERROR payload={event.payload!r}")
            if stop.is_set():
                return
    except Exception as exc:  # noqa: BLE001 - probe wants the raw failure
        sink.append(f"{time.monotonic():.1f}s events() raised {exc!r}")
    finally:
        stop.set()


async def _keepalive_pinger(
    connection,
    sample_rate: int,
    interval_s: float,
    stop: asyncio.Event,
    sink: list[str],
) -> None:
    """Mirror the broker's _provider_keepalive_loop: 100ms of silent PCM
    (16-bit mono, never committed) every interval while the probe idles."""
    chunk = b"\x00" * max(2, int(sample_rate * 2 * 0.1))
    while not stop.is_set():
        try:
            await asyncio.wait_for(stop.wait(), timeout=interval_s)
            return
        except asyncio.TimeoutError:
            pass
        try:
            await connection.send_audio(chunk, sample_rate)
            sink.append(f"{time.monotonic():.1f}s keepalive ping ({len(chunk)} bytes silence)")
        except Exception as exc:  # noqa: BLE001 - probe wants the raw failure
            sink.append(f"{time.monotonic():.1f}s keepalive send failed: {exc!r}")
            return


async def probe(provider_id: str, windows: list[int], keepalive_ms: int = 0) -> int:
    cls, *_, sample_rate = _PROVIDERS[provider_id]
    provider = cls()
    print(f"[probe] connecting {provider_id} ...")
    connection = await provider.connect(_session_config(provider_id))
    print("[probe] connected + configured")

    stop = asyncio.Event()
    sink: list[str] = []
    reader = asyncio.create_task(_drain_events(connection, stop, sink))
    pinger: asyncio.Task | None = None
    if keepalive_ms > 0:
        print(f"[probe] keepalive enabled: silent PCM every {keepalive_ms / 1000:.0f}s")
        pinger = asyncio.create_task(
            _keepalive_pinger(connection, sample_rate, keepalive_ms / 1000.0, stop, sink)
        )

    verdict = "hold-floor-ok"
    try:
        for window in windows:
            print(f"[probe] idling {window}s (no response.create, no audio) ...")
            try:
                await asyncio.wait_for(stop.wait(), timeout=window)
                # stop fired => the socket closed / errored while idle.
                print(f"[probe] socket DID NOT survive {window}s idle window")
                verdict = "must-reopen"
                break
            except asyncio.TimeoutError:
                print(f"[probe] survived {window}s idle")

            # Probe a post-idle turn for VAD/turn artifacts.
            print("[probe] requesting a short post-idle response ...")
            before = len(sink)
            await connection.send_text("Say the single word: ready.")
            await asyncio.sleep(8)
            produced = sink[before:]
            got_audio = any("audio_delta" in line for line in produced)
            got_error = any("error" in line for line in produced)
            print(f"[probe]   post-idle audio={got_audio} error={got_error}")
            if got_error or not got_audio:
                verdict = "needs-keepalive"
            with contextlib.suppress(Exception):
                await connection.cancel_response()
    finally:
        stop.set()
        if pinger is not None:
            pinger.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await pinger
        with contextlib.suppress(Exception):
            await connection.close()
        reader.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await reader

    print("\n[probe] event trace:")
    for line in sink:
        print("  " + line)
    print(f"\n[probe] VERDICT ({provider_id}): {verdict}")
    print("[probe] record this in docs/realtime-voice-poc.md -> Idle tolerance")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--provider", choices=sorted(_PROVIDERS), default="xai")
    parser.add_argument(
        "--windows",
        default="30,60,120",
        help="comma-separated idle windows in seconds",
    )
    parser.add_argument(
        "--keepalive-ms",
        type=int,
        default=0,
        help="send 100ms of silent PCM every N ms while idling (0 = off); "
        "mirrors the broker's provider keepalive",
    )
    args = parser.parse_args()
    windows = [int(w) for w in str(args.windows).split(",") if w.strip()]
    return asyncio.run(probe(args.provider, windows, keepalive_ms=args.keepalive_ms))


if __name__ == "__main__":
    raise SystemExit(main())
