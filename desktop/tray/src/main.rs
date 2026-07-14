#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(not(windows))]
compile_error!("hermes-relay-tray is a Windows-only optional systray");

#[cfg(windows)]
mod windows_tray {
    use hermes_relay_tray::{
        cli_candidates, cmd_k_raw_args, daemon_menu_state, menu_entries, DaemonMenuInput,
        DaemonMenuState, TrayAction,
    };
    use serde::Deserialize;
    use std::collections::HashMap;
    use std::env;
    use std::ffi::OsStr;
    use std::fs;
    use std::io::Write;
    use std::os::windows::ffi::OsStrExt;
    use std::os::windows::process::CommandExt;
    use std::path::PathBuf;
    use std::process::{Command, Output};
    use std::sync::mpsc;
    use std::time::{SystemTime, UNIX_EPOCH};
    use tray_icon::{
        menu::{CheckMenuItem, Menu, MenuEvent, MenuItem, PredefinedMenuItem},
        Icon, TrayIconBuilder,
    };
    use windows::core::PCWSTR;
    use windows::Win32::Foundation::{
        CloseHandle, GetLastError, ERROR_ALREADY_EXISTS, HANDLE, LPARAM, STILL_ACTIVE, WPARAM,
    };
    use windows::Win32::System::Threading::{
        CreateMutexW, GetCurrentThreadId, GetExitCodeProcess, OpenProcess, CREATE_NEW_CONSOLE,
        CREATE_NO_WINDOW, PROCESS_QUERY_LIMITED_INFORMATION,
    };
    use windows::Win32::UI::Shell::ShellExecuteW;
    use windows::Win32::UI::WindowsAndMessaging::{
        DispatchMessageW, GetMessageW, KillTimer, MessageBoxW, PostQuitMessage, PostThreadMessageW,
        SetTimer, TranslateMessage, IDYES, MB_ICONERROR, MB_ICONWARNING, MB_OK, MB_TOPMOST,
        MB_YESNO, MSG, SW_HIDE, WM_APP, WM_TIMER,
    };

    const ACTION_MESSAGE: u32 = WM_APP + 17;
    const STATUS_TIMER: usize = 1;
    const STATUS_REFRESH_MS: u32 = 2_000;

    struct InstanceMutex(HANDLE);

    impl Drop for InstanceMutex {
        fn drop(&mut self) {
            unsafe {
                let _ = CloseHandle(self.0);
            }
        }
    }

    fn acquire_instance_mutex() -> Result<Option<InstanceMutex>, String> {
        let name = wide(OsStr::new("Local\\HermesRelayMenuOnlyTray"));
        let handle = unsafe { CreateMutexW(None, true, PCWSTR(name.as_ptr())) }
            .map_err(|error| format!("cannot create systray instance mutex: {error}"))?;
        if unsafe { GetLastError() } == ERROR_ALREADY_EXISTS {
            unsafe {
                let _ = CloseHandle(handle);
            }
            return Ok(None);
        }
        Ok(Some(InstanceMutex(handle)))
    }

    #[derive(Debug, Deserialize)]
    struct DaemonStatus {
        pid: u32,
        state: String,
        updated_at: u64,
        privilege: Option<String>,
        username: Option<String>,
        computer_use_enabled: Option<bool>,
        computer_grant: Option<ComputerGrantStatus>,
    }

    #[derive(Debug, Deserialize)]
    struct ComputerGrantStatus {
        active: bool,
        mode: String,
        expires_at: Option<String>,
    }

    #[derive(Debug, Deserialize)]
    struct DesktopUseSettings {
        computer_use_enabled: bool,
    }

    fn wide(value: &OsStr) -> Vec<u16> {
        value.encode_wide().chain(Some(0)).collect()
    }

    pub fn show_error(message: impl AsRef<str>) {
        let message = wide(OsStr::new(message.as_ref()));
        let title = wide(OsStr::new("Hermes Relay Systray"));
        unsafe {
            let _ = MessageBoxW(
                None,
                PCWSTR(message.as_ptr()),
                PCWSTR(title.as_ptr()),
                MB_OK | MB_ICONERROR,
            );
        }
    }

    fn confirm_computer_use_enable() -> bool {
        let message = wide(OsStr::new(
            "Enable experimental desktop use?\n\nHermes may request screenshots after an observe grant. Mouse and keyboard control still require a local, task-scoped approval and expire automatically.\n\nIf the daemon is running as Administrator, approved input actions also run as Administrator.",
        ));
        let title = wide(OsStr::new("Hermes Relay Desktop Use"));
        unsafe {
            MessageBoxW(
                None,
                PCWSTR(message.as_ptr()),
                PCWSTR(title.as_ptr()),
                MB_YESNO | MB_ICONWARNING | MB_TOPMOST,
            ) == IDYES
        }
    }

    fn show_pending_grant_notification(count: usize) {
        let message = wide(OsStr::new(&format!(
            "{count} desktop control grant request{} waiting.\n\nRight-click the Hermes Relay tray icon and choose Review pending grants.",
            if count == 1 { " is" } else { "s are" }
        )));
        let title = wide(OsStr::new("Hermes Relay Approval Required"));
        unsafe {
            let _ = MessageBoxW(
                None,
                PCWSTR(message.as_ptr()),
                PCWSTR(title.as_ptr()),
                MB_OK | MB_ICONWARNING | MB_TOPMOST,
            );
        }
    }

    pub fn log_startup_error(message: &str) {
        if let Ok(home) = home_dir() {
            let log_dir = home.join(".hermes");
            let _ = fs::create_dir_all(&log_dir);
            if let Ok(mut file) = fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(log_dir.join("tray.log"))
            {
                let _ = writeln!(file, "startup error: {message}");
            }
        }
    }

    fn home_dir() -> Result<PathBuf, String> {
        env::var_os("USERPROFILE")
            .or_else(|| env::var_os("HOME"))
            .map(PathBuf::from)
            .ok_or_else(|| "USERPROFILE is not available".to_string())
    }

    fn grant_bridge_dir() -> Result<PathBuf, String> {
        Ok(home_dir()?.join(".hermes").join("grant-bridge"))
    }

    fn resolve_cli() -> Result<PathBuf, String> {
        let explicit = env::var_os("HERMES_RELAY_CLI_PATH").map(PathBuf::from);
        let current = env::current_exe()
            .map_err(|error| format!("cannot locate systray executable: {error}"))?;
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
            "hermes-relay.exe was not found beside the systray, in ~/.hermes/bin, or on PATH"
                .to_string(),
        )
    }

    fn cli_command(args: &[&str]) -> Result<Command, String> {
        let mut command = Command::new(resolve_cli()?);
        command.args(args).creation_flags(CREATE_NO_WINDOW.0);
        if let Ok(dir) = grant_bridge_dir() {
            command.env("HERMES_RELAY_GRANT_BRIDGE_DIR", dir);
        }
        Ok(command)
    }

    fn run_cli(args: &[&str]) -> Result<Output, String> {
        cli_command(args)?
            .output()
            .map_err(|error| format!("failed to run hermes-relay {}: {error}", args.join(" ")))
    }

    fn run_cli_checked(args: &[&str]) -> Result<(), String> {
        let output = run_cli(args)?;
        if output.status.success() {
            return Ok(());
        }
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
        let detail = if !stderr.is_empty() { stderr } else { stdout };
        Err(if detail.is_empty() {
            format!(
                "hermes-relay {} exited with {}",
                args.join(" "),
                output.status
            )
        } else {
            detail
        })
    }

    fn open_cli_terminal(args: &[&str]) -> Result<(), String> {
        let cli = resolve_cli()?;
        let mut command = Command::new("cmd.exe");
        command
            .raw_arg(cmd_k_raw_args(&cli, args))
            .creation_flags(CREATE_NEW_CONSOLE.0)
            .env("HERMES_RELAY_GRANT_BRIDGE_DIR", grant_bridge_dir()?)
            .spawn()
            .map(|_| ())
            .map_err(|error| format!("failed to open terminal: {error}"))
    }

    fn open_log() -> Result<(), String> {
        let log = home_dir()?.join(".hermes").join("daemon.log");
        Command::new("notepad.exe")
            .arg(log)
            .spawn()
            .map(|_| ())
            .map_err(|error| format!("failed to open daemon log: {error}"))
    }

    fn read_daemon_status() -> Option<DaemonStatus> {
        let path = match home_dir() {
            Ok(home) => home.join(".hermes").join("daemon-status.json"),
            Err(_) => return None,
        };
        fs::read_to_string(path)
            .ok()
            .and_then(|text| serde_json::from_str::<DaemonStatus>(&text).ok())
    }

    fn computer_use_preference() -> bool {
        let path = match home_dir() {
            Ok(home) => home.join(".hermes").join("desktop-settings.json"),
            Err(_) => return false,
        };
        fs::read_to_string(path)
            .ok()
            .and_then(|text| serde_json::from_str::<DesktopUseSettings>(&text).ok())
            .is_some_and(|settings| settings.computer_use_enabled)
    }

    fn process_alive(pid: u32) -> bool {
        let handle = match unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid) } {
            Ok(handle) => handle,
            Err(_) => return false,
        };
        let mut exit_code = 0_u32;
        let active = unsafe { GetExitCodeProcess(handle, &mut exit_code) }.is_ok()
            && exit_code == STILL_ACTIVE.0 as u32;
        unsafe {
            let _ = CloseHandle(handle);
        }
        active
    }

    fn current_daemon_menu_state() -> DaemonMenuState {
        let status = read_daemon_status();
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|duration| duration.as_secs())
            .unwrap_or_default();
        let Some(status) = status.as_ref() else {
            return daemon_menu_state(DaemonMenuInput {
                now,
                computer_use_enabled: computer_use_preference(),
                ..DaemonMenuInput::default()
            });
        };
        let grant = status.computer_grant.as_ref();
        daemon_menu_state(DaemonMenuInput {
            state: Some(&status.state),
            updated_at: Some(status.updated_at),
            now,
            pid_alive: process_alive(status.pid),
            privilege: status.privilege.as_deref(),
            username: status.username.as_deref(),
            computer_use_enabled: status
                .computer_use_enabled
                .unwrap_or_else(computer_use_preference),
            grant_active: grant.is_some_and(|grant| grant.active),
            grant_mode: grant.map(|grant| grant.mode.as_str()),
            grant_expires_at: grant.and_then(|grant| grant.expires_at.as_deref()),
        })
    }

    fn daemon_is_elevated() -> bool {
        read_daemon_status().is_some_and(|status| {
            process_alive(status.pid) && status.privilege.as_deref() == Some("administrator")
        })
    }

    fn pending_grant_count() -> usize {
        let Ok(directory) = grant_bridge_dir() else {
            return 0;
        };
        fs::read_dir(directory)
            .ok()
            .into_iter()
            .flatten()
            .filter_map(Result::ok)
            .filter(|entry| {
                let name = entry.file_name();
                let name = name.to_string_lossy();
                name.starts_with("request-") && name.ends_with(".json")
            })
            .count()
    }

    const RUN_KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
    const RUN_VALUE: &str = "HermesRelayTray";

    fn startup_enabled() -> bool {
        Command::new("reg.exe")
            .args(["query", RUN_KEY, "/v", RUN_VALUE])
            .creation_flags(CREATE_NO_WINDOW.0)
            .status()
            .is_ok_and(|status| status.success())
    }

    fn set_startup_enabled(enabled: bool) -> Result<(), String> {
        let mut command = Command::new("reg.exe");
        if enabled {
            let executable = env::current_exe()
                .map_err(|error| format!("cannot locate systray executable: {error}"))?;
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
            .map_err(|error| format!("failed to update sign-in setting: {error}"))?;
        if output.status.success() {
            Ok(())
        } else {
            Err(String::from_utf8_lossy(&output.stderr).trim().to_string())
        }
    }

    fn run_cli_elevated(args: &[&str]) -> Result<(), String> {
        let executable = resolve_cli()?;
        let verb = wide(OsStr::new("runas"));
        let file = wide(executable.as_os_str());
        let parameters = wide(OsStr::new(&args.join(" ")));
        let result = unsafe {
            ShellExecuteW(
                None,
                PCWSTR(verb.as_ptr()),
                PCWSTR(file.as_ptr()),
                PCWSTR(parameters.as_ptr()),
                PCWSTR::null(),
                SW_HIDE,
            )
        };
        let code = result.0 as isize;
        if code > 32 {
            Ok(())
        } else {
            Err(format!(
                "administrator action was cancelled or failed (ShellExecute code {code})"
            ))
        }
    }

    fn restart_daemon_preserving_privilege() -> Result<(), String> {
        if daemon_is_elevated() {
            run_cli_elevated(&["daemon", "restart"])
        } else {
            run_cli_checked(&["daemon", "restart"])
        }
    }

    fn perform(action: TrayAction) -> Result<(), String> {
        match action {
            TrayAction::OpenTui => open_cli_terminal(&[]),
            TrayAction::Status => open_cli_terminal(&["daemon", "status"]),
            TrayAction::StartDaemon => run_cli_checked(&["daemon", "start"]),
            TrayAction::ElevateDaemon => {
                let command = if current_daemon_menu_state().stop_enabled {
                    "restart"
                } else {
                    "start"
                };
                run_cli_elevated(&["daemon", command])
            }
            TrayAction::StopDaemon => {
                if daemon_is_elevated() {
                    run_cli_elevated(&["daemon", "stop"])
                } else {
                    run_cli_checked(&["daemon", "stop"])
                }
            }
            TrayAction::RestartDaemon => {
                if daemon_is_elevated() {
                    run_cli_elevated(&["daemon", "restart"])
                } else {
                    run_cli_checked(&["daemon", "restart"])
                }
            }
            TrayAction::Pair => open_cli_terminal(&["pair"]),
            TrayAction::ReviewGrants => open_cli_terminal(&["grants"]),
            TrayAction::Audit => open_cli_terminal(&["audit"]),
            TrayAction::Diagnostics => open_cli_terminal(&["doctor"]),
            TrayAction::OpenLogs => open_log(),
            TrayAction::ToggleComputerUse => {
                let state = current_daemon_menu_state();
                let was_running = state.stop_enabled;
                if state.computer_use_enabled {
                    run_cli_checked(&["computer-use", "disable"])?;
                } else {
                    if !confirm_computer_use_enable() {
                        return Ok(());
                    }
                    run_cli_checked(&["computer-use", "enable", "--yes"])?;
                }
                if was_running {
                    restart_daemon_preserving_privilege()?;
                }
                Ok(())
            }
            TrayAction::CancelComputerGrant => run_cli_checked(&["computer-use", "cancel"]),
            TrayAction::ToggleStartup => set_startup_enabled(!startup_enabled()),
            TrayAction::EmergencyStop => {
                if daemon_is_elevated() {
                    run_cli_elevated(&["daemon", "stop"])
                } else {
                    run_cli_checked(&["daemon", "stop"])
                }
            }
            TrayAction::Exit => {
                unsafe { PostQuitMessage(0) };
                Ok(())
            }
        }
    }

    fn load_icon() -> Result<Icon, String> {
        let image = image::load_from_memory(include_bytes!("../icons/icon-256.png"))
            .map_err(|error| format!("cannot decode systray icon: {error}"))?
            .into_rgba8();
        let (width, height) = image.dimensions();
        Icon::from_rgba(image.into_raw(), width, height)
            .map_err(|error| format!("cannot create systray icon: {error}"))
    }

    fn refresh_menu(
        status_item: &MenuItem,
        desktop_use_item: &MenuItem,
        grant_item: &MenuItem,
        safety_item: &MenuItem,
        action_items: &HashMap<TrayAction, MenuItem>,
    ) -> usize {
        let state = current_daemon_menu_state();
        status_item.set_text(&state.label);
        desktop_use_item.set_text(&state.computer_use_label);
        grant_item.set_text(&state.grant_label);
        safety_item.set_text(&state.safety_label);
        if let Some(item) = action_items.get(&TrayAction::StartDaemon) {
            item.set_enabled(state.start_enabled);
        }
        if let Some(item) = action_items.get(&TrayAction::ElevateDaemon) {
            item.set_text(&state.elevated_action_label);
            item.set_enabled(state.elevated_action_enabled);
        }
        if let Some(item) = action_items.get(&TrayAction::StopDaemon) {
            item.set_enabled(state.stop_enabled);
        }
        if let Some(item) = action_items.get(&TrayAction::RestartDaemon) {
            item.set_enabled(state.restart_enabled);
        }
        if let Some(item) = action_items.get(&TrayAction::EmergencyStop) {
            item.set_enabled(state.stop_enabled);
        }
        let pending = pending_grant_count();
        if let Some(item) = action_items.get(&TrayAction::ReviewGrants) {
            item.set_text(format!("Review pending grants ({})...", pending));
            item.set_enabled(pending > 0);
        }
        if let Some(item) = action_items.get(&TrayAction::ToggleComputerUse) {
            item.set_text(&state.computer_use_action_label);
        }
        if let Some(item) = action_items.get(&TrayAction::CancelComputerGrant) {
            item.set_enabled(state.cancel_grant_enabled);
        }
        pending
    }

    pub fn run() -> Result<(), String> {
        let Some(_instance_mutex) = acquire_instance_mutex()? else {
            return Ok(());
        };
        let menu = Menu::new();
        let status_item = MenuItem::with_id("daemon_state", "Daemon: loading...", false, None);
        let desktop_use_item =
            MenuItem::with_id("desktop_use_state", "Desktop use: loading...", false, None);
        let grant_item = MenuItem::with_id("grant_state", "Active grant: loading...", false, None);
        let safety_item = MenuItem::with_id("safety_state", "Safety: loading...", false, None);
        menu.append(&status_item)
            .map_err(|error| error.to_string())?;
        menu.append(&desktop_use_item)
            .map_err(|error| error.to_string())?;
        menu.append(&grant_item)
            .map_err(|error| error.to_string())?;
        menu.append(&safety_item)
            .map_err(|error| error.to_string())?;
        menu.append(&PredefinedMenuItem::separator())
            .map_err(|error| error.to_string())?;

        let mut action_items = HashMap::new();
        let mut startup_item = None;
        for entry in menu_entries() {
            if entry.separator_before {
                menu.append(&PredefinedMenuItem::separator())
                    .map_err(|error| error.to_string())?;
            }
            if entry.action == TrayAction::ToggleStartup {
                let item = CheckMenuItem::with_id(
                    entry.action.id(),
                    entry.label,
                    true,
                    startup_enabled(),
                    None,
                );
                menu.append(&item).map_err(|error| error.to_string())?;
                startup_item = Some(item);
            } else {
                let item = MenuItem::with_id(entry.action.id(), entry.label, true, None);
                menu.append(&item).map_err(|error| error.to_string())?;
                action_items.insert(entry.action, item);
            }
        }
        let version_item = MenuItem::with_id(
            "version",
            format!("Hermes Relay {}", env!("CARGO_PKG_VERSION")),
            false,
            None,
        );
        menu.append(&version_item)
            .map_err(|error| error.to_string())?;
        let startup_item =
            startup_item.ok_or_else(|| "startup menu item is missing".to_string())?;
        let mut last_pending_count = refresh_menu(
            &status_item,
            &desktop_use_item,
            &grant_item,
            &safety_item,
            &action_items,
        );

        let _tray = TrayIconBuilder::new()
            .with_id("hermes-relay")
            .with_tooltip("Hermes Relay CLI")
            .with_icon(load_icon()?)
            .with_menu(Box::new(menu))
            .with_menu_on_left_click(false)
            .with_menu_on_right_click(true)
            .build()
            .map_err(|error| format!("cannot create systray: {error}"))?;

        let thread_id = unsafe { GetCurrentThreadId() };
        let (sender, receiver) = mpsc::channel::<TrayAction>();
        MenuEvent::set_event_handler(Some(move |event: MenuEvent| {
            if let Some(action) = TrayAction::from_id(&event.id().0) {
                let _ = sender.send(action);
                unsafe {
                    let _ = PostThreadMessageW(thread_id, ACTION_MESSAGE, WPARAM(0), LPARAM(0));
                }
            }
        }));

        unsafe {
            if SetTimer(None, STATUS_TIMER, STATUS_REFRESH_MS, None) == 0 {
                return Err("cannot create systray status timer".to_string());
            }
            let mut message = MSG::default();
            loop {
                let result = GetMessageW(&mut message, None, 0, 0).0;
                if result == -1 {
                    let _ = KillTimer(None, STATUS_TIMER);
                    return Err("Windows message loop failed".to_string());
                }
                if result == 0 {
                    break;
                }
                if message.message == ACTION_MESSAGE {
                    while let Ok(action) = receiver.try_recv() {
                        let toggled_startup = action == TrayAction::ToggleStartup;
                        if let Err(error) = perform(action) {
                            show_error(error);
                        }
                        if toggled_startup {
                            startup_item.set_checked(startup_enabled());
                        }
                    }
                    last_pending_count = refresh_menu(
                        &status_item,
                        &desktop_use_item,
                        &grant_item,
                        &safety_item,
                        &action_items,
                    );
                    continue;
                }
                if message.message == WM_TIMER && message.wParam.0 == STATUS_TIMER {
                    let pending_count = refresh_menu(
                        &status_item,
                        &desktop_use_item,
                        &grant_item,
                        &safety_item,
                        &action_items,
                    );
                    if pending_count > last_pending_count {
                        show_pending_grant_notification(pending_count);
                    }
                    last_pending_count = pending_count;
                    continue;
                }
                let _ = TranslateMessage(&message);
                DispatchMessageW(&message);
            }
            let _ = KillTimer(None, STATUS_TIMER);
        }
        Ok(())
    }
}

#[cfg(windows)]
fn main() {
    if let Err(error) = windows_tray::run() {
        windows_tray::log_startup_error(&error);
        windows_tray::show_error(error);
    }
}
