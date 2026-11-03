package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.cap.phone.OperatingPace

/**
 * 評価ランナー用フック。テストでのみ設定されます。すべてのデフォルト値は実稼働コードで使用され、動作は変わりません。
 * はプロセスのコピー ([TaskEnvironment] を参照) であり、同時に実行されている複数のタスクによって共有されます (自動応答は質問自体のみを参照し、どのタスクでも同じです)。
 */
class EvalHooks {
    /** ask_user への自動応答。応答がないことを示すには null または空白を返し、タスクは評価がタイムアウトになるまで待機します。 */
    @Volatile var autoAnswer: ((prompt: String, options: List<String>?) -> String?)? = null

    /** 評価ランナーがテイクオーバーに遭遇したときに行うべきこと: キャンセル (タスクをキャンセルし、テイクオーバーとして記録される) または待機 (誰かがそれを処理するのを待つ)。実行中のマシンで読み取ります。 */
    @Volatile var onTakeover: String = "cancel"

    /** 1 つの質問のステップ数の上限。 null はエージェントのデフォルト値を使用します。 */
    @Volatile var maxSteps: Int? = null

    /**
     * はレベルの自動応答を確認します。空でない場合、ユーザーを待たずにその結果を直接使用します (評価では常に false になります。実際に送信、削除、注文することはありません)。
     * レベルは通常どおりイベントを記録し、カードの内容はレビューと検証のために残されます。
     */
    @Volatile var autoDecideConfirmation: ((ConfirmationRequest) -> Boolean)? = null

    /** が作成したスキルドラフトをそのまま受け付けます（評価用補助輪）。 */
    @Volatile var autoApproveSkillDrafts: Boolean = false

    /** は、「完了したタスクからスキルを学習する」スイッチをオーバーライドします。 null 設定を使用します。デフォルトでは評価はオフになっており (EvalRunTest は false に設定されています)、補助輪のみがオンになっています。 */
    @Volatile var learnSkills: Boolean? = null

    /** 「言ったことを自動的に記憶する」スイッチをオーバーライドします。 null 設定のものを使用します。デフォルトでは評価はオフになっており、記憶の質問セットのみがオンになっています。 */
    @Volatile var rememberMemory: Boolean? = null

    /** は設定の動作リズムを上書きします。 null は設定のものを使用します。デフォルトでは、評価および実機受け入れに「高速」が使用されますが、パラメータに応じて別のものに変更できます。 */
    @Volatile var operatingPace: OperatingPace? = null

    fun reset() {
        operatingPace = null
        autoApproveSkillDrafts = false
        learnSkills = null
        rememberMemory = null
        autoAnswer = null
        onTakeover = "cancel"
        maxSteps = null
        autoDecideConfirmation = null
    }

    internal fun answerFor(state: TaskState): String? {
        val s = state as? TaskState.WaitingInput ?: return null
        return autoAnswer?.invoke(s.prompt, s.options)?.trim()?.takeIf { it.isNotEmpty() }
    }

    internal fun decisionFor(state: TaskState): Boolean? {
        val s = state as? TaskState.WaitingConfirmation ?: return null
        return autoDecideConfirmation?.invoke(s.request)
    }
}
