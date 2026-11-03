package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.assembly.legacyNames
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.testing.RECORDED_TOOLS
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.verify.RecordedVerifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 名前が変更される前に記録された操作 (その夜のチューニング チームの記録など) は、古い名前比較表に従って読み取られます。記録から得られた事実を評価し、当時の証拠を検証します。
 * は、同じ実行が現在の名前で記録されたときとまったく同じであり、再スコア結果は変わりません。
 */
class RecordedRunNamesTest {
    private var t = 0L
    private fun screen(step: Int, app: String?, vararg names: String) = com.wangzan101.mika.core.testing.legacyScreenText(++t, step, app, names.toList())
    private fun reply(step: Int, tool: String, args: String = "{}") = RunEvent.ModelReply(++t, step, "", tool, args, null, null, null)
    private fun result(step: Int, tool: String, json: String) = RunEvent.ToolResult(++t, step, tool, json, null, null)

    /** 携帯電話が Web ページを追加し、ログインして紙を渡す操作を引き継いだ後、現在の名前を書き留めます。 */
    private val current: List<RunEvent> = listOf(
        reply(1, "phone_launch_app", """{"package":"com.example.notes"}"""), result(1, "phone_launch_app", """{"ok":true}"""),
        screen(2, "com.example.notes", "新建", "搜索"), reply(2, "phone_type_text", """{"text":"买牛奶"}"""), result(2, "phone_type_text", """{"ok":true}"""),
        reply(3, "web_navigate", """{"url":"https://weather.example.com/"}"""), result(3, "web_navigate", """{"ok":true,"url":"https://weather.example.com/","title":"天气"}"""),
        reply(4, "web_read_page"), result(4, "web_read_page", """{"ok":true,"text":"北京 晴 25℃"}"""),
        screen(5, "网站 weather.example.com", "欢迎登录", "请输入密码", "登录"), reply(5, "handle_obstacle", """{"kind":"login"}"""),
        RunEvent.Barrier(++t, "login"), reply(6, "hand_to_user", """{"reason":"login","message":"请登录"}"""),
        RunEvent.State(++t, "waiting_takeover", "请登录"),
        screen(7, "网站 weather.example.com", "北京 晴 25℃"), reply(8, "finish", """{"answer":"北京晴 25℃\n依据:网页正文"}"""),
        RunEvent.Verifier(++t, "pass", "{}", step = 8),
    )

    /** 名前を変更する前の同じ実行の様子: 名前を変更した各ツールは古い名前に置き換えられます。 */
    private val old: List<RunEvent> = run {
        val legacy = legacyNames()
        val former = legacy.names.flatMap { o -> legacy.replacements(o).map { it to o } }.toMap()
        fun back(n: String) = former[n] ?: n
        current.map { e ->
            when (e) {
                is RunEvent.ModelReply -> e.copy(tool = e.tool?.let(::back))
                is RunEvent.ToolResult -> e.copy(tool = back(e.tool))
                else -> e
            }
        }
    }

    private fun facts(events: List<RunEvent>, tools: com.wangzan101.mika.core.tool.ToolIndex) = listOf(
        RunFacts.ledger(events, tools).all, RunFacts.appsSeen(events, tools), RunFacts.appsLaunched(events, tools),
        RunFacts.appsWrittenIn(events, tools), RunFacts.takeoverBarrier(events, tools),
    )

    @Test fun oldNamesRescoreLikeTheCurrentOnes() {
        assertNotEquals("the fixture must really use old names", current.map { (it as? RunEvent.ModelReply)?.tool }, old.map { (it as? RunEvent.ModelReply)?.tool })
        assertEquals(facts(current, TOOLS), facts(old, RECORDED_TOOLS))
        // 対照表に従って読まないと、古い記録から得られる事実が異なります（このテストは開催できます）
        assertNotEquals(facts(current, TOOLS), facts(old, TOOLS))
        // 当時の証拠の検証も同様
        val (v, f) = RecordedVerifications.pairs(current).single()
        val answer = RecordedVerifications.answer(current, f).orEmpty()
        assertEquals(RecordedVerifications.ledgerSources(current, v, f, "", answer), RecordedVerifications.ledgerSources(old, v, f, "", answer))
    }
}
