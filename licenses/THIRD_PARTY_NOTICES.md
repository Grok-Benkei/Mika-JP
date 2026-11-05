# サードパーティのライセンス・著作権表示

各コンポーネントの出典、適用ライセンス、Mika JP での利用方法をまとめています。日本語の説明は参考案内で、正式なライセンス条件や著作権表示を置き換えません。引用ブロック内の原文は保持しています。

アプリ本体のライセンスは [LICENSE](../LICENSE)、日本語の参考要約は [LICENSE.ja.md](../LICENSE.ja.md) を参照してください。対応ソースの作成・照合・配布は [公開手順](../docs/release.md) に従います。

## scrcpy

- 公式サイト: https://github.com/Genymobile/scrcpy
- ライセンス: Apache-2.0
- 利用方法: シェルユーザーでシステムの Context を取得する際の ActivityThread やパッケージ名の扱いを参考にしています。
  参考元は、該当ソースのヘッダーにも記載しています。
  - `app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShellContext.kt`

## Apache Commons Compress

- 公式サイト: https://commons.apache.org/
- 依存コンポーネント: `org.apache.commons:commons-compress:1.28.0`
- ライセンス: Apache-2.0
- 利用方法: 同梱ルートファイルシステムの tar アーカイブをストリーム展開するために利用します。
  (`core/src/main/kotlin/com/wangzan101/mika/core/cap/sandbox/RootfsExtractor.kt`)。
- NOTICE:

```
Apache Commons Compress
Copyright 2002-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Apache Commons Codec

- 公式サイト: https://commons.apache.org/
- 依存コンポーネント: `commons-codec:commons-codec:1.19.0`
- ライセンス: Apache-2.0
- 利用方法: Apache Commons Compress が利用する依存ライブラリです。
- NOTICE:

```
Apache Commons Codec
Copyright 2002-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Apache Commons IO

- 公式サイト: https://commons.apache.org/
- 依存コンポーネント: `commons-io:commons-io:2.20.0`
- ライセンス: Apache-2.0
- 利用方法: Apache Commons Compress が利用する依存ライブラリです。
- NOTICE:

```
Apache Commons IO
Copyright 2002-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Apache Commons Lang

- 公式サイト: https://commons.apache.org/
- 依存コンポーネント: `org.apache.commons:commons-lang3:3.18.0`
- ライセンス: Apache-2.0
- 利用方法: Apache Commons Compress が利用する依存ライブラリです。
- NOTICE:

```
Apache Commons Lang
Copyright 2001-2025 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (https://www.apache.org/).
```

## Kotlin標準ライブラリ

- 公式サイト: https://kotlinlang.org/
- 依存コンポーネント: `org.jetbrains.kotlin:kotlin-stdlib:2.1.20`、`kotlin-stdlib-common:2.1.20`、`kotlin-stdlib-jdk7:1.9.10`、
  `kotlin-stdlib-jdk8:1.9.10`、`org.jetbrains:annotations:23.0.0`
- ライセンス: Apache-2.0 (各 POM による)
- 利用方法: 依存ライブラリとして APK に同梱します。

## kotlinx.coroutines

- 公式サイト: https://github.com/Kotlin/kotlinx.coroutines
- 依存コンポーネント: `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2`、`kotlinx-coroutines-core-jvm:1.10.2`、`kotlinx-coroutines-android:1.10.2`
- ライセンス: Apache-2.0 (各 POM による)
- 利用方法: 依存ライブラリとして APK に同梱します。

## kotlinx.serialization

- 公式サイト: https://github.com/Kotlin/kotlinx.serialization
- アーティファクト: `org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3`、`kotlinx-serialization-json:1.7.3` (およびその `-jvm` バリアント)
- ライセンス: Apache-2.0 (各 POM による)
- 利用方法: 依存ライブラリとして APK に同梱します。

## OpenTelemetry

- 公式サイト: https://github.com/open-telemetry/opentelemetry-proto-java
- 依存コンポーネント: `io.opentelemetry.proto:opentelemetry-proto:1.11.1-alpha` の trace・common・resource・collector スキーマ。ビルド時だけ利用します。
- ライセンス: Apache-2.0
- 利用方法: OTLP トレース用の軽量メッセージクラスを生成します。

## Protocol Buffers

- 公式サイト: https://github.com/protocolbuffers/protobuf
- 依存コンポーネント: `com.google.protobuf:protobuf-javalite:4.36.2`
- ライセンス: BSD-3-Clause。原文は `app/src/main/assets/licenses/Protocol-Buffers.txt` に同梱します。
- 利用方法: 公式の OTLP メッセージをエンコードします。

## OkHttp

- 公式サイト: https://square.github.io/okhttp/
- 依存コンポーネント: `com.squareup.okhttp3:okhttp:4.12.0`
- ライセンス: Apache-2.0 (POM による)
- 利用方法: モデルの API に接続する HTTP クライアントとして利用します。

## Okio

- 公式サイト: https://github.com/square/okio/
- 依存コンポーネント: `com.squareup.okio:okio:3.6.0`、`okio-jvm:3.6.0`
- ライセンス: Apache-2.0 (各 POM による)
- 利用方法: OkHttpが使う入出力ライブラリです。

## AndroidX（Jetpack Composeを含む）

- 公式サイト: https://developer.android.com/jetpack/androidx
- ライセンス: Apache-2.0 (それぞれの POM による。`androidx.compose:compose-bom:2024.12.01` はバージョン管理用の定義であり、コードは含まれません)
- アーティファクト (実行時のクラスパスに含まれる AndroidX コンポーネント):
  - Compose 1.7.6:`compose.runtime:runtime`、`runtime-saveable`、`compose.ui:ui`、`ui-geometry`、`ui-graphics`、`ui-text`、
    `ui-unit`、`ui-util`、`compose.foundation:foundation`、`foundation-layout`、`compose.animation:animation`、`animation-core`、
    `compose.material:material-icons-core`、`material-ripple` (上記のそれぞれには `-android` バリアントが含まれます)
  - `compose.material3:material3:1.3.1`
  - `activity:activity`、`activity-ktx`、`activity-compose`:1.9.3
  - `lifecycle:*`(common、common-java8、livedata-core、process、runtime、runtime-compose、runtime-ktx、viewmodel、
    viewmodel-ktx、viewmodel-savedstate):2.8.3
  - `core:core`、`core-ktx`:1.13.1、`annotation:annotation`:1.8.1、`annotation-experimental`:1.4.1、
    `arch.core:core-common`、`core-runtime`:2.2.0、`collection:collection`、`collection-ktx`:1.4.4、
    `savedstate:savedstate`、`savedstate-ktx`:1.2.1、`emoji2:emoji2`:1.3.0、`profileinstaller`:1.3.1、
    `startup:startup-runtime`:1.1.1、`concurrent:concurrent-futures`:1.1.0、`autofill`:1.0.0、
    `customview:customview-poolingcontainer`:1.0.0、`graphics:graphics-path`:1.0.1、`interpolator`:1.0.0、
    `tracing`:1.0.0、`versionedparcelable`:1.1.1、`webkit:webkit`:1.12.1(内蔵ブラウザ)
- 利用方法: UI と Android のシステム機能を使うためのライブラリとして APK に同梱します。

## PRoot

- 公式サイト: PRoot
- 著作権: Copyright (C) 2015 STMicroelectronics (著者リストはソースアーカイブ `AUTHORS` にあります)
- ライセンス: GPL-2.0 以降、全文については `licenses/GPL-2.0.txt` を参照してください。
- ソース: https://github.com/proot-me/proot/archive/refs/tags/v5.4.0.tar.gz
- バージョン: 5.4.0
- ソースアーカイブの SHA-256: 29248aac2a7ce10c3bd5ee5602742ec33b2532310ff9cf73b79f3c133e5a5f68
- 利用方法: スタンドアロンの実行可能プログラム。固定ソースにパッチを適用し、`tools/sandbox/build-native.sh` で
  `app/src/main/jniLibs/arm64-v8a/libproot.so` とローダー `libproot_loader.so` を生成し、APK に同梱します。
  Mika JP はこれらを子プロセスとして実行します。アプリ本体にはリンクしません。ソースの URL、版、SHA-256 は `tools/sandbox/sources.lock` に固定しています。
  `libproot.so` はローダーを埋め込みません (空のプレースホルダーのみを埋め込みます)。 `libproot_loader.so` は実行時に `PROOT_LOADER` によって使用されます。
- 変更点: 4 つのパッチが適用されており、これも GPL-2.0 以降でリリースされています。
  `tools/sandbox/patches/proot-0001-exitkill.patch`: `PTRACE_O_EXITKILL` により、PRoot の終了時にカーネルが追跡中のプロセスを終了させます。
  `tools/sandbox/patches/proot-0002-seccomp-trap-enosys.patch`: Android の seccomp による SIGSYS を `-ENOSYS` に変えてフォールバックを可能にします。arm64 では `AT_EACCESS` 以外のフラグがない `faccessat2` を `faccessat` に切り替えます。
  `tools/sandbox/patches/proot-0003-arm64-new-path-syscalls.patch`: arm64 のシステムコール番号表と追跡対象を補います。`faccessat2`・`fchmodat2` はパスを変換し、未対応の `execveat`・`openat2`・`io_uring_setup`・マウント API・`*xattrat`・`file_*attr` は `-ENOSYS` を返します。番号表の範囲外判定も修正します。
  `tools/sandbox/patches/proot-0004-link2symlink-fstat-nonfile.patch`: パイプやソケットに対する `fstat` の補正で、link2symlink が未初期化の stat 構造体を読んで正常な結果を失敗へ変えてしまう問題を修正します。
- 上流にはコンポーネント uthash (BSD ライセンス、著作権 (c) Troy D. Hanson) が付属しています。PRoot ソース ツリーはそれをサブモジュールとして参照します。
  uthash は `care` 拡張機能だけが利用します。ソースアーカイブ内のディレクトリは空で、Mika のコンパイルには `care` が含まれていないため、`libproot.so` には uthash コードはありません。

## talloc

- 公式サイト: talloc (Samba)
- 著作権: Copyright (C) Andrew Tridgell 2004;Copyright (C) Stefan Metzmacher 2006
- ライセンス: LGPL-3.0 以降、全文については `licenses/LGPL-3.0.txt` を参照してください。 LGPL-3.0 は GPL-3.0 の追加条項です。
  GPL-3.0 全文については、`licenses/GPL-3.0.txt` を参照してください。
- ソース: https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz
- バージョン: 2.4.3
- ソースアーカイブの SHA-256: dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd
- 利用方法: `talloc.c` (未変更) のみをコンパイルし、独立した実行可能プログラム `libproot.so` に静的にリンクします。Mika JP 本体にはリンクしません。
  再リンクに必要なソースとビルドスクリプトは、対応ソースのアーカイブと `tools/sandbox/` から確認できます。

## Alpine Linux

- 公式サイト: https://alpinelinux.org/
- バージョン: 3.22.7 (aarch64 最小ルートファイルシステム)、および Python 3.12、Node 22、Pillow、pypdf およびそれらの依存関係
- ライセンス: 各パッケージには独自のライセンス (GPL、LGPL、MIT、BSD、MPL など) があります。各パッケージの名前、バージョン、ライセンスを確認してください。
  `app/src/main/assets/runtime/packages.json`、アプリの「ライセンスと著作権表示」ページにも1つずつ記載されています。
- 利用方法: `tools/sandbox/build-runtime.py` が `tools/sandbox/runtime/sources.lock` に従って Alpine の配布ファイルを取得し、署名とハッシュを検証して `app/src/main/assets/runtime/rootfs.tar.xz` を生成します。APK に同梱し、その中のプログラムを子プロセスとして実行します。アプリ本体にはリンクしません。
  GPL/LGPL/MPL パッケージの対応するソース コード (Alpine のビルド レシピおよびアップストリーム ソース コード) は、各バージョンの `Mika-JP-VERSION-corresponding-sources.tar.gz` に格納されます。
  作成・検証の手順は `tools/sandbox/README.md` と `docs/release.md` を参照してください。

## Guava ListenableFuture（AndroidX経由）

- 公式サイト: https://github.com/google/guava
- アーティファクト: `com.google.guava:listenablefuture:1.0` — Apache-2.0 (Maven POM による)
- 利用方法: ライブラリの依存関係として使用され、コードは変更されていません。

## XZ for Java

- 公式サイト: https://tukaani.org/xz/java.html
- バージョン: 1.10(org.tukaani:xz)
- ライセンス: 0BSD(BSD Zero Clause License);Copyright (C) The XZ for Java authors and contributors
- 利用方法: 初回起動時に同梱の実行環境を展開します。

## commonmark-java

- 公式サイト: https://github.com/commonmark/commonmark-java
- 依存コンポーネント: `org.commonmark:commonmark:0.30.1`、`commonmark-ext-gfm-tables:0.30.1`、`commonmark-ext-gfm-strikethrough:0.30.1`
- ライセンス: BSD-2-Clause
- 利用方法: Markdown を構文ツリーへ解析する依存ライブラリとして、未改変で利用します。解析結果は Mika の UI コードで表示します。
  (`app/src/main/java/com/wangzan101/mika/ui/MarkdownModel.kt`)。
- ライセンス原文:

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

- 依存コンポーネント: com.google.mlkit:text-recognition-japanese:16.0.1 / com.google.mlkit:text-recognition:16.0.1
- ライセンス: ML Kit Terms of Service
- 利用規約: https://developers.google.com/ml-kit/terms
- 利用方法: 日本語とラテン文字の端末内 OCR に使います。正式なライセンス名と URL は配布 POM に基づきます。
