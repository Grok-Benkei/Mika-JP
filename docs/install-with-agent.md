# PCのAIアシスタントとMika JPを導入する

PCでコマンドを実行できるAIアシスタントと、USB接続したAndroidへMika JPを導入するための手順です。スマホへのインストールと設定が対象です。PCからのタスク実行も必要な場合だけ、最後の接続設定を行います。

設定済みのスマホへPCから接続するだけなら、再インストールせず[CLIとスキルの導入](computer-install.md)または[MCPガイド](mcp.md)へ進んでください。通常のスマホでの導入は[日本語ガイド](setup-guide.ja.md)が正本です。

依頼文の例:

> このリポジトリの docs/install-with-agent.md を読み、USB接続したAndroidへMika JPを導入してください。上流MikaのAPKと取り違えず、既存のアプリやデータを保持してください。本人の操作が必要な箇所は案内してください。

## 作業範囲

1. 依頼された導入・設定だけを行います。無関係な画面ロック、画面消灯時間、音、ネットワーク、開発者向け設定を変更しません。データ消去を伴う設定は使いません。
2. 既存アプリをアンインストールせず、データも消しません。署名の相違で更新できない場合は、理由とデータへの影響を説明して止めます。
3. APIキー、パスワード、ペア設定コードをチャットで求めたり、adbで取得・入力・転送したりしません。モデルのログインとキー入力は本人がスマホで行います。
4. 端末のUSBデバッグ許可、インストール確認、システムの認証などは本人が対応します。必要な画面と操作を案内し、完了を確認して続けます。
5. 段階ごとに目的を日本語で簡潔に伝えます。失敗時は秘密値を含まない診断と原因を示し、未確認の結果を成功扱いにしません。
6. PC接続は、ユーザーがPCからの利用を依頼した場合だけ有効にします。接続確認だけで実際のタスクを送信しません。タスクの検証は依頼された範囲に限ります。

以下のシェル例はmacOS・Linux・Git Bashなど向けです。PowerShellでは同じ処理へ書き換えてください。説明中の `SERIAL` と `/path/to/Mika-JP.apk` は、確認した対象へ置き換えます。

## 1. PCと対象端末を確認する

```sh
adb version
adb devices
```

adbがない場合はAndroid SDK Platform-Toolsを用意します。対象が `device` と表示される必要があります。`unauthorized` なら本人がスマホでUSBデバッグを許可します。端末がない場合は、データ通信ができるケーブル、USBデバッグ、USBの接続モードを確認してください。[スマホ操作の設定](setup-guide.md)も参照できます。

複数端末があれば本人の指定から対象を決め、以後はすべて `adb -s SERIAL` で固定します。失敗を理由に別の端末へ切り替えません。

```sh
adb -s SERIAL shell getprop ro.build.version.sdk
adb -s SERIAL shell getprop ro.product.cpu.abi
```

| 結果 | 対応 |
| --- | --- |
| SDK 30未満 | Android 11未満なので導入できません。理由を伝えて終了します。 |
| SDK 30（Android 11） | 会話・ウェブ・ファイル処理は利用できます。依頼がアプリ操作なら非対応と伝えます。 |
| SDK 31〜33（Android 12・13） | アプリ操作に対応しますが、背景操作がメイン画面の入力フォーカスへ影響する場合があります。 |
| SDK 34以降（Android 14以降） | 背景画面が独立した入力フォーカスを持ちます。 |
| arm64-v8a | Linux実行環境に対応します。 |
| その他のABI | APKの導入は可能ですが、同梱のLinux実行環境は使えません。 |

## 2. このフォークのAPKを用意する

[Mika JPのリリース](https://github.com/yebisu0529-ship-it/Mika-JP/releases)から端末に合うAPKを取得し、同じリリースのSHA256SUMSと照合します。上流 `wangzan101/Mika` のリリースAPKは別アプリです。それを導入してからMika JPのパッケージへ権限を付与する手順は使いません。

### ソースからビルドする場合

ビルドにはJDK 17、Android SDK Platform 36が必要です。SDKの場所を `local.properties` の `sdk.dir` または `ANDROID_HOME` に指定し、リポジトリのルートで実行します。

Windows:

```powershell
.\gradlew.bat :app:assemblePerf
```

macOS・Linux:

```sh
./gradlew :app:assemblePerf
```

APKは `app/build/outputs/apk/perf/` に作成されます。これはローカルの開発用署名によるビルドです。正式なリリースや配布用署名を用意したことにはなりません。詳細は[ルートREADME](../README.md#ビルド)を参照してください。

既存APKを使う場合も、用意したソース版・ビルド種別・アプリIDを確認します。Android SDKの `apkanalyzer` がある場合の確認例:

```sh
apkanalyzer manifest application-id /path/to/Mika-JP.apk
```

結果は `jp.studioyebisu.mika` です。確認できないAPKへ、このガイドの設定を機械的に適用しないでください。

## 3. APKをインストールする

```sh
adb -s SERIAL install -r /path/to/Mika-JP.apk
```

成功時は `Success` が返ります。スマホにインストール確認が出た場合は本人が対応します。

| エラー | 対応 |
| --- | --- |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 同じアプリIDでも署名が異なります。アンインストールやデータ消去で自動解決しません。既存データを保持できる署名・APKを確認します。 |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | 既存版の方が新しい状態です。強制的に戻さず、その版を使うか目的を確認します。 |
| `INSTALL_FAILED_USER_RESTRICTED` / `INSTALL_FAILED_ABORTED` | 本人の拒否や端末側の制限を確認します。Galaxyの自動ブロッカーなどの変更は、本人が影響を確認して判断します。 |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | 空き容量が不足しています。無関係なファイルを自動で削除しません。 |

上流Mikaは別のアプリIDなので、Mika JPのために削除する必要はありません。上流版の会話・設定・認証情報を自動でコピーすることもしません。

## 4. アプリを開き、通知を設定する

```sh
adb -s SERIAL shell am start -n jp.studioyebisu.mika/com.wangzan101.mika.ui.MainActivity
```

アプリIDとクラスの名前空間は異なります。コンポーネントは短縮せず、クラス名を `com.wangzan101.mika.ui.MainActivity` と記載します。

SDK 33以降で通知を設定する場合:

```sh
adb -s SERIAL shell pm grant jp.studioyebisu.mika android.permission.POST_NOTIFICATIONS
```

通知は進捗、結果、本人への引き継ぎ、ペア設定の案内に使います。Android 12以前ではこの実行時権限の付与は不要です。

## 5. 必要な場合だけスマホ操作を設定する

Android 11、または会話・ウェブ・ファイル処理だけなら、ユーザー補助とスマホ操作ヘルパーは不要です。この段階を省略しても導入失敗ではありません。

アプリ操作に必要なユーザー補助のコンポーネントは次のとおりです。

```text
jp.studioyebisu.mika/com.wangzan101.mika.a11y.MikaAccessibilityService
```

クラス名を `jp.studioyebisu.mika` へ変更しません。既存のユーザー補助サービス一覧を消さず、Mika JPがなければ追加します。以下は既存一覧を保持する例です。読み取った一覧をチャットや公開ログへ転載する必要はありません。

```sh
ME=jp.studioyebisu.mika/com.wangzan101.mika.a11y.MikaAccessibilityService
CUR=$(adb -s SERIAL shell settings get secure enabled_accessibility_services | tr -d '\r')
case ":$CUR:" in
  *":$ME:"*) NEW="$CUR" ;;
  *)
    if [ -z "$CUR" ] || [ "$CUR" = "null" ]; then
      NEW="$ME"
    else
      NEW="$CUR:$ME"
    fi ;;
esac
adb -s SERIAL shell settings put secure enabled_accessibility_services "'$NEW'"
adb -s SERIAL shell settings put secure accessibility_enabled 1
```

未設定時の `null` は空として扱います。サービス名に `$` が含まれる場合もあるため、書き戻し時の引用を保ちます。設定後はMika JPのサービスが有効で、既存のサービスも保持されたことを確認してください。シェル設定を使わない場合は[ウィザード](setup-guide.md)で設定できます。

### ヘルパーの起動方法を選ぶ

- 無線デバッグのペア設定: 本人がスマホで一度ペア設定します。必要な許可とWi-Fi接続があれば、再起動後の自動復旧を試みます。
- USBでの起動: ペア設定せず、PCのコマンドで起動します。この方法だけならスマホの再起動後に再実行します。

ユーザーが方法を指定したらそれに従います。USBで先に起動し、後から本人が無線のペア設定を行う方法もあります。スマホ内ヘルパーのペア設定は、PCの無線ADB接続やコンピューターアクセスの許可とは別です。

無線方式は、本人にMika JPのウィザードを開いてもらい、システムのペア設定ウィンドウから通知へコードを入力してもらいます。PCのアシスタントはコードを取得・入力しません。詳しい手順は[スマホ操作の設定](setup-guide.md)を参照してください。

USB方式では、Mika JPを一度開いてから実行します。

```sh
adb -s SERIAL shell sh /sdcard/Android/data/jp.studioyebisu.mika/files/usb-start.sh
```

ケーブルを外してもヘルパーは動作します。ファイルがない場合はアプリを開き、少し待って再試行します。起動ファイルには一度限りの接続情報が含まれるため、内容を取り出して報告しません。最終的にはMika JPの設定でスマホ操作が利用可能になったことを確認します。

## 6. 必要に応じて背景実行を設定する

背景タスクや予約タスクを使う場合は、省電力対象からMika JPを除外できます。

```sh
adb -s SERIAL shell cmd deviceidle whitelist +jp.studioyebisu.mika
```

このコマンドが使えない端末では、同じ操作の `dumpsys deviceidle whitelist +jp.studioyebisu.mika` を使用できます。設定変更の理由を伝え、無関係な省電力設定や他アプリの設定は変更しません。

## 7. モデルを本人が設定する

スマホの「設定 › モデル」から、対応するアカウントでログインするか、本人がAPIキーを入力します。[モデルの設定](models.md)を参照してください。

PCのアシスタントはキーやログイン情報を求めず、adbで入力・保存しません。「テストして保存」は実際のAPIへ接続し、利用料やアカウントの利用枠が適用されます。モデル接続を本人が行っていない場合は、未設定・未検証として残します。

## 8. 結果を確認する

```sh
adb -s SERIAL shell dumpsys package jp.studioyebisu.mika
```

インストールされた版、必要な通知・ユーザー補助、ヘルパーの状態を確認します。アプリ操作を使わない構成では、ユーザー補助やヘルパーが未設定でも問題ありません。互換性チェックは[導入ガイド](setup-guide.ja.md)を参照してください。

実際のタスク確認も依頼されている場合は、対象と範囲を守って単純なタスクを実行します。本人がスマホから試す例:

> 設定を開いて、Androidのバージョンを教えて

タスクを実行していなければ「導入・設定を確認したが、モデルを使ったタスクは未検証」と報告します。コマンドの成功だけで、モデルや他社アプリの操作まで成功したと扱いません。

## 9. PCからの利用を依頼された場合だけ接続する

通常はCLIとスキル、またはMCPを一つ選びます。

- AIエージェントがスキルを使う: [スキルの導入](computer-install.md#skill安装)に従います。同梱CLIがあるので、別のグローバルCLIは不要です。
- ターミナルやスクリプトで使う: [CLIの導入](computer-install.md#cli安装)に従います。
- MCPクライアントで使う: [MCPガイド](mcp.md)に従います。CLIやスキルを追加で入れる必要はありません。

同じ端末を指定して接続確認し、実際にクライアントがスキルやMCPツールを読み込んだことを別に確認します。接続だけを依頼された場合はタスクを送信しません。トークンはクライアント内の設定や権限を制限したファイルで扱い、チャットへ表示しません。

USBではスマホの再起動後に再接続が必要です。CLIは `connect` を再実行し、MCPは起動と転送をやり直して新しいトークンを設定します。ケーブルの抜き差しだけなら、手動MCP転送をやり直します。詳しくは各ガイドを参照してください。

## 最後に報告すること

日本語で、確認できた結果と残る作業を伝えます。

- APKの版、ソースとビルド種別、アプリID、インストールの成否。
- 通知、ユーザー補助、省電力の除外、ヘルパーについて、必要だったもの・設定したもの・省略したもの。
- モデルの設定・接続確認・互換性チェック・実際のタスク成功を区別した結果。
- PC接続も依頼された場合は、選んだ入口、導入先、版、接続確認、クライアントでの読み込み確認。
- 本人に残る操作と、USB方式を使う場合の再起動後の手順。

エラー、未設定、未検証の項目を隠さず、認証情報や端末内の一覧そのものを報告に含めないでください。
