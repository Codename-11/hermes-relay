---
translation_status: ai-translated
canonical_source: /guide/quick-start
---

# 快速开始

安装 → 连接 → 对话，大约两分钟即可完成。此标准流程只需要正常运行的
Hermes Agent，不要求安装 Relay 插件。

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

## 3. 连接

打开应用并进入 **Connect**，然后选择一种方式：

1. 使用 **Scan for Hermes on LAN** 搜索局域网服务器。
2. 输入类似 `http://<host>:9119` 的 Dashboard/Gateway 地址。
3. 使用 **Scan setup QR**；旧版 API-first 二维码仍支持高级兼容场景。
4. 按提示使用已配置的 Dashboard 提供方登录。

API 服务器仅作为可选自动 fallback 或高级 headless 兼容方式；Relay 也只在使用扩展功能时需要。

## 4. 检查状态

- **Chat · Ready** 表示可以发送消息。
- **Manage** 可能仍要求登录 Dashboard。
- **Voice** 通过同一个 Dashboard 会话启用。
- **API fallback** 不可用时不会阻止 Chat。
- **Relay** 可以保持未配对，不会阻止标准功能。

## 5. 发送第一条消息

打开 Chat 并发送消息。标题栏中的绿色连接指示表示当前 Hermes 连接可用。

[详细安装 →](/zh-CN/guide/getting-started) ·
[故障排除 →](/zh-CN/guide/troubleshooting) ·
[英文规范指南 →](/guide/quick-start)
