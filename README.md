# Mika JP

日本語で使うために調整した、Android向けのスマホ操作アシスタントです。[Wang Zan氏のMika](https://github.com/wangzan101/Mika)から派生したフォークです。

会話、ウェブ閲覧、ファイル処理、アプリの操作に使えます。AIの接続先はユーザーが選びます。画面やファイルの内容を扱うため、最初は読み取りだけの依頼で動作を確認してください。

## 日本向けの変更

- 画面、案内、エラーの表示言語と既定リソースを日本語に固定。
- 日本語と英数字のOCRモデルをAPKに同梱。画面読み取りのための初回モデルダウンロードは不要です。
- 日本語の依頼例、メモリ、学習スキル、検証理由、操作停止・エラー案内。
- LINE、X、楽天市場、メルカリ、Googleマップなどの日本語の知識と別名。
- グローバル向けのAIサービスを先に表示。中国本土向けの接続先は「他のリージョン」から選びます。
- 日本語の送信・購入・決済ボタン、ログイン・認証画面の判定。
- 日本語以外のAI回答を見つけた場合の書き直しと、日本語での停止案内。
- 日本語のコメント・開発資料、問い合わせ先、フォーク専用の更新元。

日付と予定には端末の実際のタイムゾーンを使います。日本語だからといって、現在地や住所を東京に決めつけません。買い物では税込・税別、送料、ポイント還元、実際の支払額を区別します。

## Galaxyで試す

[日本語の導入ガイド](docs/setup-guide.ja.md)を参照してください。Android 11以降にインストールでき、アプリ操作はAndroid 12以降、背景画面の独立した入力にはAndroid 14以降を推奨します。Linux実行環境はarm64向けです。

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

画面固有の動作は、その時点の実画面で確認します。内蔵の案内があることは、各社アプリの全機能を実機検証したという意味ではありません。指定のない日本向けタスクで、中国本土向けのサービスを標準の入口として選びません。

<!-- generated:knowledge:start -->
<!-- ./gradlew updateReadme で生成します。手作業で編集しないでください -->
| 種類 | 内蔵 |
|---|---|
| アプリ | Androidの設定、百家号、Bilibili、BOSS直聘、Chrome、Dedao、Douyin、Feishu、Gmail、Googleマップ、Jike、Kuaishou、LINE、LinkedIn、メルカリ、QQ Mail、楽天市場、Telegram、Toutiao、Uber、Uber Eats、WeChat、Weibo、WhatsApp、X、Xianyu、小紅書（RED）、Zhihu |
| ウェブサイト | Amazon.co.jp、Bilibili、Googleカレンダー、Douban、Douyin、Instagram、メルカリ、Gmail、Googleマップ、楽天市場、食べログ、Telegram、tenki.jp、Baidu Tieba、Yahoo!路線情報、Yahoo!天気、Weibo、Sogou WeChat検索、WhatsApp、X、小紅書（RED）、Xiaoyuzhou、Yahoo! JAPAN、YouTube、Zhihu、BOSS直聘 |
| スキル | Mikaの設定を変更する、動画や記事を日本語で整理する、PDFを読む・整理する、調査して出典を確かめる、メッセージとメール、スキルを作成・更新する |
<!-- generated:knowledge:end -->

## AIサービス

APIキーはサービスやリージョンごとに異なります。国際向けのキーと中国本土向けのキーを取り違えないでください。既存の接続先やキーを自動で別のサービスへ移すことはありません。モデルの接続試験・実行には、選んだサービスの料金や利用枠が適用されます。

<!-- generated:providers:start -->
<!-- ./gradlew updateReadme で生成します。手作業で編集しないでください -->
OpenAI、Anthropic、Gemini、OpenRouter、OpenCode Go、xAI、Mistral、Groq、DeepSeek、Qwen（シンガポール）、Kimi、MiniMax、StepFun、Ollama、LM Studio、Qwen（中国・北京）、Kimi（中国本土）、GLM（中国本土）、GLM Coding Plan（中国本土）、SiliconFlow（中国本土）、Volcengine Ark（中国本土）、MiniMax（中国本土）、Tencent Hunyuan（中国本土）、Baidu Qianfan（中国本土）を内蔵しています。その他は「カスタム」で接続できます。中国本土向けは「他のリージョン」に表示します。
<!-- generated:providers:end -->

## 検証と保守

調整した範囲、互換性、確認方法は[日本向け調整の記録](docs/JAPAN_TUNING.md)にまとめています。確認できた段階と未確認の機能を区別して記録します。

上流の[元のREADME](README.upstream.md)と多言語ドキュメントは記録として残しています。このフォークの導入手順は[日本語ガイド](docs/setup-guide.ja.md)が正本です。元のパッケージ名を使う上流用の導入コマンドと取り違えないでください。

## ライセンスと原作

原作: [Mika / Wang Zan](https://github.com/wangzan101/Mika)。日本向け調整: Studio Yebisu。

[Apache-2.0](LICENSE)、[NOTICE](NOTICE)、[第三者コンポーネント](licenses/THIRD_PARTY_NOTICES.md)を引き継いでいます。原作の著作権表示や第三者の権利は変更しません。

[不具合・提案](https://github.com/yebisu0529-ship-it/Mika-JP/issues) · [Studio Yebisu](https://x.com/studio_yebisu)
