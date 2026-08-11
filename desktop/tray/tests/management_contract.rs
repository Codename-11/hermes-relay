#[test]
fn native_context_menu_stays_small_and_management_focused() {
    let source = include_str!("../src/main.rs");

    for required in [
        "Open Hermes-Relay CLI UI",
        "Restart daemon",
        "Emergency stop daemon",
        "Quit tray",
    ] {
        assert!(
            source.contains(required),
            "missing context-menu action: {required}"
        );
    }

    for removed in [
        "Open Hermes Relay TUI",
        "Review pending grants",
        "Recent tool activity",
        "Run diagnostics",
        "Enable desktop use",
    ] {
        assert!(
            !source.contains(removed),
            "legacy oversized-menu action remains in the native context menu: {removed}"
        );
    }

    assert!(source.contains("acquire_instance_mutex"));
}

#[test]
fn management_window_owns_the_expected_narrow_surfaces() {
    let source = include_str!("../ui/App.tsx");

    for required in [
        "Overview",
        "Hosts",
        "Settings",
        "Authorized clients",
        "Hermes-Relay CLI UI",
        "check_desktop_update",
        "install_desktop_update",
        "ActivityPanel",
        "Recent events",
        "Issues",
        "Non-zero",
        "Clear activity",
        "HostDetailPage",
        "rename_host",
        "duration_ms",
        "exit_code",
        "pending_grants",
        "resolve_grant",
    ] {
        assert!(
            source.contains(required),
            "missing management surface: {required}"
        );
    }

    for excluded in ["xterm", "Plugin manager", "Voice settings", "Chat sessions"] {
        assert!(
            !source.contains(excluded),
            "out-of-scope desktop-client surface returned: {excluded}"
        );
    }
}

#[test]
fn grants_use_the_dedicated_card_and_host_changes_reconcile_daemon_truth() {
    let ui = include_str!("../ui/App.tsx");
    let native = include_str!("../src/main.rs");
    let config = include_str!("../tauri.conf.json");
    let config_json: serde_json::Value = serde_json::from_str(config).expect("valid Tauri config");
    let grant_window = config_json["app"]["windows"]
        .as_array()
        .and_then(|windows| windows.iter().find(|window| window["label"] == "grant"))
        .expect("grant window configuration");

    assert!(ui.contains("isGrantWindow ? <GrantWindow /> : <ManagementApp />"));
    assert!(ui.contains("present_grant_window"));
    assert!(ui.contains("Remote access request"));
    assert!(native.contains("start_grant_watcher"));
    assert!(native.contains("get_webview_window(\"grant\")"));
    assert!(!native.contains("get_webview_window(\"main\").show"));
    assert_eq!(grant_window["alwaysOnTop"], true);
    assert_eq!(
        grant_window["backgroundColor"],
        serde_json::json!([0, 0, 0, 0])
    );
    assert_eq!(grant_window["shadow"], false);
    assert!(ui.contains("snapshot.daemon.url === host.url"));
    assert!(ui.contains("formatGrantScope(grant.scope)"));
    assert!(native.contains("restart_daemon_if_running"));
    assert!(native.contains("management.completed"));
    assert!(native.contains("append_management_event"));
    assert!(native.contains("fn clear_activity"));
    assert!(native.contains("skip_serializing_if = \"Option::is_none\""));
    assert!(native.contains("popup_position"));
    assert!(native.contains("physical_window_size"));
    assert!(native.contains("run_cli_checked(&[\"daemon\", \"restart\"])"));
    assert!(native.contains("start_tray_action_worker"));
    assert!(native.contains("mpsc::channel::<TrayAction>()"));
    assert!(native.contains("app.run_on_main_thread"));
    assert!(native.contains("async fn get_snapshot"));
    assert!(native.contains("async fn check_desktop_update"));
    assert!(native.contains("async fn install_desktop_update"));
    assert!(
        native.contains("SHA256SUMS")
            || include_str!("../../src/updater.ts").contains("SHA256SUMS")
    );
    assert!(native.contains("fn request_main_hide"));
    assert!(native.contains("let _ = window.hide();"));
    assert!(native.contains("GetCursorPos"));
    assert!(native.contains("DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2"));
    assert!(native.contains("tray_event_anchor(rect.position, rect.size)"));
    assert!(native.contains("current_tray_anchor(handle)"));
    assert!(native.contains("tray_by_id(\"hermes-relay\")"));
    assert!(native.contains("tray.rect()"));
    assert!(native.contains("notification_area_anchor()"));
    assert!(native.contains("UIA_AutomationIdPropertyId"));
    assert!(native.contains("NotifyItemIcon"));
    assert!(native.contains("reveal_main_window(handle, anchor)"));
    assert!(native.contains("fn reveal_existing_instance"));
    assert!(native.contains("activation_request_path"));
    assert!(native.contains("start_activation_watcher"));
    assert!(native.contains("tray-show-request"));
    assert!(native.contains("MonitorFromPoint"));
    assert!(native.contains("GetMonitorInfoW"));
    assert!(!native.contains("window.available_monitors()"));
    assert!(!native.contains("let Ok(Some(monitor)) = window.monitor_from_point"));
}

#[test]
fn release_build_embeds_the_ui_instead_of_using_the_vite_server() {
    let cargo = include_str!("../Cargo.toml");
    let build = include_str!("../../scripts/build-tray-installer.mjs");
    let dev_install = include_str!("../../scripts/dev-install-tray.mjs");

    assert!(cargo.contains("custom-protocol = [\"tauri/custom-protocol\"]"));
    assert!(build.contains("'--features', 'custom-protocol'"));
    assert!(dev_install.contains("'--features', 'custom-protocol'"));
}

#[test]
fn management_window_keeps_the_reviewed_compact_geometry() {
    let config = include_str!("../tauri.conf.json");
    let ui = include_str!("../ui/App.tsx");
    let capability = include_str!("../capabilities/default.json");

    assert!(config.contains("\"width\": 420"));
    assert!(config.contains("\"height\": 700"));
    assert!(config.contains("\"minWidth\": 390"));
    assert!(config.contains("\"resizable\": false"));
    assert_eq!(config.matches("\"alwaysOnTop\": true").count(), 2);
    assert!(!ui.contains("toggleMaximize"));
    assert!(!ui.contains("data-tauri-drag-region"));
    assert!(!capability.contains("allow-start-dragging"));
    assert!(ui.contains("useState(true)"));
    assert!(ui.contains("hide().finally(() => setWindowVisible(true))"));
    assert!(ui.contains("document.visibilityState === 'visible'"));
}
