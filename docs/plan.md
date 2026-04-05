# hermes-android — Claude Code Build Plan

> Complete implementation plan for an Android extension toolset for hermes-agent.
> This document is the source of truth for Claude Code to build both repos from scratch.

---

## Overview

Two repos, one system:

- **`hermes-android`** — Python package that registers `android_*` tools into hermes-agent's registry
- **`hermes-android-bridge`** — Android app (Kotlin) that runs on the phone, exposes an HTTP API, and executes actions via AccessibilityService

```
hermes-agent (desktop/server)
    │
    │  HTTP over LAN or USB forwarding
    ▼
hermes-android-bridge (Android app)
    │
    ├── AccessibilityService  →  reads screen tree, executes taps/types
    ├── HTTP Server (Ktor)    →  REST API for hermes to call
    └── Status Overlay        →  always-on HUD widget
```

---

## Repo 1: `hermes-android` (Python toolset)

### Directory structure

```
hermes-android/
├── tools/
│   └── android_tool.py          # All tool registrations
├── skills/
│   └── android/
│       ├── README.md             # Skills index
│       ├── general.md            # Generic Android navigation
│       ├── uber.md               # Uber/ride ordering
│       ├── whatsapp.md           # WhatsApp messaging
│       ├── spotify.md            # Spotify playback
│       ├── maps.md               # Google Maps navigation
│       └── settings.md           # Android settings navigation
├── tests/
│   ├── test_android_tool.py
│   └── conftest.py
├── setup.py
├── pyproject.toml
├── requirements.txt
├── AGENTS.md                     # Hermes-agent skill context file
└── README.md
```

---

### File: `tools/android_tool.py`

This is the core file. It must be placed inside the hermes-agent `tools/` directory OR installed as a package and imported.

**Full implementation:**

```python
"""
hermes-android tool — registers android_* tools into hermes-agent registry.

Tools registered:
  - android_ping          check bridge connectivity
  - android_read_screen   get accessibility tree of current screen
  - android_tap           tap at coordinates or by node id
  - android_tap_text      tap element by visible text
  - android_type          type text into focused field
  - android_swipe         swipe gesture
  - android_open_app      launch app by package name
  - android_press_key     press hardware/software key (back, home, recents)
  - android_screenshot    capture screenshot as base64
  - android_scroll        scroll in direction
  - android_wait          wait for element to appear
  - android_get_apps      list installed apps
  - android_current_app   get foreground app package name
"""

import json
import os
import time
import requests
from typing import Optional

# ── Config ────────────────────────────────────────────────────────────────────

def _bridge_url() -> str:
    return os.getenv("ANDROID_BRIDGE_URL", "http://localhost:8765")

def _timeout() -> float:
    return float(os.getenv("ANDROID_BRIDGE_TIMEOUT", "10"))

def _check_requirements() -> bool:
    """Returns True only if the bridge is reachable."""
    try:
        r = requests.get(f"{_bridge_url()}/ping", timeout=2)
        return r.status_code == 200
    except Exception:
        return False

def _post(path: str, payload: dict) -> dict:
    r = requests.post(f"{_bridge_url()}{path}", json=payload, timeout=_timeout())
    r.raise_for_status()
    return r.json()

def _get(path: str) -> dict:
    r = requests.get(f"{_bridge_url()}{path}", timeout=_timeout())
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
    Capture a screenshot. Returns base64-encoded PNG.
    Use this when the accessibility tree doesn't give enough context.
    """
    try:
        data = _get("/screenshot")
        return json.dumps(data)  # { "image": "<base64>", "width": ..., "height": ... }
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
}

# ── Registry registration ──────────────────────────────────────────────────────

try:
    from tools.registry import registry

    for tool_name, schema in _SCHEMAS.items():
        registry.register(
            name=tool_name,
            toolset="android",
            schema={"type": "function", "function": schema},
            handler=_HANDLERS[tool_name],
            check_fn=_check_requirements,
            requires_env=[],  # ANDROID_BRIDGE_URL has a default
        )
except ImportError:
    # Running outside hermes-agent context (e.g. tests)
    pass
```

---

### File: `setup.py`

```python
from setuptools import setup, find_packages

setup(
    name="hermes-android",
    version="0.1.0",
    packages=find_packages(),
    install_requires=[
        "requests>=2.28.0",
    ],
    package_data={
        "": ["skills/android/*.md"],
    },
    python_requires=">=3.11",
)
```

---

### File: `pyproject.toml`

```toml
[build-system]
requires = ["setuptools>=68", "wheel"]
build-backend = "setuptools.backends.legacy:build"

[project]
name = "hermes-android"
version = "0.1.0"
description = "Android device control toolset for hermes-agent"
requires-python = ">=3.11"
dependencies = ["requests>=2.28.0"]

[project.optional-dependencies]
dev = ["pytest", "pytest-mock", "responses"]
```

---

### File: `AGENTS.md`

This file is picked up automatically by hermes-agent when running from this directory.

```markdown
# hermes-android

## Overview
This extension adds Android device control to hermes-agent via the `android` toolset.
It communicates with a bridge app running on an Android device over HTTP.

## Setup
1. Install the bridge APK on the Android device
2. Grant the bridge app Accessibility Service permission in Settings > Accessibility
3. Grant SYSTEM_ALERT_WINDOW permission
4. Set ANDROID_BRIDGE_URL in ~/.hermes/.env:
   - Same WiFi: `ANDROID_BRIDGE_URL=http://192.168.x.x:8765`
   - USB (recommended): run `adb forward tcp:8765 tcp:8765` then use `http://localhost:8765`
5. Install the Python package: `pip install -e ./hermes-android`
6. Add to hermes-agent model_tools.py _modules: `"tools.android_tool"`
7. Add "android" toolset to toolsets.py

## Tool usage patterns

### Read before act
ALWAYS call android_read_screen before tapping. Never guess coordinates.

### Prefer text over coordinates
Use android_tap_text("Continue") over android_tap(x=540, y=1200).

### Wait after navigation
After opening an app or tapping a button that triggers loading,
always call android_wait with expected text before next action.

### Confirmation pattern for destructive actions
Before confirming a purchase, ride, or send action — always report 
to the user what you're about to do and wait for approval.
Example: "I'm about to confirm an Uber ride to [destination] for [price]. 
Reply 'yes' to confirm."

## Common package names
- com.ubercab — Uber
- com.bolt.client — Bolt  
- com.whatsapp — WhatsApp
- com.spotify.music — Spotify
- com.google.android.apps.maps — Google Maps
- com.android.chrome — Chrome
- com.google.android.gm — Gmail
- com.instagram.android — Instagram
- com.twitter.android — X/Twitter
```

---

### File: `skills/android/uber.md`

```markdown
---
name: android-uber
description: Order an Uber ride on Android using the Uber app
version: 1.0.0
metadata:
  hermes:
    tags: [android, uber, transport]
    category: android
---

# Ordering an Uber on Android

## When to Use
User asks to order/book/get an Uber, taxi, or ride.

## Prerequisites
IMPORTANT: Before confirming any ride, ask the user for approval. 
Show destination, estimated price, and car type.

## Procedure

1. Open the Uber app:
   android_open_app("com.ubercab")

2. Wait for main screen:
   android_wait(text="Where to?", timeout_ms=8000)

3. Tap the "Where to?" field:
   android_tap_text("Where to?")

4. Type the destination:
   android_type("<destination>", clear_first=True)

5. Wait for suggestions and tap the best match:
   android_wait(text="<destination keyword>")
   android_tap_text("<first suggestion>")

6. Read the price and car options from screen:
   android_read_screen()

7. ⚠️ STOP — Report to user:
   "I found the following ride options: [list from screen].
   The recommended option is [UberX] for [price], 
   arriving in ~[N] minutes. Reply 'yes' to confirm."

8. Only after user confirms — tap the ride type and confirm:
   android_tap_text("UberX")
   android_tap_text("Confirm UberX")

9. Wait for confirmation screen:
   android_wait(text="Finding your driver", timeout_ms=10000)

10. Report back:
    android_read_screen()  → extract driver details and ETA

## Pitfalls
- Uber may show a "Where to?" placeholder inside a card — tap inside it
- If logged out, the app shows a phone number prompt — report this to user
- Price surges show a multiplier banner — always mention this to user before confirming
- Uber blocks Accessibility on some Android versions — if tap_text fails, try screenshot + coordinates
```

---

### File: `skills/android/whatsapp.md`

```markdown
---
name: android-whatsapp
description: Send WhatsApp messages on Android
version: 1.0.0
---

# Sending a WhatsApp Message

## Procedure

1. android_open_app("com.whatsapp")
2. android_wait(text="Chats")
3. To open existing chat: android_tap_text("<contact name>")
4. To new message: android_tap_text("New chat") → android_type("<contact name>") → tap match
5. android_tap_text("Type a message")  (or the message input area)
6. android_type("<message text>")
7. ⚠️ STOP — confirm with user before sending
8. android_tap_text("Send")  OR  android_press_key("enter")

## Pitfalls
- WhatsApp's message input node class is "android.widget.EditText"
- After typing, read the screen to verify text is correct before sending
```

---

### File: `skills/android/general.md`

```markdown
---
name: android-general
description: General Android navigation patterns
version: 1.0.0
---

# General Android Navigation

## Opening any app
android_open_app("<package_name>")
If unsure of package name: android_get_apps() → search results

## Going back
android_press_key("back")

## Going home
android_press_key("home")

## Opening notifications
android_press_key("notifications")

## Handling permission dialogs
After opening an app, read_screen may show permission dialogs.
Look for "Allow" / "Deny" / "While using the app" buttons.
android_tap_text("Allow")

## Scrolling to find content
android_scroll("down") — repeat until target text is found
After each scroll, android_read_screen() to check current state

## Dealing with app loading
android_wait(text="<expected element>", timeout_ms=8000)
If it times out, android_screenshot() to see the actual screen state

## Typing into a field
1. android_tap_text("<field label or placeholder text>")
2. android_wait(class_name="android.widget.EditText")
3. android_type("<text>", clear_first=True)
```

---

### Integration into hermes-agent

After installing the package, two manual edits are needed in the hermes-agent repo:

**Edit 1: `model_tools.py`** — add to the `_modules` list:
```python
_modules = [
    # ... existing modules ...
    "tools.android_tool",   # ← ADD THIS
]
```

**Edit 2: `toolsets.py`** — add the android toolset definition:
```python
"android": {
    "description": "Android device control — read screen, tap, type, open apps, and more via the hermes-android bridge",
    "tools": [
        "android_ping", "android_read_screen", "android_tap", "android_tap_text",
        "android_type", "android_swipe", "android_open_app", "android_press_key",
        "android_screenshot", "android_scroll", "android_wait",
        "android_get_apps", "android_current_app",
    ],
},
```

---

## Repo 2: `hermes-android-bridge` (Android app)

### Directory structure

```
hermes-android-bridge/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/hermesandroid/bridge/
│       │   ├── MainActivity.kt
│       │   ├── BridgeApplication.kt
│       │   ├── service/
│       │   │   └── BridgeAccessibilityService.kt
│       │   ├── server/
│       │   │   ├── BridgeServer.kt
│       │   │   └── BridgeRouter.kt
│       │   ├── executor/
│       │   │   ├── ActionExecutor.kt
│       │   │   └── ScreenReader.kt
│       │   ├── power/
│       │   │   └── WakeLockManager.kt
│       │   ├── overlay/
│       │   │   └── StatusOverlay.kt
│       │   └── model/
│       │       ├── ScreenNode.kt
│       │       ├── ActionRequest.kt
│       │       └── ActionResult.kt
│       └── res/
│           ├── layout/
│           │   └── activity_main.xml
│           └── xml/
│               └── accessibility_service_config.xml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
    └── libs.versions.toml
```

---

### File: `gradle/libs.versions.toml`

```toml
[versions]
agp = "8.3.0"
kotlin = "1.9.22"
ktor = "2.3.7"
coroutines = "1.7.3"
gson = "2.10.1"

[libraries]
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-netty = { module = "io.ktor:ktor-server-netty", version.ref = "ktor" }
ktor-server-content-negotiation = { module = "io.ktor:ktor-server-content-negotiation", version.ref = "ktor" }
ktor-serialization-gson = { module = "io.ktor:ktor-serialization-gson", version.ref = "ktor" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
gson = { module = "com.google.code.gson:gson", version.ref = "gson" }
```

---

### File: `app/build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hermesandroid.bridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hermesandroid.bridge"
        minSdk = 26   // Android 8.0 — required for TYPE_APPLICATION_OVERLAY
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
}
```

---

### File: `AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- HTTP server -->
    <uses-permission android:name="android.permission.INTERNET" />
    <!-- Wake screen for background actions -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <!-- Overlay HUD -->
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <!-- Foreground service to keep bridge alive -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <!-- Screenshots via MediaProjection (Phase 2 enhancement) -->
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />

    <application
        android:name=".BridgeApplication"
        android:label="Hermes Bridge"
        android:icon="@mipmap/ic_launcher"
        android:allowBackup="false">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Accessibility service declaration -->
        <service
            android:name=".service.BridgeAccessibilityService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService" />
            </intent-filter>
            <meta-data
                android:name="android.accessibilityservice"
                android:resource="@xml/accessibility_service_config" />
        </service>

    </application>
</manifest>
```

---

### File: `res/xml/accessibility_service_config.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagReportViewIds"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:description="@string/accessibility_description"
    android:notificationTimeout="100"
    android:packageNames="" />
```

Note: `packageNames=""` means all packages — required to control any app.

---

### File: `model/ScreenNode.kt`

```kotlin
package com.hermesandroid.bridge.model

import android.graphics.Rect

data class ScreenNode(
    val nodeId: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val packageName: String?,
    val clickable: Boolean,
    val focusable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val checked: Boolean?,
    val bounds: NodeBounds?,
    val children: List<ScreenNode> = emptyList()
)

data class NodeBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class ActionResult(
    val success: Boolean,
    val message: String = "",
    val data: Any? = null
)
```

---

### File: `executor/ScreenReader.kt`

```kotlin
package com.hermesandroid.bridge.executor

import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.bridge.model.NodeBounds
import com.hermesandroid.bridge.model.ScreenNode
import com.hermesandroid.bridge.service.BridgeAccessibilityService

object ScreenReader {

    fun readCurrentScreen(includeBounds: Boolean): List<ScreenNode> {
        val service = BridgeAccessibilityService.instance
            ?: return listOf()

        return service.windows
            .flatMap { window -> window.root?.let { listOf(it) } ?: emptyList() }
            .map { root -> buildNode(root, includeBounds) }
    }

    private fun buildNode(info: AccessibilityNodeInfo, includeBounds: Boolean): ScreenNode {
        val rect = if (includeBounds) {
            val r = android.graphics.Rect()
            info.getBoundsInScreen(r)
            NodeBounds(r.left, r.top, r.right, r.bottom)
        } else null

        val nodeId = "${info.packageName ?: "?"}_${info.className ?: "?"}_${info.hashCode()}"

        val children = (0 until info.childCount)
            .mapNotNull { info.getChild(it) }
            .map { buildNode(it, includeBounds) }

        return ScreenNode(
            nodeId = nodeId,
            text = info.text?.toString()?.takeIf { it.isNotBlank() },
            contentDescription = info.contentDescription?.toString()?.takeIf { it.isNotBlank() },
            className = info.className?.toString(),
            packageName = info.packageName?.toString(),
            clickable = info.isClickable,
            focusable = info.isFocusable,
            scrollable = info.isScrollable,
            editable = info.isEditable,
            checked = if (info.isCheckable) info.isChecked else null,
            bounds = rect,
            children = children
        )
    }

    /**
     * Find a node by text content. Returns the first match.
     * exact=false: text contains match
     * exact=true: full text equality
     */
    fun findNodeByText(
        text: String,
        exact: Boolean = false
    ): AccessibilityNodeInfo? {
        val service = BridgeAccessibilityService.instance ?: return null
        return service.windows
            .flatMap { it.root?.let { r -> listOf(r) } ?: emptyList() }
            .flatMap { root -> flattenNodes(root) }
            .firstOrNull { node ->
                val nodeText = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                if (exact) nodeText == text else nodeText.contains(text, ignoreCase = true)
            }
    }

    private fun flattenNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }
}
```

---

### File: `executor/ActionExecutor.kt`

```kotlin
package com.hermesandroid.bridge.executor

import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.hermesandroid.bridge.model.ActionResult
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import kotlinx.coroutines.delay

object ActionExecutor {

    suspend fun tap(x: Int? = null, y: Int? = null, nodeId: String? = null): ActionResult =
        WakeLockManager.wakeForAction {
        val service = BridgeAccessibilityService.instance
            ?: return@wakeForAction ActionResult(false, "Accessibility service not running")

        if (nodeId != null) {
            val node = findNodeById(nodeId)
                ?: return@wakeForAction ActionResult(false, "Node not found: $nodeId")
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return@wakeForAction ActionResult(result, if (result) "Tapped node $nodeId" else "Click action failed")
        }

        if (x != null && y != null) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            var done = false
            service.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) { done = true }
                override fun onCancelled(gestureDescription: GestureDescription) { done = true }
            }, null)
            var waited = 0
            while (!done && waited < 2000) { delay(50); waited += 50 }
            return@wakeForAction ActionResult(true, "Tapped ($x, $y)")
        }

        ActionResult(false, "Provide either (x, y) or nodeId")
    }

    suspend fun tapText(text: String, exact: Boolean = false): ActionResult =
        WakeLockManager.wakeForAction {
        val node = ScreenReader.findNodeByText(text, exact)
            ?: return@wakeForAction ActionResult(false, "Element with text '$text' not found")
        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        ActionResult(result, if (result) "Tapped '$text'" else "Click failed on '$text'")
    }

    suspend fun typeText(text: String, clearFirst: Boolean = false): ActionResult =
        WakeLockManager.wakeForAction {
        val service = BridgeAccessibilityService.instance
            ?: return@wakeForAction ActionResult(false, "Accessibility service not running")

        // Find focused editable node
        val focusedNode = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (clearFirst) {
            val bundle = Bundle()
            bundle.putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0
            )
            bundle.putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                focusedNode?.text?.length ?: 0
            )
            focusedNode?.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, bundle)
            focusedNode?.performAction(AccessibilityNodeInfo.ACTION_CUT)
        }

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        val result = focusedNode?.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT, arguments
        ) ?: false
        ActionResult(result, if (result) "Typed text" else "No focused input found")
    }

    suspend fun swipe(direction: String, distance: String = "medium"): ActionResult =
        WakeLockManager.wakeForAction {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")

        val displayMetrics = service.resources.displayMetrics
        val w = displayMetrics.widthPixels
        val h = displayMetrics.heightPixels

        val shortDist = 0.2f
        val mediumDist = 0.4f
        val longDist = 0.7f
        val dist = when (distance) { "short" -> shortDist; "long" -> longDist; else -> mediumDist }

        val (startX, startY, endX, endY) = when (direction) {
            "up" ->    arrayOf(w / 2f, h * 0.7f, w / 2f, h * (0.7f - dist))
            "down" ->  arrayOf(w / 2f, h * 0.3f, w / 2f, h * (0.3f + dist))
            "left" ->  arrayOf(w * 0.8f, h / 2f, w * (0.8f - dist), h / 2f)
            "right" -> arrayOf(w * 0.2f, h / 2f, w * (0.2f + dist), h / 2f)
            else -> return ActionResult(false, "Unknown direction: $direction")
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        service.dispatchGesture(gesture, null, null)
        delay(400)
        ActionResult(true, "Swiped $direction ($distance)")
    }

    fun openApp(packageName: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult(false, "App not found: $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(intent)
        return ActionResult(true, "Opening $packageName")
    }

    fun pressKey(key: String): ActionResult {
        val service = BridgeAccessibilityService.instance
            ?: return ActionResult(false, "Accessibility service not running")
        val action = when (key) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "power" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            else -> return ActionResult(false, "Unknown key: $key")
        }
        val result = service.performGlobalAction(action)
        return ActionResult(result, if (result) "Pressed $key" else "Key press failed")
    }

    suspend fun waitForElement(
        text: String? = null,
        className: String? = null,
        timeoutMs: Int = 5000
    ): ActionResult {
        val interval = 500L
        var elapsed = 0L
        while (elapsed < timeoutMs) {
            val nodes = ScreenReader.readCurrentScreen(false)
            val found = findInTree(nodes, text, className)
            if (found != null) {
                return ActionResult(true, "Element found", found)
            }
            delay(interval)
            elapsed += interval
        }
        return ActionResult(false, "Timeout waiting for element (text=$text, class=$className)")
    }

    fun getInstalledApps(): List<Map<String, String>> {
        val service = BridgeAccessibilityService.instance ?: return emptyList()
        val pm = service.packageManager
        return pm.getInstalledApplications(0).map { appInfo ->
            mapOf(
                "packageName" to appInfo.packageName,
                "label" to pm.getApplicationLabel(appInfo).toString()
            )
        }.sortedBy { it["label"] }
    }

    private fun findNodeById(nodeId: String): AccessibilityNodeInfo? {
        val service = BridgeAccessibilityService.instance ?: return null
        return service.windows
            .flatMap { it.root?.let { r -> listOf(r) } ?: emptyList() }
            .flatMap { root -> flattenNodeInfos(root) }
            .firstOrNull { node ->
                val id = "${node.packageName}_${node.className}_${node.hashCode()}"
                id == nodeId
            }
    }

    private fun flattenNodeInfos(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            result.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return result
    }

    private fun findInTree(
        nodes: List<com.hermesandroid.bridge.model.ScreenNode>,
        text: String?,
        className: String?
    ): com.hermesandroid.bridge.model.ScreenNode? {
        for (node in nodes) {
            val textMatch = text == null || node.text?.contains(text, true) == true ||
                    node.contentDescription?.contains(text, true) == true
            val classMatch = className == null || node.className == className
            if (textMatch && classMatch) return node
            val childMatch = findInTree(node.children, text, className)
            if (childMatch != null) return childMatch
        }
        return null
    }
}
```

---

### File: `service/BridgeAccessibilityService.kt`

```kotlin
package com.hermesandroid.bridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent

class BridgeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BridgeAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
        // Configure to receive events from all apps
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op — we read state on demand, not event-driven
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
```

---

### File: `server/BridgeRouter.kt`

```kotlin
package com.hermesandroid.bridge.server

import com.google.gson.Gson
import com.hermesandroid.bridge.executor.ActionExecutor
import com.hermesandroid.bridge.executor.ScreenReader
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val gson = Gson()

fun Application.configureRouting() {
    routing {

        get("/ping") {
            val serviceRunning = BridgeAccessibilityService.instance != null
            call.respond(mapOf(
                "status" to "ok",
                "accessibilityService" to serviceRunning,
                "version" to "0.1.0"
            ))
        }

        get("/screen") {
            val bounds = call.request.queryParameters["bounds"] == "true"
            val tree = ScreenReader.readCurrentScreen(bounds)
            call.respond(mapOf("tree" to tree, "count" to countNodes(tree)))
        }

        post("/tap") {
            data class TapRequest(val x: Int? = null, val y: Int? = null, val nodeId: String? = null)
            val req = call.receive<TapRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.tap(req.x, req.y, req.nodeId)
            }
            call.respond(result)
        }

        post("/tap_text") {
            data class TapTextRequest(val text: String, val exact: Boolean = false)
            val req = call.receive<TapTextRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.tapText(req.text, req.exact)
            }
            call.respond(result)
        }

        post("/type") {
            data class TypeRequest(val text: String, val clearFirst: Boolean = false)
            val req = call.receive<TypeRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.typeText(req.text, req.clearFirst)
            }
            call.respond(result)
        }

        post("/swipe") {
            data class SwipeRequest(val direction: String, val distance: String = "medium")
            val req = call.receive<SwipeRequest>()
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.swipe(req.direction, req.distance)
            }
            call.respond(result)
        }

        post("/open_app") {
            data class OpenAppRequest(val package_: String)
            val body = call.receiveText()
            val json = gson.fromJson(body, Map::class.java)
            val pkg = json["package"] as? String
                ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing package")
            val result = ActionExecutor.openApp(pkg)
            call.respond(result)
        }

        post("/press_key") {
            data class PressKeyRequest(val key: String)
            val req = call.receive<PressKeyRequest>()
            val result = ActionExecutor.pressKey(req.key)
            call.respond(result)
        }

        get("/screenshot") {
            // Phase 2: implement with MediaProjection
            // For now return placeholder
            call.respond(mapOf(
                "error" to "Screenshot requires MediaProjection setup",
                "hint" to "Grant screen capture permission and restart bridge"
            ))
        }

        post("/scroll") {
            data class ScrollRequest(val direction: String, val nodeId: String? = null)
            val req = call.receive<ScrollRequest>()
            // Scroll is implemented as a swipe
            val result = withContext(Dispatchers.Main) {
                ActionExecutor.swipe(req.direction, "medium")
            }
            call.respond(result)
        }

        post("/wait") {
            data class WaitRequest(
                val text: String? = null,
                val className: String? = null,
                val timeoutMs: Int = 5000
            )
            val req = call.receive<WaitRequest>()
            val result = ActionExecutor.waitForElement(req.text, req.className, req.timeoutMs)
            call.respond(result)
        }

        get("/apps") {
            val apps = ActionExecutor.getInstalledApps()
            call.respond(mapOf("apps" to apps, "count" to apps.size))
        }

        get("/current_app") {
            val service = BridgeAccessibilityService.instance
            val windows = service?.windows
            val foreground = windows?.firstOrNull()?.root
            call.respond(mapOf(
                "package" to (foreground?.packageName ?: "unknown"),
                "className" to (foreground?.className ?: "unknown")
            ))
        }
    }
}

private fun countNodes(nodes: List<Any>): Int {
    // Simple approximation
    return nodes.size
}
```

---

### File: `server/BridgeServer.kt`

```kotlin
package com.hermesandroid.bridge.server

import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*

object BridgeServer {
    private var server: EmbeddedServer<*, *>? = null

    fun start(port: Int = 8765) {
        if (server != null) return
        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) {
                gson {
                    setPrettyPrinting()
                    serializeNulls()
                }
            }
            configureRouting()
        }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}
```

---

### File: `BridgeApplication.kt`

```kotlin
package com.hermesandroid.bridge

import android.app.Application
import com.hermesandroid.bridge.power.WakeLockManager
import com.hermesandroid.bridge.server.BridgeServer

class BridgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WakeLockManager.init(applicationContext)  // ← init before server starts
        BridgeServer.start(port = 8765)
    }
}
```

---

### File: `power/WakeLockManager.kt`

Automatically wakes the screen before any gesture/UI action, then releases the lock after a short hold. This allows the agent to operate while the screen is off.

```kotlin
package com.hermesandroid.bridge.power

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.delay

/**
 * Manages a wake lock so the agent can execute UI actions
 * even when the screen is off.
 *
 * Usage:
 *   WakeLockManager.init(applicationContext)
 *   WakeLockManager.wakeForAction { /* your action here */ }
 */
object WakeLockManager {

    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // How long to keep screen on after each action (ms)
    // Short enough to not drain battery, long enough for gesture dispatch
    private const val HOLD_MS = 3_000L

    fun init(context: Context) {
        powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    /**
     * Acquires a FULL_WAKE_LOCK (wakes screen if off),
     * runs [block], then schedules release after HOLD_MS.
     *
     * Safe to call when screen is already on — no-op in that case.
     */
    suspend fun <T> wakeForAction(block: suspend () -> T): T {
        val pm = powerManager
        val alreadyAwake = pm?.isInteractive ?: true

        if (!alreadyAwake) {
            acquireWakeLock()
            // Give the display stack ~150ms to come alive before gestures
            delay(150)
        }

        return try {
            block()
        } finally {
            if (!alreadyAwake) {
                // Keep screen on briefly so Android can finish rendering
                // then release — screen will go dark again on its own
                delay(HOLD_MS)
                releaseWakeLock()
            }
        }
    }

    private fun acquireWakeLock() {
        val pm = powerManager ?: return
        wakeLock?.release()
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
            PowerManager.ACQUIRE_CAUSES_WAKEUP or
            PowerManager.ON_AFTER_RELEASE,
            "hermes:bridge_action"
        ).apply { acquire(10_000) } // hard 10s safety cap
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {}
        wakeLock = null
    }

    /** Call this if you want to force-release (e.g. on service stop). */
    fun forceRelease() = releaseWakeLock()
}
```

---

### File: `overlay/StatusOverlay.kt`

```kotlin
package com.hermesandroid.bridge.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Minimal always-on status bar indicator.
 * Shows a small "H●" dot in the corner when the bridge is running.
 */
object StatusOverlay {
    private var overlayView: View? = null

    fun show(context: Context) {
        if (overlayView != null) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 8
            y = 48
        }

        val tv = TextView(context).apply {
            text = "H●"
            textSize = 10f
            setTextColor(Color.parseColor("#4CAF50"))
            alpha = 0.7f
        }

        wm.addView(tv, params)
        overlayView = tv
    }

    fun hide(context: Context) {
        overlayView?.let {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.removeView(it)
            overlayView = null
        }
    }

    fun setStatus(active: Boolean) {
        (overlayView as? TextView)?.apply {
            text = if (active) "H●" else "H○"
            setTextColor(Color.parseColor(if (active) "#4CAF50" else "#FF5722"))
        }
    }
}
```

---

### File: `MainActivity.kt`

```kotlin
package com.hermesandroid.bridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hermesandroid.bridge.overlay.StatusOverlay
import com.hermesandroid.bridge.service.BridgeAccessibilityService
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        updateStatus()

        // Open accessibility settings if service not enabled
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Request overlay permission
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            } else {
                StatusOverlay.show(this)
            }
        }

        // Display local IP
        val ip = getLocalIpAddress()
        findViewById<TextView>(R.id.tvAddress).text =
            "Bridge URL: http://$ip:8765\n\nFor USB: adb forward tcp:8765 tcp:8765"
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val serviceRunning = BridgeAccessibilityService.instance != null
        findViewById<TextView>(R.id.tvStatus).text =
            if (serviceRunning) "✓ Accessibility Service: Active"
            else "✗ Accessibility Service: Not active — tap button below"
    }

    private fun getLocalIpAddress(): String {
        return NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress ?: "localhost"
    }
}
```

---

## Build order for Claude Code

Execute in this exact sequence:

### Step 1 — Scaffold `hermes-android` Python package
1. Create all directories and files as specified above
2. Verify `tools/android_tool.py` has all 13 tools registered
3. Create the 5 skills files
4. Run `pip install -e .` to verify setup.py is valid

### Step 2 — Scaffold `hermes-android-bridge` Android project
1. Create Android Studio project with package `com.hermesandroid.bridge`, minSdk 26, Kotlin
2. Replace `build.gradle.kts` and `libs.versions.toml` with versions above
3. Create all Kotlin files in their correct packages:
   - `service/BridgeAccessibilityService.kt`
   - `executor/ScreenReader.kt` + `ActionExecutor.kt`
   - `power/WakeLockManager.kt`  ← wake lock
   - `server/BridgeServer.kt` + `BridgeRouter.kt`
   - `overlay/StatusOverlay.kt`
   - `BridgeApplication.kt` + `MainActivity.kt`
4. Add `accessibility_service_config.xml` to `res/xml/`
5. Replace `AndroidManifest.xml`
6. Create a minimal `activity_main.xml` layout with the UI elements referenced in MainActivity

### Step 3 — Integrate into hermes-agent
1. Copy `tools/android_tool.py` into hermes-agent's `tools/` directory
2. Add `"tools.android_tool"` to `_modules` list in `model_tools.py`
3. Add `"android"` toolset definition to `toolsets.py`
4. Copy `skills/android/` directory to `~/.hermes/skills/android/`

### Step 4 — Verify
1. Run `hermes tools` — confirm `android` toolset and 13 tools appear
2. Build and install the APK on Android device
3. Grant Accessibility Service permission
4. Run `adb forward tcp:8765 tcp:8765`
5. Run `hermes chat --toolsets android -q "ping the android bridge"`
6. Expected: `{ "status": "ok", "accessibilityService": true, "version": "0.1.0" }`

---

## Environment variables

Add to `~/.hermes/.env`:

```bash
# IP of the Android device (same WiFi)
ANDROID_BRIDGE_URL=http://192.168.x.x:8765

# Or if using USB forwarding (recommended for dev)
ANDROID_BRIDGE_URL=http://localhost:8765

# Timeout for bridge HTTP calls (seconds, default 10)
ANDROID_BRIDGE_TIMEOUT=10
```

---

## Known limitations and workarounds

| Issue | Cause | Workaround |
|---|---|---|
| Screen off blocks gestures | Android requires interactive display for gesture dispatch | **Handled** — `WakeLockManager.wakeForAction` wakes screen before every action, releases after 3s |
| Screen flashes on briefly per action | Wake lock forces display on | Expected — screen dims again automatically; use min brightness to reduce visibility |
| Uber blocks A11y taps | App detects automation | Use Uber deep link + Uber API for booking |
| Banking apps unresponsive | FLAG_SECURE blocks A11y | No workaround — skip these apps |
| Text field not found | App uses canvas rendering | `android_screenshot()` + coordinates |
| Gesture dispatch fails | Android 13+ restrictions on certain gestures | Use `ACTION_CLICK` on node instead |
| Service dies after 30min | Android battery optimization | Add bridge app to battery optimization whitelist |

---

## USB forwarding (recommended for development)

```bash
# Forward phone port 8765 to localhost:8765
adb forward tcp:8765 tcp:8765

# Verify
curl http://localhost:8765/ping
# Expected: {"status":"ok","accessibilityService":true}
```

This avoids WiFi networking issues and works even when phone is on mobile data.