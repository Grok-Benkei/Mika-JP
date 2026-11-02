# Mika JPへの改善提案

このフォークは日本語専用です。画面、通知、エラー、診断、AIの案内、コードの説明文には日本語を使います。ツール名、JSONキー、保存形式、実際の画面を照合する辞書、出典の引用原文は意味を変えずに保持してください。

## アプリやサイトの知識を追加する

[内蔵知識](../core/src/main/resources/knowledge/)にAPP.mdまたはSITE.mdを追加できます。Markdownのヘッダーと本文で、対象のパッケージやドメイン、利用時の確認事項を記載します。[形式の説明](../docs/dev/knowledge-format.md)を参照してください。複数のアプリに共通する作業は[スキル](../core/src/main/resources/skills/)へ追加します。

存在しない日本語ボタンや未確認の画面座標は記載しません。内蔵知識は操作の手掛かりであり、アプリの実画面を確認する代わりにはなりません。

## 不具合を報告する

[このフォークのIssues](https://github.com/yebisu0529-ship-it/Mika-JP/issues)に、端末の機種、Androidのバージョン、Mika JPのバージョン、期待した動作と実際の動作を記載してください。「設定 › 復旧と修復 › 互換性チェック」の結果は[端末の互換性を報告](https://github.com/yebisu0529-ship-it/Mika-JP/issues/new?template=device-report.yml)から送れます。画像や診断結果は本人の情報が含まれないか確認してください。

## コードを変更する

[構成](../docs/dev/architecture.md)、[開発状況](../docs/dev/roadmap.md)、[日本向け調整](../docs/JAPAN_TUNING.md)を参照してください。coreはAndroidに依存しないKotlinの処理、appはAndroidの画面・端末操作・設定を担当します。変更した動作に応じた検証を行い、未検証の範囲も記録してください。

必要な環境はJDK 17とAndroid SDKです。SDKの場所はlocal.propertiesのsdk.dirまたはANDROID_HOMEで指定します。CLIの検査にはPython 3.10以降が必要です。

~~~sh
./gradlew :app:assembleDebug
./gradlew :app:assemblePerf
./gradlew :core:test :app:testDebugUnitTest
./gradlew :core:cliTest -PmikaPython=/path/to/python3
./gradlew updateReadme
~~~

Windowsではgradlew.batを使います。操作感の確認にはperfを使います。現在のperfはローカルの開発用署名によるビルドで、正式配布用の署名管理とは区別します。APK、秘密鍵、APIキー、local.properties、ログをコミットしないでください。

READMEのgeneratedマーカー内はupdateReadmeで生成します。内蔵実行環境とネイティブバイナリの再構築は[実行環境の説明](../tools/sandbox/README.md)、端末での評価は[評価手順](../eval/README.md)を参照してください。OSに依存するテストの失敗は、変更前と同じ環境で比較して切り分けてください。

## モデルの接続

メインモデルにはツール呼び出しが必要です。画像を読むモデルと軽量モデルは別に設定できます。「テストして保存」は実際のAPIへ接続するため、選んだサービスの料金や利用枠が適用されます。検査を省略して保存した接続先や、APIが報告していない能力を、確認済みとして扱わないでください。

詳細は[モデルの設定](../docs/models.md)を参照してください。日本語以外の入力やモデルの回答が、日本語専用の表示・回答方針を上書きしないことも検証します。

## リリースと更新

フォークの更新先はこのリポジトリです。原作MikaのAPKとMika JPはパッケージ名・署名・設定が異なります。正式な公開やリリースは管理者が判断します。公開する場合は署名、アプリID、バージョン、チェックサム、ライセンスと対応ソースを確認してください。

原作の著作権表示、LICENSE、NOTICE、第三者コンポーネントの権利を保持します。サンドボックスのGPL対象コンポーネントと対応ソースについては[ライセンス案内](../licenses/THIRD_PARTY_NOTICES.md)と[再構築手順](../tools/sandbox/README.md)を参照してください。
