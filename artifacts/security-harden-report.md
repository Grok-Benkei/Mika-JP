# Mika JP セキュリティ強化レポート

基準コミット: `9cf371e900ebafc63856b9ca848cecee0ed08a43`（`fix/deepseek-flash-reliability`）
作業ブランチ: `security/harden-android-release`
監査: `mika-jp-security-audit.md`（2026-11-05）

このレポートは静的な変更と、この作業環境で実行したユニットテストおよび APK 検査の結果です。緑のテストや CI は、個人のスマホで安全だという意味ではありません。動的な通信キャプチャは未実施です。

## 1. 所見

| ID | Before | After | Status |
| --- | --- | --- | --- |
| F-01 | 公開されたのは `android:debuggable=true` の debug APK。debug 証明書。 | `release` は `isDebuggable=false` かつ `isJniDebuggable=false`。debug 専用の Compose test manifest は debug のまま。release 用の debug-overrides は無い。署名秘密がリポジトリから見えず（`gh secret list` は 403）、ローカルと CI の既定は **未署名**。未署名 APK は配布用でも個人端末用でもない。 | MITIGATED |
| F-02 | `base-config cleartextTrafficPermitted="true"`。クラウドの `http://` は警告のみ。 | base-config は平文拒否。例外はホスト名 `localhost` のみ。コードでも検査する。クラウドの HTTP は拒否。ループバック HTTP（Ollama / LM Studio）は許可し、`127.0.0.1` / `::1` は接続前に `localhost` へ書き換える。LAN の平文は明示スイッチがオンのときだけコードが許可する。https→http のリダイレクトは拒否。WebSocket は `wss://` を優先し、公開 `ws://` は拒否。 | MITIGATED |
| F-03 | 画面・通知・スクリーンショットは設定済みモデルへ送る。承認の既定は確認モード。画面テキストの一部は trusted。 | 製品の送信経路は残す。追加時にプロバイダ名と実際のホストを表示。クラウド HTTP は保存できない。宛先が変わると通知（鍵は出さない）。`ApprovalSettings.ASK` が既定で、モデル出力と画面文字では変わらない。`settings_set` は安全制御キーを拒否。銀行・認証・パスワードマネージャ、およびパッケージ不明の画面はスクリーンショットと通知本文を送らない。電話画面の観察は `Trust.Untrusted`。プロンプト注入の回帰は 12 件。 | MITIGATED |
| F-04 | `WRITE_SECURE_SETTINGS` を宣言。通常インストールでは付与できない。付与されるとユーザー補助を自分で書き戻せる。 | マニフェストから削除。`canWriteSecure()` は通常インストールで常に false。再接続はペアリング済みシェルがあるときだけ。シェルも権限も無いときは設定を書き換えない（テスト追加）。ワイヤレスデバッグの自己復旧も、この権限が無いので動かない。 | FIXED |
| F-05 | MCP は既定オフ。起動時は保存されたスイッチに従う。オンのときもプライベートアドレスだけ受ける。 | 既定オフと再起動後もオフのまま、はコードどおり。スイッチの説明に、オフは再起動後もオフ、オンのときだけプライベート待受を再開し、公開アドレスは受けない、と書いた。 | MITIGATED |
| F-06 | 公式 HTTPS の APK URL と署名一致はあった。SHA-256 はメタデータがあるときだけ。 | 公式更新のインストールは `sha256:` が無いと拒否。更新確認の HTTPS は `api.github.com` のみ（テストのループバックは例外）。署名一致は維持。検証失敗は閉じる。ダイジェストの無いリリースは入れない。 | FIXED |
| F-07 | 端末 DNS が読めないと `1.1.1.1` と `8.8.8.8` を resolv.conf に書く。 | 端末 DNS だけを書く。読めないときは nameserver を書かず、名前解決は失敗して閉じる。端末自身のリゾルバが公共 DNS なら、それは端末の設定として残す。 | FIXED |
| F-08 | `contents: read` と artifact 用の `actions: write`。アクションは SHA 固定。debug APK をアップロード。 | 権限は同じ最小構成。SHA ピンは維持。秘密はログに出さない。一時キーストアはビルド後に削除し、成果物に含めない。debug と非デバッグ release、SHA-256、テスト結果、build-info をアップロード。release が debuggable ならジョブを失敗させる。 | MITIGATED |
| F-09 | ML Kit の文字認識を同梱。Play services / datatransport の初期化コンポーネントがマージされる。 | OCR は残した。同梱モデルを外すと初回ダウンロードが増える。公開の「テレメトリを切る」フラグは、壊さずに使えるものを確認できなかった。コンポーネント削除は OCR を壊す可能性があるのでしていない。 | ACCEPTED |
| F-10 | サンドボックスのビルドスクリプトは HTTPS と SHA-256 と APK 署名検証。Alpine の制御セクションに SHA-1。 | 実行時の APK には入っていない。`runtime-sources.py` も非 HTTPS を拒否するようにした。SHA-256 と署名検証は残す。Alpine の SHA-1 制御セクションは置き換えない。 | MITIGATED |

## 2. テスト

| スイート | total | pass | fail | skip |
| --- | --- | --- | --- | --- |
| `:core:test` | 2974 | 2972 | 0 | 2 |
| `:app:testDebugUnitTest` | 1486 | 1486 | 0 | 0 |
| `:core:cliTest` | 1 | 1 | 0 | 0 |
| 合計 | 4461 | 4459 | 0 | 2 |

以前の目安は約 4435 pass / 0 fail / 2 skip。失敗を消すためにテストは無効化していない。DeepSeek Flash の再試行テストは `:core:test` に含まれ、この実行は失敗 0。

今回足したテストメソッドは 23（既存の平文 URL テスト 1 件は期待値を更新）。

- HTTPS のクラウドは許可、HTTP のクラウドは拒否
- ループバック HTTP は設定なしで許可
- LAN HTTP は明示設定のときだけ許可
- 壊れた URL は拒否
- https→http のリダイレクトは拒否
- WebSocket は `wss://` を許可し、公開 `ws://` は拒否
- ローカルの MockWebServer だけに画面テキストを送り、HTTP クラウドへのリダイレクトは送らない
- プロンプト注入 12 件（ダミー鍵、架空の画面、ローカルサーバのみ）

## 3. APK（この環境でビルドした未署名 release）

署名秘密は見えない。下のファイルは **未署名** で、個人のスマホや配布には使わない。インストールするには、利用者が自分の release 鍵で `apksigner` する必要がある。

debug を同じ Gradle 起動で release と一緒に作ると ABI 分割が debug にも掛かる。CI は起動を分け、debug は従来の `app-debug.apk` になる。下の debug は、この環境で release と同時に作ったため分割されている。どちらも `application-debuggable` が付く debug ビルドである。

| ファイル | サイズ | SHA-256 | debuggable | 署名 |
| --- | --- | --- | --- | --- |
| `app-universal-release-unsigned.apk` | 92,503,463 | `303e78da77179c3085a7d3fbf69442f585b46a82c9415acfc1cef053d8a62fee` | false（`application-debuggable` なし） | なし。`apksigner verify` は `Missing META-INF/MANIFEST.MF` |
| `app-arm64-v8a-release-unsigned.apk` | 79,907,320 | `d3f4b8947770612d42135977c9f25f8d8caca6a2d82cc0413f56239d1b003310` | false | なし |
| `app-universal-debug.apk` | 104,474,267 | `2df3fde6c1e6285a9e907b665678851f5153a6276403b36aac63b36de16b1300` | true | debug 証明書（このマシン） |
| `app-arm64-v8a-debug.apk` | 90,812,816 | `36f8899be1e2a0fb30fd993433a0a74334c2152e6168ce8ac84a23298ac20e3d` | true | debug 証明書（このマシン） |

共通:

- package: `jp.studioyebisu.mika`
- versionName: `0.1.36-jp.1`
- versionCode: `38`
- `WRITE_SECURE_SETTINGS` は release の permission 一覧に無い
- 署名証明書の SHA-256: 未署名のため無し

CI の成果物 URL は、このブランチの Actions 実行がアップロードしたあとに決まる。ジョブは `SHA256SUMS` と `aapt` / `apksigner` の結果を `Mika-JP-Release-APK` と `test-results` に入れる。ローカルのハッシュと CI のハッシュは、タイムスタンプがあるため一致しないことがある。

### 署名に必要な秘密

リポジトリの Actions Secrets は、このトークンでは一覧できない（HTTP 403）。次の **4 つ全部** が揃ったときだけ CI は署名する。一つでも欠けると未署名のままにし、キーストアはアップロードしない。

優先順:

1. `MIKA_RELEASE_KEYSTORE_BASE64`, `MIKA_RELEASE_STORE_PASSWORD`, `MIKA_RELEASE_KEY_ALIAS`, `MIKA_RELEASE_KEY_PASSWORD`
2. 無ければ `ANDROID_KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

キーストアは base64 の PKCS12 または JKS。CI は一時ファイルに展開し、ビルド後に削除する。Gradle は従来どおり `MIKA_RELEASE_KEYSTORE`（パス）とパスワード、別名を読む。秘密をログに出さない。

署名後に確認すること:

```bash
apksigner verify --print-certs app-universal-release.apk
aapt dump badging app-universal-release.apk | grep application-debuggable
```

`application-debuggable` が出たら失敗。証明書 SHA-256 を控える。

## 4. GitHub

- リポジトリ: https://github.com/Grok-Benkei/Mika-JP
- ブランチ: `security/harden-android-release`
- base: `fix/deepseek-flash-reliability`（`main` ではない）
- Draft PR: https://github.com/Grok-Benkei/Mika-JP/pull/2
- 実装とこのレポートの直前の SHA: `c683b122fcefa25d10ccd9a0b702c454d918bb49`
- その SHA の Actions（`検証`）: https://github.com/Grok-Benkei/Mika-JP/actions/runs/42011131408
- 上流 `yebisu0529-ship-it/Mika-JP` と fork の `main` は変更していない

このファイルへ URL を足す commit は APK の中身を変えない。成果物を見る実行は、ブランチ上で成功した最新の `検証` を使う。

## 5. 残るリスク（動的監査が必要）

- 未署名 release は、利用者が自分の鍵で署名するまで端末に入れられない。署名後も、USB デバッグのある端末で別のデバッガが付くかは `debuggable=false` で抑えるが、root や脆弱な端末は別問題。
- Android の Network Security Config は IP リテラルを base-config で扱う。base は平文拒否なので、LAN の `http://192.168.x.x` はスイッチを入れてもプラットフォームが拒否する。ループバック IP だけ `localhost` に書き換えて Ollama / LM Studio を残している。LAN モデルは HTTPS か、`adb reverse` 経由のループバックが要る。内蔵ブラウザの平文サイトもプラットフォームが拒否する。
- 画面テキスト、スクリーンショット、通知は、識別できた通常アプリでは、利用者が設定した HTTPS のモデルへ送る。これは製品の経路。機密アプリとパッケージ不明の画面は画像と通知本文を落とす。ブラウザの中の銀行ページはパッケージがブラウザなので、この一覧では止まらない。
- モデルが悪意ある画面文字に従う可能性は、`<untrusted>` と承認ロックと HTTP 拒否で狭めた。モデルがそれでも危険なツールを呼ぶ場合、支出と宣言済みの確認は確認モードのまま。自律モードは設定画面のスイッチであり、画面文字からは変わらない。
- MCP をオンにするとプライベートネットワークで待ち受ける。トークンは要る。公開アドレスは切る。リレーは `wss://` または `https://`。
- OCR の Play services / datatransport が Google へ通信するかは、パケットキャプチャが無いので未確認。
- 更新の SHA-256 が GitHub の asset `digest` に無いリリースは、アプリ内更新できない。
- 動的に見ていないもの: 実機での TLS 宛先、通知の実送信、ユーザー補助の再接続、ML Kit の初回通信、署名済み release の上書き更新。

## 6. 判定

**ビルド・署名設定待ち**

非デバッグの release APK はできている。配布できる署名は、上の 4 つの Actions Secrets が無いので未完了。個人のスマホへは、まだ入れない。空の試験端末で静的に見る再監査は、未署名 APK の `debuggable=false` と権限一覧に対して進められる。署名後の APK は、証明書 SHA-256 をこのレポートに追記してから別途見る。
