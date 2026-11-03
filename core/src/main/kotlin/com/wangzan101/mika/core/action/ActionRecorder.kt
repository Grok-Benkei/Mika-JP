package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.wangzan101.mika.core.tool.Verb

/**
 * アクション実行記録: ツールの結果、確認レベル、および画面。スクリーンショット番号はここに一律に送信されます (ループ時に保存するスクリーンショットにもここに番号が付けられます)。
 * 記録の失敗はタスクには影響しません。 [saveScreen]がnullの場合、サブステップ画面は保存されません(番号は送信されません)。
 */
class ActionRecorder(
    private val sink: RunSink,
    private val clock: () -> Long = System::currentTimeMillis,
    private val saveScreen: ((Int, ByteArray) -> Unit)? = null,
) {
    private var shots = 0

    val savesScreens: Boolean get() = saveScreen != null

    fun now(): Long = clock()

    fun nextShot(): Int = ++shots

    fun event(e: RunEvent) {
        try {
            sink.event(e)
        } catch (x: CancellationException) {
            throw x
        } catch (x: Throwable) {
            // 記録の失敗はタスクに影響しません
        }
    }

    fun confirmation(hit: Irreversible, decision: String) =
        event(RunEvent.Confirmation(clock(), hit.action.name, RunEvent.clip(Redactor.text(hit.matched)), null, null, decision))

    /**
     * 状態を変更した最後のアクション (インターフェイス上での変更、外部操作、不可逆操作、またはサンドボックスでの書き込み操作。失敗が報告され、インターフェイスの変更は報告されません) が実行される前の証拠台帳の場所。 それ以来、
     * は再度読み取られていません。回答を送信する前にもう一度確認してください (AnswerReview を参照)。 null に対するそのようなアクションはまだありません。
     */
    var lastChangeMark: Int? = null
        private set

    fun noteChange(ledgerMark: Int) { lastChangeMark = ledgerMark }

    /** このタスクでは、いくつかの外部アクションまたは取り消し不能なアクション (送信、送信、注文、削除など) が可能です。回答を送信する前に、これに基づいてモデルをチェックするかどうかを決定してください。 */
    var outwardActs = 0
        private set

    fun approval(origin: Origin, tool: String, hit: Irreversible, risk: Risk, decision: String) {
        if (decision == "allowed" && risk.level.outward) outwardActs++
        event(RunEvent.Approval(clock(), origin.step, origin.policy.source, tool, hit.action.name, RunEvent.clip(Redactor.text(hit.matched)),
            risk.level.name, risk.payment, decision))
    }

    /** は呼び出しの結果です。 [content] はモデル (または呼び出し元) に渡されるもので、[extra] は走行記録のみを入力するフィールドです。 */
    fun result(
        step: Int, tool: String, verb: Verb?, content: JsonObject, extra: JsonObject, changed: Boolean?, shotId: Int?, durationMs: Long,
        source: String, callId: String, waitMs: Long = 0,
    ) {
        // ユーザーの回答は検証コードである可能性があり、実行記録には入力されません。
        val shown = if (verb == Verb.AskUser && content.containsKey("answer")) JsonObject(content + ("answer" to JsonPrimitive(HIDDEN_ANSWER))) else content
        val recorded = Redactor.result(verb, shown).let { if (extra.isEmpty()) it else JsonObject(it + extra) }
        event(RunEvent.ToolResult(clock(), step, tool, RunEvent.clipResult(Redactor.text(recorded.toString())), changed, shotId, durationMs,
            waitMs = waitMs, source = source, callId = callId))
    }

    /** サブステップ画面をその場で保存し、スクリーンショット番号を返します。画面が保存されていない場合は null になります。 */
    fun saveFrame(png: ByteArray): Int? {
        val save = saveScreen ?: return null
        val id = nextShot()
        try {
            save(id, png)
        } catch (x: CancellationException) {
            throw x
        } catch (x: Throwable) {
            // 同上
        }
        return id
    }

    private companion object {
        const val HIDDEN_ANSWER = "（ユーザーの回答は記録しません）"
    }
}
