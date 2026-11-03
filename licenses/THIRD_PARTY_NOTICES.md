# Third-party notices

## scrcpy

- Project: https://github.com/Genymobile/scrcpy
- License: Apache-2.0
- 使用法: システムコンテキスト(ActivityThread/パッケージ名を偽装したContext)をシェルとして取得する方法はscrcpyと同様です。
  ソース ファイル ヘッダー保持ソース:
  - `app/src/main/java/com/wangzan101/mika/shell/ShellContext.kt`

## Apache Commons Compress

- Project: https://commons.apache.org/
- Artifact: `org.apache.commons:commons-compress:1.28.0`
- License: Apache-2.0
- 使用法: ライブラリの形式で依存しており、サンドボックス ルート ファイル システムの tar.gz をストリーミング解凍するために使用されます。
  (`core/src/main/kotlin/com/wangzan101/mika/core/cap/sandbox/RootfsExtractor.kt`)。
- NOTICE:

```
Apache Commons Compress
Copyright 2002-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Apache Commons Codec

- Project: https://commons.apache.org/
- Artifact: `commons-codec:commons-codec:1.19.0`
- License: Apache-2.0
- 使用法: Apache Commons Compress の推移的な依存関係。
- NOTICE:

```
Apache Commons Codec
Copyright 2002-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Apache Commons IO

- Project: https://commons.apache.org/
- Artifact: `commons-io:commons-io:2.20.0`
- License: Apache-2.0
- 使用法: Apache Commons Compress の推移的な依存関係。
- NOTICE:

```
Apache Commons IO
Copyright 2002-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Apache Commons Lang

- Project: https://commons.apache.org/
- Artifact: `org.apache.commons:commons-lang3:3.18.0`
- License: Apache-2.0
- 使用法: Apache Commons Compress の推移的な依存関係。
- NOTICE:

```
Apache Commons Lang
Copyright 2001-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Kotlin標準ライブラリ

- Project: https://kotlinlang.org/
- Artifacts: `org.jetbrains.kotlin:kotlin-stdlib:2.1.20`、`kotlin-stdlib-common:2.1.20`、`kotlin-stdlib-jdk7:1.9.10`、
  `kotlin-stdlib-jdk8:1.9.10`;`org.jetbrains:annotations:23.0.0`
- ライセンス: Apache-2.0 (各 POM による)
- 使用法: ライブラリの形式で依存し、APK を入力します。

## kotlinx.coroutines

- Project: https://github.com/Kotlin/kotlinx.coroutines
- Artifacts: `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0`、`kotlinx-coroutines-core-jvm:1.9.0`、`kotlinx-coroutines-android:1.9.0`
- ライセンス: Apache-2.0 (各 POM による)
- 使用法: ライブラリの形式で依存し、APK を入力します。

## kotlinx.serialization

- Project: https://github.com/Kotlin/kotlinx.serialization
- アーティファクト: `org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3`、`kotlinx-serialization-json:1.7.3` (およびその `-jvm` バリアント)
- ライセンス: Apache-2.0 (各 POM による)
- 使用法: ライブラリの形式で依存し、APK を入力します。

## OpenTelemetry

- Project: https://github.com/open-telemetry/opentelemetry-proto-java
- Artifacts: OTLP trace, common, resource and collector schemas from `io.opentelemetry.proto:opentelemetry-proto:1.11.1-alpha` (build-time only).
- License: Apache-2.0
- Usage: Generate lite OTLP trace messages.

## Protocol Buffers

- Project: https://github.com/protocolbuffers/protobuf
- Artifact: `com.google.protobuf:protobuf-javalite:4.36.2`
- License: BSD-3-Clause (see `app/src/main/assets/licenses/Protocol-Buffers.txt`).
- Usage: Official OTLP messages.

## OkHttp

- Project: https://square.github.io/okhttp/
- Artifact: `com.squareup.okhttp3:okhttp:4.12.0`
- ライセンス: Apache-2.0 (POM による)
- 使用法: ライブラリの形式で依存しており、モデル インターフェイスにアクセスするために使用されます。

## Okio

- Project: https://github.com/square/okio/
- Artifacts: `com.squareup.okio:okio:3.6.0`、`okio-jvm:3.6.0`
- ライセンス: Apache-2.0 (各 POM による)
- 使用法: OkHttp 依存関係。

## AndroidX（Jetpack Composeを含む）

- Project: https://developer.android.com/jetpack/androidx
- ライセンス: Apache-2.0 (それぞれの POM による。`androidx.compose:compose-bom:2024.12.01` は単なるバージョン リストであり、コードは含まれません)
- アーティファクト (ランタイム クラスパス内のすべての AndroidX コンポーネントを解放します):
  - Compose 1.7.6:`compose.runtime:runtime`、`runtime-saveable`;`compose.ui:ui`、`ui-geometry`、`ui-graphics`、`ui-text`、
    `ui-unit`、`ui-util`;`compose.foundation:foundation`、`foundation-layout`;`compose.animation:animation`、`animation-core`;
    `compose.material:material-icons-core`、`material-ripple` (上記のそれぞれには `-android` バリアントが含まれます)
  - `compose.material3:material3:1.3.1`
  - `activity:activity`、`activity-ktx`、`activity-compose`:1.9.3
  - `lifecycle:*`(common、common-java8、livedata-core、process、runtime、runtime-compose、runtime-ktx、viewmodel、
    viewmodel-ktx、viewmodel-savedstate):2.8.3
  - `core:core`、`core-ktx`:1.13.1;`annotation:annotation`:1.8.1;`annotation-experimental`:1.4.1;
    `arch.core:core-common`、`core-runtime`:2.2.0;`collection:collection`、`collection-ktx`:1.4.4;
    `savedstate:savedstate`、`savedstate-ktx`:1.2.1;`emoji2:emoji2`:1.3.0;`profileinstaller`:1.3.1;
    `startup:startup-runtime`:1.1.1;`concurrent:concurrent-futures`:1.1.0;`autofill`:1.0.0;
    `customview:customview-poolingcontainer`:1.0.0;`graphics:graphics-path`:1.0.1;`interpolator`:1.0.0;
    `tracing`:1.0.0;`versionedparcelable`:1.1.1;`webkit:webkit`:1.12.1(内蔵ブラウザ)
- 使用法: インターフェイスおよびシステム コンポーネント ライブラリ。APK に入力されます。

## PRoot

- Project: PRoot
- 著作権: Copyright (C) 2015 STMicroelectronics (著者リストはソース コード パッケージ `AUTHORS` にあります)
- ライセンス: GPL-2.0 以降、全文については `licenses/GPL-2.0.txt` を参照してください。
- Source: https://github.com/proot-me/proot/archive/refs/tags/v5.4.0.tar.gz
- Version: 5.4.0
- SHA-256 (source archive): 29248aac2a7ce10c3bd5ee5602742ec33b2532310ff9cf73b79f3c133e5a5f68
- 使用法: スタンドアロンの実行可能プログラム。上記のソースコードを `tools/sandbox/build-native.sh` によってコンパイルすると、
  `app/src/main/jniLibs/arm64-v8a/libproot.so` とそのローダー `libproot_loader.so`、APK とともにリリースされました。
  Mito は子プロセスとしてのみ実行し、リンクしません。ソースコードのアドレス、バージョン、チェック値も`tools/sandbox/sources.lock`に記録されます。
  `libproot.so` はローダーを埋め込みません (空のプレースホルダーのみを埋め込みます)。 `libproot_loader.so` は実行時に `PROOT_LOADER` によって使用されます。
- 変更点: 4 つのパッチが適用されており、これも GPL-2.0 以降でリリースされています。
  `tools/sandbox/patches/proot-0001-exitkill.patch` (追跡オプションに `PTRACE_O_EXITKILL`:PRoot を追加すると、カーネルの終了時に追跡されているすべてのプロセスが強制終了されます)。
  `tools/sandbox/patches/proot-0002-seccomp-trap-enosys.patch` (PRoot 以外の seccomp フィルターによって SIGSYS でブロックされたシステム コールが代わりに返されます)
  `-ENOSYS` および信号をキャンセルします。 arm64 上の `AT_EACCESS` フラグのみを持つ faccessat2 は faccessat に変更されます。
  `tools/sandbox/patches/proot-0003-arm64-new-path-syscalls.patch` (arm64 システム コール番号テーブルが statx で追加され、新しいパス コールが追加されます。
  faccessat2、fchmodat2 はパス変換を実行し、execveat、openat2、io_uring_setup、新しいマウント インターフェイスと *xattrat/file_*attr は `-ENOSYS` を返します。
  、発信番号参照テーブルの範囲外判定を修正);
  `tools/sandbox/patches/proot-0004-link2symlink-fstat-nonfile.patch` (fstat パイプ、ソケット、およびその他の非ファイル記述子の場合の link2symlink 拡張子)
  は、初期化されていない stat 構造を読み取り、成功した呼び出しを失敗した呼び出しに変更しなくなりました)。
- にはコンポーネント uthash (BSD ライセンス、著作権 (c) Troy D. Hanson) が付属しています。PRoot ソース ツリーはそれをサブモジュールとして参照します。
  は、`care` 拡張機能によってのみ使用されます。ソース コード アーカイブ内のディレクトリは空で、Mika のコンパイルには `care` が含まれていないため、`libproot.so` には uthash コードはありません。

## talloc

- Project: talloc (Samba)
- Copyright: Copyright (C) Andrew Tridgell 2004;Copyright (C) Stefan Metzmacher 2006
- ライセンス: LGPL-3.0 以降、全文については `licenses/LGPL-3.0.txt` を参照してください。 LGPL-3.0 は GPL-3.0 の追加条項です。
  GPL-3.0 全文については、`licenses/GPL-3.0.txt` を参照してください。
- Source: https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz
- Version: 2.4.3
- SHA-256 (source archive): dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd
- 使用法: `talloc.c` (未変更) のみをコンパイルし、独立した実行可能プログラム `libproot.so` に静的にリンクします。ミカ自体はリンクしません。
  の再リンクに必要なすべてのソース コードとコンパイル スクリプトについては、`tools/sandbox/` を参照してください。

## Alpine Linux

- Project: https://alpinelinux.org/
- バージョン: 3.22.7 (aarch64 最小ルート ファイル システム)、および Python 3.12、Node 22、Pillow、pypdf およびそれらの依存関係
- ライセンス: 各ソフトウェア パッケージには独自のライセンス (GPL、LGPL、MIT、BSD、MPL など) があります。各ソフトウェア パッケージの名前、バージョン、ライセンスを確認してください。
  `app/src/main/assets/runtime/packages.json`、アプリの「オープンソースライセンス」ページにも1つずつ記載されています。
- 使用法: **APK でリリース**、ファイル `app/src/main/assets/runtime/rootfs.tar.xz`、`tools/sandbox/build-runtime.py`
  は、`tools/sandbox/runtime/sources.lock` に従ってアルパインの公式ソースから署名を取得して検証することによって生成されます。ミカはその中のプログラムをサブプロセスとして実行するだけで、それらをリンクしません。
  GPL/LGPL/MPL ソフトウェア パッケージの対応するソース コード (Alpine のビルド レシピおよびアップストリーム ソース コード) は、各バージョンの `Mika-JP-<version>-corresponding-sources.tar.gz` に格納されます。
  のメソッドについては、`tools/sandbox/README.md`を参照してください。

## Guava ListenableFuture（AndroidX経由）

- Project: https://github.com/google/guava
- アーティファクト: `com.google.guava:listenablefuture:1.0` — Apache-2.0 (Maven POM による)
- 使用法: ライブラリの依存関係として使用され、コードは変更されていません。

## XZ for Java

- Project: https://tukaani.org/xz/java.html
- Version: 1.10(org.tukaani:xz)
- License: 0BSD(BSD Zero Clause License);Copyright (C) The XZ for Java authors and contributors
- 使用方法: 初回起動時に組み込み実行環境を解凍します。

## commonmark-java

- Project: https://github.com/commonmark/commonmark-java
- Artifacts: `org.commonmark:commonmark:0.30.1`、`commonmark-ext-gfm-tables:0.30.1`、`commonmark-ext-gfm-strikethrough:0.30.1`
- License: BSD-2-Clause
- 使用法: ライブラリの形式で依存しているため、コードは変更されていません。会話で回答されたマークダウンは構文ツリーに解析され、Mika 独自のインターフェイス コードによって表示されます。
  (`app/src/main/java/com/wangzan101/mika/ui/MarkdownModel.kt`)。
- License text:

```
Copyright (c) 2015, Atlassian Pty Ltd
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Google ML Kit

- Artifacts: com.google.mlkit:text-recognition-japanese:16.0.1 / com.google.mlkit:text-recognition:16.0.1
- License: ML Kit Terms of Service
- Terms: https://developers.google.com/ml-kit/terms
- Usage: 日本語とラテン文字の端末内OCR。ライセンス名とURLは配布POMに基づきます。
