package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.tool.ToolExecution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** ワンアクションで入場完了のエンディング。 */
sealed interface ActionOutcome {
    /** 実行前のガード結論。 */
    val before: List<GuardVerdict>

    /**
     * 実行されませんでした。[code] は guard_stopped / guard_warning / confirmation_required / confirmation_mismatch /
     * confirmation_declined / approval_unavailable / target_unresolved / own_app。
     * [content] は呼び出し元へ返す結果で、スクリプト・モデルの単一ステップと記録に使います。
     */
    data class Refused(
        val code: String, val content: JsonObject, override val before: List<GuardVerdict>, val hit: Irreversible? = null,
    ) : ActionOutcome

    /**
     * が実行されました。 [exec] は最終結果です (モデル ステップは、ユーザーの後続の結果または検証の戻りを待つことで置き換えられた可能性があります)。 [after] は実行後のガード結論です
     * (ページ スクリプトのサブステップの結論が最初にランク付けされます)。 [content]はモデルに引き継がれ走行記録に記録される内容です。
     * [shotId]、[picture]: [Frames.Caller] は、発信者が保存するために残された画面です。 [seen] は、ルートアンカーポイントを記録する際のアクション後に読み込まれる画面です（未読はnull）。
     */
    data class Ran(
        val exec: ToolExecution, override val before: List<GuardVerdict>, val after: List<GuardVerdict>, val content: JsonObject,
        val durationMs: Long, val shotId: Int? = null, val picture: ByteArray? = null, val seen: ScreenSnapshot? = null,
    ) : ActionOutcome {
        val verdicts: List<GuardVerdict> get() = before + after
    }

    /** がユーザーを待っている間にタスクがキャンセルされました。 */
    data class Cancelled(override val before: List<GuardVerdict>) : ActionOutcome
}

/** 呼び出し元に対するこの呼び出しの結果: 実行された場合は実行結果、実行されなかった場合は拒否ステートメントです。 */
fun ActionOutcome.execution(): ToolExecution = when (this) {
    is ActionOutcome.Ran -> exec
    is ActionOutcome.Refused -> ToolExecution(content)
    is ActionOutcome.Cancelled -> ToolExecution(buildJsonObject { put("ok", false); put("error", "cancelled"); put("executed", false) })
}

/** ソースに従って書かれた、停止時に呼び出し元に与えられる指示。 */
internal object Refusals {
    const val SINGLE_USE = "承認は次の一致する操作1回だけに有効で、その成否にかかわらず使い切られる。"
    const val OWN_APP = "own_app"
    const val PAYMENT_TAKEOVER = "支払いはユーザー本人が行います。request_confirmationは使わず、支払い画面を開いてhand_to_user(reason=payment)を呼んでください。" +
        "itemには商品（仕様・数量）、amountには画面上の支払金額、addressには配送先を指定してください。"

    fun content(code: String, style: RefusalStyle, before: List<GuardVerdict>, hit: Irreversible?, reason: String?): JsonObject = when (style) {
        // モデルがガードによって 1 ステップで停止されると、タスクは終了し、説明は不要です。
        RefusalStyle.Model -> if (code == "approval_unavailable") notRun(code,
            "この操作には支出が伴いますが、この実行ではユーザーの承認を受け付けられないため、実行していません。" +
                "この実行中は再試行せず、制約をユーザーへ説明してください。") {
            hit?.let { put("action", it.action.name); put("matched", it.matched) }
            put("counts_as_failure", false)
        } else if (hit == null) notRun(code, if (code == OWN_APP) Feedback["own_app"] else code) else {
            val blocked = if (reason != null) mismatch(hit, reason) else required(hit)
            if (before.isEmpty()) blocked else JsonObject(blocked + ("guard" to JsonArray(before.map { it.toJson() })))
        }
        RefusalStyle.Script -> {
            val message = when (code) {
                "guard_stopped" -> "Mikaの操作保護機能がタスクを停止しました。"
                "guard_warning" -> "Mikaの操作保護機能がこの操作に警告したため、実行していません。"
                "confirmation_required" -> "この操作には支出が伴います（${hit?.matched}）"
                "confirmation_mismatch" -> "この支払い操作（${hit?.matched}）はユーザーが承認した内容と一致しません（$reason）"
                "approval_unavailable" -> "この操作には支出が伴いますが、この実行ではユーザーの承認を受け付けられないため、実行していません"
                "target_unresolved" -> "そのrefは最新のスナップショットにありません。mika.snapshot()を再度呼んでください"
                OWN_APP -> Feedback["own_app"]
                else -> error("不明な停止理由: $code")
            }
            notRun(code, message) { hit?.let { put("action", it.action.name); put("matched", it.matched) } }
        }
        RefusalStyle.Code -> notRun(code, code)
    }

    /** 画面外の確認要求について、この実行では本人へ確認できない場合。 */
    fun unavailable(): JsonObject = buildJsonObject {
        put("ok", false); put("error", "approval_unavailable"); put("message", Feedback["approval.unavailable"])
        put("executed", false); put("recoverable", false); put("counts_as_failure", false)
    }

    private fun required(hit: Irreversible) = buildJsonObject {
        put("ok", false); put("error", "confirmation_required")
        put("action", hit.action.name); put("matched", hit.matched)
        put("message", if (hit.action == IrrevAction.pay) "支払いに当たるため、この操作は実行していません。$PAYMENT_TAKEOVER"
            else "この操作（${hit.action.name}）には支出が伴うため、実行していません。まずrequest_confirmationで支払先・購入内容・金額を示し、ユーザーの承認後に実行してください。$SINGLE_USE")
        put("recoverable", true); put("counts_as_failure", false)
    }

    private fun mismatch(hit: Irreversible, reason: String) = buildJsonObject {
        put("ok", false); put("error", "confirmation_mismatch")
        put("action", hit.action.name); put("matched", hit.matched); put("reason", reason)
        put("message", "この操作はユーザーが承認した内容と一致しません（$reason）。承認は無効となり、実行していません。" +
            "引き続き必要な場合は、request_confirmationを再度呼び、この操作の内容を正確に説明してください。ユーザーの承認後、直ちに実行してください。")
        put("recoverable", true); put("counts_as_failure", false)
    }

    private fun notRun(error: String, message: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("ok", false); put("error", error); put("message", message); put("executed", false)
        extra()
    }
}
