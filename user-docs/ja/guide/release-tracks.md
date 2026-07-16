---
translation_status: ai-translated
canonical_source: /guide/release-tracks
---

# アプリの種類: Google Play または Sideload

Device Control が明確に必要でない限り、Google Play 版から始めてください。
2 つの版は同じコードから作られ、同じ端末に共存できます。

## 選択ガイド

| 質問 | Google Play | Sideload |
|---|---|---|
| 簡単なインストールと自動更新 | はい | いいえ |
| Chat、プロファイル、Manage、Voice | はい | はい |
| Relay のターミナル、メディア、通知 | はい | はい |
| 画面の読み取りやキャプチャ | いいえ | はい |
| タップ、入力、スワイプ、アプリ操作 | いいえ | はい |

## アプリの種類と Relay は別の選択

**アプリの種類**は Android に Device Control を含めるかを決めます。
オプションの **Relay プラグイン**は、ターミナル、メディア、通知、
デバイスチャンネルを Hermes ホストへ接続します。

Device Control は **Sideload + ペアリング済み Relay** でのみ動作します。
Chat、Manage、標準 Voice はどちらも必要としません。

## 後から切り替える

Google Play と Sideload は異なるアプリ ID を使用します。両方を試してから
片方を削除できます。設定とペアリングはアプリごとに保存されます。

[Google Play を開く](https://play.google.com/store/apps/details?id=com.axiomlabs.hermesrelay) ·
[Sideload APK を取得](https://github.com/Codename-11/hermes-relay/releases) ·
[英語の完全な比較 →](/guide/release-tracks)
