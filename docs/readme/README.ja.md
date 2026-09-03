# Hermes-Relay

**自分のマシンで動作し、いつものデバイスから使えます。**

[English](../../README.md) · [Deutsch](README.de.md) · [Español](README.es.md) · **日本語** · [Português (Brasil)](README.pt-BR.md) · [Русский](README.ru.md) · [简体中文](README.zh-CN.md)

> 英語版が完全かつ正規のプロジェクト説明です。この AI 支援翻訳は、
> 継続的に管理される簡潔な導入ページです。

Hermes-Relay は、[Hermes Agent](https://github.com/NousResearch/hermes-agent)
を Android と接続済みコンピューターから使えるようにします。Hermes 本体は
自分のマシンで動作し続け、Hermes-Relay がネイティブ UI と任意の拡張機能を提供します。

## 主な機能

- **Android:** ストリーミング Chat、Sessions、受信ファイル、Manage、Voice、Petdex。
- **標準接続:** Chat、Manage、Sessions、Voice、Files は、変更を加えていない Hermes Dashboard/Gateway に直接接続します。
- **任意の Relay 拡張:** Terminal/TUI、通知、デスクトップツール、拡張 Voice、Relay Sessions、メディア機能。
- **Sideload 版:** 画面読み取り、タップ、ナビゲーションなど、確認が必要な Device Control を追加します。
- **CLI / UI:** コンピューターを Relay に直接接続し、同意制のファイル、ターミナル、検索、スクリーンショットツールを提供します。

## Android クイックスタート

1. [Google Play](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay) からインストールするか、最新の [`android-v*` リリース](https://github.com/Codename-11/hermes-relay/releases)から署名済み Sideload APK をダウンロードします。
2. Hermes を実行しているマシンで標準の接続先を起動します。

   ```bash
   hermes dashboard
   ```

3. Android で **Connect** を開き、LAN 内の Hermes を検索するか Dashboard アドレスを入力してサインインします。標準経路では Relay も別の API キーも不要です。
4. 追加機能が必要な場合だけ Relay をインストールします。

   ```bash
   hermes plugins install Codename-11/hermes-relay/plugin --enable
   hermes relay doctor
   hermes relay start --no-ssl
   ```

   `--no-ssl` は信頼できる LAN または VPN 内でのみ使用してください。その後、Dashboard の **Relay → Pair new device** からペアリングします。

## 詳細

[日本語クイックスタート](https://hermes-relay.dev/docs/ja/guide/quick-start) ·
[インストール](https://hermes-relay.dev/docs/ja/guide/getting-started) ·
[トラブルシューティング](https://hermes-relay.dev/docs/ja/guide/troubleshooting) ·
[完全な英語ドキュメント](https://hermes-relay.dev/docs/)

[MIT ライセンス](../../LICENSE)
