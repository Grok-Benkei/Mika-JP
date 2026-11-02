# サンドボックス ネイティブ プログラム: ソース、ライセンス、ソース コードのリリース

ミカのサンドボックスには 3 つの実行可能ファイルが付属しており、すべて `app/src/main/jniLibs/arm64-v8a/` であり、APK とともに `nativeLibraryDir` にインストールされます。
Mito は子プロセスとしてのみ実行し、リンクしません。

|ドキュメント | |とは何ですか出典 |バージョン |ライセンス |
|---|---|---|---|---|
| `libproot.so` | PRoot本体、静的リンクtalloc | PRoot:`sources.lock` の `proot` ソース コード パッケージに、`patches/proot-*.patch` を追加します。 `talloc` ソース コード パッケージ (talloc:`sources.lock`)、`talloc.c` のみコンパイル、未修正 | PRoot 5.4.0、talloc 2.4.3 | GPL-2.0以降(PRoot)、LGPL-3.0以降(talloc) |
| `libproot_loader.so` | PRoot ローダー (静的、`-nostdlib`) |同じ PRoot ソース パッケージ | 5.4.0 | GPL-2.0以降 |
| `libmikarun.so` |ミカのサンドボックス ランチャー (タイムアウト、ネットワーク切断、サイトクリア) | `app/src/main/cpp/mikarun/mikarun.c` |ミカさんと |マサチューセッツ工科大学 |

ライセンスと著作権声明 (`licenses/THIRD_PARTY_NOTICES.md`) の全文は、ウェアハウスのルート ディレクトリの `licenses/` にあります。

## 同梱の実行環境

実行環境（Alpine 3.22 の最小ルートファイルシステム + Python、Node などのパッケージ）は APK に同梱して配布し、保存先は
`app/src/main/assets/runtime/rootfs.tar.xz`。端末で Mika を初めて起動すると、バックグラウンドで `files/sandbox/rootfs` に展開する。ネットワーク接続は不要。

- `runtime/sources.lock`:最小ルートファイルシステムのアドレスと SHA-256、および各パッケージのリポジトリ、バージョン、APKINDEX チェックサム、`.apk` の SHA-256。
- `build-runtime.py`:`sources.lock` に従ってダウンロード・検証し、`rootfs.tar.xz` を組み立てる。全工程で apk を実行せず、apk の配置規則に従ってパッケージを展開し、
  パッケージの署名済みメタデータから `/lib/apk/db/installed` を書き出す。そのため、実行環境内の apk から見えるデータベースとの整合性が保たれる。
  各 `.apk` について、ロックファイルの SHA-256、自身の RSA 署名（公開鍵は固定した最小ルートファイルシステムから取得）、制御部分のチェックサムと APKINDEX の一致、
  データ部分と署名済み `.PKGINFO` 内の `datahash` の一致を確認する。端末上で実行されるインストールスクリプトとトリガーは、1 つずつ `SCRIPTS_NOT_RUN` に列挙しなければならない
  （内容の SHA-256 で固定し、実行しなくても正しい理由を明記する）。そうでなければビルドは失敗する。
  同時に `app/src/main/java/com/wangzan101/mika/cap/sandbox/BundledRuntime.kt`（バージョン、SHA-256、サイズ）と、
  `app/src/main/assets/runtime/packages.json`（すべてのパッケージ、バージョン、ライセンス。「オープンソースライセンス」ページの生成元）を生成する。
- 成果物はバイト単位で再現可能:

```bash
python3 tools/sandbox/build-runtime.py --resolve   # 换 Alpine 小版本或软件包后重新求依赖,改写 runtime/sources.lock
python3 tools/sandbox/build-runtime.py             # 生成 rootfs.tar.xz、BundledRuntime.kt、packages.json
python3 tools/sandbox/build-runtime.py --check     # 重新生成并与仓库里的产物比较,不一致退出 1
```

`build-runtime.py` の `WANT` は core の `SANDBOX_PACKAGES` と一致しなければならない（`BundledRuntimeTest` で照合）。

実行環境には `tzdata` を含む。コマンドを起動するたびに `ProcRunner.env` が端末の現在の IANA タイムゾーンを `TZ` に設定し、
コマンドライン、Python、Node のローカル時刻を端末と一致させ、夏時間と過去の時差規則も維持する。端末のタイムゾーンを変更すると、次のコマンドから反映する。

## ファイル

- `sources.lock`: 1 行あたり `名称 版本 下载地址 SHA-256`。ここにリストされているソース コード パッケージは、ソース コード パッケージのコンパイルとビルドにのみ使用してください。ダウンロード後、まず SHA-256 を確認してください。
- `patches/`: 上流のソース コードを変更します。ファイル名は `<名称>-<序号>-<说明>.patch` で、ファイル名の順に `patch -p1` と入力します。パッチは、変更されるコンポーネントのライセンスに従います。
  - `proot-0001-exitkill.patch`: PRoot の追跡オプションに `PTRACE_O_EXITKILL` を追加します。このようにして、PRoot が終了すると (SIGKILL によって強制終了された場合でも)、カーネルは追跡されているすべてのプロセスを強制終了し、サンドボックス内のコマンドはバックグラウンドで実行され続けなくなります。
  - `proot-0002-seccomp-trap-enosys.patch`: Android によってアプリ用にインストールされた seccomp ポリシーは、新しいシステム コール (io_uring、clone3、faccessat2、epoll_pwait2、rseq など) に SIGSYS を送信し、インストール後のフィルターよりも優先されます。サンドボックス内のプログラムはテストされるとすぐに強制終了されます。 PRoot は、この SIGSYS のシグナル停止 (`si_code == SYS_SECCOMP`) で戻り値を `-ENOSYS` に変更し、シグナルをキャンセルし、プログラムは自ら後退します。 arm64 上の `AT_EACCESS` のみを持つ faccessat2 は faccessat に変更されます (アプリ プロセスの実際の ID は常に有効な ID と同じであり、2 つは同等です)。許可の判断は引き続きカーネルによって行われます。
  - `proot-0003-arm64-new-path-syscalls.patch`: PRoot 5.4.0 の arm64 呼び出し番号テーブルは statx(291) までしか到達せず、新しく追加された呼び出しは PRoot によって認識されません。プラットフォームがそれらを解放すると、それらは変換されていないパスでカーネルに直接移動し、ホスト パスに従って実行されます (たとえば、musl の `faccessat(…, AT_EACCESS)` は最初に faccessat2 を試行し、間違ったファイルを判断します)。次の数値を入力して、PRoot の seccomp トラッキング リストに追加します。 faccessat2、fchmodat2 および faccessat、fchmodat 変換パス。 execveat (execve 処理をバイパスします)、openat2 (`RESOLVE_*` ルールはサンドボックス内のディレクトリ ツリー用)、io_uring_setup (システム コールを使用しないパス操作)、新しいマウント インターフェイス (open_tree、move_mount、fspick、mount_setattr、open_tree_attr)、および setxattrat です。 *xattrat、file_getattr/file_setattr はすべて `-ENOSYS` を返し、呼び出し元は古い呼び出しを返します。これは PRoot によって通常どおり変換されます。同時にテーブル参照時の範囲外判定を修正(`index > length`を`>=`に変更)。
  - `proot-0004-link2symlink-fstat-nonfile.patch`: link2symlink 拡張機能は、fstat が lstat を返すときに、記述子の `/proc` リンクをパスとして使用します。パイプ、ソケットなど（`pipe:[…]`）。 lstat が失敗した後、元のコードは初期化されていない stat 構造体の読み取りを続けます。スタックの残存値によっては、成功した fstat が -1(EPERM) に変更される場合があります。したがって、起動時に標準記述子をチェックするプログラム (ノードは直接中止されます) はランダムに失敗します。 lstat エラーを処理しないように変更されました。 `" (deleted)"` サフィックスは、リンクを読み取った後にのみチェックされます。リンク ターゲットが消えると、-1 の代わりに -errno が返されます。
- `build-native.sh`: `sources.lock` からソース コードをダウンロードし、SHA-256 を確認し、パッチを適用し、NDK 28.2.13676358 (API 30) を使用して 3 つのプログラムをクロスコンパイルします。

## の再コンパイル

```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358
tools/sandbox/build-native.sh           # 编译并覆盖 app/src/main/jniLibs/arm64-v8a/ 里的产物
tools/sandbox/build-native.sh --check   # 只重编并与仓库里的产物比较 SHA-256,不一致退出 1
```

はコンパイル時にパス プレフィックス マッピングを実行し、ビルド ID を書き込まないため、同じ NDK によってコンパイルされた製品はバイトごとに同じになります。 `--check` を使用して、ウェアハウス内のバイナリが実際にこれらのソース コードからのものであることを確認します。

## はAPKリリース時にソースコードパッケージをリリースします

が APK をリリースするたびに、ソース コード パッケージ `patches/`、`sources.lock` にリストされているコンパイル スクリプトおよびライセンスを `mika-<版本>-gpl-sources.tar.gz` にパッケージ化し、APK と同じリリース ページに配置する必要があります。リポジトリのルート ディレクトリで実行します。

```bash
VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
NAME=mika-$VERSION-gpl-sources
OUT=build/gpl-sources/$NAME
rm -rf "$OUT" && mkdir -p "$OUT"
while read -r name version url sum; do
  case "$name" in ''|'#'*) continue ;; esac
  curl -fsSL "$url" -o "$OUT/$name-$version.tar.gz"
  echo "$sum  $OUT/$name-$version.tar.gz" | shasum -a 256 -c -
done < tools/sandbox/sources.lock
cp -R tools/sandbox/patches tools/sandbox/sources.lock tools/sandbox/build-native.sh tools/sandbox/README.md "$OUT/"
cp licenses/GPL-2.0.txt licenses/GPL-3.0.txt licenses/LGPL-3.0.txt "$OUT/"
# 运行环境里每个 GPL/LGPL/MPL 软件包:Alpine 的构建配方(钉在软件包记录的 aports 提交)与上游源码,逐个按 SHA-512 核对
python3 tools/sandbox/runtime-sources.py "$OUT/runtime"
cp tools/sandbox/build-runtime.py tools/sandbox/runtime-sources.py app/src/main/assets/runtime/packages.json "$OUT/runtime/"
cp -R tools/sandbox/runtime "$OUT/runtime/lock"
COPYFILE_DISABLE=1 tar -czf "build/gpl-sources/$NAME.tar.gz" -C build/gpl-sources "$NAME"
```

を実行すると、`build/gpl-sources/mika-<版本>-gpl-sources.tar.gz` が得られます。アップロードする前に、`tar -tzf` を使用して 2 つのソース コード パッケージ `patches/` があることを確認してください。
、`runtime/`、および `runtime/SOURCES.txt` の下の各 GPL/LGPL/MPL ソフトウェア パッケージのディレクトリ (gcc およびその他のソース コードはより大きく、パッケージ全体は約数百 MB)。
