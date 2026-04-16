"""
hermes-relay plugin — 14 android_* tool handlers + schemas.

Used by the plugin's __init__.py to register tools into hermes-agent
via ctx.register_tool().
"""

import json
import os
import time
import requests
from typing import Optional

# ── Config ────────────────────────────────────────────────────────────────────
#
# Architecture: Phone connects OUT to Hermes server via WebSocket (NAT-friendly).
# The unified Hermes-Relay server (``plugin/relay/server.py``, port 8767)
# multiplexes the bridge channel alongside chat, terminal, media, and voice.
# The legacy standalone bridge relay on port 8766 was retired in Phase 3
# Wave 1 (Agent bridge-server, bridge-server-migration).
#
#   Tools ──HTTP──> Unified Relay (localhost:8767) ──WSS bridge channel──> Phone
#
# For local/USB dev, tools can also talk directly to the phone's HTTP server
# by setting ANDROID_BRIDGE_URL to the phone's IP.

def _bridge_url() -> str:
    """URL of the relay (default) or direct phone connection."""
    return os.getenv("ANDROID_BRIDGE_URL", "http://localhost:8767")

def _bridge_token() -> Optional[str]:
    return os.getenv("ANDROID_BRIDGE_TOKEN")

def _relay_port() -> int:
    # Unified relay default. Override via ANDROID_RELAY_PORT or RELAY_PORT.
    return int(os.getenv("ANDROID_RELAY_PORT", os.getenv("RELAY_PORT", "8767")))

def _timeout() -> float:
    return float(os.getenv("ANDROID_BRIDGE_TIMEOUT", "30"))

def _auth_headers() -> dict:
    """Build auth headers with pairing code if configured."""
    token = _bridge_token()
    if token:
        return {"Authorization": f"Bearer {token}"}
    return {}

def _check_requirements() -> bool:
    """Returns True iff the phone is currently paired to the relay's bridge
    channel (``phone_connected`` on ``/bridge/status``). Intentionally the
    ONLY gate — accessibility and master-toggle state are NOT checked here.

    Design history — we iterated on this twice:

    *Version 1* (broken) hit ``/ping`` and looked for fields that endpoint
    doesn't return. Every check returned False, every tool except
    ``android_setup`` was hidden from every gateway platform. Caught
    2026-04-15 by Bailey when Victor reported "no android_* tools available"
    despite the app being healthy.

    *Version 2* (working but wrong UX) fixed the endpoint and added a
    three-gate rule: ``phone_connected AND accessibility_granted AND
    master_enabled``. Idea was that hiding tools when the master toggle is
    off gives the LLM a "clean no-tools signal" instead of letting it try
    and get a 403. Caught 2026-04-15 **same day** in follow-up testing
    when Bailey flipped the master toggle off and Victor hallucinated a
    reason for the missing tools: *"Phone bridge isn't connected right
    now — pair the Hermes-Relay app so I can reach the phone."* The
    phone WAS connected. Victor filled the absence-of-tools with a
    guessed explanation (pairing) and asked the user to do the wrong
    thing. LLMs don't know *why* tools are absent; they invent a cause.

    *Version 3 (this)* — gate only on ``phone_connected``. Let the
    downstream layers return clear structured errors with machine-readable
    ``error_code`` fields:

    * No a11y service → phone-side responds with HTTP 503 +
      ``error_code: service_unavailable`` + a clear "enable it in Android
      Settings" message.
    * Master toggle off → phone-side responds with HTTP 403 +
      ``error_code: bridge_disabled`` + the "this is NOT a pairing
      problem" text.

    Both paths carry enough structured context that the LLM can relay
    accurate instructions to the user. The gate stays on
    ``phone_connected`` because that's the one case where the tools
    literally cannot function — no WSS session, no way to dispatch
    anything — and in THAT case the LLM should see only ``android_setup``
    and correctly deduce "we need to pair". Every other failure mode
    surfaces as a first-class error through the call path we already
    built.

    Takeaway for future plugin check_fn design: use check_fn to express
    *"this tool is fundamentally unreachable right now"*, not *"this
    tool is currently disabled by policy"*. Disabled-by-policy belongs
    in error responses, not schema omission, because LLMs reason about
    presence/absence by inventing narratives.
    """
    try:
        r = requests.get(
            f"{_bridge_url()}/bridge/status",
            headers=_auth_headers(),
            timeout=2,
        )
        if r.status_code == 200:
            data = r.json()
            return bool(data.get("phone_connected", False))
        return False
    except Exception:
        return False

def _post(path: str, payload: dict) -> dict:
    r = requests.post(f"{_bridge_url()}{path}", json=payload,
                      headers=_auth_headers(), timeout=_timeout())
    r.raise_for_status()
    return r.json()

def _get(path: str) -> dict:
    r = requests.get(f"{_bridge_url()}{path}", headers=_auth_headers(),
                     timeout=_timeout())
    r.raise_for_status()
    return r.json()

# ── Tool implementations ───────────────────────────────────────────────────────

def android_ping() -> str:
    try:
        data = _get("/ping")
        return json.dumps({"status": "ok", "bridge": data})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def android_read_screen(include_bounds: bool = False) -> str:
    """
    Returns the accessibility tree of the current screen as JSON.
    Each node has: nodeId, text, contentDescription, className,
                   clickable, focusable, bounds (if include_bounds=True)
    """
    try:
        data = _get(f"/screen?bounds={str(include_bounds).lower()}")
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_tap(x: Optional[int] = None, y: Optional[int] = None,
                node_id: Optional[str] = None) -> str:
    """
    Tap at screen coordinates (x, y) or by accessibility node_id.
    Prefer node_id when available — it's more reliable than coordinates.
    """
    try:
        payload = {}
        if node_id:
            payload["nodeId"] = node_id
        elif x is not None and y is not None:
            payload["x"] = x
            payload["y"] = y
        else:
            return json.dumps({"error": "Provide either (x, y) or node_id"})
        data = _post("/tap", payload)
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_tap_text(text: str, exact: bool = False) -> str:
    """
    Tap the first element whose visible text matches `text`.
    exact=False uses contains matching. exact=True requires full match.
    Useful when you can see text on screen but don't have node IDs.
    """
    try:
        data = _post("/tap_text", {"text": text, "exact": exact})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_type(text: str, clear_first: bool = False) -> str:
    """
    Type text into the currently focused input field.
    Set clear_first=True to clear existing content before typing.
    """
    try:
        data = _post("/type", {"text": text, "clearFirst": clear_first})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_swipe(direction: str, distance: str = "medium") -> str:
    """
    Swipe in direction: up, down, left, right.
    distance: short, medium, long
    """
    try:
        data = _post("/swipe", {"direction": direction, "distance": distance})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_open_app(package: str) -> str:
    """
    Launch an app by its package name.
    Common packages:
      com.ubercab        - Uber
      com.whatsapp       - WhatsApp
      com.spotify.music  - Spotify
      com.google.android.apps.maps - Google Maps
      com.android.chrome - Chrome
      com.google.android.gm - Gmail
    """
    try:
        data = _post("/open_app", {"package": package})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_press_key(key: str) -> str:
    """
    Press a key. Supported keys:
      back, home, recents, power, volume_up, volume_down,
      enter, delete, tab, escape, search, notifications
    """
    try:
        data = _post("/press_key", {"key": key})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_screenshot() -> str:
    """
    Capture a screenshot of the Android screen.

    Writes the JPEG to a temp file, then asks the local Hermes-Relay to
    mint an opaque token via ``POST /media/register``. On success the
    tool returns ``MEDIA:hermes-relay://<token>`` in its output, which
    the phone parses and fetches via bearer-auth'd ``GET /media/<token>``
    on the same relay.

    Fallback: if the relay is not running (or rejects the registration),
    the tool returns the old ``MEDIA:/tmp/<path>`` form and logs a warning.
    The phone's parser renders a "relay offline" placeholder for that case.
    """
    try:
        import base64
        import logging
        import tempfile

        data = _get("/screenshot")
        if "error" in data:
            return json.dumps(data)

        # Extract base64 image from the nested result
        result = data.get("data", data)
        img_b64 = result.get("image", "")
        if not img_b64:
            return json.dumps({"error": "No image data returned"})

        # Save to temp file
        img_bytes = base64.b64decode(img_b64)
        tmp = tempfile.NamedTemporaryFile(suffix=".jpg", prefix="android_screenshot_", delete=False)
        tmp.write(img_bytes)
        tmp.close()

        w = result.get("width", "?")
        h = result.get("height", "?")

        # Try to register with the local relay so the phone gets an opaque
        # token instead of a literal path. Relay must be running on the
        # same host (it's loopback-only). Any failure falls back to the
        # bare path form — the phone shows a placeholder in that case.
        try:
            from plugin.relay.client import register_media
            token = register_media(tmp.name, "image/jpeg", file_name="screenshot.jpg")
        except Exception:
            logging.getLogger("hermes_relay.tools").warning(
                "register_media raised; falling back to bare MEDIA: path",
                exc_info=True,
            )
            token = None

        if token:
            return f"Screenshot captured ({w}x{h})\nMEDIA:hermes-relay://{token}"

        logging.getLogger("hermes_relay.tools").warning(
            "relay not reachable; falling back to bare MEDIA: path "
            "(phone will show a placeholder)"
        )
        return f"Screenshot captured ({w}x{h})\nMEDIA:{tmp.name}"
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_scroll(direction: str, node_id: Optional[str] = None) -> str:
    """
    Scroll within a scrollable element or the whole screen.
    direction: up, down, left, right
    """
    try:
        payload = {"direction": direction}
        if node_id:
            payload["nodeId"] = node_id
        data = _post("/scroll", payload)
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_wait(text: str = None, class_name: str = None,
                 timeout_ms: int = 5000) -> str:
    """
    Wait for an element to appear on screen.
    Polls every 500ms up to timeout_ms.
    Returns the matching node if found, error if timeout.
    """
    try:
        payload = {"timeoutMs": timeout_ms}
        if text:
            payload["text"] = text
        if class_name:
            payload["className"] = class_name
        data = _post("/wait", payload)
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_get_apps() -> str:
    """List all installed apps with their package names and labels."""
    try:
        data = _get("/apps")
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_current_app() -> str:
    """Get the package name and activity of the current foreground app."""
    try:
        data = _get("/current_app")
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


# ── Tier C tools: direct contact / SMS / call dispatch ────────────────────────
#
# These wrap phone-side routes that were already fully implemented
# (/search_contacts, /send_sms, /call in BridgeCommandHandler + matching
# ActionExecutor methods with runtime permission pre-checks) but had never
# been exposed as LLM-callable tools. Pre-0.4.0 the only way for an
# agent to send an SMS was to drive the Messages app step-by-step via
# open_app + read_screen + tap_text + type — fragile across OEMs and slow.
# Direct SmsManager dispatch is safer and matches what the voice fast-path
# already does locally.
#
# The phone handles all the hard parts: runtime permission pre-check,
# destructive-verb confirmation modal (user taps Allow/Deny before the
# action fires), multi-part SMS segmentation, delivery ack. If the flavor
# is googlePlay (not sideload) the phone returns 403 with a clear message
# and the agent can degrade to the UI-automation path.

def android_search_contacts(query: str, limit: int = 20) -> str:
    """Search the phone's contact book by name. Returns matching contacts
    with their phone numbers. Requires READ_CONTACTS permission on phone."""
    try:
        data = _post("/search_contacts", {"query": query, "limit": limit})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_send_sms(to: str, body: str) -> str:
    """Send an SMS directly via the phone's SmsManager (not by driving the
    Messages app UI). `to` must be a phone number — use android_search_contacts
    first if you only have a name. The phone shows a confirmation modal to
    the user before the message fires; this call blocks until they tap
    Allow or Deny (up to the configured confirmation timeout)."""
    try:
        data = _post("/send_sms", {"to": to, "body": body})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_call(number: str) -> str:
    """Dial a phone number. With CALL_PHONE granted (sideload) the call is
    auto-placed; without it the system dialer opens pre-populated and the
    user taps Call manually. Phone shows a confirmation modal before either
    mode — blocks until the user reacts."""
    try:
        data = _post("/call", {"number": number})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def android_return_to_hermes() -> str:
    """Bring the Hermes Relay app back to the foreground on the user's
    phone. Call this as the FINAL step of any multi-app task (sending a
    text, opening Maps, taking a screenshot from another app) so the user
    sees your reply in-context without having to manually switch apps.
    Do NOT call this mid-task — only when you're ready to hand control
    back. Allowed even when Bridge master toggle is off, so you can
    always wrap up cleanly."""
    try:
        data = _post("/return_to_hermes", {})
        return json.dumps(data)
    except Exception as e:
        return json.dumps({"error": str(e)})


def _get_public_ip() -> str:
    """Detect this server's public IP address."""
    for service in ["https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"]:
        try:
            r = requests.get(service, timeout=3)
            if r.status_code == 200:
                return r.text.strip()
        except Exception:
            continue
    # Fallback: hostname
    import socket
    try:
        return socket.gethostbyname(socket.gethostname())
    except Exception:
        return "<your-server-ip>"


def android_setup(pairing_code: str) -> str:
    """
    Configure the Android bridge to point at the unified Hermes-Relay.

    The unified relay (``plugin/relay/server.py``, port 8767) is the single
    WSS endpoint for chat, terminal, bridge, media, and voice. It's meant
    to run as a persistent service (``systemctl --user start hermes-relay``)
    rather than being spawned on demand per tool call, so this function no
    longer starts a standalone relay — it just updates the environment
    variables the ``android_*`` tools read and verifies the relay is up.

    For the actual pairing dance (generating + pre-registering a fresh
    code, producing a QR for the phone to scan), use ``hermes-pair`` or
    ``/hermes-relay-pair``. This function is the fallback for cases where
    the operator already has a code and just wants to tell the tool about
    it.

    Example: android_setup("K7V3NP")
    """
    try:
        port = _relay_port()
        public_ip = _get_public_ip()

        # Save config to ~/.hermes/.env
        relay_url = f"http://localhost:{port}"
        try:
            from hermes_cli.config import save_env_value
            save_env_value("ANDROID_BRIDGE_URL", relay_url)
            save_env_value("ANDROID_BRIDGE_TOKEN", pairing_code)
            save_env_value("ANDROID_RELAY_PORT", str(port))
        except ImportError:
            from pathlib import Path
            env_path = Path.home() / ".hermes" / ".env"
            env_path.parent.mkdir(parents=True, exist_ok=True)
            _update_env_file(env_path, "ANDROID_BRIDGE_URL", relay_url)
            _update_env_file(env_path, "ANDROID_BRIDGE_TOKEN", pairing_code)
            _update_env_file(env_path, "ANDROID_RELAY_PORT", str(port))

        # Update current process env
        os.environ["ANDROID_BRIDGE_URL"] = relay_url
        os.environ["ANDROID_BRIDGE_TOKEN"] = pairing_code

        # Verify the unified relay is reachable and probe phone status.
        server_address = f"{public_ip}:{port}"
        relay_running = False
        phone_connected = False
        try:
            health = requests.get(f"http://localhost:{port}/health", timeout=2)
            if health.status_code == 200:
                relay_running = True
        except Exception:
            relay_running = False

        if relay_running:
            try:
                ping = requests.get(
                    f"http://localhost:{port}/ping",
                    headers=_auth_headers(),
                    timeout=2,
                )
                if ping.status_code == 200:
                    phone_connected = True
            except Exception:
                phone_connected = False

        if not relay_running:
            return json.dumps({
                "status": "error",
                "message": (
                    "Unified Hermes-Relay is not running on "
                    f"localhost:{port}. Start it with "
                    "`systemctl --user start hermes-relay` and retry."
                ),
                "server_address": server_address,
            })

        if phone_connected:
            return json.dumps({
                "status": "ok",
                "message": "Phone is connected and ready!",
                "phone_connected": True,
                "server_address": server_address,
            })

        return json.dumps({
            "status": "ok",
            "message": (
                "Relay is running. Pair the phone via `hermes-pair` "
                "or /hermes-relay-pair, then retry."
            ),
            "phone_connected": False,
            "server_address": server_address,
            "user_instructions": (
                f"Open the Hermes app on your phone and scan the pairing QR.\n"
                f"  Server: {server_address}\n"
                f"  Pairing code: {pairing_code}\n"
                f"Then tap Connect."
            ),
        })

    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def _update_env_file(env_path, key: str, value: str):
    """Simple .env file updater (fallback when hermes_cli.config not available)."""
    lines = []
    if env_path.exists():
        lines = env_path.read_text(encoding="utf-8", errors="replace").splitlines(True)
    found = False
    for i, line in enumerate(lines):
        if line.strip().startswith(f"{key}="):
            lines[i] = f"{key}={value}\n"
            found = True
            break
    if not found:
        if lines and not lines[-1].endswith("\n"):
            lines[-1] += "\n"
        lines.append(f"{key}={value}\n")
    env_path.write_text("".join(lines), encoding="utf-8")


# ── Schema definitions ─────────────────────────────────────────────────────────

_SCHEMAS = {
    "android_ping": {
        "name": "android_ping",
        "description": "Check if the Android bridge is reachable. Call this first before any other android_ tools.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_read_screen": {
        "name": "android_read_screen",
        "description": "Get the accessibility tree of the current Android screen. Returns all visible UI nodes with text, class names, node IDs, and interactability. Use this to understand what's on screen before tapping.",
        "parameters": {
            "type": "object",
            "properties": {
                "include_bounds": {
                    "type": "boolean",
                    "description": "Include pixel coordinates for each node. Default false.",
                    "default": False,
                }
            },
            "required": [],
        },
    },
    "android_tap": {
        "name": "android_tap",
        "description": "Tap a UI element by node_id (preferred) or by screen coordinates (x, y). Always prefer node_id over coordinates — it's more reliable. Get node_ids from android_read_screen.",
        "parameters": {
            "type": "object",
            "properties": {
                "x": {"type": "integer", "description": "X coordinate in pixels"},
                "y": {"type": "integer", "description": "Y coordinate in pixels"},
                "node_id": {"type": "string", "description": "Accessibility node ID from android_read_screen"},
            },
            "required": [],
        },
    },
    "android_tap_text": {
        "name": "android_tap_text",
        "description": "Tap the first visible UI element matching the given text. Useful when you see text on screen and want to tap it without needing node IDs.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to find and tap"},
                "exact": {"type": "boolean", "description": "Exact match (true) or contains match (false, default)", "default": False},
            },
            "required": ["text"],
        },
    },
    "android_type": {
        "name": "android_type",
        "description": "Type text into the currently focused input field. Tap the field first using android_tap or android_tap_text.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to type"},
                "clear_first": {"type": "boolean", "description": "Clear existing content before typing", "default": False},
            },
            "required": ["text"],
        },
    },
    "android_swipe": {
        "name": "android_swipe",
        "description": "Perform a swipe gesture on screen.",
        "parameters": {
            "type": "object",
            "properties": {
                "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
                "distance": {"type": "string", "enum": ["short", "medium", "long"], "default": "medium"},
            },
            "required": ["direction"],
        },
    },
    "android_open_app": {
        "name": "android_open_app",
        "description": (
            "Launch an Android app by its package name. Use android_get_apps "
            "to find package names. "
            "IMPORTANT: When your task is complete in the opened app, call "
            "android_return_to_hermes as your FINAL step so the user sees "
            "your reply in-context without manually switching back from the "
            "other app. Also: before driving Messages / Phone / Contacts via "
            "UI automation (tap + read_screen + type), first consider "
            "android_send_sms / android_call / android_search_contacts — "
            "those dispatch directly and are faster + safer."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "package": {"type": "string", "description": "App package name e.g. com.ubercab"},
            },
            "required": ["package"],
        },
    },
    "android_press_key": {
        "name": "android_press_key",
        "description": "Press a hardware or software key.",
        "parameters": {
            "type": "object",
            "properties": {
                "key": {
                    "type": "string",
                    "enum": ["back", "home", "recents", "power", "volume_up", "volume_down", "enter", "delete", "tab", "escape", "search", "notifications"],
                }
            },
            "required": ["key"],
        },
    },
    "android_screenshot": {
        "name": "android_screenshot",
        "description": "Take a screenshot of the current Android screen. Returns base64 PNG. Use when the accessibility tree is missing context or the screen uses canvas/game rendering.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_scroll": {
        "name": "android_scroll",
        "description": "Scroll the screen or a specific scrollable element.",
        "parameters": {
            "type": "object",
            "properties": {
                "direction": {"type": "string", "enum": ["up", "down", "left", "right"]},
                "node_id": {"type": "string", "description": "Node ID of scrollable container (optional, defaults to screen scroll)"},
            },
            "required": ["direction"],
        },
    },
    "android_wait": {
        "name": "android_wait",
        "description": "Wait for a UI element to appear on screen. Use after actions that trigger loading or navigation.",
        "parameters": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Wait for element with this text"},
                "class_name": {"type": "string", "description": "Wait for element of this class"},
                "timeout_ms": {"type": "integer", "description": "Max wait time in milliseconds", "default": 5000},
            },
            "required": [],
        },
    },
    "android_get_apps": {
        "name": "android_get_apps",
        "description": "List all installed apps on the Android device with their package names and display labels.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_current_app": {
        "name": "android_current_app",
        "description": "Get the package name and activity name of the currently active (foreground) Android app.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
    "android_setup": {
        "name": "android_setup",
        "description": "Start the Android bridge relay and set the pairing code. Call this when the user wants to connect their phone. The relay runs on this server — the phone connects to it remotely via WebSocket. Only needs the pairing code shown in the Hermes Bridge app on the phone.",
        "parameters": {
            "type": "object",
            "properties": {
                "pairing_code": {
                    "type": "string",
                    "description": "6-character pairing code shown in the Hermes Bridge app on the phone",
                },
            },
            "required": ["pairing_code"],
        },
    },
    "android_search_contacts": {
        "name": "android_search_contacts",
        "description": (
            "Search the phone's contact book by name. Returns matching "
            "contacts with their phone numbers. "
            "CALL THIS DIRECTLY when the user gives you a name — do NOT "
            "ask the user 'who is X?' or 'is X a contact on your phone?' "
            "before calling this tool. That's what the tool is FOR. The "
            "user gave you access to their contacts specifically so you "
            "could resolve names autonomously. Bouncing lookup questions "
            "back to the user is the exact anti-pattern this tool exists "
            "to avoid. If the search returns zero matches, THEN ask for "
            "clarification. If it returns one match, use it. If multiple, "
            "pick the top result and mention it in your reply so the user "
            "can correct if needed (the on-device SMS modal is the final "
            "checkpoint anyway). Requires READ_CONTACTS permission on phone."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Name or partial name to search for (e.g. 'Hannah', 'mom', 'dr. smith').",
                },
                "limit": {
                    "type": "integer",
                    "description": "Max matches to return. Default 20.",
                    "default": 20,
                },
            },
            "required": ["query"],
        },
    },
    "android_send_sms": {
        "name": "android_send_sms",
        "description": (
            "Send an SMS directly via the phone's native SmsManager — does "
            "NOT drive the Messages app UI. ALWAYS prefer this over "
            "android_open_app('com.google.android.apps.messaging') + "
            "android_tap + android_type: the direct path is faster, safer, "
            "doesn't leave drafts behind, and works uniformly across OEM "
            "variants.\n\n"
            "TRUST MODEL — read this carefully: the user gave you phone "
            "control by explicitly pairing the Hermes Relay app, enabling "
            "the accessibility service, AND flipping the Agent Control "
            "master toggle ON. They also confirmed each destructive action "
            "on the phone itself via a system-level Allow/Deny modal that "
            "this call blocks on. That on-device modal is the user's FINAL "
            "checkpoint and is sufficient confirmation on its own. "
            "Therefore:\n"
            "- DO NOT ask the user for chat-side confirmation before "
            "calling this tool ('are you sure?', 'confirm the wording?', "
            "'is this the right person?'). The phone modal already asks "
            "them. Chat-side double-confirmation is REDUNDANT and "
            "FRUSTRATING — the user already told you what to send.\n"
            "- DO NOT ask the user to identify a contact by name. Call "
            "android_search_contacts FIRST to resolve names to numbers "
            "autonomously. That's what that tool is for.\n"
            "- If search_contacts returns multiple matches, pick the top "
            "one and mention it briefly in your reply ('texting Hannah "
            "Dixon at +1555...'); the on-device modal will let the user "
            "cancel if wrong.\n"
            "- If you genuinely don't have enough info to construct the "
            "message (no recipient, no body), THEN ask — but only for "
            "the specific missing field.\n\n"
            "The `to` argument MUST be a phone number — if the user gave "
            "a contact name, call android_search_contacts first, extract "
            "the number, then call this. Sideload flavor only — returns "
            "403 on Google Play builds. After sending, you do NOT need to "
            "call android_return_to_hermes (this tool never leaves the "
            "current foreground app)."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "to": {
                    "type": "string",
                    "description": "Phone number (E.164 or local format, e.g. '+15551234567' or '555-123-4567').",
                },
                "body": {
                    "type": "string",
                    "description": "Message body text. Multi-part messages (>160 chars) are segmented automatically.",
                },
            },
            "required": ["to", "body"],
        },
    },
    "android_call": {
        "name": "android_call",
        "description": (
            "Place a phone call. ALWAYS prefer this over driving the Phone "
            "app via android_open_app + android_tap — this dispatches "
            "directly through the system dialer API. With CALL_PHONE "
            "granted (sideload flavor) the call is auto-dialed; otherwise "
            "the system dialer opens pre-populated and the user taps Call "
            "manually.\n\n"
            "TRUST MODEL: the user gave you phone control explicitly via "
            "the Bridge tab master toggle. The phone shows a system-level "
            "confirmation modal before any call fires and blocks until the "
            "user taps Allow or Deny. That on-device modal is the user's "
            "final checkpoint. Do NOT add chat-side verbal confirmation "
            "on top ('are you sure you want to call X?') — that's "
            "redundant double-confirmation. Do NOT ask the user to "
            "identify a contact by name; call android_search_contacts "
            "first and resolve the name yourself.\n\n"
            "Because this tool brings the Phone app to foreground in "
            "dialer-opened mode, call android_return_to_hermes as a "
            "wrap-up step once the call is placed so the user can see "
            "your reply."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "number": {
                    "type": "string",
                    "description": "Phone number to dial (E.164 or local format).",
                },
            },
            "required": ["number"],
        },
    },
    "android_return_to_hermes": {
        "name": "android_return_to_hermes",
        "description": "Bring the Hermes Relay app on the phone back to the foreground. Call this as the FINAL step of any phone-control task that opened or brought focus to another app (Messages, Maps, Chrome, etc.) so the user sees your reply in-context without manually switching apps. Do NOT call mid-task — only when you're ready to hand control back. Allowed even when the Bridge master toggle is disabled.",
        "parameters": {"type": "object", "properties": {}, "required": []},
    },
}

# ── Tool handlers map ──────────────────────────────────────────────────────────

_HANDLERS = {
    "android_ping":             lambda args, **kw: android_ping(),
    "android_read_screen":      lambda args, **kw: android_read_screen(**args),
    "android_tap":              lambda args, **kw: android_tap(**args),
    "android_tap_text":         lambda args, **kw: android_tap_text(**args),
    "android_type":             lambda args, **kw: android_type(**args),
    "android_swipe":            lambda args, **kw: android_swipe(**args),
    "android_open_app":         lambda args, **kw: android_open_app(**args),
    "android_press_key":        lambda args, **kw: android_press_key(**args),
    "android_screenshot":       lambda args, **kw: android_screenshot(),
    "android_scroll":           lambda args, **kw: android_scroll(**args),
    "android_wait":             lambda args, **kw: android_wait(**args),
    "android_get_apps":         lambda args, **kw: android_get_apps(),
    "android_current_app":      lambda args, **kw: android_current_app(),
    "android_setup":            lambda args, **kw: android_setup(**args),
    "android_search_contacts":  lambda args, **kw: android_search_contacts(**args),
    "android_send_sms":         lambda args, **kw: android_send_sms(**args),
    "android_call":             lambda args, **kw: android_call(**args),
    "android_return_to_hermes": lambda args, **kw: android_return_to_hermes(),
}
