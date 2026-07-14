use std::path::{Path, PathBuf};

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub enum TrayAction {
    OpenTui,
    Status,
    StartDaemon,
    ElevateDaemon,
    StopDaemon,
    RestartDaemon,
    Pair,
    ReviewGrants,
    Audit,
    Diagnostics,
    OpenLogs,
    ToggleComputerUse,
    CancelComputerGrant,
    ToggleStartup,
    EmergencyStop,
    Exit,
}

impl TrayAction {
    pub const fn id(self) -> &'static str {
        match self {
            Self::OpenTui => "open_tui",
            Self::Status => "status",
            Self::StartDaemon => "start_daemon",
            Self::ElevateDaemon => "elevate_daemon",
            Self::StopDaemon => "stop_daemon",
            Self::RestartDaemon => "restart_daemon",
            Self::Pair => "pair",
            Self::ReviewGrants => "review_grants",
            Self::Audit => "audit",
            Self::Diagnostics => "diagnostics",
            Self::OpenLogs => "open_logs",
            Self::ToggleComputerUse => "toggle_computer_use",
            Self::CancelComputerGrant => "cancel_computer_grant",
            Self::ToggleStartup => "toggle_startup",
            Self::EmergencyStop => "emergency_stop",
            Self::Exit => "exit",
        }
    }

    pub fn from_id(id: &str) -> Option<Self> {
        menu_entries()
            .iter()
            .find(|entry| entry.action.id() == id)
            .map(|entry| entry.action)
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct MenuEntry {
    pub action: TrayAction,
    pub label: &'static str,
    pub separator_before: bool,
}

const MENU_ENTRIES: [MenuEntry; 16] = [
    MenuEntry {
        action: TrayAction::OpenTui,
        label: "Open Hermes Relay TUI",
        separator_before: false,
    },
    MenuEntry {
        action: TrayAction::Status,
        label: "Daemon status...",
        separator_before: false,
    },
    MenuEntry {
        action: TrayAction::StartDaemon,
        label: "Start daemon",
        separator_before: true,
    },
    MenuEntry {
        action: TrayAction::ElevateDaemon,
        label: "Start daemon as Administrator...",
        separator_before: false,
    },
    MenuEntry {
        action: TrayAction::StopDaemon,
        label: "Stop daemon",
        separator_before: false,
    },
    MenuEntry {
        action: TrayAction::RestartDaemon,
        label: "Restart daemon",
        separator_before: false,
    },
    MenuEntry {
        action: TrayAction::Pair,
        label: "Pair or re-pair...",
        separator_before: true,
    },
    MenuEntry {
        action: TrayAction::ReviewGrants,
        label: "Review pending grants...",
        separator_before: false,
    },
    MenuEntry {
        action: TrayAction::Audit,
        label: "Recent tool activity...",
        separator_before: false,
    },
    MenuEntry {
        action: TrayAction::Diagnostics,
        label: "Run diagnostics...",
        separator_before: false,
    },
    MenuEntry {
        action: TrayAction::OpenLogs,
        label: "Open daemon log",
        separator_before: false,
    },
    MenuEntry {
        action: TrayAction::ToggleComputerUse,
        label: "Enable desktop use...",
        separator_before: true,
    },
    MenuEntry {
        action: TrayAction::CancelComputerGrant,
        label: "Cancel active desktop grant",
        separator_before: false,
    },
    MenuEntry {
        action: TrayAction::ToggleStartup,
        label: "Start tray at sign-in",
        separator_before: true,
    },
    MenuEntry {
        action: TrayAction::EmergencyStop,
        label: "Emergency stop daemon",
        separator_before: true,
    },
    MenuEntry {
        action: TrayAction::Exit,
        label: "Exit tray (daemon keeps running)",
        separator_before: true,
    },
];

pub const fn menu_entries() -> &'static [MenuEntry] {
    &MENU_ENTRIES
}

pub fn cli_candidates(explicit: Option<&Path>, current_exe: &Path, home: &Path) -> Vec<PathBuf> {
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

pub fn cmd_k_raw_args(cli: &Path, args: &[&str]) -> String {
    let mut command = format!("\"{}\"", cli.display().to_string().replace('"', "\"\""));
    for arg in args {
        command.push(' ');
        command.push_str(arg);
    }
    format!("/D /K \"{command}\"")
}

#[derive(Clone, Copy, Debug, Default)]
pub struct DaemonMenuInput<'a> {
    pub state: Option<&'a str>,
    pub updated_at: Option<u64>,
    pub now: u64,
    pub pid_alive: bool,
    pub privilege: Option<&'a str>,
    pub username: Option<&'a str>,
    pub computer_use_enabled: bool,
    pub grant_active: bool,
    pub grant_mode: Option<&'a str>,
    pub grant_expires_at: Option<&'a str>,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct DaemonMenuState {
    pub label: String,
    pub start_enabled: bool,
    pub stop_enabled: bool,
    pub restart_enabled: bool,
    pub elevated_action_label: String,
    pub elevated_action_enabled: bool,
    pub computer_use_label: String,
    pub computer_use_enabled: bool,
    pub computer_use_action_label: String,
    pub grant_label: String,
    pub cancel_grant_enabled: bool,
    pub safety_label: String,
}

pub fn daemon_menu_state(input: DaemonMenuInput<'_>) -> DaemonMenuState {
    let has_status = input.state.is_some() && input.updated_at.is_some();
    let running = has_status && input.pid_alive;
    let stale = has_status
        && input
            .updated_at
            .is_some_and(|updated| input.now.saturating_sub(updated) > 90);
    let administrator = input.privilege == Some("administrator");

    let identity = if let Some(username) = input.username {
        let privilege = match input.privilege {
            Some("administrator") => "Administrator",
            Some("user") => "User",
            _ => "Privilege unknown",
        };
        format!(" as {username} ({privilege})")
    } else {
        String::new()
    };

    let state = input.state.unwrap_or("stopped");
    let label = if !has_status {
        "Daemon: stopped".to_string()
    } else if !input.pid_alive {
        format!("Daemon: status stale (process stopped; last: {state})")
    } else if stale {
        format!("Daemon: status stale (last: {state}{identity})")
    } else {
        format!("Daemon: {state}{identity}")
    };

    let (elevated_action_label, elevated_action_enabled) = if running && administrator {
        ("Daemon already running as Administrator".to_string(), false)
    } else if running {
        ("Restart daemon as Administrator...".to_string(), true)
    } else {
        ("Start daemon as Administrator...".to_string(), true)
    };

    let computer_use_label = if input.computer_use_enabled {
        "Desktop use: enabled".to_string()
    } else {
        "Desktop use: disabled".to_string()
    };
    let computer_use_action_label = if input.computer_use_enabled {
        "Disable desktop use".to_string()
    } else {
        "Enable desktop use...".to_string()
    };
    let grant_label = if input.grant_active {
        let mode = input.grant_mode.unwrap_or("unknown");
        let expiry = input
            .grant_expires_at
            .and_then(|value| value.get(11..16))
            .map(|value| format!(" until {value} UTC"))
            .unwrap_or_default();
        format!("Active grant: {mode}{expiry}")
    } else {
        "Active grant: none".to_string()
    };
    let input_grant =
        input.grant_active && matches!(input.grant_mode, Some("assist") | Some("control"));
    let safety_label = if administrator && input_grant {
        "WARNING: Administrator control grant active".to_string()
    } else if administrator && input.computer_use_enabled {
        "Safety: Administrator daemon; input grants run elevated".to_string()
    } else {
        "Safety: task-scoped grants required for host input".to_string()
    };

    DaemonMenuState {
        label,
        start_enabled: !running,
        stop_enabled: running,
        restart_enabled: running,
        elevated_action_label,
        elevated_action_enabled,
        computer_use_label,
        computer_use_enabled: input.computer_use_enabled,
        computer_use_action_label,
        grant_label,
        cancel_grant_enabled: input.grant_active,
        safety_label,
    }
}
