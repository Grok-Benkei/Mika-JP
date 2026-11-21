# DeepSeek Harness 統合 (初期実装)

この機能はトレード機能を含みません。DeepSeek Harness本体と、公式Web UIの
Plugins・MCP・サブエージェント・プロジェクト操作をMika-JPから利用します。
Mikaの既存Android画面操作権限・タスクエンジンとは接続しません。

## 利用方法

1. Mika-JPの「設定 → DeepSeek Harness」を開く。
2. 「導入」を選択。arm64端末、1.4GB以上の空き容量、初回約153MBのダウンロードが必要。
3. 固定SHA-256を検証したDebian/Node.js/DSHがアプリ専用領域へ展開される。
4. 「起動」を選ぶとローカルのHarness Web UIが開く。モデル設定はHarness側で行う。
5. Harness画面のPluginsから追加・管理・有効/無効切替を行う。
6. MCPサーバーはHarnessのMCP設定・プラグインから追加する。
7. 停止する際はMika画面か通知の「停止」を押す。

## 実装とデータ

- ランタイム: https://github.com/sorsama/dsh-mobile-runtime のrelease 2026.11.15-1
  (Node.js 24.22.3、DSH 0.2.2-rc.1)。
- arm64向けベース/ハーネスのtar.zstを固定済みSHA-256で検証して展開。
  バージョン更新は現在アプリ側で固定管理し、自動更新はしない。
- noBackupFilesDir/deepseek-harness/ に展開し、MikaのAlpine環境は変更しない。
- DSH home=/root、ワークスペース=/workspaces、プラグイン状態もHarness側に保存する。
- 既存MikaのPRootバイナリを使う。DSH Mobileとは版が異なるため、実機互換性は未検証。
- 127.0.0.1のランダムポートで起動し、一回限りのブラウザ認証URLはメモリだけに保持。
- WebViewから外部HTTPSを開く場合は通常のブラウザへ渡す。
- 新規セッションのコマンド権限は ask を指定する。
  プラグインのインストールスクリプトやMCPサーバーの全処理を保護するものではない。

## 制限と注意

- PRootは強固なサンドボックスではない。同じAndroid UIDのファイルが
  プラグインやシェルから読める可能性があるため、信頼できないプラグインは導入しない。
- 一部のプラグインはネイティブ実行形式やデスクトップGUIを必要とし、Androidでは使用できない。
- Androidのバックグラウンド停止制限で処理が停止する可能性がある。
- ランタイムの自動更新・専用削除UI・長時間動作保証は現段階の対象外。
- 実機での初回導入・起動・モデル接続・プラグイン操作・MCP接続を検証する必要がある。

## 第三者コード

- ター展開コードは DSH Mobile v0.24.0 のMITライセンスに基づく。
  https://github.com/sorsama/deepseek-harness-mobile/tree/v0.24.0
  Copyright (c) 2026 DSH Mobile contributors.
- zstd-jni 1.5.7-12: JNIはBSD-2-Clause。native zstdはBSD-3-Clause/GPL-2.0デュアル。
- DSHランタイムに含まれる各依存ライセンスは提供元に従う。
  ランタイムバイナリアーカイブはこのリポジトリへ収録していない。

## 検証手順

1. ./gradlew :app:assembleDebug :app:testDebugUnitTest
2. Android arm64実機で導入・起動・モデル設定を確認。
3. 公式Plugins画面でテストプラグインの追加・無効化・削除を確認。
4. MCP接続、停止、再起動、ネットワーク断、低ストレージを確認。
5. 保護対象の情報がHarnessへ送信されないこと、Mika操作権限が自動開放されないことを確認。
