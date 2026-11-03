package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.observation.ObsForm
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.verify.EvidenceLedger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val RECENT_READS = 3
internal const val MAX_READ_CHARS = EvidenceLedger.READ_CHARS
private const val MAX_ACTION_LOG = 30
/** ユーザーによって提供された事実: 最後のいくつかのエントリ。それぞれ最初から最後まで切り詰められています。 */
private const val USER_FACTS = 6
private const val USER_FACT_CHARS = EvidenceLedger.OUTPUT_CHARS

/**
 * 完成前に検証者に渡された資料。読み取られるものはすべて観察です ([Observation]: インターフェイスと Web ページの読み取り、サンドボックスの出力とファイルの読み取り、 ツールの結果にある
 * の事実)、能力はツールの結果に示され、ディストリビューターは [observe] を介して証拠台帳に [ledger] を、いつ、どのステップで記録します。
 * ユーザーの発言（[ObsKind.UserSaid]：記憶検索結果、質問への回答）も [userFacts] に記録されます。アクションのリストと各乗っ取りの結末もあります。
 * 各ツールは、アクション リスト ([AgentTool.logTarget]) に何を読み取るか ([AgentTool.observations])、何を書き込むかを独自に決定します。
 */
internal class EvidenceLog(private val clock: () -> Long = System::currentTimeMillis) {
    val ledger = EvidenceLedger()
    /** 現在のステップは (ツールの各実行前にスケジューラによって設定されます) であり、ソースは台帳に記録されます。 */
    var step: Int = 0
    /** 最近数回読み取られたインターフェイス ステータスのテキスト (インターフェイスの読み取りと再利用)。 */
    val recentReads = ArrayDeque<String>()
    val actionLog = ArrayDeque<String>()
    /** ユーザーから提供された事実: メモリ検索結果、このタスクの ask_user への回答。 */
    val userFacts = ArrayDeque<String>()
    /** list_apps で指定されたアプリ名が launch_app のファクトに含まれます。 */
    val appLabels = mutableMapOf<String, String>()
    /** 各テイクオーバーの終了。数に制限はなく、アクションリストのように出てくることもありません。 */
    val takeovers = mutableListOf<String>()
    /** write_file を使用してアシスタントによって書き込まれたファイル: read_file によって読み取られるものはアシスタント自身によって書き込まれており、答えの証拠は提供されません。 */
    val writtenPaths = mutableSetOf<String>()

    /**
     * 観察に注意してください。ユーザーが言ったことは [userFacts] に入力され (先頭と末尾で切り詰められます)、残りは台帳に入力されます (エコーの感度を下げ、切り詰め、削除します。[EvidenceLedger.add] を参照)。
     * は台帳に記録されたエントリを返します。台帳に含まれていないエントリ (ユーザーによって指定され、切り捨てられた後に空になるエントリ) は null です。
     */
    fun observe(o: Observation): Observation? {
        if (o.kind == ObsKind.UserSaid) {
            noteUserFact(o.text)
            return null
        }
        if (o.form == ObsForm.Interface) {
            recentReads.addLast(o.text)
            while (recentReads.size > RECENT_READS) recentReads.removeFirst()
        }
        return ledger.add(o, step, clock())
    }

    /** アシスタントが入力ボックス (パスワード ボックスではない) に入力した単語: 入力ボックス内のエコーは答えを証明できません。 */
    fun noteTyped(text: String) = ledger.noteTyped(text)

    private fun noteUserFact(text: String) {
        userFacts.addLast(if (text.length <= USER_FACT_CHARS) text else text.take(USER_FACT_CHARS * 3 / 4) + "\n…（中略）…\n" + text.takeLast(USER_FACT_CHARS / 4))
        while (userFacts.size > USER_FACTS) userFacts.removeFirst()
    }

    fun noteTakeover(line: String) {
        ledger.noteTakeover()
        takeovers += line
        actionLog.addLast(line)
        while (actionLog.size > MAX_ACTION_LOG) actionLog.removeFirst()
    }

    /**
     * アクション リストの行: [label] は「N 番目のステップ」、[target] はアクションのオブジェクト、[typed] は入力単語 (すべてツールによって与えられます)、
     * [wide] の場合、オブジェクト (Web アドレス、コマンド、パス) は長くなります。
     */
    fun logAction(label: String, name: String, target: String?, typed: String?, r: ToolExecution, wide: Boolean) {
        val effect = when {
            r.content["ok"] != JsonPrimitive(true) -> (r.content["error"] as? JsonPrimitive)?.content
                ?: (r.content["exitCode"] as? JsonPrimitive)?.let { "終了コード ${it.content}" } ?: "失敗"
            r.changed == false -> "画面に変化なし"
            else -> "操作を反映"
        }
        val what = listOfNotNull(target?.take(if (wide) 120 else 40), typed).joinToString(" ")
        actionLog.addLast("$label $name($what) $effect")
        while (actionLog.size > MAX_ACTION_LOG) actionLog.removeFirst()
    }
}

internal fun JsonObject.argStr(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
