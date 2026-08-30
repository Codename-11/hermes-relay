---
translation_status: ai-translated
canonical_source: /guide/getting-started
---

# 安装与设置

这是用于版本选择、手动连接、远程访问和安全检查的详细参考。如果
Hermes Dashboard 已可访问，请使用[快速开始](./quick-start)。

<AndroidSetupPath mode="reference" />

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

Dashboard 登录在当前 Gateway 上使用原生 bearer，在兼容 Gateway 上使用精确
origin Cookie，并配合短期 Gateway ticket。API 密钥与其独立。

Android 保存的路由可以是 LAN、Tailscale 或公共地址。OIDC 仍需要一个可访问的
HTTPS 回调：`<公共-dashboard-origin>/auth/callback`。Hermes 通常从受信任的代理
标头重建该 origin；只有在重建不可靠时，才设置 upstream
`dashboard.public_url` / `HERMES_DASHBOARD_PUBLIC_URL`。Android 会在保存前验证
不同的登录 origin。

## 3. 连接并开始对话

1. 在 Android 应用中打开 **Connect**。
2. 搜索局域网中的 Hermes、输入 Dashboard/Gateway URL，或扫描设置二维码；旧版 API-first 二维码仍兼容。
3. 按提示登录 Dashboard。
4. 点击 **Connect** 并确认显示 **Chat · Ready**。
5. 在 **完成设置** 中，如需后台聊天提醒，请启用 Android 通知。相机、麦克风和其他功能仍为可选，并可逐项设置；选择 **暂不** 可直接继续。
6. 如有需要，稍后在 **Advanced** 中添加 API fallback、Relay 或远程路由。

`hermes-relay-tailscale enable` 会在 tailnet 的 `:443` 发布
`https://host.ts.net`，并将它连同同源 Relay 路径代理到本地 Dashboard
`:9119`。也可以使用有意直接开放的 `http://100.x.y.z:9119` 路由，但它没有应用层 TLS。

同一个登录会启用 Chat、会话、Manage 和 Voice。Relay 未配对或 API fallback 不可用都是正常状态。

## 建议：使用 Relay 完成设置 {#relay-server-optional}

upstream 标准路径在没有 plugin 时仍可工作。建议使用 Relay 来获得
Terminal/TUI、通知、媒体、桌面工具、增强 Voice、Relay 会话和可选
Device Control。规范命令为
`hermes plugins install Codename-11/hermes-relay/plugin --enable`、
`hermes relay doctor`、`hermes relay start --no-ssl` 和 `hermes pair`。

推荐在 Web Dashboard 的 **Relay** 页面先打开 **Connect mobile app**，再打开
**Pair new device**，并用 Android 扫描两个二维码。无法使用二维码时，仍可使用
URL/配对码输入和 `hermes pair --register-code`。

Device Control 同时需要 **Sideload 应用和已配对的 Relay**。

[比较应用版本 →](/zh-CN/guide/release-tracks) ·
[英文远程访问指南 →](/guide/remote-access) ·
[故障排除 →](/zh-CN/guide/troubleshooting)
