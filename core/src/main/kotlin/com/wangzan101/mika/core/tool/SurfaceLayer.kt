package com.wangzan101.mika.core.tool

import kotlinx.serialization.json.JsonObject

/**
 * には、各通話の前後で同じレイヤーを共有するインターフェイス機能 (携帯電話、Web ページ) があります。どの画面を操作するか、アクションの前後で実際の効果を一目で確認、アクションが付加された後のインターフェイスです。
 * およびその他のインターフェイスは安定しており、現時点では次のようになります。ディストリビュータはこのインターフェイスのみを使用し、画面の読み取り、要素、番号などのインターフェイスの状態を知りません。特定の実装は、最初にアセンブリ層によって提供されます。
 * 画面機能の ID。
 */
internal interface SurfaceLayer {
    /** 現在操作中の画面。通話がない場合は、最後に正常に操作された画面であり、タスクが開始されたときの最初の画面です。 */
    val current: String

    /** は通話で開始され、[id] 画面で動作します。それが画面でない場合、または提供されていない場合は、現在の画面が使用されます。 */
    fun enter(id: String?)

    /** 通話は終了します。 [succeeded]の場合、その画面が最後に操作した画面になります。 */
    fun leave(succeeded: Boolean)

    /** このツールが動作する画面。ツールが画面ではない場合、null になります。 */
    fun surfaceOf(tool: AgentTool?): String?

    /** この画面のツールを別の画面の番号で操作した（実行されなかった）場合にモデルに与えられる結果。認識されない、または使用されない場合、エラーは null になります。 */
    fun misaimed(tool: AgentTool, args: JsonObject, tools: ToolIndex): ToolExecution?

    /** この呼び出しはインターフェイス上にありません (または、[around] に行きません): 前回残されたアクション ターゲットは保持されません。 */
    fun skip()

    /**
     * はツールの実行を 1 回ラップします。事前にインターフェイスで準備を行い、[body] が「今回は実際の効果がインターフェイス側から報告される」かどうかを取得し、インターフェイスで仕上げを行います。
     * ([inlineScreen] をクリックしてインターフェース差分を添付し、画面を添付し、効果を報告し、画像から取得したポイントをクリアします)。
     */
    suspend fun around(tool: AgentTool, args: JsonObject, inlineScreen: Boolean, body: suspend (reportsEffect: Boolean) -> ToolExecution): ToolExecution

    /** がオンの場合、ターゲット要素とアンカー ポイントは書き込み操作が実行される前に取得されます (再生スイッチがオンのときにループによって設定されます)。 */
    var captureTargets: Boolean

    /** 実行記録では、現時点でのモデル番号に対応するポート番号が使用されます。そうでない場合は空です。 現時点で */
    fun portRecord(tool: AgentTool?, args: JsonObject): JsonObject

    /** モデルでまだ表示されている読み取り値 (番号別)。 */
    fun setVisible(ids: Set<String>)

    /** など 最近操作した画面は [timeoutMs] までは安定しています。ステータスを読み取れない場合は通常どおりに戻ります。 */
    suspend fun settle(timeoutMs: Long)

    /** 最近操作した画面に表示される単語（要素ごとに 1 つ、登録番号なし）。読み取られていない場合は空です。 */
    suspend fun visibleText(): List<String>

    /**
     * 最近操作した画面で [until] が確立されるまで待つか、事故が発生し、制限時間に達し、[interrupted] が true (ユーザーが中断) になった場合は、もう待つ必要はありません。
     * 時間は [clock] として計算されます。画面読み上げでは番号は登録されません。
     */
    suspend fun waitUntil(until: Until, clock: () -> Long, interrupted: () -> Boolean): UntilOutcome

    /** 最近操作された画面のフレームとその画面番号。保護されている場合、または傍受できない場合は、null になります。 現時点では、 */
    suspend fun imageNow(): Pair<ByteArray, Int>?

    /** は次のようになります (コンテンツと画面が同時に表示されます)。何も開かれていない場合は null です。観察はまだ台帳に記録されていません。 */
    suspend fun viewNow(): ToolDispatcher.Glance?

    /** 現時点でのその場所のバージョン (Web ページのモバイル バージョン、コンピューター バージョン)。利用できない場合、または読み取ることができない場合は、null になります。 */
    suspend fun mode(): String?
}
