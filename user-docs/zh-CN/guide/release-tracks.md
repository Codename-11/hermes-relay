---
translation_status: ai-translated
canonical_source: /guide/release-tracks
---

# 应用版本：Google Play 或 Sideload

除非明确需要 Device Control，否则建议先使用 Google Play。两个版本来自同一套
代码，可以同时安装在同一部手机上。

## 选择指南

| 问题 | Google Play | Sideload |
|---|---|---|
| 安装简单并自动更新？ | 是 | 否 |
| Chat、Profiles、Manage 和 Voice？ | 是 | 是 |
| 配合 Relay 的终端、媒体和通知？ | 是 | 是 |
| 读取或截取屏幕？ | 否 | 是 |
| 点击、输入、滑动和操作应用？ | 否 | 是 |

## 应用版本和 Relay 是两个独立选择

**应用版本**决定 Android 是否包含 Device Control。可选的 **Relay 插件**负责将
终端、媒体、通知和设备通道连接到 Hermes 主机。

Device Control 仅在 **Sideload + 已配对 Relay** 的组合下工作。Chat、Manage 和
标准 Voice 均不需要这两项。

## 以后切换

Google Play 和 Sideload 使用不同的应用 ID。您可以同时试用，再删除其中一个。
设置和配对信息按应用分别保存。

[打开 Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay) ·
[下载 Sideload APK](https://github.com/Codename-11/hermes-relay/releases) ·
[英文完整对比 →](/guide/release-tracks)
