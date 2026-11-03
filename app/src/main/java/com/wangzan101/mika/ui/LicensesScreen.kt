package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** ライセンス ページのサードパーティ コンポーネントに関するデータ。ウェアハウスライセンス/THIRD_PARTY_NOTICES.md のエントリと 1 対 1 で対応します (LicensesTest によってチェックされます)。 */
object Licenses {
    data class Entry(val name: String, val license: String, val note: String)

    const val GPL_SOURCE_OFFER =
        "PRoot（GPL-2.0-or-later）とtalloc（LGPL-3.0-or-later）は独立したプログラムとして同梱され、Mika本体とはリンクしません。" +
            "ソース、チェックサム、パッチ、再ビルド手順はリポジトリのtools/sandbox/に記録しています。正式配布時の対応ソースのアーカイブ名はmika-<version>-gpl-sources.tar.gzです。" +
            "開発版についても、使用したソースとパッチをこの情報から確認できます。"

    const val RUNTIME_NOTE =
        "PythonやNodeを実行する環境としてAlpine Linux 3.22を同梱しています。以下に各パッケージとライセンスを表示します。" +
            "GPL、LGPL、MPLのパッケージのソースとAlpineのビルド情報も対応ソースの対象です。正式配布用のアーカイブ名はmika-<version>-gpl-sources.tar.gzです。"

    /** には、実行環境のソフトウェア パッケージが付属しています (assets/runtime/packages.json、tools/sandbox/build-runtime.py によって生成)。 */
    fun runtimePackages(json: String): List<Entry> =
        Json.parseToJsonElement(json).jsonObject.getValue("packages").jsonArray.map { e ->
            val o = e.jsonObject
            fun f(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            Entry(f("name"), f("license"), "バージョン ${f("version")}。${f("url")}")
        }

    val ENTRIES = listOf(
        Entry("Mika", "Apache-2.0", "原作Mikaと日本向けフォーク。実行環境の起動プログラム、mikaコマンド、スキルのスクリプトを含みます。ライセンス全文はページ上部にあります。"),
        Entry("PRoot", "GPL-2.0-or-later", "PythonやNodeの実行環境。バージョン5.4.0に、終了時に全子プロセスを終了する上流Mikaのパッチが適用されています。$GPL_SOURCE_OFFER"),
        Entry("talloc", "LGPL-3.0-or-later", "バージョン2.4.3。未改変でPRootへ静的リンクされています。tools/sandbox/のソースとスクリプトで再リンクできます。"),
        Entry("Apache Commons Compress", "Apache-2.0", "初回起動時に同梱の実行環境を展開します。Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons Codec", "Apache-2.0", "Apache Commons Compressの依存ライブラリ。Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons IO", "Apache-2.0", "Apache Commons Compressの依存ライブラリ。Copyright 2002-2025 The Apache Software Foundation."),
        Entry("Apache Commons Lang", "Apache-2.0", "Apache Commons Compressの依存ライブラリ。Copyright 2001-2025 The Apache Software Foundation."),
        Entry("Kotlin標準ライブラリ", "Apache-2.0", "kotlin-stdlib 2.1.20、JetBrains annotations 23.0.0など。https://kotlinlang.org/"),
        Entry("kotlinx.coroutines", "Apache-2.0", "バージョン1.9.0。https://github.com/Kotlin/kotlinx.coroutines"),
        Entry("kotlinx.serialization", "Apache-2.0", "バージョン1.7.3。https://github.com/Kotlin/kotlinx.serialization"),
        Entry("OpenTelemetry", "Apache-2.0", "トレースのOTLPメッセージ定義。 https://opentelemetry.io/"),
        Entry("Protocol Buffers", "BSD-3-Clause", "トレースのProtobufエンコード。 https://protobuf.dev/"),
        Entry("OkHttp", "Apache-2.0", "バージョン4.12.0。モデルのAPIへ接続するHTTPクライアント。https://square.github.io/okhttp/"),
        Entry("Okio", "Apache-2.0", "バージョン3.6.0。OkHttpの依存ライブラリ。https://github.com/square/okio/"),
        Entry("AndroidX（Jetpack Composeを含む）", "Apache-2.0", "Compose 1.7.6、Material3 1.3.1、Activity 1.9.3、Lifecycle 2.8.3、Core 1.13.1、WebKit 1.12.1 などのUI・システムコンポーネント。https://developer.android.com/jetpack/androidx"),
        Entry("Guava ListenableFuture（AndroidX経由）", "Apache-2.0", "listenablefuture 1.0。https://github.com/google/guava"),
        Entry("Google ML Kit", "ML Kit Terms of Service", "日本語と英数字の端末内OCR。text-recognition-japanese / text-recognition 16.0.1。https://developers.google.com/ml-kit/terms"),
        Entry("scrcpy", "Apache-2.0", "shellからシステムのContextを得る方法はscrcpyを参考にしています。該当ソースの冒頭に出典を保持しています。"),
        Entry("Alpine Linux", "各パッケージのライセンス", RUNTIME_NOTE),
        Entry("XZ for Java", "0BSD", "バージョン1.10。同梱の実行環境の解凍に使います。https://tukaani.org/xz/java.html"),
        Entry("commonmark-java", "BSD-2-Clause", "バージョン0.30.1。表と取消線の拡張を含み、回答のMarkdown表示に使います。Copyright (c) 2015, Atlassian Pty Ltd. https://github.com/commonmark/commonmark-java"),
    )
}

/**
 * ライセンス ページ (バージョン情報ページの下部にある「Apache-2.0 オープン ソース ライセンス」にあります): まず、元のライセンス テキスト (assets/licenses/Apache-2.0.txt)、
 * Mega とともにリリースされたサードパーティ コンポーネントを以下にリストします。コンポーネント (名前、ライセンス) ごとに 1 行、その行の下に説明が記載されています。フッターは全文を指します。
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val licenseText = remember {
        runCatching { context.assets.open(LICENSE_ASSET).use { it.readBytes().decodeToString() } }.getOrDefault("")
    }
    val runtime = remember {
        runCatching { context.assets.open("runtime/packages.json").use { Licenses.runtimePackages(it.readBytes().decodeToString()) } }
            .getOrDefault(emptyList())
    }
    val thirdParty = Licenses.ENTRIES.filter { it.name != "Mika" } + runtime
    SubPage(AboutCopy.LICENSE_TITLE, onBack) {
        if (licenseText.isNotEmpty()) {
            MikaCard(Modifier.padding(bottom = 12.dp).testTag("license-text")) {
                Text(
                    licenseText.trimEnd(), Modifier.padding(16.dp),
                    style = MikaType.Hint.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 19.sp),
                    color = MikaTheme.colors.ink2,
                )
            }
        }
        GroupLabel(stringResource(R.string.licenses_third_party, thirdParty.size))
        GroupCard {
            thirdParty.forEachIndexed { i, e ->
                Column {
                    ListRow(null, e.name, e.license, first = i == 0)
                    Text(e.note, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MikaType.Hint, color = MikaTheme.colors.ink2)
                }
            }
        }
        FooterNote(stringResource(R.string.licenses_footer))
    }
}

private const val LICENSE_ASSET = "licenses/Apache-2.0.txt"
