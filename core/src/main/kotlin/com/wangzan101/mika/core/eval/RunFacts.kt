package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.verify.EvidenceLedger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb

/** 実行のイベントから評価のためのファクトを取得します。 */
object RunFacts {
    /**
     * は、同じランタイム チェッカー [EvidenceLedger] (同じマスキング、切り捨て、重複排除、および回答による選択) を使用して、実行記録からこのタスクの証拠台帳を再構築します。
     * 各ツール レコードのフィールドは、実行時のフィールドと同じです ([RecordedEvidence] を参照)。
     */
    fun ledger(events: List<RunEvent>, tools: ToolIndex): EvidenceLedger = RecordedEvidence.ledger(events, tools)

    /** 回答を確認するための評価で使用される証拠: 台帳内の回答に従って選択されたエントリの全文が、一度に 1 つずつ読み取られます (実行時に検証者に与えられる選択方法と同じ)。 */
    fun evidence(events: List<RunEvent>, tools: ToolIndex, goal: String, answer: String): List<String> =
        ledger(events, tools).forAnswer(goal, answer).map { it.text }

    /** インターフェイスを開くか読み取るエージェント アプリのパッケージ名。最初の出現順に並べ替えられます。 launch_app を使用して */
    fun appsSeen(events: List<RunEvent>, tools: ToolIndex): List<String> = events.mapNotNull { e ->
        when {
            e is RunEvent.Observed && e.kind == READ -> appOf(e)
            e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) == Verb.LaunchApp -> arg(e, "package")
            else -> null
        }
    }.filter { it.isNotBlank() }.distinct()

    /** エージェントによって開かれたアプリは、最初に開いた順に並べ替えられます (実行後、`{app:<能力>}` がどのアプリに使用されるかを決定します)。 */
    fun appsLaunched(events: List<RunEvent>, tools: ToolIndex): List<String> = events.mapNotNull { e ->
        if (e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) == Verb.LaunchApp) arg(e, "package") else null
    }.filter { it.isNotBlank() }.distinct()

    /**
     * が乗っ取りのためにユーザーに引き渡される理由には、画面上の証拠が必要です。それは、初めて乗っ取りに入る前の最新のバリア レポート (バリア イベント)、および乗っ取りの申請以外に 2 者の間に他のアクションがなかったことです。
     * そして、この障害は、報告する前に最後に読み取られた画面 ([BarrierSignals]) で確認できます。モデルが発言し、画面に表示されないもののみがカウントされません。
     */
    fun takeoverBarrier(events: List<RunEvent>, tools: ToolIndex): String? {
        val t = events.indexOfFirst { it is RunEvent.State && it.name == TAKEOVER_STATE }
        if (t < 0) return null
        val b = (t - 1 downTo 0).firstOrNull { events[it] is RunEvent.Barrier } ?: return null
        if ((b + 1 until t).any { val e = events[it]; e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) != Verb.HandToUser }) return null
        val kind = (events[b] as RunEvent.Barrier).kind
        val read = (b - 1 downTo 0).map { events[it] }.firstOrNull { it is RunEvent.Observed && it.kind == READ } as? RunEvent.Observed ?: return null
        return kind.takeIf { BarrierSignals.shows(it, read.text.lines()) }
    }

    /**
     * エージェント 入力時にフォアグラウンドにあるアプリ (入力前に最後に読み取られたインターフェイスが配置されているアプリ) を初出順に並べ替えます: どのアプリに、質問を書くために作成されたものが配置されています。
     * Web ページはアプリとみなされません。
     */
    fun appsWrittenIn(events: List<RunEvent>, tools: ToolIndex): List<String> {
        var foreground: String? = null
        val out = mutableListOf<String>()
        for (e in events) {
            if (e is RunEvent.Observed && e.kind == READ) foreground = appOf(e)
            if (e is RunEvent.ModelReply && e.acted && tools.verb(e.tool) == Verb.TypeText) foreground?.takeIf { PACKAGE.matches(it) }?.let { out += it }
        }
        return out.distinct()
    }

    /**
     * この応答はユーザーに与えられる最終的な応答です。電話をかけずに言葉で応答します。古い記録では、それはフィニッシュコールでした（当時はツールを通じて答えが得られました）。
     * 再配布された軽量モデルはカウントされません。
     */
    fun isFinalAnswer(e: RunEvent): Boolean =
        e is RunEvent.ModelReply && e.acted && (e.tool == LEGACY_FINISH || (e.tool == null && e.text.isNotBlank()))

    /** の最終回答の元のテキスト: 古いレコードは、finish という回答パラメーターを受け取り、新しいレコードは、返信の単語を受け取ります (レコード内の長いものは切り捨てられ、完全なものは task_ended にあります)。 */
    fun answerOf(r: RunEvent.ModelReply): String? = if (r.tool == LEGACY_FINISH) arg(r, "answer") else r.text.takeIf { it.isNotBlank() }

    /**
     * このタスクでは、外部または取り消しできないアクション (承認レコードのリスク レベルに応じて、送信、送信、注文、削除...) がリリースされたか、支払いがユーザーに渡されました。
     * のようなタスクの場合は、回答を送信する前にモデルを確認してください。
     */
    fun actedOutward(events: List<RunEvent>): Boolean =
        events.any { it is RunEvent.Approval && it.decision == "allowed" && it.risk in OUTWARD_RISKS } ||
            events.any { it is RunEvent.ModelReply && it.acted && it.tool == "hand_to_user" && arg(it, "reason") == "payment" }

    /** 古いレコードで結果を送信したツールの名前 (呼び出しなしの応答が応答になりました)。 */
    const val LEGACY_FINISH = "finish"
    private val OUTWARD_RISKS = com.wangzan101.mika.core.action.ActionRisk.entries.filter { it.outward }.map { it.name }.toSet()

    private const val TAKEOVER_STATE = "waiting_takeover"
    private val READ = ObsKind.Read.wire

    /** この観察が読まれたアプリ。 Web ページ上のものは null です。 */
    private fun appOf(e: RunEvent.Observed): String? = e.place?.takeIf { it.kind == PlaceKind.App }?.name

    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /**
     * は確認レベルで停止しました。最後の確認イベントは拒否 (レビューでの自動拒否) であり、その後タスクは自動的に終了しました (キャンセルされませんでした)。
     * 却下後に再度申請した場合、承認された場合、または未完了の場合はカウントされません。
     */
    fun stoppedAtGate(events: List<RunEvent>): Boolean {
        val i = events.indexOfLast { it is RunEvent.Confirmation && it.decision != "required" && it.decision != "mismatch" }
        val last = events.getOrNull(i) as? RunEvent.Confirmation ?: return false
        if (last.decision != "declined" && last.decision != "declined_before") return false
        return events.drop(i + 1).any { it is RunEvent.TaskEnded && it.outcome != "cancelled" }
    }


    private fun arg(r: RunEvent.ModelReply, key: String): String? = runCatching {
        ((Json.parseToJsonElement(r.argsJson.orEmpty()) as JsonObject)[key] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()
}
