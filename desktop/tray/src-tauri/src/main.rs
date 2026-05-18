use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    env, fs,
    io::{BufRead, BufReader},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
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

const DEFAULT_RELAY_URL: &str = "ws://127.0.0.1:8767";
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
    paired_at: Option<u64>,
    ttl_expires_at: Option<u64>,
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
    paired_at: Option<u64>,
    ttl_expires_at: Option<u64>,
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
    sessions: Vec<SessionSummary>,
    selected_url: Option<String>,
    daemon: DaemonStatus,
    cli: CliStatus,
    terminal_cli: CliStatus,
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

struct DaemonProcess {
    child: Child,
    remote: String,
    started_at_ms: u128,
}

struct AppState {
    daemon: Mutex<Option<DaemonProcess>>,
    task_log: Mutex<Vec<TaskLogEntry>>,
    paused: AtomicBool,
}

impl Default for AppState {
    fn default() -> Self {
        Self {
            daemon: Mutex::new(None),
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
    config
        .relay_url
        .clone()
        .or_else(|| (sessions.len() == 1).then(|| sessions[0].url.clone()))
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
    command.push_str(" shell");
    if let Some(remote) = remote_override.filter(|value| !value.trim().is_empty()) {
        command.push_str(" --remote ");
        command.push_str(&terminal_arg_quote(remote.trim()));
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
    let config_path = config_path()?.to_string_lossy().to_string();
    let session_store_path = sessions_path()?.to_string_lossy().to_string();
    let daemon = daemon_status_inner(&state);
    let task_log = state.task_log.lock().expect("task log poisoned").clone();
    Ok(DashboardState {
        config,
        config_path,
        session_store_path,
        sessions,
        selected_url: selected,
        daemon,
        cli: cli_status(Some(&app)),
        terminal_cli: terminal_cli_status(),
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
fn start_daemon(app: AppHandle, remote: Option<String>) -> Result<DaemonStatus, String> {
    let config = load_config();
    let sessions = load_sessions();
    let url = remote
        .or_else(|| selected_url(&config, &sessions))
        .unwrap_or_else(|| DEFAULT_RELAY_URL.to_string());
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
    let url = remote
        .or_else(|| selected_url(&config, &sessions))
        .ok_or_else(|| "no paired relay selected".to_string())?;
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
    let url = remote
        .or_else(|| selected_url(&config, &sessions))
        .ok_or_else(|| "no paired relay selected".to_string())?;
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
    let url = remote
        .or_else(|| selected_url(&config, &sessions))
        .ok_or_else(|| "no paired relay selected".to_string())?;
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

    let command_line =
        terminal_launch_command(remote_override.as_deref(), config.experimental_computer_use)?;
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
            &open, &pair, &devices, &grants, &log, &pause, &emergency, &settings, &quit,
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
            start_daemon,
            stop_daemon,
            emergency_stop,
            pair_relay,
            set_desktop_tool_consent,
            list_devices,
            revoke_device,
            clear_task_log,
            run_doctor,
            open_tui_terminal,
            resolve_grant
        ])
        .setup(|app| {
            let handle = app.handle().clone();
            configure_tray(&handle)?;
            configure_shortcut(&handle)?;
            start_grant_watcher(handle.clone());
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
    fn selected_url_prefers_config_then_single_session() {
        let mut config = DesktopControlConfig::default();
        let sessions = vec![SessionSummary {
            url: "ws://one".to_string(),
            server_version: None,
            paired_at: None,
            ttl_expires_at: None,
            endpoint_role: None,
            tools_consented: true,
            computer_use_consented: false,
            token_redacted: "(redacted)".to_string(),
        }];
        assert_eq!(
            selected_url(&config, &sessions),
            Some("ws://one".to_string())
        );
        config.relay_url = Some("ws://configured".to_string());
        assert_eq!(
            selected_url(&config, &sessions),
            Some("ws://configured".to_string())
        );
    }

    #[test]
    fn empty_sessions_do_not_select_default_remote() {
        let config = DesktopControlConfig::default();
        assert_eq!(selected_url(&config, &[]), None);
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
    fn grant_bridge_ids_are_path_safe() {
        assert!(valid_bridge_id("grant-abc_123"));
        assert!(!valid_bridge_id(""));
        assert!(!valid_bridge_id("../grant-abc"));
        assert!(!valid_bridge_id("grant abc"));
    }
}
