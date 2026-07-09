import os, re

zh_path = r'D:\clawtemp\hermes-relay-cn\app\src\main\res\values-zh\strings.xml'

with open(zh_path, 'r', encoding='utf-8') as f:
    zh_content = f.read()

if '</resources>' in zh_content:
    zh_content = zh_content.replace('</resources>', '')

# Append Chinese translations for all new keys
zh_content += """
    <!-- DestructiveVerbConfirmDialog -->
    <string name="destructive_confirm_title">确认危险操作</string>
    <string name="destructive_confirm_warning">如果您没有预料到此操作，请点击拒绝。</string>
    <string name="destructive_confirm_dont_ask">不再询问 &quot;%1$s&quot;</string>
    <string name="destructive_confirm_deny">拒绝</string>
    <string name="destructive_confirm_allow">允许此操作</string>
    <string name="destructive_label_tap_text">目标文本（点击）</string>
    <string name="destructive_label_type">要输入的文本</string>
    <string name="destructive_label_payload">载荷</string>
    <string name="destructive_phrase_tap">点击包含以下内容的按钮</string>
    <string name="destructive_phrase_type">输入包含以下内容的文本</string>
    <string name="destructive_phrase_action">执行带有以下内容的操作</string>
    <string name="destructive_phrase_keyword">一个危险关键词</string>
    <string name="destructive_phrase_word">词语 &quot;%1$s&quot;</string>
    <string name="destructive_body_format">%1$s %2$s</string>
    <string name="destructive_no_text">（无文本）</string>
    <string name="destructive_chip_unattended">无人值守 ON</string>
    <string name="destructive_chip_active">Hermes 活跃</string>

    <!-- InsecureConnectionAckDialog -->
    <string name="insecure_ack_title">允许不安全连接？</string>
    <string name="insecure_ack_body_1">不安全模式允许此应用通过明文 ws:// 和 http:// 进行连接。您手机与服务器之间网络上的任何人都可以读取您的聊天消息、会话令牌和终端流量。</string>
    <string name="insecure_ack_body_2">仅在您控制的网络上使用。选择最能描述您配置的原因：</string>
    <string name="insecure_ack_confirm">我了解</string>
    <string name="insecure_ack_cancel">取消</string>
    <string name="insecure_ack_reason_lan">仅局域网（可信网络）</string>
    <string name="insecure_ack_reason_tailscale">Tailscale 或 VPN</string>
    <string name="insecure_ack_reason_dev">仅本地开发</string>

    <!-- SessionTtlPickerDialog -->
    <string name="ttl_title">将此配对保持…</string>
    <string name="ttl_body">您的手机将在此窗口内自动重新连接。到期后，您需要重新配对才能使用 Relay 功能。</string>
    <string name="ttl_pair">配对</string>
    <string name="ttl_cancel">取消</string>
    <string name="ttl_option_1d">1 天</string>
    <string name="ttl_option_7d">7 天</string>
    <string name="ttl_option_30d">30 天</string>
    <string name="ttl_option_90d">90 天</string>
    <string name="ttl_option_1y">1 年</string>
    <string name="ttl_option_never">永不过期</string>
    <string name="ttl_never_expire_warning">此设备将保持配对状态，直到您手动撤销。</string>
    <string name="ttl_helper_tailscale">传输：检测到 Tailscale</string>
    <string name="ttl_helper_tls">传输：TLS（wss://）</string>
    <string name="ttl_helper_plain">传输：明文 ws://</string>

    <!-- PowerFeatureGate -->
    <string name="power_gate_requires_pairing">需要配对</string>
    <string name="power_gate_pair_to_unlock">配对以解锁</string>
    <string name="power_gate_pairing_expired">配对已过期</string>
    <string name="power_gate_pair_again">重新配对</string>
    <string name="power_gate_unavailable">此服务器上不可用</string>
    <string name="power_gate_view_connection">查看连接</string>
    <string name="power_gate_signin_required">需要仪表盘登录</string>
    <string name="power_gate_open_signin">打开登录</string>
    <string name="power_gate_explanation_pairing">此功能需要在您的 Hermes 服务器上安装 Relay 插件。</string>
    <string name="power_gate_explanation_expired">您的 Relay 会话已不被服务器接受。打开连接重新配对。</string>
    <string name="power_gate_explanation_unavailable">此 Hermes 服务器未暴露此功能所需的接口。</string>
    <string name="power_gate_explanation_signin">此标准仪表盘功能需要在登录后才能使用。</string>

    <!-- AgentTextFlow / CleanModeComposer -->
    <string name="agent_text_placeholder">消息</string>

    <!-- ExtraKeysToolbar -->
    <string name="extra_keys_esc">ESC</string>
    <string name="extra_keys_tab">TAB</string>
    <string name="extra_keys_ctrl">CTRL</string>
    <string name="extra_keys_alt">ALT</string>
    <string name="extra_keys_copy">复制</string>
    <string name="extra_keys_paste">粘贴</string>

    <!-- ToolProgressCard -->
    <string name="tool_progress_preparing">正在准备工具…</string>
    <string name="tool_progress_arguments">参数：</string>
    <string name="tool_progress_error">错误：</string>
    <string name="tool_progress_result">结果：</string>
    <string name="tool_progress_status_completed">已完成</string>
    <string name="tool_progress_status_failed">失败</string>
    <string name="tool_progress_status_running">运行中</string>
    <string name="tool_progress_cd_collapse">折叠</string>
    <string name="tool_progress_cd_expand">展开</string>

    <!-- AgentIconRow -->
    <string name="agent_icon_title">代理图标</string>
    <string name="agent_icon_set">设置图像</string>
    <string name="agent_icon_change">更改</string>
    <string name="agent_icon_clear">清除</string>
    <string name="agent_icon_description">显示在此配置文件名称旁边。仅保留在此设备上，不会发送到 Hermes。</string>

    <!-- ConnectionSecuritySheet -->
    <string name="conn_security_title">连接安全</string>
    <string name="conn_security_empty">尚无活动路由。连接到服务器以查看连接的各个部分如何受到保护。</string>
    <string name="conn_security_learn_more">了解连接安全 →</string>

    <!-- ConnectionSwitcherSheet -->
    <string name="conn_switcher_title">切换连接</string>
    <string name="conn_switcher_empty">尚无连接</string>
    <string name="conn_switcher_manage">管理连接…</string>

    <!-- DiagnosticsLogPanel -->
    <string name="diagnostics_title">最近活动</string>
    <string name="diagnostics_clear">清除</string>
    <string name="diagnostics_all">全部</string>
    <string name="diagnostics_empty">暂无最近活动</string>

    <!-- HermesCardBubble -->
    <string name="hermes_card_hide_value">隐藏值</string>
    <string name="hermes_card_reveal_value">显示值</string>
    <string name="hermes_card_not_stored">不存储在聊天历史中</string>
    <string name="hermes_card_send_answer">发送回答</string>
    <string name="hermes_card_submit">提交</string>
    <string name="hermes_card_hold_confirm">长按确认</string>
    <string name="hermes_card_type_answer">输入答案…</string>
    <string name="hermes_card_chose">选择了：%1$s</string>
    <string name="hermes_card_secret_provided">已提供密钥 · ••••</string>
    <string name="hermes_card_answered">已回答：%1$s</string>
    <string name="hermes_card_expired">已过期 — 未授权</string>

    <!-- ModelPickerSheet -->
    <string name="model_picker_title">模型</string>
    <string name="model_picker_search">搜索模型或提供商…</string>
    <string name="model_picker_empty">没有匹配您搜索的模型</string>

    <!-- InjectedContextSheet -->
    <string name="context_sheet_agent_sees">代理看到的内容</string>
    <string name="context_sheet_transparency">为透明起见，附加到您下一轮对话前的确切额外上下文。</string>
    <string name="context_sheet_persona">角色 / 配置文件</string>
    <string name="context_sheet_phone_status">手机状态</string>
    <string name="context_sheet_media_capability">媒体能力</string>
    <string name="context_sheet_relay_context">Relay 上下文（服务端）</string>
    <string name="context_sheet_this_turn">本轮</string>
    <string name="context_sheet_transport_desc">传输：%1$s。</string>

    <!-- InboundAttachmentCard -->
    <string name="inbound_attach_tap_download">点击下载</string>
    <string name="inbound_attach_downloading">正在下载…%1$s</string>
    <string name="inbound_attach_failed">附件失败</string>
    <string name="inbound_attach_tap_retry">点击重试</string>
    <string name="inbound_attach_open">外部打开</string>
    <string name="inbound_attach_share">分享</string>
    <string name="inbound_attach_save">保存到设备</string>
    <string name="inbound_attach_cd_save">保存</string>
    <string name="inbound_attach_type_image">图片</string>
    <string name="inbound_attach_type_video">视频</string>
    <string name="inbound_attach_type_audio">音频</string>
    <string name="inbound_attach_type_pdf">PDF</string>
    <string name="inbound_attach_type_text">文本</string>
    <string name="inbound_attach_type_file">文件</string>
    <string name="inbound_attach_read_failed">无法读取此文件</string>
    <string name="inbound_attach_saved">已保存到 %1$s</string>
    <string name="inbound_attach_save_failed">保存失败：%1$s</string>

    <!-- AttachmentViewer -->
    <string name="attachment_sensitive">敏感内容</string>
    <string name="attachment_tap_reveal">点击显示</string>
    <string name="attachment_cd_close">关闭</string>
    <string name="attachment_cd_open">外部打开</string>
    <string name="attachment_cd_share">分享</string>
    <string name="attachment_cd_save">保存</string>
    <string name="attachment_no_preview">此类型不支持应用内预览。</string>
    <string name="attachment_open_ext">外部打开</string>
    <string name="attachment_load_failed">无法加载此图片</string>
    <string name="attachment_preparing_video">正在准备视频…</string>
    <string name="attachment_preparing_audio">正在准备音频…</string>
    <string name="attachment_audio_label">音频</string>
    <string name="attachment_pause">暂停</string>
    <string name="attachment_play">播放</string>
    <string name="attachment_unmute">取消静音</string>
    <string name="attachment_mute">静音</string>
    <string name="attachment_title">附件</string>

    <!-- CrashReportDialog -->
    <string name="crash_title">Hermes-Relay 意外关闭</string>
    <string name="crash_body">上次会话崩溃了。发送此报告有助于更快修复问题。</string>
    <string name="crash_dismiss">忽略</string>
    <string name="crash_copy">复制</string>
    <string name="crash_share">分享</string>
    <string name="crash_report">报告</string>
    <string name="crash_toast_copied">崩溃报告已复制</string>

    <!-- QrPairingScanner -->
    <string name="qr_scanner_title">扫描 Hermes QR</string>
    <string name="qr_scanner_instruction">扫描 Hermes 设置二维码</string>
    <string name="qr_scanner_subtext">询问 Hermes："生成一个包含我的 API URL 和 API 密钥的二维码。"</string>
    <string name="qr_scanner_camera_error">无法在此设备上启动相机。</string>
    <string name="qr_scanner_fallback_message">您可以在没有相机的情况下配对。</string>
    <string name="qr_scanner_pair_manual">手动配对</string>

    <!-- SphereSkin -->
    <string name="sphere_skin_adaptive">自适应</string>
    <string name="sphere_skin_classic">经典</string>
    <string name="sphere_skin_aurora">极光</string>
    <string name="sphere_skin_solar">太阳</string>
    <string name="sphere_skin_mono">单色</string>
    <string name="sphere_skin_adaptive_desc">跟随您的主题颜色</string>
    <string name="sphere_skin_classic_desc">原始绿色-紫色球体</string>
    <string name="sphere_reactivity_voice">语音</string>
    <string name="sphere_reactivity_tools">工具</string>
    <string name="sphere_reactivity_activity">活动</string>
    <string name="sphere_reactivity_gaze">注视</string>
    <string name="sphere_reactivity_static">静态</string>

    <!-- TimelineView -->
    <string name="timeline_header">时间线</string>
    <string name="timeline_status_checks">状态检查</string>
    <string name="timeline_empty_activity">暂无活动</string>
    <string name="timeline_empty_checks">尚无检查 — 连接到服务器以填充诊断信息。</string>
    <string name="timeline_legend_chat">聊天</string>
    <string name="timeline_legend_tool">工具</string>
    <string name="timeline_legend_voice">语音</string>
    <string name="timeline_legend_profile">配置文件</string>
    <string name="timeline_legend_conn">连接</string>
    <string name="timeline_legend_pass">通过</string>
    <string name="timeline_legend_warn">警告</string>
    <string name="timeline_legend_fail">失败</string>
    <string name="timeline_legend_unknown">未知</string>
    <string name="timeline_tap_detail">点击查看日志详情</string>
    <string name="timeline_pass">通过</string>
    <string name="timeline_warn">警告</string>
    <string name="timeline_fail">失败</string>
    <string name="timeline_status_unknown">未知</string>

    <!-- CommandPalette -->
    <string name="command_palette_header">命令</string>
    <string name="command_palette_search">搜索命令…</string>
    <string name="command_palette_cd_clear">清除搜索</string>
    <string name="command_palette_filter_all">全部</string>
    <string name="command_palette_show_less">显示更少</string>
    <string name="command_palette_show_all">显示全部（%1$d）</string>
    <string name="command_palette_empty">没有匹配您搜索的命令</string>

    <!-- TransportSecurityBadge -->
    <string name="transport_badge_secure_tls">安全 — TLS</string>
    <string name="transport_badge_mixed">混合 — 有安全回退可用</string>
    <string name="transport_badge_plain">所有路由均为明文 — 仅限开发</string>
    <string name="transport_badge_secure_tls_short">安全（TLS）</string>
    <string name="transport_badge_insecure_lan">明文（在局域网）</string>
    <string name="transport_badge_insecure_tailscale">明文（在 Tailscale）</string>
    <string name="transport_badge_insecure_public">明文（在公网 URL）</string>
    <string name="transport_badge_insecure_lan_only">明文（仅局域网）</string>
    <string name="transport_badge_encrypted_tls">已加密 · TLS</string>
    <string name="transport_badge_encrypted_tailscale">已加密 · Tailscale</string>
    <string name="transport_badge_mixed_routes">混合路由</string>
    <string name="transport_badge_not_encrypted">未加密</string>
    <string name="transport_badge_checking">检查中…</string>
    <string name="transport_badge_glyph_tls">已加密（TLS）</string>
    <string name="transport_badge_glyph_encrypted">已加密</string>
    <string name="transport_badge_glyph_not_encrypted">未加密</string>

    <!-- MessageBubble -->
    <string name="msg_bubble_copy">复制</string>
    <string name="msg_bubble_quote">引用回复</string>
    <string name="msg_bubble_edit">编辑并重新发送</string>
    <string name="msg_bubble_reconnecting">正在重新连接到您的回答…</string>
    <string name="msg_bubble_still_working">仍在处理…</string>
    <string name="msg_bubble_sending">发送中…</string>
    <string name="msg_bubble_delivered">已送达</string>
    <string name="msg_bubble_not_sent">未发送</string>

    <!-- ProfileInspectorCard -->
    <string name="profile_inspector_card_title">检查代理</string>
    <string name="profile_inspector_card_subtitle">查看配置、SOUL、记忆、技能</string>
    <string name="profile_inspector_card_no_agent">无活动代理</string>

    <!-- TerminalSearchBar -->
    <string name="term_search_placeholder">搜索回滚缓冲区</string>
    <string name="term_search_cd_previous">上一个匹配</string>
    <string name="term_search_cd_next">下一个匹配</string>
    <string name="term_search_cd_close">关闭搜索</string>

    <!-- UpdateAvailableBanner -->
    <string name="update_banner_available">有可用更新</string>
    <string name="update_banner_downloading">正在下载更新…</string>
    <string name="update_banner_ready">更新已就绪 — 重启</string>
    <string name="update_banner_update">更新</string>
    <string name="update_banner_restart">重启</string>

    <!-- UpdateBanner -->
    <string name="update_banner_cd_dismiss">忽略</string>

    <!-- VoiceOverlayHost -->
    <string name="voice_overlay_title">语音叠加</string>
    <string name="voice_overlay_minimize">最小化</string>
    <string name="voice_overlay_hermes">Hermes</string>
    <string name="voice_overlay_hide">隐藏</string>
    <string name="voice_overlay_exit">退出</string>
    <string name="voice_overlay_cd_exit">退出语音模式</string>
    <string name="voice_overlay_cd_mic">语音叠加麦克风</string>
    <string name="voice_overlay_state_ready">就绪</string>
    <string name="voice_overlay_state_listening">正在聆听</string>
    <string name="voice_overlay_state_transcribing">正在转录</string>
    <string name="voice_overlay_state_thinking">正在思考</string>
    <string name="voice_overlay_state_speaking">正在说话</string>
    <string name="voice_overlay_state_error">错误</string>
    <string name="voice_overlay_bubble_label_ready">就绪</string>
    <string name="voice_overlay_bubble_label_listen">聆听</string>
    <string name="voice_overlay_bubble_label_stt">语音转文字</string>
    <string name="voice_overlay_bubble_label_think">思考</string>
    <string name="voice_overlay_bubble_label_speak">说话</string>
    <string name="voice_overlay_bubble_label_error">错误</string>
    <string name="voice_overlay_tap_action_idle">开始聆听</string>
    <string name="voice_overlay_tap_action_listening">停止聆听</string>
    <string name="voice_overlay_tap_action_pause">暂停自动模式</string>
    <string name="voice_overlay_tap_action_interrupt">中断</string>
    <string name="voice_overlay_cd_description">语音叠加 %1$s。点击 %2$s，双击展开。</string>
    <string name="voice_overlay_label_default_profile">默认配置文件</string>
    <string name="voice_overlay_provider_output_off">输出关闭</string>
    <string name="voice_overlay_provider_placeholder">提供商 ...</string>
    <string name="voice_overlay_engine_realtime">实时代理</string>
    <string name="voice_overlay_engine_hermes">Hermes 语音</string>
    <string name="voice_overlay_engine_placeholder">语音引擎 ...</string>

    <!-- AppTheme labels + descriptions -->
    <string name="theme_hermes_relay">Hermes Relay</string>
    <string name="theme_hermes_relay_desc">标志性的电蓝色品牌 — 跟随明/暗</string>
    <string name="theme_hermes_teal">Hermes Teal</string>
    <string name="theme_hermes_teal_desc">经典深青色 — 规范 Hermes 外观</string>
    <string name="theme_nous_blue">Nous Blue</string>
    <string name="theme_nous_blue_desc">明亮模式 — 奶油色画布上生动的 Nous 蓝色强调色</string>
    <string name="theme_midnight">午夜</string>
    <string name="theme_midnight_desc">深蓝紫色配冷色调</string>
    <string name="theme_ember">余烬</string>
    <string name="theme_ember_desc">暖深红和古铜色 — 炉火氛围</string>
    <string name="theme_mono">单色</string>
    <string name="theme_mono_desc">干净灰度 — 简约专注</string>
    <string name="theme_cyberpunk">赛博朋克</string>
    <string name="theme_cyberpunk_desc">黑色背景上的霓虹绿 — 矩阵终端</string>
    <string name="theme_rose">玫瑰</string>
    <string name="theme_rose_desc">柔粉色和暖象牙色 — 舒适护眼</string>

    <!-- ChatScreen hardcoded strings -->
    <string name="chat_slash_new">开始新会话</string>
    <string name="chat_slash_retry_label">重试上一条消息</string>
    <string name="chat_slash_undo">删除最后一次交换</string>
    <string name="chat_slash_title_txt">为此会话设置标题</string>
    <string name="chat_slash_branch">分支/分叉当前会话</string>
    <string name="chat_slash_compress">压缩对话上下文</string>
    <string name="chat_slash_rollback">列出或恢复检查点</string>
    <string name="chat_slash_stop">终止正在运行的后台进程</string>
    <string name="chat_slash_resume">恢复之前的会话</string>
    <string name="chat_slash_background">在后台运行提示</string>
    <string name="chat_slash_btw">使用会话上下文的附带问题</string>
    <string name="chat_slash_queue_label">为下一轮排队提示</string>
    <string name="chat_slash_approve">批准待处理的命令</string>
    <string name="chat_slash_deny">拒绝待处理的命令</string>
    <string name="chat_slash_model_label">为此会话切换模型</string>
    <string name="chat_slash_provider">显示可用提供商</string>
    <string name="chat_slash_personality_label">设置预定义角色</string>
    <string name="chat_slash_verbose">循环工具进度显示</string>
    <string name="chat_slash_yolo">切换自动批准模式</string>
    <string name="chat_slash_reasoning">设置推理努力级别</string>
    <string name="chat_slash_voice_label">切换语音模式</string>
    <string name="chat_slash_reload_mcp">重新加载 MCP 服务器</string>
    <string name="chat_slash_help">显示可用命令</string>
    <string name="chat_slash_status_label">显示会话信息</string>
    <string name="chat_slash_usage">显示令牌使用情况</string>
    <string name="chat_slash_insights">使用分析</string>
    <string name="chat_slash_commands_label">浏览所有命令</string>
    <string name="chat_slash_profile_label">显示活动配置文件</string>
    <string name="chat_slash_category_session">会话</string>
    <string name="chat_slash_category_config">配置</string>
    <string name="chat_slash_category_info">信息</string>
    <string name="chat_prompt_help_me_code">帮我写代码</string>
    <string name="chat_prompt_explain">解释某件事</string>
    <string name="chat_prompt_what_can_you_do">你能做什么？</string>
    <string name="chat_connect_to_hermes_dots">正在连接到 Hermes…</string>
    <string name="chat_needs_connection">连接到 Hermes</string>
    <string name="chat_streaming">流式传输</string>
    <string name="chat_connected_label">已连接</string>
    <string name="chat_reconnecting_dots">正在重新连接…</string>
    <string name="chat_connecting_dots">正在连接…</string>
    <string name="chat_disconnected_label">已断开</string>
    <string name="chat_server_default_sessions">服务器默认会话</string>
    <string name="chat_active_connection">活动连接</string>
    <string name="chat_compatibility_overlay">%1$s 上的兼容性覆盖</string>
    <string name="chat_connection_label">连接：%1$s</string>
    <string name="chat_file_too_large">文件过大（最大 %1$d MB）</string>
    <string name="chat_prompt_chat_with">与 %1$s 聊天</string>
    <string name="chat_hermes_message">Hermes 消息</string>
    <string name="chat_start_conversation">开始对话</string>
    <string name="chat_not_on_plan">您的套餐中不包含</string>
    <string name="chat_needs_setup">需要设置</string>
    <string name="chat_model_label">模型</string>
    <string name="chat_reasoning_none">无</string>
    <string name="chat_reasoning_minimal">最少</string>
    <string name="chat_reasoning_low">低</string>
    <string name="chat_reasoning_medium">中</string>
    <string name="chat_reasoning_high">高</string>
    <string name="chat_date_today">今天</string>
    <string name="chat_date_yesterday">昨天</string>
    <string name="chat_share_subject">Hermes 对话</string>

    <!-- RelayApp Screen labels -->
    <string name="screen_chat_label">聊天</string>
    <string name="screen_terminal_label">终端</string>
    <string name="screen_bridge_label">桥接</string>
    <string name="screen_manage_label">管理</string>
    <string name="screen_settings_label">设置</string>
    <string name="screen_connections_label">连接</string>
    <string name="screen_connection_label">连接</string>
    <string name="screen_voice_label">语音</string>
    <string name="screen_notification_companion_label">通知伴侣</string>
    <string name="screen_proactive_label">线程</string>
    <string name="screen_permissions_label">权限</string>
    <string name="screen_bridge_safety_label">桥接安全</string>
    <string name="screen_chat_settings_label">聊天</string>
    <string name="screen_media_label">媒体</string>
    <string name="screen_appearance_label">外观</string>
    <string name="screen_analytics_label">分析</string>
    <string name="screen_diagnostics_label">诊断</string>
    <string name="screen_developer_label">开发者</string>
    <string name="screen_realtime_voice_label">实时语音</string>
    <string name="screen_about_label">关于</string>
    <string name="screen_profile_inspector_label">配置文件检查器</string>
    <string name="screen_relay_sessions_label">Relay 会话</string>

    <!-- ChatScreen remaining strings -->
    <string name="chat_relay_sessions_title">Relay 会话</string>
    <string name="chat_relay_sessions_summary">查看和撤销与此继电器配对的设备。</string>
    <string name="chat_profile_inspector_title">配置文件检查器</string>
    <string name="chat_profile_inspector_summary">检查 Relay 支持的配置文件配置、SOUL、内存文件和技能。</string>
    <string name="chat_reconnecting_toast">正在重新连接到 Relay…</string>
    <string name="chat_rename_failed">重命名失败</string>
    <string name="chat_only_active_revoke">现在只能撤销活动连接</string>
</resources>
"""

with open(zh_path, 'w', encoding='utf-8') as f:
    f.write(zh_content)

# Verify
en_keys = set(re.findall(r'<string name="([^"]+)"', open(r'D:\clawtemp\hermes-relay-cn\app\src\main\res\values\strings.xml', encoding='utf-8').read()))
zh_keys = set(re.findall(r'<string name="([^"]+)"', open(zh_path, encoding='utf-8').read()))
missing = en_keys - zh_keys
print(f'English keys: {len(en_keys)}')
print(f'Chinese keys: {len(zh_keys)}')
print(f'Missing from zh: {len(missing)}')
if missing:
    for k in sorted(missing):
        print(f'  MISSING: {k}')