"""
hermes-relay plugin — registers android_* tools into hermes-agent registry.

Tools registered:
  - android_ping           check bridge connectivity
  - android_read_screen    get accessibility tree of current screen
  - android_tap            tap at coordinates or by node id
  - android_tap_text       tap element by visible text
  - android_type           type text into focused field
  - android_swipe          swipe gesture
  - android_open_app       launch app by package name
  - android_press_key      press hardware/software key (back, home, recents)
  - android_screenshot     capture screenshot as base64
  - android_scroll         scroll in direction
  - android_wait           wait for element to appear
  - android_get_apps       list installed apps
  - android_current_app    get foreground app package name
  - android_describe_node  full property bag for a node by id (A4)
  - android_setup          configure bridge URL and pairing code
"""

import json
import os
import time
import requests
from typing import Optional

# ── Config ────────────────────────────────────────────────────────────────────
#
# Architecture: Phone connects OUT to Hermes server via WebSocket (NAT-friendly).
# The unified Hermes-Relay server runs on localhost:8767 and multiplexes the
# bridge channel alongside chat, terminal, media, and voice. The legacy
# standalone bridge relay on port 8766 was retired in Phase 3 Wave 1.
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
    """Returns True if the relay is running and a phone is connected."""
    try:
        r = requests.get(f"{_bridge_url()}/ping", headers=_auth_headers(), timeout=2)
        if r.status_code == 200:
            data = r.json()
            return data.get("phone_connected", False) or data.get("accessibilityService", False)
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


def android_describe_node(node_id: str) -> str:
    """
    Return the full property bag for a single accessibility node by ID.

    Accepts a nodeId from `android_read_screen` (format: `w<windowIndex>:<seq>`)
    and returns a richer view than the compact tree: bounds, className, text,
    contentDescription, hintText, viewIdResourceName, childCount, plus every
    state flag (clickable / longClickable / focusable / focused / editable /
    scrollable / checkable / checked / enabled / selected / password).

    `checked` is `null` when the node isn't checkable (so the agent can
    distinguish "not a toggle" from "unchecked toggle"). `hintText` is API 26+
    only; older devices return `null`.

    Use this when `read_screen` gives you a node you want to reason about
    more deeply before deciding to tap/scroll it — e.g. "is this toggle
    currently checked?" or "what's the input's placeholder hint?".
    """
    try:
        if not node_id:
            return json.dumps({"error": "node_id is required"})
        data = _post("/describe_node", {"nodeId": node_id})
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


def android_setup(bridge_session_token: str) -> str:
    """
    FALLBACK helper — tell the android_* tools about an existing session token.

    This is NOT the canonical pairing flow. For first-time setup, use
    ``hermes-pair`` (shell) or ``/hermes-relay-pair`` (slash command) — both
    generate a QR code that the phone scans once, which handles credential
    exchange end-to-end and gives the phone a long-lived session token.

    Use this function only when you already have a session token from a
    previous successful pair and want to teach the host's android_* tools
    about it (e.g., after re-installing the plugin without re-pairing the
    phone). It writes ``ANDROID_BRIDGE_TOKEN`` (the bearer token used for
    every bridge HTTP call) and ``ANDROID_BRIDGE_URL`` to ``~/.hermes/.env``
    and verifies the unified relay is reachable. It does NOT register a
    pairing code with the relay — that's ``plugin.pair``'s job.

    The parameter name was historically ``pairing_code``, but the value is
    actually used as a long-lived bearer token, not a one-shot pairing code.
    Renamed for clarity.

    Example: android_setup("eyJraWQiOi...long-token...")
    """
    # Local alias keeps the rest of the function readable without
    # propagating the rename through every line.
    pairing_code = bridge_session_token
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
                "Relay is running but no phone is currently connected. "
                "Pair the phone via `hermes-pair` or /hermes-relay-pair "
                "(canonical QR flow), then retry. android_setup is a "
                "fallback for when you already have a session token."
            ),
            "phone_connected": False,
            "server_address": server_address,
            "user_instructions": (
                "Run `hermes-pair` on this host, scan the resulting QR "
                "from the Hermes-Relay app (Settings → Connection → Scan "
                "Pairing QR), then re-run android_setup or any other "
                "android_* tool."
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
        "description": "Launch an Android app by its package name. Use android_get_apps to find package names.",
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
    "android_describe_node": {
        "name": "android_describe_node",
        "description": (
            "Return the full property bag for a specific accessibility node by ID. "
            "Use this after android_read_screen when you need richer details about "
            "one node than the compact tree provides — e.g. 'is this toggle checked?', "
            "'what hint text does this input show?', or 'is this button clickable?'. "
            "Returns bounds, className, text, contentDescription, hintText, viewIdResourceName, "
            "childCount, and state flags (clickable, checkable, checked, enabled, etc). "
            "`checked` is null when the node isn't a toggle."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "node_id": {
                    "type": "string",
                    "description": "Stable node ID from android_read_screen (e.g. 'w0:42')",
                },
            },
            "required": ["node_id"],
        },
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
}

# ── Tool handlers map ──────────────────────────────────────────────────────────

_HANDLERS = {
    "android_ping":         lambda args, **kw: android_ping(),
    "android_read_screen":  lambda args, **kw: android_read_screen(**args),
    "android_tap":          lambda args, **kw: android_tap(**args),
    "android_tap_text":     lambda args, **kw: android_tap_text(**args),
    "android_type":         lambda args, **kw: android_type(**args),
    "android_swipe":        lambda args, **kw: android_swipe(**args),
    "android_open_app":     lambda args, **kw: android_open_app(**args),
    "android_press_key":    lambda args, **kw: android_press_key(**args),
    "android_screenshot":   lambda args, **kw: android_screenshot(),
    "android_scroll":       lambda args, **kw: android_scroll(**args),
    "android_wait":         lambda args, **kw: android_wait(**args),
    "android_get_apps":     lambda args, **kw: android_get_apps(),
    "android_current_app":  lambda args, **kw: android_current_app(),
    "android_describe_node": lambda args, **kw: android_describe_node(**args),
    "android_setup":        lambda args, **kw: android_setup(**args),
}

# ── Registry registration ──────────────────────────────────────────────────────

try:
    from tools.registry import registry

    for tool_name, schema in _SCHEMAS.items():
        registry.register(
            name=tool_name,
            toolset="android",
            schema=schema,
            handler=_HANDLERS[tool_name],
            # android_setup must work without a bridge connection (it creates the connection)
            check_fn=(lambda: True) if tool_name == "android_setup" else _check_requirements,
            requires_env=[],  # ANDROID_BRIDGE_URL has a default
        )
except ImportError:
    # Running outside hermes-agent context (e.g. tests)
    pass
