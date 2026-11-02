package com.wangzan101.mika.core.knowledge

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import com.wangzan101.mika.core.workspace.SandboxPaths

/** はスキル スクリプト リスト (`scripts/manifest.json`) の項目です。 [file] は、スキル ディレクトリ内の相対パスです。 */
@Serializable
data class ScriptEntry(val file: String, val runnable: Boolean, val needs: List<String> = emptyList(), val network: Boolean = false, val reason: String? = null)

/**
 * はスキルディレクトリにあります。 `scripts/manifest.json`:`[{file, runnable, needs, network, reason?}]`、[ScriptEntry.file]はスキルディレクトリ内の相対パスです。
 * run_bundled_script、knowledge_read、skill_read はすべてこれに基づいています。
 */
object ScriptManifest {
    const val NEEDS_NETWORK = "ネットワーク接続が必要です。Mikaの設定で「スクリプトのネット接続を許可」を有効にできます"
    private val json = Json { ignoreUnknownKeys = true }
    private val INTERPRETERS = mapOf("py" to "/usr/bin/python3", "mjs" to "/usr/bin/node", "js" to "/usr/bin/node", "sh" to "/bin/sh")

    /** は、読み取れない場合は空です。パスがスキルディレクトリ内の相対パスではないアイテムは破棄されます。 */
    fun parse(text: String): List<ScriptEntry> = try {
        json.decodeFromString<List<ScriptEntry>>(text).filter { safe(it.file) }
    } catch (e: SerializationException) {
        emptyList()
    } catch (e: IllegalArgumentException) {
        emptyList()
    }

    /** は絶対パスを使用します。ワークスペースまたは PATH 内の同じ名前のファイルの影響を受けません。 現時点で */
    fun interpreterFor(file: String): String? = INTERPRETERS[file.substringAfterLast('.', "").lowercase()]

    /** が実行できるかどうか、実行できない場合はその理由を示します。動作不能としてマークされている場合は、独自の理由を使用します。インターネットに接続する必要があるがネットワークがオフになっている場合は、設定でインターネットを開くように求められます。 */
    fun effectiveRunnable(e: ScriptEntry, networkAllowed: Boolean): Pair<Boolean, String?> = when {
        !e.runnable -> false to (e.reason ?: "cannot be run for now")
        interpreterFor(e.file) == null -> false to "unsupported script type"
        e.network && !networkAllowed -> false to NEEDS_NETWORK
        else -> true to null
    }

    private fun safe(rel: String): Boolean {
        if (rel.isBlank() || rel.startsWith("/")) return false
        val base = "${SandboxPaths.SKILLS}/x"
        return SandboxPaths.normalize(rel, base)?.startsWith("$base/") == true
    }
}
