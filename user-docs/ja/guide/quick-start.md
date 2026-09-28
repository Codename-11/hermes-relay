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

Dashboard は端末から到達可能で、認証プロバイダーが設定されている必要があります。`hermes dashboard` は既定でループバックだけを使います。まず認証を設定し、直接 LAN/VPN 接続する場合は `hermes dashboard --host 0.0.0.0 --port 9119 --no-open` で起動します。ループバックへ転送するリバースプロキシには外部の `dashboard.public_url` と認証プロバイダーが必要です。内部セッショントークンを端末へコピーしないでください。

**Hermes nearby** または **Remote gateway** を選びます。HTTPS を推奨します。それ以外の HTTP アドレスでは、この接続の正確なホストとポートについてリスクへの同意が必要です。アプリは VPN の保護を検出も強制もしません。VPN 切断時の認証情報や会話の露出リスクは利用者が負います。接続元を変更すると新たな同意とサインインが必要です。キャンセルすると元のアドレスが保持され、履歴と下書きは維持されます。Direct API は明示的な選択であり、Gateway チャットを自動で置き換えません。401 だけでは `dashboard.public_url` の誤設定を断定できません。

## 3. 標準 Hermes 接続を追加する {#other-supported-paths}

Android で **Connect** を開き、**Hermes nearby** を使うか、通常は
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
- **Direct API** が利用不可でも Chat はブロックされません。
- **Relay · Paired** は推奨拡張が有効であることを示します。Relay の障害が
  upstream 標準経路を妨げてはいけません。

## 6. 最初のメッセージを送る

Chat を開いてメッセージを送信します。ヘッダーの緑色の接続表示は、現在の
Hermes 接続が利用可能であることを示します。

[詳細なインストール →](/ja/guide/getting-started) ·
[トラブルシューティング →](/ja/guide/troubleshooting) ·
[英語の正規ガイド →](/guide/quick-start)
