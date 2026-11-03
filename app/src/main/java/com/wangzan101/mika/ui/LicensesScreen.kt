package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.about.MikaLinks

/** 日本語の参考案内と原文の一覧。第三者の項目は licenses/THIRD_PARTY_NOTICES.md と照合する。 */
object Licenses {
    data class Entry(val name: String, val license: String, val note: String)
    data class Document(val id: String, val title: String, val asset: String, val summary: String, val source: String)

    const val JAPANESE_GUIDE =
        "このページはライセンスと著作権表示を理解するための日本語の参考要約です。正式な翻訳ではありません。法的な条件は英語の原文で確認してください。" +
            "Mika JP 本体は Apache License 2.0、同梱するライブラリや実行環境にはそれぞれのライセンスが適用されます。" +
            "使用・改変・再配布の条件は、下の原文と各コンポーネントの表示で確認してください。"

    val DOCUMENTS = listOf(
        Document("apache", "Apache License 2.0", "licenses/Apache-2.0.txt",
            "Mika JP 本体などのライセンスです。使用・改変・再配布には、ライセンスと著作権表示の保持、変更の明示、NOTICE の取り扱いなどの条件があります。保証の免責や商標の扱いも原文を確認してください。",
            "https://www.apache.org/licenses/LICENSE-2.0"),
        Document("gpl2", "GNU GPL バージョン2", "licenses/GPL-2.0.txt",
            "PRoot などのライセンスです。改変・再配布や実行ファイルに対応するソースの提供について条件があります。各項目にある適用バージョンと原文を確認してください。",
            "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html"),
        Document("gpl3", "GNU GPL バージョン3", "licenses/GPL-3.0.txt",
            "GNU GPL バージョン3の原文です。LGPL バージョン3は、この本文に追加の許可を組み合わせる形式です。",
            "https://www.gnu.org/licenses/gpl-3.0.html"),
        Document("lgpl3", "GNU LGPL バージョン3", "licenses/LGPL-3.0.txt",
            "talloc などのライセンスです。ライブラリの改変や、組み合わせたプログラムの再リンクなどに関する条件は、GPL バージョン3とこの追加条項を合わせて確認してください。",
            "https://www.gnu.org/licenses/lgpl-3.0.html"),
        Document("protobuf", "Protocol Buffers のライセンス", "licenses/Protocol-Buffers.txt",
            "Protocol Buffers の著作権表示、BSD-3-Clause の条件、生成コードに関する説明を含む原文です。",
            "https://github.com/protocolbuffers/protobuf"),
        Document("notice", "Mika / Mika JP の著作権表示", "licenses/NOTICE.txt",
            "原作と日本向けフォークの著作権・帰属の表示です。作者名と元の表示を保持しています。",
            "ソースリポジトリの NOTICE"),
        Document("third-party", "第三者の著作権・NOTICE", "licenses/THIRD_PARTY_NOTICES.md",
            "コンポーネントごとの出典、適用ライセンス、利用方法と、保持が必要な原文の表示をまとめています。",
            "ソースリポジトリの licenses/THIRD_PARTY_NOTICES.md"),
    )

    const val GPL_SOURCE_OFFER =
        "PRoot（GPL-2.0-or-later）はMika本体とはリンクせず、独立したプログラムとして同梱・実行します。" +
            "talloc（LGPL-3.0-or-later）はPRootへ静的リンクされています。" +
            "ソース、チェックサム、パッチ、再ビルド手順はリポジトリのtools/sandbox/に記録しています。正式配布時の対応ソースのアーカイブ名はMika-JP-VERSION-corresponding-sources.tar.gzです。" +
            "開発版についても、使用したソースとパッチをこの情報から確認できます。"

    const val RUNTIME_NOTE =
        "PythonやNodeを実行する環境としてAlpine Linux 3.22を同梱しています。以下に各パッケージとライセンスを表示します。" +
            "GPL、LGPL、MPLのパッケージのソースとAlpineのビルド情報も対応ソースの対象です。正式配布用のアーカイブ名はMika-JP-VERSION-corresponding-sources.tar.gzです。"

    fun sourceNotice(text: String, version: String): String = text.replace(
        "Mika-JP-VERSION-corresponding-sources.tar.gz", "Mika-JP-$version-corresponding-sources.tar.gz",
    )

    /** tools/sandbox/build-runtime.py が生成する、同梱実行環境のパッケージ一覧を読み取る。 */
    fun runtimePackages(json: String): List<Entry> =
        Json.parseToJsonElement(json).jsonObject.getValue("packages").jsonArray.map { e ->
            val o = e.jsonObject
            fun f(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            Entry(f("name"), f("license"), "バージョン ${f("version")}。${f("url")}")
        }

    val ENTRIES = listOf(
        Entry("Mika", "Apache-2.0", "原作Mikaと日本向けフォーク。実行環境の起動プログラム、mikaコマンド、スキルのスクリプトを含みます。上の原文一覧から全文と著作権表示を開けます。"),
        Entry("PRoot", "GPL-2.0-or-later", "PythonやNodeの実行環境。バージョン5.4.0に、子プロセスの終了、Androidのシステムコール互換性、パス変換、ファイル情報取得を修正する4つのパッチを適用しています。$GPL_SOURCE_OFFER"),
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
        Entry("scrcpy", "Apache-2.0", "シェルユーザーでシステムの Context を取得する方法は scrcpy を参考にしています。該当ソースの冒頭に出典を保持しています。"),
        Entry("Alpine Linux", "各パッケージのライセンス", RUNTIME_NOTE),
        Entry("XZ for Java", "0BSD", "バージョン1.10。同梱の実行環境の解凍に使います。https://tukaani.org/xz/java.html"),
        Entry("commonmark-java", "BSD-2-Clause", "バージョン0.30.1。表と取消線の拡張を含み、回答のMarkdown表示に使います。Copyright (c) 2015, Atlassian Pty Ltd. https://github.com/commonmark/commonmark-java"),
    )
}

/** 日本語の案内を先に示し、利用者が選んだときだけ原文を開く。原文のファイル内容は変更しない。 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    Licenses.DOCUMENTS.firstOrNull { it.id == selectedId }?.let { document ->
        LicenseOriginal(document, onBack = { selectedId = null })
        return
    }
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val runtime = remember {
        runCatching { context.assets.open("runtime/packages.json").use { Licenses.runtimePackages(it.readBytes().decodeToString()) } }
            .getOrDefault(emptyList())
    }
    val thirdParty = Licenses.ENTRIES.filter { it.name != "Mika" } + runtime
    SubPage(AboutCopy.LICENSE_TITLE, onBack) {
        MikaCard(Modifier.padding(bottom = 12.dp).testTag("license-guide")) {
            Text(Licenses.JAPANESE_GUIDE, Modifier.padding(16.dp), style = MikaType.Hint, color = MikaTheme.colors.ink2)
        }
        FooterNote(Licenses.sourceNotice(Licenses.GPL_SOURCE_OFFER, BuildConfig.VERSION_NAME))
        GroupCard {
            ListRow(null, "対応ソースの配布ページ", "同じバージョンの添付ファイルを確認する", first = true,
                onClick = { uriHandler.openUri(MikaLinks.RELEASES) })
        }
        GroupLabel("ライセンス原文・著作権表示を開く")
        GroupCard {
            Licenses.DOCUMENTS.forEachIndexed { i, document ->
                Column(Modifier.testTag("license-open-${document.id}")) {
                    ListRow(null, document.title, "タップして原文を読む", first = i == 0, onClick = { selectedId = document.id })
                }
            }
        }
        GroupLabel(stringResource(R.string.licenses_third_party, thirdParty.size))
        GroupCard {
            thirdParty.forEachIndexed { i, e ->
                Column {
                    ListRow(null, e.name, e.license, first = i == 0)
                    Text(Licenses.sourceNotice(e.note, BuildConfig.VERSION_NAME), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MikaType.Hint, color = MikaTheme.colors.ink2)
                }
            }
        }
        FooterNote(stringResource(R.string.licenses_footer))
    }
}

@Composable
private fun LicenseOriginal(document: Licenses.Document, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val original = remember(document.asset) {
        runCatching { context.assets.open(document.asset).use { it.readBytes().decodeToString() } }.getOrNull()
    }
    SubPage(document.title, onBack) {
        FooterNote("日本語の参考要約です。正式な条件は下の原文を確認してください。")
        Text(document.summary, Modifier.padding(vertical = 12.dp), style = MikaType.Hint, color = MikaTheme.colors.ink2)
        FooterNote("出典: ${document.source}")
        if (original == null) {
            Text("原文を読み込めませんでした。開き直すか、ソースリポジトリの app/src/main/assets/${document.asset} を確認してください。",
                Modifier.testTag("license-original-error"), style = MikaType.Hint, color = MikaTheme.colors.ink2)
        } else {
            MikaCard(Modifier.padding(top = 12.dp)) {
                SelectionContainer {
                    Text(original.trimEnd(), Modifier.padding(16.dp).testTag("license-original-text"),
                        style = MikaType.Hint.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 19.sp),
                        color = MikaTheme.colors.ink2)
                }
            }
        }
    }
}
