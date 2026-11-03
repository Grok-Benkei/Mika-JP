package com.wangzan101.mika.core.prompt

import com.wangzan101.mika.core.model.ToolSpec
import java.security.MessageDigest

/** このタスクのプロンプトのバージョン: システムプロンプトとツール定義の要約で、実行記録に書き込む; 比較評価の際にこれでプロンプトのバージョンを区別する。 */
object PromptVersion {
    fun of(system: String, tools: List<ToolSpec>): String {
        val text = system + tools.joinToString("") { "\n\n" + it.name + "\n" + it.description + "\n" + it.parametersJsonSchema }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
    }
}
