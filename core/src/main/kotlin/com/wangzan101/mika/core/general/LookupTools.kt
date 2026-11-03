package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.knowledge.GuideView
import com.wangzan101.mika.core.knowledge.KNOWLEDGE_KINDS
import com.wangzan101.mika.core.knowledge.KnowledgeIndex
import com.wangzan101.mika.core.knowledge.PERSONAL_KIND
import com.wangzan101.mika.core.knowledge.SKILL_KINDS
import com.wangzan101.mika.core.knowledge.toolsNote
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Concurrency
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** アプリとウェブサイトの知識を確認します。読み取り専用ディレクトリ。ディレクトリが存在する場合にのみ提供されます。 */
internal object KnowledgeSearch : AgentTool {
    override val verb = Verb.KnowledgeSearch
    override val label = "アプリ／サイトの知識を検索"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    private val KINDS = KnowledgeIndex.KINDS.filter { it in KNOWLEDGE_KINDS }
    override val def = ToolDef("knowledge_search",
        Prompts.tool("knowledge_search"),
        mapOf(
            "query" to query("App or website name, alias or topic words, 1-200 characters."),
            "kind" to str("Only this kind: app (a phone app) or site (a website).", KINDS),
        ), listOf("query"))

    override fun available(ctx: ToolContext) = ctx.config.skills != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val q = a.str("query", max = 200)!!
        val kind = a.str("kind")
        if (kind != null && kind !in KINDS) throw ArgError("kind", "must be ${KINDS.joinToString(", ")}")
        return matches(ctx, q, kind, KNOWLEDGE_KINDS)
    }
}

/** スキル (組み込みスキルとユーザーが使用する個人スキル) を確認します。読み取り専用ディレクトリ。ディレクトリが存在する場合にのみ提供されます。 */
internal object SkillSearch : AgentTool {
    override val verb = Verb.SkillSearch
    override val label = "スキルを検索"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("skill_search", Prompts.tool("skill_search"), mapOf("query" to query("Words for the kind of task, 1-200 characters.")), listOf("query"))

    override fun available(ctx: ToolContext) = ctx.config.skills != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution =
        matches(ctx, a.str("query", max = 200)!!, null, SKILL_KINDS)
}

/** アプリまたはウェブサイトのナレッジのテキストまたは添付ファイルを読みます。 */
internal object KnowledgeRead : AgentTool {
    override val verb = Verb.KnowledgeRead
    override val label = "アプリ／サイトの知識を表示"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("knowledge_read",
        Prompts.tool("knowledge_read"),
        mapOf(
            "name" to str("Name from knowledge_search or from the knowledge given to you, optionally as app:<name> or site:<domain>."),
            "file_path" to FILE_PATH,
        ), listOf("name"))

    override fun available(ctx: ToolContext) = ctx.config.skills != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = read(a, ctx, KNOWLEDGE_KINDS)
}

/** ユーザーの個人スキルを含むスキルのテキストまたは添付ファイルを読み取ります。 */
internal object SkillRead : AgentTool {
    override val verb = Verb.SkillRead
    override val label = "スキルを表示"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("skill_read",
        Prompts.tool("skill_read"),
        mapOf(
            "name" to str("Skill name from skill_search or from a skill list given to you, optionally as skill:<name> or personal:<name>."),
            "file_path" to FILE_PATH,
        ), listOf("name"))

    override fun available(ctx: ToolContext) = ctx.config.skills != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = read(a, ctx, SKILL_KINDS)
}

private fun query(description: String): JsonObject = buildJsonObject {
    put("type", "string"); put("description", description); put("minLength", 1); put("maxLength", 200)
}
private val FILE_PATH = str("Optional relative path of an attachment listed in files, e.g. references/stations.md.")

private fun matches(ctx: ToolContext, query: String, kind: String?, among: Set<String>): ToolExecution {
    val found = ctx.config.skills!!.find(query, kind, among = among)
    return ok {
        put("matches", JsonArray(found.map {
            buildJsonObject { put("name", it.name); put("display", it.display); put("kind", it.kind); put("description", it.description) }
        }))
    }
}

private suspend fun read(a: Args, ctx: ToolContext, among: Set<String>): ToolExecution {
    val name = a.str("name", max = 200)!!
    val file = a.str("file_path", max = 500)
    val catalog = ctx.config.skills!!
    val runner = ctx.scripts
    val ready = runner?.ready() == true
    return when (val v = catalog.view(name, file, toolsNote(runner?.offered() == true), among)) {
        is GuideView.Failed -> fail(v.error, v.message)
        is GuideView.Found -> {
            // には、このストリップ内のスクリプトがいつ実行できるか、および現時点で実行できるかどうかがリストされます。 scriptSource は、スクリプトの実行時にソースによって入力される値です。
            // 個人スキルにはテキストのみがあり、スクリプトはありません
            val dir = if (v.info.kind == PERSONAL_KIND) "" else catalog.scriptSource(v.info)
            val scripts = if (ready && dir.isNotEmpty()) runner!!.scripts(dir) else emptyList()
            ok {
                put("name", v.info.name); put("kind", v.info.kind); put("file", v.file)
                put("content", v.content); put("truncated", v.truncated)
                put("files", JsonArray(v.info.files.map(::JsonPrimitive)))
                // 個人スキルバージョン番号：書き換え（skill_save）の際にご持参ください。他の場所で変更すると、正しくなくなります。
                if (v.info.kind == PERSONAL_KIND && v.file == v.info.files.firstOrNull())
                    ctx.config.personalSkills?.get(v.info.name)?.let { put("version", PersonalSkills.version(it.markdown)) }
                if (scripts.isNotEmpty()) {
                    put("scriptSource", dir)
                    put("scripts", JsonArray(scripts.map { e ->
                        buildJsonObject { put("file", e.file); put("runnable", e.runnable); e.reason?.let { put("reason", it) } }
                    }))
                }
            }
        }
    }
}
