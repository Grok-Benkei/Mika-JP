package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.knowledge.KNOWLEDGE_KINDS
import com.wangzan101.mika.core.knowledge.KnowledgeIndex
import com.wangzan101.mika.core.knowledge.PERSONAL_KIND
import com.wangzan101.mika.core.knowledge.RECOMMEND_MIN_SCORE
import com.wangzan101.mika.core.knowledge.SKILL_KINDS
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.knowledge.GuideInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.Verb

internal const val SKILL_INDEX_HEADER = "All skills you have, one line each (when one fits this task, read its full text with skill_read before acting on it):"
internal const val KNOWLEDGE_INDEX_HEADER = "App and website knowledge you can refer to (read the full text with knowledge_read when needed):"
private const val RECOMMEND_COUNT = 3

/**
 * タスクの開始時のインデックス (デプロイメント コンテキストで一度だけ書き込み、途中で変更しないでください):
 * タスク テキストに従ってアプリ/サイトのナレッジが検索され、独自の方法が使用されます (最初の 3 項目をスキルとともに取得し、ナレッジのみを残します)。
 * スキルを選択するのではなく、すべてをリストします。先頭に組み込みスキル、最後に個人スキルを名前順に、コピーごとに 1 行 (ID、表示名、説明)、
 * を使用するかどうか、およびどのコピーを使用するかは、モデルの説明によって決まります。使用したい場合は、skill_read を使用して全文を読んでください。
 */
internal object GuideRecommender {
    fun id(info: GuideInfo) = "${info.kind}:${info.name}"
    fun line(info: GuideInfo) = "- ${id(info)}(${info.display}):${info.description}"

    /** 知識とスキルはそれぞれ独自の見出しの下にリストされています。どちらも null ではありません。個人スキルは使用中および試用中のみです ([GuideCatalog.items] を参照)。 */
    fun index(catalog: GuideCatalog, task: String): String? {
        val knowledge = catalog.find(task, limit = RECOMMEND_COUNT, minScore = RECOMMEND_MIN_SCORE).filter { it.kind in KNOWLEDGE_KINDS }
        val skills = catalog.items.filter { it.kind in SKILL_KINDS }.sortedWith(compareBy({ it.kind != KnowledgeIndex.SKILL }, { it.name }))
        return listOf(KNOWLEDGE_INDEX_HEADER to knowledge, SKILL_INDEX_HEADER to skills).filter { it.second.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { (header, items) -> header + "\n" + items.joinToString("\n", transform = ::line) }
    }

    /** skill_read は、個人スキルの読み取りが成功した場合のスキル名を与えます。 */
    fun viewedPersonal(verb: Verb?, content: JsonObject): String? {
        if (verb != Verb.SkillRead || content["ok"] != JsonPrimitive(true)) return null
        if ((content["kind"] as? JsonPrimitive)?.contentOrNull != PERSONAL_KIND) return null
        return (content["name"] as? JsonPrimitive)?.contentOrNull
    }
}
