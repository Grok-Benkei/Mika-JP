package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.replay.Events
import com.wangzan101.mika.core.replay.MemRoutes
import com.wangzan101.mika.core.replay.Notes
import com.wangzan101.mika.core.replay.PagedScreen
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.replay.ui
import com.wangzan101.mika.core.verify.Verifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

class AgentLoopReplayTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = ModelReply(listOf(Part.ToolCall("m${++seq}", name, args)), StopReason.ToolUse, null)
    private fun text(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)
    private val finish = text("保存しました。画面に「新笔记」があります")
    private val pick = """{"route": 1, "slots": ["今天天气不错"]}"""
    private val pass = """{"checks": [{"criterion": "t", "status": "met", "quote": "新笔记"}], "verdict": "pass", "reason": "屏上有"}"""
    private val goal = "在备忘录里写今天天气不错"

    private class Run(val sink: Events, val main: FakeModel, val store: MemRoutes, val screen: PagedScreen)

    private fun run(
        screen: PagedScreen = Notes.screen(), store: MemRoutes = MemRoutes(Notes.route()), main: FakeModel = FakeModel(finish),
        matcher: ChatModel = FakeModel(text(pick)), flags: SpeedFlags = SpeedFlags(replay = true), verifier: Verifier? = null,
        task: String = goal,
        /** 答えを渡してから勝敗を判定します（本製品ではユーザーに答えを渡してから勝敗を判定します）。ルートは完了した場合にのみ成功として記録されます。 */
        outcome: RunOutcome = RunOutcome.Done,
    ): Run {
        val sink = Events()
        runBlocking {
            val agent = AgentLoop(main, toolDispatcher(screen), "BASE", sink = sink, flags = flags, verifier = verifier,
                routes = RouteMatcher(matcher, store, null))
            agent.run(task)
            agent.noteOutcome(outcome)
        }
        return Run(sink, main, store, screen)
    }

    /** ダイアログを返すときに、ダイアログのすべてのテキスト (説明、および画面を読み取るための呼び出しの結果) を追加します。 */
    private fun firstPrompt(r: Run) = r.main.requests.first().drop(1).flatMap { it.parts }.joinToString("") {
        when (it) {
            is Part.Text -> it.text
            is Part.ToolResult -> it.content
            else -> ""
        }
    }

    @Test fun goalsThatNameTheWebAreNotReplayed() {
        val matcher = FakeModel(text(pick))
        val r = run(matcher = matcher, task = "用网页$goal")
        assertTrue(r.sink.all.filterIsInstance<RunEvent.Replay>().isEmpty())
        assertTrue(matcher.requests.isEmpty())
    }

    @Test fun divergedHandsBackWithSummaryAndFullScreen() {
        val s = Notes.screen()
        s.pages["home"] = listOf(ui("@e4", "text", "请先登录", listOf(0, 0, 999, 60), clickable = false))
        val r = run(screen = s)
        val msg = firstPrompt(r)
        assertTrue(msg, msg.contains("3 手順のうち 1 手順が、以前に成功した進め方に従って自動で実行された"))
        assertTrue(msg, msg.contains("1. phone_launch_app com.notes"))
        assertTrue(msg, msg.contains("手順 2 は"))
        assertTrue(msg, msg.contains("请先登录"))
        // には全画面表示 (番号とスナップショット ID) が付属しており、モデルを直接作成できます。
        assertTrue(msg, msg.contains("\"snapshotId\"") && msg.contains("\"ref\":\"@e4\""))
        // の画面読み取りは呼び出しと結果のペアであり (アシスタントの呼び出しが最初に行われ、すぐに結果が続きます)、各インターフェイスのペアリング要件が満たされています。
        val h = r.main.requests.first().withoutRuntime()
        val callAt = h.indexOfFirst { m -> m.role == Role.Assistant && m.parts.any { it is Part.ToolCall && it.id == "replay_handback" && it.name == "phone_list_elements" } }
        assertTrue(callAt > 0)
        assertEquals("replay_handback", (h[callAt + 1].parts.single() as Part.ToolResult).callId)
        assertEquals(callAt + 2, h.size)
        assertEquals(listOf(true, false), r.sink.all.filterIsInstance<RunEvent.Replay>().map { it.matched })
        assertEquals(1, r.store.m["r1"]!!.consecutiveMisses)
        assertFalse(r.store.m["r1"]!!.disabled)
    }

    @Test fun executedButUnchangedStepToldToModel() {
        val s = Notes.screen().apply { links.clear() }
        val msg = firstPrompt(run(screen = s))
        assertTrue(msg, msg.contains("手順 2 は実行されたが、期待した変化が現れなかった"))
    }

    @Test fun unreadableHandbackScreenStillContinues() {
        val s = Notes.screen().apply { failReads = true }
        val r = run(screen = s)
        val msg = firstPrompt(r)
        assertTrue(msg, msg.contains("現在の状態を読めなかった"))
        assertEquals("succeeded", r.sink.all.filterIsInstance<RunEvent.TaskEnded>().single().outcome)
    }

    @Test fun completedStillRequiresAnAnswer() {
        // モデルは、歩行を完了した後、自分自身の質問に答える必要があります。答えが検証された後にのみ、ルートは成功したと見なされます。この答えで引用されている「新しいメモ」が読まれています（文字通りの検証は正しいので、別の検証を求める必要はありません）。
        val ok = run(
            main = FakeModel(text("保存しました。「新笔记」にあります"), text("保存しました。「新笔记」にあります")),
            verifier = Verifier(FakeModel(text(pass), text(pass)), null),
        )
        assertTrue(firstPrompt(ok).contains("以前の進め方のすべての手順が実行された"))
        assertTrue(ok.main.requests.size >= 1)
        assertFalse(ok.main.requests.joinToString { it.toString() }.contains("\"name\":\"新笔记\""))
        assertEquals(0, ok.store.m["r1"]!!.successes)

        val gaveUp = run(main = FakeModel(call("end_not_done", """{"message":"保存できませんでした"}""")), verifier = Verifier(FakeModel(text(pass)), null))
        assertTrue(gaveUp.sink.all.none { it is RunEvent.Verifier })
        assertEquals(0, gaveUp.store.m["r1"]!!.successes)

        // 画面の引用がなく、検証するものがない完了は、結末として成功が記録される。
        assertEquals(1, run(main = FakeModel(text("保存しました"))).store.m["r1"]!!.successes)
        // はチェックされましたが、一致しませんでした (一度返された後も未確認としてマークされています): 成功として記録されません
        val doubtful = run(main = FakeModel(text("保存しました。「旧草稿 42 号」にあります"), text("保存しました。「旧草稿 42 号」にあります")))
        assertEquals(0, doubtful.store.m["r1"]!!.successes)
    }

    @Test fun routeSuccessOnlyWhenJudgedDone() {
        // の回答は検証されましたが、その一部のみが完了したか、完了できなかった、または成功と判定されなかった場合、成功として記録されません。
        for (o in listOf(RunOutcome.Partial, RunOutcome.NotPossible, RunOutcome.Unknown)) {
            val r = run(main = FakeModel(text("保存しました。「新笔记」にあります")), verifier = Verifier(FakeModel(text(pass)), null), outcome = o)
            assertEquals(o.wire, 0, r.store.m["r1"]!!.successes)
        }
    }

    @Test fun twoMissesAtSameStepDisableRoute() {
        val store = MemRoutes(Notes.route())
        fun broken() = Notes.screen().apply { pages["home"] = listOf(ui("@e4", "text", "请先登录", listOf(0, 0, 999, 60), clickable = false)) }
        run(screen = broken(), store = store)
        assertFalse(store.m["r1"]!!.disabled)
        run(screen = broken(), store = store)
        assertTrue(store.m["r1"]!!.disabled)
        // は非アクティブ化後は候補ではなくなります。一致するモデルは呼び出されません。
        val m = FakeModel()
        run(store = store, matcher = m)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun replayOffNoMatcherCall() {
        val m = FakeModel()
        val r = run(matcher = m, flags = SpeedFlags.OFF)
        assertTrue(m.requests.isEmpty())
        assertTrue(r.sink.all.none { it is RunEvent.Replay })
        assertTrue(r.screen.typed.isEmpty())
    }

    @Test fun noModelCallsDuringReplay() {
        val s = Notes.screen()
        var typedWhenFirstAsked: List<String>? = null
        // メイン モデルが呼び出されるとすぐに、その時点でポートが受信した入力を記録します。再生プロセス全体の間、メイン モデルに触れないでください。
        val main = object : ChatModel by FakeModel() {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                if (typedWhenFirstAsked == null) typedWhenFirstAsked = s.typed.toList()
                return finish
            }
        }
        val sink = Events()
        val matcher = FakeModel(text(pick))
        runBlocking {
            AgentLoop(main, toolDispatcher(s), "BASE", sink = sink, flags = SpeedFlags(replay = true),
                routes = RouteMatcher(matcher, MemRoutes(Notes.route()), null)).run(goal)
        }
        assertEquals(listOf("今天天气不错"), typedWhenFirstAsked)
        assertEquals(1, matcher.requests.size)
        val firstReply = sink.all.indexOfFirst { it is RunEvent.ModelReply }
        assertTrue(sink.all.withIndex().filter { it.value is RunEvent.Replay }.all { it.index < firstReply })
    }
}
