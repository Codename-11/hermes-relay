#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

#[cfg(not(windows))]
compile_error!("hermes-relay-tray is a Windows-only optional systray");

#[cfg(windows)]
mod app {
    use serde::{Deserialize, Serialize};
    use serde_json::Value;
    use std::{
        collections::BTreeMap,
        env, fs,
        fs::OpenOptions,
        io::Write,
        os::windows::process::CommandExt,
        path::{Path, PathBuf},
        process::{Command, Output},
        sync::{mpsc, Arc, Mutex},
        thread,
        time::{Duration, SystemTime, UNIX_EPOCH},
    };
    use tauri::{
        image::Image,
        menu::{MenuBuilder, MenuItemBuilder},
        tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
        AppHandle, LogicalSize, Manager, PhysicalPosition, PhysicalRect, PhysicalSize, Position,
        RunEvent, Size, State, WindowEvent,
    };
    use windows::{
        core::{IUnknown, PCWSTR},
        Win32::{
            Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS, HANDLE, POINT},
            System::{
                Com::{CoCreateInstance, CLSCTX_INPROC_SERVER},
                Threading::{CreateMutexW, CREATE_NO_WINDOW},
                Variant::VARIANT,
            },
            UI::{
                Accessibility::{
                    CUIAutomation, IUIAutomation, TreeScope_Descendants,
                    UIA_AutomationIdPropertyId, UIA_NamePropertyId,
                },
                HiDpi::{
                    SetProcessDpiAwarenessContext, DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2,
                },
                WindowsAndMessaging::GetCursorPos,
            },
        },
    };

    const RUN_KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
    const RUN_VALUE: &str = "HermesRelayTray";
    const POPUP_GAP: f64 = 10.0;
    const MONITOR_MARGIN: f64 = 8.0;
    const MAIN_LOGICAL_WIDTH: f64 = 380.0;
    const MAIN_LOGICAL_HEIGHT: f64 = 620.0;
    const MAIN_MIN_LOGICAL_WIDTH: f64 = 340.0;
    const MAIN_MIN_LOGICAL_HEIGHT: f64 = 460.0;

    #[derive(Clone, Copy, Debug)]
    struct TrayAnchor {
        x: f64,
        y: f64,
        width: f64,
        height: f64,
    }

    fn tray_rect_anchor(position: Position, size: Size) -> TrayAnchor {
        match (position, size) {
            (Position::Physical(position), Size::Physical(size)) => TrayAnchor {
                x: position.x as f64,
                y: position.y as f64,
                width: size.width as f64,
                height: size.height as f64,
            },
            (position, size) => {
                let logical_position = match position {
                    Position::Physical(value) => (value.x as f64, value.y as f64),
                    Position::Logical(value) => (value.x, value.y),
                };
                let logical_size = match size {
                    Size::Physical(value) => (value.width as f64, value.height as f64),
                    Size::Logical(value) => (value.width, value.height),
                };
                TrayAnchor {
                    x: logical_position.0,
                    y: logical_position.1,
                    width: logical_size.0,
                    height: logical_size.1,
                }
            }
        }
    }

    fn tray_event_anchor(position: Position, size: Size) -> TrayAnchor {
        let fallback = tray_rect_anchor(position, size);

        // Windows can report notification-area bounds in logical coordinates while
        // Tauri's window and monitor APIs below consume physical coordinates. The
        // cursor is necessarily over the icon for a click event, so use its physical
        // position as the stable anchor across mixed-DPI and multi-monitor layouts.
        let mut cursor = POINT::default();
        if unsafe { GetCursorPos(&mut cursor) }.is_ok() {
            let width = fallback.width.max(1.0);
            let height = fallback.height.max(1.0);
            return TrayAnchor {
                x: cursor.x as f64 - width / 2.0,
                y: cursor.y as f64 - height / 2.0,
                width,
                height,
            };
        }

        fallback
    }

    fn tray_anchor_from_bounds(left: i32, top: i32, right: i32, bottom: i32) -> Option<TrayAnchor> {
        if right <= left || bottom <= top {
            return None;
        }
        Some(TrayAnchor {
            x: left as f64,
            y: top as f64,
            width: (right - left) as f64,
            height: (bottom - top) as f64,
        })
    }

    fn notification_area_anchor() -> Option<TrayAnchor> {
        // Shell_NotifyIconGetRect can briefly return the neighboring icon's slot
        // while Explorer is settling the notification area. UI Automation gives
        // us the bounds of the exact visible Hermes icon by its accessible name.
        unsafe {
            let automation: IUIAutomation =
                CoCreateInstance(&CUIAutomation, None::<&IUnknown>, CLSCTX_INPROC_SERVER).ok()?;
            let root = automation.GetRootElement().ok()?;
            let name = VARIANT::from("Hermes-Relay CLI UI");
            let automation_id = VARIANT::from("NotifyItemIcon");
            let name_condition = automation
                .CreatePropertyCondition(UIA_NamePropertyId, &name)
                .ok()?;
            let id_condition = automation
                .CreatePropertyCondition(UIA_AutomationIdPropertyId, &automation_id)
                .ok()?;
            let condition = automation
                .CreateAndCondition(&name_condition, &id_condition)
                .ok()?;
            let element = root.FindFirst(TreeScope_Descendants, &condition).ok()?;
            let bounds = element.CurrentBoundingRectangle().ok()?;
            tray_anchor_from_bounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }
    }

    fn current_tray_anchor(app: &AppHandle) -> Option<TrayAnchor> {
        notification_area_anchor().or_else(|| {
            app.tray_by_id("hermes-relay")
                .and_then(|tray| tray.rect().ok().flatten())
                .map(|rect| tray_rect_anchor(rect.position, rect.size))
        })
    }

    #[derive(Clone, Default)]
    struct TrayPositionState(Arc<Mutex<Option<TrayAnchor>>>);

    enum TrayAction {
        Toggle(Option<TrayAnchor>),
        RestartDaemon,
        StopDaemon,
    }

    struct InstanceMutex(HANDLE);

    impl Drop for InstanceMutex {
        fn drop(&mut self) {
            unsafe {
                let _ = CloseHandle(self.0);
            }
        }
    }

    fn acquire_instance_mutex() -> Result<Option<InstanceMutex>, String> {
        let name = "Local\\HermesRelayManagementTray\0"
            .encode_utf16()
            .collect::<Vec<_>>();
        let handle = unsafe { CreateMutexW(None, true, PCWSTR(name.as_ptr())) }
            .map_err(|error| format!("cannot create tray instance mutex: {error}"))?;
        if unsafe { GetLastError() } == ERROR_ALREADY_EXISTS {
            unsafe {
                let _ = CloseHandle(handle);
            }
            return Ok(None);
        }
        Ok(Some(InstanceMutex(handle)))
    }

    fn reveal_existing_instance() -> bool {
        let Ok(path) = activation_request_path() else {
            return false;
        };
        let Some(parent) = path.parent() else {
            return false;
        };
        fs::create_dir_all(parent).is_ok()
            && fs::write(path, std::process::id().to_string()).is_ok()
    }

    #[derive(Debug, Clone, Default, Deserialize, Serialize)]
    struct DaemonStatus {
        #[serde(default = "stopped")]
        state: String,
        #[serde(default, alias = "alive")]
        running: bool,
        url: Option<String>,
        privilege: Option<String>,
        username: Option<String>,
        updated_at: Option<u64>,
    }

    fn stopped() -> String {
        "stopped".to_string()
    }

    #[derive(Debug, Clone, Deserialize, Serialize)]
    struct Host {
        url: String,
        #[serde(default, alias = "host", alias = "hostname")]
        name: String,
        server_version: Option<String>,
        endpoint_role: Option<String>,
        paired_at: Option<u64>,
        #[serde(default)]
        is_active: bool,
        #[serde(default = "ask_mode")]
        access_mode: String,
        #[serde(default)]
        capabilities: BTreeMap<String, String>,
    }

    fn ask_mode() -> String {
        "ask".to_string()
    }

    #[derive(Debug, Clone, Default, Deserialize, Serialize)]
    struct Activity {
        ts: u64,
        #[serde(skip_serializing_if = "Option::is_none")]
        kind: Option<String>,
        tool: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        category: Option<String>,
        ok: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        aborted: Option<bool>,
        #[serde(skip_serializing_if = "Option::is_none")]
        request_id: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        host_url: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        duration_ms: Option<u64>,
        #[serde(skip_serializing_if = "Option::is_none")]
        exit_code: Option<i64>,
        #[serde(skip_serializing_if = "Option::is_none")]
        summary: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        args_preview: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        request_detail: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        stdout: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        stderr: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        result_detail: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        request_truncated: Option<bool>,
        #[serde(skip_serializing_if = "Option::is_none")]
        stdout_truncated: Option<bool>,
        #[serde(skip_serializing_if = "Option::is_none")]
        stderr_truncated: Option<bool>,
        #[serde(skip_serializing_if = "Option::is_none")]
        result_truncated: Option<bool>,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
    }

    #[derive(Debug, Clone, Deserialize, Serialize)]
    struct PendingGrantRequest {
        id: String,
        mode: String,
        duration_seconds: u64,
        reason: String,
        created_at: String,
        scope: Option<Value>,
    }

    #[derive(Debug, Serialize)]
    struct Snapshot {
        hosts: Vec<Host>,
        active_url: Option<String>,
        daemon: DaemonStatus,
        activity: Vec<Activity>,
        pending_grants: Vec<PendingGrantRequest>,
        startup_enabled: bool,
        hardware_availability: HardwareAvailability,
    }

    #[derive(Debug, Serialize)]
    struct HardwareAvailability {
        usb: bool,
        adb: bool,
        microphone: bool,
        camera: bool,
    }

    fn home_dir() -> Result<PathBuf, String> {
        env::var_os("USERPROFILE")
            .or_else(|| env::var_os("HOME"))
            .map(PathBuf::from)
            .ok_or_else(|| "USERPROFILE is not available".to_string())
    }

    fn activation_request_path() -> Result<PathBuf, String> {
        Ok(home_dir()?.join(".hermes").join("tray-show-request"))
    }

    fn grant_bridge_dir() -> Result<PathBuf, String> {
        Ok(env::var_os("HERMES_RELAY_GRANT_BRIDGE_DIR")
            .map(PathBuf::from)
            .unwrap_or(home_dir()?.join(".hermes").join("grant-bridge")))
    }

    fn first_pending_grant_id() -> Option<String> {
        let mut requests = fs::read_dir(grant_bridge_dir().ok()?)
            .ok()?
            .filter_map(Result::ok)
            .filter_map(|entry| {
                let name = entry.file_name().to_string_lossy().into_owned();
                if !name.starts_with("request-") || !name.ends_with(".json") {
                    return None;
                }
                let request =
                    serde_json::from_slice::<PendingGrantRequest>(&fs::read(entry.path()).ok()?)
                        .ok()?;
                (name == format!("request-{}.json", request.id)).then_some(request)
            })
            .collect::<Vec<_>>();
        requests.sort_by(|left, right| left.created_at.cmp(&right.created_at));
        requests.first().map(|request| request.id.clone())
    }

    fn cli_candidates(explicit: Option<&Path>, current_exe: &Path, home: &Path) -> Vec<PathBuf> {
        let mut candidates = Vec::new();
        if let Some(path) = explicit {
            candidates.push(path.to_path_buf());
        }
        if let Some(parent) = current_exe.parent() {
            candidates.push(parent.join("hermes-relay.exe"));
        }
        candidates.push(home.join(".hermes").join("bin").join("hermes-relay.exe"));
        candidates
    }

    fn resolve_cli() -> Result<PathBuf, String> {
        let explicit = env::var_os("HERMES_RELAY_CLI_PATH").map(PathBuf::from);
        let current =
            env::current_exe().map_err(|e| format!("cannot locate tray executable: {e}"))?;
        let home = home_dir()?;
        for candidate in cli_candidates(explicit.as_deref(), &current, &home) {
            if candidate.is_file() {
                return Ok(candidate);
            }
        }
        if let Some(path) = env::var_os("PATH") {
            for directory in env::split_paths(&path) {
                let candidate = directory.join("hermes-relay.exe");
                if candidate.is_file() {
                    return Ok(candidate);
                }
            }
        }
        Err(
            "hermes-relay.exe was not found beside the tray app, in ~/.hermes/bin, or on PATH"
                .to_string(),
        )
    }

    fn run_cli(args: &[&str]) -> Result<Output, String> {
        Command::new(resolve_cli()?)
            .args(args)
            .creation_flags(CREATE_NO_WINDOW.0)
            .output()
            .map_err(|e| format!("failed to run hermes-relay {}: {e}", args.join(" ")))
    }

    fn run_cli_checked(args: &[&str]) -> Result<String, String> {
        let output = run_cli(args)?;
        let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if output.status.success() {
            return Ok(stdout);
        }
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        Err(if stderr.is_empty() { stdout } else { stderr })
    }

    fn run_json(args: &[&str]) -> Result<Value, String> {
        let output = run_cli_checked(args)?;
        serde_json::from_str(&output)
            .map_err(|e| format!("invalid JSON from hermes-relay {}: {e}", args.join(" ")))
    }

    fn active_url() -> Option<String> {
        let path = home_dir()
            .ok()?
            .join(".hermes")
            .join("desktop-control.json");
        let value: Value = serde_json::from_str(&fs::read_to_string(path).ok()?).ok()?;
        value.get("relay_url")?.as_str().map(str::to_string)
    }

    fn hosts_from_status(active: Option<&str>) -> Result<Vec<Host>, String> {
        let value = run_json(&["status", "--json"])?;
        let Some(records) = value.as_object() else {
            return Ok(Vec::new());
        };
        Ok(records
            .iter()
            .map(|(url, record)| Host {
                url: url.clone(),
                name: String::new(),
                server_version: record
                    .get("serverVersion")
                    .or_else(|| record.get("server_version"))
                    .and_then(Value::as_str)
                    .map(str::to_string),
                endpoint_role: record
                    .get("endpointRole")
                    .or_else(|| record.get("endpoint_role"))
                    .and_then(Value::as_str)
                    .map(str::to_string),
                paired_at: record
                    .get("pairedAt")
                    .or_else(|| record.get("paired_at"))
                    .and_then(Value::as_u64),
                is_active: active == Some(url.as_str()),
                access_mode: if record
                    .get("toolsConsented")
                    .or_else(|| record.get("tools_consented"))
                    .and_then(Value::as_bool)
                    == Some(true)
                {
                    "trusted".to_string()
                } else {
                    "ask".to_string()
                },
                capabilities: BTreeMap::new(),
            })
            .collect())
    }

    fn read_activity() -> Vec<Activity> {
        let Ok(path) = home_dir().map(|h| h.join(".hermes").join("desktop-audit.jsonl")) else {
            return Vec::new();
        };
        let Ok(text) = fs::read_to_string(path) else {
            return Vec::new();
        };
        text.lines()
            .rev()
            .take(30)
            .filter_map(|line| serde_json::from_str(line).ok())
            .collect::<Vec<_>>()
            .into_iter()
            .rev()
            .collect()
    }

    fn append_management_event(
        tool: &str,
        summary: &str,
        host_url: Option<&str>,
        request_id: Option<&str>,
    ) {
        let Ok(directory) = home_dir().map(|home| home.join(".hermes")) else {
            return;
        };
        if fs::create_dir_all(&directory).is_err() {
            return;
        }
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        let event = serde_json::json!({
            "ts": timestamp,
            "kind": "management.completed",
            "tool": tool,
            "category": "system",
            "ok": true,
            "host_url": host_url,
            "request_id": request_id,
            "summary": summary,
        });
        if let Ok(mut file) = OpenOptions::new()
            .create(true)
            .append(true)
            .open(directory.join("desktop-audit.jsonl"))
        {
            let _ = writeln!(file, "{event}");
        }
    }

    #[tauri::command]
    fn clear_activity() -> Result<(), String> {
        let directory = home_dir()?.join(".hermes");
        let path = directory.join("desktop-audit.jsonl");
        fs::write(&path, []).map_err(|error| format!("cannot clear activity: {error}"))?;
        let backup = directory.join("desktop-audit.jsonl.1");
        if backup.exists() {
            fs::remove_file(&backup)
                .map_err(|error| format!("cannot remove rotated activity: {error}"))?;
        }
        Ok(())
    }

    fn startup_enabled() -> bool {
        Command::new("reg.exe")
            .args(["query", RUN_KEY, "/v", RUN_VALUE])
            .creation_flags(CREATE_NO_WINDOW.0)
            .status()
            .is_ok_and(|s| s.success())
    }

    fn hardware_availability() -> HardwareAvailability {
        let adb = env::var_os("HERMES_RELAY_ADB_PATH")
            .filter(|value| !value.is_empty())
            .unwrap_or_else(|| "adb".into());
        let adb = Command::new(adb)
            .arg("version")
            .creation_flags(CREATE_NO_WINDOW.0)
            .status()
            .is_ok_and(|status| status.success());
        HardwareAvailability {
            usb: true,
            adb,
            microphone: false,
            camera: false,
        }
    }

    #[tauri::command]
    async fn get_snapshot() -> Result<Snapshot, String> {
        let selected = active_url();
        let mut hosts = match run_json(&["hosts", "list", "--json"]) {
            Ok(value) => serde_json::from_value::<Vec<Host>>(
                value
                    .get("hosts")
                    .cloned()
                    .unwrap_or(Value::Array(Vec::new())),
            )
            .unwrap_or_default(),
            Err(_) => hosts_from_status(selected.as_deref())?,
        };
        for host in &mut hosts {
            if host.access_mode == "full_access" {
                host.access_mode = "full-access".to_string();
            } else if host.access_mode == "ask_every_time" {
                host.access_mode = "ask-every-time".to_string();
            }
        }
        let daemon = run_json(&["daemon", "status", "--json"])
            .ok()
            .and_then(|value| serde_json::from_value::<DaemonStatus>(value).ok())
            .unwrap_or_default();
        let pending_grants = run_json(&["grants", "--json"])
            .ok()
            .and_then(|value| serde_json::from_value::<Vec<PendingGrantRequest>>(value).ok())
            .unwrap_or_default();
        Ok(Snapshot {
            hosts,
            active_url: selected,
            daemon,
            activity: read_activity(),
            pending_grants,
            startup_enabled: startup_enabled(),
            hardware_availability: hardware_availability(),
        })
    }

    #[tauri::command]
    async fn check_desktop_update() -> Result<Value, String> {
        tauri::async_runtime::spawn_blocking(|| {
            run_json(&["update", "--installer", "--check", "--json"])
        })
        .await
        .map_err(|error| format!("desktop update check task failed: {error}"))?
    }

    #[tauri::command]
    async fn install_desktop_update(app: AppHandle) -> Result<Value, String> {
        let (report, restart_daemon) = tauri::async_runtime::spawn_blocking(|| {
            let restart_daemon = run_json(&["daemon", "status", "--json"])
                .ok()
                .and_then(|value| serde_json::from_value::<DaemonStatus>(value).ok())
                .is_some_and(|status| status.running);
            // The tray owns exit/restart orchestration. Ask the CLI only to
            // download and verify so setup is launched exactly once.
            let report = run_json(&[
                "update",
                "--installer",
                "--download-only",
                "--yes",
                "--json",
            ])?;
            Ok::<_, String>((report, restart_daemon))
        })
        .await
        .map_err(|error| format!("desktop update download task failed: {error}"))??;

        let installer = report
            .get("installed_path")
            .and_then(Value::as_str)
            .filter(|path| !path.is_empty())
            .ok_or_else(|| "desktop updater did not return an installer path".to_string())?;
        let tray_exe = env::current_exe()
            .map_err(|error| format!("cannot resolve the tray executable: {error}"))?;
        let cli_exe = tray_exe
            .parent()
            .map(|directory| directory.join("hermes-relay.exe"))
            .ok_or_else(|| "cannot resolve the installed CLI path".to_string())?;
        let helper_script = "$ErrorActionPreference='Stop'; "
            .to_string()
            + "$targetPid=[int]$env:HERMES_UPDATE_PID; "
            + "Wait-Process -Id $targetPid -ErrorAction SilentlyContinue; "
            + "$result=Start-Process -FilePath $env:HERMES_UPDATE_INSTALLER -ArgumentList '/S' -Wait -PassThru; "
            + "if ($result.ExitCode -eq 0) { "
            + "if ($env:HERMES_UPDATE_RESTART_DAEMON -eq '1') { & $env:HERMES_UPDATE_CLI daemon start | Out-Null }; "
            + "Start-Process -FilePath $env:HERMES_UPDATE_TRAY }";
        Command::new("powershell.exe")
            .args([
                "-NoProfile",
                "-NonInteractive",
                "-WindowStyle",
                "Hidden",
                "-Command",
                &helper_script,
            ])
            .env("HERMES_UPDATE_PID", std::process::id().to_string())
            .env("HERMES_UPDATE_INSTALLER", installer)
            .env("HERMES_UPDATE_TRAY", tray_exe)
            .env("HERMES_UPDATE_CLI", cli_exe)
            .env(
                "HERMES_UPDATE_RESTART_DAEMON",
                if restart_daemon { "1" } else { "0" },
            )
            .creation_flags(CREATE_NO_WINDOW.0)
            .spawn()
            .map_err(|error| format!("cannot launch desktop update helper: {error}"))?;

        let exit_app = app.clone();
        app.run_on_main_thread(move || exit_app.exit(0))
            .map_err(|error| format!("cannot exit for desktop update: {error}"))?;
        append_management_event("desktop.update", "Desktop update started", None, None);
        Ok(report)
    }

    fn daemon_is_running() -> bool {
        run_json(&["daemon", "status", "--json"])
            .ok()
            .and_then(|value| {
                value
                    .get("running")
                    .or_else(|| value.get("alive"))
                    .and_then(Value::as_bool)
            })
            .unwrap_or(false)
    }

    fn restart_daemon_if_running(was_running: bool) -> Result<(), String> {
        if was_running {
            run_cli_checked(&["daemon", "restart"]).map(|_| ())
        } else {
            Ok(())
        }
    }

    #[tauri::command]
    async fn select_host(remote: String) -> Result<(), String> {
        tauri::async_runtime::spawn_blocking(move || {
            let was_running = daemon_is_running();
            run_cli_checked(&["hosts", "select", &remote])?;
            restart_daemon_if_running(was_running)?;
            append_management_event(
                "host.select",
                "Connected host selected",
                Some(&remote),
                None,
            );
            Ok(())
        })
        .await
        .map_err(|error| format!("host selection task failed: {error}"))?
    }

    #[tauri::command]
    fn rename_host(remote: String, name: String) -> Result<(), String> {
        let normalized = name.trim();
        if normalized.is_empty() || normalized.len() > 64 || normalized.contains(['\r', '\n', '\t'])
        {
            return Err("host name must be 1-64 characters on one line".to_string());
        }
        run_cli_checked(&["hosts", "rename", &remote, normalized])?;
        append_management_event(
            "host.rename",
            "Host display name changed",
            Some(&remote),
            None,
        );
        Ok(())
    }

    #[tauri::command]
    fn set_host_access(remote: String, mode: String) -> Result<(), String> {
        if !matches!(
            mode.as_str(),
            "ask" | "ask-every-time" | "structured" | "trusted" | "full-access"
        ) {
            return Err("invalid host access mode".to_string());
        }
        let mut args = vec![
            "hosts",
            "access",
            mode.as_str(),
            "--remote",
            remote.as_str(),
        ];
        if mode == "full-access" {
            args.push("--yes");
        }
        let was_running = daemon_is_running();
        run_cli_checked(&args)?;
        restart_daemon_if_running(was_running)?;
        append_management_event(
            "host.access",
            &format!("Access changed to {mode}"),
            Some(&remote),
            None,
        );
        Ok(())
    }

    #[tauri::command]
    fn set_host_capability(remote: String, capability: String, mode: String) -> Result<(), String> {
        if !matches!(
            capability.as_str(),
            "commands" | "files" | "screen-input" | "usb" | "microphone" | "camera"
        ) {
            return Err("invalid host capability".to_string());
        }
        if !matches!(mode.as_str(), "disabled" | "ask" | "allow") {
            return Err("invalid capability access mode".to_string());
        }
        let mut args = vec![
            "hosts",
            "capability",
            capability.as_str(),
            mode.as_str(),
            "--remote",
            remote.as_str(),
        ];
        if mode == "allow" {
            args.push("--yes");
        }
        let was_running = daemon_is_running();
        run_cli_checked(&args)?;
        restart_daemon_if_running(was_running)?;
        append_management_event(
            "host.capability",
            &format!("{capability} capability changed to {mode}"),
            Some(&remote),
            None,
        );
        Ok(())
    }

    #[tauri::command]
    fn list_authorized_clients(remote: String) -> Result<Value, String> {
        run_json(&["devices", "list", "--remote", &remote, "--json"])
    }

    #[tauri::command]
    fn revoke_authorized_client(remote: String, prefix: String) -> Result<(), String> {
        run_cli_checked(&["devices", "revoke", &prefix, "--remote", &remote])?;
        append_management_event(
            "client.revoke",
            "Authorized client deauthorized",
            Some(&remote),
            None,
        );
        Ok(())
    }

    #[tauri::command]
    fn resolve_grant(id: String, approved: bool) -> Result<(), String> {
        if id.is_empty()
            || id.len() > 96
            || !id
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
        {
            return Err("invalid grant request id".to_string());
        }
        let verb = if approved { "approve" } else { "reject" };
        run_cli_checked(&["grants", verb, &id])?;
        append_management_event(
            "grant.resolve",
            if approved {
                "Access request approved"
            } else {
                "Access request rejected"
            },
            None,
            Some(&id),
        );
        Ok(())
    }

    fn open_pair_terminal() -> Result<(), String> {
        let cli = resolve_cli()?;
        let escaped = cli.display().to_string().replace('\'', "''");
        Command::new("powershell.exe")
            .args([
                "-NoLogo",
                "-NoExit",
                "-Command",
                &format!("& '{escaped}' pair"),
            ])
            .spawn()
            .map(|_| ())
            .map_err(|e| format!("failed to open PowerShell: {e}"))
    }

    #[tauri::command]
    fn pair_host() -> Result<(), String> {
        open_pair_terminal()?;
        append_management_event("host.pair", "Pairing opened", None, None);
        Ok(())
    }

    #[tauri::command]
    fn connect_daemon() -> Result<(), String> {
        run_cli_checked(&["daemon", "start"])?;
        append_management_event("daemon.start", "Relay daemon connected", None, None);
        Ok(())
    }
    #[tauri::command]
    fn disconnect_daemon() -> Result<(), String> {
        run_cli_checked(&["daemon", "stop"])?;
        append_management_event("daemon.stop", "Relay daemon disconnected", None, None);
        Ok(())
    }
    #[tauri::command]
    fn restart_daemon() -> Result<(), String> {
        run_cli_checked(&["daemon", "restart"])?;
        append_management_event("daemon.restart", "Relay daemon restarted", None, None);
        Ok(())
    }

    #[tauri::command]
    fn set_startup(enabled: bool) -> Result<(), String> {
        let mut command = Command::new("reg.exe");
        if enabled {
            let executable =
                env::current_exe().map_err(|e| format!("cannot locate tray executable: {e}"))?;
            let value = format!("\"{}\"", executable.display());
            command.args([
                "add", RUN_KEY, "/v", RUN_VALUE, "/t", "REG_SZ", "/d", &value, "/f",
            ]);
        } else {
            command.args(["delete", RUN_KEY, "/v", RUN_VALUE, "/f"]);
        }
        let output = command
            .creation_flags(CREATE_NO_WINDOW.0)
            .output()
            .map_err(|e| format!("failed to update sign-in setting: {e}"))?;
        if output.status.success() {
            append_management_event(
                "startup.change",
                if enabled {
                    "Start at sign-in enabled"
                } else {
                    "Start at sign-in disabled"
                },
                None,
                None,
            );
            Ok(())
        } else {
            Err(String::from_utf8_lossy(&output.stderr).trim().to_string())
        }
    }

    fn clamped(value: f64, minimum: f64, maximum: f64) -> f64 {
        if maximum < minimum {
            minimum
        } else {
            value.clamp(minimum, maximum)
        }
    }

    fn popup_position(
        anchor: TrayAnchor,
        window_size: PhysicalSize<u32>,
        monitor_position: PhysicalPosition<i32>,
        monitor_size: PhysicalSize<u32>,
    ) -> PhysicalPosition<i32> {
        let left = monitor_position.x as f64;
        let top = monitor_position.y as f64;
        let right = left + monitor_size.width as f64;
        let bottom = top + monitor_size.height as f64;
        let anchor_x = anchor.x + anchor.width / 2.0;
        let anchor_y = anchor.y + anchor.height / 2.0;
        let window_width = window_size.width as f64;
        let window_height = window_size.height as f64;

        let distances = [
            (anchor_x - left, 0_u8),
            (right - anchor_x, 1_u8),
            (anchor_y - top, 2_u8),
            (bottom - anchor_y, 3_u8),
        ];
        let nearest_edge = distances
            .into_iter()
            .min_by(|a, b| a.0.total_cmp(&b.0))
            .map(|(_, edge)| edge)
            .unwrap_or(3);

        let (x, y) = match nearest_edge {
            0 => (
                anchor.x + anchor.width + POPUP_GAP,
                anchor_y - window_height / 2.0,
            ),
            1 => (
                anchor.x - window_width - POPUP_GAP,
                anchor_y - window_height / 2.0,
            ),
            2 => (
                anchor_x - window_width / 2.0,
                anchor.y + anchor.height + POPUP_GAP,
            ),
            _ => (
                anchor_x - window_width / 2.0,
                anchor.y - window_height - POPUP_GAP,
            ),
        };

        PhysicalPosition::new(
            clamped(
                x,
                left + MONITOR_MARGIN,
                right - window_width - MONITOR_MARGIN,
            )
            .round() as i32,
            clamped(
                y,
                top + MONITOR_MARGIN,
                bottom - window_height - MONITOR_MARGIN,
            )
            .round() as i32,
        )
    }

    fn responsive_logical_window_size(
        work_area: &PhysicalRect<i32, u32>,
        scale: f64,
    ) -> LogicalSize<f64> {
        let scale = if scale.is_finite() && scale > 0.0 {
            scale
        } else {
            1.0
        };
        let available_width = (work_area.size.width as f64 / scale - MONITOR_MARGIN * 2.0).max(1.0);
        let available_height =
            (work_area.size.height as f64 / scale - MONITOR_MARGIN * 2.0).max(1.0);
        let width = MAIN_LOGICAL_WIDTH
            .min(available_width)
            .max(MAIN_MIN_LOGICAL_WIDTH.min(available_width));
        let height = MAIN_LOGICAL_HEIGHT
            .min(available_height)
            .max(MAIN_MIN_LOGICAL_HEIGHT.min(available_height));

        LogicalSize::new(width, height)
    }

    fn position_window(app: &AppHandle, anchor: TrayAnchor) {
        let Some(window) = app.get_webview_window("main") else {
            return;
        };
        let center_x = anchor.x + anchor.width / 2.0;
        let center_y = anchor.y + anchor.height / 2.0;
        let Some(monitor) = window
            .monitor_from_point(center_x, center_y)
            .ok()
            .flatten()
            .or_else(|| window.current_monitor().ok().flatten())
            .or_else(|| window.primary_monitor().ok().flatten())
        else {
            return;
        };
        let work_area = monitor.work_area();
        let scale = monitor.scale_factor();
        let logical_size = responsive_logical_window_size(work_area, scale);

        // Commit the tray monitor before applying its logical size. This avoids
        // inheriting the hidden creation monitor's DPI on first launch.
        let _ = window.set_position(PhysicalPosition::new(
            center_x.round() as i32,
            center_y.round() as i32,
        ));
        let _ = window.set_size(Size::Logical(logical_size));
        // WebView frame metrics and monitor transitions can produce outer
        // dimensions that differ from a logical-size times DPI calculation.
        // Center from the authoritative post-resize bounds Windows reports.
        let Ok(physical_size) = window.outer_size() else {
            return;
        };
        let position = popup_position(anchor, physical_size, work_area.position, work_area.size);
        let _ = window.set_position(position);
    }

    fn reveal_main_window(app: &AppHandle, anchor: Option<TrayAnchor>) {
        let Some(window) = app.get_webview_window("main") else {
            return;
        };
        // Refresh on every reveal. Explorer can move notification icons after
        // startup, and a cached rectangle would make later resizes snap back.
        let anchor = current_tray_anchor(app).or(anchor);
        if let Some(anchor) = anchor {
            position_window(app, anchor);
        }
        let _ = window.show();
        // Showing commits the target monitor's DPI on Windows. Re-run the same
        // calculation immediately so the now-final physical size is centered
        // over the icon rather than the hidden creation monitor's scale.
        if let Some(anchor) = anchor {
            position_window(app, anchor);
        }
        let _ = window.set_focus();
        let _ = window.eval(
            "requestAnimationFrame(() => { window.dispatchEvent(new Event('hermes-show')); document.querySelector('.app-shell')?.animate([{ opacity: 0, transform: 'translateY(8px) scale(.985)' }, { opacity: 1, transform: 'translateY(0) scale(1)' }], { duration: 180, easing: 'cubic-bezier(.2, .8, .2, 1)' }); })",
        );
    }

    fn request_main_hide(window: &tauri::WebviewWindow) {
        let _ = window.hide();
    }

    fn grant_window_size(expanded: bool, scale: f64) -> PhysicalSize<u32> {
        let logical_height = if expanded { 226.0 } else { 134.0 };
        PhysicalSize::new(
            (360.0_f64 * scale).round() as u32,
            (logical_height * scale).round() as u32,
        )
    }

    fn bottom_right_position(
        work_area: &PhysicalRect<i32, u32>,
        window_size: PhysicalSize<u32>,
        scale: f64,
    ) -> PhysicalPosition<i32> {
        let margin = (12.0_f64 * scale).round() as i32;
        PhysicalPosition::new(
            work_area.position.x + work_area.size.width as i32 - window_size.width as i32 - margin,
            work_area.position.y + work_area.size.height as i32
                - window_size.height as i32
                - margin,
        )
    }

    fn toggle_window(app: &AppHandle, anchor: Option<TrayAnchor>) {
        let Some(window) = app.get_webview_window("main") else {
            return;
        };
        if window.is_visible().unwrap_or(false) {
            request_main_hide(&window);
        } else {
            reveal_main_window(app, anchor);
        }
    }

    fn start_tray_action_worker(app: AppHandle) -> mpsc::Sender<TrayAction> {
        let (sender, receiver) = mpsc::channel::<TrayAction>();
        thread::spawn(move || {
            while let Ok(action) = receiver.recv() {
                match action {
                    TrayAction::Toggle(anchor) => {
                        let toggle_app = app.clone();
                        let _ = app.run_on_main_thread(move || {
                            toggle_window(&toggle_app, anchor);
                        });
                    }
                    TrayAction::RestartDaemon => {
                        let _ = run_cli_checked(&["daemon", "restart"]);
                    }
                    TrayAction::StopDaemon => {
                        let _ = run_cli_checked(&["daemon", "stop"]);
                    }
                }
            }
        });
        sender
    }

    fn present_grant_window_inner(
        app: &AppHandle,
        expanded: bool,
        tray_position: &TrayPositionState,
    ) -> Result<(), String> {
        let window = app
            .get_webview_window("grant")
            .ok_or_else(|| "grant window is unavailable".to_string())?;
        let scale = window.scale_factor().map_err(|error| error.to_string())?;
        let size = grant_window_size(expanded, scale);
        window
            .set_size(Size::Physical(size))
            .map_err(|error| error.to_string())?;

        let anchor = tray_position.0.lock().ok().and_then(|value| *value);
        let monitor = anchor
            .and_then(|value| {
                let center_x = value.x + value.width / 2.0;
                let center_y = value.y + value.height / 2.0;
                window.monitor_from_point(center_x, center_y).ok().flatten()
            })
            .or_else(|| window.current_monitor().ok().flatten())
            .or_else(|| window.primary_monitor().ok().flatten())
            .ok_or_else(|| "no monitor is available for the grant window".to_string())?;
        let work_area = monitor.work_area();
        let position = bottom_right_position(work_area, size, scale);
        window
            .set_position(position)
            .map_err(|error| error.to_string())?;
        window.show().map_err(|error| error.to_string())?;
        Ok(())
    }

    #[tauri::command]
    fn present_grant_window(
        app: AppHandle,
        expanded: bool,
        tray_position: State<'_, TrayPositionState>,
    ) -> Result<(), String> {
        present_grant_window_inner(&app, expanded, &tray_position)
    }

    fn start_grant_watcher(app: AppHandle, tray_position: TrayPositionState) {
        thread::spawn(move || {
            let mut active_id = None::<String>;
            loop {
                let next_id = first_pending_grant_id();
                if next_id != active_id {
                    active_id = next_id.clone();
                    if next_id.is_some() {
                        let handle = app.clone();
                        let position = tray_position.clone();
                        let _ = app.run_on_main_thread(move || {
                            let _ = present_grant_window_inner(&handle, false, &position);
                        });
                    }
                }
                thread::sleep(Duration::from_millis(500));
            }
        });
    }

    fn start_activation_watcher(app: AppHandle) {
        thread::spawn(move || loop {
            if let Ok(path) = activation_request_path() {
                if path.exists() && fs::remove_file(&path).is_ok() {
                    let handle = app.clone();
                    let _ = app.run_on_main_thread(move || {
                        let anchor = current_tray_anchor(&handle);
                        reveal_main_window(&handle, anchor);
                    });
                }
            }
            thread::sleep(Duration::from_millis(100));
        });
    }

    pub fn run() {
        // Establish one physical coordinate space before Tauri creates any
        // windows. Without this, Windows virtualizes tray/cursor coordinates on
        // scaled monitors and the popup can anchor to the wrong screen edge.
        let _ =
            unsafe { SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2) };
        let show_on_launch = env::args_os().any(|arg| arg == "--show");
        let Some(_instance_mutex) = acquire_instance_mutex()
            .expect("failed to enforce a single Hermes-Relay CLI UI instance")
        else {
            if show_on_launch {
                let _ = reveal_existing_instance();
            }
            return;
        };
        let app = tauri::Builder::default()
            .invoke_handler(tauri::generate_handler![
                get_snapshot,
                check_desktop_update,
                install_desktop_update,
                select_host,
                rename_host,
                set_host_access,
                set_host_capability,
                list_authorized_clients,
                revoke_authorized_client,
                resolve_grant,
                pair_host,
                connect_daemon,
                disconnect_daemon,
                restart_daemon,
                set_startup,
                clear_activity,
                present_grant_window
            ])
            .setup(|app| {
                let anchor = TrayPositionState::default();
                app.manage(anchor.clone());
                let tray_anchor = anchor.clone();
                let menu_anchor = anchor.clone();
                start_grant_watcher(app.handle().clone(), anchor);
                start_activation_watcher(app.handle().clone());
                let tray_actions = start_tray_action_worker(app.handle().clone());
                let click_actions = tray_actions.clone();
                let menu_actions = tray_actions;
                let open =
                    MenuItemBuilder::with_id("open", "Open Hermes-Relay CLI UI").build(app)?;
                let restart = MenuItemBuilder::with_id("restart", "Restart daemon").build(app)?;
                let stop = MenuItemBuilder::with_id("stop", "Emergency stop daemon").build(app)?;
                let quit = MenuItemBuilder::with_id("quit", "Quit tray").build(app)?;
                let menu = MenuBuilder::new(app)
                    .items(&[&open, &restart, &stop])
                    .separator()
                    .item(&quit)
                    .build()?;
                TrayIconBuilder::with_id("hermes-relay")
                    .tooltip("Hermes-Relay CLI UI")
                    .icon(Image::from_bytes(include_bytes!("../icons/icon-256.png"))?)
                    .menu(&menu)
                    .show_menu_on_left_click(false)
                    .on_tray_icon_event(move |_tray, event| {
                        if let TrayIconEvent::Click {
                            rect,
                            button,
                            button_state,
                            ..
                        } = event
                        {
                            let current = tray_event_anchor(rect.position, rect.size);
                            if let Ok(mut stored) = tray_anchor.0.lock() {
                                *stored = Some(current);
                            }
                            if button == MouseButton::Left && button_state == MouseButtonState::Up {
                                let _ = click_actions.send(TrayAction::Toggle(Some(current)));
                            }
                        }
                    })
                    .on_menu_event(move |app, event| match event.id().as_ref() {
                        "open" => {
                            let current = menu_anchor.0.lock().ok().and_then(|value| *value);
                            let _ = menu_actions.send(TrayAction::Toggle(current));
                        }
                        "restart" => {
                            let _ = menu_actions.send(TrayAction::RestartDaemon);
                        }
                        "stop" => {
                            let _ = menu_actions.send(TrayAction::StopDaemon);
                        }
                        "quit" => app.exit(0),
                        _ => {}
                    })
                    .build(app)?;
                Ok(())
            })
            .build(tauri::generate_context!())
            .expect("failed to build Hermes-Relay CLI UI");

        app.run(move |handle, event| match event {
            RunEvent::Ready if show_on_launch => {
                let anchor = current_tray_anchor(handle);
                if let (Some(anchor), Some(state)) =
                    (anchor, handle.try_state::<TrayPositionState>())
                {
                    if let Ok(mut stored) = state.0.lock() {
                        *stored = Some(anchor);
                    }
                }
                reveal_main_window(handle, anchor);
            }
            RunEvent::ExitRequested {
                api, code: None, ..
            } => api.prevent_exit(),
            RunEvent::WindowEvent {
                label,
                event: WindowEvent::CloseRequested { api, .. },
                ..
            } if label == "main" => {
                api.prevent_close();
                if let Some(window) = handle.get_webview_window("main") {
                    request_main_hide(&window);
                }
            }
            _ => {}
        });
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn installed_cli_resolution_prefers_explicit_then_sibling_then_home() {
            let candidates = cli_candidates(
                Some(Path::new(r"C:\custom\hermes-relay.exe")),
                Path::new(r"C:\Program Files\Hermes\hermes-relay-tray.exe"),
                Path::new(r"C:\Users\example"),
            );

            assert_eq!(candidates[0], Path::new(r"C:\custom\hermes-relay.exe"));
            assert_eq!(
                candidates[1],
                Path::new(r"C:\Program Files\Hermes\hermes-relay.exe")
            );
            assert_eq!(
                candidates[2],
                Path::new(r"C:\Users\example\.hermes\bin\hermes-relay.exe")
            );
        }

        #[test]
        fn popup_centers_above_a_bottom_taskbar_icon() {
            let position = popup_position(
                TrayAnchor {
                    x: 1810.0,
                    y: 1040.0,
                    width: 24.0,
                    height: 24.0,
                },
                PhysicalSize::new(530, 744),
                PhysicalPosition::new(0, 0),
                PhysicalSize::new(1920, 1080),
            );

            assert_eq!(position.y, 286);
            assert_eq!(
                position.x, 1382,
                "horizontal placement is clamped on-screen"
            );
        }

        #[test]
        fn startup_uses_the_physical_tray_rectangle_as_its_anchor() {
            let anchor = tray_rect_anchor(
                Position::Physical(PhysicalPosition::new(3066, 1380)),
                Size::Physical(PhysicalSize::new(40, 60)),
            );

            assert_eq!(anchor.x, 3066.0);
            assert_eq!(anchor.y, 1380.0);
            assert_eq!(anchor.width, 40.0);
            assert_eq!(anchor.height, 60.0);
        }

        #[test]
        fn accessibility_bounds_map_to_the_exact_notification_icon() {
            let anchor = tray_anchor_from_bounds(3066, 1380, 3106, 1440).unwrap();

            assert_eq!(anchor.x, 3066.0);
            assert_eq!(anchor.y, 1380.0);
            assert_eq!(anchor.width, 40.0);
            assert_eq!(anchor.height, 60.0);
            assert!(tray_anchor_from_bounds(10, 10, 10, 20).is_none());
        }

        #[test]
        fn window_size_uses_monitor_dpi_not_taskbar_icon_geometry() {
            let work_area = PhysicalRect {
                position: PhysicalPosition::new(0, 0),
                size: PhysicalSize::new(3440, 1390),
            };
            let logical = responsive_logical_window_size(&work_area, 1.25);
            assert_eq!(logical, LogicalSize::new(380.0, 620.0));
        }

        #[test]
        fn window_height_compacts_to_a_small_scaled_work_area() {
            let work_area = PhysicalRect {
                position: PhysicalPosition::new(0, 0),
                size: PhysicalSize::new(1366, 728),
            };
            let logical = responsive_logical_window_size(&work_area, 1.5);
            assert_eq!(logical.width, 380.0);
            assert!(logical.height < 480.0);
        }

        #[test]
        fn popup_uses_the_icon_monitor_with_negative_coordinates() {
            let position = popup_position(
                TrayAnchor {
                    x: -900.0,
                    y: 1035.0,
                    width: 24.0,
                    height: 24.0,
                },
                PhysicalSize::new(530, 744),
                PhysicalPosition::new(-1920, 0),
                PhysicalSize::new(1920, 1080),
            );

            assert_eq!(position, PhysicalPosition::new(-1153, 281));
        }

        #[test]
        fn grant_card_sizes_and_anchors_inside_the_monitor_work_area() {
            let collapsed = grant_window_size(false, 1.25);
            let expanded = grant_window_size(true, 1.25);
            let work_area = PhysicalRect {
                position: PhysicalPosition::new(0, 0),
                size: PhysicalSize::new(1920, 1040),
            };

            assert_eq!(collapsed, PhysicalSize::new(450, 168));
            assert_eq!(expanded, PhysicalSize::new(450, 283));
            assert_eq!(
                bottom_right_position(&work_area, collapsed, 1.25),
                PhysicalPosition::new(1455, 857)
            );
        }
    }
}

#[cfg(windows)]
fn main() {
    app::run();
}
