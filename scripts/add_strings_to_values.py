import os, sys

strings_path = r'D:\clawtemp\hermes-relay-cn\app\src\main\res\values\strings.xml'

# Read existing content
with open(strings_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Remove closing resources tag
if '</resources>\n' in content:
    content = content.replace('</resources>\n', '')
elif '</resources>' in content:
    content = content.replace('</resources>', '')

# Append new strings and close
content += """
    <!-- DestructiveVerbConfirmDialog -->
    <string name="destructive_confirm_title">Confirm destructive action</string>
    <string name="destructive_confirm_warning">If you didn't expect this, tap Deny.</string>
    <string name="destructive_confirm_dont_ask">Don't ask again for &quot;%1$s&quot;</string>
    <string name="destructive_confirm_deny">Deny</string>
    <string name="destructive_confirm_allow">Allow this action</string>
    <string name="destructive_label_tap_text">Target text (tap)</string>
    <string name="destructive_label_type">Text to type</string>
    <string name="destructive_label_payload">Payload</string>
    <string name="destructive_phrase_tap">tap a button containing</string>
    <string name="destructive_phrase_type">type text containing</string>
    <string name="destructive_phrase_action">perform an action with</string>
    <string name="destructive_phrase_keyword">a destructive keyword</string>
    <string name="destructive_phrase_word">the word &quot;%1$s&quot;</string>
    <string name="destructive_body_format">%1$s %2$s</string>
    <string name="destructive_no_text">(no text)</string>
    <string name="destructive_chip_unattended">Unattended ON</string>
    <string name="destructive_chip_active">Hermes active</string>

    <!-- InsecureConnectionAckDialog -->
    <string name="insecure_ack_title">Allow insecure connections?</string>
    <string name="insecure_ack_body_1">Insecure mode lets this app connect over plain ws:// and http://. Anyone on the network between your phone and the server can read your chat messages, session tokens, and terminal traffic.</string>
    <string name="insecure_ack_body_2">Only use this on networks you control. Pick the reason that best describes your setup:</string>
    <string name="insecure_ack_confirm">I understand</string>
    <string name="insecure_ack_cancel">Cancel</string>
    <string name="insecure_ack_reason_lan">LAN only (trusted network)</string>
    <string name="insecure_ack_reason_tailscale">Tailscale or VPN</string>
    <string name="insecure_ack_reason_dev">Local development only</string>

    <!-- SessionTtlPickerDialog -->
    <string name="ttl_title">Keep this pairing for&hellip;</string>
    <string name="ttl_body">Your phone will reconnect automatically during this window. After it expires, you'll need to pair again to use Relay features.</string>
    <string name="ttl_pair">Pair</string>
    <string name="ttl_cancel">Cancel</string>
    <string name="ttl_option_1d">1 day</string>
    <string name="ttl_option_7d">7 days</string>
    <string name="ttl_option_30d">30 days</string>
    <string name="ttl_option_90d">90 days</string>
    <string name="ttl_option_1y">1 year</string>
    <string name="ttl_option_never">Never expire</string>
    <string name="ttl_never_expire_warning">This device will stay paired until you revoke it manually.</string>
    <string name="ttl_helper_tailscale">Transport: Tailscale detected</string>
    <string name="ttl_helper_tls">Transport: TLS (wss://)</string>
    <string name="ttl_helper_plain">Transport: plain ws://</string>

    <!-- PowerFeatureGate -->
    <string name="power_gate_requires_pairing">Requires pairing</string>
    <string name="power_gate_pair_to_unlock">Pair to unlock</string>
    <string name="power_gate_pairing_expired">Pairing expired</string>
    <string name="power_gate_pair_again">Pair again</string>
    <string name="power_gate_unavailable">Unavailable on this server</string>
    <string name="power_gate_view_connection">View connection</string>
    <string name="power_gate_signin_required">Dashboard sign-in required</string>
    <string name="power_gate_open_signin">Open sign-in</string>
    <string name="power_gate_explanation_pairing">This feature requires the Relay plugin to be installed on your Hermes server.</string>
    <string name="power_gate_explanation_expired">Your relay session is no longer accepted by the server. Open the connection to repair.</string>
    <string name="power_gate_explanation_unavailable">This Hermes server isn't exposing the surface needed for this feature.</string>
    <string name="power_gate_explanation_signin">This standard dashboard feature needs dashboard sign-in before it can be used.</string>

    <!-- AgentTextFlow / CleanModeComposer -->
    <string name="agent_text_placeholder">Message</string>

    <!-- ExtraKeysToolbar -->
    <string name="extra_keys_esc">ESC</string>
    <string name="extra_keys_tab">TAB</string>
    <string name="extra_keys_ctrl">CTRL</string>
    <string name="extra_keys_alt">ALT</string>
    <string name="extra_keys_copy">COPY</string>
    <string name="extra_keys_paste">PASTE</string>

    <!-- ToolProgressCard -->
    <string name="tool_progress_preparing">Preparing tool&hellip;</string>
    <string name="tool_progress_arguments">Arguments:</string>
    <string name="tool_progress_error">Error:</string>
    <string name="tool_progress_result">Result:</string>
    <string name="tool_progress_status_completed">completed</string>
    <string name="tool_progress_status_failed">failed</string>
    <string name="tool_progress_status_running">running</string>
    <string name="tool_progress_cd_collapse">Collapse</string>
    <string name="tool_progress_cd_expand">Expand</string>

    <!-- AgentIconRow -->
    <string name="agent_icon_title">Agent icon</string>
    <string name="agent_icon_set">Set image</string>
    <string name="agent_icon_change">Change</string>
    <string name="agent_icon_clear">Clear</string>
    <string name="agent_icon_description">Shown beside this profile's name in chat. Stays on this device &mdash; never sent to Hermes.</string>

    <!-- ConnectionSecuritySheet -->
    <string name="conn_security_title">Connection security</string>
    <string name="conn_security_empty">No active route yet. Connect to a server to see how each part of the connection is protected.</string>
    <string name="conn_security_learn_more">Learn about connection security &rarr;</string>

    <!-- ConnectionSwitcherSheet -->
    <string name="conn_switcher_title">Switch connection</string>
    <string name="conn_switcher_empty">No connections yet</string>
    <string name="conn_switcher_manage">Manage connections&hellip;</string>

    <!-- DiagnosticsLogPanel -->
    <string name="diagnostics_title">Recent activity</string>
    <string name="diagnostics_clear">Clear</string>
    <string name="diagnostics_all">All</string>
    <string name="diagnostics_empty">No recent activity</string>

    <!-- HermesCardBubble -->
    <string name="hermes_card_hide_value">Hide value</string>
    <string name="hermes_card_reveal_value">Reveal value</string>
    <string name="hermes_card_not_stored">Not stored in chat history</string>
    <string name="hermes_card_send_answer">Send answer</string>
    <string name="hermes_card_submit">Submit</string>
    <string name="hermes_card_hold_confirm">Hold to confirm</string>
    <string name="hermes_card_type_answer">Type an answer&hellip;</string>
    <string name="hermes_card_chose">Chose: %1$s</string>
    <string name="hermes_card_secret_provided">Secret provided &middot; &bull;&bull;&bull;&bull;</string>
    <string name="hermes_card_answered">Answered: %1$s</string>
    <string name="hermes_card_expired">Expired &mdash; not granted</string>

    <!-- ModelPickerSheet -->
    <string name="model_picker_title">Model</string>
    <string name="model_picker_search">Search models or providers&hellip;</string>
    <string name="model_picker_empty">No models match your search</string>

    <!-- InjectedContextSheet -->
    <string name="context_sheet_agent_sees">What the agent sees</string>
    <string name="context_sheet_transparency">The exact extra context prepended to your next turn, for transparency.</string>
    <string name="context_sheet_persona">Persona / profile</string>
    <string name="context_sheet_phone_status">Phone status</string>
    <string name="context_sheet_media_capability">Media capability</string>
    <string name="context_sheet_relay_context">Relay context (server-side)</string>
    <string name="context_sheet_this_turn">This turn</string>
    <string name="context_sheet_transport_desc">Transport: %1$s.</string>

    <!-- InboundAttachmentCard -->
    <string name="inbound_attach_tap_download">Tap to download</string>
    <string name="inbound_attach_downloading">Downloading&hellip;%1$s</string>
    <string name="inbound_attach_failed">Attachment failed</string>
    <string name="inbound_attach_tap_retry">Tap to retry</string>
    <string name="inbound_attach_open">Open externally</string>
    <string name="inbound_attach_share">Share</string>
    <string name="inbound_attach_save">Save to device</string>
    <string name="inbound_attach_cd_save">Save</string>
    <string name="inbound_attach_type_image">Image</string>
    <string name="inbound_attach_type_video">Video</string>
    <string name="inbound_attach_type_audio">Audio</string>
    <string name="inbound_attach_type_pdf">PDF</string>
    <string name="inbound_attach_type_text">Text</string>
    <string name="inbound_attach_type_file">File</string>
    <string name="inbound_attach_read_failed">Couldn't read this file</string>
    <string name="inbound_attach_saved">Saved to %1$s</string>
    <string name="inbound_attach_save_failed">Save failed: %1$s</string>

    <!-- AttachmentViewer -->
    <string name="attachment_sensitive">Sensitive</string>
    <string name="attachment_tap_reveal">Tap to reveal</string>
    <string name="attachment_cd_close">Close</string>
    <string name="attachment_cd_open">Open externally</string>
    <string name="attachment_cd_share">Share</string>
    <string name="attachment_cd_save">Save</string>
    <string name="attachment_no_preview">No in-app preview for this type.</string>
    <string name="attachment_open_ext">Open externally</string>
    <string name="attachment_load_failed">Couldn't load this image</string>
    <string name="attachment_preparing_video">Preparing video&hellip;</string>
    <string name="attachment_preparing_audio">Preparing audio&hellip;</string>
    <string name="attachment_audio_label">Audio</string>
    <string name="attachment_pause">Pause</string>
    <string name="attachment_play">Play</string>
    <string name="attachment_unmute">Unmute</string>
    <string name="attachment_mute">Mute</string>
    <string name="attachment_title">Attachment</string>

    <!-- CrashReportDialog -->
    <string name="crash_title">Hermes-Relay closed unexpectedly</string>
    <string name="crash_body">The last session crashed. Sending this report helps get it fixed faster.</string>
    <string name="crash_dismiss">Dismiss</string>
    <string name="crash_copy">Copy</string>
    <string name="crash_share">Share</string>
    <string name="crash_report">Report</string>
    <string name="crash_toast_copied">Crash report copied</string>

    <!-- QrPairingScanner -->
    <string name="qr_scanner_title">Scan Hermes QR</string>
    <string name="qr_scanner_instruction">Scan a Hermes setup QR</string>
    <string name="qr_scanner_subtext">Ask Hermes: &quot;Generate a QR code with my API URL and API key.&quot;</string>
    <string name="qr_scanner_camera_error">Couldn't start the camera on this device.</string>
    <string name="qr_scanner_fallback_message">You can pair without the camera.</string>
    <string name="qr_scanner_pair_manual">Pair manually</string>

    <!-- SphereSkin -->
    <string name="sphere_skin_adaptive">Adaptive</string>
    <string name="sphere_skin_classic">Classic</string>
    <string name="sphere_skin_aurora">Aurora</string>
    <string name="sphere_skin_solar">Solar</string>
    <string name="sphere_skin_mono">Mono</string>
    <string name="sphere_skin_adaptive_desc">Follows your theme's colors</string>
    <string name="sphere_skin_classic_desc">The original green-violet orb</string>
    <string name="sphere_reactivity_voice">Voice</string>
    <string name="sphere_reactivity_tools">Tools</string>
    <string name="sphere_reactivity_activity">Activity</string>
    <string name="sphere_reactivity_gaze">Gaze</string>
    <string name="sphere_reactivity_static">Static</string>

    <!-- TimelineView -->
    <string name="timeline_header">Timeline</string>
    <string name="timeline_status_checks">Status checks</string>
    <string name="timeline_empty_activity">No activity yet</string>
    <string name="timeline_empty_checks">No checks yet &mdash; connect to a server to populate diagnostics.</string>
    <string name="timeline_legend_chat">Chat</string>
    <string name="timeline_legend_tool">Tool</string>
    <string name="timeline_legend_voice">Voice</string>
    <string name="timeline_legend_profile">Profile</string>
    <string name="timeline_legend_conn">Conn</string>
    <string name="timeline_legend_pass">Pass</string>
    <string name="timeline_legend_warn">Warn</string>
    <string name="timeline_legend_fail">Fail</string>
    <string name="timeline_legend_unknown">Unknown</string>
    <string name="timeline_tap_detail">Tap for log detail</string>
    <string name="timeline_pass">PASS</string>
    <string name="timeline_warn">WARN</string>
    <string name="timeline_fail">FAIL</string>
    <string name="timeline_status_unknown">UNKNOWN</string>

    <!-- CommandPalette -->
    <string name="command_palette_header">Commands</string>
    <string name="command_palette_search">Search commands&hellip;</string>
    <string name="command_palette_cd_clear">Clear search</string>
    <string name="command_palette_filter_all">All</string>
    <string name="command_palette_show_less">Show less</string>
    <string name="command_palette_show_all">Show all (%1$d)</string>
    <string name="command_palette_empty">No commands match your search</string>

    <!-- TransportSecurityBadge -->
    <string name="transport_badge_secure_tls">Secure &mdash; TLS</string>
    <string name="transport_badge_mixed">Mixed &mdash; secure fallback available</string>
    <string name="transport_badge_plain">All routes plain &mdash; dev only</string>
    <string name="transport_badge_secure_tls_short">Secure (TLS)</string>
    <string name="transport_badge_insecure_lan">Plain (on LAN)</string>
    <string name="transport_badge_insecure_tailscale">Plain (on Tailscale)</string>
    <string name="transport_badge_insecure_public">Plain (on public URL)</string>
    <string name="transport_badge_insecure_lan_only">Plain (LAN only)</string>
    <string name="transport_badge_encrypted_tls">Encrypted &middot; TLS</string>
    <string name="transport_badge_encrypted_tailscale">Encrypted &middot; Tailscale</string>
    <string name="transport_badge_mixed_routes">Mixed routes</string>
    <string name="transport_badge_not_encrypted">Not encrypted</string>
    <string name="transport_badge_checking">Checking&hellip;</string>
    <string name="transport_badge_glyph_tls">Encrypted (TLS)</string>
    <string name="transport_badge_glyph_encrypted">Encrypted</string>
    <string name="transport_badge_glyph_not_encrypted">Not encrypted</string>

    <!-- MessageBubble -->
    <string name="msg_bubble_copy">Copy</string>
    <string name="msg_bubble_quote">Quote in reply</string>
    <string name="msg_bubble_edit">Edit &amp; resend</string>
    <string name="msg_bubble_reconnecting">Reconnecting to your answer&hellip;</string>
    <string name="msg_bubble_still_working">Still working&hellip;</string>
    <string name="msg_bubble_sending">Sending&hellip;</string>
    <string name="msg_bubble_delivered">Delivered</string>
    <string name="msg_bubble_not_sent">Not sent</string>

    <!-- ProfileInspectorCard -->
    <string name="profile_inspector_card_title">Inspect Agent</string>
    <string name="profile_inspector_card_subtitle">View config, SOUL, memory, skills</string>
    <string name="profile_inspector_card_no_agent">No active agent</string>

    <!-- TerminalSearchBar -->
    <string name="term_search_placeholder">Search scrollback</string>
    <string name="term_search_cd_previous">Previous match</string>
    <string name="term_search_cd_next">Next match</string>
    <string name="term_search_cd_close">Close search</string>

    <!-- UpdateAvailableBanner -->
    <string name="update_banner_available">Update available</string>
    <string name="update_banner_downloading">Downloading update&hellip;</string>
    <string name="update_banner_ready">Update ready &mdash; restart</string>
    <string name="update_banner_update">Update</string>
    <string name="update_banner_restart">Restart</string>

    <!-- UpdateBanner -->
    <string name="update_banner_cd_dismiss">Dismiss</string>

    <!-- VoiceOverlayHost -->
    <string name="voice_overlay_title">Voice Overlay</string>
    <string name="voice_overlay_minimize">Minimize</string>
    <string name="voice_overlay_hermes">Hermes</string>
    <string name="voice_overlay_hide">Hide</string>
    <string name="voice_overlay_exit">Exit</string>
    <string name="voice_overlay_cd_exit">Exit voice mode</string>
    <string name="voice_overlay_cd_mic">Voice overlay mic</string>
    <string name="voice_overlay_state_ready">Ready</string>
    <string name="voice_overlay_state_listening">Listening</string>
    <string name="voice_overlay_state_transcribing">Transcribing</string>
    <string name="voice_overlay_state_thinking">Thinking</string>
    <string name="voice_overlay_state_speaking">Speaking</string>
    <string name="voice_overlay_state_error">Error</string>
    <string name="voice_overlay_bubble_label_ready">Ready</string>
    <string name="voice_overlay_bubble_label_listen">Listen</string>
    <string name="voice_overlay_bubble_label_stt">STT</string>
    <string name="voice_overlay_bubble_label_think">Think</string>
    <string name="voice_overlay_bubble_label_speak">Speak</string>
    <string name="voice_overlay_bubble_label_error">Error</string>
    <string name="voice_overlay_tap_action_idle">start listening</string>
    <string name="voice_overlay_tap_action_listening">stop listening</string>
    <string name="voice_overlay_tap_action_pause">pause auto mode</string>
    <string name="voice_overlay_tap_action_interrupt">interrupt</string>
    <string name="voice_overlay_cd_description">Voice overlay %1$s. Tap to %2$s, double tap to expand.</string>
    <string name="voice_overlay_label_default_profile">default profile</string>
    <string name="voice_overlay_provider_output_off">output off</string>
    <string name="voice_overlay_provider_placeholder">provider ...</string>
    <string name="voice_overlay_engine_realtime">Realtime Agent</string>
    <string name="voice_overlay_engine_hermes">Hermes voice</string>
    <string name="voice_overlay_engine_placeholder">Voice engine ...</string>

    <!-- AppTheme labels + descriptions -->
    <string name="theme_hermes_relay">Hermes Relay</string>
    <string name="theme_hermes_relay_desc">The signature electric-blue brand &mdash; follows light/dark</string>
    <string name="theme_hermes_teal">Hermes Teal</string>
    <string name="theme_hermes_teal_desc">Classic dark teal &mdash; the canonical Hermes look</string>
    <string name="theme_nous_blue">Nous Blue</string>
    <string name="theme_nous_blue_desc">Light mode &mdash; vivid Nous-blue accents on a cream canvas</string>
    <string name="theme_midnight">Midnight</string>
    <string name="theme_midnight_desc">Deep blue-violet with cool accents</string>
    <string name="theme_ember">Ember</string>
    <string name="theme_ember_desc">Warm crimson and bronze &mdash; forge vibes</string>
    <string name="theme_mono">Mono</string>
    <string name="theme_mono_desc">Clean grayscale &mdash; minimal and focused</string>
    <string name="theme_cyberpunk">Cyberpunk</string>
    <string name="theme_cyberpunk_desc">Neon green on black &mdash; matrix terminal</string>
    <string name="theme_rose">Ros&eacute;</string>
    <string name="theme_rose_desc">Soft pink and warm ivory &mdash; easy on the eyes</string>

    <!-- ChatScreen hardcoded strings -->
    <string name="chat_slash_new">Start a new session</string>
    <string name="chat_slash_retry_label">Retry the last message</string>
    <string name="chat_slash_undo">Remove the last exchange</string>
    <string name="chat_slash_title_txt">Set a title for this session</string>
    <string name="chat_slash_branch">Branch/fork the current session</string>
    <string name="chat_slash_compress">Compress conversation context</string>
    <string name="chat_slash_rollback">List or restore checkpoints</string>
    <string name="chat_slash_stop">Kill running background processes</string>
    <string name="chat_slash_resume">Resume a previous session</string>
    <string name="chat_slash_background">Run a prompt in the background</string>
    <string name="chat_slash_btw">Side question using session context</string>
    <string name="chat_slash_queue_label">Queue a prompt for the next turn</string>
    <string name="chat_slash_approve">Approve a pending command</string>
    <string name="chat_slash_deny">Deny a pending command</string>
    <string name="chat_slash_model_label">Switch model for this session</string>
    <string name="chat_slash_provider">Show available providers</string>
    <string name="chat_slash_personality_label">Set a predefined personality</string>
    <string name="chat_slash_verbose">Cycle tool progress display</string>
    <string name="chat_slash_yolo">Toggle auto-approve mode</string>
    <string name="chat_slash_reasoning">Set reasoning effort level</string>
    <string name="chat_slash_voice_label">Toggle voice mode</string>
    <string name="chat_slash_reload_mcp">Reload MCP servers</string>
    <string name="chat_slash_help">Show available commands</string>
    <string name="chat_slash_status_label">Show session info</string>
    <string name="chat_slash_usage">Show token usage</string>
    <string name="chat_slash_insights">Usage analytics</string>
    <string name="chat_slash_commands_label">Browse all commands</string>
    <string name="chat_slash_profile_label">Show active profile</string>
    <string name="chat_slash_category_session">session</string>
    <string name="chat_slash_category_config">configuration</string>
    <string name="chat_slash_category_info">info</string>
    <string name="chat_prompt_help_me_code">Help me code</string>
    <string name="chat_prompt_explain">Explain something</string>
    <string name="chat_prompt_what_can_you_do">What can you do?</string>
    <string name="chat_connect_to_hermes_dots">Connecting to Hermes...</string>
    <string name="chat_needs_connection">Connect to Hermes</string>
    <string name="chat_streaming">Streaming</string>
    <string name="chat_connected_label">Connected</string>
    <string name="chat_reconnecting_dots">Reconnecting&hellip;</string>
    <string name="chat_connecting_dots">Connecting&hellip;</string>
    <string name="chat_disconnected_label">Disconnected</string>
    <string name="chat_server_default_sessions">Server default sessions</string>
    <string name="chat_active_connection">Active connection</string>
    <string name="chat_compatibility_overlay">Compatibility overlay on %1$s</string>
    <string name="chat_connection_label">Connection: %1$s</string>
    <string name="chat_file_too_large">File too large (max %1$d MB)</string>
    <string name="chat_prompt_chat_with">Chat with %1$s</string>
    <string name="chat_hermes_message">Hermes message</string>
    <string name="chat_start_conversation">Start a conversation</string>
    <string name="chat_not_on_plan">Not on your plan</string>
    <string name="chat_needs_setup">Needs setup</string>
    <string name="chat_model_label">Model</string>
    <string name="chat_reasoning_none">None</string>
    <string name="chat_reasoning_minimal">Minimal</string>
    <string name="chat_reasoning_low">Low</string>
    <string name="chat_reasoning_medium">Medium</string>
    <string name="chat_reasoning_high">High</string>
    <string name="chat_date_today">Today</string>
    <string name="chat_date_yesterday">Yesterday</string>
    <string name="chat_share_subject">Hermes conversation</string>
</resources>
"""

with open(strings_path, 'w', encoding='utf-8') as f:
    f.write(content)

print('OK - all strings written to values/strings.xml')