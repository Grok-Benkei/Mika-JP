package com.wangzan101.mika.core.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** ウェアハウス (eval/tasks/<category>/…/<id>.json) に設定された評価タスク。 */
class TaskSetTest {
    private val evalDir = listOf("eval", "../eval").map(::File).first { File(it, "tasks").isDirectory }
    private val tasksDir = File(evalDir, "tasks")
    private val files = tasksDir.walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.path }.toList()
    private val tasks = files.map { it to EvalTasks.parse(it.readText()) }

    /** ファイルは、read/content などのタスク ディレクトリに関連しています。 */
    private fun group(f: File) = f.parentFile.relativeTo(tasksDir).invariantSeparatorsPath

    /** 特別な問題セット (マッチング問題、記憶問題) は別のディレクトリに配置され、カテゴリには分割されません。 */
    private fun suite(f: File) = group(f).substringBefore('/').takeIf { it in SUITES }

    @Test fun allTasksParseAndValidate() {
        assertTrue(files.isNotEmpty())
        assertEquals(emptyList<String>(), EvalTasks.validateAll(tasks.map { it.second }))
        tasks.forEach { (f, t) ->
            // Web ページの質問シートは Web ディレクトリに配置されます。さまざまなカテゴリがあり、携帯電話上のアプリが操作されていない場合、アプリは空になることがあります。
            if (group(f) == WEB) {
                assertTrue("${t.id}: ウェブの問題のidは web- で始める必要があります", t.id.startsWith("web-"))
                t.apps.forEach { assertTrue("${t.id}: installed:$it がありません", pinnedOk(t, it)) }
                return@forEach
            }
            // カテゴリ ディレクトリは質問のカテゴリと一致しています: 評価者はディレクトリに従って質問を選択します
            if (suite(f) == null) assertEquals(f.path, t.category.name.lowercase(), group(f).substringBefore('/'))
            // そもそもできない質問（月の温度）はアプリを書く必要がなく、アプリも必要ありません。
            assertTrue("${t.id}: apps が空です", t.apps.isNotEmpty() || t.category == EvalCategory.Impossible)
            t.apps.forEach { assertTrue("${t.id}: installed:$it がありません", pinnedOk(t, it)) }
            if (t.category == EvalCategory.Impossible) assertEquals(t.id, Expect.Failed, t.expect)
            if (t.category == EvalCategory.Barrier) assertEquals(t.id, Expect.Takeover, t.expect)
            if (t.category == EvalCategory.Read) assertEquals(t.id, Expect.Succeeded, t.expect)
        }
    }

    /** サードパーティ アプリはインストール済みと書き込む必要があります:;電話機に付属している場合は、{app:<capability>} と記述します。これには、installed: は必要ありません。 */
    private fun pinnedOk(t: EvalTask, app: String) = APP_TOKEN.matches(app) || "installed:$app" in t.requires

    @Test fun idsUniqueAndMatchFileNames() {
        tasks.forEach { (f, t) -> assertEquals(f.path, f.nameWithoutExtension, t.id) }
        assertEquals(tasks.size, tasks.map { it.second.id }.toSet().size)
    }

    @Test fun compositionMeetsMinimums() {
        assertTrue("合計 ${tasks.size} 件", tasks.size >= 60)
        val count = tasks.filter { suite(it.first) == null }.groupingBy { group(it.first) }.eachCount()
        val minimums = mapOf(
            "read/system" to 10,
            "write" to 10,
            "read/content" to 15,
            "read/shopping" to 10,
            "irreversible" to 8,
            "impossible" to 5,
            "barrier" to 2,
            WEB to 10,
        )
        minimums.forEach { (g, min) -> assertTrue("$g の問題は${count[g] ?: 0}問しかありません", (count[g] ?: 0) >= min) }
        assertEquals("指定のディレクトリ外にある問題があります", minimums.keys, count.keys)
    }

    @Test fun skillPairsAreTrainTest() {
        val skills = tasks.filter { suite(it.first) == "skills" }.map { it.second }
        assertEquals(20, skills.size)
        val groups = skills.groupBy { it.id.substringBeforeLast('-') }
        assertEquals(10, groups.size)
        groups.forEach { (g, ts) ->
            assertEquals(g, setOf("train", "test"), ts.map { it.split }.toSet())
            ts.forEach { assertEquals(it.id, "$g-${it.split}", it.id) }
            assertEquals("$g: 同じ組の2問は同じアプリを使う必要があります", 1, ts.map { it.apps }.toSet().size)
            assertEquals("$g: 同じ組の2問は同じ分類にする必要があります", 1, ts.map { it.category }.toSet().size)
            assertTrue("$g: 2問のパラメータは異なる必要があります", ts[0].goal != ts[1].goal)
        }
        assertTrue(skills.none { it.session != null })
    }

    @Test fun memorySessionsTellThenUse() {
        val mem = tasks.filter { suite(it.first) == "memory" }.map { it.second }
        val sessions = mem.groupBy { it.session }
        assertEquals(5, sessions.size)
        sessions.forEach { (session, ts) ->
            assertTrue(session != null)
            val (tell, use) = ts.sortedBy { it.id }.also { assertEquals(2, it.size) }
            assertTrue("${tell.id}: 1問目ではMikaに記憶を求める必要があります", "覚えて" in tell.goal)
            assertTrue("${tell.id}: 1問目では記憶の利用を検査しません", tell.checks.none { it is Check.MemoryUsed })
            assertTrue("${use.id}: 2問目では記憶の利用を検査する必要があります", use.checks.any { it is Check.MemoryUsed })
            assertTrue("${use.id}: 2問目では記憶を求める指示を繰り返しません", "覚えて" !in use.goal)
        }
        assertTrue(mem.none { it.split != null })
    }

    @Test fun partitionsAreStratified() {
        // 各質問はチューニング プールまたは保持プールに割り当てられます。保持プールは約 30% を占め、ディレクトリごとに階層化されています。各ディレクトリには予約済みの質問があります。
        tasks.forEach { (f, t) -> assertTrue("${f.path}: partition がありません", t.partition in EvalTasks.PARTITIONS) }
        val holdout = tasks.count { it.second.partition == "holdout" }
        assertTrue("保留用の問題は $holdout / ${tasks.size} 件です", holdout * 100 in tasks.size * 25..tasks.size * 35)
        tasks.groupBy { group(it.first) }.forEach { (g, ts) ->
            val h = ts.count { it.second.partition == "holdout" }
            assertTrue("$g: 保留用の問題は $h / ${ts.size} 件です", h >= 1 && h * 100 in ts.size * 20..ts.size * 40)
        }
        // 一連の一致する質問と記憶セッションは同じプール内にあり、分離されません。
        tasks.filter { suite(it.first) == "skills" }.map { it.second }.groupBy { it.id.substringBeforeLast('-') }
            .forEach { (g, ts) -> assertEquals(g, 1, ts.map { it.partition }.toSet().size) }
        tasks.filter { suite(it.first) == "memory" }.map { it.second }.groupBy { it.session }
            .forEach { (s, ts) -> assertEquals("$s", 1, ts.map { it.partition }.toSet().size) }
    }

    @Test fun askingTasksCanContinue() {
        // では、最初に尋ねられた質問には自動回答が必要です。そうしないと、レビュー内の質問に誰も回答できず、タイムアウトになるまで待たなければなりません。
        tasks.map { it.second }.filter { t -> t.checks.any { it is Check.AskedUser && it.expected } }
            .also { assertTrue(it.isNotEmpty()) }
            .forEach { assertTrue("${it.id}: 質問が必要な問題に answers がありません", it.answers.isNotEmpty()) }
    }

    @Test fun writeTasksHaveMarker() {
        val writes = tasks.filter { suite(it.first) != "memory" }.map { it.second }.filter { it.category == EvalCategory.Write }
        assertTrue(writes.isNotEmpty())
        writes.forEach { t ->
            val marker = t.marker
            assertTrue("${t.id}: marker がありません", marker != null && "{run}" in marker)
            assertTrue("${t.id}: 目標に marker がありません", marker!! in t.goal)
            // によって作成されたデータは、評価者自体によって取得する必要があります。
            assertTrue("${t.id}: marker に基づく検査がありません", t.checks.any { (it is Check.ScreenHas && marker in it.text) || (it is Check.ProbeEquals && marker in it.expected) })
        }
    }

    /** 評価データが定める、支出前の確認とアカウント・対象の事前条件を検査する。実機での操作は行わない。 */
    @Test fun irreversibleTasksAskOnlyBeforeSpending() {
        val irreversible = tasks.map { it.second }.filter { it.category == EvalCategory.Irreversible }
        assertTrue(irreversible.isNotEmpty())
        val (spending, free) = irreversible.partition { it.expect == Expect.Confirmation }
        assertTrue(spending.isNotEmpty() && free.isNotEmpty())
        spending.forEach { t ->
            val card = t.checks.filterIsInstance<Check.ConfirmationShows>()
            // カードには、どの会社のカードなのか、何をするのか、そしていくらかかるのかが記載されている必要があります。
            assertTrue("${t.id}: 注文だけに確認が必要です", card.isNotEmpty() && card.all { it.action == "注文" })
            assertTrue("${t.id}: 注文では対象と金額を検査する必要があります", card.any { it.target != null && it.amount != null })
            // は実際のアカウントのお金を使用します。ユーザーが存在し、アカウントが手動でログイン済みとしてマークされている場合にのみ実行されます。
            assertTrue("${t.id}: signedIn: の事前条件がありません", t.apps.all { "signedIn:$it" in t.requires })
            assertTrue("${t.id}: 説明がありません", !t.note.isNullOrBlank())
        }
        free.forEach { t ->
            assertEquals(t.id, Expect.Succeeded, t.expect)
            assertTrue("${t.id}: 支出のない問題に確認カードは不要です", t.checks.none { it is Check.ConfirmationShows })
            // ユーザーがやりたいことを明確にしたら、それ以上質問すべきではありません。どの項目を削除するかなど、ユーザーのみが知っている質問のみを行うべきです。
            val asked = t.checks.filterIsInstance<Check.AskedUser>()
            assertTrue("${t.id}: ユーザーへ質問したか検査する必要があります", asked.size == 1)
            // 取り消しできないことは、この質問のマークされたデータに対してのみ実行できます。
            val marker = t.marker
            assertTrue("${t.id}: {run} を含む marker が必要です", marker != null && "{run}" in marker)
            assertTrue("${t.id}: 目標に marker を記述する必要があります", marker!! in t.goal)
            // 実際のアカウントに送信された質問: ユーザーが存在し、アカウントが手動でログイン済みとしてマークされている場合にのみ実行されます。送信後、アプリを確認してください。
            if (t.apps.any { !it.startsWith("{app:") }) {
                assertTrue("${t.id}: signedIn: の事前条件がありません", t.apps.all { "signedIn:$it" in t.requires })
                assertTrue("${t.id}: 説明がありません", !t.note.isNullOrBlank())
                assertTrue("${t.id}: marker に基づいて送信済みか検査する必要があります", t.checks.any { it is Check.ScreenHas && marker in it.text })
            }
        }
    }

    @Test fun systemAppsAreNotPinned() {
        // スマホ標準のアプリは機種によって異なる。機能を指定し、実行時に標準インテントからアプリを探す。
        val system = tasks.map { it.second }.filter { "スマホ標準" in it.goal }
        assertTrue(system.isNotEmpty())
        system.forEach { t ->
            t.apps.forEach { assertTrue("${t.id}: 端末標準アプリはパッケージ名ではなく {app:...} で指定してください: $it", APP_TOKEN.matches(it)) }
            t.checks.filterIsInstance<Check.ScreenHas>().forEach { assertTrue("${t.id}: screen_has はパッケージ名に固定しません", APP_TOKEN.matches(it.packageName)) }
            t.requires.forEach { assertFalse("${t.id}: 端末標準アプリに installed: は指定しません", it.startsWith("installed:")) }
        }
    }

    private companion object {
        val SUITES = setOf("skills", "memory")
        val APP_TOKEN = Regex("^\\{app:[a-z]+\\}$")
        /** Web ページの質問のディレクトリ。 */
        const val WEB = "web"
    }

    @Test fun noLocalPaths() {
        val scanned = evalDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(scanned.size >= files.size)
        scanned.forEach { f ->
            assertFalse("${f.path} mentions a local path", f.readText().contains("/Users/"))
        }
    }
}
