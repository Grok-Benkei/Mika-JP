package com.wangzan101.mika.core.skills

/** ドラフト保留中。裁判は受理されましたが、まだ何も行われていません。 active は完了しました (LearnGate を参照)。一時停止は最近機能しません。 */
@Suppress("EnumEntryName")
enum class SkillStatus { active, pending, suspended, trial }

/**
 * ユーザー自身のスキル: 完了したタスクから抽出され、ユーザーによって受け入れられます (または受け入れられる下書き)。
 * [markdown] は SKILL.md 全体 (ヘッダーを含む) です。 [replacesDraftOf] は、「Update」ドラフトで指定されている既存のスキル名です。
 * [outcomes] は、最近の使用結果です (ルールを非アクティブ化するために使用されます)。
 */
data class PersonalSkill(
    val name: String,
    val markdown: String,
    val status: SkillStatus,
    val learnedFrom: String?,
    val replacesDraftOf: String?,
    val createdAt: Long,
    val outcomes: List<String>,
    /** は、草案作成時に読み取られた元のテキストのフィンガープリントを更新します。受理される前に、確認のために元のテキストを再読する必要があります。 */
    val baseHash: String? = null,
    /** の由来: [SOURCE_USER] は、ユーザーがタスク (`skill_save`) に書き込むよう要求したものです。完了したタスクから null が沈殿します。 */
    val source: String? = null,
) {
    val createdByUser: Boolean get() = source == SOURCE_USER
    val imported: Boolean get() = source == SOURCE_IMPORTED

    /** 非アクティブ化後に復元するときにどのレベルに戻るか: まだ完了していないもの (沈殿、ユーザー要件が書き留められているもの) は試用版に戻り、完了したものは正式版に戻ります。 */
    fun restoredStatus(): SkillStatus =
        if ((learnedFrom != null || createdByUser || imported) && SkillResult.verified.name !in outcomes) SkillStatus.trial else SkillStatus.active

    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_IMPORTED = "imported"
    }
}

data class SkillRevision(val id: String, val operation: String, val atMs: Long)

/** 個人スキルへのアクセスと承認されるドラフト。配置はアプリ層によって実装されます。 */
interface PersonalSkills {
    /** ドラフトを除く、受信済み (使用中および非アクティブ)。 */
    fun list(): List<PersonalSkill>
    fun get(name: String): PersonalSkill?
    fun put(s: PersonalSkill)
    fun remove(name: String)
    /** は 1 つのスキルの読み取りと変更を行い、他の書き込みとは相互に排他的です (並列タスクが同時に結果を記録しても、結果は失われません)。 [transform] は変更がないことを示す null を返します。変更したものを返却します。 */
    fun update(name: String, transform: (PersonalSkill) -> PersonalSkill?): PersonalSkill?

    fun pending(): List<PersonalSkill>
    /** ドラフトを承認するには、ドラフト番号 (インターフェイスを承認するか使用しないときに使用します) を押します。 */
    fun drafts(): Map<String, PersonalSkill>
    fun putPending(id: String, s: PersonalSkill)
    /** はドラフトを受け入れます。これは使用中の個人スキルになります (更新されたドラフトは [PersonalSkill.replacesDraftOf] をカバーします)。 [editedMarkdown] は、ユーザーによって変更された全文です。下書きが存在しない → null。 */
    fun approve(id: String, editedMarkdown: String? = null): PersonalSkill?
    fun reject(id: String)
    fun revisions(name: String): List<SkillRevision> = emptyList()
    fun restore(revision: String): Boolean = false

    companion object {
        /** モデルに表示されるバージョン番号: 元の指紋の最初の 12 桁 (指紋を読み取って変更します。変更するときに持参してください。一致しない場合は、途中で変更されたことを意味します)。 */
        fun version(markdown: String): String = fingerprint(markdown).take(12)
        fun fingerprint(markdown: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(markdown.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        /** スキル名: 小文字、数字、ハイフン、およびディレクトリ名。 */
        val NAME = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

        /** ヘッダーの `name:` 行を [name] に変更します (更新されたドラフトを受け入れて全文を変更する場合、ヘッダー名は保存されている名前と一致している必要があります)。ヘッダがない場合はそのまま返します。 */
        fun withName(markdown: String, name: String): String = withField(markdown, "name", name)

        /** ヘッダーの [key] 行は、[value] に置き換えられます (そうでない場合は追加します)。ヘッダーのない行はそのまま返されます。 */
        fun withField(markdown: String, key: String, value: String): String {
            val lines = markdown.replace("\r\n", "\n").split("\n").toMutableList()
            if (lines.firstOrNull()?.trimEnd() != "---") return markdown
            val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return markdown
            val matches = (1 until end).filter { isField(lines[it], key) }
            if (matches.isEmpty()) lines.add(end, "$key: $value") else {
                lines[matches.first()] = "$key: $value"
                matches.drop(1).asReversed().forEach(lines::removeAt)
            }
            return lines.joinToString("\n")
        }

        /** ヘッダーが削除された行 [key]。ヘッダーまたはこの行なしで変更されずに返されます。 */
        fun withoutField(markdown: String, key: String): String {
            val lines = markdown.replace("\r\n", "\n").split("\n").toMutableList()
            if (lines.firstOrNull()?.trimEnd() != "---") return markdown
            val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return markdown
            val matches = (1 until end).filter { isField(lines[it], key) }
            if (matches.isEmpty()) return markdown
            matches.asReversed().forEach(lines::removeAt)
            return lines.joinToString("\n")
        }

        /** FrontMatter と同じようにキーの前後の空白を許容します。 */
        private fun isField(line: String, key: String): Boolean {
            val colon = line.indexOf(':')
            return colon > 0 && line.substring(0, colon).trim() == key
        }
    }
}
