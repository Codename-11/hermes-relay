---
translation_status: ai-translated
canonical_source: /guide/troubleshooting
---

# 故障排除

从当前看到的症状开始，可以快速判断问题来自 Android、网络还是 Hermes 主机。

- [红色指示或无法连接](#无法连接)
- [“No reachable endpoint”](#没有可访问的端点)
- [Chat 不流式显示](#chat-不流式显示)
- [Manage 或 Voice 要求登录](#manage-和-voice)
- [会话未显示](#会话未显示)
- [应用启动时崩溃](#启动时崩溃)

## 无法连接

1. 确认主机上正在运行 `hermes gateway`。
2. 在**手机**浏览器中打开 `http://<host>:8642/health`。
3. 检查 `API_SERVER_ENABLED=true` 和防火墙规则。
4. 不要在手机上使用 `localhost` 或 `127.0.0.1`；它们指向手机本身。

## 没有可访问的端点

应用已检查所有保存的局域网、Tailscale 和公网路由，但均未收到响应。局域网地址
只在同一 Wi-Fi 中可用。使用 Tailscale 时，手机和服务器都必须处于连接状态。

## Chat 不流式显示

- 检查 API URL 和 API 密钥。
- 如果出现错误提示，只点击一次 **Retry**。
- 查看 Hermes 服务器日志。
- 本地模型可能需要数分钟。如果 Android 在后台断开连接，重新连接后会读取已完成的响应。

## Manage 和 Voice

Manage 和标准 Voice 使用 Dashboard 会话，而不是 `API_SERVER_KEY`。请在 Manage
中登录一次，并确认手机可以访问 Dashboard。

## 会话未显示

切换会话时服务器必须可访问。大型会话可能需要一些时间，请等待加载指示完成。

## 启动时崩溃

打开 Android **设置 → 应用 → Hermes-Relay → 存储**，清除应用数据，然后重新
配置 API URL 和密钥。

对于 Relay 问题，可以使用只读诊断命令 `hermes relay doctor`。

[英文完整故障排除 →](/guide/troubleshooting) ·
[安装与设置 →](/zh-CN/guide/getting-started)
