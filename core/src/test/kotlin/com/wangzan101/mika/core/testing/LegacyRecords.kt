package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.record.RunEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * テスト: 古い実行レコード内の screen_text (画面読み取りによってモデルに与えられる要素テキスト) を、古いレコードを読み取るのと同じ方法で読み取ります (読み取りには番号 0 が付けられます)。
 */
fun legacyScreenText(
    atMs: Long, step: Int, app: String?, names: List<String>, site: String? = null, inputs: List<String>? = null, focused: String? = null,
): RunEvent.Observed = RunEvent.decode(buildJsonObject {
    put("type", "screen_text"); put("atMs", atMs); put("step", step)
    app?.let { put("app", it) }
    put("names", JsonArray(names.map(::JsonPrimitive)))
    site?.let { put("site", it) }
    inputs?.let { put("inputs", JsonArray(it.map(::JsonPrimitive))) }
    focused?.let { put("focused", it) }
}.toString()) as RunEvent.Observed
