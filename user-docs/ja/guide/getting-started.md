---
translation_status: ai-translated
canonical_source: /guide/getting-started
---

# インストールと設定

このページは、ビルド選択、手動接続、リモートアクセス、セキュリティ確認の
詳細リファレンスです。Hermes Dashboard に到達できる場合は、
[クイックスタート](./quick-start)を使ってください。

<AndroidSetupPath mode="reference" />

::: tip 翻訳ステータス
この要約ガイドは一般的な導入手順を扱います。高度なサーバー、TLS、運用設定は
[完全な英語ガイド](/guide/getting-started)を参照してください。
:::

## 1. アプリを選ぶ

| | Google Play | Sideload |
|---|---|---|
| 推奨対象 | ほとんどのユーザー | Device Control を使うユーザー |
| 更新 | 自動 | APK を手動更新 |
| Chat、Voice、Manage、受信ファイル | 含まれる | 含まれる |
| Relay のターミナル、メディア拡張、通知 | 含まれる | 含まれる |
| 画面読み取り、タップ、入力、ナビゲーション | 含まれない | 含まれる |

<StoreBadge />

署名済み Sideload ファイルは `-sideload-release.apk` で終わり、
[GitHub Releases](https://github.com/Codename-11/hermes-relay/releases) から
取得できます。`.aab` は Google Play 用なのでインストールしないでください。

## 2. Hermes を到達可能にする

Android の標準接続先は `:9119` の Hermes Dashboard/Gateway です。Chat、
セッション、ログイン、Manage、標準 Voice、認証済み受信ファイルを提供します。`hermes dashboard`
で起動し、スマートフォンから到達できるようにします。

`:8642` の API サーバーはオプションです。明示的な Direct API 接続または
高度な headless 互換用途でのみ設定し、その場合だけ API キーが必要です。
`API_SERVER_KEY` はサーバー管理者が作成するもので、Dashboard からは発行されません。

::: warning ネットワークアクセスを保護する
暗号化されていない Dashboard、API、Relay ポートをインターネットへ
直接公開せず、リモートアクセスには Tailscale、VPN、HTTPS を使用します。
:::

ダッシュボードログインは、現在の Gateway ではネイティブ bearer、互換 Gateway
では厳密な origin の Cookie を使用し、どちらも短時間の Gateway チケットを
組み合わせます。API キーは別の認証情報です。

Android に保存するルートは LAN、Tailscale、公開アドレスのいずれでも構いません。
OIDC には到達可能な HTTPS コールバック
`<公開-dashboard-origin>/auth/callback` が必要です。Hermes は通常、信頼済み
プロキシヘッダーからこの origin を復元します。それが確実でない場合だけ upstream の
`dashboard.public_url` / `HERMES_DASHBOARD_PUBLIC_URL` を設定してください。
Android は異なるサインイン origin を保存前に検証します。

## 3. 接続して会話する

1. Android アプリで **Connect** を開きます。
2. LAN 検索、Dashboard/Gateway URL の入力、またはセットアップ QR を選びます。従来の API-first QR も互換です。
3. 求められた場合はダッシュボードへログインします。
4. **Connect** をタップし、**Chat · Ready** を確認します。
5. **セットアップを完了** で、バックグラウンドのチャット通知が必要なら Android の通知を有効にします。カメラ、マイク、その他の機能は引き続き任意で、個別に設定できます。すぐ進む場合は **今はしない** を選びます。
6. 必要なら後から **Advanced** で Direct API、Relay、リモートルートを追加します。

`hermes-relay-tailscale enable` は tailnet の専用 `:10443` に
`https://host.ts.net:10443` を公開し、同一 origin の Relay パスとともにローカル
Dashboard `:9119` へ転送します。意図的に直接公開した
`http://100.x.y.z:9119` も使用できますが、アプリケーション TLS はありません。

同じログインで Chat、セッション、Manage、Voice が有効になります。Relay が
未ペアリングでも、Direct API が利用不可でも正常です。

## 推奨: Relay でセットアップを完成する {#relay-server-optional}

upstream 標準経路は plugin なしでも動作します。Terminal/TUI、通知、
メディア、デスクトップツール、拡張 Voice、Relay セッション、任意の
Device Control には Relay を推奨します。正規コマンドは
`hermes plugins install Codename-11/hermes-relay/plugin --enable`、
`hermes relay doctor`、`hermes relay start --no-ssl`、`hermes pair` です。

Web Dashboard の **Relay** で、まず **Connect mobile app**、次に
**Pair new device** を開き、両方の QR を Android で読み取る方法が推奨です。
QR を使えない場合は URL/コード入力と `hermes pair --register-code` を使えます。

Device Control には **Sideload アプリとペアリング済み Relay の両方**が必要です。

[アプリの種類を比較 →](/ja/guide/release-tracks) ·
[英語のリモートアクセス →](/guide/remote-access) ·
[トラブルシューティング →](/ja/guide/troubleshooting)
