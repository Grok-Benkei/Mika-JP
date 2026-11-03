package com.wangzan101.mika.core

import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.mcp.FakeRemoteTasks
import com.wangzan101.mika.core.mcp.McpTaskTools
import com.wangzan101.mika.core.mcp.McpUsb
import com.wangzan101.mika.core.shell.HelperHandoff
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ドキュメントとコードの間に切れ目はありません。ドキュメント内のバッククォートで囲まれたファイル パス、Kotlin クラス名、ツール名はウェアハウスに実際に存在する必要があります。
 * スコープ: README.md、docs/ 下のすべての .md (docs/readme/ 内の各言語の README を含む)、eval/README.md、tools/sandbox/README.md。
 * さらに、同じドキュメント内のハードコーディングできない変数の数 (組み込みのナレッジ、ツール、サービス プロバイダー、評価質問の数) については、[docsDoNotHardCodeCounts] を参照してください。
 *
 * は、「バックティックの内容全体が識別子である」という記述のみをチェックします。以下はチェックされません (ホワイトリストではなくルール)。
 *  - フ​​ェンスで囲まれたコード ブロック (```) は全体として表示されません。行内のバックティック内の空白はコマンドまたはコードの一部です。
 *  - < > * … { } ( ) = : " ' $ , ; | # が含まれます。プレースホルダー、ワイルドカード、呼び出し、キー値、およびサンプル値です。
 *  - / ~ で始まるものはデバイスまたはシステムのパスで、:// を含むものは URL です。
 *  - パス: / の場合、最初の段落のみがウェアハウス ルートの下に実際に存在するディレクトリまたはファイルになります (`files/sandbox/rootfs`、`read/system`、これらはランタイム製品名またはカテゴリ名です)。
 *    / なし、コード クラス拡張子 (kt、py、xml など) のみがチェックされ、json、md、txt などは多くの場合ランタイム製品です。
 *    を確認する場合: ルート ディレクトリに直接存在するか、「モジュール/パッケージ パス/ファイル」の省略形 (`app/task/X.kt`)、または中間の ... で省略されている (`core/.../X.kt`)、または短いファイル名がウェアハウス内のどこかにあります。
 *  - クラス名: 大きなキャメルケースと少なくとも 2 つの大文字 (`Mika` のような単語は適切なクラス名ではありません)。コアとアプリ（テストを含む）のソースコードにclass/object/interface/typealiasがある
 *    宣言または同じ名前のファイル。このリポジトリの外部のパッケージ (Android、JDK、ライブラリ クラス) からインポートされたソース コードは、このリポジトリでは管理されません。
 *  - ツール名: 「最初の段落が既存のツール名の最初の段落である」snake_case のみを検索し (`max_seconds` のようなパラメーター名はチェックされません)、[knownToolNames] を比較します。
 *    これはツール レジストリ リストであり、ゴールデン スナップショットではありません。
 */
class DocsConsistencyTest {
    private val root = File("..").canonicalFile
    private val skipDirs = setOf("build", ".gradle", ".git", ".kotlin", ".idea", "reference", "dist", ".claude", ".omc", "node_modules")

    private val repoFiles: List<String> by lazy {
        root.walkTopDown().onEnter { it.name !in skipDirs }.filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }
    private val repoDirs: List<String> by lazy {
        root.walkTopDown().onEnter { it.name !in skipDirs }.filter { it.isDirectory && it != root }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }

    private val sourceFiles: List<File> by lazy {
        listOf("core/src", "app/src").map { File(root, it) }.filter { it.isDirectory }
            .flatMap { d -> d.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
    }
    private val sourceText: String by lazy { sourceFiles.joinToString("\n") { it.readText() } }
    private val sourceNames: Set<String> by lazy { sourceFiles.map { it.nameWithoutExtension }.toSet() }

    private fun docs(): List<File> {
        val list = mutableListOf(File(root, "README.md"), File(root, "eval/README.md"), File(root, "tools/sandbox/README.md"))
        File(root, "docs").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "md" }?.let { list += it }
        return list.filter { it.isFile }
    }

    /** フェンス コード ブロックを削除した後、すべての行のバッククォートの内容を取得します。 */
    private fun spans(text: String): List<String> {
        var fenced = false
        val prose = text.lines().filter { line ->
            if (line.trimStart().startsWith("```")) { fenced = !fenced; false } else !fenced
        }.joinToString("\n")
        return Regex("`([^`\n]+)`").findAll(prose).map { it.groupValues[1].trim() }.toList()
    }

    private val notIdentifier = Regex("""[\s<>*…{}()=:"'$,;|#]""")
    private val pathExtensions = setOf("kt", "kts", "java", "md", "json", "xml", "txt", "yml", "yaml", "gradle", "toml", "properties", "py", "sh", "js", "html", "pro", "aidl", "cpp", "h")
    private val tool = Regex("""[a-z][a-z0-9]*(_[a-z0-9]+)+""")
    private val classLike = Regex("""[A-Z][A-Za-z0-9]*""")

    private val toolNames: Set<String> by lazy { knownToolNames() }
    private val toolPrefixes: Set<String> by lazy { toolNames.map { it.substringBefore('_') }.toSet() }
    /** request_idなどの入力フィールドは、同じ接頭辞のツール名とは区別する。許可する名前は実際のMCPスキーマから得る。 */
    private val parameterNames: Set<String> by lazy {
        McpTaskTools(FakeRemoteTasks()).tools.flatMap { it.schema["properties"]?.jsonObject?.keys.orEmpty() }.toSet()
    }

    private fun isPath(s: String): Boolean =
        !s.startsWith("/") && !s.startsWith("~") && "://" !in s &&
            ('/' in s || s.substringAfterLast('.', "").let { it in pathExtensions && s.length > it.length + 1 })

    /** ビルドやアプリの実行で生成するパスは、生成元を示してソースの存在検査と区別する。 */
    private val runtimeOnly = mapOf(
        "eval/logged-in.txt" to "評価時に本人がログイン済みと示す端末内のファイルで、リポジトリには含めない",
        HelperHandoff.USB_SCRIPT to "HelperHandoffがスマホの外部ファイル領域へ生成する起動スクリプト",
        McpUsb.SCRIPT to "McpUsbがスマホの外部ファイル領域へ生成するPC接続スクリプト",
        "app/build/outputs/apk/perf" to "appのperfビルドがassemblePerfで生成するAPK出力先",
    )

    /** ウェアハウスのルートにある第 1 レベルのディレクトリまたはファイルの先頭のパスと、コード クラスの短いファイル名のみがウェアハウス内の内容を参照します。残りはランタイム製品または相対表記です。 */
    private val topLevel: Set<String> by lazy { File(root, ".").list().orEmpty().filter { it !in skipDirs }.toSet() }
    private val codeExtensions = setOf("kt", "kts", "java", "py", "sh", "gradle", "xml", "aidl", "cpp", "h")

    private fun pathExists(rel: String): Boolean {
        val clean = rel.removePrefix("./").trimEnd('/')
        if (clean.isEmpty() || clean in runtimeOnly) return true
        val dir = rel.endsWith("/")
        // は、真ん中に ... で書かれています。先頭は実際のディレクトリのプレフィックス、末尾は特定のファイル パスの末尾です (拡張子なしでも可)
        if ("..." in clean) {
            val head = clean.substringBefore("...").trimEnd('/')
            val tail = clean.substringAfterLast("...").trimStart('/')
            return repoFiles.any { f -> f.startsWith(head) && (f.endsWith("/$tail") || f.substringBeforeLast('.').endsWith("/$tail")) }
        }
        if ('/' !in clean) {
            return if (clean.substringAfterLast('.', "") in codeExtensions) repoFiles.any { it == clean || it.endsWith("/$clean") } else true
        }
        val first = clean.substringBefore('/')
        if (first !in topLevel) return true
        if (File(root, clean).exists()) return true
        // 「モジュール/パッケージ パス/ファイル」の略語 (例: app/task/X.kt は app/src/main/java/.../task/X.kt を指します): モジュール内のパスは次の部分で終わることができます
        val rest = clean.substringAfter('/')
        return if (dir) repoDirs.any { it.startsWith("$first/") && (it.endsWith("/$rest")) }
        else repoFiles.any { it.startsWith("$first/") && it.endsWith("/$rest") }
    }

    private fun classExists(name: String): Boolean =
        name in sourceNames || Regex("""\b(class|object|interface|typealias)\s+$name\b""").containsMatchIn(sourceText)

    private fun importedFromOutside(name: String): Boolean =
        Regex("""(?m)^import\s+(?!com\.wangzan101\.mika\.)[\w.]+\.$name\b""").containsMatchIn(sourceText)

    private fun problems(doc: File): List<String> = problems(doc.relativeTo(root).invariantSeparatorsPath, doc.readText())

    private fun problems(rel: String, text: String): List<String> {
        return spans(text).distinct().mapNotNull { s ->
            if (notIdentifier.containsMatchIn(s)) return@mapNotNull null
            when {
                isPath(s) -> if (pathExists(s)) null else "$rel: パス `$s` がリポジトリに見つかりません"
                tool.matches(s) -> if (s.substringBefore('_') !in toolPrefixes || s in toolNames || s in parameterNames) null else "$rel: ツール名 `$s` は現在のツールにありません"
                classLike.matches(s) && s.count { it.isUpperCase() } >= 2 && s.any { it.isLowerCase() } ->
                    if (classExists(s) || importedFromOutside(s)) null else "$rel: クラス名 `$s` がcore/appのソースに見つかりません"
                else -> null
            }
        }
    }

    @Test fun declaredParametersAndGeneratedPathsRemainCheckedAgainstTheirContracts() {
        assertTrue("request_idはMCPの入力フィールドとして定義されている", "request_id" in parameterNames)
        assertEquals(emptyList<String>(), problems("guide.md", "`request_id`、`${HelperHandoff.USB_SCRIPT}`、`${McpUsb.SCRIPT}`"))
        assertTrue("perfビルドが実際に定義されている", File(root, "app/build.gradle.kts").readText().contains("create(\"perf\")"))
        assertEquals(emptyList<String>(), problems("guide.md", "`app/build/outputs/apk/perf/`"))
        assertEquals(1, problems("guide.md", "`app/build/outputs/apk/perf-typo/`").size)
        val bad = problems("guide.md", "`request_typo`、`missing-bootstrap.sh`")
        assertEquals(2, bad.size)
        assertTrue(bad.toString(), bad.any { "request_typo" in it && "ツール名" in it })
        assertTrue(bad.toString(), bad.any { "missing-bootstrap.sh" in it && "パス" in it })
    }

    /**
     * 変更される内容の数に制限はありません。「18 個のアプリ」、「60 個以上のツール」、「128 個の評価質問」 (英語では「18 個のアプリ」および「60 個以上のツール」) は、コンテンツが追加されるとすぐに期限切れになります。
     * リストを作成するには、README で生成された段落 (`./gradlew updateReadme`) を使用します。実際に番号を書きたい行の最後に [countOk] を追加し、その理由を説明します。
     */
    private val hardCount = Regex(
        """\d+\s*多?\s*(份|个|道|家)\s*(内置)?\s*(App|app|网站|站点|技能|工具|服务商|预设|(真机)?评测题)""" +
            """|\b\d+\+?\s+(built-in\s+)?(apps?|websites?|sites?|skills?|tools?|providers?|presets?|(on-device\s+)?eval\s+tasks?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val countOk = "<!-- count-ok -->"

    @Test fun docsDoNotHardCodeCounts() {
        val bad = docs().flatMap { doc ->
            val rel = doc.relativeTo(root).invariantSeparatorsPath
            doc.readLines().withIndex().filter { (_, line) -> countOk !in line && hardCount.containsMatchIn(line) }
                .map { (i, line) -> "$rel:${i + 1}: 「${hardCount.find(line)!!.value}」" }
        }
        assertTrue("変動する件数を文書へ固定で記載しています。件数を省き、一覧はREADMEの生成部分を使ってください:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun countPatternCatchesTypicalWording() {
        listOf("18 份 App", "12 份网站", "4 份技能", "60 多个工具", "128 道真机评测题", "7 家服务商",
            "18 apps", "12 websites", "4 skills", "60 tools", "60+ tools", "7 providers", "18 built-in apps", "128 eval tasks").forEach {
            assertTrue(it, hardCount.containsMatchIn(it))
        }
        listOf("最多 3 条知识", "一次最多 9 个", "每个任务最多 3 份,关联的技能",
            "Android 13 and later", "python3 tools/x.py", "up to 3 notes", "arm64 devices").forEach {
            assertTrue(it, !hardCount.containsMatchIn(it))
        }
    }

    @Test fun inputsAreFound() {
        assertTrue("リポジトリのルートが見つかりません。作業ディレクトリはcore/を使ってください", File(root, "settings.gradle.kts").isFile)
        assertTrue("文書が見つかりません", docs().size >= 5)
        assertTrue(toolNames.contains("knowledge_read"))
        assertTrue(classExists("ToolDef"))
        assertTrue(spans(docs().joinToString("\n") { it.readText() }).size > 50)
    }

    @Test fun docsMentionOnlyThingsThatExist() {
        val bad = docs().flatMap(::problems)
        assertTrue("リポジトリにないものが文書に記載されています:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
