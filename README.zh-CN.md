<p align="center">
  <img src="assets/play-store-feature-1024x500.png" alt="Hermes-Relay — 随身携带您的 Hermes 代理" width="800">
</p>

<p align="center">
  <strong>运行在您的电脑上，连接到您的设备。</strong><br>
  Hermes-Relay 是 <a href="https://github.com/NousResearch/hermes-agent">Hermes Agent</a> 的原生 Android 客户端，提供流式聊天、免手动语音和代理管理；另有单文件 CLI，让代理在已配对的电脑上安全使用终端、文件和截图工具。
</p>

<p align="center">
  <strong>简体中文</strong> · <a href="README.md">English</a><br>
  <a href="https://hermes-relay.dev/docs/zh-CN/">中文文档</a> ·
  <a href="https://github.com/Codename-11/hermes-relay/releases">版本下载</a> ·
  <a href="CHANGELOG.md">更新日志</a>
</p>

> 英文 [README.md](README.md) 是最新、完整的项目说明。本页维护中文安装入口和核心功能摘要；协议、架构和维护者文档以英文版本为准。

## 功能简介

- **Android 应用**：流式聊天、会话历史、文件附件、Hermes 管理、语音模式、多连接和配置文件。
- **无需插件的标准路径**：聊天、管理和标准语音可直接连接未修改的上游 Hermes Agent。
- **可选 Relay 插件**：增加终端、手机控制、媒体传输、通知助手、Relay 语音和电脑工具。
- **安全连接**：二维码配对、Android Keystore、证书固定、按通道授权和可配置会话有效期。
- **远程使用**：可配置 Tailscale 或 HTTPS 地址，在家庭局域网和远程路由之间自动切换。
- **两种 Android 发行渠道**：Google Play 版本适合日常使用；sideload 版本包含完整手机控制能力。

## 快速开始

### 1. 安装 Android 应用

- [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay)：自动更新，包含聊天、语音、管理、终端、媒体和通知功能。
- [GitHub Releases](https://github.com/Codename-11/hermes-relay/releases)：下载最新 `android-v*` 版本中以 `-sideload-release.apk` 结尾的文件，获得完整手机控制功能。

### 2. 启动 Hermes API 服务

手机需要能够访问 Hermes API 服务，并使用 API 密钥进行身份验证：

```bash
hermes setup --portal

mkdir -p ~/.hermes
API_SERVER_KEY="$(openssl rand -hex 32)"
cat >> ~/.hermes/.env <<EOF
API_SERVER_ENABLED=true
API_SERVER_HOST=0.0.0.0
API_SERVER_PORT=8642
API_SERVER_KEY=$API_SERVER_KEY
EOF
chmod 600 ~/.hermes/.env

echo "Android API URL: http://<电脑IP>:8642   key: $API_SERVER_KEY"
hermes gateway
```

`0.0.0.0` 会让同一网络中的设备访问 API。请保留强密钥；离开可信局域网时，应使用 Tailscale 或 HTTPS 反向代理，不要直接把端口暴露到互联网。

### 3. 在手机上连接

打开应用后，可以：

- 扫描局域网中的 Hermes；
- 手动输入 `http://<主机>:8642` 和 API 密钥；
- 扫描包含 API、Dashboard 和可选 Relay 地址的设置二维码。

如需在手机上管理模型、密钥、技能和配置文件，请运行 Hermes Dashboard，并在应用的 **管理** 页面登录一次。同一登录会话也会启用标准语音。

### 4. 可选：安装 Relay

仅在需要终端、手机控制、媒体路由、Relay 会话、实时语音或电脑工具时安装：

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
hermes relay start --no-ssl
hermes pair
```

完整说明请阅读[中文快速开始](https://hermes-relay.dev/docs/zh-CN/guide/quick-start)；远程访问、协议和高级配置暂时链接到英文参考文档。

## 中文界面

<table>
  <tr>
    <td align="center" width="33%"><img src="assets/screenshots/Zh01.jpg" alt="中文设置界面" width="100%"><br><sub><b>设置</b></sub></td>
    <td align="center" width="33%"><img src="assets/screenshots/Zh02.jpg" alt="中文管理界面" width="100%"><br><sub><b>管理</b></sub></td>
    <td align="center" width="33%"><img src="assets/screenshots/Zh03.jpg" alt="中文导航界面" width="100%"><br><sub><b>导航</b></sub></td>
  </tr>
</table>

## 参与翻译

Android 英文资源是规范来源。新增语言必须保持资源名称、类型和格式参数一致，并通过：

```bash
python scripts/check-android-locales.py
./gradlew lint
```

翻译规范、目录命名、复数和占位符规则见 [docs/localization.md](docs/localization.md)。

## 许可证

[MIT](LICENSE) — Copyright (c) 2026 [Axiom-Labs](https://codename-11.dev)
