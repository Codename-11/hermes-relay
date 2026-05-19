use portable_pty::{native_pty_system, CommandBuilder, MasterPty, PtySize};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    env, fs,
    io::{BufRead, BufReader, Read, Write},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicBool, AtomicU64, Ordering},
        Mutex,
    },
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tauri::{
    image::Image,
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager, PhysicalPosition, State,
};
use tauri_plugin_global_shortcut::{Code, GlobalShortcutExt, Modifiers, Shortcut, ShortcutState};
#[cfg(windows)]
use windows::Win32::UI::WindowsAndMessaging::{
    GetWindowLongW, SetWindowLongW, SetWindowPos, GWL_EXSTYLE, GWL_STYLE, HWND_TOPMOST,
    SWP_FRAMECHANGED, SWP_NOACTIVATE, SWP_NOMOVE, SWP_NOSIZE, SWP_SHOWWINDOW, WS_CAPTION,
    WS_EX_APPWINDOW, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TRANSPARENT, WS_MAXIMIZEBOX,
    WS_MINIMIZEBOX, WS_SYSMENU, WS_THICKFRAME,
};

const DEFAULT_SHORTCUT: &str = "Ctrl+Shift+H";
const LOG_LIMIT: usize = 300;
const OVERLAY_WORK_AREA_MARGIN: i32 = 8;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
struct OverlayConfig {
    position: String,
    visible: bool,
}

impl Default for OverlayConfig {
    fn default() -> Self {
        Self {
            position: "top_right".to_string(),
            visible: true,
        }
    }
}

fn default_tier() -> String {
    "easy".to_string()
}

fn default_blocklist() -> Vec<String> {
    vec![
        "password manager".to_string(),
        "credential vault".to_string(),
        "passkey prompt".to_string(),
        "mfa prompt".to_string(),
        "banking".to_string(),
        "brokerage".to_string(),
        "crypto wallet".to_string(),
        "payment app".to_string(),
        "windows security".to_string(),
        "firewall settings".to_string(),
        "account settings".to_string(),
        ".ssh".to_string(),
        "private key".to_string(),
        "browser passwords".to_string(),
    ]
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
struct DesktopControlConfig {
    #[allow(dead_code)]
    #[serde(default = "default_tier", skip_serializing)]
    tier: String,
    #[serde(default)]
    relay_url: Option<String>,
    #[serde(default)]
    chat_gateway_url: Option<String>,
    #[serde(default = "default_true")]
    auto_start_daemon: bool,
    #[serde(default)]
    experimental_computer_use: bool,
    #[serde(default = "default_shortcut")]
    emergency_shortcut: String,
    #[serde(default)]
    overlay: OverlayConfig,
    #[serde(default = "default_blocklist")]
    blocklist: Vec<String>,
}

impl Default for DesktopControlConfig {
    fn default() -> Self {
        Self {
            tier: default_tier(),
            relay_url: None,
            chat_gateway_url: None,
            auto_start_daemon: true,
            experimental_computer_use: false,
            emergency_shortcut: default_shortcut(),
            overlay: OverlayConfig::default(),
            blocklist: default_blocklist(),
        }
    }
}

fn default_true() -> bool {
    true
}

fn default_shortcut() -> String {
    DEFAULT_SHORTCUT.to_string()
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct SessionSummary {
    url: String,
    server_version: Option<String>,
    paired_at: Option<f64>,
    ttl_expires_at: Option<f64>,
    endpoint_role: Option<String>,
    tools_consented: bool,
    computer_use_consented: bool,
    token_redacted: String,
}

#[derive(Debug, Clone, Deserialize)]
struct StoredSessionsFile {
    sessions: HashMap<String, StoredSessionRecord>,
}

#[derive(Debug, Clone, Deserialize)]
struct StoredSessionRecord {
    token: String,
    server_version: Option<String>,
    paired_at: Option<f64>,
    ttl_expires_at: Option<f64>,
    endpoint_role: Option<String>,
    tools_consented: Option<bool>,
    computer_use_consented: Option<bool>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct CliStatus {
    available: bool,
    command: String,
    mode: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct ChatSetupState {
    default_mode: String,
    can_use_relay: bool,
    can_use_gateway: bool,
    relay_url: Option<String>,
    gateway_url: Option<String>,
    setup_hint: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct ChatTurnStatus {
    running: bool,
    id: u64,
    pid: Option<u32>,
    mode: String,
    route: String,
    started_at_ms: u128,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct ChatStreamLine {
    id: u64,
    stream: String,
    line: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct ChatStreamExit {
    id: u64,
    code: Option<i32>,
    success: bool,
    message: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct PluginCapability {
    id: String,
    label: String,
    description: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct PluginDescriptor {
    id: String,
    name: String,
    description: String,
    source_url: String,
    package_name: String,
    binary_name: String,
    tabs: Vec<String>,
    commands: Vec<PluginCapability>,
    keybindings: Vec<PluginCapability>,
    status_cards: Vec<PluginCapability>,
    session_actions: Vec<PluginCapability>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct PluginCommandPlan {
    program: String,
    args: Vec<String>,
    display: String,
    mode: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct PluginStatus {
    descriptor: PluginDescriptor,
    installed: bool,
    available: bool,
    version: Option<String>,
    command: String,
    installer: Option<PluginCommandPlan>,
    update: Option<PluginCommandPlan>,
    fallback: Option<PluginCommandPlan>,
    launch: Option<PluginCommandPlan>,
    resume: Option<PluginCommandPlan>,
    setup_hint: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct DaemonStatus {
    running: bool,
    paused: bool,
    pid: Option<u32>,
    remote: Option<String>,
    started_at_ms: Option<u128>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct TaskLogEntry {
    ts_ms: u128,
    level: String,
    event: String,
    message: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
struct PendingGrantRequest {
    id: String,
    #[serde(default)]
    kind: Option<String>,
    mode: String,
    duration_seconds: u64,
    reason: String,
    #[serde(default)]
    scope: serde_json::Value,
    created_at: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct DashboardState {
    config: DesktopControlConfig,
    config_path: String,
    session_store_path: String,
    terminal_session_store_path: String,
    sessions: Vec<SessionSummary>,
    selected_url: Option<String>,
    chat: ChatSetupState,
    daemon: DaemonStatus,
    embedded_terminal: EmbeddedTerminalStatus,
    cli: CliStatus,
    terminal_cli: CliStatus,
    plugins: Vec<PluginStatus>,
    task_log: Vec<TaskLogEntry>,
    pending_grants: Vec<PendingGrantRequest>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct CommandRunResult {
    ok: bool,
    code: Option<i32>,
    stdout: String,
    stderr: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct EmbeddedTerminalStatus {
    running: bool,
    id: Option<u64>,
    pid: Option<u32>,
    remote: Option<String>,
    session_name: Option<String>,
    surface_id: Option<String>,
    surface_label: Option<String>,
    started_at_ms: Option<u128>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct EmbeddedTerminalOutput {
    id: u64,
    data: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "snake_case")]
struct EmbeddedTerminalExit {
    id: u64,
    message: String,
}

struct DaemonProcess {
    child: Child,
    remote: String,
    started_at_ms: u128,
}

struct ChatTurnProcess {
    id: u64,
    child: Child,
    mode: String,
    route: String,
    started_at_ms: u128,
}

struct EmbeddedTerminalProcess {
    id: u64,
    child: Box<dyn portable_pty::Child + Send + Sync>,
    master: Box<dyn MasterPty + Send>,
    writer: Box<dyn Write + Send>,
    remote: String,
    session_name: Option<String>,
    surface_id: String,
    surface_label: String,
    started_at_ms: u128,
}

struct AppState {
    daemon: Mutex<Option<DaemonProcess>>,
    chat_turn: Mutex<Option<ChatTurnProcess>>,
    chat_turn_next_id: AtomicU64,
    embedded_terminal: Mutex<Option<EmbeddedTerminalProcess>>,
    embedded_terminal_next_id: AtomicU64,
    task_log: Mutex<Vec<TaskLogEntry>>,
    paused: AtomicBool,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            daemon: Mutex::new(None),
            chat_turn: Mutex::new(None),
            chat_turn_next_id: AtomicU64::new(1),
            embedded_terminal: Mutex::new(None),
            embedded_terminal_next_id: AtomicU64::new(1),
            task_log: Mutex::new(Vec::new()),
            paused: AtomicBool::new(false),
        }
    }
}

#[derive(Clone)]
struct CliInvocation {
    program: PathBuf,
    prefix_args: Vec<String>,
    mode: String,
}

fn now_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or_default()
}

fn home_dir() -> Result<PathBuf, String> {
    if let Some(home) = env::var_os("USERPROFILE").or_else(|| env::var_os("HOME")) {
        return Ok(PathBuf::from(home));
    }
    Err("could not resolve user home directory".to_string())
}

fn hermes_dir() -> Result<PathBuf, String> {
    Ok(home_dir()?.join(".hermes"))
}

fn config_path() -> Result<PathBuf, String> {
    Ok(hermes_dir()?.join("desktop-control.json"))
}

fn sessions_path() -> Result<PathBuf, String> {
    Ok(hermes_dir()?.join("remote-sessions.json"))
}

fn terminal_sessions_path() -> Result<PathBuf, String> {
    Ok(hermes_dir()?.join("desktop-sessions.json"))
}

fn voice_discovery_path() -> Result<PathBuf, String> {
    Ok(hermes_dir()?.join("desktop-voice.json"))
}

fn tray_control_path() -> Result<PathBuf, String> {
    Ok(hermes_dir()?.join("desktop-tray-control.json"))
}

fn grant_bridge_dir() -> Result<PathBuf, String> {
    Ok(hermes_dir()?.join("grant-bridge"))
}

fn load_config() -> DesktopControlConfig {
    match config_path()
        .ok()
        .and_then(|path| fs::read_to_string(path).ok())
        .and_then(|raw| serde_json::from_str::<DesktopControlConfig>(&raw).ok())
    {
        Some(config) => config,
        None => DesktopControlConfig::default(),
    }
}

fn save_config_file(config: &DesktopControlConfig) -> Result<(), String> {
    let path = config_path()?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("create config dir failed: {e}"))?;
    }
    let tmp = path.with_extension("json.tmp");
    let raw = serde_json::to_string_pretty(config)
        .map_err(|e| format!("serialize config failed: {e}"))?;
    fs::write(&tmp, raw).map_err(|e| format!("write config failed: {e}"))?;
    fs::rename(&tmp, &path).map_err(|e| format!("replace config failed: {e}"))?;
    Ok(())
}

fn load_sessions() -> Vec<SessionSummary> {
    let raw = match sessions_path()
        .ok()
        .and_then(|path| fs::read_to_string(path).ok())
    {
        Some(raw) => raw,
        None => return Vec::new(),
    };
    let parsed = match serde_json::from_str::<StoredSessionsFile>(&raw) {
        Ok(parsed) => parsed,
        Err(_) => return Vec::new(),
    };
    let mut out = parsed
        .sessions
        .into_iter()
        .filter_map(|(url, session)| {
            if session.token.is_empty() {
                return None;
            }
            Some(SessionSummary {
                url,
                server_version: session.server_version,
                paired_at: session.paired_at,
                ttl_expires_at: session.ttl_expires_at,
                endpoint_role: session.endpoint_role,
                tools_consented: session.tools_consented.unwrap_or(false),
                computer_use_consented: session.computer_use_consented.unwrap_or(false),
                token_redacted: "(redacted)".to_string(),
            })
        })
        .collect::<Vec<_>>();
    out.sort_by(|a, b| a.url.cmp(&b.url));
    out
}

fn set_tools_consent(url: &str, consented: bool) -> Result<(), String> {
    set_tools_consent_at(&sessions_path()?, url, consented)
}

fn set_tools_consent_at(path: &Path, url: &str, consented: bool) -> Result<(), String> {
    let url = url.trim();
    if url.is_empty() {
        return Err("relay URL is required".to_string());
    }
    let raw =
        fs::read_to_string(&path).map_err(|e| format!("read remote-sessions.json failed: {e}"))?;
    let mut doc: serde_json::Value = serde_json::from_str(&raw)
        .map_err(|e| format!("parse remote-sessions.json failed: {e}"))?;
    let sessions = doc
        .get_mut("sessions")
        .and_then(serde_json::Value::as_object_mut)
        .ok_or_else(|| "remote-sessions.json has no sessions object".to_string())?;
    let record = sessions
        .get_mut(url)
        .and_then(serde_json::Value::as_object_mut)
        .ok_or_else(|| format!("no stored session for {url}"))?;
    record.insert(
        "tools_consented".to_string(),
        serde_json::Value::Bool(consented),
    );
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("create session dir failed: {e}"))?;
    }
    let tmp = path.with_extension("json.tmp");
    let rendered = serde_json::to_string_pretty(&doc)
        .map_err(|e| format!("serialize sessions failed: {e}"))?;
    fs::write(&tmp, rendered).map_err(|e| format!("write sessions failed: {e}"))?;
    fs::rename(&tmp, &path).map_err(|e| format!("replace sessions failed: {e}"))?;
    Ok(())
}

fn valid_bridge_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 96
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

fn load_pending_grants() -> Vec<PendingGrantRequest> {
    let dir = match grant_bridge_dir() {
        Ok(dir) => dir,
        Err(_) => return Vec::new(),
    };
    let entries = match fs::read_dir(dir) {
        Ok(entries) => entries,
        Err(_) => return Vec::new(),
    };
    let mut out = entries
        .filter_map(Result::ok)
        .filter_map(|entry| {
            let name = entry.file_name().to_string_lossy().to_string();
            if !name.starts_with("request-") || !name.ends_with(".json") {
                return None;
            }
            fs::read_to_string(entry.path())
                .ok()
                .and_then(|raw| serde_json::from_str::<PendingGrantRequest>(&raw).ok())
        })
        .collect::<Vec<_>>();
    out.sort_by(|a, b| a.created_at.cmp(&b.created_at));
    out
}

fn selected_url(config: &DesktopControlConfig, sessions: &[SessionSummary]) -> Option<String> {
    let configured = config
        .relay_url
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty());
    if let Some(url) = configured {
        if sessions.iter().any(|session| session.url == url) {
            return Some(url.to_string());
        }
    }
    (sessions.len() == 1).then(|| sessions[0].url.clone())
}

fn selected_or_remote(
    remote: Option<String>,
    config: &DesktopControlConfig,
    sessions: &[SessionSummary],
) -> Result<String, String> {
    remote
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToString::to_string)
        .or_else(|| selected_url(config, sessions))
        .ok_or_else(|| "no paired relay selected".to_string())
}

fn trim_nonempty(value: Option<&String>) -> Option<String> {
    value
        .map(|v| v.trim())
        .filter(|v| !v.is_empty())
        .map(ToString::to_string)
}

fn normalize_gateway_url(raw: &str) -> Result<String, String> {
    let value = raw.trim();
    if value.is_empty() {
        return Err("gateway URL is required".to_string());
    }
    let mut normalized = if value.contains("://") {
        value.to_string()
    } else {
        format!("http://{value}")
    };
    while normalized.ends_with('/') {
        normalized.pop();
    }
    if !(normalized.starts_with("http://") || normalized.starts_with("https://")) {
        return Err("gateway URL must use http:// or https://".to_string());
    }
    Ok(normalized)
}

fn chat_setup_state(config: &DesktopControlConfig, sessions: &[SessionSummary]) -> ChatSetupState {
    let relay = selected_url(config, sessions);
    let gateway = trim_nonempty(config.chat_gateway_url.as_ref()).and_then(|url| {
        normalize_gateway_url(&url)
            .ok()
            .or_else(|| Some(url.trim_end_matches('/').to_string()))
    });
    let (default_mode, setup_hint) = if relay.is_some() {
        (
            "relay".to_string(),
            "Paired relay ready. Chat will reuse the saved relay session.".to_string(),
        )
    } else if gateway.is_some() {
        (
            "gateway".to_string(),
            "Direct gateway configured. Chat will use the Hermes WebAPI stream.".to_string(),
        )
    } else {
        (
            "setup".to_string(),
            "Pair a relay or enter a Hermes gateway/API URL to start chatting.".to_string(),
        )
    };
    ChatSetupState {
        default_mode,
        can_use_relay: relay.is_some(),
        can_use_gateway: gateway.is_some(),
        relay_url: relay,
        gateway_url: gateway,
        setup_hint,
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ChatLaunchPlan {
    mode: String,
    route: String,
    args: Vec<String>,
}

fn chat_launch_plan(
    config: &DesktopControlConfig,
    sessions: &[SessionSummary],
    requested_mode: &str,
    prompt: &str,
    gateway_url: Option<String>,
    session_id: Option<String>,
    fresh: bool,
) -> Result<ChatLaunchPlan, String> {
    let prompt = prompt.trim();
    if prompt.is_empty() {
        return Err("prompt is required".to_string());
    }

    let mode = if requested_mode == "gateway" {
        "gateway"
    } else if requested_mode == "relay" {
        "relay"
    } else if selected_url(config, sessions).is_some() {
        "relay"
    } else {
        "gateway"
    };

    let clean_session_id = session_id
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToString::to_string);

    if mode == "relay" {
        let route = selected_or_remote(None, config, sessions)?;
        let mut args = vec![
            "chat".to_string(),
            "--json".to_string(),
            "--non-interactive".to_string(),
            "--quiet".to_string(),
            "--no-tools".to_string(),
            "--remote".to_string(),
            route.clone(),
        ];
        if fresh {
            args.push("--new".to_string());
        }
        if let Some(session_id) = clean_session_id {
            args.push("--conversation".to_string());
            args.push(session_id);
        }
        args.push(prompt.to_string());
        return Ok(ChatLaunchPlan {
            mode: "relay".to_string(),
            route,
            args,
        });
    }

    let raw_gateway = gateway_url
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToString::to_string)
        .or_else(|| trim_nonempty(config.chat_gateway_url.as_ref()))
        .ok_or_else(|| "gateway URL is required".to_string())?;
    let route = normalize_gateway_url(&raw_gateway)?;
    let mut args = vec![
        "chat-worker".to_string(),
        "api".to_string(),
        "--gateway-url".to_string(),
        route.clone(),
    ];
    if fresh {
        args.push("--new".to_string());
    }
    if let Some(session_id) = clean_session_id {
        args.push("--session".to_string());
        args.push(session_id);
    }
    args.push(prompt.to_string());
    Ok(ChatLaunchPlan {
        mode: "gateway".to_string(),
        route,
        args,
    })
}

fn parse_pair_stdout_value(stdout: &str, label: &str) -> Option<String> {
    let prefix = format!("{label}:");
    stdout.lines().find_map(|line| {
        let trimmed = line.trim();
        trimmed
            .strip_prefix(&prefix)
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(ToString::to_string)
    })
}

fn redact_command_path(path: &Path) -> String {
    path.to_string_lossy().to_string()
}

fn cli_binary_name() -> &'static str {
    if cfg!(windows) {
        "hermes-relay.exe"
    } else {
        "hermes-relay"
    }
}

fn cli_display_name() -> &'static str {
    "hermes-relay"
}

fn standard_install_cli_path() -> Option<PathBuf> {
    let path = hermes_dir().ok()?.join("bin").join(cli_binary_name());
    path.metadata()
        .map(|m| m.len() > 0)
        .unwrap_or(false)
        .then_some(path)
}

fn sidecar_file_name() -> &'static str {
    if cfg!(all(target_os = "windows", target_arch = "x86_64")) {
        "hermes-relay-x86_64-pc-windows-msvc.exe"
    } else if cfg!(all(target_os = "linux", target_arch = "x86_64")) {
        "hermes-relay-x86_64-unknown-linux-gnu"
    } else if cfg!(all(target_os = "macos", target_arch = "x86_64")) {
        "hermes-relay-x86_64-apple-darwin"
    } else if cfg!(all(target_os = "macos", target_arch = "aarch64")) {
        "hermes-relay-aarch64-apple-darwin"
    } else {
        cli_binary_name()
    }
}

fn sidecar_candidates(app: Option<&AppHandle>) -> Vec<PathBuf> {
    let mut bases = Vec::new();
    if let Some(app) = app {
        if let Ok(resource_dir) = app.path().resource_dir() {
            bases.push(resource_dir);
        }
    }
    if let Ok(exe) = env::current_exe() {
        if let Some(dir) = exe.parent() {
            bases.push(dir.to_path_buf());
            bases.push(dir.join("resources"));
        }
    }

    let mut out = Vec::new();
    for base in bases {
        for name in [sidecar_file_name(), cli_binary_name()] {
            out.push(base.join(name));
            out.push(base.join("bin").join(name));
        }
    }
    out
}

fn resolve_terminal_cli() -> CliInvocation {
    if let Ok(path) = env::var("HERMES_RELAY_CLI_PATH") {
        return CliInvocation {
            program: PathBuf::from(path),
            prefix_args: Vec::new(),
            mode: "env".to_string(),
        };
    }

    if let Some(path) = standard_install_cli_path() {
        return CliInvocation {
            program: path,
            prefix_args: Vec::new(),
            mode: "install_dir".to_string(),
        };
    }

    CliInvocation {
        program: PathBuf::from(cli_binary_name()),
        prefix_args: Vec::new(),
        mode: "path".to_string(),
    }
}

fn resolve_cli(app: Option<&AppHandle>) -> CliInvocation {
    if let Ok(path) = env::var("HERMES_RELAY_CLI_PATH") {
        let path = PathBuf::from(path);
        return CliInvocation {
            program: path,
            prefix_args: Vec::new(),
            mode: "env".to_string(),
        };
    }

    for local in sidecar_candidates(app) {
        if local.metadata().map(|m| m.len() > 0).unwrap_or(false) {
            return CliInvocation {
                program: local,
                prefix_args: Vec::new(),
                mode: "sidecar".to_string(),
            };
        }
    }

    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    if let Some(desktop_dir) = manifest_dir.parent().and_then(|p| p.parent()) {
        let dist_cli = desktop_dir.join("dist").join("cli.js");
        if dist_cli.exists() {
            return CliInvocation {
                program: PathBuf::from("node"),
                prefix_args: vec![dist_cli.to_string_lossy().to_string()],
                mode: "local_dist".to_string(),
            };
        }
    }

    CliInvocation {
        program: PathBuf::from(cli_binary_name()),
        prefix_args: Vec::new(),
        mode: "path".to_string(),
    }
}

fn command_for_cli(cli: &CliInvocation) -> Command {
    let mut cmd = Command::new(&cli.program);
    cmd.args(&cli.prefix_args);
    cmd
}

fn cli_status(app: Option<&AppHandle>) -> CliStatus {
    let cli = resolve_cli(app);
    CliStatus {
        available: cli.mode != "path" || command_available(&cli.program),
        command: redact_command_path(&cli.program),
        mode: cli.mode,
    }
}

fn terminal_cli_status() -> CliStatus {
    let cli = resolve_terminal_cli();
    let available = match cli.mode.as_str() {
        "install_dir" => true,
        _ => command_available(&cli.program),
    };
    let command = match cli.mode.as_str() {
        "install_dir" | "path" => cli_display_name().to_string(),
        _ => redact_command_path(&cli.program),
    };
    CliStatus {
        available,
        command,
        mode: cli.mode,
    }
}

fn command_available(program: &Path) -> bool {
    let mut cmd = Command::new(program);
    cmd.arg("--version");
    cmd.stdout(Stdio::null());
    cmd.stderr(Stdio::null());
    cmd.status().map(|s| s.success()).unwrap_or(false)
}

fn shell_command_success(program: &str, args: &[&str]) -> bool {
    #[cfg(windows)]
    {
        let mut cmd = Command::new("cmd");
        cmd.arg("/C").arg(command_line_from_parts(program, args));
        cmd.stdout(Stdio::null()).stderr(Stdio::null());
        return cmd.status().map(|s| s.success()).unwrap_or(false);
    }
    #[cfg(not(windows))]
    {
        let mut cmd = Command::new(program);
        cmd.args(args);
        cmd.stdout(Stdio::null()).stderr(Stdio::null());
        return cmd.status().map(|s| s.success()).unwrap_or(false);
    }
}

fn shell_command_output(program: &str, args: &[&str]) -> Option<String> {
    #[cfg(windows)]
    let output = Command::new("cmd")
        .arg("/C")
        .arg(command_line_from_parts(program, args))
        .output()
        .ok()?;
    #[cfg(not(windows))]
    let output = Command::new(program).args(args).output().ok()?;

    if !output.status.success() {
        return None;
    }
    let text = format!(
        "{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
    text.lines()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .map(ToString::to_string)
}

fn terminal_arg_quote(value: &str) -> String {
    if !value.is_empty()
        && value
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "-_./:\\=@".contains(c))
    {
        return value.to_string();
    }
    format!("\"{}\"", value.replace('"', "\\\""))
}

fn command_line_from_parts(program: &str, args: &[&str]) -> String {
    std::iter::once(program.to_string())
        .chain(args.iter().map(|arg| terminal_arg_quote(arg)))
        .collect::<Vec<_>>()
        .join(" ")
}

fn plugin_capability(id: &str, label: &str, description: &str) -> PluginCapability {
    PluginCapability {
        id: id.to_string(),
        label: label.to_string(),
        description: description.to_string(),
    }
}

fn builtin_plugins() -> Vec<PluginDescriptor> {
    vec![PluginDescriptor {
        id: "herm".to_string(),
        name: "Herm".to_string(),
        description: "OpenTUI dashboard for Hermes Agent, packaged as herm-tui.".to_string(),
        source_url: "https://github.com/liftaris/herm".to_string(),
        package_name: "herm-tui".to_string(),
        binary_name: "herm".to_string(),
        tabs: [
            "chat",
            "sessions",
            "context",
            "agents",
            "analytics",
            "skills",
            "cron",
            "toolsets",
            "config",
            "env",
            "memory",
            "kanban",
        ]
        .iter()
        .map(|value| value.to_string())
        .collect(),
        commands: vec![
            plugin_capability(
                "install",
                "Install",
                "Install herm-tui globally with Bun when available, otherwise npm.",
            ),
            plugin_capability(
                "update",
                "Update",
                "Re-run the package manager install to refresh herm-tui.",
            ),
            plugin_capability("launch", "Launch", "Open a fresh Herm dashboard session."),
            plugin_capability("resume", "Resume", "Open Herm with -c."),
        ],
        keybindings: vec![
            plugin_capability("palette", "Ctrl+K", "Open the Herm command palette."),
            plugin_capability("keys", "/keys", "Show or edit Herm keybindings."),
        ],
        status_cards: vec![
            plugin_capability(
                "install",
                "Install state",
                "Reports whether the herm binary is on PATH.",
            ),
            plugin_capability(
                "runtime",
                "Runtime",
                "Reports Bun/npm fallback availability.",
            ),
            plugin_capability(
                "source",
                "Source",
                "Links the built-in plugin to liftaris/herm.",
            ),
        ],
        session_actions: vec![
            plugin_capability("fresh", "Fresh session", "Run herm without resume flags."),
            plugin_capability("resume", "Resume last", "Run herm -c."),
        ],
    }]
}

fn plugin_plan(program: &str, args: &[&str], mode: &str) -> PluginCommandPlan {
    PluginCommandPlan {
        program: program.to_string(),
        args: args.iter().map(|arg| arg.to_string()).collect(),
        display: command_line_from_parts(program, args),
        mode: mode.to_string(),
    }
}

fn plugin_install_plan(plugin: &PluginDescriptor) -> Option<PluginCommandPlan> {
    if shell_command_success("bun", &["--version"]) {
        return Some(plugin_plan("bun", &["add", "-g", &plugin.package_name], "bun"));
    }
    if shell_command_success("npm", &["--version"]) {
        return Some(plugin_plan(
            "npm",
            &["install", "-g", &plugin.package_name],
            "npm",
        ));
    }
    None
}

fn plugin_fallback_plan(plugin: &PluginDescriptor, resume: bool) -> Option<PluginCommandPlan> {
    let mut bunx_args = vec![plugin.package_name.as_str()];
    if resume {
        bunx_args.push("-c");
    }
    if shell_command_success("bunx", &["--version"]) {
        return Some(plugin_plan("bunx", &bunx_args, "bunx"));
    }

    let mut npx_args = vec!["--yes", plugin.package_name.as_str()];
    if resume {
        npx_args.push("-c");
    }
    if shell_command_success("npx", &["--version"]) {
        return Some(plugin_plan("npx", &npx_args, "npx"));
    }
    None
}

fn plugin_launch_plan(plugin: &PluginDescriptor, resume: bool) -> Option<PluginCommandPlan> {
    let mut args = Vec::new();
    if resume {
        args.push("-c");
    }
    if shell_command_success(&plugin.binary_name, &["--version"]) {
        return Some(plugin_plan(&plugin.binary_name, &args, "installed"));
    }
    plugin_fallback_plan(plugin, resume)
}

fn plugin_status(plugin: &PluginDescriptor) -> PluginStatus {
    let installed = shell_command_success(&plugin.binary_name, &["--version"]);
    let installer = plugin_install_plan(plugin);
    let launch = plugin_launch_plan(plugin, false);
    let resume = plugin_launch_plan(plugin, true);
    let fallback = plugin_fallback_plan(plugin, false);
    let version = if installed {
        shell_command_output(&plugin.binary_name, &["--version"])
    } else {
        None
    };
    let setup_hint = if installed {
        format!("{} is available on PATH.", plugin.binary_name)
    } else if let Some(plan) = installer.as_ref() {
        format!("Install with {}.", plan.display)
    } else if let Some(plan) = fallback.as_ref() {
        format!("Use fallback launch with {}.", plan.display)
    } else {
        "Install Bun or npm, then install herm-tui.".to_string()
    };

    PluginStatus {
        descriptor: plugin.clone(),
        installed,
        available: launch.is_some(),
        version,
        command: plugin.binary_name.clone(),
        installer: installer.clone(),
        update: installer,
        fallback,
        launch,
        resume,
        setup_hint,
    }
}

fn plugin_statuses() -> Vec<PluginStatus> {
    builtin_plugins()
        .iter()
        .map(plugin_status)
        .collect::<Vec<_>>()
}

fn builtin_plugin(id: &str) -> Option<PluginDescriptor> {
    builtin_plugins().into_iter().find(|plugin| plugin.id == id)
}

fn plugin_action_plan(
    plugin: &PluginDescriptor,
    action: &str,
    resume: bool,
) -> Option<PluginCommandPlan> {
    match action {
        "install" | "update" => plugin_install_plan(plugin),
        "launch" => plugin_launch_plan(plugin, resume),
        _ => None,
    }
}

fn command_from_plugin_plan(plan: &PluginCommandPlan) -> Command {
    #[cfg(windows)]
    {
        let mut cmd = Command::new("cmd");
        cmd.arg("/C").arg(&plan.display);
        return cmd;
    }
    #[cfg(not(windows))]
    {
        let mut cmd = Command::new(&plan.program);
        cmd.args(&plan.args);
        return cmd;
    }
}

fn command_builder_from_plugin_plan(plan: &PluginCommandPlan) -> CommandBuilder {
    #[cfg(windows)]
    {
        let mut cmd = CommandBuilder::new("cmd");
        cmd.arg("/C");
        cmd.arg(&plan.display);
        return cmd;
    }
    #[cfg(not(windows))]
    {
        let mut cmd = CommandBuilder::new(&plan.program);
        cmd.args(plan.args.iter());
        return cmd;
    }
}

#[cfg(not(windows))]
fn shell_single_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "'\\''"))
}

fn terminal_path_prefix(dir: &Path) -> String {
    #[cfg(windows)]
    {
        return format!("set \"PATH={};%PATH%\" && ", dir.to_string_lossy());
    }
    #[cfg(not(windows))]
    {
        return format!("PATH={}:$PATH ", shell_single_quote(&dir.to_string_lossy()));
    }
}

fn terminal_launch_command(
    remote_override: Option<&str>,
    experimental: bool,
    session_name: Option<&str>,
    fresh: bool,
) -> Result<String, String> {
    let cli = resolve_terminal_cli();
    let mut command = String::new();
    if cli.mode == "install_dir" {
        if let Some(dir) = cli.program.parent() {
            command.push_str(&terminal_path_prefix(dir));
        }
    }

    let program = if cli.mode == "env" {
        terminal_arg_quote(&cli.program.to_string_lossy())
    } else {
        cli_display_name().to_string()
    };
    command.push_str(&program);
    if let Some(remote) = remote_override.filter(|value| !value.trim().is_empty()) {
        command.push_str(" --remote ");
        command.push_str(&terminal_arg_quote(remote.trim()));
    }
    if fresh {
        command.push_str(" --new");
    }
    if let Some(name) = session_name.filter(|value| !value.trim().is_empty()) {
        command.push_str(" --session ");
        command.push_str(&terminal_arg_quote(name.trim()));
    }
    if experimental {
        command.push_str(" --experimental-computer-use");
    }
    Ok(command)
}

fn single_quote(value: &str) -> String {
    format!("'{}'", value.replace('\'', "''"))
}

#[cfg(target_os = "macos")]
fn applescript_quote(value: &str) -> String {
    format!("\"{}\"", value.replace('\\', "\\\\").replace('"', "\\\""))
}

#[cfg(windows)]
fn launch_terminal(command_line: &str) -> Result<(), String> {
    let ps_command = format!(
        "Start-Process -FilePath 'cmd.exe' -ArgumentList @('/K', {}) -WindowStyle Normal",
        single_quote(command_line)
    );
    Command::new("powershell")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            &ps_command,
        ])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("failed to open terminal: {e}"))?;
    Ok(())
}

#[cfg(target_os = "macos")]
fn launch_terminal(command_line: &str) -> Result<(), String> {
    let script = format!(
        "tell application \"Terminal\" to do script {}\ntell application \"Terminal\" to activate",
        applescript_quote(command_line)
    );
    Command::new("osascript")
        .arg("-e")
        .arg(script)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("failed to open Terminal: {e}"))?;
    Ok(())
}

#[cfg(all(unix, not(target_os = "macos")))]
fn launch_terminal(command_line: &str) -> Result<(), String> {
    for candidate in ["x-terminal-emulator", "gnome-terminal", "konsole"] {
        let mut cmd = Command::new(candidate);
        if candidate == "gnome-terminal" {
            cmd.args(["--", "sh", "-lc", command_line]);
        } else if candidate == "konsole" {
            cmd.args(["-e", "sh", "-lc", command_line]);
        } else {
            cmd.args(["-e", "sh", "-lc", command_line]);
        }
        cmd.stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());
        if cmd.spawn().is_ok() {
            return Ok(());
        }
    }
    Err("failed to find a terminal emulator on PATH".to_string())
}

fn push_log(state: &AppState, level: &str, event: &str, message: impl Into<String>) {
    let mut log = state.task_log.lock().expect("task log poisoned");
    log.push(TaskLogEntry {
        ts_ms: now_ms(),
        level: level.to_string(),
        event: event.to_string(),
        message: message.into(),
    });
    if log.len() > LOG_LIMIT {
        let excess = log.len() - LOG_LIMIT;
        log.drain(0..excess);
    }
}

fn command_output(mut cmd: Command) -> Result<CommandRunResult, String> {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000);
    }
    let out = cmd
        .output()
        .map_err(|e| format!("failed to run command: {e}"))?;
    Ok(CommandRunResult {
        ok: out.status.success(),
        code: out.status.code(),
        stdout: String::from_utf8_lossy(&out.stdout).to_string(),
        stderr: String::from_utf8_lossy(&out.stderr).to_string(),
    })
}

fn daemon_status_inner(state: &AppState) -> DaemonStatus {
    let paused = state.paused.load(Ordering::SeqCst);
    let mut guard = state.daemon.lock().expect("daemon mutex poisoned");
    if let Some(proc) = guard.as_mut() {
        match proc.child.try_wait() {
            Ok(Some(status)) => {
                let remote = proc.remote.clone();
                push_log(
                    state,
                    if status.success() { "info" } else { "warn" },
                    "daemon_exit",
                    format!("daemon for {remote} exited with {status}"),
                );
                *guard = None;
            }
            Ok(None) => {
                return DaemonStatus {
                    running: true,
                    paused,
                    pid: Some(proc.child.id()),
                    remote: Some(proc.remote.clone()),
                    started_at_ms: Some(proc.started_at_ms),
                };
            }
            Err(err) => {
                push_log(
                    state,
                    "warn",
                    "daemon_status",
                    format!("daemon poll failed: {err}"),
                );
                *guard = None;
            }
        }
    }
    DaemonStatus {
        running: false,
        paused,
        pid: None,
        remote: None,
        started_at_ms: None,
    }
}

fn spawn_reader<R: std::io::Read + Send + 'static>(
    reader: R,
    app: AppHandle,
    level: &'static str,
    event: &'static str,
) {
    std::thread::spawn(move || {
        let buf = BufReader::new(reader);
        for line in buf.lines().map_while(Result::ok) {
            let state = app.state::<AppState>();
            push_log(&state, level, event, line);
            let _ = app.emit("dashboard://refresh", ());
        }
    });
}

fn chat_turn_status(proc: &ChatTurnProcess) -> ChatTurnStatus {
    ChatTurnStatus {
        running: true,
        id: proc.id,
        pid: Some(proc.child.id()),
        mode: proc.mode.clone(),
        route: proc.route.clone(),
        started_at_ms: proc.started_at_ms,
    }
}

fn spawn_chat_line_reader<R: std::io::Read + Send + 'static>(
    reader: R,
    app: AppHandle,
    id: u64,
    stream: &'static str,
) {
    thread::spawn(move || {
        let buf = BufReader::new(reader);
        for line in buf.lines().map_while(Result::ok) {
            if stream == "stderr" {
                let state = app.state::<AppState>();
                push_log(&state, "info", "chat_stderr", line.clone());
            }
            let _ = app.emit(
                "chat://line",
                ChatStreamLine {
                    id,
                    stream: stream.to_string(),
                    line,
                },
            );
        }
    });
}

fn spawn_chat_exit_watcher(app: AppHandle, id: u64) {
    thread::spawn(move || loop {
        thread::sleep(Duration::from_millis(200));
        let outcome = {
            let state = app.state::<AppState>();
            let mut guard = state.chat_turn.lock().expect("chat mutex poisoned");
            let Some(proc) = guard.as_mut() else {
                return;
            };
            if proc.id != id {
                return;
            }
            match proc.child.try_wait() {
                Ok(Some(status)) => {
                    let code = status.code();
                    let success = status.success();
                    *guard = None;
                    Some(ChatStreamExit {
                        id,
                        code,
                        success,
                        message: if success {
                            "chat turn completed".to_string()
                        } else {
                            format!("chat turn exited with {status}")
                        },
                    })
                }
                Ok(None) => None,
                Err(err) => {
                    *guard = None;
                    Some(ChatStreamExit {
                        id,
                        code: None,
                        success: false,
                        message: format!("chat turn status failed: {err}"),
                    })
                }
            }
        };
        if let Some(exit) = outcome {
            let state = app.state::<AppState>();
            push_log(
                &state,
                if exit.success { "info" } else { "warn" },
                "chat_exit",
                exit.message.clone(),
            );
            let _ = app.emit("chat://exit", exit);
            let _ = app.emit("dashboard://refresh", ());
            return;
        }
    });
}

fn start_chat_turn_app(
    app: &AppHandle,
    state: &AppState,
    mode: String,
    prompt: String,
    gateway_url: Option<String>,
    api_key: Option<String>,
    session_id: Option<String>,
    fresh: bool,
) -> Result<ChatTurnStatus, String> {
    {
        let mut guard = state.chat_turn.lock().expect("chat mutex poisoned");
        if let Some(proc) = guard.as_mut() {
            if proc.child.try_wait().map_err(|e| format!("chat status failed: {e}"))?.is_none() {
                return Err("a chat turn is already running".to_string());
            }
            *guard = None;
        }
    }

    let config = load_config();
    let sessions = load_sessions();
    let plan = chat_launch_plan(
        &config,
        &sessions,
        &mode,
        &prompt,
        gateway_url.clone(),
        session_id,
        fresh,
    )?;

    if plan.mode == "gateway" {
        let mut updated = config.clone();
        updated.chat_gateway_url = Some(plan.route.clone());
        save_config_file(&updated)?;
    }

    let cli = resolve_cli(Some(app));
    let mut cmd = command_for_cli(&cli);
    cmd.args(&plan.args);
    if plan.mode == "gateway" {
        if let Some(key) = api_key.as_deref().map(str::trim).filter(|value| !value.is_empty()) {
            cmd.env("HERMES_RELAY_GATEWAY_API_KEY", key);
        }
    }
    cmd.stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .stdin(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000);
    }

    let mut child = cmd
        .spawn()
        .map_err(|e| format!("failed to start chat turn: {e}"))?;
    let id = state.chat_turn_next_id.fetch_add(1, Ordering::SeqCst);
    if let Some(stdout) = child.stdout.take() {
        spawn_chat_line_reader(stdout, app.clone(), id, "stdout");
    }
    if let Some(stderr) = child.stderr.take() {
        spawn_chat_line_reader(stderr, app.clone(), id, "stderr");
    }
    let status = ChatTurnStatus {
        running: true,
        id,
        pid: Some(child.id()),
        mode: plan.mode.clone(),
        route: plan.route.clone(),
        started_at_ms: now_ms(),
    };
    {
        let mut guard = state.chat_turn.lock().expect("chat mutex poisoned");
        *guard = Some(ChatTurnProcess {
            id,
            child,
            mode: plan.mode.clone(),
            route: plan.route.clone(),
            started_at_ms: status.started_at_ms,
        });
    }
    push_log(
        state,
        "info",
        "chat_start",
        format!("started {} chat via {}", plan.mode, plan.route),
    );
    spawn_chat_exit_watcher(app.clone(), id);
    let _ = app.emit("dashboard://refresh", ());
    Ok(status)
}

fn stop_chat_turn_app(app: &AppHandle, state: &AppState) -> Result<Option<ChatTurnStatus>, String> {
    let stopped = {
        let mut guard = state.chat_turn.lock().expect("chat mutex poisoned");
        guard.take()
    };
    let Some(mut proc) = stopped else {
        return Ok(None);
    };
    let status = chat_turn_status(&proc);
    let _ = proc.child.kill();
    let _ = proc.child.wait();
    push_log(
        state,
        "warn",
        "chat_stop",
        format!("stopped {} chat via {}", proc.mode, proc.route),
    );
    let _ = app.emit(
        "chat://exit",
        ChatStreamExit {
            id: proc.id,
            code: None,
            success: false,
            message: "chat turn stopped".to_string(),
        },
    );
    let _ = app.emit("dashboard://refresh", ());
    Ok(Some(status))
}

fn start_daemon_app(
    app: &AppHandle,
    remote: String,
    experimental: bool,
) -> Result<DaemonStatus, String> {
    let state = app.state::<AppState>();
    let existing = daemon_status_inner(&state);
    if existing.running {
        return Ok(existing);
    }

    let cli = resolve_cli(Some(app));
    let mut cmd = command_for_cli(&cli);
    cmd.arg("daemon")
        .arg("--remote")
        .arg(&remote)
        .arg("--log-human");
    if experimental {
        cmd.arg("--experimental-computer-use");
    }
    if let Ok(dir) = grant_bridge_dir() {
        let _ = fs::create_dir_all(&dir);
        cmd.env("HERMES_RELAY_GRANT_BRIDGE_DIR", dir);
    }
    cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000);
    }

    let mut child = cmd
        .spawn()
        .map_err(|e| format!("failed to start daemon: {e}"))?;

    if let Some(stdout) = child.stdout.take() {
        spawn_reader(stdout, app.clone(), "info", "daemon_stdout");
    }
    if let Some(stderr) = child.stderr.take() {
        spawn_reader(stderr, app.clone(), "info", "daemon_stderr");
    }

    let pid = child.id();
    let started_at_ms = now_ms();
    {
        let mut guard = state.daemon.lock().expect("daemon mutex poisoned");
        *guard = Some(DaemonProcess {
            child,
            remote: remote.clone(),
            started_at_ms,
        });
    }
    state.paused.store(false, Ordering::SeqCst);
    push_log(
        &state,
        "info",
        "daemon_start",
        format!("started daemon pid={pid} remote={remote}"),
    );
    show_overlay(app, load_config().overlay.visible);
    let _ = app.emit("dashboard://refresh", ());
    Ok(DaemonStatus {
        running: true,
        paused: false,
        pid: Some(pid),
        remote: Some(remote),
        started_at_ms: Some(started_at_ms),
    })
}

fn stop_daemon_app(
    app: &AppHandle,
    reason: &str,
    mark_paused: bool,
) -> Result<DaemonStatus, String> {
    let state = app.state::<AppState>();
    let stopped = {
        let mut guard = state.daemon.lock().expect("daemon mutex poisoned");
        guard.take()
    };
    if let Some(mut proc) = stopped {
        let pid = proc.child.id();
        let remote = proc.remote.clone();
        let _ = proc.child.kill();
        let _ = proc.child.wait();
        push_log(
            &state,
            "warn",
            reason,
            format!("stopped daemon pid={pid} remote={remote}"),
        );
    } else {
        push_log(&state, "info", reason, "no daemon was running");
    }
    state.paused.store(mark_paused, Ordering::SeqCst);
    let status = daemon_status_inner(&state);
    show_overlay(app, load_config().overlay.visible);
    let _ = app.emit("dashboard://refresh", ());
    Ok(status)
}

fn terminal_status_stopped() -> EmbeddedTerminalStatus {
    EmbeddedTerminalStatus {
        running: false,
        id: None,
        pid: None,
        remote: None,
        session_name: None,
        surface_id: None,
        surface_label: None,
        started_at_ms: None,
    }
}

fn pty_size(cols: u16, rows: u16) -> PtySize {
    PtySize {
        rows: rows.clamp(8, 80),
        cols: cols.clamp(40, 240),
        pixel_width: 0,
        pixel_height: 0,
    }
}

fn embedded_terminal_status_inner(state: &AppState) -> EmbeddedTerminalStatus {
    let mut guard = state
        .embedded_terminal
        .lock()
        .expect("embedded terminal mutex poisoned");
    if let Some(proc) = guard.as_mut() {
        match proc.child.try_wait() {
            Ok(Some(status)) => {
                let id = proc.id;
                push_log(
                    state,
                    if status.success() { "info" } else { "warn" },
                    "embedded_terminal_exit",
                    format!("embedded terminal {id} exited with {status}"),
                );
                *guard = None;
            }
            Ok(None) => {
                return EmbeddedTerminalStatus {
                    running: true,
                    id: Some(proc.id),
                    pid: proc.child.process_id(),
                    remote: Some(proc.remote.clone()),
                    session_name: proc.session_name.clone(),
                    surface_id: Some(proc.surface_id.clone()),
                    surface_label: Some(proc.surface_label.clone()),
                    started_at_ms: Some(proc.started_at_ms),
                };
            }
            Err(err) => {
                push_log(
                    state,
                    "warn",
                    "embedded_terminal_status",
                    format!("embedded terminal poll failed: {err}"),
                );
                *guard = None;
            }
        }
    }
    terminal_status_stopped()
}

fn spawn_embedded_terminal_reader(mut reader: Box<dyn Read + Send>, app: AppHandle, id: u64) {
    thread::spawn(move || {
        let mut buf = [0_u8; 8192];
        loop {
            match reader.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => {
                    let data = String::from_utf8_lossy(&buf[..n]).to_string();
                    let _ = app.emit("terminal://output", EmbeddedTerminalOutput { id, data });
                }
                Err(err) => {
                    let _ = app.emit(
                        "terminal://exit",
                        EmbeddedTerminalExit {
                            id,
                            message: format!("read failed: {err}"),
                        },
                    );
                    return;
                }
            }
        }
        let _ = app.emit(
            "terminal://exit",
            EmbeddedTerminalExit {
                id,
                message: "terminal stream ended".to_string(),
            },
        );
        let _ = app.emit("dashboard://refresh", ());
    });
}

fn start_embedded_terminal_app(
    app: &AppHandle,
    remote: String,
    session_name: Option<String>,
    fresh: bool,
    cols: u16,
    rows: u16,
) -> Result<EmbeddedTerminalStatus, String> {
    let state = app.state::<AppState>();
    let existing = embedded_terminal_status_inner(&state);
    if existing.running {
        return Ok(existing);
    }

    let cli = resolve_cli(Some(app));
    if cli.mode == "path" && !command_available(&cli.program) {
        return Err("hermes-relay CLI is not available for embedded terminal launch".to_string());
    }

    let pty_system = native_pty_system();
    let pair = pty_system
        .openpty(pty_size(cols, rows))
        .map_err(|e| format!("open embedded terminal pty failed: {e}"))?;
    let reader = pair
        .master
        .try_clone_reader()
        .map_err(|e| format!("open embedded terminal reader failed: {e}"))?;
    let writer = pair
        .master
        .take_writer()
        .map_err(|e| format!("open embedded terminal writer failed: {e}"))?;

    let mut cmd = CommandBuilder::new(&cli.program);
    cmd.args(cli.prefix_args.iter());
    cmd.arg("--remote");
    cmd.arg(&remote);
    if fresh {
        cmd.arg("--new");
    }
    if let Some(name) = session_name
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
    {
        cmd.arg("--session");
        cmd.arg(name);
    }
    if load_config().experimental_computer_use {
        cmd.arg("--experimental-computer-use");
    }
    cmd.env("TERM", "xterm-256color");
    cmd.env("COLORTERM", "truecolor");
    cmd.env("HERMES_RELAY_EMBEDDED_TERMINAL", "1");

    let child = pair
        .slave
        .spawn_command(cmd)
        .map_err(|e| format!("start embedded terminal failed: {e}"))?;
    drop(pair.slave);

    let id = state
        .embedded_terminal_next_id
        .fetch_add(1, Ordering::SeqCst);
    let pid = child.process_id();
    let started_at_ms = now_ms();
    let clean_session = session_name
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToString::to_string);

    {
        let mut guard = state
            .embedded_terminal
            .lock()
            .expect("embedded terminal mutex poisoned");
        *guard = Some(EmbeddedTerminalProcess {
            id,
            child,
            master: pair.master,
            writer,
            remote: remote.clone(),
            session_name: clean_session.clone(),
            surface_id: "relay-tui".to_string(),
            surface_label: "Relay TUI".to_string(),
            started_at_ms,
        });
    }
    spawn_embedded_terminal_reader(reader, app.clone(), id);
    push_log(
        &state,
        "info",
        "embedded_terminal_start",
        format!("started embedded TUI id={id} pid={pid:?} remote={remote}"),
    );
    let _ = app.emit("dashboard://refresh", ());
    Ok(EmbeddedTerminalStatus {
        running: true,
        id: Some(id),
        pid,
        remote: Some(remote),
        session_name: clean_session,
        surface_id: Some("relay-tui".to_string()),
        surface_label: Some("Relay TUI".to_string()),
        started_at_ms: Some(started_at_ms),
    })
}

fn start_plugin_embedded_terminal_app(
    app: &AppHandle,
    plugin: PluginDescriptor,
    resume: bool,
    cols: u16,
    rows: u16,
) -> Result<EmbeddedTerminalStatus, String> {
    let state = app.state::<AppState>();
    let existing = embedded_terminal_status_inner(&state);
    if existing.running {
        return Ok(existing);
    }

    let plan = plugin_action_plan(&plugin, "launch", resume)
        .ok_or_else(|| format!("{} is not installed and no fallback launcher is available", plugin.name))?;

    let pty_system = native_pty_system();
    let pair = pty_system
        .openpty(pty_size(cols, rows))
        .map_err(|e| format!("open embedded plugin terminal pty failed: {e}"))?;
    let reader = pair
        .master
        .try_clone_reader()
        .map_err(|e| format!("open embedded plugin terminal reader failed: {e}"))?;
    let writer = pair
        .master
        .take_writer()
        .map_err(|e| format!("open embedded plugin terminal writer failed: {e}"))?;

    let mut cmd = command_builder_from_plugin_plan(&plan);
    cmd.env("TERM", "xterm-256color");
    cmd.env("COLORTERM", "truecolor");
    cmd.env("HERMES_RELAY_EMBEDDED_TERMINAL", "1");
    cmd.env("HERMES_RELAY_SURFACE_PLUGIN", &plugin.id);

    let child = pair
        .slave
        .spawn_command(cmd)
        .map_err(|e| format!("start embedded plugin terminal failed: {e}"))?;
    drop(pair.slave);

    let id = state
        .embedded_terminal_next_id
        .fetch_add(1, Ordering::SeqCst);
    let pid = child.process_id();
    let started_at_ms = now_ms();
    let surface_id = format!("plugin:{}", plugin.id);
    let surface_label = plugin.name.clone();

    {
        let mut guard = state
            .embedded_terminal
            .lock()
            .expect("embedded terminal mutex poisoned");
        *guard = Some(EmbeddedTerminalProcess {
            id,
            child,
            master: pair.master,
            writer,
            remote: surface_id.clone(),
            session_name: None,
            surface_id: surface_id.clone(),
            surface_label: surface_label.clone(),
            started_at_ms,
        });
    }
    spawn_embedded_terminal_reader(reader, app.clone(), id);
    push_log(
        &state,
        "info",
        "plugin_embedded_start",
        format!(
            "started embedded plugin {} id={id} pid={pid:?} via {}",
            plugin.id, plan.display
        ),
    );
    let _ = app.emit("dashboard://refresh", ());
    Ok(EmbeddedTerminalStatus {
        running: true,
        id: Some(id),
        pid,
        remote: Some(surface_id),
        session_name: None,
        surface_id: Some(format!("plugin:{}", plugin.id)),
        surface_label: Some(surface_label),
        started_at_ms: Some(started_at_ms),
    })
}

fn stop_embedded_terminal_app(
    app: &AppHandle,
    reason: &str,
) -> Result<EmbeddedTerminalStatus, String> {
    let state = app.state::<AppState>();
    let stopped = {
        let mut guard = state
            .embedded_terminal
            .lock()
            .expect("embedded terminal mutex poisoned");
        guard.take()
    };
    if let Some(mut proc) = stopped {
        let id = proc.id;
        let pid = proc.child.process_id();
        let _ = proc.child.kill();
        let _ = app.emit(
            "terminal://exit",
            EmbeddedTerminalExit {
                id,
                message: format!("terminal stopped ({reason})"),
            },
        );
        push_log(
            &state,
            "warn",
            reason,
            format!("stopped embedded terminal id={id} pid={pid:?}"),
        );
    } else {
        push_log(&state, "info", reason, "no embedded terminal was running");
    }
    let _ = app.emit("dashboard://refresh", ());
    Ok(terminal_status_stopped())
}

fn show_main(app: &AppHandle, route: Option<&str>) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.set_focus();
        if let Some(route) = route {
            let _ = app.emit("dashboard://route", route);
        }
    }
}

fn position_overlay(window: &tauri::WebviewWindow) {
    let size = match window.outer_size() {
        Ok(size) => size,
        Err(_) => return,
    };
    let monitor = window
        .current_monitor()
        .ok()
        .flatten()
        .or_else(|| window.primary_monitor().ok().flatten());
    if let Some(monitor) = monitor {
        let area = monitor.work_area();
        let width = size.width as i32;
        let height = size.height as i32;
        let area_width = area.size.width as i32;
        let area_height = area.size.height as i32;
        let x = area.position.x + ((area_width - width) / 2).max(0);
        let y = area.position.y + area_height - height - OVERLAY_WORK_AREA_MARGIN;
        let _ = window.set_position(PhysicalPosition::new(x, y.max(area.position.y)));
    }
}

fn bring_overlay_to_front(window: &tauri::WebviewWindow) {
    harden_overlay_window(window);
    let _ = window.set_always_on_top(true);
    bring_overlay_to_front_platform(window);
}

fn keep_overlay_front_after_startup(window: tauri::WebviewWindow) {
    thread::spawn(move || {
        for delay_ms in [400_u64, 1200] {
            thread::sleep(Duration::from_millis(delay_ms));
            if window.is_visible().unwrap_or(false) {
                position_overlay(&window);
                bring_overlay_to_front(&window);
            }
        }
    });
}

#[cfg(windows)]
fn bring_overlay_to_front_platform(window: &tauri::WebviewWindow) {
    if let Ok(hwnd) = window.hwnd() {
        let _ = unsafe {
            SetWindowPos(
                hwnd,
                Some(HWND_TOPMOST),
                0,
                0,
                0,
                0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW,
            )
        };
    }
}

#[cfg(not(windows))]
fn bring_overlay_to_front_platform(_window: &tauri::WebviewWindow) {}

fn harden_overlay_window(window: &tauri::WebviewWindow) {
    let _ = window.set_decorations(false);
    let _ = window.set_shadow(false);
    let _ = window.set_focusable(false);
    let _ = window.set_skip_taskbar(true);
    let _ = window.set_ignore_cursor_events(true);
    harden_overlay_window_platform(window);
}

#[cfg(windows)]
fn harden_overlay_window_platform(window: &tauri::WebviewWindow) {
    if let Ok(hwnd) = window.hwnd() {
        unsafe {
            let style = GetWindowLongW(hwnd, GWL_STYLE) as u32;
            let chrome_bits =
                WS_CAPTION.0 | WS_THICKFRAME.0 | WS_SYSMENU.0 | WS_MINIMIZEBOX.0 | WS_MAXIMIZEBOX.0;
            let _ = SetWindowLongW(hwnd, GWL_STYLE, (style & !chrome_bits) as i32);

            let ex_style = GetWindowLongW(hwnd, GWL_EXSTYLE) as u32;
            let overlay_bits = WS_EX_TOOLWINDOW.0 | WS_EX_NOACTIVATE.0 | WS_EX_TRANSPARENT.0;
            let _ = SetWindowLongW(
                hwnd,
                GWL_EXSTYLE,
                ((ex_style | overlay_bits) & !WS_EX_APPWINDOW.0) as i32,
            );

            let _ = SetWindowPos(
                hwnd,
                Some(HWND_TOPMOST),
                0,
                0,
                0,
                0,
                SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_SHOWWINDOW | SWP_FRAMECHANGED,
            );
        }
    }
}

#[cfg(not(windows))]
fn harden_overlay_window_platform(_window: &tauri::WebviewWindow) {}

fn show_overlay(app: &AppHandle, visible: bool) {
    if let Some(window) = app.get_webview_window("overlay") {
        if visible {
            harden_overlay_window(&window);
            position_overlay(&window);
        }
        if visible {
            let _ = window.show();
            bring_overlay_to_front(&window);
            keep_overlay_front_after_startup(window.clone());
        } else {
            let _ = window.hide();
        }
    }
}

fn emergency_stop_app(app: &AppHandle) -> Result<DaemonStatus, String> {
    let status = stop_daemon_app(app, "emergency_stop", true)?;
    show_overlay(app, true);
    show_main(app, Some("log"));
    Ok(status)
}

#[tauri::command]
fn get_dashboard_state(app: AppHandle, state: State<AppState>) -> Result<DashboardState, String> {
    let config = load_config();
    let sessions = load_sessions();
    let selected = selected_url(&config, &sessions);
    let chat = chat_setup_state(&config, &sessions);
    let config_path = config_path()?.to_string_lossy().to_string();
    let session_store_path = sessions_path()?.to_string_lossy().to_string();
    let terminal_session_store_path = terminal_sessions_path()?.to_string_lossy().to_string();
    let daemon = daemon_status_inner(&state);
    let embedded_terminal = embedded_terminal_status_inner(&state);
    let task_log = state.task_log.lock().expect("task log poisoned").clone();
    Ok(DashboardState {
        config,
        config_path,
        session_store_path,
        terminal_session_store_path,
        sessions,
        selected_url: selected,
        chat,
        daemon,
        embedded_terminal,
        cli: cli_status(Some(&app)),
        terminal_cli: terminal_cli_status(),
        plugins: plugin_statuses(),
        task_log,
        pending_grants: load_pending_grants(),
    })
}

#[tauri::command]
fn save_desktop_config(
    app: AppHandle,
    state: State<AppState>,
    config: DesktopControlConfig,
) -> Result<DesktopControlConfig, String> {
    save_config_file(&config)?;
    show_overlay(&app, config.overlay.visible);
    push_log(
        &state,
        "info",
        "settings_save",
        "desktop-control.json updated",
    );
    let _ = app.emit("dashboard://refresh", ());
    Ok(config)
}

#[tauri::command]
fn start_chat_turn(
    app: AppHandle,
    state: State<AppState>,
    mode: String,
    prompt: String,
    gateway_url: Option<String>,
    api_key: Option<String>,
    session_id: Option<String>,
    fresh: bool,
) -> Result<ChatTurnStatus, String> {
    start_chat_turn_app(
        &app,
        &state,
        mode,
        prompt,
        gateway_url,
        api_key,
        session_id,
        fresh,
    )
}

#[tauri::command]
fn stop_chat_turn(app: AppHandle, state: State<AppState>) -> Result<Option<ChatTurnStatus>, String> {
    stop_chat_turn_app(&app, &state)
}

#[tauri::command]
fn start_daemon(app: AppHandle, remote: Option<String>) -> Result<DaemonStatus, String> {
    let config = load_config();
    let sessions = load_sessions();
    let url = selected_or_remote(remote, &config, &sessions)?;
    start_daemon_app(&app, url, config.experimental_computer_use)
}

#[tauri::command]
fn stop_daemon(app: AppHandle) -> Result<DaemonStatus, String> {
    stop_daemon_app(&app, "pause", true)
}

#[tauri::command]
fn emergency_stop(app: AppHandle) -> Result<DaemonStatus, String> {
    emergency_stop_app(&app)
}

#[tauri::command]
fn pair_relay(
    app: AppHandle,
    state: State<AppState>,
    remote: String,
    code: String,
    pair_qr: Option<String>,
    grant_tools: bool,
    start_after_pair: bool,
) -> Result<CommandRunResult, String> {
    let remote = remote.trim().to_string();
    let code = code.trim().to_uppercase();
    let pair_qr = pair_qr.unwrap_or_default().trim().to_string();
    if pair_qr.is_empty() && remote.is_empty() {
        return Err("relay URL is required".to_string());
    }
    if pair_qr.is_empty() && (code.len() != 6 || !code.chars().all(|c| c.is_ascii_alphanumeric())) {
        return Err("pairing code must be 6 letters or numbers".to_string());
    }

    let cli = resolve_cli(Some(&app));
    let mut cmd = command_for_cli(&cli);
    cmd.arg("pair");
    if pair_qr.is_empty() {
        cmd.arg(&code).arg("--remote").arg(&remote);
    } else {
        cmd.arg("--pair-qr").arg(&pair_qr);
    }
    cmd.arg("--non-interactive");
    if grant_tools {
        cmd.arg("--auto-grant-tools");
    }
    let result = command_output(cmd)?;
    if result.ok {
        let paired_url = parse_pair_stdout_value(&result.stdout, "Relay")
            .or_else(|| (!remote.is_empty()).then(|| remote.clone()))
            .or_else(|| {
                let sessions = load_sessions();
                (sessions.len() == 1).then(|| sessions[0].url.clone())
            });
        let mut config = load_config();
        if let Some(url) = paired_url.clone() {
            config.relay_url = Some(url.clone());
        }
        save_config_file(&config)?;
        let route = parse_pair_stdout_value(&result.stdout, "Route");
        let target = paired_url.unwrap_or_else(|| "selected relay".to_string());
        if let Some(route) = route {
            push_log(
                &state,
                "info",
                "pair",
                format!("paired {target} via {route}"),
            );
        } else {
            push_log(&state, "info", "pair", format!("paired {target}"));
        }
        if start_after_pair {
            if let Some(url) = config.relay_url.clone() {
                let _ = start_daemon_app(&app, url, config.experimental_computer_use);
            }
        }
    } else {
        push_log(
            &state,
            "warn",
            "pair",
            format!("pair failed: {}", result.stderr.trim()),
        );
    }
    let _ = app.emit("dashboard://refresh", ());
    Ok(result)
}

#[tauri::command]
fn set_desktop_tool_consent(
    app: AppHandle,
    state: State<AppState>,
    remote: Option<String>,
    consented: bool,
) -> Result<(), String> {
    let config = load_config();
    let sessions = load_sessions();
    let url = selected_or_remote(remote, &config, &sessions)?;
    set_tools_consent(&url, consented)?;
    if !consented {
        let _ = stop_daemon_app(&app, "tool_consent_revoke", true);
    }
    push_log(
        &state,
        if consented { "info" } else { "warn" },
        "tool_consent",
        if consented {
            format!("desktop tools granted for {url}")
        } else {
            format!("desktop tools revoked for {url}")
        },
    );
    let _ = app.emit("dashboard://refresh", ());
    Ok(())
}

#[tauri::command]
fn list_devices(
    app: AppHandle,
    state: State<AppState>,
    remote: Option<String>,
) -> Result<CommandRunResult, String> {
    let config = load_config();
    let sessions = load_sessions();
    let url = selected_or_remote(remote, &config, &sessions)?;
    let cli = resolve_cli(Some(&app));
    let mut cmd = command_for_cli(&cli);
    cmd.arg("devices")
        .arg("list")
        .arg("--json")
        .arg("--remote")
        .arg(&url);
    let result = command_output(cmd)?;
    push_log(
        &state,
        if result.ok { "info" } else { "warn" },
        "devices_list",
        if result.ok {
            format!("loaded devices for {url}")
        } else {
            format!("device list failed: {}", result.stderr.trim())
        },
    );
    Ok(result)
}

#[tauri::command]
fn revoke_device(
    app: AppHandle,
    state: State<AppState>,
    remote: Option<String>,
    prefix: String,
) -> Result<CommandRunResult, String> {
    let config = load_config();
    let sessions = load_sessions();
    let url = selected_or_remote(remote, &config, &sessions)?;
    let cli = resolve_cli(Some(&app));
    let mut cmd = command_for_cli(&cli);
    cmd.arg("devices")
        .arg("revoke")
        .arg(prefix.trim())
        .arg("--remote")
        .arg(&url);
    let result = command_output(cmd)?;
    if result.ok {
        let _ = stop_daemon_app(&app, "revoke", true);
    }
    push_log(
        &state,
        if result.ok { "warn" } else { "error" },
        "device_revoke",
        if result.ok {
            format!("revoked device {}", prefix.trim())
        } else {
            format!("revoke failed: {}", result.stderr.trim())
        },
    );
    let _ = app.emit("dashboard://refresh", ());
    Ok(result)
}

#[tauri::command]
fn clear_task_log(app: AppHandle, state: State<AppState>) -> Result<(), String> {
    state.task_log.lock().expect("task log poisoned").clear();
    let _ = app.emit("dashboard://refresh", ());
    Ok(())
}

/// Read `~/.hermes/desktop-voice.json` and return the voice URL, if any.
///
/// The discovery file is written by `hermes-relay daemon` (or `voice mode`)
/// when a local voice server is running. Returns `Ok(None)` for the missing /
/// stale / malformed cases so the tray UI can show its "daemon not running"
/// hint instead of an error toast — the file genuinely going missing when the
/// daemon is paused is the common case, not an error.
#[tauri::command]
fn get_voice_url() -> Result<Option<String>, String> {
    let path = match voice_discovery_path() {
        Ok(p) => p,
        Err(_) => return Ok(None),
    };
    let raw = match fs::read_to_string(&path) {
        Ok(s) => s,
        Err(_) => return Ok(None),
    };
    let parsed: serde_json::Value = match serde_json::from_str(&raw) {
        Ok(v) => v,
        Err(_) => return Ok(None),
    };
    let url = parsed.get("url").and_then(|v| v.as_str()).map(|s| s.to_string());
    Ok(url.filter(|u| u.starts_with("http://127.0.0.1") || u.starts_with("https://127.0.0.1")))
}

#[tauri::command]
fn run_doctor(app: AppHandle, state: State<AppState>) -> Result<CommandRunResult, String> {
    let cli = resolve_cli(Some(&app));
    let mut cmd = command_for_cli(&cli);
    cmd.arg("doctor").arg("--json");
    let result = command_output(cmd)?;
    push_log(
        &state,
        if result.ok { "info" } else { "warn" },
        "doctor",
        if result.ok {
            "doctor completed".to_string()
        } else {
            format!("doctor failed: {}", result.stderr.trim())
        },
    );
    let _ = app.emit("dashboard://refresh", ());
    Ok(result)
}

#[tauri::command]
fn list_terminal_sessions(
    app: AppHandle,
    state: State<AppState>,
    remote: Option<String>,
) -> Result<CommandRunResult, String> {
    let config = load_config();
    let sessions = load_sessions();
    let url = selected_or_remote(remote, &config, &sessions)?;
    let cli = resolve_cli(Some(&app));
    let mut cmd = command_for_cli(&cli);
    cmd.arg("sessions")
        .arg("list")
        .arg("--json")
        .arg("--non-interactive")
        .arg("--remote")
        .arg(&url);
    let result = command_output(cmd)?;
    push_log(
        &state,
        if result.ok { "info" } else { "warn" },
        "sessions_list",
        if result.ok {
            format!("loaded TUI sessions for {url}")
        } else {
            format!("TUI session list failed: {}", result.stderr.trim())
        },
    );
    Ok(result)
}

#[tauri::command]
fn open_tui_terminal(
    app: AppHandle,
    state: State<AppState>,
    remote: Option<String>,
) -> Result<(), String> {
    let terminal_cli = terminal_cli_status();
    if !terminal_cli.available {
        return Err(
            "CLI shim is not installed yet. Install the hermes-relay CLI shim, then open the TUI."
                .to_string(),
        );
    }

    let config = load_config();
    let sessions = load_sessions();
    let remote_override = remote
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToString::to_string);
    if remote_override.is_none() && selected_url(&config, &sessions).is_none() {
        return Err("Pair a relay first, or pass a --remote override from the CLI.".to_string());
    }

    let command_line = terminal_launch_command(
        remote_override.as_deref(),
        config.experimental_computer_use,
        None,
        false,
    )?;
    launch_terminal(&command_line)?;
    push_log(
        &state,
        "info",
        "tui_open",
        match remote_override {
            Some(url) => format!("opened terminal TUI with --remote override {url}"),
            None => "opened terminal TUI using saved active relay".to_string(),
        },
    );
    let _ = app.emit("dashboard://refresh", ());
    Ok(())
}

#[tauri::command]
fn open_tui_session(
    app: AppHandle,
    state: State<AppState>,
    remote: Option<String>,
    session_name: Option<String>,
    fresh: bool,
) -> Result<(), String> {
    let terminal_cli = terminal_cli_status();
    if !terminal_cli.available {
        return Err(
            "CLI shim is not installed yet. Install the hermes-relay CLI shim, then open the TUI."
                .to_string(),
        );
    }

    let config = load_config();
    let sessions = load_sessions();
    let remote_override = remote
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(ToString::to_string);
    if remote_override.is_none() && selected_url(&config, &sessions).is_none() {
        return Err("Pair a relay first, or pass a --remote override from the CLI.".to_string());
    }
    let session_name = session_name
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty());

    let command_line = terminal_launch_command(
        remote_override.as_deref(),
        config.experimental_computer_use,
        session_name,
        fresh,
    )?;
    launch_terminal(&command_line)?;
    push_log(
        &state,
        "info",
        if fresh { "tui_new" } else { "tui_resume" },
        match session_name {
            Some(name) => format!("opened terminal TUI session {name}"),
            None if fresh => "opened new terminal TUI session".to_string(),
            None => "opened terminal TUI using saved active relay".to_string(),
        },
    );
    let _ = app.emit("dashboard://refresh", ());
    Ok(())
}

#[tauri::command]
fn embedded_terminal_status(state: State<AppState>) -> Result<EmbeddedTerminalStatus, String> {
    Ok(embedded_terminal_status_inner(&state))
}

#[tauri::command]
fn start_embedded_terminal(
    app: AppHandle,
    remote: Option<String>,
    session_name: Option<String>,
    fresh: bool,
    cols: u16,
    rows: u16,
) -> Result<EmbeddedTerminalStatus, String> {
    let config = load_config();
    let sessions = load_sessions();
    let url = selected_or_remote(remote, &config, &sessions)?;
    start_embedded_terminal_app(&app, url, session_name, fresh, cols, rows)
}

#[tauri::command]
fn write_embedded_terminal(state: State<AppState>, data: String) -> Result<(), String> {
    let mut guard = state
        .embedded_terminal
        .lock()
        .expect("embedded terminal mutex poisoned");
    let proc = guard
        .as_mut()
        .ok_or_else(|| "no embedded terminal is running".to_string())?;
    proc.writer
        .write_all(data.as_bytes())
        .map_err(|e| format!("write embedded terminal failed: {e}"))?;
    proc.writer
        .flush()
        .map_err(|e| format!("flush embedded terminal failed: {e}"))
}

#[tauri::command]
fn resize_embedded_terminal(state: State<AppState>, cols: u16, rows: u16) -> Result<(), String> {
    let guard = state
        .embedded_terminal
        .lock()
        .expect("embedded terminal mutex poisoned");
    let proc = guard
        .as_ref()
        .ok_or_else(|| "no embedded terminal is running".to_string())?;
    proc.master
        .resize(pty_size(cols, rows))
        .map_err(|e| format!("resize embedded terminal failed: {e}"))
}

#[tauri::command]
fn stop_embedded_terminal(app: AppHandle) -> Result<EmbeddedTerminalStatus, String> {
    stop_embedded_terminal_app(&app, "embedded_terminal_stop")
}

#[tauri::command]
fn install_plugin(
    app: AppHandle,
    state: State<AppState>,
    plugin_id: String,
) -> Result<CommandRunResult, String> {
    let plugin = builtin_plugin(plugin_id.trim())
        .ok_or_else(|| format!("unknown plugin: {}", plugin_id.trim()))?;
    let plan = plugin_action_plan(&plugin, "install", false)
        .ok_or_else(|| "Install Bun or npm, then retry plugin install.".to_string())?;
    let result = command_output(command_from_plugin_plan(&plan))?;
    push_log(
        &state,
        if result.ok { "info" } else { "error" },
        "plugin_install",
        if result.ok {
            format!("installed plugin {} via {}", plugin.id, plan.display)
        } else {
            format!("plugin {} install failed via {}", plugin.id, plan.display)
        },
    );
    let _ = app.emit("dashboard://refresh", ());
    Ok(result)
}

#[tauri::command]
fn update_plugin(
    app: AppHandle,
    state: State<AppState>,
    plugin_id: String,
) -> Result<CommandRunResult, String> {
    let plugin = builtin_plugin(plugin_id.trim())
        .ok_or_else(|| format!("unknown plugin: {}", plugin_id.trim()))?;
    let plan = plugin_action_plan(&plugin, "update", false)
        .ok_or_else(|| "Install Bun or npm, then retry plugin update.".to_string())?;
    let result = command_output(command_from_plugin_plan(&plan))?;
    push_log(
        &state,
        if result.ok { "info" } else { "error" },
        "plugin_update",
        if result.ok {
            format!("updated plugin {} via {}", plugin.id, plan.display)
        } else {
            format!("plugin {} update failed via {}", plugin.id, plan.display)
        },
    );
    let _ = app.emit("dashboard://refresh", ());
    Ok(result)
}

#[tauri::command]
fn launch_plugin_terminal(
    app: AppHandle,
    state: State<AppState>,
    plugin_id: String,
    resume: bool,
) -> Result<(), String> {
    let plugin = builtin_plugin(plugin_id.trim())
        .ok_or_else(|| format!("unknown plugin: {}", plugin_id.trim()))?;
    let plan = plugin_action_plan(&plugin, "launch", resume).ok_or_else(|| {
        format!(
            "{} is not installed and no bunx/npx fallback is available.",
            plugin.name
        )
    })?;
    launch_terminal(&plan.display)?;
    push_log(
        &state,
        "info",
        "plugin_launch",
        format!("opened plugin {} via {}", plugin.id, plan.display),
    );
    let _ = app.emit("dashboard://refresh", ());
    Ok(())
}

#[tauri::command]
fn start_plugin_embedded_terminal(
    app: AppHandle,
    plugin_id: String,
    resume: bool,
    cols: u16,
    rows: u16,
) -> Result<EmbeddedTerminalStatus, String> {
    let plugin = builtin_plugin(plugin_id.trim())
        .ok_or_else(|| format!("unknown plugin: {}", plugin_id.trim()))?;
    start_plugin_embedded_terminal_app(&app, plugin, resume, cols, rows)
}

#[tauri::command]
fn kill_tui_session(
    app: AppHandle,
    state: State<AppState>,
    remote: Option<String>,
    session_name: String,
) -> Result<CommandRunResult, String> {
    let name = session_name.trim().to_string();
    if name.is_empty() {
        return Err("session name is required".to_string());
    }
    let config = load_config();
    let sessions = load_sessions();
    let url = selected_or_remote(remote, &config, &sessions)?;
    let cli = resolve_cli(Some(&app));
    let mut cmd = command_for_cli(&cli);
    cmd.arg("sessions")
        .arg("kill")
        .arg(&name)
        .arg("--non-interactive")
        .arg("--remote")
        .arg(&url);
    let result = command_output(cmd)?;
    push_log(
        &state,
        if result.ok { "warn" } else { "error" },
        "sessions_kill",
        if result.ok {
            format!("killed TUI session {name}")
        } else {
            format!("TUI session kill failed: {}", result.stderr.trim())
        },
    );
    let _ = app.emit("dashboard://refresh", ());
    Ok(result)
}

#[tauri::command]
fn resolve_grant(
    app: AppHandle,
    state: State<AppState>,
    id: String,
    approved: bool,
    reason: Option<String>,
) -> Result<(), String> {
    if !valid_bridge_id(&id) {
        return Err("invalid grant request id".to_string());
    }
    let dir = grant_bridge_dir()?;
    fs::create_dir_all(&dir).map_err(|e| format!("create grant bridge dir failed: {e}"))?;
    let response_path = dir.join(format!("response-{id}.json"));
    let tmp = response_path.with_extension("json.tmp");
    let response_reason = reason.unwrap_or_else(|| {
        if approved {
            "".to_string()
        } else {
            "rejected from Hermes Relay Desktop".to_string()
        }
    });
    let payload = serde_json::json!({
        "approved": approved,
        "reason": response_reason,
        "resolved_at_ms": now_ms() as u64
    });
    let raw = serde_json::to_string_pretty(&payload)
        .map_err(|e| format!("serialize grant response failed: {e}"))?;
    fs::write(&tmp, raw).map_err(|e| format!("write grant response failed: {e}"))?;
    fs::rename(&tmp, &response_path).map_err(|e| format!("replace grant response failed: {e}"))?;
    let _ = fs::remove_file(dir.join(format!("request-{id}.json")));
    push_log(
        &state,
        if approved { "warn" } else { "info" },
        "grant_response",
        if approved {
            format!("approved computer-use grant {id}")
        } else {
            format!("rejected computer-use grant {id}")
        },
    );
    let _ = app.emit("dashboard://refresh", ());
    Ok(())
}

/// Tiny localhost HTTP listener that lets sibling processes (the CLI in
/// particular) bring the tray window forward and switch to a named route.
///
/// Wire shape:
///
///   POST http://127.0.0.1:<port>/voice/show
///        Content-Type: application/json
///        Body: {"token": "<32-hex>"}
///        → 200 {"ok":true}  on success
///        → 401              on token mismatch
///        → 404              on unknown path
///
/// On bind, writes ~/.hermes/desktop-tray-control.json with `{port, token, pid}`
/// (mode 0600) so the CLI can discover the address. Loopback-only +
/// token-gated; not reachable off-box.
fn start_tray_ipc_server(app: AppHandle) {
    use std::net::TcpListener;

    // Bind on a free port; surface the chosen port for the control file.
    let listener = match TcpListener::bind("127.0.0.1:0") {
        Ok(l) => l,
        Err(err) => {
            eprintln!("tray IPC bind failed: {err}");
            return;
        }
    };
    let port = match listener.local_addr() {
        Ok(addr) => addr.port(),
        Err(err) => {
            eprintln!("tray IPC local_addr failed: {err}");
            return;
        }
    };

    // 32-hex char token. Reused for every request — written once to a
    // 0600 file so other local users can't read it.
    let token: String = {
        let mut bytes = [0u8; 16];
        // SystemTime + pid + millis gives us a non-secret-quality token,
        // but mixed with a process-unique entropy source is good enough
        // for "no other local user can guess this". For higher-stakes
        // pinning we'd reach for `getrandom`, but the file mode is the
        // primary gate here.
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        let pid = std::process::id() as u128;
        let seed = nanos ^ (pid.rotate_left(64));
        for (i, b) in bytes.iter_mut().enumerate() {
            *b = ((seed >> (i * 8)) & 0xff) as u8 ^ ((nanos >> (i * 4)) & 0xff) as u8;
        }
        bytes.iter().map(|b| format!("{:02x}", b)).collect()
    };

    if let Err(err) = write_tray_control_file(port, &token) {
        eprintln!("tray IPC control file write failed: {err}");
        return;
    }

    let expected_token = token.clone();
    thread::spawn(move || {
        for incoming in listener.incoming() {
            let stream = match incoming {
                Ok(s) => s,
                Err(_) => continue,
            };
            let app = app.clone();
            let token = expected_token.clone();
            thread::spawn(move || handle_tray_ipc_connection(stream, app, token));
        }
    });
}

fn write_tray_control_file(port: u16, token: &str) -> Result<(), String> {
    let path = tray_control_path()?;
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let payload = serde_json::json!({
        "port": port,
        "token": token,
        "pid": std::process::id(),
        "started_at": SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0),
    });
    let body = serde_json::to_string_pretty(&payload).map_err(|e| e.to_string())?;
    fs::write(&path, body).map_err(|e| e.to_string())?;
    // POSIX 0600 — best-effort on Windows where ACLs aren't a chmod call.
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(&path, fs::Permissions::from_mode(0o600));
    }
    Ok(())
}

fn handle_tray_ipc_connection(mut stream: std::net::TcpStream, app: AppHandle, expected_token: String) {
    // Tight bounds — this surface only ever sees small JSON bodies, and we
    // never trust the client. Drop anything that doesn't fit the shape fast.
    let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));

    let mut buf = Vec::with_capacity(2048);
    let mut chunk = [0u8; 1024];
    loop {
        let n = match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => n,
            Err(_) => return,
        };
        buf.extend_from_slice(&chunk[..n]);
        if buf.windows(4).any(|w| w == b"\r\n\r\n") || buf.len() > 64 * 1024 {
            break;
        }
    }

    // Split headers from body.
    let header_end = match buf.windows(4).position(|w| w == b"\r\n\r\n") {
        Some(p) => p,
        None => {
            let _ = stream.write_all(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n");
            return;
        }
    };
    let head = String::from_utf8_lossy(&buf[..header_end]).to_string();
    let body_start = header_end + 4;

    let mut lines = head.split("\r\n");
    let request_line = lines.next().unwrap_or("");
    let mut parts = request_line.split_whitespace();
    let method = parts.next().unwrap_or("");
    let path = parts.next().unwrap_or("");

    let mut content_length: usize = 0;
    for h in lines {
        if let Some(rest) = h.strip_prefix("Content-Length:").or_else(|| h.strip_prefix("content-length:")) {
            content_length = rest.trim().parse().unwrap_or(0);
        }
    }

    // Read any remaining body bytes that didn't arrive in the first chunk.
    while buf.len() - body_start < content_length && buf.len() < 64 * 1024 {
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => buf.extend_from_slice(&chunk[..n]),
            Err(_) => break,
        }
    }
    let body = &buf[body_start..(body_start + content_length).min(buf.len())];

    // Route dispatch.
    let (status, body_out) = match (method, path) {
        ("POST", "/voice/show") => {
            let parsed: serde_json::Value = serde_json::from_slice(body).unwrap_or(serde_json::Value::Null);
            let token = parsed.get("token").and_then(|v| v.as_str()).unwrap_or("");
            if token != expected_token {
                ("401 Unauthorized", r#"{"ok":false,"error":"token mismatch"}"#.to_string())
            } else {
                show_main(&app, Some("voice"));
                ("200 OK", r#"{"ok":true}"#.to_string())
            }
        }
        ("GET", "/ping") => ("200 OK", r#"{"ok":true,"service":"hermes-relay-tray-ipc"}"#.to_string()),
        _ => ("404 Not Found", r#"{"ok":false,"error":"unknown route"}"#.to_string()),
    };

    let response = format!(
        "HTTP/1.1 {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        status,
        body_out.len(),
        body_out
    );
    let _ = stream.write_all(response.as_bytes());
}

fn start_grant_watcher(app: AppHandle) {
    thread::spawn(move || {
        let mut last_ids = String::new();
        loop {
            let grants = load_pending_grants();
            let ids = grants
                .iter()
                .map(|grant| grant.id.as_str())
                .collect::<Vec<_>>()
                .join(",");
            if !ids.is_empty() && ids != last_ids {
                let state = app.state::<AppState>();
                push_log(
                    &state,
                    "warn",
                    "grant_request",
                    format!(
                        "{} computer-use grant request(s) awaiting review",
                        grants.len()
                    ),
                );
                show_overlay(&app, true);
                show_main(&app, Some("grants"));
                let _ = app.emit("dashboard://refresh", ());
            }
            last_ids = ids;
            thread::sleep(Duration::from_millis(1000));
        }
    });
}

fn configure_tray(app: &AppHandle) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Open Hermes Relay", true, None::<&str>)?;
    let pair = MenuItem::with_id(app, "pair", "Pair new relay", true, None::<&str>)?;
    let sessions = MenuItem::with_id(app, "sessions", "TUI Sessions", true, None::<&str>)?;
    let devices = MenuItem::with_id(app, "devices", "Devices", true, None::<&str>)?;
    let grants = MenuItem::with_id(app, "grants", "Grant Requests", true, None::<&str>)?;
    let log = MenuItem::with_id(app, "log", "Task Log", true, None::<&str>)?;
    let pause = MenuItem::with_id(app, "pause", "Pause control", true, None::<&str>)?;
    let emergency = MenuItem::with_id(app, "emergency", "Emergency Stop", true, None::<&str>)?;
    let settings = MenuItem::with_id(app, "settings", "Settings", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    let menu = Menu::with_items(
        app,
        &[
            &open, &pair, &sessions, &devices, &grants, &log, &pause, &emergency, &settings, &quit,
        ],
    )?;
    let tray_icon = Image::from_bytes(include_bytes!("../icons/icon-256.png"))?;

    TrayIconBuilder::with_id("hermes-relay")
        .tooltip("Hermes Relay Desktop")
        .icon(tray_icon)
        .show_menu_on_left_click(false)
        .menu(&menu)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "open" => show_main(app, Some("overview")),
            "pair" => show_main(app, Some("pair")),
            "sessions" => show_main(app, Some("sessions")),
            "devices" => show_main(app, Some("devices")),
            "grants" => show_main(app, Some("grants")),
            "log" => show_main(app, Some("log")),
            "settings" => show_main(app, Some("settings")),
            "pause" => {
                let _ = stop_daemon_app(app, "pause", true);
            }
            "emergency" => {
                let _ = emergency_stop_app(app);
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main(tray.app_handle(), Some("overview"));
            }
        })
        .build(app)?;
    Ok(())
}

fn configure_shortcut(app: &AppHandle) -> tauri::Result<()> {
    let shortcut = Shortcut::new(Some(Modifiers::CONTROL | Modifiers::SHIFT), Code::KeyH);
    if let Err(err) = app.global_shortcut().register(shortcut) {
        let state = app.state::<AppState>();
        push_log(
            &state,
            "warn",
            "hotkey",
            format!("failed to register emergency shortcut: {err}"),
        );
    }
    Ok(())
}

pub fn run() {
    tauri::Builder::default()
        .manage(AppState::default())
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(|app, _shortcut, event| {
                    if event.state == ShortcutState::Pressed {
                        let _ = emergency_stop_app(app);
                    }
                })
                .build(),
        )
        .invoke_handler(tauri::generate_handler![
            get_dashboard_state,
            save_desktop_config,
            start_chat_turn,
            stop_chat_turn,
            start_daemon,
            stop_daemon,
            emergency_stop,
            pair_relay,
            set_desktop_tool_consent,
            list_devices,
            revoke_device,
            clear_task_log,
            get_voice_url,
            run_doctor,
            list_terminal_sessions,
            open_tui_terminal,
            open_tui_session,
            embedded_terminal_status,
            start_embedded_terminal,
            write_embedded_terminal,
            resize_embedded_terminal,
            stop_embedded_terminal,
            install_plugin,
            update_plugin,
            launch_plugin_terminal,
            start_plugin_embedded_terminal,
            kill_tui_session,
            resolve_grant
        ])
        .setup(|app| {
            let handle = app.handle().clone();
            configure_tray(&handle)?;
            configure_shortcut(&handle)?;
            start_grant_watcher(handle.clone());
            start_tray_ipc_server(handle.clone());
            let config = load_config();
            show_overlay(&handle, config.overlay.visible);
            let sessions = load_sessions();
            if config.auto_start_daemon {
                if let Some(url) = selected_url(&config, &sessions) {
                    let _ = start_daemon_app(&handle, url, config.experimental_computer_use);
                }
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            if window.label() == "main" {
                if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running Hermes Relay Desktop");
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
fn main() {
    run();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_config_keeps_legacy_tier_and_blocklist() {
        let config = DesktopControlConfig::default();
        assert_eq!(config.tier, "easy");
        assert!(config.auto_start_daemon);
        assert!(config
            .blocklist
            .iter()
            .any(|item| item.contains("password")));
        assert!(config.blocklist.iter().any(|item| item.contains("banking")));
    }

    #[test]
    fn selected_url_requires_configured_relay_to_be_paired() {
        let mut config = DesktopControlConfig::default();
        let one = SessionSummary {
            url: "ws://one".to_string(),
            server_version: None,
            paired_at: None,
            ttl_expires_at: None,
            endpoint_role: None,
            tools_consented: true,
            computer_use_consented: false,
            token_redacted: "(redacted)".to_string(),
        };
        let configured = SessionSummary {
            url: "ws://configured".to_string(),
            server_version: None,
            paired_at: None,
            ttl_expires_at: None,
            endpoint_role: None,
            tools_consented: true,
            computer_use_consented: false,
            token_redacted: "(redacted)".to_string(),
        };
        assert_eq!(
            selected_url(&config, &[one.clone()]),
            Some("ws://one".to_string())
        );
        config.relay_url = Some("ws://configured".to_string());
        assert_eq!(
            selected_url(&config, &[one.clone()]),
            Some("ws://one".to_string())
        );
        assert_eq!(
            selected_url(&config, &[one.clone(), configured.clone()]),
            Some("ws://configured".to_string())
        );
        assert_eq!(selected_url(&config, &[]), None);
        assert_eq!(
            selected_url(
                &config,
                &[
                    one.clone(),
                    SessionSummary {
                        url: "ws://two".to_string(),
                        ..one
                    },
                ],
            ),
            None
        );
    }

    #[test]
    fn selected_or_remote_accepts_override_or_paired_state() {
        let mut config = DesktopControlConfig::default();
        let sessions = vec![SessionSummary {
            url: "ws://configured".to_string(),
            server_version: None,
            paired_at: None,
            ttl_expires_at: None,
            endpoint_role: None,
            tools_consented: true,
            computer_use_consented: false,
            token_redacted: "(redacted)".to_string(),
        }];
        assert_eq!(
            selected_or_remote(Some(" ws://override ".to_string()), &config, &sessions),
            Ok("ws://override".to_string())
        );
        assert_eq!(
            selected_or_remote(None, &config, &[]),
            Err("no paired relay selected".to_string())
        );
        config.relay_url = Some("ws://configured".to_string());
        assert_eq!(
            selected_or_remote(None, &config, &sessions),
            Ok("ws://configured".to_string())
        );
    }

    #[test]
    fn empty_sessions_do_not_select_default_remote() {
        let config = DesktopControlConfig::default();
        assert_eq!(selected_url(&config, &[]), None);
    }

    #[test]
    fn chat_setup_prefers_paired_relay_for_first_run() {
        let config = DesktopControlConfig::default();
        let sessions = vec![SessionSummary {
            url: "ws://relay.example:8767".to_string(),
            server_version: None,
            paired_at: None,
            ttl_expires_at: None,
            endpoint_role: Some("lan".to_string()),
            tools_consented: true,
            computer_use_consented: false,
            token_redacted: "(redacted)".to_string(),
        }];
        let setup = chat_setup_state(&config, &sessions);
        assert_eq!(setup.default_mode, "relay");
        assert!(setup.can_use_relay);
        assert_eq!(setup.relay_url, Some("ws://relay.example:8767".to_string()));
    }

    #[test]
    fn chat_setup_uses_gateway_when_unpaired() {
        let mut config = DesktopControlConfig::default();
        config.chat_gateway_url = Some("gateway.local:8642/".to_string());
        let setup = chat_setup_state(&config, &[]);
        assert_eq!(setup.default_mode, "gateway");
        assert!(setup.can_use_gateway);
        assert_eq!(setup.gateway_url, Some("http://gateway.local:8642".to_string()));
    }

    #[test]
    fn chat_setup_requests_first_run_without_route() {
        let config = DesktopControlConfig::default();
        let setup = chat_setup_state(&config, &[]);
        assert_eq!(setup.default_mode, "setup");
        assert!(!setup.can_use_relay);
        assert!(!setup.can_use_gateway);
    }

    #[test]
    fn chat_launch_plan_builds_relay_resume_command() {
        let mut config = DesktopControlConfig::default();
        config.relay_url = Some("ws://relay.example:8767".to_string());
        let sessions = vec![SessionSummary {
            url: "ws://relay.example:8767".to_string(),
            server_version: None,
            paired_at: None,
            ttl_expires_at: None,
            endpoint_role: None,
            tools_consented: true,
            computer_use_consented: false,
            token_redacted: "(redacted)".to_string(),
        }];
        let plan = chat_launch_plan(
            &config,
            &sessions,
            "relay",
            "hello",
            None,
            Some("sess_123".to_string()),
            false,
        )
        .expect("relay chat plan");
        assert_eq!(plan.mode, "relay");
        assert_eq!(plan.route, "ws://relay.example:8767");
        assert!(plan.args.contains(&"--conversation".to_string()));
        assert!(plan.args.contains(&"sess_123".to_string()));
        assert!(plan.args.contains(&"--no-tools".to_string()));
    }

    #[test]
    fn chat_launch_plan_builds_direct_gateway_command() {
        let config = DesktopControlConfig::default();
        let plan = chat_launch_plan(
            &config,
            &[],
            "gateway",
            "hello",
            Some("https://gateway.example:8642/".to_string()),
            Some("sess_123".to_string()),
            true,
        )
        .expect("gateway chat plan");
        assert_eq!(plan.mode, "gateway");
        assert_eq!(plan.route, "https://gateway.example:8642");
        assert_eq!(plan.args[0], "chat-worker");
        assert!(plan.args.contains(&"--gateway-url".to_string()));
        assert!(plan.args.contains(&"--new".to_string()));
        assert!(plan.args.contains(&"--session".to_string()));
    }

    #[test]
    fn stored_sessions_accept_fractional_expiry_values() {
        let raw = r#"{
  "version": 1,
  "sessions": {
    "ws://relay.example:8767": {
      "token": "secret",
      "server_version": "0.6.0",
      "paired_at": 1779119869,
      "ttl_expires_at": 1781711871.5673373,
      "tools_consented": true
    }
  }
}"#;
        let parsed: StoredSessionsFile =
            serde_json::from_str(raw).expect("parse fractional session timestamps");
        let session = parsed
            .sessions
            .get("ws://relay.example:8767")
            .expect("stored session");
        assert_eq!(session.paired_at, Some(1779119869.0));
        assert_eq!(session.ttl_expires_at, Some(1781711871.5673373));
    }

    #[test]
    fn set_tools_consent_updates_existing_session_only() {
        let mut path = env::temp_dir();
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock before unix epoch")
            .as_nanos();
        path.push(format!("hermes-relay-sessions-{unique}.json"));
        fs::write(
            &path,
            r#"{
  "sessions": {
    "ws://relay.example:8767": {
      "token": "secret",
      "tools_consented": false,
      "computer_use_consented": false
    }
  }
}"#,
        )
        .expect("write test sessions");

        set_tools_consent_at(&path, "ws://relay.example:8767", true).expect("grant tools consent");
        let granted: serde_json::Value =
            serde_json::from_str(&fs::read_to_string(&path).expect("read granted sessions"))
                .expect("parse granted sessions");
        assert_eq!(
            granted["sessions"]["ws://relay.example:8767"]["tools_consented"],
            serde_json::Value::Bool(true)
        );

        let missing = set_tools_consent_at(&path, "ws://missing:8767", true)
            .expect_err("missing relay should fail");
        assert!(missing.contains("no stored session"));

        let _ = fs::remove_file(&path);
    }

    #[test]
    fn parse_pair_stdout_extracts_relay_and_route() {
        let stdout =
            "✓ Paired\n  Server: 0.6.0\n  Relay:  ws://example:8767\n  Route:  tailscale\n";
        assert_eq!(
            parse_pair_stdout_value(stdout, "Relay"),
            Some("ws://example:8767".to_string())
        );
        assert_eq!(
            parse_pair_stdout_value(stdout, "Route"),
            Some("tailscale".to_string())
        );
    }

    #[test]
    fn sidecar_name_matches_tauri_external_bin_convention() {
        let name = sidecar_file_name();
        assert!(name.starts_with("hermes-relay"));
        if cfg!(windows) {
            assert!(name.ends_with(".exe"));
        }
    }

    #[test]
    fn terminal_launch_command_uses_bare_resume_path() {
        let command =
            terminal_launch_command(Some("ws://relay.example:8767"), true, Some("default"), true)
                .expect("terminal launch command");
        assert!(!command.contains(" shell"));
        assert!(command.contains("--remote"));
        assert!(command.contains("ws://relay.example:8767"));
        assert!(command.contains("--new"));
        assert!(command.contains("--session"));
        assert!(command.contains("default"));
        assert!(command.contains("--experimental-computer-use"));
    }

    #[test]
    fn builtin_plugins_register_herm_surface_contract() {
        let herm = builtin_plugin("herm").expect("built-in Herm plugin");
        assert_eq!(herm.name, "Herm");
        assert_eq!(herm.source_url, "https://github.com/liftaris/herm");
        assert_eq!(herm.package_name, "herm-tui");
        assert_eq!(herm.binary_name, "herm");
        assert!(herm.tabs.iter().any(|tab| tab == "sessions"));
        assert!(herm.tabs.iter().any(|tab| tab == "kanban"));
        assert!(herm.commands.iter().any(|command| command.id == "install"));
        assert!(herm.session_actions.iter().any(|action| action.id == "resume"));
    }

    #[test]
    fn plugin_command_plan_quotes_display_and_preserves_args() {
        let plan = plugin_plan("bun", &["add", "-g", "herm tui"], "bun");
        assert_eq!(plan.program, "bun");
        assert_eq!(plan.args, vec!["add", "-g", "herm tui"]);
        assert_eq!(plan.display, "bun add -g \"herm tui\"");
        assert_eq!(plan.mode, "bun");
    }

    #[test]
    fn plugin_resume_plan_uses_dash_c_for_installed_binary() {
        let mut herm = builtin_plugin("herm").expect("built-in Herm plugin");
        herm.binary_name = "rustc".to_string();
        let plan = plugin_launch_plan(&herm, true).expect("installed plugin launch plan");
        assert_eq!(plan.program, "rustc");
        assert_eq!(plan.args, vec!["-c"]);
        assert_eq!(plan.display, "rustc -c");
        assert_eq!(plan.mode, "installed");
    }

    #[test]
    fn grant_bridge_ids_are_path_safe() {
        assert!(valid_bridge_id("grant-abc_123"));
        assert!(!valid_bridge_id(""));
        assert!(!valid_bridge_id("../grant-abc"));
        assert!(!valid_bridge_id("grant abc"));
    }
}
