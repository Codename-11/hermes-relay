"""PulseForge-inspired interactive terminal session for voice-lab runs."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, TextIO

from .expressions import VoiceExpression
from .metrics import MetricsRecorder
from .playback import PlaybackError, play_audio_file
from .providers.base import ProviderRunError, ProviderUnavailable, VoiceRequest, VoiceResponse
from .registry import ProviderRegistry
from .terminal_ui import render_waveform, run_with_waveform

_CLEAR_SCREEN = "\033[2J\033[H"
_PROVIDER_ALIASES = {
    "openai": "openai_realtime",
    "grok": "xai_realtime",
    "xai": "xai_realtime",
}


@dataclass(slots=True)
class TuiSessionConfig:
    provider_id: str
    expression: VoiceExpression
    provider_options: dict[str, str] = field(default_factory=dict)
    output_dir: Path = Path("voice-lab-runs")
    event_dir: Path | None = None
    event_log: bool = True
    play: bool = False
    visual_mode: str = "on"
    initial_text: str | None = None
    json_output: bool = False


@dataclass(slots=True)
class TuiRunState:
    turn_index: int = 0
    last_prompt: str | None = None
    last_response: VoiceResponse | None = None
    last_events: list[dict[str, Any]] = field(default_factory=list)
    last_event_log: Path | None = None
    last_error: str | None = None


def normalize_provider_id(provider_id: str) -> str:
    value = provider_id.strip()
    return _PROVIDER_ALIASES.get(value, value)


def run_tui_session(
    *,
    registry: ProviderRegistry,
    config: TuiSessionConfig,
    input_stream: TextIO | None = None,
    output_stream: TextIO | None = None,
    status_stream: TextIO | None = None,
) -> int:
    input_stream = input_stream or sys.stdin
    output_stream = output_stream or sys.stdout
    status_stream = status_stream or sys.stderr
    if _should_run_textual_tui(
        config=config,
        input_stream=input_stream,
        output_stream=output_stream,
        status_stream=status_stream,
    ):
        try:
            from .textual_tui import run_textual_tui_session
        except ImportError as exc:
            status_stream.write(
                "voice-lab: Textual TUI unavailable; falling back to console loop. "
                "Install with `pip install -e \".[voice-lab]\"` for the full keyboard UI.\n"
            )
            status_stream.write(f"voice-lab: {exc}\n")
            status_stream.flush()
        else:
            return run_textual_tui_session(registry=registry, config=config)

    session = VoiceLabTui(
        registry=registry,
        config=config,
        input_stream=input_stream,
        output_stream=output_stream,
        status_stream=status_stream,
    )
    return session.run()


class VoiceLabTui:
    """Small persistent prompt loop with PulseForge-style panels."""

    def __init__(
        self,
        *,
        registry: ProviderRegistry,
        config: TuiSessionConfig,
        input_stream: TextIO,
        output_stream: TextIO,
        status_stream: TextIO,
    ) -> None:
        self.registry = registry
        self.config = config
        self.input_stream = input_stream
        self.output_stream = output_stream
        self.status_stream = status_stream
        self.state = TuiRunState()
        self.config.provider_id = normalize_provider_id(config.provider_id)
        self.config.output_dir.mkdir(parents=True, exist_ok=True)
        if self.config.event_dir:
            self.config.event_dir.mkdir(parents=True, exist_ok=True)

    def run(self) -> int:
        if self.config.initial_text:
            self._run_prompt(self.config.initial_text)

        while True:
            self._render()
            line = self._read_line()
            if line is None:
                return 0
            line = line.strip()
            if not line:
                continue
            if line.startswith(":"):
                if self._handle_command(line[1:].strip()):
                    return 0
                continue
            self._run_prompt(line)

    def _read_line(self) -> str | None:
        self.output_stream.write("\nvoice> ")
        self.output_stream.flush()
        line = self.input_stream.readline()
        if line == "":
            return None
        return line

    def _render(self) -> None:
        if _stream_is_tty(self.output_stream):
            self.output_stream.write(_CLEAR_SCREEN)
        lines = [
            self._header_line(),
            "",
            *self._panel_rows(),
            "",
            "Commands: :provider grok|openai|stub  :voice NAME  :model NAME  :emotion NAME",
            "          :tone NAME  :intensity 0.0-1.0  :tool on|off  :play on|off",
            "          :replay  :rerun  :save PATH  :log  :open-log  :help  :quit",
        ]
        self.output_stream.write("\n".join(lines) + "\n")
        self.output_stream.flush()

    def _header_line(self) -> str:
        stamp = datetime.now().strftime("%m-%d-%y  %I:%M:%S %p")
        provider = self.config.provider_id
        model = self.config.provider_options.get("model", "default")
        voice = self.config.provider_options.get("voice", "default")
        return (
            f" PULSEFORGE VOICE LAB  [ITERATION]    provider={provider}    "
            f"model={model}    voice={voice}    {stamp} "
        )

    def _panel_rows(self) -> list[str]:
        left = self._signal_panel()
        center = self._run_panel()
        right = self._settings_panel()
        width_left = 28
        width_center = 50
        width_right = 30
        rows = []
        total = max(len(left), len(center), len(right))
        for index in range(total):
            lval = left[index] if index < len(left) else ""
            cval = center[index] if index < len(center) else ""
            rval = right[index] if index < len(right) else ""
            rows.append(
                f"{lval:<{width_left}}  {cval:<{width_center}}  {rval:<{width_right}}"
            )
        return rows

    def _signal_panel(self) -> list[str]:
        metrics = self.state.last_response.metrics if self.state.last_response else None
        audio_bytes = _audio_bytes(self.state.last_events)
        levels = _audio_levels(self.state.last_events)
        peak = max(levels) if levels else 0.0
        return [
            "-- SIGNAL DATA --",
            f"STATUS: {_status_text(self.state)}",
            f"TURN:   {self.state.turn_index}",
            f"BYTES:  {audio_bytes}",
            f"PEAK:   {peak:.2f}",
            f"1ST MS: {_fmt_ms(metrics.first_audio_ms if metrics else None)}",
            f"DONE:   {_fmt_ms(metrics.response_done_ms if metrics else None)}",
            f"GAP:    {_fmt_ms(metrics.audio_chunk_gap_ms if metrics else None)}",
        ]

    def _run_panel(self) -> list[str]:
        levels = _audio_levels(self.state.last_events)
        waveform = render_waveform(0, width=44, unicode=_supports_unicode(self.output_stream), levels=levels)
        prompt = self.state.last_prompt or "(enter text to synthesize)"
        if len(prompt) > 46:
            prompt = prompt[:43] + "..."
        response = self.state.last_response
        audio = str(response.audio_path) if response else "---"
        if len(audio) > 46:
            audio = "..." + audio[-43:]
        event_log = str(self.state.last_event_log) if self.state.last_event_log else "---"
        if len(event_log) > 46:
            event_log = "..." + event_log[-43:]
        error = self.state.last_error or ""
        if len(error) > 46:
            error = error[:43] + "..."
        return [
            "-- RUN OUTPUT --",
            f"[{waveform}]",
            f"PROMPT: {prompt}",
            f"AUDIO:  {audio}",
            f"LOG:    {event_log}",
            f"ERROR:  {error or '---'}",
        ]

    def _settings_panel(self) -> list[str]:
        expression = self.config.expression
        options = self.config.provider_options
        return [
            "-- VOICE SETTINGS --",
            f"EMOTION: {expression.emotion or 'neutral'}",
            f"TONE:    {expression.tone or 'natural'}",
            f"INTENSE: {expression.intensity:.2f}",
            f"PACE:    {expression.pace}",
            f"STYLE:   {expression.style}",
            f"PLAY:    {'on' if self.config.play else 'off'}",
            f"TOOLS:   {options.get('tool_scaffold', 'off')}",
        ]

    def _handle_command(self, command: str) -> bool:
        if not command:
            return False
        name, _, arg = command.partition(" ")
        name = name.lower()
        arg = arg.strip()

        if name in {"q", "quit", "exit"}:
            return True
        if name in {"h", "help"}:
            self._print_help()
            return False
        if name == "provider":
            self._set_provider(arg)
            return False
        if name == "voice":
            self._set_option("voice", arg)
            return False
        if name == "model":
            self._set_option("model", arg)
            return False
        if name == "option":
            self._set_provider_option(arg)
            return False
        if name == "tool":
            self._set_on_off_option("tool_scaffold", arg)
            return False
        if name == "play":
            self.config.play = _parse_on_off(arg, default=self.config.play)
            return False
        if name == "json":
            self.config.json_output = _parse_on_off(arg, default=self.config.json_output)
            return False
        if name in {"emotion", "tone", "pace", "style", "persona"}:
            self._set_expression_value(name, arg)
            return False
        if name == "intensity":
            self._set_intensity(arg)
            return False
        if name == "replay":
            self._replay_last()
            return False
        if name == "rerun":
            if self.state.last_prompt:
                self._run_prompt(self.state.last_prompt)
            return False
        if name == "save":
            self._save_last(arg)
            return False
        if name == "log":
            self._print_log_path()
            return False
        if name == "open-log":
            self._open_log()
            return False
        self._message(f"Unknown command: :{name}. Type :help.")
        return False

    def _set_provider(self, value: str) -> None:
        provider_id = normalize_provider_id(value)
        if provider_id not in self.registry.provider_ids():
            self._message(f"Unknown provider: {value}")
            return
        self.config.provider_id = provider_id
        self.config.provider_options.pop("model", None)
        self.config.provider_options.pop("voice", None)

    def _set_option(self, key: str, value: str) -> None:
        if not value:
            self.config.provider_options.pop(key, None)
            return
        self.config.provider_options[key] = value

    def _set_provider_option(self, value: str) -> None:
        if "=" not in value:
            self._message("Use :option key=value")
            return
        key, item = value.split("=", 1)
        key = key.strip()
        if not key:
            self._message("Provider option key cannot be empty")
            return
        self.config.provider_options[key] = item.strip()

    def _set_on_off_option(self, key: str, value: str) -> None:
        enabled = _parse_on_off(value, default=False)
        self.config.provider_options[key] = "true" if enabled else "false"

    def _set_expression_value(self, name: str, value: str) -> None:
        if name == "persona":
            self.config.expression.persona_instructions = value or None
            return
        setattr(self.config.expression, name, value or None)

    def _set_intensity(self, value: str) -> None:
        try:
            self.config.expression.intensity = max(0.0, min(1.0, float(value)))
        except ValueError:
            self._message("Intensity must be a number from 0.0 to 1.0")

    def _run_prompt(self, prompt: str) -> None:
        self.state.turn_index += 1
        self.state.last_prompt = prompt
        self.state.last_error = None
        output_path = self._audio_path()
        event_log = self._event_log_path(output_path)
        expression = VoiceExpression(
            emotion=self.config.expression.emotion,
            tone=self.config.expression.tone,
            intensity=self.config.expression.intensity,
            pace=self.config.expression.pace,
            style=self.config.expression.style,
            pronunciation_hints=list(self.config.expression.pronunciation_hints),
            interruption_behavior=self.config.expression.interruption_behavior,
            persona_instructions=self.config.expression.persona_instructions,
            provider_overrides=dict(self.config.provider_options),
        )
        request = VoiceRequest(
            text=prompt,
            expression=expression,
            output_path=output_path,
            provider_options=dict(self.config.provider_options),
        )
        recorder = MetricsRecorder()
        provider = self.registry.create(self.config.provider_id)
        try:
            response = run_with_waveform(
                label=f"{self.config.provider_id} tui",
                recorder=recorder,
                visual_mode=self.config.visual_mode,
                stream=self.status_stream,
                fn=lambda: provider.run_text(request, recorder),
            )
            events = recorder.events_as_dicts()
            if event_log:
                _write_event_log(event_log, events)
            self.state.last_response = response
            self.state.last_events = events
            self.state.last_event_log = event_log
            if self.config.json_output:
                self._message(json.dumps(response.to_dict(), indent=2, sort_keys=True))
            if self.config.play:
                self._play_response(response)
        except (ProviderUnavailable, ProviderRunError, PlaybackError, KeyError) as exc:
            self.state.last_error = str(exc)
            self.state.last_events = recorder.events_as_dicts()
            self.state.last_event_log = event_log

    def _audio_path(self) -> Path:
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        safe_provider = _slug(self.config.provider_id)
        return self.config.output_dir / f"{stamp}-{self.state.turn_index:03d}-{safe_provider}.wav"

    def _event_log_path(self, audio_path: Path) -> Path | None:
        if not self.config.event_log:
            return None
        event_dir = self.config.event_dir or self.config.output_dir
        return event_dir / f"{audio_path.stem}.jsonl"

    def _play_response(self, response: VoiceResponse) -> None:
        play_audio_file(response.audio_path)

    def _replay_last(self) -> None:
        response = self.state.last_response
        if not response:
            self._message("No rendered audio to replay yet.")
            return
        try:
            self._play_response(response)
        except PlaybackError as exc:
            self._message(str(exc))

    def _save_last(self, value: str) -> None:
        response = self.state.last_response
        if not response:
            self._message("No rendered audio to save yet.")
            return
        if not value:
            self._message("Use :save path\\to\\copy.wav")
            return
        target = Path(value).expanduser()
        if not target.is_absolute():
            target = Path.cwd() / target
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(response.audio_path, target)
        self._message(f"Saved {target}")

    def _print_log_path(self) -> None:
        self._message(str(self.state.last_event_log or "No event log yet."))

    def _open_log(self) -> None:
        path = self.state.last_event_log
        if not path:
            self._message("No event log yet.")
            return
        try:
            if os.name == "nt":
                os.startfile(path)  # type: ignore[attr-defined]
            elif sys.platform == "darwin":
                subprocess.Popen(["open", str(path)])
            else:
                subprocess.Popen(["xdg-open", str(path)])
        except OSError as exc:
            self._message(f"Could not open log: {exc}")

    def _print_help(self) -> None:
        self._message(
            "\n".join(
                [
                    "Voice Lab TUI commands:",
                    ":provider grok|openai|stub",
                    ":model NAME        :voice NAME",
                    ":emotion NAME      :tone NAME      :intensity 0.0-1.0",
                    ":pace NAME         :style NAME     :persona TEXT",
                    ":tool on|off       :play on|off    :json on|off",
                    ":option key=value  :replay        :rerun",
                    ":save PATH         :log           :open-log",
                    ":quit",
                    "",
                    "Any non-command line is sent as the next voice prompt.",
                ]
            )
        )

    def _message(self, text: str) -> None:
        self.output_stream.write(f"\n{text}\n")
        self.output_stream.flush()


def _write_event_log(path: Path, events: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as fh:
        for event in events:
            fh.write(json.dumps(event, sort_keys=True) + "\n")


def _audio_bytes(events: list[dict[str, Any]]) -> int:
    total = 0
    for event in events:
        if event.get("type") != "audio_chunk":
            continue
        data = event.get("data")
        if isinstance(data, dict) and isinstance(data.get("byte_count"), int):
            total += int(data["byte_count"])
    return total


def _audio_levels(events: list[dict[str, Any]]) -> list[float]:
    levels: list[float] = []
    for event in events:
        if event.get("type") != "audio_chunk":
            continue
        data = event.get("data")
        if not isinstance(data, dict):
            continue
        value = data.get("rms_level")
        if isinstance(value, (int, float)):
            levels.append(float(value))
    return levels


def _status_text(state: TuiRunState) -> str:
    if state.last_error:
        return "ERROR"
    if state.last_response:
        return "READY"
    return "IDLE"


def _fmt_ms(value: float | None) -> str:
    if value is None:
        return "---"
    return f"{value:.1f}"


def _slug(value: str) -> str:
    safe = "".join(ch if ch.isalnum() or ch in "._-" else "-" for ch in value.strip())
    return safe.strip("-") or "run"


def _parse_on_off(value: str, *, default: bool) -> bool:
    if not value:
        return default
    lowered = value.strip().lower()
    if lowered in {"1", "true", "yes", "on", "play"}:
        return True
    if lowered in {"0", "false", "no", "off", "stop"}:
        return False
    return default


def _stream_is_tty(stream: TextIO) -> bool:
    isatty = getattr(stream, "isatty", None)
    return bool(isatty and isatty())


def _supports_unicode(stream: TextIO) -> bool:
    encoding = (getattr(stream, "encoding", None) or "").lower()
    return "utf" in encoding or encoding in {"cp65001"}


def _should_run_textual_tui(
    *,
    config: TuiSessionConfig,
    input_stream: TextIO,
    output_stream: TextIO,
    status_stream: TextIO,
) -> bool:
    override = os.getenv("VOICE_LAB_TUI", "").strip().lower()
    if override in {"console", "simple", "off", "0", "false"}:
        return False
    if config.json_output or config.visual_mode == "off":
        return False
    if override in {"textual", "on", "1", "true"}:
        return True
    return (
        _stream_is_tty(input_stream)
        and _stream_is_tty(output_stream)
        and _stream_is_tty(status_stream)
    )
