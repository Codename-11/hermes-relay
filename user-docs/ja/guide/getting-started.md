---
translation_status: ai-translated
canonical_source: /guide/getting-started
---

# インストールと設定

手順は 3 つです。アプリをインストールし、Hermes に接続し、最初の
メッセージを送信します。Hermes がすでに動作している場合、サーバーへの追加
インストールは不要です。

::: tip 翻訳ステータス
この要約ガイドは一般的な導入手順を扱います。高度なサーバー、TLS、運用設定は
[完全な英語ガイド](/guide/getting-started)を参照してください。
:::

## 1. アプリを選ぶ

| | Google Play | Sideload |
|---|---|---|
| 推奨対象 | ほとんどのユーザー | Device Control を使うユーザー |
| 更新 | 自動 | APK を手動更新 |
| Chat、Voice、Manage | 含まれる | 含まれる |
| Relay のターミナル、メディア、通知 | 含まれる | 含まれる |
| 画面読み取り、タップ、入力、ナビゲーション | 含まれない | 含まれる |

<StoreBadge />

署名済み Sideload ファイルは `-sideload-release.apk` で終わり、
[GitHub Releases](https://github.com/Codename-11/hermes-relay/releases) から
取得できます。`.aab` は Google Play 用なのでインストールしないでください。

## 2. Hermes を到達可能にする

Android は通常 `:8642` の Hermes API サーバーを使用します。

- `API_SERVER_ENABLED=true` で API サーバーを有効にします。
- `API_SERVER_HOST=0.0.0.0` でネットワークから到達可能にします。
- `API_SERVER_KEY` は Chat リクエストを bearer キーで保護します。
- `hermes gateway` で Hermes と有効化された API サーバーを起動します。

::: warning ネットワークアクセスを保護する
`0.0.0.0` はネットワーク上の他のデバイスからの接続を許可します。強力な
API キーを使用してください。暗号化されていないポートをインターネットへ
直接公開せず、リモートアクセスには Tailscale、VPN、HTTPS を使用します。
:::

`:9119` のダッシュボードはオプションです。Manage と標準 Voice に使用され、
独自のログインがあります。API キーはダッシュボードのログイン情報ではありません。

## 3. 接続して会話する

1. Android アプリで **Connect** を開きます。
2. LAN 検索、セットアップ QR、または API URL とキーの入力を選びます。
3. **Connect** をタップします。
4. **Chat · Ready** が表示されることを確認します。
5. Chat を開いて最初のメッセージを送ります。

Manage と Voice はログインを求める場合があります。Relay が未ペアリングでも正常です。

## オプション: Relay ツールを追加する

ターミナル、Device Control、メディア、通知、高度なリモートツールが必要な
場合だけプラグインを追加します。正規コマンドは
`hermes plugins install Codename-11/hermes-relay/plugin --enable`、
`hermes relay doctor`、`hermes relay start --no-ssl`、`hermes pair` です。

Device Control には **Sideload アプリとペアリング済み Relay の両方**が必要です。

[アプリの種類を比較 →](/ja/guide/release-tracks) ·
[英語のリモートアクセス →](/guide/remote-access) ·
[トラブルシューティング →](/ja/guide/troubleshooting)
