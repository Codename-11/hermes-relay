---
translation_status: ai-translated
canonical_source: /guide/troubleshooting
---

# トラブルシューティング

画面に見えている症状から始めます。Android、ネットワーク、Hermes ホストの
どこに問題があるかをすばやく切り分けられます。

- [赤い表示または接続なし](#接続できない)
- [「No reachable endpoint」](#到達可能なエンドポイントがない)
- [Chat がストリーミングしない](#chat-がストリーミングしない)
- [Manage または Voice がログインを求める](#manage-と-voice)
- [セッションが表示されない](#セッションが表示されない)
- [起動時にアプリがクラッシュする](#起動時のクラッシュ)

## 接続できない

1. ホストで `hermes dashboard` が動作していることを確認します。
2. **スマートフォン**から Dashboard/Gateway アドレス（通常 `http://<host>:9119`）を開きます。
3. ファイアウォールを確認し、再ログインして新しい `/api/ws` チケットを取得します。
4. スマートフォンで `localhost` や `127.0.0.1` を使用しないでください。これらはスマートフォン自身を示します。

## 到達可能なエンドポイントがない

保存済みの LAN、Tailscale、公開ルートをすべて確認しましたが、応答がありません。
LAN アドレスは同じ Wi-Fi 内でのみ動作します。Tailscale ではスマートフォンと
サーバーの両方が接続済みである必要があります。
`http://100.x.y.z:9119` のような Dashboard ルートは、API サーバーや API キーなしで確認されます。

## Chat がストリーミングしない

- Dashboard/Gateway URL、ログイン、`/api/ws` を確認します。
- エラーが表示された場合は **Retry** を 1 回タップします。
- Hermes サーバーのログを確認します。
- オプションの API fallback は別に診断します。障害があっても正常な Gateway 接続はブロックしません。
- ローカルモデルは数分かかる場合があります。Android がバックグラウンド接続を切断しても、再接続時に完了済みの応答を取得します。

意図的に設定した API fallback が利用できない場合は、`API_SERVER_ENABLED`、
bind アドレス、`http://<host>:8642/health`、管理者が作成した `API_SERVER_KEY`、
ファイアウォールを確認します。Dashboard ログインではこのキーは作成されません。

## Manage と Voice

Manage と標準 Voice は `API_SERVER_KEY` ではなくダッシュボードセッションを
使用します。Manage から一度ログインし、スマートフォンからダッシュボードへ
到達できることを確認してください。

## セッションが表示されない

セッション切り替え時にはサーバーへ接続できる必要があります。大きな
セッションでは読み込みに時間がかかるため、進行表示を待ってください。

## 起動時のクラッシュ

Android の **設定 → アプリ → Hermes-Relay → ストレージ** でアプリデータを
削除し、Dashboard/Gateway アドレスとログインを再設定します。

Relay の問題には、読み取り専用診断の `hermes relay doctor` を使用できます。

[英語の完全なトラブルシューティング →](/guide/troubleshooting) ·
[インストール →](/ja/guide/getting-started)
