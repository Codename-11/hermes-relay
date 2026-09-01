"""Relay-owned agent context injection.

This module wraps ``AIAgent._build_system_prompt`` when that seam exists and
appends auditable, removable blocks controlled by relay env flags. The wrapper
is intentionally fail-open: when disabled, absent, or broken, upstream prompt
construction is returned unchanged.
"""

from __future__ import annotations

import importlib
import logging
import sys
from functools import wraps
from typing import Any, Mapping

from ..config import (
    agent_context_enabled,
    context_media_sensitivity_enabled,
    context_phone_platform_enabled,
    phone_platform_enabled,
)
from .registry import Enhancement

logger = logging.getLogger(__name__)

MEDIA_SENSITIVITY_BLOCK_NAME = "media-sensitivity"
MEDIA_SENSITIVITY_INSTRUCTION = (
    "Media references may be private, NSFW, or spoiler content. When you include "
    "or refer to inline Markdown images (`![alt](path)`), `MEDIA:/path` markers, "
    "or relay media that could be sensitive, mark them using the client's spoiler "
    "convention: wrap the rendered image as `||![alt](path)||` or include `nsfw`, "
    "`sensitive`, or `spoiler` in the image alt text. Do not expose sensitive "
    "media without one of those markers."
)

PHONE_PLATFORM_BLOCK_NAME = "phone-platform"
PHONE_PLATFORM_INSTRUCTION = (
    "Proactive phone messaging is available. You can reach the user on their "
    "paired phone by calling `send_message` with target `phone` (e.g. "
    "`send_message(target=\"phone\", message=\"...\")`); it is delivered as a "
    "phone notification and collected in a dedicated in-app inbox. Use it for "
    "time-sensitive updates, to report that a long-running task has finished, or "
    "whenever the user asks to be notified on their phone. Keep these messages "
    "concise and high-signal — the user may not be looking at the screen."
)

_WRAPPED_ATTR = "_hermes_relay_context_wrapped"
_ORIGINAL_ATTR = "_hermes_relay_context_original"


def available_context_blocks() -> list[dict[str, str]]:
    """Return all known blocks, regardless of current gates."""
    return [
        {
            "name": MEDIA_SENSITIVITY_BLOCK_NAME,
            "text": MEDIA_SENSITIVITY_INSTRUCTION,
        },
        {
            "name": PHONE_PLATFORM_BLOCK_NAME,
            "text": PHONE_PLATFORM_INSTRUCTION,
        },
    ]


def _session_tool_names(session_info: Mapping[str, Any] | None) -> set[str]:
    """Flatten the host's authoritative per-session tool catalog.

    A host that exposes tools to native plugin prompt sections may use
    ``{toolset: [tool_name, ...]}`` or a flat list. Current upstream's native
    callback metadata does not include tools, so it intentionally fails closed.
    A context block must never advertise a tool merely because a related
    platform is configured.
    """
    if not isinstance(session_info, Mapping):
        return set()
    tools = session_info.get("tools")
    if isinstance(tools, Mapping):
        values = tools.values()
    elif isinstance(tools, (list, tuple, set, frozenset)):
        values = (tools,)
    else:
        return set()

    names: set[str] = set()
    for value in values:
        if isinstance(value, str):
            names.add(value)
        elif isinstance(value, (list, tuple, set, frozenset)):
            names.update(item for item in value if isinstance(item, str))
    return names


def get_injected_context_blocks(
    session_info: Mapping[str, Any] | None = None,
) -> list[dict[str, str]]:
    """Return the blocks that would be injected for this agent session."""
    if not agent_context_enabled():
        return []

    blocks: list[dict[str, str]] = []
    if context_media_sensitivity_enabled():
        blocks.append(
            {
                "name": MEDIA_SENSITIVITY_BLOCK_NAME,
                "text": MEDIA_SENSITIVITY_INSTRUCTION,
            }
        )
    # Platform registration is not proof that the model can call a delivery
    # tool. Current upstream deliberately keeps send_message out of the agent
    # tool catalog; it remains an operator/cron/MCP transport. Older or custom
    # hosts may expose a real callable. Advertise it only when the authoritative
    # per-session tool catalog says that callable exists.
    if (
        phone_platform_enabled()
        and context_phone_platform_enabled()
        and "send_message" in _session_tool_names(session_info)
    ):
        blocks.append(
            {
                "name": PHONE_PLATFORM_BLOCK_NAME,
                "text": PHONE_PLATFORM_INSTRUCTION,
            }
        )
    return blocks


def injected_context_payload() -> dict[str, Any]:
    """Return the public audit shape for ``GET /context/injected``."""
    enabled = agent_context_enabled()
    return {
        "enabled": enabled,
        "blocks": get_injected_context_blocks() if enabled else [],
    }


def _fence_block(block: dict[str, str]) -> str:
    name = block["name"]
    text = block["text"]
    return f"<!-- hermes-relay:{name} -->\n{text}\n<!-- /hermes-relay:{name} -->"


def _build_injection_text(
    session_info: Mapping[str, Any] | None = None,
) -> str:
    return "\n\n".join(
        _fence_block(block) for block in get_injected_context_blocks(session_info)
    )


def _native_section_content(
    block_name: str,
    session_info: Mapping[str, Any] | None = None,
) -> str:
    """Resolve one section inside the active Hermes profile scope."""
    for block in get_injected_context_blocks(session_info):
        if block["name"] == block_name:
            return block["text"]
    return ""


def register_context_sections(ctx: Any) -> bool:
    """Register profile-owned prompt sections on modern Hermes hosts.

    The upstream registration is recorded in the plugin ownership ledger, so
    reload/disable/unload removes only this profile's sections. ``False`` means
    the additive API is absent and the caller may use the legacy wrapper.
    """
    register = getattr(ctx, "register_system_prompt_section", None)
    if not callable(register):
        return False

    sections = (
        (MEDIA_SENSITIVITY_BLOCK_NAME, MEDIA_SENSITIVITY_INSTRUCTION),
        (PHONE_PLATFORM_BLOCK_NAME, PHONE_PLATFORM_INSTRUCTION),
    )
    for block_name, instruction in sections:
        def _content(
            _session_info: Mapping[str, Any],
            *,
            _block_name: str = block_name,
        ) -> str:
            return _native_section_content(_block_name, _session_info)

        register(
            id=f"hermes-relay.{block_name}",
            content=_content,
            position="after_memory",
            max_chars=len(instruction) + 64,
        )
    return True


def _resolve_ai_agent_class() -> type[Any] | None:
    for module_name in ("agent.system_prompt", "run_agent"):
        module = sys.modules.get(module_name)
        if module is None:
            try:
                module = importlib.import_module(module_name)
            except Exception:
                logger.debug(
                    "AIAgent seam module %s unavailable for relay context injection",
                    module_name,
                    exc_info=True,
                )
                continue
        candidate = getattr(module, "AIAgent", None)
        if isinstance(candidate, type):
            return candidate
    return None


def apply_context_injection(agent_class: type[Any] | None = None) -> bool:
    """Wrap ``AIAgent._build_system_prompt`` if the host exposes it."""
    target = agent_class or _resolve_ai_agent_class()
    if target is None:
        logger.debug("Relay context injection seam absent: no AIAgent class found")
        return False

    original = getattr(target, "_build_system_prompt", None)
    if not callable(original):
        logger.debug(
            "Relay context injection seam absent on %s: _build_system_prompt missing",
            target,
        )
        return False

    if getattr(original, _WRAPPED_ATTR, False):
        return True

    @wraps(original)
    def _wrapped_build_system_prompt(self: Any, *args: Any, **kwargs: Any) -> Any:
        base_prompt = original(self, *args, **kwargs)
        if not isinstance(base_prompt, str):
            return base_prompt

        try:
            tool_names: list[str] = []
            for tool in getattr(self, "tools", ()) or ():
                if not isinstance(tool, Mapping):
                    continue
                function = tool.get("function")
                if isinstance(function, Mapping) and isinstance(function.get("name"), str):
                    tool_names.append(function["name"])
            injection = _build_injection_text({"tools": tool_names})
        except Exception:
            logger.debug(
                "Relay context injection failed while building blocks; returning base prompt",
                exc_info=True,
            )
            return base_prompt

        if not injection:
            return base_prompt
        if base_prompt:
            return f"{base_prompt}\n\n{injection}"
        return injection

    setattr(_wrapped_build_system_prompt, _WRAPPED_ATTR, True)
    setattr(_wrapped_build_system_prompt, _ORIGINAL_ATTR, original)

    try:
        setattr(target, "_build_system_prompt", _wrapped_build_system_prompt)
    except Exception:
        logger.debug(
            "Relay context injection could not patch %s._build_system_prompt",
            target,
            exc_info=True,
        )
        return False

    logger.debug("Relay context injection wrapper installed on %s", target)
    return True


CONTEXT_INJECTION_ENHANCEMENT = Enhancement(
    name="context_injection",
    phase="plugin_load",
    enabled=agent_context_enabled,
    apply=apply_context_injection,
    retirement_note=(
        "Remove when upstream Hermes exposes a first-class plugin system-prompt "
        "context hook."
    ),
)


__all__ = [
    "CONTEXT_INJECTION_ENHANCEMENT",
    "MEDIA_SENSITIVITY_BLOCK_NAME",
    "MEDIA_SENSITIVITY_INSTRUCTION",
    "apply_context_injection",
    "available_context_blocks",
    "get_injected_context_blocks",
    "injected_context_payload",
    "register_context_sections",
]
