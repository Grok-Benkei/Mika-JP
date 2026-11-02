package com.wangzan101.mika.core.cap.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal class McpHandshakeInfo(
    val protocolVersion: String,
    val serverName: String?,
    val instructions: String?,
    val tools: List<McpToolDescriptor>,
    val resources: List<McpResourceDescriptor>,
    val prompts: List<McpPromptDescriptor>,
)

/**
 * initialize → notifications/initialized → tools/list → resources/list、prompts/list。
 * は、JSON-RPC ロング接続 (SSE および stdio の古いバージョン) を使用する送信でのみ共有されます。 resource/prompts はオプションの機能であり、サポートしていると主張しているにもかかわらず、エラーが報告されると空にダウングレードされます。
 */
internal suspend fun mcpHandshake(
    requestedProtocol: String,
    clientName: String,
    clientVersion: String,
    request: suspend (String, JsonObject) -> JsonObject,
    notify: suspend (String) -> Unit,
): McpHandshakeInfo {
    val init = request("initialize", buildJsonObject {
        put("protocolVersion", requestedProtocol)
        putJsonObject("capabilities") {}
        putJsonObject("clientInfo") {
            put("name", clientName)
            put("version", clientVersion)
        }
    })
    val negotiated = (init["protocolVersion"] as? JsonPrimitive)?.contentOrNull ?: requestedProtocol
    val serverInfo = init["serverInfo"] as? JsonObject
    val serverName = (serverInfo?.get("name") as? JsonPrimitive)?.contentOrNull
    val instructions = (init["instructions"] as? JsonPrimitive)?.contentOrNull
    notify("notifications/initialized")

    val tools = mcPages(request, "tools/list", "tools", MAX_PAGES) { mcpToolDescriptor(it) }
    val caps = init["capabilities"] as? JsonObject
    val resources = if (caps?.get("resources") is JsonObject) {
        optionalPages(request, "resources/list", "resources") { mcpResourceDescriptor(it) }
    } else emptyList()
    val prompts = if (caps?.get("prompts") is JsonObject) {
        optionalPages(request, "prompts/list", "prompts") { mcpPromptDescriptor(it) }
    } else emptyList()
    return McpHandshakeInfo(negotiated, serverName, instructions, tools, resources, prompts)
}

/** ツール/リストは必須の機能であり、ページング エラーは接続失敗の直接の原因となります。 */
private suspend fun <T> mcPages(
    request: suspend (String, JsonObject) -> JsonObject,
    method: String,
    key: String,
    maxPages: Int,
    parse: (JsonObject) -> T,
): List<T> {
    val out = mutableListOf<T>()
    var cursor: String? = null
    var pages = 0
    do {
        val result = request(method, buildJsonObject { cursor?.let { put("cursor", it) } })
        (result[key] as? JsonArray)?.forEach { element ->
            (element as? JsonObject)?.let { out += parse(it) }
        }
        cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        pages++
    } while (cursor != null && pages < maxPages)
    return out
}

/** リソース/プロンプト オプション: サーバーがサポートしていると主張しながらエラーを報告すると、空のテーブルが返されます。これはツールの接続には影響しません。 */
private suspend fun <T> optionalPages(
    request: suspend (String, JsonObject) -> JsonObject,
    method: String,
    key: String,
    parse: (JsonObject) -> T,
): List<T> = try {
    mcPages(request, method, key, MAX_PAGES, parse)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    emptyList()
}

private const val MAX_PAGES = 20
