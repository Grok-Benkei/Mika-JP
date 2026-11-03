package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.agent.ReplyLanguage
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.FAILURE_SUBTYPES
import com.wangzan101.mika.core.tool.Outcome
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.TAKEOVER_REASONS
import com.wangzan101.mika.core.tool.TAKEOVER_REASON_ALIASES
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.bool
import com.wangzan101.mika.core.tool.capField
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** は作成できません。ユーザーに問い合わせるか、ユーザーにツールを提供してください。結果として [Outcome] がループに入ります。 */
internal object EndNotDone : AgentTool {
    override val verb = Verb.EndNotDone
    override val label = "できなかった理由を説明"
    override val risk = Risk.Read
    override val def = ToolDef("end_not_done",
        Prompts.tool("end_not_done"),
        mapOf("message" to str("具体的な障害、完了したこと・未完了のこと、現在の画面の状態を日本語で説明します。"),
            "subtype" to str("失敗の種類（既定値はinteraction_failed）。", FAILURE_SUBTYPES),
            "recoverable" to bool("再試行で成功する可能性があるか（既定値はfalse）。")),
        listOf("message"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val m = japanese(a.str("message", max = 8000)!!, "message")
        val st = a.str("subtype") ?: "interaction_failed"
        if (st !in FAILURE_SUBTYPES) throw ArgError("subtype", "次のいずれかを指定してください: ${FAILURE_SUBTYPES.joinToString("|")}")
        val rec = a.bool("recoverable", false)
        return ToolExecution(buildJsonObject {
            put("ok", false); put("reported", true); put("subtype", st); put("recoverable", rec); put("counts_as_failure", false)
        }, outcome = Outcome.Failure(m, st, rec))
    }
}

internal object AskUser : AgentTool {
    override val verb = Verb.AskUser
    override val label = "質問する"
    override val risk = Risk.Read
    override val def = ToolDef("ask_user",
        Prompts.tool("ask_user"),
        mapOf("prompt" to str("ユーザーに表示する質問を日本語で指定します。"),
            "options" to buildJsonObject {
                put("type", "array"); put("description", "回答の選択肢を2〜8件指定できます。説明は日本語で書き、固有名詞は保持します。"); put("minItems", 2); put("maxItems", 8)
                put("items", buildJsonObject { put("type", "string") })
            }),
        listOf("prompt"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val q = japanese(a.str("prompt", max = 1000)!!.trim(), "prompt")
        val opts = a.strList("options", 2, 8)?.map { japanese(it.trim(), "options") }?.distinct()
        if (opts != null && opts.size < 2) throw ArgError("options", "異なる選択肢を2件以上指定してください")
        return ToolExecution(okObj { put("asked", true) }, outcome = Outcome.Input(q, opts))
    }
}

/** は、画面の操作をユーザーに任せます。ロック画面レベルを通過する必要があります (ユーザーが画面上で操作する必要があります)。 */
internal object HandToUser : SurfaceAware {
    override val verb = Verb.HandToUser
    override val label = "引き継ぎをお願いする"
    override val risk = Risk.Write
    override val withScreens = true
    override val def = ToolDef("hand_to_user",
        Prompts.tool("hand_to_user"),
        mapOf("reason" to str("ユーザーの操作が必要な理由。", TAKEOVER_REASONS),
            "message" to str("ユーザーが行うことと停止した場所を日本語で説明します。支払い時は商品・仕様・数量・画面上の金額・住所を含めます。"),
            "item" to str("支払い時の商品・仕様・数量を画面の表記どおりに指定します。"),
            "amount" to str("支払金額と通貨を画面の表記どおりに指定します。"),
            "address" to str("配送先の住所が表示されている場合、その表記どおりに指定します。")),
        listOf("reason", "message"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        checkSurface(a, ctx)
        val raw = a.str("reason")!!
        val r = TAKEOVER_REASON_ALIASES[raw] ?: raw
        if (r !in TAKEOVER_REASONS) throw ArgError("reason", "次のいずれかを指定してください: ${TAKEOVER_REASONS.joinToString("|")}")
        val m = japanese(a.str("message", max = 1000)!!, "message")
        // 支払い: モデルに含まれていない品目については、以前に拒否された支払い申告を使用します。
        val declared = ctx.refusedPay.takeIf { r == "payment" }
        // は次回の支払いにのみ使用され、その後の無関係な支払いには使用されません。
        if (declared != null) ctx.refusedPay = null
        val item = a.str("item", max = 10000)?.let(::capField) ?: declared?.content
        val amount = a.str("amount", max = 10000)?.let(::capField) ?: declared?.amount
        val address = a.str("address", max = 10000)?.let(::capField)
        return ToolExecution(okObj { put("handedOver", true) }, outcome = Outcome.Takeover(r, m, item, amount, address))
    }
}

/** ユーザー向けの説明が外国語なら表示前に差し戻す。商品名・住所などの画面データは対象にしない。 */
private fun japanese(text: String, field: String): String {
    if (ReplyLanguage.mismatch(text) != null) throw ArgError(field, "ユーザー向けの説明を日本語で書き直してください。引用・コード・固有名詞は元の表記を保持してください")
    return text
}
