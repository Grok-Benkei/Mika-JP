package com.wangzan101.mika.core.knowledge

/** ナレッジ ディレクトリまたはスキル ディレクトリ内のスクリプトが現時点で実行できるかどうか。 [reason] 実行できない場合の理由を書きます。 組み込みスクリプトを実行する */
data class ScriptState(val file: String, val runnable: Boolean, val reason: String?)

/** の機能は、knowledge_read、skill_read、およびリファレンス ノートに任されています。 */
interface BundledScripts {
    /** このタスクは、スクリプトを実行するためのツールを提供します (実行環境はまだ準備されている可能性があります)。 */
    fun offered(): Boolean

    /** この時点で実行環境の準備ができたら、まずスキル ディレクトリをパッケージ化されたコピーにチェックインし、true を返します。準備ができていない場合は false になります。 */
    suspend fun ready(): Boolean

    /** [skillDir] 現時点では、次のリストのスクリプトを実行できます。 */
    fun scripts(skillDir: String): List<ScriptState>
}
