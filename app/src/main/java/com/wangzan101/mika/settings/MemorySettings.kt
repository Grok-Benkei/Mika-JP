package com.wangzan101.mika.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 「メモリとスキル」の 3 つのスイッチ: [useMemory] マスター スイッチ (オフ = 取得なし、注入なし、抽出なし、メモリ ツールなし)。
 * [autoRemember] "私が言ったことを自動的に記憶します"; [learnSkills] 「完了したタスクからスキルを学習する」 (デフォルトでは、ユーザーは使用する前に学習した内容を受け入れる必要があります)。
 */
data class MemorySwitches(val useMemory: Boolean = true, val autoRemember: Boolean = true, val learnSkills: Boolean = true)

/**
 * スイッチは小さな JSON ファイルとして保存されます。読めない場合はデフォルト値を押してください。
 * 旧バージョンで保存されているファイルはありません。 [LEARN_DEFAULT_APPLIED]: 当時、「スキルの学習」はデフォルトでオフになっており、アップグレード後に読書時にオンになりました。その後、保存するとすぐに持ち込まれ、ユーザーが手動で電源をオフにした後もオフのままでした。
 */
class MemorySettings(private val file: File) {
    private val _switches = MutableStateFlow(read())
    val switches: StateFlow<MemorySwitches> = _switches.asStateFlow()

    @Synchronized fun update(transform: (MemorySwitches) -> MemorySwitches) {
        val next = transform(_switches.value)
        file.parentFile?.mkdirs()
        file.writeText(buildJsonObject {
            put("useMemory", next.useMemory); put("autoRemember", next.autoRemember); put("learnSkills", next.learnSkills)
            put(LEARN_DEFAULT_APPLIED, true)
        }.toString())
        _switches.value = next
    }

    private fun read(): MemorySwitches {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return MemorySwitches()
        fun b(k: String, d: Boolean) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: d
        val def = MemorySwitches()
        val upgraded = b(LEARN_DEFAULT_APPLIED, false)
        return MemorySwitches(b("useMemory", def.useMemory), b("autoRemember", def.autoRemember),
            if (upgraded) b("learnSkills", def.learnSkills) else def.learnSkills)
    }

    private companion object {
        const val LEARN_DEFAULT_APPLIED = "learnDefaultApplied"
    }
}
