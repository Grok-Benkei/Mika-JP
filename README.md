# Mika JP

日本語で使うために調整した、Android向けのスマホ操作アシスタントです。[Wang Zan氏のMika](https://github.com/wangzan101/Mika)から派生したフォークです。

会話、ウェブ閲覧、ファイル処理、アプリの操作に使えます。AIの接続先はユーザーが選びます。画面やファイルの内容を扱うため、最初は読み取りだけの依頼で動作を確認してください。

## 日本向けの変更

- 画面、案内、エラーの表示言語と既定リソースを日本語に固定。
- 日本語と英数字のOCRモデルをAPKに同梱。画面読み取りのための初回モデルダウンロードは不要です。
- 日本語の依頼例、メモリ、学習スキル、検証理由、操作停止・エラー案内。
- LINE、X、楽天市場、メルカリ、Googleマップなどの日本語の知識と別名。
- 中国系サービスは海外リージョンも含め標準候補から外し、必要な場合だけ「中国系を表示（任意）」から選択。中継サービスでも、既知の中国系モデルを自動候補から除外。
- 日本語の送信・購入・決済ボタン、ログイン・認証画面の判定。
- 日本語以外のAI回答を見つけた場合の書き直しと、日本語での停止案内。
- 日本語のコメント・開発資料、問い合わせ先、フォーク専用の更新元。

日付と予定には端末の実際のタイムゾーンを使います。日本語だからといって、現在地や住所を東京に決めつけません。買い物では税込・税別、送料、ポイント還元、実際の支払額を区別します。

## 対応環境と導入

Mika JPはGalaxy専用のアプリではなく、以下の条件を満たすAndroid端末で利用できます。導入手順は[日本語の導入ガイド](docs/setup-guide.ja.md)を参照してください。

| 対象 | 条件 |
|---|---|
| インストール・会話・ウェブ閲覧・ファイル処理 | Android 11以降（minSdk 30） |
| スマホのアプリ操作（組み込みヘルパー） | Android 12以降（SDK 31以降） |
| 背景画面の独立した入力フォーカス | Android 14以降（SDK 34以降）を推奨（Android 12〜13では背景操作中にメイン画面の入力フォーカスが一時的に外れる場合があります） |
| Linuxサンドボックス | arm64（arm64-v8a）端末 |

バージョン条件を満たすすべてのメーカー・機種で検証済みではありません。無線デバッグ、ユーザー補助、バッテリーやバックグラウンド動作の制限など、メーカーごとの仕様や権限差は[日本語の導入ガイド](docs/setup-guide.ja.md)とアプリ内の「設定 › 復旧と修復 › 互換性セルフチェック」で確認してください。

**今回の実機検証環境**: Galaxy S26 Ultra（SM-S948Q、Android 17 / SDK 37 / arm64）で、互換性チェック12項目の正常通過、日本語表示・日本語OCR、表示構成、およびChatGPTログイン後の日本語応答を確認しています。Galaxy固有の制限や設定項目は[日本語の導入ガイド](docs/setup-guide.ja.md)と[日本向け調整の記録](docs/JAPAN_TUNING.md)に分けて記載しています。

開発版のアプリ名は **Mika JP**、アプリIDは **jp.studioyebisu.mika** です。公式Mikaと別にインストールでき、公式版の会話や設定を消しません。公式版から認証情報や会話を自動でコピーする機能はありません。

このフォークのリリースAPKがまだ公開されていない場合は、以下のビルド手順を使います。上流MikaのAPKは日本語調整版ではありません。

## ビルド

JDK 17、Android SDK Platform 36を使用します。SDKの場所をlocal.propertiesのsdk.dir、またはANDROID_HOMEで指定してください。

Windows:
~~~powershell
.\gradlew.bat :app:assemblePerf
~~~

macOS / Linux:
~~~sh
./gradlew :app:assemblePerf
~~~

成果物はapp/build/outputs/apk/perf/に作成されます。perfは操作感を確認するための、ローカルの開発用署名を使うビルドです。配布用の正式リリースや正式な署名管理とは区別してください。秘密鍵、APIキー、local.properties、生成APKをGitへ追加しないでください。

## 内蔵知識

画面固有の動作は、その時点の実画面で確認します。内蔵の案内があることは、各社アプリの全機能を実機検証したという意味ではありません。中国系アプリ・サイトの資料は[任意資料](optional/china/README.md)へ分離し、標準APKと自動候補には含めません。

<!-- generated:knowledge:start -->
<!-- ./gradlew updateReadme で生成します。手作業で編集しないでください -->
| 種類 | 内蔵 |
|---|---|
| アプリ | Androidの設定、Chrome、Gmail、Googleマップ、LINE、LinkedIn、メルカリ、楽天市場、Telegram、Uber、Uber Eats、WhatsApp、X |
| ウェブサイト | Amazon.co.jp、Googleカレンダー、Instagram、メルカリ、Gmail、Googleマップ、楽天市場、食べログ、Telegram、tenki.jp、Yahoo!路線情報、Yahoo!天気、WhatsApp、X、Yahoo! JAPAN、YouTube |
| スキル | Mikaの設定を変更する、動画や記事を日本語で整理する、PDFを読む・整理する、調査して出典を確かめる、メッセージとメール、スキルを作成・更新する |
<!-- generated:knowledge:end -->

## AIサービス

APIキーはサービスやリージョンごとに異なります。国際向けのキーと中国本土向けのキーを取り違えないでください。既存の接続先やキーを自動で別のサービスへ移すことはありません。モデルの接続試験・実行には、選んだサービスの料金や利用枠が適用されます。

<!-- generated:providers:start -->
<!-- ./gradlew updateReadme で生成します。手作業で編集しないでください -->
OpenAI、Anthropic、Gemini、OpenRouter、OpenCode Go、xAI、Mistral、Groq、Ollama、LM Studioを標準候補として用意しています。中国系サービスは海外リージョンも含め標準候補から外し、必要な場合だけ「中国系を表示（任意）」から選べます。その他は「カスタム」で接続できます。
<!-- generated:providers:end -->

## 検証と保守

調整した範囲、互換性、確認方法は[日本向け調整の記録](docs/JAPAN_TUNING.md)にまとめています。確認できた段階と未確認の機能を区別して記録します。

上流の[元のREADME](README.upstream.md)と多言語ドキュメントは記録として残しています。このフォークの導入手順は[日本語ガイド](docs/setup-guide.ja.md)が正本です。元のパッケージ名を使う上流用の導入コマンドと取り違えないでください。

## ライセンスと原作

原作: [Mika / Wang Zan](https://github.com/wangzan101/Mika)。日本向け調整: Studio Yebisu。

[Apache-2.0](LICENSE)、[NOTICE](NOTICE)、[第三者コンポーネント](licenses/THIRD_PARTY_NOTICES.md)を引き継いでいます。原作の著作権表示や第三者の権利は変更しません。

[不具合・提案](https://github.com/yebisu0529-ship-it/Mika-JP/issues) · [Studio Yebisu](https://x.com/studio_yebisu)
