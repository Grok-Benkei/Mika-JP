package com.wangzan101.mika.core.verify

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * の答えの 1 つの情報源: 証拠台帳 ([obsId]、[com.wangzan101.mika.core.observation.Observation.id] を参照) の観察を参照しています。
 * それが何であっても (インターフェイス、ページ、ファイル、コマンド出力、応答)。
 * [kind] は観察のタイプです ([com.wangzan101.mika.core.observation.ObsKind.wire]; 古いレコードでは、screen / web / app_ai / Output / fat)。
 * [source] はその機能を提供します (古いレコードにはそれがありません)。 [place] は読み取られる場所 (アプリのパッケージ名、「ウェブサイトのドメイン名」または URL、コマンド、またはファイル)、[title] はそのページのタイトルです。
 * [step] は読み取りステップです。 [mediaIds] は、そのステップで保存された画面番号です (空ではありません)。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnswerSource(
    @JsonNames("entryId") val obsId: Int, val kind: String, val place: String?, val title: String?, val step: Int,
    val mediaIds: List<Int> = emptyList(), val source: String? = null,
)

/** 答えのソースは、モデルに書かれたものではなくコード内にあります。つまり、答えの事実と一致する証拠台帳内の観察です。 */
object AnswerSources {
    /** は、多くても非常に多くの場所にリストされます。 */
    const val MAX = 6

    /**
     * [media] は各ステップ（ステップ→番号）で保存される画面番号です。回答に有効な事実がある場合は、これらの所見を列挙してください。
     * 回答に有効な事実がない場合 (「Bluetooth がオンになっている」)、回答で言及されている結果が存在する最後の観察を使用します。何も読み取られていない場合は空です。
     */
    fun of(ledger: EvidenceLedger, goal: String, answer: String, media: Map<Int, List<Int>>): List<AnswerSource> {
        val hits = ledger.supporting(goal, answer).ifEmpty { listOfNotNull(ledger.all.lastOrNull()) }
        return hits.map { AnswerSource(it.id, it.kind.wire, it.where, it.title, it.step, media[it.step].orEmpty(), it.source) }
            .takeLast(MAX)
    }
}
