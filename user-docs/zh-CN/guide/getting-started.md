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

Android 的标准连接是 `:9119` 上的 Hermes Dashboard/Gateway。它提供 Chat、
会话、登录、Manage 和标准 Voice。请使用 `hermes dashboard` 启动，并确保手机可访问。

`:8642` 上的 API 服务器是可选的，仅用于 Chat 自动 fallback 或高级 headless
兼容。只有配置该可选端点时才需要 API 密钥。`API_SERVER_KEY` 由服务器运维人员创建，Dashboard 不会提供该密钥。

::: warning 保护网络访问
不要将未加密的 Dashboard、API 或 Relay 端口直接暴露到互联网；远程访问应使用 Tailscale、VPN 或 HTTPS。
:::

Dashboard 登录使用 Cookie 和短期 Gateway ticket。API 密钥与其独立，不能用于 Dashboard 登录。

## 3. 连接并开始对话

1. 在 Android 应用中打开 **Connect**。
2. 搜索局域网中的 Hermes、输入 Dashboard/Gateway URL，或扫描设置二维码；旧版 API-first 二维码仍兼容。
3. 按提示登录 Dashboard。
4. 点击 **Connect** 并确认显示 **Chat · Ready**。
5. 如有需要，稍后在 **Advanced** 中添加 API fallback、Relay 或远程路由。

可以添加并测试 `http://100.x.y.z:9119` 这样的 Tailscale Dashboard 地址，或单独发布的 `.ts.net` 地址；无需配置 API 服务器或 API 密钥。

同一个登录会启用 Chat、会话、Manage 和 Voice。Relay 未配对或 API fallback 不可用都是正常状态。

## 可选：添加 Relay 工具

仅在需要终端、Device Control、媒体、通知或高级远程工具时安装插件。规范命令为
`hermes plugins install Codename-11/hermes-relay/plugin --enable`、
`hermes relay doctor`、`hermes relay start --no-ssl` 和 `hermes pair`。

Device Control 同时需要 **Sideload 应用和已配对的 Relay**。

[比较应用版本 →](/zh-CN/guide/release-tracks) ·
[英文远程访问指南 →](/guide/remote-access) ·
[故障排除 →](/zh-CN/guide/troubleshooting)
