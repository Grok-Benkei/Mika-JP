package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.verify.AnswerSource

/** モデルを使用せずに複数のステップを一度に完了する進行状況。タスク カードと通知には、それに応じて何が行われているかが示されます。 */
sealed interface RunProgress {
    /** は記憶されたルートに従い、現在ステップ [step]/[total] を実行しています。 */
    data class Replay(val step: Int, val total: Int) : RunProgress
    /** は、モデルによって指定された [steps] ステップ (do_steps) を一度に実行します。 */
    data class Batch(val steps: Int) : RunProgress
    /**
     * 別のタスクは [app] (インターフェイス上の名前) を使用しています。このステップで停止し、リリースされるまで待ちます (アプリを開くときにモデルが要求されます)。
     * は他のタスクを待っているだけで、ユーザーが何もする必要はありません。インターフェイス上で動作しているとみなされ、「処理する必要がある」とはみなされません。
     */
    data class WaitingForApp(val app: String) : RunProgress
}

sealed interface TaskState {
    /** エンディングには、回答、失敗、キャンセルのいくつかの種類があります。 */
    sealed interface Terminal : TaskState
    /** はまだ実行を開始していません。 */
    data object Idle : TaskState
    /**
     * [note] はモデルの最新のセンテンスです。単語がない場合は、最後に呼び出されたツールの名前です。
     * [tool] は、このステップで呼び出されるツールです。空の場合は、このステップがまだモデルの応答を待っていることを意味します (この時点では、[note] はまだ前のステップです)。
     * [progress] 空でない場合、このステップはモデルを経由せずに複数の連続したステップ (再生ルート、バッチ実行) で実行されます。
     * [reason] は、この呼び出しに対してモデルによって書き込まれた意図 (呼び出しパラメーター内の理由) です。書かれていない場合は null です。
     * [narration] は、この呼び出しで作成されたモデルです (回答ではなく、進行状況の説明)。最初にインターフェイスの「何をしていますか」で使用し、次に [reason] を使用します。
     * [preview] は、現在のモデル リクエストが流出した表示テキストです。最終的な返信なのか、ツールの進捗なのかは未確認です。
     */
    data class Running(
        val step: Int, val note: String, val tool: String? = null, val progress: RunProgress? = null, val reason: String? = null,
        val narration: String? = null,
        val preview: String? = null,
    ) : TaskState
    /** 現時点では機能の前提条件が満たされていないため、ここで停止して待ちます ([GateReason] を参照)。条件が満たされるか、ユーザーが「続行」をクリックすると、実行が再開されます。 */
    data class Held(val reason: GateReason) : TaskState
    /** は、[item]/[amount]/[address] の支払い時に引き継ぎカードに個別に記載されます。 [AgentLoop.resumeAfterTakeover] でエンディングを返します。 */
    data class WaitingTakeover(
        val reason: String, val message: String,
        val item: String? = null, val amount: String? = null, val address: String? = null,
    ) : TaskState
    data class WaitingInput(val prompt: String, val options: List<String>? = null) : TaskState
    /**
     * お金がかかる操作にユーザーが同意するまで待ちます (今回は同意することしかできません)。 [AgentLoop.answerConfirmation] で応答し、応答、キャンセル、またはタイムアウトになるまで待ちます (タイムアウト後に [同意しない] を押します)。
     */
    data class WaitingConfirmation(val request: ConfirmationRequest) : TaskState
    /**
     * モデルのツール呼び出しなしの応答は [answer] です (そのままユーザーに表示されます)。検証結果は 3 つあります。
     * [reviewed] は false です。これは、何もチェックされていないことを意味します (チャットと回答にはチェック可能な値がなく、モデルの検証は要求されません)。現時点では、[verified] も false であり、インターフェイスはマークされていません。
     * が検証され、すべてが一致する場合、[verified] は true になります。検証されたが検証できなかった場合、[verified] は false であり、[note] が理由です。
     * [unconfirmed] は、一度返しても一致しない箇所です（答えはまだ渡されており、これらの箇所は未確認とマークされています）。
     * [sources] は、答えをサポートする証拠台帳からコードが見つかったいくつかの読み取りです (モデルは根拠を記述しません)。
     * 物事（経験ルート、個人スキル、アプリの知識）を学び、判断された結末を見てください。ここにチェックを入れない場合は、[LearnGate] を参照してください。
     */
    data class Succeeded(
        val answer: String, val verified: Boolean = true, val note: String? = null,
        val sources: List<AnswerSource> = emptyList(), val unconfirmed: List<String> = emptyList(),
        val reviewed: Boolean = true,
    ) : Terminal {
        /** はチェックされましたが、検証に失敗しました (インターフェイス上でマークされ、評価では未検証として記録されます)。 */
        val doubtful: Boolean get() = reviewed && !verified
    }
    /** [kind] は、障害自体を生成したコードによって与えられます。[reason] のテキストから推測しないでください。 */
    data class Failed(val reason: String, val kind: FailKind) : Terminal
    data object Cancelled : Terminal
}

/**
 * 停止して待機する理由: この前提条件を与える能力に依存します (コアはそれが具体的に何であるかを知りません)。 [code] は、実行レコード内の状態イベントの名前です。
 * [why] はユーザーの説明です (空にすることもできます)。 [resumable] が true の場合、ユーザーは「続行」をクリックできます。それ以外の場合は、条件が満たされた後に自動的に続行されます。
 * [needsYou] が true の場合、ユーザーは続行する前に電話機上で手を動かす必要があります (インターフェイスは、条件を待つだけでなく、「処理を要求する」ものとしてカウントされます)。
 */
data class GateReason(val code: String, val why: String = "", val resumable: Boolean = false, val needsYou: Boolean = false) {
    /** はまだ開始されておらず、開始キュー内にあります (並列プロセスがいっぱいで、同じ会話にすでにタスクがある)。順番が来ると自動的に開始されます。キューに入れられないようにするには、[続行] をクリックします。 */
    val queued: Boolean get() = code == QUEUED

    companion object {
        const val QUEUED = "queued"

        /** は開始をキューに入れます。 [why] はユーザーに対する文です (たとえば、これからいくつかのタスクがあります)。 */
        fun queued(why: String) = GateReason(QUEUED, why)
    }
}

/** タスク失敗の原因。 */
enum class FailKind {
    /** エージェントは end_not_done を呼び出しますが、正直、これは実行できません。 */
    GaveUp,
    /** モデルの呼び出し時にエラーが発生したか、例外が返されたか、操作が停止されましたが結果が得られませんでした。 */
    Model,
    /** 動作保護トリガー、安全停止。 */
    Guard,
    StepLimit,
    /** 動作環境が不十分、またはエラーが発生しています（携帯電話の操作アシスタント、アクセシビリティ、モデルなし、仮想画面の切断など）。 */
    Environment,
    /**
     * は受け入れられ、それを実行したタスク サービスまたはプロセスが途中で消えました (システムによって停止され、プロセスが強制終了されました)。
     * スコアは、実行環境のエラーと同様に、エージェントのせいではありません。学習内容やスキルのトライアルは失敗とみなされません ([GaveUp] と [StepLimit] のみがカウントされます)。
     */
    Interrupted,
}

