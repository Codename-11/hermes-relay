---
translation_status: ai-translated
canonical_source: /guide/quick-start
---

# クイックスタート

まず標準の upstream Dashboard/Gateway 接続を設定します。その後、追加の
ツールや拡張が必要な場合に Relay をペアリングします。

<AndroidSetupPath mode="quick" />

::: tip 翻訳ステータス
このページは AI 支援で翻訳され、技術検証を通過しています。製品と
セキュリティの意味については英語版が正規情報です。
:::

## 1. アプリをインストールする

ほとんどのユーザーには **Google Play** 版が最短です。1 回の操作で
インストールでき、更新も自動で届きます。

<StoreBadge />

Hermes に画面の読み取り、タップ、文字入力、アプリ操作を許可したい場合は、
署名済みの **Sideload APK** を使用します。2 つの版は同時にインストールできます。

## 2. Hermes を起動する

Hermes Dashboard/Gateway が起動し、スマートフォンから到達できる必要があります。
必要に応じて `hermes dashboard` で起動します。サーバーの準備は
[インストールと設定](/ja/guide/getting-started)を参照してください。
これだけで標準の Chat、セッション、Manage、Voice、受信ファイルを利用できます。

## 3. 標準 Hermes 接続を追加する {#other-supported-paths}

Android で **Connect** を開き、**Find Hermes on LAN** を使うか、通常は
`http://<host>:9119` となる Dashboard アドレスを手入力します。求められたら
ログインします。これで plugin や Relay URL のない完全な標準接続が作成されます。

## 4. オプション: Relay をインストールしてペアリングする

標準接続が動作することを確認してから、推奨される完全な体験のために
Relay をインストールします。

```bash
hermes plugins install Codename-11/hermes-relay/plugin --enable
hermes relay doctor
hermes relay start --no-ssl
```

`--no-ssl` は信頼できる LAN または VPN でのみ使用してください。外出先からの
アクセスには [Tailscale を推奨します](/guide/remote-access)。

その後、Web Dashboard で **Relay → Pair new device** を開き、**Settings →
Connections → Pair Hermes Relay** から 1 回限りの QR を読み取ります。

API サーバーは任意のフォールバックです。Relay は upstream 標準経路には
必須ではありませんが、Terminal/TUI、通知、デスクトップツール、拡張 Voice、
Relay セッション、Device Control、メディア互換性やメタデータのために推奨されます。
通常の受信ファイルには現在の Dashboard ルートを使用します。

## 5. 状態を確認する

- **Chat · Ready** ならメッセージを送信できます。
- **Manage** ではダッシュボードへのログインを求められる場合があります。
- **Voice** も同じダッシュボードセッションで有効になります。
- **API fallback** が利用不可でも Chat はブロックされません。
- **Relay · Paired** は推奨拡張が有効であることを示します。Relay の障害が
  upstream 標準経路を妨げてはいけません。

## 6. 最初のメッセージを送る

Chat を開いてメッセージを送信します。ヘッダーの緑色の接続表示は、現在の
Hermes 接続が利用可能であることを示します。

[詳細なインストール →](/ja/guide/getting-started) ·
[トラブルシューティング →](/ja/guide/troubleshooting) ·
[英語の正規ガイド →](/guide/quick-start)
