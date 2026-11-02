package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.observation.tag
import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.replay.ReplayOutcome
import com.wangzan101.mika.core.replay.Replayer
import com.wangzan101.mika.core.replay.Route
import com.wangzan101.mika.core.replay.RouteMatcher
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.Verb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private val REPLAY_SOURCE: String get() = Feedback["replay_source"]
private val REPLAY_SCREEN_HEADER: String get() = Feedback["replay_screen_header"]
private val REPLAY_NO_SCREEN: String get() = Feedback["replay_no_screen"]

/**
 * エクスペリエンスのリプレイ: 初めてモデルを呼び出す前に、適切なルートがある場合は、まずそのルートに従い、完了した場合、または一致しない場合はモデルを返します。タスクが完了すると、エンディングに応じてルート数が更新されます。
 * ルート (ルートの照合、読み取り、書き込み) に問題がある場合、タスクには影響しません。各タスクは [reset] で始まります。
 */
internal class RouteReplay(
    private val routes: RouteMatcher,
    private val tools: ToolDispatcher,
    private val gate: suspend () -> Unit,
    /** は、再生の進行状況を外部に表示します (これは、レベルの待機が終了した後に復元された状態でもあります)。 */
    private val show: (TaskState.Running) -> Unit,
    private val emit: (RunEvent) -> Unit,
    private val clock: () -> Long,
) {
    /** このミッションのリプレイルートと結果。 */
    private var replayed: Pair<Route, ReplayOutcome>? = null

    fun reset() { replayed = null }

    /** 画面を戻すときは、直前に操作していた画面の要素読み取りツールを使用します（再生ルートがどちらの画面に該当するかを読み取ります）。 */
    private val dumpTool: String get() = tools.index.nameOf(Verb.ListElements, tools.surface)!!

    /**
     * ルートを検索して再生します。再生後、プロセス (実行されたステップと一致しない箇所) と現在の全画面読み取りのコピーをモデルに渡し、画面読み取りのフロントエンド アプリに戻ります。
     */
    suspend fun run(task: String, history: MutableList<Message>, executor: ActionExecutor, ctx: ContextBuilder, track: StepTrack): Place? {
        val choice = try {
            withContext(Dispatchers.IO) { routes.choose(task) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val total = choice.route.steps.size
        var running = TaskState.Running(0, REPLAY_NOTE, progress = RunProgress.Replay(1, total))
        show(running)
        val replayer = Replayer(
            tools, executor, gate = gate,
            sink = object : RunSink {
                override fun event(e: RunEvent) {
                    emit(e)
                    // 1 つのステップが正しい場合は、次のステップが表示されます。
                    if (e is RunEvent.Replay && e.matched && e.step < e.total) {
                        running = running.copy(progress = RunProgress.Replay(e.step + 1, e.total))
                        show(running)
                    }
                }
                override fun media(mediaId: Int, png: ByteArray) = Unit
            },
            clock = clock,
        )
        val out = replayer.run(choice)
        replayed = choice.route to out
        update(choice.route.id) { r ->
            when (out) {
                is ReplayOutcome.Diverged -> {
                    val misses = if (r.missStep == out.done) r.consecutiveMisses + 1 else 1
                    r.copy(consecutiveMisses = misses, missStep = out.done, disabled = r.disabled || misses >= ROUTE_MISSES_TO_DISABLE, lastUsedMs = clock())
                }
                else -> r.copy(lastUsedMs = clock())
            }
        }
        val steps = choice.route.steps
        val doneLines = steps.take(out.done).mapIndexed { i, st -> "${i + 1}. ${Replayer.describe(st, choice.slots)}" }.joinToString("\n")
        val head = when (out) {
            is ReplayOutcome.Completed -> Feedback.format("replay.completed", "total" to steps.size, "steps" to doneLines)
            is ReplayOutcome.Diverged -> {
                val error = out.error?.let { Feedback.format("replay.error_code", "code" to it) }.orEmpty()
                val next = if (out.executed) Feedback.format("replay.diverged_executed", "step" to (out.done + 1), "expected" to out.expected, "seen" to out.seen, "error" to error)
                else Feedback.format("replay.diverged_skipped", "step" to (out.done + 1), "expected" to out.expected, "seen" to out.seen, "error" to error)
                Feedback.format("replay.progress", "done" to out.done, "total" to steps.size, "steps" to doneLines.ifEmpty { Feedback["replay.no_steps"] }, "next" to next)
            }
            is ReplayOutcome.StoppedForConfirmation -> Feedback.format("replay.progress", "done" to out.done, "total" to steps.size, "steps" to doneLines.ifEmpty { Feedback["replay.no_steps"] },
                "next" to Feedback.format("replay.stopped", "step" to (out.done + 1), "action" to Replayer.describe(steps[out.done], choice.slots)))
        }
        // は現在、画面を完全に読み取り中です。ここからモデル番号を取得して続行します。読めない場合は指示を提出するだけで、タスクは通常どおり続行されます
        val looked = try {
            tools.executeRead(Part.ToolCall("replay_handback", dumpTool, "{}"), 0).takeIf { it.content["ok"] == JsonPrimitive(true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val read = looked?.content
        if (read != null) {
            // 画面読み取りでは、トリミングと「モデルが表示できる画面の数」という 2 つのツール呼び出しと結果が作成されます。画面読み上げツールを押して識別します。各インターフェイスは、呼び出しと結果の一致要件も満たします。
            val id = "replay_handback"
            history += Message(Role.Assistant, listOf(Part.Text(Feedback.format("replay.handback", "head" to head, "note" to REPLAY_SCREEN_HEADER)), Part.ToolCall(id, dumpTool, "{}")), Author.Mika)
            history += Message(Role.Tool, listOf(Part.ToolResult(id, read.toString(), isError = false, looked?.observations.orEmpty().map { it.tag })), Author.Mika)
            // 映像が見れるメインモデル：返す瞬間とアクション後の映像が見れます。
            tools.imageNow()?.let { (png, shotId) -> history += Message(Role.User, listOf(Part.Text(Feedback["action_frame_note"]), tools.modelImage(png, shotId)), Author.Mika) }
        } else {
            history += ctx.notice(REPLAY_SOURCE, Feedback.format("replay.handback", "head" to head, "note" to REPLAY_NO_SCREEN))
        }
        // 復帰後のステップをリカバリステップとしてカウントします（大機種と小機種に分かれている場合はメイン機種扱い）
        track.markRecovery()
        return looked?.place
    }

    /** 再生が完了し、今回の実行が完了（[LearnGate]、[outcome]は解答提出後の判定結果）：成功回数+1、連続エラークリア。 */
    suspend fun noteResult(end: TaskState, outcome: RunOutcome) {
        val (route, out) = replayed ?: return
        if (out !is ReplayOutcome.Completed || !LearnGate.done(end, outcome)) return
        update(route.id) { it.copy(successes = it.successes + 1, consecutiveMisses = 0, missStep = null) }
    }

    private suspend fun update(id: String, change: (Route) -> Route) {
        val store = routes.store
        try {
            // 読み取り、変更、書き込みは一度に完了します。別の並列タスクが同時にこのルートを変更しても、カウントは失われません。
            withContext(Dispatchers.IO) { store.update(id, change) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ルート数を書き込むことができず、タスクには影響しません。
        }
    }

    companion object {
        /** 再生中に画面へ出すこのステップの説明。 */
        const val REPLAY_NOTE = "以前の方法で操作しています"
        /** 同じステップで連続してこの回数合わなければルートを無効にする。 */
        const val ROUTE_MISSES_TO_DISABLE = 2
    }
}
