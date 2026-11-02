package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.agent.ContextBuilder
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.File
import java.security.MessageDigest

/**
 * 「モデルが見たもの」のスナップショット: 各リクエストのシステム プロンプト ワード (概要)、ツール リスト、メッセージごとのメッセージ (役割、コンテキスト タイプとソース、最初の行、全文概要)。
 * 最初の行により違いが簡単に確認でき、要約により変更された単語が確実に検出されます。システム プロンプトの単語とツール リストは、変更された場合にのみ記憶されます。
 *
 * ゴールド サンプル ファイルは core/src/test/resources/loop-snapshots/ に再生成されます。
 *   ./gradlew :core:test --tests '*LoopScenarioTest' -PupdateSnapshots
 */
object LoopSnapshot {
    private val dir = File(System.getProperty("mika.snapshotDir") ?: "src/test/resources/loop-snapshots")
    private val update = System.getProperty("mika.updateSnapshots") == "true"

    /** をゴールドサンプルと比較してください。再生成が有効な場合は直接書き込みます。 */
    fun check(name: String, actual: String) {
        val file = File(dir, "$name.txt")
        if (update) {
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.exists()) fail("基準ファイルがありません: ${file.path}。./gradlew :core:test --tests '*LoopScenarioTest' -PupdateSnapshots で生成してください")
        assertEquals("スナップショット $name が変わりました。意図した変更か確認してから -PupdateSnapshots で再生成してください", file.readText(), actual)
    }

    /** シーンのスナップショット: エンディング、および各モデル (メイン モデル、補助キャラクター) が受け取ったリクエスト。 */
    fun render(outcome: String, models: List<Pair<String, FakeChatModel>>): String = buildString {
        appendLine("outcome: " + outcome.replace("\n", "⏎"))
        for ((role, m) in models) {
            var system: String? = null
            var tools: List<ToolSpec>? = null
            var previous: List<Message> = emptyList()
            m.requests.forEachIndexed { i, r ->
                appendLine()
                appendLine("## $role #${i + 1}")
                if (r.system != system) {
                    appendLine("system: len=${r.system.length} #${hash(r.system)} | ${oneLine(r.system, 80)}")
                    system = r.system
                }
                if (r.tools != tools) {
                    appendLine("tools: #${hash(r.tools.joinToString("\n") { it.name + "\n" + it.description + "\n" + it.parametersJsonSchema })} ${r.toolNames}")
                    tools = r.tools
                }
                // 前のリクエストと同じ始まりを持つエントリの数だけが記録されます。リクエストのプレフィックスは徐々に増加します。変更（圧縮、トリミング）すると、すべてのエントリが変更されたものからリストされます。
                val same = r.messages.zip(previous).takeWhile { (a, b) -> a == b }.size
                if (same > 0) appendLine("… $same messages unchanged")
                r.messages.drop(same).forEach { append(message(it)) }
                previous = r.messages
            }
        }
    }

    private fun message(m: Message): String = buildString {
        appendLine("${m.role.name.lowercase()}:")
        for (p in m.parts) appendLine("  " + part(p))
    }

    private fun part(p: Part): String = when (p) {
        is Part.Text -> {
            val kind = ContextBuilder.kindOf(p.text)
            if (kind == null) "text #${hash(p.text)} | ${oneLine(p.text, 120)}"
            else {
                val source = p.text.substringAfter(" source=\"").substringBefore('"')
                val body = p.text.substringAfter(">\n")
                "ctx:${kind.wire} source=\"$source\" #${hash(p.text)} | ${oneLine(body, 120)}"
            }
        }
        is Part.Thinking -> "thinking #${hash(p.text)} | ${oneLine(p.text, 80)}"
        is Part.ToolCall -> "call ${p.id} ${p.name} ${oneLine(p.argsJson, 160)}" + (p.opaque?.let { " opaque" } ?: "")
        is Part.ToolResult -> "result ${p.callId}${if (p.isError) " error" else ""} #${hash(p.content)} | ${oneLine(p.content, 200)}"
        is Part.Image -> "image ${p.mime} ${p.width}x${p.height} shot=${p.screenshotId} #${hash(p.pngBase64)}"
    }

    private fun oneLine(s: String, max: Int): String {
        val first = s.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        val more = s.trim().contains('\n')
        val clipped = if (first.length > max) first.take(max) + "…" else first
        return if (more && clipped == first) "$clipped ⏎…" else clipped
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).take(4).joinToString("") { "%02x".format(it) }
}
