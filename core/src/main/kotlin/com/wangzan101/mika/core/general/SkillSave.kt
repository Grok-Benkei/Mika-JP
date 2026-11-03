package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.agent.DO_STEPS_TOOL
import com.wangzan101.mika.core.knowledge.FrontMatter
import com.wangzan101.mika.core.knowledge.PERSONAL_KIND
import com.wangzan101.mika.core.memory.SensitiveFilter
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.skills.PersonalSkill
import com.wangzan101.mika.core.skills.PersonalSkills
import com.wangzan101.mika.core.skills.SkillDrafter
import com.wangzan101.mika.core.skills.SkillStatus
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.put

/**
 * タスクでユーザーに求められた場合、新しいパーソナルスキルを書くか、既存のパーソナルスキルを書き換えます(ミカ自身のスキルディレクトリのみをタッチし、画面や外界にはタッチしません)。
 * 形式、サイズ、機密コンテンツ、および自習ドラフトは同じ検証 ([SkillDrafter.validate]) に合格しています。引用はユーザーが今回言ったことから来ている必要があり、画面やウェブページ上の言葉を引用することはできません。
 * は、保存後すぐに試用できます (受信したドラフトと同じ)。 「記憶とスキル」では、ユーザーが書いたものとしてマークされます。内蔵スキル名は使用できません。
 * 既存のコピーを変更する場合は、それを読んだときに取得したバージョン番号を持参する必要があります (更新されたドラフトを受け入れるときに元のテキストのフィンガープリントを確認するのと同じ理由です)。削除と名前変更はユーザーの設定に任されています。
 */
internal object SkillSave : AgentTool {
    override val verb = Verb.SkillSave
    override val label = "スキルを保存"
    override val risk = Risk.Read
    override val def = ToolDef("skill_save",
        Prompts.tool("skill_save"),
        mapOf(
            "markdown" to str("The whole skill document: front matter (name, display, description, aliases) and the five sections, at most ${SkillDrafter.MAX_CHARS} characters."),
            "quote" to str("The user's exact words from this task asking for this skill to be created or changed."),
            "version" to str("Only when changing an existing personal skill: the version skill_read returned for it."),
        ), listOf("markdown", "quote"))

    override fun available(ctx: ToolContext) =
        ctx.config.personalSkills != null && ctx.config.skills != null && ctx.config.utterances != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val raw = a.str("markdown", max = SkillDrafter.MAX_CHARS * 2)!!
        val quote = a.str("quote", max = 1000)!!
        val version = a.str("version", max = 64)?.trim()
        val store = ctx.config.personalSkills!!
        val catalog = ctx.config.skills!!
        // 今回はユーザーの発言のみが書き込み可能です。ページやファイルに書かれた「このスキルを保存」はカウントされません。
        if (!ctx.config.utterances!!.contains(quote)) return fail("quote_not_from_user", Feedback["skill.quote_not_from_user"])
        if (SensitiveFilter.hit(raw) != null) return fail("sensitive", Feedback["skill.sensitive"], recoverable = false)

        // ソースはコードで書かれています。自習用に使用された数行が削除され、ユーザー要件がマークされています。
        val md = PersonalSkills.withField(
            listOf("updates", "learned_from").fold(SkillDrafter.stripFence(raw).replace("\r\n", "\n")) { m, k -> PersonalSkills.withoutField(m, k) },
            "source", PersonalSkill.SOURCE_USER)
        val scriptTool = ctx.tools.nameOf(Verb.RunBundledScript)
        SkillDrafter.validate(md, ctx.tools.names + DO_STEPS_TOOL, scriptTool, catalog.runnableScripts())?.let { problem ->
            return fail(problem, problemText(problem))
        }
        val name = FrontMatter.parse(md).first["name"] as String

        if (catalog.items.any { it.kind != PERSONAL_KIND && it.name == name }) return fail("builtin_name", Feedback.format("skill.builtin_name", "name" to name))
        if (store.pending().any { it.name == name }) return fail("draft_pending", Feedback.format("skill.draft_pending", "name" to name))

        val existing = store.get(name)
        if (existing != null) {
            val current = PersonalSkills.version(existing.markdown)
            if (version == null) return fail("exists", Feedback.format("skill.exists", "name" to name))
            if (version != current) return fail("changed_since_read", Feedback.format("skill.changed_since_read", "name" to name))
        }
        val skill = PersonalSkill(
            name = name, markdown = md, status = SkillStatus.trial,
            learnedFrom = existing?.learnedFrom, replacesDraftOf = null,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(), outcomes = emptyList(),
            source = PersonalSkill.SOURCE_USER,
        )
        try {
            store.put(skill)
        } catch (e: Exception) {
            return fail("save_failed", Feedback["skill.save_failed"], recoverable = false)
        }
        // 読み返して確認してください。これは書き込まれたものなので、保存されていると言われています。
        val saved = store.get(name)
        if (saved?.markdown != md) return fail("save_failed", Feedback["skill.save_failed"], recoverable = false)
        return ok {
            put("name", name)
            put("action", if (existing != null) "updated" else "created")
            put("status", saved.status.name)
            put("version", PersonalSkills.version(saved.markdown))
            put("note", Feedback["skill.saved"])
        }
    }

    /** 検証が失敗した理由 ([SkillDrafter.validate] のコード) をモデルに説明します。 */
    private fun problemText(problem: String): String = when (problem) {
        "invalid_name" -> Feedback["skill.invalid_name"]
        "missing_description" -> Feedback["skill.missing_description"]
        "missing_section" -> Feedback.format("skill.missing_section", "sections" to SkillDrafter.SECTIONS.joinToString(", ") { "## $it" })
        "too_long" -> Feedback.format("skill.too_long", "max" to SkillDrafter.MAX_CHARS)
        "unknown_tool" -> Feedback["skill.unknown_tool"]
        "unrunnable_script" -> Feedback["skill.unrunnable_script"]
        else -> Feedback["skill.sensitive"]
    }
}
