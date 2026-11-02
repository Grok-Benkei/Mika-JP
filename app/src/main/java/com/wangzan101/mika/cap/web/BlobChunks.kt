package com.wangzan101.mika.cap.web

/**
 * BLOB コンテンツ (base64) がページ セグメントによって返されました。このページで報告されている長さ、各段落の長さ、および累計の長さは信じられません。
 * によって報告される長さが、base64 に変換された [maxBytes] の上限 (3 バイトあたり 4 ワード、およびパディングマージン) を超える場合、割り当て前に拒否されます。
 * はメッセージの長さに応じて事前に割り当てられません。各セグメントは最大 [chunk] ワードであり、合計の長さはメッセージの長さを超えません。
 */
internal class BlobChunks(private val declared: Int, private val maxBytes: Long, private val chunk: Int = CHUNK) {
    sealed interface Outcome {
        class Ok(val bytes: ByteArray) : Outcome
        class Failed(val error: String) : Outcome
    }

    private val sb = StringBuilder()

    /** 報告された長さが受け入れられるかどうか。そうでない場合は、その理由を教えてください。 */
    fun refusal(): String? = when {
        declared < 0 -> UNREADABLE
        declared > maxBase64(maxBytes) -> WebDownloads.TOO_LARGE
        else -> null
    }

    val received: Int get() = sb.length
    val complete: Boolean get() = sb.length >= declared

    /** は次のセグメントを受け入れます。長すぎる場合、または報告された長さを超える場合は、失敗の原因となります。 */
    fun add(part: String): String? {
        if (part.length > chunk) return UNREADABLE
        if (sb.length.toLong() + part.length > declared) return UNREADABLE
        sb.append(part)
        return null
    }

    fun finish(): Outcome {
        val bytes = runCatching { java.util.Base64.getDecoder().decode(sb.toString()) }.getOrNull() ?: return Outcome.Failed(UNREADABLE)
        if (bytes.size > maxBytes) return Outcome.Failed(WebDownloads.TOO_LARGE)
        return Outcome.Ok(bytes)
    }

    companion object {
        /** は page.js の BLOB_CHUNK と一致します。 */
        const val CHUNK = 1 shl 20
        const val UNREADABLE = "unreadable"

        fun maxBase64(maxBytes: Long): Long = maxBytes * 4 / 3 + 8
    }
}
