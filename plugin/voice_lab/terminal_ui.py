"""Terminal status UI for voice-lab runs."""

from __future__ import annotations

import sys
import threading
import time
from collections.abc import Callable
from typing import TextIO, TypeVar

from .metrics import MetricsRecorder

T = TypeVar("T")

_UNICODE_BARS = "▁▂▃▄▅▆▇█"
_ASCII_BARS = "._-~=^#"
_CLEAR_LINE = "\r\033[2K"


def visual_mode_enabled(mode: str, stream: TextIO | None = None) -> bool:
    """Return whether an animation should render for the requested mode."""
    if mode == "off":
        return False
    if mode == "on":
        return True
    stream = stream or sys.stderr
    isatty = getattr(stream, "isatty", None)
    return bool(isatty and isatty())


def render_waveform(
    frame: int,
    *,
    width: int = 18,
    unicode: bool = True,
    levels: list[float] | None = None,
) -> str:
    """Build a compact waveform string from real audio levels when available."""
    bars = _UNICODE_BARS if unicode else _ASCII_BARS
    max_index = len(bars) - 1
    if levels:
        samples = list(levels[-width:])
        if len(samples) < width:
            samples = [0.0] * (width - len(samples)) + samples
    else:
        # Idle baseline: this makes it clear that no provider audio has landed yet.
        pulse = 0.18 if frame % 8 in {0, 1} else 0.04
        samples = [pulse] * width
    samples = [max(0.0, min(1.0, item)) for item in samples]
    return "".join(bars[min(max_index, max(0, round(value * max_index)))] for value in samples)


def run_with_waveform(
    *,
    label: str,
    recorder: MetricsRecorder,
    visual_mode: str,
    fn: Callable[[], T],
    stream: TextIO | None = None,
) -> T:
    """Run a blocking provider call while rendering a small progress waveform."""
    stream = stream or sys.stderr
    if not visual_mode_enabled(visual_mode, stream):
        return fn()

    result: list[T] = []
    error: list[BaseException] = []

    def _target() -> None:
        try:
            result.append(fn())
        except BaseException as exc:  # pragma: no cover - re-raised below
            error.append(exc)

    worker = threading.Thread(target=_target, name="voice-lab-provider", daemon=True)
    worker.start()
    frame = 0
    try:
        while worker.is_alive():
            _render_status_line(
                stream=stream,
                label=label,
                recorder=recorder,
                frame=frame,
            )
            frame += 1
            time.sleep(0.08)
        worker.join()
    finally:
        stream.write(_CLEAR_LINE)
        stream.flush()

    if error:
        raise error[0]
    return result[0]


def _render_status_line(
    *,
    stream: TextIO,
    label: str,
    recorder: MetricsRecorder,
    frame: int,
) -> None:
    elapsed = recorder.elapsed_ms()
    phase = _phase(recorder)
    audio_bytes = _audio_bytes(recorder)
    levels = _audio_levels(recorder)
    waveform = render_waveform(
        frame,
        unicode=_supports_unicode(stream),
        levels=levels,
        width=28,
    )
    stream.write(
        f"{_CLEAR_LINE}voice {label} | {waveform} | {phase} | "
        f"{audio_bytes} bytes | {elapsed:7.1f} ms"
    )
    stream.flush()


def _phase(recorder: MetricsRecorder) -> str:
    if recorder.first_audio_ms is not None:
        return "audio"
    if not recorder.events:
        return "starting"
    last = recorder.events[-1]
    if last.type == "server_event":
        server_type = last.data.get("server_event_type")
        if isinstance(server_type, str) and server_type:
            return server_type[:24]
    if last.type == "client_event_sent":
        client_type = last.data.get("client_event_type")
        if isinstance(client_type, str) and client_type:
            return client_type[:24]
    return last.type[:24]


def _audio_bytes(recorder: MetricsRecorder) -> int:
    total = 0
    for event in recorder.events:
        if event.type != "audio_chunk":
            continue
        value = event.data.get("byte_count")
        if isinstance(value, int):
            total += value
    return total


def _audio_levels(recorder: MetricsRecorder) -> list[float]:
    levels: list[float] = []
    for event in recorder.events:
        if event.type != "audio_chunk":
            continue
        value = event.data.get("rms_level")
        if isinstance(value, (int, float)):
            levels.append(float(value))
    return levels


def _supports_unicode(stream: TextIO) -> bool:
    encoding = (getattr(stream, "encoding", None) or "").lower()
    return "utf" in encoding or encoding in {"cp65001"}
