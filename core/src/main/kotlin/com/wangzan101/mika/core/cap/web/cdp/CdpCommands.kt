package com.wangzan101.mika.core.cap.web.cdp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

// は、一般的に使用されるいくつかのコマンドを 1 つのレイヤーにラップするだけで、手書きのパラメーター名や解体結果によって引き起こされるエラーの可能性を排除します。他のコマンドは CdpSession.send を直接使用します。

/**
 * `Page.frameNavigated` パラメータ [p] は、メイン フレーム [frameId] を指します。ドキュメントが変更されました (ドキュメントが変更された場合、内部の分離世界は失われます)。
 * 同じ文書内のアドレスの変更は別のイベントであり、カウントされません。 [frameId] が null の場合はカウントされません。
 */
fun mainFrameNavigated(p: JsonObject, frameId: String?): Boolean {
    val frame = p["frame"] as? JsonObject ?: return false
    if (frame["parentId"] != null) return false
    return frameId != null && frame["id"]?.jsonPrimitive?.contentOrNull == frameId
}

/** ページのスクリプトが例外をスローしました。 */
class CdpEvaluationException(val details: JsonObject) :
    RuntimeException(
        details["exception"]?.jsonObject?.get("description")?.jsonPrimitive?.contentOrNull
            ?: details["text"]?.jsonPrimitive?.contentOrNull
            ?: "evaluation failed",
    )

/** `Runtime.evaluate`、RemoteObject を返します (`returnByValue` の値は `value` にあります)。スクリプト例外は [CdpEvaluationException] をスローします。 */
suspend fun CdpSession.evaluate(
    expression: String,
    sessionId: String? = null,
    awaitPromise: Boolean = true,
    returnByValue: Boolean = true,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
): JsonObject {
    val r = send(
        "Runtime.evaluate",
        buildJsonObject {
            put("expression", expression)
            put("awaitPromise", awaitPromise)
            put("returnByValue", returnByValue)
        },
        sessionId,
        timeoutMs,
    )
    (r["exceptionDetails"] as? JsonObject)?.let { throw CdpEvaluationException(it) }
    return r["result"]?.jsonObject ?: JsonObject(emptyMap())
}

/** `DOM.getDocument`、ルートノードに戻ります。 [depth] は -1 で、ツリー全体を受け取ります。 [pierce] は iframe とシャドウ ルートを貫通します。 */
suspend fun CdpSession.getDocument(
    depth: Int = -1,
    pierce: Boolean = true,
    sessionId: String? = null,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
): JsonObject = send(
    "DOM.getDocument",
    buildJsonObject {
        put("depth", depth)
        put("pierce", pierce)
    },
    sessionId,
    timeoutMs,
).getValue("root").jsonObject

/** `Accessibility.getFullAXTree`、フラット ノード リストを返します。 */
suspend fun CdpSession.getFullAXTree(
    depth: Int? = null,
    sessionId: String? = null,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
): List<JsonObject> = send(
    "Accessibility.getFullAXTree",
    buildJsonObject { if (depth != null) put("depth", depth) },
    sessionId,
    timeoutMs,
)["nodes"]?.jsonArray?.map { it.jsonObject }.orEmpty()

/** `Page.captureScreenshot`、デコードされたピクチャバイトを返します。 [format] は png/jpeg/webp です。 */
suspend fun CdpSession.captureScreenshot(
    format: String = "png",
    quality: Int? = null,
    captureBeyondViewport: Boolean = false,
    sessionId: String? = null,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
): ByteArray {
    val r = send(
        "Page.captureScreenshot",
        buildJsonObject {
            put("format", format)
            if (quality != null) put("quality", quality)
            put("captureBeyondViewport", captureBeyondViewport)
        },
        sessionId,
        timeoutMs,
    )
    return Base64.getDecoder().decode(r.getValue("data").jsonPrimitive.content)
}

/** `Input.dispatchMouseEvent`。 [type]:マウス押された / マウス解放 / マウス移動 / マウスホイール;座標は CSS ピクセルです。 */
suspend fun CdpSession.dispatchMouseEvent(
    type: String,
    x: Double,
    y: Double,
    button: String = "none",
    clickCount: Int = 0,
    modifiers: Int = 0,
    sessionId: String? = null,
    timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS,
) {
    send(
        "Input.dispatchMouseEvent",
        buildJsonObject {
            put("type", type)
            put("x", x * inputScale)
            put("y", y * inputScale)
            put("button", button)
            put("clickCount", clickCount)
            if (modifiers != 0) put("modifiers", modifiers)
        },
        sessionId,
        timeoutMs,
    )
}

/** 左クリック: 押してから持ち上げます。 */
suspend fun CdpSession.click(x: Double, y: Double, sessionId: String? = null) {
    dispatchMouseEvent("mousePressed", x, y, "left", 1, sessionId = sessionId)
    dispatchMouseEvent("mouseReleased", x, y, "left", 1, sessionId = sessionId)
}

/** `Network.enable`;後で `Network.*` イベントを受け取ります。 */
suspend fun CdpSession.enableNetwork(sessionId: String? = null, timeoutMs: Long = CdpSession.DEFAULT_TIMEOUT_MS) {
    send("Network.enable", JsonObject(emptyMap()), sessionId, timeoutMs)
}
