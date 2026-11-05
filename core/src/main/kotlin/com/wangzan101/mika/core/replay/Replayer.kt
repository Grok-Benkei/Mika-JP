package com.wangzan101.mika.core.replay

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionOutcome
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.interfaces
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.hypot
import com.wangzan101.mika.core.tool.Verb

sealed interface ReplayOutcome {
    /** 完了した (または検証された) ステップの数。 */
    val done: Int

    data class Completed(override val done: Int) : ReplayOutcome
    /**
     * [executed]: ステップ完了+1 が実際に実行されます (インターフェイスが移動された可能性があります)。実行前に拒否されたもの (パラメータ、リンク、期限切れの番号、占有されているアプリなど) は false です。
     * [error] は、そのステップでのツール結果のエラー コードであり、返されたときにモデルに報告されます。
     */
    data class Diverged(
        override val done: Int, val expected: String, val seen: String, val executed: Boolean = false, val error: String? = null,
    ) : ReplayOutcome
    /** 次のステップは不可逆的な操作 (または不明確) です。その前に停止し、モデルを戻して通常の確認プロセスに従います。 */
    data class StoppedForConfirmation(override val done: Int) : ReplayOutcome
}

/**
 * モデルを調整せずに、ルートを段階的にたどってください。各ステップ: ロック画面レベル → 画面読み取り → ライト検証 (フロントパッケージ名、アクション前のアンカーポイント、セレクター固有のヒット) →
 * は [ActionExecutor] によって実行されます (ガードのプロンプトが表示されたらすぐに停止します。元に戻せない場合や不明確な場合は停止します。再生ではユーザーが同意したとはみなされません) → アクション後の変更を確認します。
 * 一致しない部分がある場合は、モデルを停止して返却します。入力される言葉はルート内の定型文と原作のスロット値のみとなります。
 */
class Replayer(
    private val tools: ToolDispatcher,
    private val executor: ActionExecutor,
    private val gate: suspend () -> Unit,
    private val sink: RunSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var seq = 0

    suspend fun run(choice: RouteChoice): ReplayOutcome {
        val steps = choice.route.steps
        val total = steps.size
        fun event(i: Int, matched: Boolean, detail: String) =
            emit(RunEvent.Replay(clock(), choice.route.id, i + 1, total, matched, Redactor.text(detail)))

        for ((i, step) in steps.withIndex()) {
            fun diverged(why: String, seen: String, executed: Boolean = false, error: String? = null): ReplayOutcome {
                event(i, false, why)
                return ReplayOutcome.Diverged(i, describe(step, choice.slots) + " ($why)", seen, executed, error)
            }
            val verb = tools.index[step.tool]?.takeIf { it.routable }?.verb ?: return diverged("not a replayable action", "")
            // 画面がロックされたり奪われたりしたら、まず待ちます。待機後に画面を読み込み、待機後に画面上で確認して実行（2回目の実行入力を待つ必要はありません）
            gate()
            val snap = read() ?: return diverged("the screen could not be read", "")
            if (snap.protected) return diverged("the page is protected", PROTECTED_SEEN)
            val seen = summary(snap)
            step.before.packageName?.let { if (snap.packageName != it) return diverged("the foreground app is not $it", seen) }
            val names = snap.elements.map { it.name.trim() }.toSet()
            val need = minOf(2, step.before.texts.size)
            if (step.before.texts.count { it in names } < need) return diverged("the page before the action does not match", seen)

            val args = build(step, choice.slots) ?: return diverged("the slots in the arguments cannot be filled", seen)
            val target = step.target?.let { sel ->
                find(snap, sel, choice.slots) ?: return diverged("the target element is not unique or not found", seen)
            }
            if (verb == Verb.Tap && target == null) return diverged("the tap has no target to check", seen)
            // 参考資料に残っているリンクのみ公開します。情報が変更されると、ルートに記録されているリンクは公開されなくなります。
            if (verb == Verb.OpenLink) {
                fun arg(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull
                if (!tools.linkAllowed(arg("action"), arg("uri"), arg("package"))) return diverged("the link is no longer in the reference material", seen)
            }
            val call = target?.let { JsonObject(args + mapOf("ref" to JsonPrimitive(it.ref), "snapshotId" to JsonPrimitive(snap.snapshotId))) } ?: args

            val id = "replay-${++seq}"
            val out = when (val o = executor.run(Part.ToolCall(id, step.tool, call.toString()), Origin.Replay)) {
                is ActionOutcome.Ran -> o
                is ActionOutcome.Refused -> when (o.code) {
                    "guard_stopped", "guard_warning" -> return diverged("the action guard raised a warning", seen)
                    "confirmation_required", "confirmation_mismatch" -> {
                        event(i, false, "irreversible action, handed back to the model for confirmation")
                        return ReplayOutcome.StoppedForConfirmation(i)
                    }
                    "target_unresolved" -> return diverged("cannot tell which element the tap would hit", seen)
                    "own_app" -> return diverged("it acts on Mika itself", seen)
                    // 承認を得られない実行元の決済ステップは待機せずモデルへ戻し、未完了の理由を利用者へ説明します。
                    "approval_unavailable" -> return diverged(
                        "この実行では支払い操作の承認を取得できません", seen,
                        error = "approval_unavailable",
                    )
                    else -> error("unexpected refusal ${o.code}")
                }
                is ActionOutcome.Cancelled -> return diverged("cancelled", seen)
            }
            val exec = out.exec
            // は、アクションの後に 1 つの画面のみを読み取ります。これは、ルートのアンカー ポイントと「期待されるテキストが表示されるかどうか」に使用されます。
            val fresh = out.seen ?: tools.interfaces.peek()
            val effective = effectiveWrite(verb, exec.content)
            val now = fresh?.let { if (it.protected) PROTECTED_SEEN else summary(it) } ?: seen
            if (exec.outcome != null || exec.content["ok"] != JsonPrimitive(true)) {
                val error = (exec.content["error"] as? JsonPrimitive)?.contentOrNull
                return diverged("the action failed" + error?.let { ": $it" }.orEmpty(), now, executed = exec.outcome != null || error !in NOT_EXECUTED, error = error)
            }
            val shown = fresh?.elements?.map { it.name.trim() }?.toSet().orEmpty()
            val landed = exec.content["changed"] == JsonPrimitive(true) || exec.content["verification"] == JsonPrimitive("match") ||
                (effective && verb in OPENS) ||
                step.after.texts.any { it in shown }
            if (!landed) return diverged("no expected change after the action", now, executed = true)
            event(i, true, describe(step, choice.slots))
        }
        return ReplayOutcome.Completed(total)
    }

    private suspend fun read(): ScreenSnapshot? = try {
        // 現時点でルートが存在する画面（最新のアクションのある画面）を読み取ります。
        val dump = tools.index.nameOf(Verb.ListElements, tools.surface)!!
        val r = tools.executeRead(Part.ToolCall("replay-read-${++seq}", dump, "{}"), 0)
        (r.content["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let { tools.interfaces.snapshotFor(tools.declared(dump), JsonObject(mapOf("snapshotId" to JsonPrimitive(it)))) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** は番号を削除します。各文字列パラメータの `{n}` はスロット値に置き換えられます。スロット番号が範囲外であるか、非文字列パラメータにスロットが含まれているため、null になります。 */
    private fun build(step: RouteStep, slots: List<String>): JsonObject? {
        val o = try { Json.parseToJsonElement(step.argsJson) as? JsonObject } catch (e: Exception) { null } ?: return null
        val out = mutableMapOf<String, JsonElement>()
        for ((k, v) in o) {
            if (k == "ref" || k == "snapshotId" || k == "target") continue
            out[k] = when {
                v is JsonPrimitive && v.isString -> JsonPrimitive(fill(v.content, slots) ?: return null)
                SLOT.containsMatchIn(v.toString()) -> return null
                else -> v
            }
        }
        return JsonObject(out)
    }

    /**
     * セレクターを押して、現在の画面で唯一のターゲットを見つけます。名前が空の場合は、名前に置き換えられません。 `{n}` は最初にスロット値に置き換えられます。 記録されたときは
     * のみでしたが、現在は複数存在します。ページはもはや当時のものではない (たとえば、リスト内に同じ名前のエントリがさらに存在する) ため、認識されません。
     * は記録時に一意ではありません。同じシリアル番号を持つものは、記録時の位置が画面幅の 15% を超えない場合にのみ認識されます。
     */
    private fun find(s: ScreenSnapshot, sel: Selector, slots: List<String>): UiElement? {
        val name = fill(sel.name, slots) ?: return null
        val same = s.elements.filter { it.role == sel.role && it.resourceId == sel.resourceId && (name.isEmpty() || it.name.trim() == name) }
        if (same.size == 1) return same.single()
        if (same.isEmpty() || sel.uniqueAtRecord || sel.bounds.size != 4) return null
        fun near(e: UiElement) = e.bounds.size == 4 && hypot(cx(e.bounds) - cx(sel.bounds), cy(e.bounds) - cy(sel.bounds)) <= NEAR
        val pick = same.getOrNull(sel.ordinal)?.takeIf(::near) ?: return null
        return pick.takeIf { same.count(::near) == 1 }
    }

    private fun cx(b: List<Int>) = (b[0] + b[2]) / 2.0
    private fun cy(b: List<Int>) = (b[1] + b[3]) / 2.0

    private fun summary(s: ScreenSnapshot): String =
        "${s.packageName ?: "unknown app"}: " + s.elements.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct().take(8).joinToString(", ")

    private fun emit(e: RunEvent) {
        try { sink.event(e) } catch (x: CancellationException) { throw x } catch (x: Exception) { /* 録画失敗は再生に影響しない */ }
    }

    companion object {
        /** これらのエラーはアクションが実行される前に拒否され、インターフェイスは操作されませんでした。 */
        private val NOT_EXECUTED = setOf(
            "invalid_arguments", "unknown_tool", "invalid_link", "link_unresolved", "ref_expired", "app_in_use_by_other_task",
            "app_wait_would_deadlock", "app_wait_timed_out",
            "app_in_use_by_user", "app_not_found", "no_app_open", "confirmation_required", "batch_stopped",
        )

        /** 画面幅 15% (0 ～ 999 座標)。 */
        private const val NEAR = 150.0
        private const val PROTECTED_SEEN = "a protected page"
        private val SLOT = Regex("\\{(\\d+)\\}")

        /** は、`{n}` を n 番目のスロット値に置き換えます。スロット番号が存在しない場合は、null になります。 */
        internal fun fill(s: String, slots: List<String>): String? {
            var bad = false
            val out = SLOT.replace(s) { m -> slots.getOrNull(m.groupValues[1].toInt() - 1) ?: run { bad = true; "" } }
            return out.takeUnless { bad }
        }

        /** モデルを表示するためのワンステップ手順。 */
        fun describe(step: RouteStep, slots: List<String>): String {
            val o = try { Json.parseToJsonElement(step.argsJson) as? JsonObject } catch (e: Exception) { null }
            val what = listOfNotNull(
                step.target?.name?.let { fill(it, slots) }?.takeIf { it.isNotEmpty() }?.let { "「$it」" },
                (o?.get("text") as? JsonPrimitive)?.contentOrNull?.let { fill(it, slots) }?.let { "type 「$it」" },
                (o?.get("package") as? JsonPrimitive)?.contentOrNull,
                (o?.get("direction") as? JsonPrimitive)?.contentOrNull,
                (o?.get("key") as? JsonPrimitive)?.contentOrNull,
            ).joinToString(" ")
            return if (what.isEmpty()) step.tool else "${step.tool} $what"
        }
    }
}
