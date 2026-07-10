"""Normalized dataclasses + tool-surface constants for the realtime agent.

Provider adapters convert raw OpenAI/xAI events into the normalized
[ProviderEvent] / [ToolCallEvent] / [TranscriptEvent] shapes defined
here, so handler logic never branches on provider names.

Wire shapes mirror the plan's Android-to-relay websocket vocabulary in
docs/plans/2026-05-19-realtime-hermes-voice-agent.md.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Literal


# ── Hermes broker tool surface ──────────────────────────────────────────────

HERMES_TOOL_SURFACE: tuple[str, ...] = (
    "hermes_run_task",
    "hermes_get_status",
    "hermes_cancel",
    "hermes_confirm",
)

HERMES_TOOL_SCHEMAS: tuple[dict[str, Any], ...] = (
    {
        "name": "hermes_run_task",
        "description": (
            "Ask the Hermes agent to handle a user request. Hermes owns "
            "tool execution, profiles, memory, confirmation prompts, current "
            "checks, research, live/external data, and any answer that should "
            "not rely on provider model priors, including latest/versioned info, "
            "device or desktop state, personal/project context, side effects, "
            "precision-sensitive answers, media/artifact handling, and dense "
            "machine-readable output that needs a speech-safe summary. Do not "
            "use this to ask what is currently running or queued; use "
            "hermes_get_status for current run and queue status."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "text": {"type": "string"},
                "profile": {"type": "string"},
                "session_id": {"type": "string"},
                "mode": {
                    "type": "string",
                    "enum": ["chat", "run", "background"],
                    "description": (
                        "'run' (default) answers in-line for short tasks and is "
                        "auto-promoted to the background if it runs long; "
                        "'background' starts a durable run immediately and reports "
                        "back when it finishes."
                    ),
                },
            },
            "required": ["text"],
        },
    },
    {
        "name": "hermes_get_status",
        "description": (
            "Check the current Hermes background run, active tool, pending "
            "confirmation, and queued follow-up requests. Use this when the "
            "user asks what is running, what is in the queue, whether a task "
            "finished, or for status of the current voice background work."
        ),
        "parameters": {
            "type": "object",
            "properties": {"run_id": {"type": "string"}},
        },
    },
    {
        "name": "hermes_cancel",
        "description": "Cancel an in-flight Hermes run.",
        "parameters": {
            "type": "object",
            "properties": {"run_id": {"type": "string"}},
        },
    },
    {
        "name": "hermes_confirm",
        "description": "Answer a pending Hermes confirmation prompt.",
        "parameters": {
            "type": "object",
            "properties": {
                "confirmation_id": {"type": "string"},
                "answer": {
                    "type": "string",
                    "enum": ["allow", "allow_once", "deny", "cancel"],
                },
            },
            "required": ["confirmation_id", "answer"],
        },
    },
)


AuthPrincipalKind = Literal["relay_session", "hermes_api"]


@dataclass(frozen=True, slots=True)
class RealtimeAgentCapabilities:
    provider_id: str
    supports_function_calls: bool = True
    supports_server_vad: bool = True
    input_audio_format: str = "pcm16"
    output_audio_format: str = "pcm16"
    accepts_text_input: bool = True


@dataclass(frozen=True, slots=True)
class RealtimeAgentSessionConfig:
    provider: str
    model: str
    voice: str
    sample_rate: int
    profile: str | None
    hermes_session_id: str | None
    voice_engine_mode: str = "realtime_agent"
    instructions: str = ""
    provider_options: dict[str, Any] = field(default_factory=dict)
    tools: tuple[dict[str, Any], ...] = HERMES_TOOL_SCHEMAS


@dataclass(slots=True)
class RealtimeAgentSession:
    session_id: str
    config: RealtimeAgentSessionConfig
    auth_kind: AuthPrincipalKind
    config_scope: str
    config_path: Path | None
    created_at: float
    event_log_path: Path
    hermes_run_id: str | None = None
    pending_confirmation_id: str | None = None


class ProviderEventKind(str, Enum):
    READY = "ready"
    AUDIO_DELTA = "audio_delta"
    AUDIO_DONE = "audio_done"
    INPUT_TRANSCRIPT_DELTA = "input_transcript_delta"
    INPUT_TRANSCRIPT_FINAL = "input_transcript_final"
    OUTPUT_TEXT_DELTA = "output_text_delta"
    RESPONSE_STARTED = "response_started"
    RESPONSE_DONE = "response_done"
    FUNCTION_CALL_STARTED = "function_call_started"
    FUNCTION_CALL_ARGUMENTS_DELTA = "function_call_arguments_delta"
    FUNCTION_CALL_COMPLETED = "function_call_completed"
    ERROR = "error"


@dataclass(slots=True)
class ProviderEvent:
    kind: ProviderEventKind
    response_id: str = ""
    payload: dict[str, Any] = field(default_factory=dict)
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class ToolCallEvent:
    call_id: str
    name: str
    arguments: dict[str, Any] = field(default_factory=dict)

    def is_hermes_tool(self) -> bool:
        return self.name in HERMES_TOOL_SURFACE


CLIENT_MSG_SESSION_START = "session.start"
CLIENT_MSG_INPUT_AUDIO_APPEND = "input_audio.append"
CLIENT_MSG_INPUT_AUDIO_COMMIT = "input_audio.commit"
CLIENT_MSG_INPUT_AUDIO_CLEAR = "input_audio.clear"
CLIENT_MSG_RESPONSE_CREATE = "response.create"
CLIENT_MSG_RESPONSE_CANCEL = "response.cancel"
CLIENT_MSG_PLAYBACK_DRAINED = "playback.drained"
CLIENT_MSG_HERMES_CONFIRM = "hermes.confirm"
CLIENT_MSG_SESSION_RESUME = "session.resume"
CLIENT_MSG_CLIENT_ACK = "client.ack"
CLIENT_MSG_SESSION_CLOSE = "session.close"
CLIENT_MSG_RESULT_RESPEAK = "hermes.result.respeak"

CLIENT_MSGS: frozenset[str] = frozenset({
    CLIENT_MSG_SESSION_START,
    CLIENT_MSG_SESSION_RESUME,
    CLIENT_MSG_INPUT_AUDIO_APPEND,
    CLIENT_MSG_INPUT_AUDIO_COMMIT,
    CLIENT_MSG_INPUT_AUDIO_CLEAR,
    CLIENT_MSG_RESPONSE_CREATE,
    CLIENT_MSG_PLAYBACK_DRAINED,
    CLIENT_MSG_RESPONSE_CANCEL,
    CLIENT_MSG_HERMES_CONFIRM,
    CLIENT_MSG_CLIENT_ACK,
    CLIENT_MSG_SESSION_CLOSE,
    CLIENT_MSG_RESULT_RESPEAK,
})

SERVER_EVT_SESSION_READY = "voice.session.ready"
SERVER_EVT_SESSION_RESUMED = "voice.session.resumed"
SERVER_EVT_SESSION_DETACHED = "voice.session.detached"
SERVER_EVT_SESSION_RESUME_FAILED = "voice.session.resume_failed"
SERVER_EVT_REPLAY_STARTED = "voice.replay.started"
SERVER_EVT_REPLAY_DONE = "voice.replay.done"
SERVER_EVT_INPUT_AUDIO_RECEIVED = "voice.input_audio.received"
SERVER_EVT_INPUT_TRANSCRIPT_DELTA = "voice.input_transcript.delta"
SERVER_EVT_INPUT_TRANSCRIPT_FINAL = "voice.input_transcript.final"
SERVER_EVT_RESPONSE_STARTED = "voice.response.started"
SERVER_EVT_RESPONSE_DELTA = "voice.response.delta"
SERVER_EVT_RESPONSE_DONE = "voice.response.done"
SERVER_EVT_OUTPUT_AUDIO_DELTA = "voice.output_audio.delta"
SERVER_EVT_OUTPUT_AUDIO_DONE = "voice.output_audio.done"
SERVER_EVT_PLAYBACK_DRAIN_REQUESTED = "voice.playback_drain.requested"
SERVER_EVT_HERMES_RUN_STARTED = "hermes.run.started"
SERVER_EVT_HERMES_RUN_PROGRESS = "hermes.run.progress"
SERVER_EVT_HERMES_RUN_PROMOTED = "hermes.run.promoted"
SERVER_EVT_HERMES_RUN_BACKGROUND_COMPLETED = "hermes.run.background_completed"
SERVER_EVT_HERMES_TOOL_STARTED = "hermes.tool.started"
SERVER_EVT_HERMES_TOOL_DELTA = "hermes.tool.delta"
SERVER_EVT_HERMES_TOOL_COMPLETED = "hermes.tool.completed"
SERVER_EVT_HERMES_CONFIRMATION_REQUESTED = "hermes.confirmation.requested"
SERVER_EVT_HERMES_RUN_COMPLETED = "hermes.run.completed"
SERVER_EVT_VOICE_ERROR = "voice.error"

REALTIME_AGENT_PROTOCOL = "hermes.voice.realtime-agent.v0"
REALTIME_AGENT_OPTIONS_PROTOCOL = "hermes.voice.realtime-agent.options.v0"


__all__ = [
    "AuthPrincipalKind",
    "CLIENT_MSGS",
    "CLIENT_MSG_HERMES_CONFIRM",
    "CLIENT_MSG_INPUT_AUDIO_APPEND",
    "CLIENT_MSG_INPUT_AUDIO_CLEAR",
    "CLIENT_MSG_INPUT_AUDIO_COMMIT",
    "CLIENT_MSG_PLAYBACK_DRAINED",
    "CLIENT_MSG_RESPONSE_CREATE",
    "CLIENT_MSG_RESPONSE_CANCEL",
    "CLIENT_MSG_CLIENT_ACK",
    "CLIENT_MSG_SESSION_RESUME",
    "CLIENT_MSG_SESSION_CLOSE",
    "CLIENT_MSG_RESULT_RESPEAK",
    "CLIENT_MSG_SESSION_START",
    "HERMES_TOOL_SCHEMAS",
    "HERMES_TOOL_SURFACE",
    "ProviderEvent",
    "ProviderEventKind",
    "REALTIME_AGENT_OPTIONS_PROTOCOL",
    "REALTIME_AGENT_PROTOCOL",
    "RealtimeAgentCapabilities",
    "RealtimeAgentSession",
    "RealtimeAgentSessionConfig",
    "SERVER_EVT_HERMES_CONFIRMATION_REQUESTED",
    "SERVER_EVT_HERMES_RUN_BACKGROUND_COMPLETED",
    "SERVER_EVT_HERMES_RUN_COMPLETED",
    "SERVER_EVT_HERMES_RUN_PROMOTED",
    "SERVER_EVT_HERMES_RUN_STARTED",
    "SERVER_EVT_HERMES_TOOL_COMPLETED",
    "SERVER_EVT_HERMES_TOOL_DELTA",
    "SERVER_EVT_HERMES_TOOL_STARTED",
    "SERVER_EVT_INPUT_AUDIO_RECEIVED",
    "SERVER_EVT_INPUT_TRANSCRIPT_DELTA",
    "SERVER_EVT_INPUT_TRANSCRIPT_FINAL",
    "SERVER_EVT_OUTPUT_AUDIO_DELTA",
    "SERVER_EVT_OUTPUT_AUDIO_DONE",
    "SERVER_EVT_PLAYBACK_DRAIN_REQUESTED",
    "SERVER_EVT_REPLAY_DONE",
    "SERVER_EVT_REPLAY_STARTED",
    "SERVER_EVT_RESPONSE_DELTA",
    "SERVER_EVT_RESPONSE_DONE",
    "SERVER_EVT_RESPONSE_STARTED",
    "SERVER_EVT_SESSION_DETACHED",
    "SERVER_EVT_SESSION_READY",
    "SERVER_EVT_SESSION_RESUMED",
    "SERVER_EVT_SESSION_RESUME_FAILED",
    "SERVER_EVT_VOICE_ERROR",
    "ToolCallEvent",
]
