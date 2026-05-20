"""Textual-backed PulseForge-style TUI for interactive voice-lab runs."""

from __future__ import annotations

import asyncio
import json
import os
import shutil
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.css.query import NoMatches
from textual.theme import Theme
from textual.widgets import Button, Footer, Input, Label, Select, Static

from .expressions import VoiceExpression
from .metrics import MetricsRecorder
from .playback import PlaybackError, play_audio_file
from .providers.base import ProviderRunError, ProviderUnavailable, VoiceRequest, VoiceResponse
from .registry import ProviderRegistry
from .terminal_ui import render_waveform
from .tui_session import (
    TuiRunState,
    TuiSessionConfig,
    _audio_bytes,
    _audio_levels,
    _fmt_ms,
    _slug,
    _write_event_log,
    normalize_provider_id,
)


EMOTIONS = ["neutral", "calm", "warm", "empathetic", "serious", "energetic", "playful"]
TONES = ["natural", "warm", "crisp", "calm", "professional", "expressive", "urgent"]
PACES = ["slow", "normal", "fast"]
STYLES = ["natural", "conversational", "concise", "dramatic", "assistant"]
PROVIDER_DEFAULTS = {
    "xai_tts": {"model": "xai-tts", "voice": "eve"},
    "openai_tts": {"model": "gpt-4o-mini-tts", "voice": "coral"},
    "openai_realtime": {"model": "gpt-realtime-2", "voice": "marin"},
    "xai_realtime": {"model": "grok-voice-latest", "voice": "eve"},
    "stub": {"model": "local-tone", "voice": "sine"},
}
PROVIDER_MODEL_CHOICES = {
    "xai_tts": ["xai-tts"],
    "openai_tts": ["gpt-4o-mini-tts"],
    "openai_realtime": ["gpt-realtime-2", "gpt-realtime"],
    "xai_realtime": ["grok-voice-latest"],
    "stub": ["local-tone"],
}
PROVIDER_VOICE_CHOICES = {
    "xai_tts": ["eve", "ara", "rex", "sal", "leo"],
    "openai_tts": ["coral", "alloy", "ash", "ballad", "echo", "fable", "nova", "onyx", "sage", "shimmer"],
    "openai_realtime": ["marin"],
    "xai_realtime": ["eve"],
    "stub": ["sine"],
}
COMMANDS = [
    ("Run prompt", "run"),
    ("Replay last audio", "replay"),
    ("Rerun last prompt", "rerun"),
    ("Save last audio", "save"),
    ("Show event log path", "log"),
    ("Open event log", "open-log"),
    ("Apply provider option", "option"),
    ("Toggle playback", "toggle-play"),
    ("Toggle tool scaffold", "toggle-tool"),
    ("Toggle JSON display", "toggle-json"),
    ("Focus prompt", "focus-prompt"),
    ("Toggle help", "help"),
    ("Quit", "quit"),
]


AMBER_BURN_THEME = Theme(
    name="voice-lab-amber",
    primary="#FFB000",
    secondary="#FFCC00",
    accent="#FFB000",
    foreground="#FFB000",
    background="#0A0A0A",
    surface="#111111",
    panel="#111111",
    boost="#221100",
    warning="#FFCC00",
    error="#FF4400",
    success="#FFB000",
    dark=True,
    variables={
        "amber": "#FFB000",
        "amber-dim": "#8A6200",
        "amber-dark": "#221100",
        "amber-bright": "#FFCC00",
        "bg": "#0A0A0A",
        "panel-bg": "#111111",
        "footer-key-foreground": "#FFB000",
        "footer-description-foreground": "#8A6200",
        "footer-background": "#111111",
    },
)


@dataclass(slots=True)
class _RunResult:
    response: VoiceResponse
    events: list[dict[str, Any]]
    event_log: Path | None


@dataclass(slots=True)
class _RunSnapshot:
    provider_id: str
    expression: VoiceExpression
    provider_options: dict[str, str]
    output_path: Path
    event_log: Path | None


class VoiceLabHeader(Static):
    """Top status bar modeled on the PulseForge header."""

    def __init__(self, app_ref: "VoiceLabTextualApp", **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self._app_ref = app_ref
        self._clock_text = ""

    def update_clock(self) -> None:
        tz = datetime.now().strftime("%Z") or time_zone_name()
        self._clock_text = datetime.now().strftime("%m-%d-%y  %I:%M:%S %p") + f"  {tz}"
        self.refresh()

    def render(self) -> str:
        provider = self._app_ref.config.provider_id
        model = self._app_ref.config.provider_options.get("model", "default")
        voice = self._app_ref.config.provider_options.get("voice", "default")
        status = self._app_ref.status_text
        clock = self._clock_text or datetime.now().strftime("%m-%d-%y  %I:%M:%S %p")
        return (
            f" HERMES VOICE LAB  [PULSEFORGE TUI]    {status:<9}    "
            f"provider={provider}    model={model}    voice={voice}    {clock} "
        )


class ProgressStrip(Static):
    """Bottom run strip with latency and output status."""

    def __init__(self, app_ref: "VoiceLabTextualApp", **kwargs: Any) -> None:
        super().__init__(**kwargs)
        self._app_ref = app_ref

    def render(self) -> str:
        app = self._app_ref
        events = app.current_events()
        metrics = app.state.last_response.metrics if app.state.last_response else None
        bytes_out = _audio_bytes(events)
        elapsed = app.active_recorder.elapsed_ms() if app.active_recorder else None
        done = metrics.response_done_ms if metrics else elapsed
        first = metrics.first_audio_ms if metrics else (
            app.active_recorder.first_audio_ms if app.active_recorder else None
        )
        log = app.state.last_event_log.name if app.state.last_event_log else "no-log"
        audio = app.state.last_response.audio_path.name if app.state.last_response else "no-audio"
        return (
            f"  {app.status_text:<9}  bytes={bytes_out:<7}  first={_fmt_ms(first):>7}ms  "
            f"done={_fmt_ms(done):>7}ms  audio={audio}  log={log}"
        )


class VoiceLabTextualApp(App):
    """Keyboard-driven interactive lab for realtime voice provider testing."""

    CSS = """
    VoiceLabTextualApp {
        background: #0A0A0A;
        color: #FFB000;
    }

    #header-bar {
        dock: top;
        height: 1;
        background: #111111;
        color: #FFB000;
        text-style: bold;
        padding: 0 1;
    }

    #main-container {
        height: 1fr;
        layout: horizontal;
    }

    .side-panel {
        border: round #221100;
        padding: 1;
        background: #111111;
    }

    #left-panel {
        width: 31;
        min-width: 28;
        max-width: 34;
    }

    #right-panel {
        width: 42;
        min-width: 38;
        max-width: 46;
    }

    #center-panel {
        width: 1fr;
        border: round #221100;
        padding: 1;
        background: #0D0D0D;
    }

    .panel-title {
        color: #8A6200;
        text-style: bold;
        height: 1;
        margin-bottom: 1;
    }

    .metric {
        height: 1;
        text-wrap: nowrap;
        text-overflow: ellipsis;
    }

    #waveform {
        height: 9;
        content-align: center middle;
        color: #FFCC00;
        text-style: bold;
        margin-bottom: 1;
        border: tall #221100;
        padding: 1;
    }

    #prompt-input {
        margin-top: 1;
        border: tall #8A6200;
        background: #0A0A0A;
        color: #FFB000;
    }

    #message-line, #last-prompt, #artifact-line, #error-line, #help-text {
        height: auto;
        min-height: 1;
        color: #FFB000;
        text-wrap: wrap;
    }

    #help-text {
        color: #8A6200;
        margin-top: 1;
    }

    Input, Select {
        height: 3;
        border: tall #221100;
        background: #0A0A0A;
        color: #FFB000;
        margin-bottom: 0;
    }

    Button {
        height: 3;
        margin: 0 1 1 0;
        min-width: 0;
        text-align: left;
    }

    .field-label {
        height: 1;
        color: #8A6200;
        text-style: bold;
    }

    .field-control {
        width: 100%;
        margin: 0 0 1 0;
    }

    .button-row Button {
        width: 1fr;
        text-align: center;
    }

    .button-row {
        height: auto;
    }

    #progress-strip {
        dock: bottom;
        height: 1;
        background: #111111;
        color: #FFB000;
        text-style: bold;
        border-top: solid #221100;
        padding: 0 1;
    }

    Footer {
        background: #111111;
        color: #8A6200;
    }
    """

    BINDINGS = [
        Binding("ctrl+q", "quit", "Quit", show=True),
        Binding("f1", "toggle_help", "Help", show=True),
        Binding("f2", "focus_provider", "Provider", show=True),
        Binding("f3", "focus_command", "Command", show=True),
        Binding("f4", "focus_prompt", "Prompt", show=True),
        Binding("f5", "focus_emotion", "Emotion", show=True),
        Binding("f6", "focus_tone", "Tone", show=True),
        Binding("f7", "toggle_tool", "Tool", show=True),
        Binding("f8", "toggle_play", "Play", show=True),
        Binding("f9", "replay", "Replay", show=True),
        Binding("f10", "rerun", "Rerun", show=True),
        Binding("ctrl+r", "run_prompt", "Run", show=False),
        Binding("ctrl+j", "toggle_json", "JSON", show=False),
        Binding("ctrl+l", "show_log", "Log", show=False),
        Binding("ctrl+o", "open_log", "Open Log", show=False),
        Binding("ctrl+e", "execute_command", "Execute", show=False),
        Binding("escape", "focus_prompt", "Prompt", show=False),
    ]

    def __init__(self, *, registry: ProviderRegistry, config: TuiSessionConfig) -> None:
        super().__init__()
        self.registry = registry
        self.config = config
        self.config.provider_id = normalize_provider_id(config.provider_id)
        for key, value in PROVIDER_DEFAULTS.get(self.config.provider_id, {}).items():
            self.config.provider_options.setdefault(key, value)
        self.config.output_dir.mkdir(parents=True, exist_ok=True)
        if self.config.event_dir:
            self.config.event_dir.mkdir(parents=True, exist_ok=True)
        self.state = TuiRunState()
        self.active_recorder: MetricsRecorder | None = None
        self.status_text = "IDLE"
        self.tool_enabled = _parse_bool(self.config.provider_options.get("tool_scaffold"))
        self.help_visible = False

    def compose(self) -> ComposeResult:
        yield VoiceLabHeader(self, id="header-bar")
        with Horizontal(id="main-container"):
            with Vertical(id="left-panel", classes="side-panel"):
                yield Label("-- SIGNAL DATA --", classes="panel-title")
                yield Label("STATUS: IDLE", id="status-label", classes="metric")
                yield Label("TURN:   0", id="turn-label", classes="metric")
                yield Label("BYTES:  0", id="bytes-label", classes="metric")
                yield Label("PEAK:   0.00", id="peak-label", classes="metric")
                yield Label("1ST MS: ---", id="first-audio-label", classes="metric")
                yield Label("DONE:   ---", id="done-label", classes="metric")
                yield Label("GAP:    ---", id="gap-label", classes="metric")
                yield Label("", classes="metric")
                yield Label("-- PROVIDER --", classes="panel-title")
                yield Label("READY:  provider loaded", id="provider-status-label", classes="metric")

            with Vertical(id="center-panel"):
                yield Label("-- VOICE VISUALIZER --", classes="panel-title")
                yield Static("", id="waveform")
                yield Input(placeholder="Type prompt and press Enter", id="prompt-input")
                yield Label("Esc returns focus to prompt. F1 toggles keys.", id="message-line")
                yield Label("PROMPT: ---", id="last-prompt")
                yield Label("AUDIO:  ---", id="artifact-line")
                yield Label("ERROR:  ---", id="error-line")
                yield Static("", id="help-text")

            with VerticalScroll(id="right-panel", classes="side-panel"):
                yield Label("-- VOICE SETTINGS --", classes="panel-title")
                yield Label("PROVIDER  F2", classes="field-label")
                yield Select(
                    self._provider_options(),
                    allow_blank=False,
                    value=self.config.provider_id,
                    id="provider-select",
                    compact=True,
                    classes="field-control",
                )
                yield Label("MODEL", classes="field-label")
                yield Select(
                    self._model_options(),
                    allow_blank=False,
                    value=self.config.provider_options.get("model", ""),
                    id="model-select",
                    compact=True,
                    classes="field-control",
                )
                yield Label("VOICE", classes="field-label")
                yield Select(
                    self._voice_options(),
                    allow_blank=False,
                    value=self.config.provider_options.get("voice", ""),
                    id="voice-select",
                    compact=True,
                    classes="field-control",
                )
                yield Label("EMOTION  F5", classes="field-label")
                yield Select(
                    _choice_options(EMOTIONS, self.config.expression.emotion or "neutral"),
                    allow_blank=False,
                    value=self.config.expression.emotion or "neutral",
                    id="emotion-select",
                    compact=True,
                    classes="field-control",
                )
                yield Label("TONE  F6", classes="field-label")
                yield Select(
                    _choice_options(TONES, self.config.expression.tone or "natural"),
                    allow_blank=False,
                    value=self.config.expression.tone or "natural",
                    id="tone-select",
                    compact=True,
                    classes="field-control",
                )
                yield Label("INTENSITY", classes="field-label")
                yield Input(
                    value=f"{self.config.expression.intensity:.2f}",
                    placeholder="intensity 0.0-1.0",
                    id="intensity-input",
                    compact=True,
                    classes="field-control",
                )
                yield Label("PACE", classes="field-label")
                yield Select(
                    _choice_options(PACES, self.config.expression.pace or "normal"),
                    allow_blank=False,
                    value=self.config.expression.pace or "normal",
                    id="pace-select",
                    compact=True,
                    classes="field-control",
                )
                yield Label("STYLE", classes="field-label")
                yield Select(
                    _choice_options(STYLES, self.config.expression.style or "natural"),
                    allow_blank=False,
                    value=self.config.expression.style or "natural",
                    id="style-select",
                    compact=True,
                    classes="field-control",
                )
                with Horizontal(classes="button-row"):
                    yield Button("Run", id="run-button", variant="primary")
                    yield Button("Replay", id="replay-button")
                    yield Button("Rerun", id="rerun-button")
                with Horizontal(classes="button-row"):
                    yield Button(self._toggle_label("Play", self.config.play), id="play-button")
                    yield Button(self._toggle_label("Tool", self.tool_enabled), id="tool-button")
                    yield Button(self._toggle_label("JSON", self.config.json_output), id="json-button")
                yield Label("COMMAND  F3 / CTRL+E", classes="field-label")
                yield Select(
                    COMMANDS,
                    allow_blank=False,
                    value="run",
                    id="command-select",
                    compact=True,
                    classes="field-control",
                )
                yield Input(
                    placeholder="command arg: path or key=value",
                    id="command-arg-input",
                    compact=True,
                    classes="field-control",
                )

        yield ProgressStrip(self, id="progress-strip")
        yield Footer()

    def on_mount(self) -> None:
        self.register_theme(AMBER_BURN_THEME)
        self.theme = "voice-lab-amber"
        self.set_interval(0.08, self._render_tick)
        self.set_interval(1.0, self._tick_clock)
        self._sync_config_from_controls()
        self._refresh_all()
        self.query_one("#prompt-input", Input).focus()
        if self.config.initial_text:
            self.query_one("#prompt-input", Input).value = self.config.initial_text
            self.call_later(self._run_prompt_from_input)

    def on_input_submitted(self, event: Input.Submitted) -> None:
        if event.input.id == "prompt-input":
            event.stop()
            self._run_prompt_from_input()
        elif event.input.id == "command-arg-input":
            event.stop()
            self.action_execute_command()
        elif event.input.id == "intensity-input":
            self._sync_config_from_controls()
            self._refresh_all()
            self.query_one("#prompt-input", Input).focus()

    def on_select_changed(self, event: Select.Changed) -> None:
        select_id = event.select.id
        if select_id == "provider-select":
            self._apply_provider(str(event.value))
            self._refresh_all()
        elif select_id in {
            "model-select",
            "voice-select",
            "emotion-select",
            "tone-select",
            "pace-select",
            "style-select",
        }:
            self._sync_config_from_controls()
            self._refresh_all()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        button_id = event.button.id
        if button_id == "run-button":
            self._run_prompt_from_input()
        elif button_id == "replay-button":
            self.run_worker(self._replay_last(), exclusive=False)
        elif button_id == "rerun-button":
            self.action_rerun()
        elif button_id == "play-button":
            self.action_toggle_play()
        elif button_id == "tool-button":
            self.action_toggle_tool()
        elif button_id == "json-button":
            self.config.json_output = not self.config.json_output
            self._refresh_toggle_buttons()

    def current_events(self) -> list[dict[str, Any]]:
        if self.active_recorder is not None:
            return self.active_recorder.events_as_dicts()
        return self.state.last_events

    def action_quit(self) -> None:
        self.exit(0)

    def action_toggle_help(self) -> None:
        self.help_visible = not self.help_visible
        self.query_one("#help-text", Static).display = self.help_visible
        self._refresh_help()

    def action_focus_provider(self) -> None:
        self.query_one("#provider-select", Select).focus()

    def action_focus_command(self) -> None:
        self.query_one("#command-select", Select).focus()

    def action_focus_emotion(self) -> None:
        self.query_one("#emotion-select", Select).focus()

    def action_focus_tone(self) -> None:
        self.query_one("#tone-select", Select).focus()

    def action_run_prompt(self) -> None:
        self._run_prompt_from_input()

    def action_toggle_json(self) -> None:
        self.config.json_output = not self.config.json_output
        self._refresh_toggle_buttons()
        self._refresh_all()

    def action_show_log(self) -> None:
        self._set_message(str(self.state.last_event_log or "No event log yet."))

    def action_open_log(self) -> None:
        self._open_log()

    def action_execute_command(self) -> None:
        command = str(self.query_one("#command-select", Select).value)
        arg = self.query_one("#command-arg-input", Input).value.strip()
        self._execute_named_command(command, arg)

    def action_cycle_provider(self) -> None:
        provider_ids = self.registry.provider_ids()
        if not provider_ids:
            return
        current = self.config.provider_id
        index = provider_ids.index(current) if current in provider_ids else 0
        next_provider = provider_ids[(index + 1) % len(provider_ids)]
        self.query_one("#provider-select", Select).value = next_provider
        self._apply_provider(next_provider)
        self._refresh_all()

    def action_cycle_emotion(self) -> None:
        current = self.config.expression.emotion or "neutral"
        next_value = _next_value(EMOTIONS, current)
        self.query_one("#emotion-select", Select).value = next_value
        self._sync_config_from_controls()
        self._refresh_all()

    def action_cycle_tone(self) -> None:
        current = self.config.expression.tone or "natural"
        next_value = _next_value(TONES, current)
        self.query_one("#tone-select", Select).value = next_value
        self._sync_config_from_controls()
        self._refresh_all()

    def action_toggle_tool(self) -> None:
        self.tool_enabled = not self.tool_enabled
        self._sync_config_from_controls()
        self._refresh_toggle_buttons()
        self._refresh_all()

    def action_toggle_play(self) -> None:
        self.config.play = not self.config.play
        self._refresh_toggle_buttons()
        self._refresh_all()

    async def action_replay(self) -> None:
        await self._replay_last()

    def action_rerun(self) -> None:
        if not self.state.last_prompt:
            self._set_message("No previous prompt to rerun.")
            return
        self.query_one("#prompt-input", Input).value = self.state.last_prompt
        self._run_prompt_from_input()

    def action_focus_prompt(self) -> None:
        self.query_one("#prompt-input", Input).focus()

    def _provider_options(self) -> list[tuple[str, str]]:
        options: list[tuple[str, str]] = []
        for provider_id in self.registry.provider_ids():
            try:
                name = self.registry.info(provider_id).name
            except KeyError:
                name = provider_id
            options.append((f"{name} ({provider_id})", provider_id))
        return options

    def _model_options(self, current: str | None = None) -> list[tuple[str, str]]:
        current = current or self.config.provider_options.get("model", "")
        return _choice_options(
            PROVIDER_MODEL_CHOICES.get(self.config.provider_id, []),
            current,
        )

    def _voice_options(self, current: str | None = None) -> list[tuple[str, str]]:
        current = current or self.config.provider_options.get("voice", "")
        return _choice_options(
            PROVIDER_VOICE_CHOICES.get(self.config.provider_id, []),
            current,
        )

    def _run_prompt_from_input(self) -> None:
        prompt = self.query_one("#prompt-input", Input).value.strip()
        if not prompt:
            self._set_message("Type a prompt first.")
            return
        if prompt.startswith(":"):
            self._handle_command(prompt[1:].strip())
            self.query_one("#prompt-input", Input).value = ""
            return
        if self.active_recorder is not None:
            self._set_message("Provider run already active.")
            return
        self.run_worker(self._run_prompt(prompt), exclusive=False)

    def _execute_named_command(self, command: str, arg: str = "") -> None:
        if command == "run":
            self._run_prompt_from_input()
        elif command == "replay":
            self.run_worker(self._replay_last(), exclusive=False)
        elif command == "rerun":
            self.action_rerun()
        elif command == "save":
            self._save_last(arg)
        elif command == "log":
            self.action_show_log()
        elif command == "open-log":
            self.action_open_log()
        elif command == "option":
            self._apply_provider_option(arg)
        elif command == "toggle-play":
            self.action_toggle_play()
        elif command == "toggle-tool":
            self.action_toggle_tool()
        elif command == "toggle-json":
            self.action_toggle_json()
        elif command == "focus-prompt":
            self.action_focus_prompt()
        elif command == "help":
            self.action_toggle_help()
        elif command == "quit":
            self.exit(0)
        else:
            self._set_message(f"Unknown command menu item: {command}")

    def _handle_command(self, command: str) -> None:
        name, _, arg = command.partition(" ")
        name = name.lower().strip()
        arg = arg.strip()
        if name in {"q", "quit", "exit"}:
            self.exit(0)
            return
        if name in {"h", "help"}:
            self.action_toggle_help()
            return
        if name == "provider":
            provider_id = normalize_provider_id(arg)
            if provider_id in self.registry.provider_ids():
                self.query_one("#provider-select", Select).value = provider_id
                self._apply_provider(provider_id)
            else:
                self._set_message(f"Unknown provider: {arg}")
            self._refresh_all()
            return
        if name in {"model", "voice"}:
            self._set_select_value("model-select" if name == "model" else "voice-select", arg)
            self._sync_config_from_controls()
            self._refresh_all()
            return
        if name == "emotion":
            value = arg or "neutral"
            if value in EMOTIONS:
                self.query_one("#emotion-select", Select).value = value
                self._sync_config_from_controls()
                self._refresh_all()
            else:
                self._set_message(f"Known emotions: {', '.join(EMOTIONS)}")
            return
        if name == "tone":
            value = arg or "natural"
            if value in TONES:
                self.query_one("#tone-select", Select).value = value
                self._sync_config_from_controls()
                self._refresh_all()
            else:
                self._set_message(f"Known tones: {', '.join(TONES)}")
            return
        if name == "pace":
            value = arg or "normal"
            if value in PACES:
                self.query_one("#pace-select", Select).value = value
                self._sync_config_from_controls()
                self._refresh_all()
            else:
                self._set_message(f"Known paces: {', '.join(PACES)}")
            return
        if name == "style":
            value = arg or "natural"
            if value in STYLES:
                self.query_one("#style-select", Select).value = value
                self._sync_config_from_controls()
                self._refresh_all()
            else:
                self._set_message(f"Known styles: {', '.join(STYLES)}")
            return
        if name == "intensity":
            self.query_one("#intensity-input", Input).value = arg
            self._sync_config_from_controls()
            self._refresh_all()
            return
        if name == "tool":
            self.tool_enabled = _parse_on_off(arg, default=self.tool_enabled)
            self._sync_config_from_controls()
            self._refresh_all()
            return
        if name == "play":
            self.config.play = _parse_on_off(arg, default=self.config.play)
            self._refresh_all()
            return
        if name == "json":
            self.config.json_output = _parse_on_off(arg, default=self.config.json_output)
            self._refresh_all()
            return
        if name == "option":
            self._apply_provider_option(arg)
            return
        if name == "replay":
            self.run_worker(self._replay_last(), exclusive=False)
            return
        if name == "rerun":
            self.action_rerun()
            return
        if name == "save":
            self._save_last(arg)
            return
        if name == "log":
            self._set_message(str(self.state.last_event_log or "No event log yet."))
            return
        if name == "open-log":
            self._open_log()
            return
        self._set_message(f"Unknown command: :{name}. Press F1 for keys.")

    async def _run_prompt(self, prompt: str) -> None:
        self._sync_config_from_controls()
        self.state.turn_index += 1
        self.state.last_prompt = prompt
        self.state.last_error = None
        self.state.last_response = None
        recorder = MetricsRecorder()
        snapshot = self._snapshot_for_run(prompt=prompt, turn_index=self.state.turn_index)
        self.active_recorder = recorder
        self.status_text = "RUNNING"
        self._set_message("Rendering voice output...")
        self._refresh_all()
        try:
            result = await asyncio.to_thread(self._run_prompt_blocking, prompt, snapshot, recorder)
            self.state.last_response = result.response
            self.state.last_events = result.events
            self.state.last_event_log = result.event_log
            self.status_text = "READY"
            self._set_message("Run complete. Press F9 to replay or type another prompt.")
            if self.config.json_output:
                self._set_message(json.dumps(result.response.to_dict(), sort_keys=True))
            if self.config.play:
                await self._play_response(result.response)
        except (ProviderUnavailable, ProviderRunError, PlaybackError, KeyError, OSError) as exc:
            self.state.last_error = str(exc)
            self.state.last_events = recorder.events_as_dicts()
            self.state.last_event_log = snapshot.event_log
            if snapshot.event_log and self.state.last_events:
                _write_event_log(snapshot.event_log, self.state.last_events)
            self.status_text = "ERROR"
            self._set_message("Provider failed. Check ERROR and event log path.")
        finally:
            self.active_recorder = None
            if self._has_ui():
                self._refresh_all()
                self.query_one("#prompt-input", Input).focus()

    def _run_prompt_blocking(
        self,
        prompt: str,
        snapshot: _RunSnapshot,
        recorder: MetricsRecorder,
    ) -> _RunResult:
        provider = self.registry.create(snapshot.provider_id)
        request = VoiceRequest(
            text=prompt,
            expression=snapshot.expression,
            output_path=snapshot.output_path,
            provider_options=dict(snapshot.provider_options),
        )
        response = provider.run_text(request, recorder)
        events = recorder.events_as_dicts()
        if snapshot.event_log:
            _write_event_log(snapshot.event_log, events)
        return _RunResult(response=response, events=events, event_log=snapshot.event_log)

    def _snapshot_for_run(self, *, prompt: str, turn_index: int) -> _RunSnapshot:
        del prompt
        output_path = self._audio_path(turn_index)
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
        return _RunSnapshot(
            provider_id=self.config.provider_id,
            expression=expression,
            provider_options=dict(self.config.provider_options),
            output_path=output_path,
            event_log=event_log,
        )

    def _audio_path(self, turn_index: int) -> Path:
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        safe_provider = _slug(self.config.provider_id)
        return self.config.output_dir / f"{stamp}-{turn_index:03d}-{safe_provider}.wav"

    def _event_log_path(self, audio_path: Path) -> Path | None:
        if not self.config.event_log:
            return None
        event_dir = self.config.event_dir or self.config.output_dir
        return event_dir / f"{audio_path.stem}.jsonl"

    async def _replay_last(self) -> None:
        response = self.state.last_response
        if not response:
            self._set_message("No rendered audio to replay yet.")
            return
        try:
            self.status_text = "PLAYING"
            self._refresh_all()
            await self._play_response(response)
            self.status_text = "READY"
            self._set_message("Playback complete.")
        except PlaybackError as exc:
            self.state.last_error = str(exc)
            self.status_text = "ERROR"
        finally:
            if self._has_ui():
                self._refresh_all()

    async def _play_response(self, response: VoiceResponse) -> None:
        await asyncio.to_thread(play_audio_file, response.audio_path)

    def _apply_provider(self, provider_id: str) -> None:
        provider_id = normalize_provider_id(provider_id)
        if provider_id not in self.registry.provider_ids():
            self._set_message(f"Unknown provider: {provider_id}")
            return
        self.config.provider_id = provider_id
        defaults = PROVIDER_DEFAULTS.get(provider_id, {})
        model = defaults.get("model", "")
        voice = defaults.get("voice", "")
        self.config.provider_options["model"] = model
        self.config.provider_options["voice"] = voice
        provider_select = self.query_one("#provider-select", Select)
        if str(provider_select.value) != provider_id:
            provider_select.value = provider_id
        model_select = self.query_one("#model-select", Select)
        model_select.set_options(self._model_options(model))
        model_select.value = model
        voice_select = self.query_one("#voice-select", Select)
        voice_select.set_options(self._voice_options(voice))
        voice_select.value = voice
        self._sync_config_from_controls()

    def _apply_provider_option(self, value: str) -> None:
        if "=" not in value:
            self._set_message("Use command arg key=value for provider options.")
            return
        key, item = value.split("=", 1)
        key = key.strip()
        item = item.strip()
        if not key:
            self._set_message("Provider option key cannot be empty.")
            return
        self.config.provider_options[key] = item
        if key == "model":
            self._set_select_value("model-select", item)
        elif key == "voice":
            self._set_select_value("voice-select", item)
        elif key == "tool_scaffold":
            self.tool_enabled = _parse_bool(item)
        self._sync_config_from_controls()
        self._refresh_all()
        self._set_message(f"Applied option {key}={item}")

    def _set_select_value(self, select_id: str, value: str) -> None:
        select = self.query_one(f"#{select_id}", Select)
        if select_id == "model-select":
            select.set_options(self._model_options(value))
        elif select_id == "voice-select":
            select.set_options(self._voice_options(value))
        select.value = value

    def _sync_config_from_controls(self) -> None:
        provider_value = str(self.query_one("#provider-select", Select).value)
        if provider_value:
            self.config.provider_id = normalize_provider_id(provider_value)
        model = str(self.query_one("#model-select", Select).value).strip()
        voice = str(self.query_one("#voice-select", Select).value).strip()
        if model:
            self.config.provider_options["model"] = model
        else:
            self.config.provider_options.pop("model", None)
        if voice:
            self.config.provider_options["voice"] = voice
        else:
            self.config.provider_options.pop("voice", None)

        try:
            intensity = float(self.query_one("#intensity-input", Input).value)
        except ValueError:
            intensity = self.config.expression.intensity
        self.config.expression.intensity = max(0.0, min(1.0, intensity))
        self.query_one("#intensity-input", Input).value = f"{self.config.expression.intensity:.2f}"
        emotion = str(self.query_one("#emotion-select", Select).value)
        tone = str(self.query_one("#tone-select", Select).value)
        pace = str(self.query_one("#pace-select", Select).value)
        style = str(self.query_one("#style-select", Select).value)
        self.config.expression.emotion = None if emotion == "neutral" else emotion
        self.config.expression.tone = None if tone == "natural" else tone
        self.config.expression.pace = pace or "normal"
        self.config.expression.style = style or "natural"
        self.config.provider_options["tool_scaffold"] = "true" if self.tool_enabled else "false"

    def _render_tick(self) -> None:
        if not self._has_ui():
            return
        self._refresh_signal()
        self._refresh_waveform()
        self.query_one("#progress-strip", ProgressStrip).refresh()

    def _tick_clock(self) -> None:
        if not self._has_ui():
            return
        self.query_one("#header-bar", VoiceLabHeader).update_clock()

    def _refresh_all(self) -> None:
        if not self._has_ui():
            return
        self._refresh_signal()
        self._refresh_waveform()
        self._refresh_text()
        self._refresh_help()
        self._refresh_toggle_buttons()
        self.query_one("#header-bar", VoiceLabHeader).refresh()
        self.query_one("#progress-strip", ProgressStrip).refresh()

    def _refresh_signal(self) -> None:
        events = self.current_events()
        metrics = self.state.last_response.metrics if self.state.last_response else None
        active = self.active_recorder
        levels = _audio_levels(events)
        peak = max(levels) if levels else 0.0
        first = metrics.first_audio_ms if metrics else (active.first_audio_ms if active else None)
        done = metrics.response_done_ms if metrics else (active.elapsed_ms() if active else None)
        gap = metrics.audio_chunk_gap_ms if metrics else None
        self.query_one("#status-label", Label).update(f"STATUS: {self.status_text}")
        self.query_one("#turn-label", Label).update(f"TURN:   {self.state.turn_index}")
        self.query_one("#bytes-label", Label).update(f"BYTES:  {_audio_bytes(events)}")
        self.query_one("#peak-label", Label).update(f"PEAK:   {peak:.2f}")
        self.query_one("#first-audio-label", Label).update(f"1ST MS: {_fmt_ms(first)}")
        self.query_one("#done-label", Label).update(f"DONE:   {_fmt_ms(done)}")
        self.query_one("#gap-label", Label).update(f"GAP:    {_fmt_ms(gap)}")
        self.query_one("#provider-status-label", Label).update(
            f"READY:  {self.config.provider_id}"
        )

    def _refresh_waveform(self) -> None:
        events = self.current_events()
        levels = _audio_levels(events)
        width = max(24, min(78, self.size.width - 78 if self.size.width else 48))
        waveform = render_waveform(self.state.turn_index, width=width, unicode=True, levels=levels)
        label = "PCM RMS waveform" if levels else "waiting for generated PCM"
        self.query_one("#waveform", Static).update(f"{label}\n\n{waveform}")

    def _refresh_text(self) -> None:
        prompt = self.state.last_prompt or "---"
        if len(prompt) > 120:
            prompt = prompt[:117] + "..."
        self.query_one("#last-prompt", Label).update(f"PROMPT: {prompt}")
        if self.state.last_response:
            audio = self.state.last_response.audio_path
            event_log = self.state.last_event_log or Path("---")
            self.query_one("#artifact-line", Label).update(f"AUDIO:  {audio}\nLOG:    {event_log}")
        else:
            self.query_one("#artifact-line", Label).update("AUDIO:  ---\nLOG:    ---")
        self.query_one("#error-line", Label).update(f"ERROR:  {self.state.last_error or '---'}")

    def _refresh_help(self) -> None:
        help_widget = self.query_one("#help-text", Static)
        help_widget.display = self.help_visible
        if not self.help_visible:
            return
        help_widget.update(
            "\n".join(
                [
                    "Keys: Ctrl+Q quit | F2 provider | F5 emotion | F6 tone | F7 tool | F8 play",
                    "      F9 replay | F10 rerun | Esc prompt | Tab moves through controls",
                    "Controls are live: edit model, voice, intensity, provider, emotion, and tone before a run.",
                ]
            )
        )

    def _refresh_toggle_buttons(self) -> None:
        self.query_one("#play-button", Button).label = self._toggle_label("Play", self.config.play)
        self.query_one("#tool-button", Button).label = self._toggle_label("Tool", self.tool_enabled)
        self.query_one("#json-button", Button).label = self._toggle_label("JSON", self.config.json_output)

    def _set_message(self, text: str) -> None:
        if not self._has_ui():
            return
        self.query_one("#message-line", Label).update(text)

    def _has_ui(self) -> bool:
        try:
            self.query_one("#status-label", Label)
        except NoMatches:
            return False
        return True

    def _save_last(self, value: str) -> None:
        response = self.state.last_response
        if not response:
            self._set_message("No rendered audio to save yet.")
            return
        if not value:
            self._set_message("Use :save path\\to\\copy.wav")
            return
        target = Path(value).expanduser()
        if not target.is_absolute():
            target = Path.cwd() / target
        try:
            copy_last_audio(response, target)
        except OSError as exc:
            self.state.last_error = str(exc)
            self.status_text = "ERROR"
            self._refresh_all()
            return
        self._set_message(f"Saved {target}")

    def _open_log(self) -> None:
        path = self.state.last_event_log
        if not path:
            self._set_message("No event log yet.")
            return
        try:
            open_path(path)
        except OSError as exc:
            self.state.last_error = str(exc)
            self.status_text = "ERROR"
            self._refresh_all()

    @staticmethod
    def _toggle_label(label: str, enabled: bool) -> str:
        return f"{label}:{'on' if enabled else 'off'}"

    def _provider_button_label(self) -> str:
        try:
            name = self.registry.info(self.config.provider_id).name
        except KeyError:
            name = self.config.provider_id
        return f"Provider: {name} ({self.config.provider_id})"

    @staticmethod
    def _value_button_label(label: str, value: str, key: str) -> str:
        return f"{label}: {value}  {key}"


def run_textual_tui_session(*, registry: ProviderRegistry, config: TuiSessionConfig) -> int:
    app = VoiceLabTextualApp(registry=registry, config=config)
    result = app.run()
    return int(result or 0)


def time_zone_name() -> str:
    if time_name := datetime.now().astimezone().tzname():
        return time_name
    return "UTC"


def _next_value(values: list[str], current: str) -> str:
    index = values.index(current) if current in values else 0
    return values[(index + 1) % len(values)]


def _choice_options(values: list[str], current: str | None) -> list[tuple[str, str]]:
    ordered: list[str] = []
    if current:
        ordered.append(current)
    ordered.extend(values)
    seen: set[str] = set()
    options: list[tuple[str, str]] = []
    for value in ordered:
        if not value or value in seen:
            continue
        seen.add(value)
        options.append((value, value))
    return options or [("default", "default")]


def _parse_bool(value: str | None) -> bool:
    if value is None:
        return False
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _parse_on_off(value: str, *, default: bool) -> bool:
    lowered = value.strip().lower()
    if not lowered:
        return default
    if lowered in {"1", "true", "yes", "on", "play"}:
        return True
    if lowered in {"0", "false", "no", "off", "stop"}:
        return False
    return default


def copy_last_audio(response: VoiceResponse, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(response.audio_path, target)


def open_path(path: Path) -> None:
    if os.name == "nt":
        os.startfile(path)  # type: ignore[attr-defined]
    elif sys.platform == "darwin":
        subprocess.Popen(["open", str(path)])
    else:
        subprocess.Popen(["xdg-open", str(path)])
