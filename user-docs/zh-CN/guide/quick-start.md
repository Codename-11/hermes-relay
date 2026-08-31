---
translation_status: ai-translated
canonical_source: /guide/quick-start
---

# 快速开始

安装 → 连接 → 对话。标准路径继续优先 upstream；要获得完整的
Hermes-Relay 体验，建议安装 Relay plugin。

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

建议的完整路径还需要安装：

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
hermes relay start --no-ssl
```

仅在可信 LAN 或 VPN 中使用 `--no-ssl`。如需在外网访问，
[建议使用 Tailscale](/guide/remote-access)。

## 3. 连接 {#other-supported-paths}

打开应用并进入 **Connect**，然后选择一种方式：

1. 在 Web Dashboard 打开 **Relay → Connect mobile app**，并在 Android
   **Connect → Scan Hermes setup QR** 扫描不含凭据的二维码。
2. 然后打开 **Relay → Pair new device**，在 **Settings → Connections →
   Pair Hermes Relay** 扫描一次性二维码。
3. 如果没有 Dashboard plugin，请使用 **Find Hermes on LAN**，或手动输入
   类似 `http://<host>:9119` 的 Dashboard 地址。
4. 如果无法使用相机，`hermes pair` 会生成同一二维码和可复制邀请；URL 与
   配对码仍可作为手动 fallback。
5. 按提示使用 Dashboard 提供方登录。

API 服务器仍是可选 fallback。upstream 标准路径不强制要求 Relay，但建议
使用 Relay 来获得 Terminal/TUI、通知、媒体、桌面工具、增强 Voice 和 Device Control。

## 4. 检查状态

- **Chat · Ready** 表示可以发送消息。
- **Manage** 可能仍要求登录 Dashboard。
- **Voice** 通过同一个 Dashboard 会话启用。
- **Direct API** 不可用时不会阻止 Chat。
- **Relay · Paired** 表示推荐扩展已启用；Relay 故障不应阻止 upstream 标准路径。

## 5. 发送第一条消息

打开 Chat 并发送消息。标题栏中的绿色连接指示表示当前 Hermes 连接可用。

[详细安装 →](/zh-CN/guide/getting-started) ·
[故障排除 →](/zh-CN/guide/troubleshooting) ·
[英文规范指南 →](/guide/quick-start)
