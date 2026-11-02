package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.tool.Verb

/** 次に使用するモデルを決定するときは、前のステップを参照してください。 */
data class StepContext(
    val step: Int,
    val lastOk: Boolean,
    val lastHadGuard: Boolean,
    /** 前のステップの後、画面は変化しません。画面読み取りで時間が報告されない場合は、null になります。 */
    val lastChanged: Boolean?,
    /** 再生復帰、一括停止、検証復帰、引き継ぎまたは質問回復。 */
    val lastWasRecovery: Boolean,
    val sameAppAsLast: Boolean,
    /** 送信されるコンテキスト内にスクリーンショットがあります。写真のない軽量モデルはお受けできません。 */
    val hasImages: Boolean = false,
)

/**
 * 実験用スイッチ ライトステップ分業: 簡単なステップは軽量モデルに与えられ、残りはメイン モデルに与えられます。
 * 軽量モデルが設定されておらず、ツール呼び出しをサポートしていない場合は、常にメイン モデルが使用されます。 1 つのステップでエラーが発生した場合、ガード プロンプトが表示されるか、画面が変化しない場合、次のステップ [TROUBLE_STEPS] はマスター モデルを強制します。
 */
class ModelRouter(private val main: ChatModel, private val light: ChatModel?) {
    private var forcedMain = 0
    private var disabled = false

    /** 軽量モデルが携帯電話のステップを受信できるかどうか。そうしないと、lightSteps は有効になりません。 */
    val active: Boolean get() = !disabled && light != null && light.capabilities.toolCalls

    /** 軽量モデルにエラーがあり、再試行しても役に立ちません (キー、クォータ、モデル名、リクエストが間違っています)。残りはメイン モデルが使用されます。ルーターは 1 つのタスクのみを実行します。 */
    fun disable() { disabled = true }

    fun pick(ctx: StepContext): ChatModel {
        if (!ctx.lastOk || ctx.lastHadGuard || ctx.lastChanged == false) noteTrouble()
        if (forcedMain > 0) {
            forcedMain--
            return main
        }
        val l = light?.takeIf { active } ?: return main
        val simple = ctx.step > 1 && !ctx.lastWasRecovery && ctx.sameAppAsLast && (!ctx.hasImages || l.capabilities.vision)
        return if (simple) l else main
    }

    /** で問題が発生しました (軽量モデルの呼び出しが失敗し、応答がメイン モデルにリダイレクトされたことを含む): その場合、[TROUBLE_STEPS] はメイン モデルを使用します。 */
    fun noteTrouble() { forcedMain = TROUBLE_STEPS }

    fun isLight(m: ChatModel): Boolean = light != null && m === light

    companion object {
        const val TROUBLE_STEPS = 2
        /** これらの呼び出し (および do_steps) は、軽量モデルが指定されている場合には実行されません。このステップはメイン モデルに変更されます。放棄、確認、引き継ぎ、チャレンジ、およびサンドボックス コマンドはすべてメイン モデルによって決定されます。 */
        private val MAIN_ONLY_VERBS = setOf(Verb.EndNotDone, Verb.RequestConfirmation, Verb.HandToUser, Verb.AskUser, Verb.HandleObstacle,
            Verb.PhoneRun, Verb.Collect, Verb.PhoneFind, Verb.PhoneDialog, Verb.PhoneGesture, Verb.PhoneCommand) +
            // サンドボックスはモデルによって書かれたコマンドを実行します: メイン モデルのみが書き込みを許可されます
            setOf(Verb.RunCommand, Verb.RunBundledScript, Verb.ReadFile, Verb.WriteFile, Verb.SearchFiles, Verb.ShareFile)
        /** はメイン モデルによってのみ呼び出すことができます ([name] はツール名、[verb] はツールが実行するアクションです)。 */
        fun mainOnly(name: String, verb: Verb?): Boolean = name == DO_STEPS_TOOL || verb in MAIN_ONLY_VERBS

        /** これらのアクションの後の 1 ステップは、回復 (ユーザーの待機) とみなされます。 */
        internal val RECOVERY_VERBS = setOf(Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation)
        /** これらのアクションにより、フロントエンド アプリまたは Web ページが変更されます (URL を開いてタブを変更します)。後続のステップは同じ場所とはみなされません。 */
        internal val APP_SWITCH_VERBS = setOf(Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.ShowAlarms, Verb.AddCalendarEvent, Verb.Navigate, Verb.Tabs)
    }
}

/** AgentLoop の使用: 各ステップの状況を書き留め、次のステップ [StepContext] を詳しく説明します。 */
internal class StepTrack {
    private var ok = true
    private var guard = false
    private var changed: Boolean? = null
    private var recovery = false
    private var app: String? = null
    private var appBefore: String? = null
    private var switched = false

    fun context(step: Int, hasImages: Boolean) =
        StepContext(step, ok, guard, changed, recovery, !switched && app != null && app == appBefore, hasImages)

    /** [app] は null です。これは、このステップの結果がフロントエンド アプリに報告されず、前の結果が使用されることを意味します。 */
    /** は、再生復帰とバッチ停止の状況から回復しました。次のステップは、回復手順に従うことです。 [after] の後に呼び出されます。 */
    fun markRecovery() { recovery = true }

    fun after(verb: Verb?, ok: Boolean, guard: Boolean, changed: Boolean?, recovery: Boolean, app: String?) {
        this.ok = ok
        this.guard = guard
        this.changed = changed
        this.recovery = recovery || verb in ModelRouter.RECOVERY_VERBS
        appBefore = this.app
        if (app != null) this.app = app
        switched = verb in ModelRouter.APP_SWITCH_VERBS
    }
}
