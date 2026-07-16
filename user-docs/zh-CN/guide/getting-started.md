---
translation_status: ai-translated
canonical_source: /guide/getting-started
---

# 安装与设置

只需三步：安装应用、连接 Hermes、发送第一条消息。如果 Hermes 已在运行，
服务器上无需额外安装任何组件。

::: tip 翻译状态
此精简指南覆盖常用流程。高级服务器、TLS 和运维选项请查看
[完整英文指南](/guide/getting-started)。
:::

## 1. 选择应用版本

| | Google Play | Sideload |
|---|---|---|
| 适合 | 大多数用户 | 需要 Device Control 的用户 |
| 更新 | 自动 | 手动更新 APK |
| Chat、Voice、Manage | 包含 | 包含 |
| 配合 Relay 的终端、媒体和通知 | 包含 | 包含 |
| 读取屏幕、点击、输入和导航 | 不包含 | 包含 |

<StoreBadge />

签名的 Sideload 文件名以 `-sideload-release.apk` 结尾，可从
[GitHub Releases](https://github.com/Codename-11/hermes-relay/releases) 下载。
不要下载 `.aab` 文件；它仅供 Google Play 使用。

## 2. 让手机可以访问 Hermes

Android 通常通过 `:8642` 访问 Hermes API 服务器：

- `API_SERVER_ENABLED=true` 启用 API 服务器。
- `API_SERVER_HOST=0.0.0.0` 允许网络访问。
- `API_SERVER_KEY` 使用 bearer 密钥保护 Chat 请求。
- `hermes gateway` 启动 Hermes 和已启用的 API 服务器。

::: warning 保护网络访问
`0.0.0.0` 允许网络中的其他设备访问服务。请使用强 API 密钥。不要将未加密
端口直接暴露到互联网；远程访问应使用 Tailscale、VPN 或 HTTPS。
:::

`:9119` 上的 Dashboard 是可选的。Manage 和标准 Voice 使用它，并且它有独立
登录；API 密钥不是 Dashboard 登录凭据。

## 3. 连接并开始对话

1. 在 Android 应用中打开 **Connect**。
2. 搜索局域网中的 Hermes、扫描设置二维码，或输入 API URL 和密钥。
3. 点击 **Connect**。
4. 确认显示 **Chat · Ready**。
5. 打开 Chat 并发送第一条消息。

Manage 和 Voice 可能仍要求登录。Relay 显示未配对也是正常状态。

## 可选：添加 Relay 工具

仅在需要终端、Device Control、媒体、通知或高级远程工具时安装插件。规范命令为
`hermes plugins install Codename-11/hermes-relay/plugin --enable`、
`hermes relay doctor`、`hermes relay start --no-ssl` 和 `hermes pair`。

Device Control 同时需要 **Sideload 应用和已配对的 Relay**。

[比较应用版本 →](/zh-CN/guide/release-tracks) ·
[英文远程访问指南 →](/guide/remote-access) ·
[故障排除 →](/zh-CN/guide/troubleshooting)
