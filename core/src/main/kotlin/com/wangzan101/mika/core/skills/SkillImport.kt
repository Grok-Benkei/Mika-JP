package com.wangzan101.mika.core.skills

import com.wangzan101.mika.core.knowledge.FrontMatter

/** 共有された SKILL.md はモデルを介さず確認待ちの下書きにし、利用者が承認するまで使いません。[SkillDrafter.validate] で形式・サイズ・機密情報を検証し、組込み名の衝突を拒否します。同名の個人スキルは差分を確認できる更新下書きにします。 */
object SkillImport {
    sealed interface Result {
        /** 確認待ちへ保存する識別子と下書きです。 */
        data class Ready(val id: String, val skill: PersonalSkill) : Result
        /** 拒否理由は [SkillDrafter.validate] のコード、builtin_name、draft_pending のいずれかです。 */
        data class Rejected(val problem: String) : Result
    }

    /** 出所・学習元・更新先など端末固有の情報は共有時に取り除き、取込時にコードで設定し直します。 */
    private val LOCAL_FIELDS = listOf("updates", "learned_from", "source")

    /** [LOCAL_FIELDS] だけを除き、本文はそのまま共有します。 */
    fun shareable(markdown: String): String =
        LOCAL_FIELDS.fold(markdown.replace("\r\n", "\n")) { m, k -> PersonalSkills.withoutField(m, k) }

    fun prepare(
        raw: String, store: PersonalSkills, builtinNames: Set<String>,
        toolNames: Set<String>, scriptTool: String?, runnableScripts: Set<String> = emptySet(),
        id: String, now: Long,
    ): Result {
        if (raw.length > SkillDrafter.MAX_CHARS * 2) return Result.Rejected("too_long")
        val md = PersonalSkills.withField(shareable(SkillDrafter.stripFence(raw)), "source", PersonalSkill.SOURCE_IMPORTED)
        SkillDrafter.validate(md, toolNames, scriptTool, runnableScripts)?.let { return Result.Rejected(it) }
        val name = FrontMatter.parse(md).first["name"] as String
        if (name in builtinNames) return Result.Rejected("builtin_name")
        if (store.pending().any { it.name == name || it.replacesDraftOf == name }) return Result.Rejected("draft_pending")
        val existing = store.get(name)
        return Result.Ready(id, PersonalSkill(
            name = name, markdown = md, status = SkillStatus.pending,
            learnedFrom = null, replacesDraftOf = existing?.name,
            createdAt = now, outcomes = emptyList(),
            baseHash = existing?.let { PersonalSkills.fingerprint(it.markdown) },
            source = PersonalSkill.SOURCE_IMPORTED,
        ))
    }
}
