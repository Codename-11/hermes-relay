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

Android の標準接続先は `:9119` の Hermes Dashboard/Gateway です。Chat、
セッション、ログイン、Manage、標準 Voice を提供します。`hermes dashboard`
で起動し、スマートフォンから到達できるようにします。

`:8642` の API サーバーはオプションです。Chat の自動フォールバックまたは
高度な headless 互換用途でのみ設定し、その場合だけ API キーが必要です。
`API_SERVER_KEY` はサーバー管理者が作成するもので、Dashboard からは発行されません。

::: warning ネットワークアクセスを保護する
暗号化されていない Dashboard、API、Relay ポートをインターネットへ
直接公開せず、リモートアクセスには Tailscale、VPN、HTTPS を使用します。
:::

ダッシュボードログインは Cookie と短時間の Gateway チケットを使用します。
API キーは別の認証情報で、ダッシュボードへのログインには使いません。

## 3. 接続して会話する

1. Android アプリで **Connect** を開きます。
2. LAN 検索、Dashboard/Gateway URL の入力、またはセットアップ QR を選びます。従来の API-first QR も互換です。
3. 求められた場合はダッシュボードへログインします。
4. **Connect** をタップし、**Chat · Ready** を確認します。
5. 必要なら後から **Advanced** で API fallback、Relay、リモートルートを追加します。

`http://100.x.y.z:9119` のような Tailscale Dashboard アドレスや、別途公開した
`.ts.net` アドレスは、API サーバーや API キーなしで追加・テストできます。

同じログインで Chat、セッション、Manage、Voice が有効になります。Relay が
未ペアリングでも、API fallback が利用不可でも正常です。

## オプション: Relay ツールを追加する

ターミナル、Device Control、メディア、通知、高度なリモートツールが必要な
場合だけプラグインを追加します。正規コマンドは
`hermes plugins install Codename-11/hermes-relay/plugin --enable`、
`hermes relay doctor`、`hermes relay start --no-ssl`、`hermes pair` です。

Device Control には **Sideload アプリとペアリング済み Relay の両方**が必要です。

[アプリの種類を比較 →](/ja/guide/release-tracks) ·
[英語のリモートアクセス →](/guide/remote-access) ·
[トラブルシューティング →](/ja/guide/troubleshooting)
