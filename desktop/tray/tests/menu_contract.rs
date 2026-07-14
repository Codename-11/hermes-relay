use std::path::Path;

use hermes_relay_tray::{
    cli_candidates, cmd_k_raw_args, daemon_menu_state, menu_entries, DaemonMenuInput, TrayAction,
};

#[test]
fn tray_contract_is_context_menu_only() {
    let entries = menu_entries();
    let actions = entries.iter().map(|entry| entry.action).collect::<Vec<_>>();

    for required in [
        TrayAction::OpenTui,
        TrayAction::Status,
        TrayAction::StartDaemon,
        TrayAction::ElevateDaemon,
        TrayAction::StopDaemon,
        TrayAction::RestartDaemon,
        TrayAction::Pair,
        TrayAction::ReviewGrants,
        TrayAction::OpenLogs,
        TrayAction::Diagnostics,
        TrayAction::ToggleStartup,
        TrayAction::ToggleComputerUse,
        TrayAction::CancelComputerGrant,
        TrayAction::EmergencyStop,
        TrayAction::Exit,
    ] {
        assert!(
            actions.contains(&required),
            "missing tray action: {required:?}"
        );
    }

    let labels = entries
        .iter()
        .map(|entry| entry.label.to_ascii_lowercase())
        .collect::<Vec<_>>()
        .join(" ");
    for forbidden in [
        "chat",
        "sessions",
        "plugins",
        "voice",
        "settings",
        "dashboard",
    ] {
        assert!(
            !labels.contains(forbidden),
            "menu regrew a desktop surface: {forbidden}"
        );
    }
}

#[test]
fn daemon_menu_state_is_pid_and_privilege_aware() {
    let stopped = daemon_menu_state(DaemonMenuInput::default());
    assert_eq!(stopped.label, "Daemon: stopped");
    assert!(stopped.start_enabled);
    assert!(!stopped.stop_enabled);

    let user = daemon_menu_state(DaemonMenuInput {
        state: Some("connected"),
        updated_at: Some(1_000),
        now: 1_010,
        pid_alive: true,
        privilege: Some("user"),
        username: Some("ExampleUser"),
        computer_use_enabled: true,
        grant_active: true,
        grant_mode: Some("control"),
        grant_expires_at: Some("2026-07-14T01:25:00.000Z"),
    });
    assert_eq!(user.label, "Daemon: connected as ExampleUser (User)");
    assert!(!user.start_enabled);
    assert!(user.stop_enabled);
    assert_eq!(
        user.elevated_action_label,
        "Restart daemon as Administrator..."
    );
    assert!(user.elevated_action_enabled);
    assert_eq!(user.computer_use_label, "Desktop use: enabled");
    assert_eq!(user.grant_label, "Active grant: control until 01:25 UTC");
    assert!(user.cancel_grant_enabled);

    let admin = daemon_menu_state(DaemonMenuInput {
        privilege: Some("administrator"),
        computer_use_enabled: true,
        grant_active: true,
        grant_mode: Some("control"),
        grant_expires_at: Some("2026-07-14T01:25:00.000Z"),
        ..user_input()
    });
    assert_eq!(
        admin.label,
        "Daemon: connected as ExampleUser (Administrator)"
    );
    assert!(!admin.elevated_action_enabled);
    assert_eq!(
        admin.safety_label,
        "WARNING: Administrator control grant active"
    );

    let stale = daemon_menu_state(DaemonMenuInput {
        updated_at: Some(800),
        ..user_input()
    });
    assert!(stale.label.starts_with("Daemon: status stale"));
    assert!(!stale.start_enabled, "a live PID must not be started twice");
    assert!(stale.stop_enabled);
}

fn user_input() -> DaemonMenuInput<'static> {
    DaemonMenuInput {
        state: Some("connected"),
        updated_at: Some(1_000),
        now: 1_010,
        pid_alive: true,
        privilege: Some("user"),
        username: Some("ExampleUser"),
        computer_use_enabled: false,
        grant_active: false,
        grant_mode: None,
        grant_expires_at: None,
    }
}

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
fn terminal_command_uses_cmd_outer_quotes_for_paths_with_spaces() {
    let cli = Path::new(r"C:\Users\Test User\.hermes\bin\hermes-relay.exe");
    assert_eq!(
        cmd_k_raw_args(cli, &["grants"]),
        r#"/D /K ""C:\Users\Test User\.hermes\bin\hermes-relay.exe" grants""#
    );
}
