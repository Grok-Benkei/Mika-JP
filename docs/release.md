# Mika JPの公開準備と配布

このリポジトリから配布するMika JPは、Studio Yebisuによる日本向けフォークです。原作MikaとはアプリID・更新元・署名を分けて管理します。公開済みの配布物は[Mika JPのリリース](https://github.com/yebisu0529-ship-it/Mika-JP/releases)を参照してください。この文書は、次の版を準備・検証して公開する保守者向けの手順です。

## 配布するファイル

同じコミットから次を用意し、同じGitHub Releaseへ添付します。

- 公開用の鍵で署名したarm64 APKとuniversal APK。
- アプリ本体のソースアーカイブ。
- 同梱プログラムの対応ソース、ビルド用の設定・パッチ・ライセンスをまとめたアーカイブ。
- SHA256SUMSと、版・コミット・署名証明書・ABIを記録したrelease-manifest.json。

arm64以外の端末でLinuxサンドボックスは使えません。Galaxyは今回の検証環境で、対応条件を満たすすべての機種を検証したという意味ではありません。root取得済み端末の機能は明示許可制・既定無効で、root実機の確認は未実施です。

## 署名

公開用の署名鍵はリポジトリ外で管理します。鍵とパスワードはGit、ログ、チャット、Releaseへ含めません。今後の更新にも同じ鍵を使うため、公開前に所有者が管理・バックアップ方法を確認します。

releaseビルドは、次の環境変数がすべて指定されたときだけ公開用の署名を使います。

| 環境変数 | 内容 |
|---|---|
| MIKA_RELEASE_KEYSTORE | リポジトリ外の署名鍵ファイル |
| MIKA_RELEASE_STORE_PASSWORD | キーストアのパスワード |
| MIKA_RELEASE_KEY_ALIAS | 使用する鍵の別名 |
| MIKA_RELEASE_KEY_PASSWORD | 鍵のパスワード |

指定がないrelease APKは未署名です。perf/debugのAndroid開発用署名を公開用に流用しません。既存の開発版と公開用の署名が異なる場合、そのAPKは上書きインストールできません。会話や認証状態を失うアンインストールを自動で行わず、移行方法を本人が判断します。

## 検査とビルド

JDK 17とAndroid SDK Platform 36を用意します。先に変更内容に対応する検査と、Linux CIの結果を確認します。Windowsに依存する既存の失敗と、新しい失敗を混同しません。

```sh
python3 -m unittest discover -s tools/release -p 'test_*.py'
./gradlew :core:test :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

Windowsではgradlew.batを使います。公開用パスワードは端末上の非表示入力や資格情報管理から環境変数へ渡し、作業後に解除します。CIへ秘密情報を追加して自動公開する設定は含めていません。

## 対応ソースとチェックサム

以下のPythonは3.10以降を使います。出力先は作業用フォルダーです。配布する版に合わせてVERSIONを置き換えます。

```sh
python3 tools/release/package_sources.py --output build/release --version VERSION
git archive --format=tar.gz --prefix=Mika-JP-VERSION-source/ -o build/release/Mika-JP-VERSION-source.tar.gz HEAD
```

対応ソースの作成では、固定したPRoot/tallocをSHA-256で、同梱実行環境のGPL/LGPL/MPL対象をSHA-512で検証します。既存の対応ソースも再検証し、必要なパッケージやファイルが欠けていれば失敗します。

ビルドしたAPKを同じ出力フォルダーへ置き、次を実行します。実際のAPK名とVERSIONを指定してください。

```sh
python3 tools/release/verify_release.py --apk build/release/Mika-JP-VERSION-arm64.apk --apk build/release/Mika-JP-VERSION-universal.apk --sources build/release/Mika-JP-VERSION-corresponding-sources.tar.gz --project-source build/release/Mika-JP-VERSION-source.tar.gz --output build/release
```

この検査は、アプリID・版・コミット・署名・対応ソースを確認し、チェックサムを作ります。未署名の準備物を調べる場合だけ--allow-unsignedを指定できます。その結果は準備用として記録し、公開用の署名確認にはなりません。

## リリースの下書きと公開

新しい候補版はpre-releaseの下書きとして準備します。公開前に管理者が、署名証明書の指紋、添付ファイル、検証済み・未検証の範囲、変更履歴、プライバシーとライセンスの案内を確認します。下書きの保存と、一般公開・正式採用は区別します。

公開後のリリースを差し替える場合は、利用者が照合できるよう、変更内容と新しいチェックサムを記録します。mainには統合済みの履歴を残し、マージ後の作業ブランチは削除します。
