# サンドボックスのネイティブプログラムと対応ソース

Mika JP は次の3つの実行ファイルを `app/src/main/jniLibs/arm64-v8a/` に同梱します。APK のインストール時に `nativeLibraryDir` へ配置され、子プロセスとして実行されます。PRoot や talloc をアプリ本体へリンクする構成ではありません。

| 成果物 | 役割とソース | 固定版 | ライセンス |
|---|---|---|---|
| `libproot.so` | PRoot 本体。未変更の `talloc.c` をコンパイルして静的リンク | PRoot 5.4.0、talloc 2.4.3 | PRoot: GPL-2.0 以降、talloc: LGPL-3.0 以降 |
| `libproot_loader.so` | 同じ PRoot ソースから作るローダー。静的リンク、`-nostdlib` | PRoot 5.4.0 | GPL-2.0 以降 |
| `libmikarun.so` | タイムアウト、ネットワーク制限、終了時の子プロセス回収を担うランチャー。[mikarun.c](../../app/src/main/cpp/mikarun/mikarun.c) | アプリのソースに含む | Apache-2.0（ソースの SPDX 表記） |

ライセンス全文は [licenses/](../../licenses/)、著作権表示と各コンポーネントの情報は [THIRD_PARTY_NOTICES.md](../../licenses/THIRD_PARTY_NOTICES.md) を参照してください。

## 固定ソースとパッチ

[sources.lock](sources.lock) は `名前 バージョン URL SHA-256` の形式です。[build-native.sh](build-native.sh) は、ここに記録したソースアーカイブをダウンロードして SHA-256 を照合し、[patches/](patches/) 内の `<名前>-<連番>-<説明>.patch` をファイル名順に `patch -p1` で適用します。パッチには変更対象のコンポーネントのライセンスが適用されます。

- [proot-0001-exitkill.patch](patches/proot-0001-exitkill.patch): 追跡オプションに `PTRACE_O_EXITKILL` を追加します。PRoot が終了すると、SIGKILL による強制終了でもカーネルが追跡中のプロセスを終了させ、サンドボックス内のコマンドが残り続けるのを防ぎます。
- [proot-0002-seccomp-trap-enosys.patch](patches/proot-0002-seccomp-trap-enosys.patch): Android の seccomp ポリシーが未対応のシステムコールを SIGSYS で停止させた場合（`si_code == SYS_SECCOMP`）、戻り値を `-ENOSYS` にしてシグナルを抑制し、呼び出し元のフォールバックを可能にします。対象例は `io_uring`、`clone3`、`epoll_pwait2`、`rseq` です。arm64 の `faccessat2` は、フラグが `AT_EACCESS` 以外を含まない場合、通常のパス変換を通る `faccessat` に切り替えます。実 UID と実効 UID が一致するアプリの実行条件で、カーネルによるアクセス判定を維持します。
- [proot-0003-arm64-new-path-syscalls.patch](patches/proot-0003-arm64-new-path-syscalls.patch): `statx`（291）までだった arm64 の番号表を補い、新しいパス関連システムコールを seccomp の追跡対象へ加えます。`faccessat2` と `fchmodat2` はパスを変換します。未変換のホスト側パスで実行されないよう、`execveat`、`openat2`、`io_uring_setup`、新しいマウント API（`open_tree`、`move_mount`、`fspick`、`mount_setattr`、`open_tree_attr`）、`*xattrat`、`file_getattr` / `file_setattr` は `-ENOSYS` を返し、古いシステムコールへのフォールバックを促します。番号表の範囲外判定も `index > length` から `index >= length` に修正します。
- [proot-0004-link2symlink-fstat-nonfile.patch](patches/proot-0004-link2symlink-fstat-nonfile.patch): link2symlink 拡張で、パイプやソケットの `/proc` リンク（`pipe:[…]` など）を `lstat` した際に、未初期化の stat 構造体を読まないようにします。これにより、成功した `fstat` が誤って EPERM に変わり、Node などが起動に失敗する問題を防ぎます。`lstat` の失敗時は補正を打ち切り、`" (deleted)"` の末尾判定はリンクの読み取り成功後に行います。リンク先が消失した場合は `-1` ではなく `-errno` を返します。

PRoot 本体にはローダーの空のプレースホルダーだけを埋め込み、実行時は `PROOT_LOADER` で `nativeLibraryDir` 内の `libproot_loader.so` を指定します。

## ネイティブプログラムの再ビルド

Linux または macOS、Bash、`curl`、`patch`、`sha256sum` または `shasum` と、**Android NDK 28.2.13676358** が必要です。コンパイル対象は Android arm64 / **API 30** です。スクリプトは NDK の `source.properties` も確認し、指定版と違う場合は終了します。

リポジトリのルートで実行します。

```sh
export ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/28.2.13676358"

# 3つの実行ファイルを生成し、app/src/main/jniLibs/arm64-v8a/ に配置する
tools/sandbox/build-native.sh

# 再ビルドして同梱バイナリの SHA-256 と比較する。配置済みファイルは上書きしない
tools/sandbox/build-native.sh --check
```

ビルドではローカルパスのプレフィックスを置き換え、ビルド ID を付けません。同じ固定入力と指定 NDK でバイト単位の再現を確認できます。`--check` は3ファイルすべての SHA-256 を比較し、不一致や比較対象の欠落があれば終了コード1を返します。

## 同梱する Alpine 実行環境

Alpine 3.22 系の最小ルートファイルシステムに Python、Node、Pillow、pypdf、`tzdata` とその依存パッケージを加え、`app/src/main/assets/runtime/rootfs.tar.xz` として APK に同梱します。Alpine の固定版（現在は 3.22.7）とパッケージごとの固定値は [runtime/sources.lock](runtime/sources.lock) が正本です。対応端末では初回起動時にバックグラウンドでアプリ内の `files/sandbox/rootfs` へ展開し、追加のダウンロードは不要です。

[build-runtime.py](build-runtime.py) はロックファイルに従って取得・検証・展開します。ビルド中に `apk` やインストールスクリプトを実行せず、署名済みメタデータから `/lib/apk/db/installed` を生成して、実行環境内のパッケージデータベースと整合させます。検証内容は次のとおりです。

- 最小ルートファイルシステムと各 `.apk` の SHA-256 をロックファイルと照合します。
- 各 `.apk` の RSA 署名を、固定した最小ルートファイルシステム内の公開鍵で検証します。`--resolve` 時は APKINDEX の署名も検証します。
- 制御部分のチェックサムを APKINDEX 由来の値と照合し、データ部分の SHA-256 を署名済み `.PKGINFO` の `datahash` と照合します。
- インストールスクリプトやトリガーは、実行せずに済む理由と内容の SHA-256 を `SCRIPTS_NOT_RUN` に列挙します。未確認のものが含まれる場合やハッシュが違う場合はビルドを停止します。

生成物は `rootfs.tar.xz`、[BundledRuntime.kt](../../app/src/main/java/com/wangzan101/mika/cap/sandbox/BundledRuntime.kt)（版・SHA-256・サイズ）、[packages.json](../../app/src/main/assets/runtime/packages.json)（全パッケージの版・ライセンス）です。`packages.json` はアプリの「オープンソースライセンス」ページでも使います。

以下の Python 手順は、公開手順と同じ Python 3.10 以降を使います。実行環境の RSA 署名検査には `openssl` も必要です。

```sh
# Alpine の版や追加パッケージを変えるときに依存関係を解決し、runtime/sources.lock を更新する
python3 tools/sandbox/build-runtime.py --resolve

# 固定した入力から実行環境とメタデータを生成する
python3 tools/sandbox/build-runtime.py

# 一時ディレクトリで再生成し、配置済みのメタデータ・実行環境と照合する
python3 tools/sandbox/build-runtime.py --check
```

固定入力と同じビルド環境からバイト単位で再現・照合できます。実行環境の `--check` は生成したメタデータを `BundledRuntime.kt` と比較し、`rootfs.tar.xz` が配置済みならその SHA-256 も比較します。不一致なら終了コード1を返し、配置済み成果物は更新しません。

`build-runtime.py` の `WANT` は core の [SANDBOX_PACKAGES](../../core/src/main/kotlin/com/wangzan101/mika/core/cap/sandbox/SandboxPackages.kt) と一致させます（`BundledRuntimeTest` で照合）。`tzdata` を含むため、`ProcRunner.env` がコマンド起動ごとに `TZ` を端末の現在の IANA タイムゾーンへ合わせ、Python や Node のローカル時刻に夏時間・過去の時差規則を反映します。端末のタイムゾーン変更は次のコマンドから反映されます。

## 公開 APK に添える対応ソース

対応ソースの作成は [package_sources.py](../release/package_sources.py) に統一します。リポジトリのルートで、`VERSION` を配布する版へ置き換えて実行してください。

```sh
python3 tools/release/package_sources.py --output build/release --version VERSION
```

出力は `build/release/Mika-JP-VERSION-corresponding-sources.tar.gz` です。作業用ツリーは `build/release/corresponding-sources/` に置かれます。CLI は既存の取得物も再検証し、必要なソースやハッシュが揃わなければ失敗します。

アーカイブには次を含めます。

- `native/`: `sources.lock` で固定した PRoot と talloc のソースアーカイブ。SHA-256 を検証し、URL とハッシュを `NATIVE-SOURCES.json` に記録します。
- `runtime/`: 同梱パッケージのうち GPL / LGPL / MPL 対象の対応ソース。記録済み aports コミットの `APKBUILD`、ローカルパッチ、レシピが列挙する上流ソースを集め、各ファイルを SHA-512 で検証します。取得元とハッシュは `SOURCES.txt`、対象の `packages.json` の SHA-256 は `PACKAGES.sha256` に記録します。
- `project/`: ビルドスクリプト、ロックファイル、PRoot パッチ、mikarun のソース、サンドボックス用 `mika`、`BundledRuntime.kt`、`packages.json`、公開用ツールと案内、`LICENSE`・`NOTICE`・ライセンス表示一式を含めます。

内容は次の読み取り専用コマンドで確認できます。

```sh
tar -tzf build/release/Mika-JP-VERSION-corresponding-sources.tar.gz
```

対応ソースは、同じコミットから用意した APK とアプリ本体のソースアーカイブとともに同じ Release へ添付します。署名、ライセンス・対応ソースの確認、APK とソースのハッシュ照合、`SHA256SUMS` と `release-manifest.json` の作成は [公開手順](../../docs/release.md) に従ってください。
