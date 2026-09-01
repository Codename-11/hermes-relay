---
translation_status: ai-translated
canonical_source: /guide/quick-start
---

# 快速开始

先使用标准 upstream Dashboard/Gateway 连接。需要 Relay 提供的附加工具和
增强功能时，再进行配对。

<AndroidSetupPath mode="quick" />

::: tip 翻译状态
本页由 AI 辅助翻译并通过技术检查。产品含义和安全要求仍以英文版为准。
:::

## 1. 安装应用

对大多数用户，**Google Play** 是最快的方式：一键安装并自动更新。

<StoreBadge />

如果希望 Hermes 读取屏幕、点击、输入或操作手机，请安装签名的
**Sideload APK**。两个版本可以同时安装。

## 2. 启动 Hermes

Hermes Dashboard/Gateway 必须运行，并且手机可以访问。如有需要，在主机上运行
`hermes dashboard`。服务器准备步骤见[安装与设置](/zh-CN/guide/getting-started)。
这就是标准 Chat、会话、Manage、Voice 和入站文件所需的全部服务器端设置。

## 3. 添加标准 Hermes 连接 {#other-supported-paths}

在 Android 中打开 **Connect**。使用 **Find Hermes on LAN**，或手动输入
Dashboard 地址（通常为 `http://<host>:9119`）。按提示登录。这样即可创建不含
plugin 或 Relay URL 的完整标准连接。

## 4. 可选：安装并配对 Relay

标准连接正常工作后，再安装 Relay 以获得建议的完整体验：

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
hermes relay start --no-ssl
```

仅在可信 LAN 或 VPN 中使用 `--no-ssl`。如需在外网访问，
[建议使用 Tailscale](/guide/remote-access)。

然后在 Web Dashboard 中打开 **Relay → Pair new device**，并在 **Settings →
Connections → Pair Hermes Relay** 扫描一次性二维码。

API 服务器仍是可选 fallback。upstream 标准路径不强制要求 Relay，但建议
使用 Relay 来获得 Terminal/TUI、通知、桌面工具、增强 Voice、Relay 会话、
Device Control，以及媒体兼容性或元数据。普通入站文件使用当前 Dashboard 路由。

## 5. 检查状态

- **Chat · Ready** 表示可以发送消息。
- **Manage** 可能仍要求登录 Dashboard。
- **Voice** 通过同一个 Dashboard 会话启用。
- **Direct API** 不可用时不会阻止 Chat。
- **Relay · Paired** 表示推荐扩展已启用；Relay 故障不应阻止 upstream 标准路径。

## 6. 发送第一条消息

打开 Chat 并发送消息。标题栏中的绿色连接指示表示当前 Hermes 连接可用。

[详细安装 →](/zh-CN/guide/getting-started) ·
[故障排除 →](/zh-CN/guide/troubleshooting) ·
[英文规范指南 →](/guide/quick-start)
