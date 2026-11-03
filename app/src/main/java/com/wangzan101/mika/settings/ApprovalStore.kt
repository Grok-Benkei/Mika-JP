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
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * クエリ / 自律 (新しいタスクの開始時に取得)、小さな JSON ファイルとして保存します。理解できない場合は、「クエリ」を押してください。
 * 最初に一時ファイルを書き込んでから、名前を変更します。ファイルの半分を書き込んだ後は、停電によって不良ファイルが残ることはありません。
 */
class ApprovalStore(private val file: File) {
    private val _autonomous: MutableStateFlow<Boolean>

    init {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        _autonomous = MutableStateFlow((o?.get("autonomous") as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    /** 自律モード: 開くときに、最初に支払いを要求されることさえありません (要求する場合は支払いのみが最初に要求されます)。変更するには、[ApprovalControl.setAutonomous] に移動してください。それに応じて、実行中のタスクが変更されます。 */
    val autonomous: StateFlow<Boolean> = _autonomous.asStateFlow()

    @Synchronized fun setAutonomous(on: Boolean) {
        _autonomous.value = on
        save()
    }

    @Synchronized private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(buildJsonObject { put("autonomous", _autonomous.value) }.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
