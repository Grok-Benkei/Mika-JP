package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS

class RunFactsTest {
    private fun screen(step: Int, vararg names: String, app: String? = "com.x") = com.wangzan101.mika.core.testing.legacyScreenText(step.toLong(), step, app, names.toList())
    private fun typed(step: Int, text: String) = RunEvent.ModelReply(step.toLong(), step, "", "phone_type_text", """{"text":${kotlinx.serialization.json.JsonPrimitive(text)}}""", null, null, null)
    private fun launch(step: Int, pkg: String) = RunEvent.ModelReply(step.toLong(), step, "", "phone_launch_app", """{"package":"$pkg"}""", null, null, null)

    @Test fun ledgerKeepsEveryScreenWhole() {
        val long = (1..500).map { "第 $it 条标题" }.toTypedArray()
        val ev = listOf(screen(1, "旧"), screen(2, "甲"), screen(3, "乙"), screen(4, *long))
        // は過去 3 回だけを保持するのではなく、最も古い読み取り画面も台帳に記録されます。
        val l = RunFacts.ledger(ev, TOOLS).all
        assertEquals(listOf("旧", "甲", "乙", long.joinToString("\n").take(com.wangzan101.mika.core.verify.EvidenceLedger.READ_CHARS)), l.map { it.text })
        assertEquals(listOf(1, 2, 3, 4), l.map { it.step })
        assertEquals("com.x", l.first().where)
    }

    @Test fun typedEchoExcludedOnlyWhenWholeElement() {
        val ev = listOf(typed(1, "时光 回溯"), screen(2, "时光回溯", "搜索结果:时光回溯 无匹配", "设置"))
        // 入力ボックスのエコー (行全体が入力された単語と同じ) が削除されます。これらの単語を含む他の要素は保持されます。
        assertEquals(listOf("搜索结果:时光回溯 无匹配\n设置"), RunFacts.evidence(ev, TOOLS, "", "时光回溯"))
    }

    @Test fun namesWithQuotesAndNewlinesKept() {
        val ev = listOf(screen(1, "他说\"你好\"", "第一行\n第二行", "反斜杠\\"))
        assertEquals(listOf("他说\"你好\"\n第一行\n第二行\n反斜杠\\"), RunFacts.evidence(ev, TOOLS, "", "x"))
    }

    // ツール結果内の作業モデルに対して書かれたプロンプトは、読み取られたマテリアルではありません。失敗した結果もカウントされません。
    @Test fun ledgerTakesReadResultsWithoutHints() {
        fun result(step: Int, tool: String, json: String) = RunEvent.ToolResult(step.toLong(), step, tool, json, null, null)
        val ev = listOf(
            result(1, "web_read_page", """{"ok":true,"url":"https://a.example.com/","text":"长江全长 6300 公里","hint":"Mika 提示 9999 公里"}"""),
            result(2, "web_read_page", """{"ok":false,"error":"timeout","message":"超时 8888 公里"}"""),
        )
        val l = RunFacts.ledger(ev, TOOLS).all.filter { it.kind == com.wangzan101.mika.core.observation.ObsKind.Read }
        assertEquals(1, l.size)
        assertEquals("https://a.example.com/", l.single().where)
        assertTrue("6300" in l.single().text && "9999" !in l.single().text)
    }

    @Test fun appsSeenFromScreensAndLaunches() {
        val ev = listOf(launch(1, "com.android.settings"), screen(2, "a", app = "com.android.settings"), screen(3, "b", app = "com.example.health"), screen(4, "c", app = null))
        assertEquals(listOf("com.android.settings", "com.example.health"), RunFacts.appsSeen(ev, TOOLS))
    }

    // 修正 1 M6: レベルで停止 = 最後の確認が拒否され、タスクが自動的に終了しました
    @Test fun stoppedAtGateOnlyWhenDeclineIsLastAndRunEnded() {
        fun c(d: String?) = RunEvent.Confirmation(1, "delete", "x", "y", null, d)
        val end = RunEvent.TaskEnded(9, "failed", "没同意")
        assertEquals(true, RunFacts.stoppedAtGate(listOf(c(null), c("declined"), end)))
        assertEquals(true, RunFacts.stoppedAtGate(listOf(c("declined_before"), end)))
        // は拒否され、宣言されて承認されました: カウントされません
        assertEquals(false, RunFacts.stoppedAtGate(listOf(c("declined"), c(null), c("approved"), end)))
        // が拒否後に終了しない (評価者によってタイムアウトがキャンセルされた)
        assertEquals(false, RunFacts.stoppedAtGate(listOf(c("declined"))))
        assertEquals(false, RunFacts.stoppedAtGate(listOf(c("declined"), RunEvent.TaskEnded(9, "cancelled", "已取消"))))
        assertEquals(false, RunFacts.stoppedAtGate(listOf(c("required"), end)))
    }
}

