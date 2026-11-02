package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.guard.Severity
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** スタックしたリマインダーの計算: 警備員が「スタックした」リマインダーを報告した回数。各場所 (アプリまたは Web サイト) で各段落が正常に入力された回数。 */
internal data class StuckState(val warnings: Int = 0, val typed: Map<String, Int> = emptyMap())

/**
 * アクション (シングル ステップ、またはバッチ内のサブステップ) がスタック リマインダーに引き渡されるという事実: ガードの結論 (アクション + ターゲットが繰り返され、移動後に画面が変化せず、ページが変化せずに繰り返し読み取られる)、
 * 今回はプレーン テキストが正常に入力された場所 ([StuckAdvice.typedText]、入力されなかったか、パスワード ボックスが null でした)。
 */
internal data class StuckInput(val verdicts: List<GuardVerdict>, val typed: String?, val place: String?, val progressed: Boolean = false)

/**
 * 行き詰まったときにモデルに与えられるアドバイス (ツール結果のアドバイス フィールドに入力): 行き詰まったクラスは、提案を 2 回検索した後に諦めるように促します。
 * 同じ段落を同じ場所に再度入力したとき（同じ内容を別の単語で繰り返し検索したため、ガード ウィンドウでは認識できませんでした）、すでに数回入力したと言いました。
 * バッチ内のシングル ステップとサブステップは同じセットで実行されます。純粋な機能。
 */
internal object StuckAdvice {
    private val STUCK_CODES = setOf("no_progress", "no_new_state", "read_loop")

    /** 提供されるアカウンティングと提案を返します (そうでない場合は null)。 */
    fun next(s: StuckState, input: StuckInput): Pair<StuckState, String?> {
        val warnings = (if (input.progressed) 0 else s.warnings) + input.verdicts.count { it.severity == Severity.Warn && it.code in STUCK_CODES }
        var typed = if (input.progressed) emptyMap() else s.typed
        // 「現在の場所 + テキスト」に従ってカウント: 他のアプリで同じ単語を検索しても重複としてカウントされません
        val again = input.typed?.let { t ->
            val key = "${input.place}\u0000$t"
            val n = (typed[key] ?: 0) + 1
            typed = typed + (key to n)
            if (n >= 2 && input.verdicts.any { it.code in STUCK_CODES })
                Feedback.format("repeated_input_advice", "text" to t, "times" to n, "answer_or_failure" to ANSWER_OR_FAILURE) else null
        }
        val advice = if (warnings >= 2 && input.verdicts.any { it.code in STUCK_CODES })
            Feedback.format("give_up_advice", "answer_or_failure" to ANSWER_OR_FAILURE) else again
        return StuckState(warnings, typed) to advice
    }

    /**
     * 今回はテキストが正常に入力されました (先頭と末尾の空白は削除されます。空の空白はカウントされません)。入力されていない場合、成功していない場合、またはポートが「パスワード ボックスではない」と明確に示していない場合は、null になります。
     * 元のテキストがプロンプトに含まれ、ツールの結果が実行記録に入力されます。
     */
    fun typedText(verb: Verb?, args: JsonObject, content: JsonObject?): String? {
        if (verb != Verb.TypeText || content?.get("ok") != JsonPrimitive(true) || content["secret"] != JsonPrimitive(false)) return null
        return (args["text"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
