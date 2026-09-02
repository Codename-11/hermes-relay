# Hermes-Relay

**运行在您的电脑上，连接到您的设备。**

[English](../../README.md) · [Deutsch](README.de.md) · [Español](README.es.md) · [日本語](README.ja.md) · [Português (Brasil)](README.pt-BR.md) · [Русский](README.ru.md) · **简体中文**

> 英文版是完整、规范的项目说明。此 AI 辅助翻译是持续维护的精简入门页。

Hermes-Relay 将您的 [Hermes Agent](https://github.com/NousResearch/hermes-agent)
带到 Android 和已连接的电脑上。Hermes 仍在您自己的电脑上运行；
Hermes-Relay 提供原生界面和可选扩展。

## 核心功能

- **Android：**流式聊天、会话、接收文件、Manage、语音和 Petdex。
- **标准连接：**聊天、Manage、会话、语音和文件直接连接未修改的 Hermes Dashboard/Gateway。
- **可选 Relay 扩展：**Terminal/TUI、通知、桌面工具、增强语音、Relay 会话和媒体功能。
- **Sideload 版本：**增加需要确认的 Device Control，可读取屏幕、点击和导航。
- **CLI / UI：**电脑直接连接 Relay，并提供需要授权的文件、终端、搜索和截图工具。

## Android 快速开始

1. 从 [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay) 安装，或从最新 [`android-v*` 版本](https://github.com/Codename-11/hermes-relay/releases)下载已签名的 Sideload APK。
2. 在运行 Hermes 的电脑上启动标准连接界面：

   ```bash
   hermes dashboard
   ```

3. 在 Android 中打开 **Connect**，在局域网中查找 Hermes，或输入 Dashboard 地址并登录。标准路径不需要 Relay，也不需要单独的 API 密钥。
4. 仅在需要附加功能时安装 Relay：

   ```bash
   hermes plugins install Codename-11/hermes-relay/plugin --enable
   hermes relay doctor
   hermes relay start --no-ssl
   ```

   `--no-ssl` 仅限可信局域网或 VPN。然后在 Dashboard 中通过 **Relay → Pair new device** 配对。

## 了解更多

[中文快速开始](https://hermes-relay.dev/docs/zh-CN/guide/quick-start) ·
[安装与设置](https://hermes-relay.dev/docs/zh-CN/guide/getting-started) ·
[故障排除](https://hermes-relay.dev/docs/zh-CN/guide/troubleshooting) ·
[完整英文文档](https://hermes-relay.dev/docs/)

[MIT 许可证](../../LICENSE)
