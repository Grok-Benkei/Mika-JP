package com.wangzan101.mika.core.action

/** サブステップ画面の保存方法：呼び出し元に任せます（モデルステップは独自の順序で保存されます）。インターフェイスが変更されたときにその場で保存するか保存しません。 */
enum class Frames { Caller, WhenChanged, None }

/** 停止時の指示は誰が書くべきか：モデル（中国語の指示、確認処理）、ページスクリプト（スクリプトで取得したエラー）、コードに応じた呼び出し元の指示。 */
enum class RefusalStyle { Model, Script, Code }

/**
 * ここでは、1 つのソースが他のソースと意図的に異なる箇所のみが書かれています。実行シーケンス自体はすべてのソースで同じです。
 * [source] は、実行レコードのソースです。
 */
data class OriginPolicy(
    val source: String,
    /** はユーザーの同意を得て支払いを解除できます。他のソースは、支払いが発生すると停止し、ユーザーの同意を前提とすることなくモデルを返します。 */
    val usesGrant: Boolean,
    /** は、レベルを確認するために一致していない場合でも不可逆的であるとみなされます。レベルに関係なく、サブステップがヒットすると停止します。 */
    val judgeWithoutGate: Boolean,
    /** ポイントが存在する要素が見つかりません: true は実行なしを意味します。 false は、許可されるが痕跡は残ることを意味します。 */
    val refuseUnresolved: Boolean,
    /** ガードはプロンプトが表示されても実行しません (再生はルートに従い、異常がある場合はモデルが返されます)。 */
    val stopOnGuardWarning: Boolean,
    /** 呼び出し元は、画面を読み取る前にロック画面レベルを待機しただけであり (再生には待機後の画面チェックが必要です)、実行前に再度待機しません。 */
    val gatedByCaller: Boolean,
    /** 読み取りは、それらが属する呼び出し内でのみカウントされます。せいぜい、タスクがリマインドされるだけで、タスクは停止されません (ページ スクリプトでのポーリング読み取り)。 */
    val isolatedReads: Boolean,
    /** が null でない場合: アクションが開始されると、完了して記録されます (キャンセルできません)。最長でもこのくらい待つことができます。 */
    val actionCapMs: Long?,
    val frames: Frames,
    /** 主要なターゲット要素と前後のアンカー ポイントに注意してください (再生がオンの場合)。 */
    val routeTargets: Boolean,
    /** 確認レベルでブロックされた通話自体はモデルに引き継がれた結果であり、通常通り走行記録に記録されます。 */
    val recordsRefusal: Boolean,
    val refusals: RefusalStyle,
)

/** はアクションの発生元です。 [step] は、そのサイクル ラウンドです。 */
sealed interface Origin {
    val step: Int
    val policy: OriginPolicy

    /** モデルのツール呼び出し。 */
    data class Model(override val step: Int) : Origin {
        override val policy get() = MODEL
    }

    /** do_steps のステップ。 */
    data class Batch(override val step: Int) : Origin {
        override val policy get() = BATCH
    }

    /** web_script の `mika.*` アクション。 */
    data class Script(override val step: Int) : Origin {
        override val policy get() = SCRIPT
    }

    /** ワンステップの体験再生（モデル調整なし）。 */
    data object Replay : Origin {
        override val step = 0
        override val policy get() = REPLAY
    }

    /** MCP サーバー上の低レベルのツール呼び出し。モデルはありませんが、同じ実行エントリを通過する必要があります。 */
    data class McpTool(override val step: Int) : Origin {
        override val policy get() = MCP_TOOL
    }

    companion object {
        /** ページ上のスクリプト アクションの上限: URL を開いて読み込みまで最大 20 秒待ち、その後余白を残してページが静かになるまで待ちます。 */
        const val SCRIPT_ACTION_CAP_MS = 40_000L

        // ユーザーの同意がある場合、モデル ステップのみを支払いのためにリリースできます。他のソースは、支払いが発生すると停止して戻ります。
        private val MODEL = OriginPolicy(
            "model", usesGrant = true, judgeWithoutGate = false, refuseUnresolved = false, stopOnGuardWarning = false,
            gatedByCaller = false, isolatedReads = false, actionCapMs = null, frames = Frames.Caller, routeTargets = true,
            recordsRefusal = true, refusals = RefusalStyle.Model,
        )
        private val BATCH = OriginPolicy(
            "batch", usesGrant = false, judgeWithoutGate = true, refuseUnresolved = true, stopOnGuardWarning = false,
            gatedByCaller = false, isolatedReads = false, actionCapMs = null, frames = Frames.WhenChanged, routeTargets = true,
            recordsRefusal = false, refusals = RefusalStyle.Code,
        )
        // スクリプトは Web ページ上のプログラムであるため、ルートのステップとして再生できません
        private val SCRIPT = OriginPolicy(
            "script", usesGrant = false, judgeWithoutGate = true, refuseUnresolved = true, stopOnGuardWarning = false,
            gatedByCaller = false, isolatedReads = true, actionCapMs = SCRIPT_ACTION_CAP_MS, frames = Frames.WhenChanged, routeTargets = false,
            recordsRefusal = false, refusals = RefusalStyle.Script,
        )
        // の再生はモデル ステップと一致しています。レベルが設定されていない場合、判定は行われません。
        private val REPLAY = OriginPolicy(
            "replay", usesGrant = false, judgeWithoutGate = false, refuseUnresolved = true, stopOnGuardWarning = true,
            gatedByCaller = true, isolatedReads = false, actionCapMs = null, frames = Frames.None, routeTargets = true,
            recordsRefusal = false, refusals = RefusalStyle.Code,
        )
        private val MCP_TOOL = OriginPolicy(
            "mcp_tool", usesGrant = false, judgeWithoutGate = true, refuseUnresolved = true, stopOnGuardWarning = true,
            gatedByCaller = false, isolatedReads = false, actionCapMs = null, frames = Frames.None, routeTargets = false,
            recordsRefusal = true, refusals = RefusalStyle.Code,
        )
    }
}

/** アクションの開始者。 Origin は古い発信者の名前のままにしておきます。 RunOrigin は、この実行がどこから来たのかを示すだけです。 */
typealias Invoker = Origin
