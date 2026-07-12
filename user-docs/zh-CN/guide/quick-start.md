# 快速开始

Hermes-Relay 的标准路径只需要正常运行的 Hermes Agent。聊天、管理和标准语音都不需要额外插件。

## 1. 安装应用

- **Google Play**：适合大多数用户，自动更新。
- **Sideload APK**：从 [GitHub Releases](https://github.com/Codename-11/hermes-relay/releases) 下载最新 `android-v*` 版本中以 `-sideload-release.apk` 结尾的文件。该版本包含完整手机控制功能。

## 2. 启用 Hermes API

在运行 Hermes 的电脑上：

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

hermes gateway
```

`API_SERVER_HOST=0.0.0.0` 允许手机从局域网访问。必须保留强 API 密钥。远程使用请配置 [Tailscale 或 HTTPS](/guide/remote-access)，不要直接向互联网开放未加密端口。

## 3. 连接手机

在应用中选择以下任一方式：

1. 点击 **扫描局域网中的 Hermes**；
2. 手动填写 `http://<电脑IP>:8642` 和 API 密钥；
3. 扫描 Hermes 生成的设置二维码。

连接向导会分别检查聊天、管理、语音、远程路由和可选 Relay。Relay 显示未配对不会阻止标准功能。

## 4. 登录管理页面

如果需要从手机管理模型、密钥、技能或配置文件，请确保 Hermes Dashboard 正在运行，并在应用的 **管理** 页面登录一次。同一会话也会启用标准语音。

## 5. 可选：安装 Relay

仅在需要终端、手机控制、Relay 会话、媒体、实时语音或电脑工具时安装：

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
hermes relay start --no-ssl
hermes pair
```

扫描配对二维码后，应用会自动启用可用的 Relay 功能。完整服务器、TLS 和故障排除信息见[英文安装指南](/guide/getting-started)。
