# MCP Tooling for Development

Two MCP servers provide AI-assisted Android development capabilities in this project. They serve different layers and are designed to be used together.

## Overview

| Server | What it talks to | What it knows | When to use |
|--------|-----------------|---------------|-------------|
| **android-tools-mcp** | Android Studio (via internal Gemini plugin APIs) | Project structure, Gradle model, Compose previews, Android docs, source code | Build-time: previewing, building, searching, looking up APIs |
| **mobile-mcp** | Device/emulator (via ADB) | Screen state, UI elements, touch input, app lifecycle | Runtime: testing, interacting with the running app, UI verification |

```
┌─────────────────────────────────────────────────────────────────┐
│                      Claude Code (MCP Client)                    │
├────────────────────────────┬────────────────────────────────────┤
│   android-tools-mcp        │   mobile-mcp                       │
│   (IDE / Build layer)      │   (Device / Runtime layer)         │
│                            │                                    │
│   ┌──────────────────┐     │   ┌──────────────────────────┐    │
│   │  Android Studio   │     │   │  ADB → Device/Emulator    │    │
│   │  (Gemini plugin)  │     │   │  (accessibility + input)  │    │
│   └──────────────────┘     │   └──────────────────────────┘    │
├────────────────────────────┴────────────────────────────────────┤
│                     Full Dev Loop                                │
│   Code → Preview → Build → Deploy → Interact → Screenshot       │
└─────────────────────────────────────────────────────────────────┘
```

---

## android-tools-mcp

**Source:** [Codename-11/android-tools-mcp](https://github.com/Codename-11/android-tools-mcp) (fork of [amsavarthan/android-tools-mcp](https://github.com/amsavarthan/android-tools-mcp))

An IntelliJ plugin that bridges Android Studio's built-in Gemini agent tools to external AI coding tools via MCP. Our fork adds image/screenshot support (ImageContent) and Windows build fixes.

### Prerequisites

- Android Studio Panda 2025.3.+ with Gemini plugin enabled (bundled by default)
- Python 3.7+ (for the stdio-to-SSE bridge script)
- Plugin installed in AS (see below)

### Setup

1. **Install the plugin** — download ZIP from [releases](https://github.com/Codename-11/android-tools-mcp/releases) or build from source:
   ```bash
   cd ../android-tools-mcp
   # Create local.properties with your AS path (Windows example):
   echo "android.studio.path=C:\\Program Files\\Android\\Android Studio" > local.properties
   ./gradlew buildPlugin
   # ZIP lands in build/distributions/ — install via AS Settings → Plugins → Install from Disk
   ```

2. **Register in Claude Code** (already configured for this project):
   ```bash
   claude mcp add android-studio -- python path/to/android-tools-mcp/scripts/android-studio-mcp.py
   ```

3. **Open a project in Android Studio** — the MCP server starts automatically on port 24601.

### Tools (20)

**Device:**
| Tool | Description |
|------|-------------|
| `take_screenshot` | Capture screenshot from connected device (returns image) |
| `read_logcat` | Read logcat output |
| `ui_state` | Dump current UI hierarchy |
| `adb_shell_input` | Send input events via ADB |
| `deploy` | Build and deploy to connected device |
| `render_compose_preview` | Render a `@Preview` composable (returns image) |

**Gradle / Build:**
| Tool | Description |
|------|-------------|
| `gradle_sync` | Trigger Gradle sync |
| `gradle_build` | Build via Gradle |
| `get_top_level_sub_projects` | List subprojects |
| `get_build_file_location` | Get build file path for an artifact |
| `get_gradle_artifact_from_file` | Identify which artifact owns a source file |
| `get_assemble_task_for_artifact` | Get assemble task for an artifact |
| `get_test_task_for_artifact` | Get test task for an artifact |
| `get_artifact_consumers` | List artifacts depending on a given artifact |
| `get_test_artifacts_for_sub_project` | List test artifacts for a subproject |
| `get_source_folders_for_artifact` | List source folders for an artifact |

**Docs / Search:**
| Tool | Description |
|------|-------------|
| `search_android_docs` | Search Android developer documentation |
| `fetch_android_docs` | Fetch content of an Android docs page |
| `code_search` | Search code in the open project (AS indexed) |
| `version_lookup` | Look up latest stable/preview versions of Maven artifacts |

**Meta:**
| Tool | Description |
|------|-------------|
| `_refresh_tools` | Re-discover tools (call if tools seem missing after AS restart) |

### Troubleshooting

| Problem | Fix |
|---------|-----|
| `Failed to connect` | Android Studio must be running with a project open |
| 0 tools discovered | Wait for Gradle sync to complete; ensure Gemini plugin is enabled |
| Port conflict | Change with `-Dandroid.tools.mcp.port=PORT` in AS VM options |
| Screenshots return text only | Make sure you're using the Codename-11 fork (v0.1.1+), not upstream |

---

## mobile-mcp

**Source:** [mobile-next/mobile-mcp](https://github.com/mobile-next/mobile-mcp) (4,400+ stars)

The de facto standard MCP server for mobile device control. Provides direct ADB-based interaction with Android devices and emulators — tap, swipe, screenshot, accessibility tree inspection, app management.

### Prerequisites

- Node.js v22+
- Android Platform Tools (ADB) — bundled with Android Studio, or install standalone from [developer.android.com](https://developer.android.com/tools/releases/platform-tools)
- A connected device or running emulator

### Setup

Already configured for this project. To add manually:
```bash
claude mcp add mobile-mcp -e MOBILEMCP_DISABLE_TELEMETRY=1 -- npx -y @mobilenext/mobile-mcp@latest
```

No separate install step — runs via `npx` on demand.

### Tools (20)

**Device Management:**
| Tool | Description |
|------|-------------|
| `mobile_list_available_devices` | List all devices (emulators, real devices) |
| `mobile_get_screen_size` | Get screen dimensions in pixels |
| `mobile_get_orientation` | Get current orientation |
| `mobile_set_orientation` | Change orientation (portrait/landscape) |

**App Management:**
| Tool | Description |
|------|-------------|
| `mobile_list_apps` | List all installed apps |
| `mobile_launch_app` | Launch app by package name |
| `mobile_terminate_app` | Stop a running app |
| `mobile_install_app` | Install from APK file |
| `mobile_uninstall_app` | Uninstall by package name |

**Screen Interaction:**
| Tool | Description |
|------|-------------|
| `mobile_take_screenshot` | Take a screenshot |
| `mobile_save_screenshot` | Save screenshot to file |
| `mobile_list_elements_on_screen` | List UI elements with coordinates and properties |
| `mobile_click_on_screen_at_coordinates` | Tap at x,y |
| `mobile_double_tap_on_screen` | Double-tap at coordinates |
| `mobile_long_press_on_screen_at_coordinates` | Long press at coordinates |
| `mobile_swipe_on_screen` | Swipe in any direction |

**Input / Navigation:**
| Tool | Description |
|------|-------------|
| `mobile_type_keys` | Type text into focused element |
| `mobile_press_button` | Press device buttons (HOME, BACK, VOLUME, ENTER) |
| `mobile_open_url` | Open URL in device browser |

### Configuration

| Option | How |
|--------|-----|
| Disable telemetry | `MOBILEMCP_DISABLE_TELEMETRY=1` env var (already set in project config) |
| SSE server mode | `npx @mobilenext/mobile-mcp@latest --listen 3000` |
| SSE auth | `MOBILEMCP_AUTH=my-secret-token` env var |

---

## When to Use Which

| Task | Server |
|------|--------|
| "Does this Compose preview look right?" | `android-tools-mcp` → `render_compose_preview` |
| "Build the debug APK" | `android-tools-mcp` → `gradle_build` |
| "What version of OkHttp is latest?" | `android-tools-mcp` → `version_lookup` |
| "Search Android docs for Navigation" | `android-tools-mcp` → `search_android_docs` |
| "Tap the Settings button on screen" | `mobile-mcp` → `mobile_click_on_screen_at_coordinates` |
| "What UI elements are visible?" | `mobile-mcp` → `mobile_list_elements_on_screen` |
| "Take a screenshot of the running app" | Either — both have screenshot tools |
| "Install and launch the APK" | `mobile-mcp` → `mobile_install_app` + `mobile_launch_app` |
| "Read crash logs" | `android-tools-mcp` → `read_logcat` |
| "Swipe to the next tab" | `mobile-mcp` → `mobile_swipe_on_screen` |

### Overlap

Four tools exist in both servers: screenshot, logcat/logs, UI hierarchy, and shell input. The difference:
- **android-tools-mcp** goes through AS's device bridge (respects AS device selector, integrated with the IDE)
- **mobile-mcp** goes through raw ADB (works without AS running, supports multi-device by serial)

Both can be used — android-tools-mcp requires AS running, mobile-mcp only requires ADB.
