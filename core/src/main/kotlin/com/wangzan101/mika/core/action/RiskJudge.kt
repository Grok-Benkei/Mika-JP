package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.Verb

/**
 * 1 つのアクションのリスク。 [hit] は不可逆判定の結果です (判定なしまたはヒットなしは null)。 [screen] は判定の元となる画面ですので、使用する場合のみお読みください。
 * The tool itself can declare the risk and then replace the implementation.判断や承認を変更する必要はありません。
 */
fun interface RiskJudge {
    fun assess(verb: Verb?, hit: Irreversible?, screen: () -> ScreenSnapshot?): Risk
}

/** is classified according to the existing irreversible judgment and screen reading signal;お金がかかるかどうかはアプリではなく、アクションのカテゴリと画面上の金額で判断されます。 */
object ClassifiedRisk : RiskJudge {
    /** はインターフェース上の動作を変更します。他の書き込み操作 (戻る、スライド、アプリを開く、URL を開く) はインターフェイス間を移動するだけです。 */
    private val CHANGE_VERBS = setOf(Verb.Tap, Verb.TypeText, Verb.ClearText, Verb.PressKey, Verb.Upload, Verb.Interact)

    /** このアクションは、インターフェイス上の内容を変更します (インターフェイス間を移動するだけではありません)。これらのアクションによって、不可逆的な外部アクションも行われます。 */
    fun changesState(verb: Verb?): Boolean = verb in CHANGE_VERBS

    /** 画面上の通貨（￥、元、$）付きの金額：このとき、送信や削除などのアクションは金額としてカウントされます（決済ページの「確認」および「支払いに進む」）。メッセージの送信にはお金がかからないため、カウントされません。 */
    private val AMOUNT = Regex("[¥￥$＄]\\s*\\d|\\d+(\\.\\d+)?\\s*元")

    override fun assess(verb: Verb?, hit: Irreversible?, screen: () -> ScreenSnapshot?): Risk {
        val level = when (hit?.action) {
            null -> if (verb in CHANGE_VERBS) ActionRisk.Change else ActionRisk.Read
            IrrevAction.send, IrrevAction.submit -> ActionRisk.External
            IrrevAction.pay, IrrevAction.order, IrrevAction.delete -> ActionRisk.Irreversible
            IrrevAction.other -> ActionRisk.Unknown
        }
        if (hit == null) return Risk(level)
        val payment = hit.action == IrrevAction.pay || hit.action == IrrevAction.order ||
            hit.action != IrrevAction.send && screen()?.elements?.any { !it.editable && AMOUNT.containsMatchIn(it.name) } == true
        return Risk(level, payment)
    }

    /** アクションが該当する要素: 番号付きの要素を番号で検索し、フォーカスされた入力ボックスに入力し、座標をクリックして、その点を含む最小の要素を取得します。 */
    fun target(verb: Verb?, args: JsonObject, s: ScreenSnapshot): UiElement? {
        fun str(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull
        str("ref")?.let { ref -> return s.elements.firstOrNull { it.ref == ref } }
        if (verb == Verb.TypeText || verb == Verb.ClearText) return s.elements.firstOrNull { it.focused && it.editable }
        val x = str("x")?.toDoubleOrNull() ?: return null
        val y = str("y")?.toDoubleOrNull() ?: return null
        return s.elements.filter { e -> e.bounds.size == 4 && x >= e.bounds[0] && x <= e.bounds[2] && y >= e.bounds[1] && y <= e.bounds[3] }
            .minByOrNull { (it.bounds[2] - it.bounds[0]).toLong() * (it.bounds[3] - it.bounds[1]) }
    }
}
