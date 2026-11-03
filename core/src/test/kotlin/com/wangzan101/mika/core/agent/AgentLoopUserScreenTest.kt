package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.userscreen.ScreenLine
import com.wangzan101.mika.core.userscreen.UserScreen
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import com.wangzan101.mika.core.userscreen.UserScreenSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** ユーザーが現在の画面を送信すると、モデルには次のような内容が表示されます: 文、スクリーンショット、詳細。前のラウンドから残っているのは 1 行だけです。画面のみを送信した場合は意図は判断されません。 */
class AgentLoopUserScreenTest {
    private val at = 1_761_624_120_000L // 2025-10-28 04:02 UTC
    private val chat = UserScreen("s1", at, "com.example.chat", "聊天", lines = listOf(
        ScreenLine("周六那个聚会你还去吗", 300, 200), ScreenLine("应该去吧,怎么了", 700, 260),
    ))
    private val bank = UserScreen("s2", at, "com.example.bank", "银行", blocked = true)
    private val png = byteArrayOf(1, 2, 3)
    private val screenAnswer = "2つ目の「应该去吧,怎么了」は相手の発言です。土曜日の集まりに行くかという質問に、たぶん行くと答え、何か用があるのかを尋ねています。"

    private val source = object : UserScreenSource {
        override suspend fun screen(id: String) = listOf(chat, bank).firstOrNull { it.id == id }
        override suspend fun image(id: String) = png.takeIf { id == "s1" }
    }

    private fun loop(model: FakeModel, goal: String, vision: Boolean = false, prior: List<Turn> = emptyList(), judge: FakeModel? = null,
        events: MutableList<RunEvent> = mutableListOf()): AgentLoop {
        val spec = RunSpec(RunId("r"), goal, "c", prior)
        val tools = toolDispatcher(FakeScreen(), visionEnabled = vision, runSpec = spec, userScreens = source)
        return AgentLoop(model, tools, "B", zone = ZoneOffset.UTC, userScreens = source, intentJudge = judge,
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } })
    }

    @Test fun screenWithoutWordsBecomesSentenceImageAndDetail() = runBlocking {
        val model = FakeModel(answerReply("好"))
        val goal = UserScreenMessage.compose("s1", "")
        loop(model, goal, vision = true).run(goal)
        val msgs = model.requests.first().withoutRuntime()
        val said = msgs.first { it.author == com.wangzan101.mika.core.model.Author.User }
        assertEquals("ユーザーが今見ている状態を撮影したばかり（聊天、Oct 28 04:02）。言葉は添えられていない。", (said.parts.first() as Part.Text).text)
        assertEquals(1, said.parts.count { it is Part.Image })
        val detail = msgs[msgs.indexOf(said) + 1]
        assertEquals(ContextKind.UserScreen, ContextBuilder.kindOf(detail))
        val body = ContextBuilder.bodyOf(detail)!!
        assertTrue(body.contains("状態の id: s1"))
        assertTrue(body.contains("(300,200) 周六那个聚会你还去吗"))
        assertTrue(body.contains("<untrusted>"))
        assertTrue("言葉が添えられていない場合は画面から意図を読み取る規則を含める必要があります", body.contains("ユーザーは言葉を添えず"))
    }

    @Test fun screenWithWordsHasNoExtraRuleAndNoImageWhenMainModelIsBlind() = runBlocking {
        val model = FakeModel(answerReply("好"))
        val goal = UserScreenMessage.compose("s1", "第二句是谁说的")
        loop(model, goal, vision = false).run(goal)
        val msgs = model.requests.first().withoutRuntime()
        val said = msgs.first { it.author == com.wangzan101.mika.core.model.Author.User }
        assertEquals("ユーザーが今見ている状態を撮影したばかり（聊天、Oct 28 04:02）。次の言葉を添えている: 第二句是谁说的", (said.parts.single() as Part.Text).text)
        val body = ContextBuilder.bodyOf(msgs[msgs.indexOf(said) + 1])!!
        assertFalse(body.contains("ユーザーは言葉を添えず"))
        assertTrue(model.offeredTools.first().contains("view_user_screen"))
    }

    // モデルに表示される文は、固定の英語の説明と追記です。言語プロンプトと回答の言語チェックは追記を読むだけで、中国語の回答は返されません。
    @Test fun captionIsQuotedWithoutChangingTheJapaneseTarget() = runBlocking {
        val goal = UserScreenMessage.compose("s1", "第二句是谁说的")
        val model = FakeModel(answerReply(screenAnswer), repeatLast = true)
        loop(model, goal).run(goal)
        assertEquals(1, model.requests.size)
        val hint = model.requests[0].runtimeHint()
        assertTrue(hint, "latest message: \"第二句是谁说的\"" in hint)
    }

    // は入力せずに画面を送信しただけです。撮影する言語はなく、言語のプロンプトも表示されず、チェックも行われません。
    @Test fun screenWithoutWordsStillTargetsJapanese() = runBlocking {
        val goal = UserScreenMessage.compose("s1", "")
        val model = FakeModel(answerReply(screenAnswer), repeatLast = true)
        loop(model, goal).run(goal)
        assertEquals(1, model.requests.size)
        val hint = model.requests[0].runtimeHint()
        assertFalse(hint, "latest message" in hint)
        assertTrue(hint, "Write to the user in Japanese." in hint)
    }

    private fun List<com.wangzan101.mika.core.model.Message>.runtimeHint(): String =
        flatMap { it.parts }.filterIsInstance<Part.Text>().last { ContextBuilder.kindOf(it.text) == ContextKind.RuntimeState }.text

    @Test fun blockedScreenSaysItCouldNotBeCaptured() = runBlocking {
        val model = FakeModel(answerReply("好"))
        val goal = UserScreenMessage.compose("s2", "")
        loop(model, goal, vision = true).run(goal)
        val msgs = model.requests.first().withoutRuntime()
        val said = msgs.first { it.author == com.wangzan101.mika.core.model.Author.User }
        assertTrue((said.parts.single() as Part.Text).text.contains("画像の取得を許可していない"))
        assertTrue(ContextBuilder.bodyOf(msgs[msgs.indexOf(said) + 1])!!.contains("画像の取得を許可していない"))
    }

    @Test fun earlierScreensCollapseToOneLine() = runBlocking {
        val model = FakeModel(answerReply("好"))
        val prior = listOf(Turn(UserScreenMessage.compose("s1", "看看这个"), "她在提醒你", "succeeded"))
        loop(model, "那我怎么回", prior = prior).run("那我怎么回", prior)
        val first = model.requests.first().withoutRuntime().first().plainText()
        assertTrue(first.startsWith(Conversation.HEADER))
        assertTrue(first.contains("（ユーザーが以前に送った状態 s1: 聊天、Oct 28 04:02。その状態は「周六那个聚会你还去吗 应该去吧,怎么了」で始まる） 看看这个"))
        assertFalse(first.contains("[[screen:"))
        // 過去ラウンド画面があり、番号に従って振り返ることができます
        assertTrue(model.offeredTools.first().contains("view_user_screen"))
    }

    @Test fun noScreenNoTool() = runBlocking {
        val model = FakeModel(answerReply("好"))
        loop(model, "看看天气").run("看看天气")
        assertFalse(model.offeredTools.first().contains("view_user_screen"))
    }

    // は画面を送信しただけです。何も言うことはありません。意図に関係なく、提案された通話は通常どおりに行われます
    @Test fun screenWithoutWordsSkipsIntentGate() = runBlocking {
        val main = FakeModel(ModelReply(listOf(Part.ToolCall("c1", "phone_list_apps", "{}")), StopReason.ToolUse, null), answerReply("好"))
        val judge = FakeModel(answerReply("DISCUSS"))
        val events = mutableListOf<RunEvent>()
        val goal = UserScreenMessage.compose("s1", "")
        loop(main, goal, judge = judge, events = events).run(goal)
        assertTrue(judge.requests.isEmpty())
        assertTrue(events.any { it is RunEvent.ToolResult && it.tool == "phone_list_apps" })
    }

    @Test fun viewUserScreenReturnsTextAndImage() = runBlocking {
        val main = FakeModel(ModelReply(listOf(Part.ToolCall("c1", "view_user_screen", """{"id":"s1"}""")), StopReason.ToolUse, null), answerReply("好"))
        val goal = UserScreenMessage.compose("s1", "")
        loop(main, goal, vision = true).run(goal)
        val result = main.requests[1].last { it.parts.any { p -> p is Part.ToolResult } }.parts.filterIsInstance<Part.ToolResult>().single()
        assertTrue(result.content.contains("周六那个聚会你还去吗"))
        assertTrue(result.content.contains("\"imageAttached\":true"))
    }
}
