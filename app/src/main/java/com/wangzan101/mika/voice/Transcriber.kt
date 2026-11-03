package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelHttp
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * は、サービス プロバイダーのモデルを使用して録音をテキストに変換します。インターフェイスの形式については、[TranscribeMethod] を参照してください。
 * エラー報告およびダイアログ モデルは同じマッピング セット ([ModelHttp]) を使用し、インターフェイスは既存のステートメントを直接再利用できます。リクエストはモデル名ではなくプロトコルに従ってのみ送信されます。
 * [headers] は、各リクエストによって伝送されるヘッダーです (クライアント ID など。「ClientHeaders」を参照)。 Key ヘッダーは、インターフェイスの種類に応じてここに追加されます。
 */
class Transcriber(
    private val http: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** は WAV を転送します。失敗した場合は、[ModelError] (対話モデルと同じセット) をスローします。返されるテキストは空の文字列である可能性があります (単語が聞こえなかった)。 */
    suspend fun transcribe(
        p: Provider, key: String?, model: String, method: TranscribeMethod, wav: ByteArray, language: String?,
    ): String {
        val base = p.baseUrl.trim().trimEnd('/')
        val gemini = p.kind == ProviderConfig.Kind.Gemini
        val request = when (method) {
            TranscribeMethod.AudioTranscriptions -> {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
                    .addFormDataPart("model", model)
                    .apply { if (!language.isNullOrBlank()) addFormDataPart("language", language) }
                    .build()
                builder("$base/audio/transcriptions", key, gemini).post(body).build()
            }
            TranscribeMethod.ChatInputAudio, TranscribeMethod.ChatInputAudioUri, TranscribeMethod.ChatInputAudioOnly -> {
                val data = if (method == TranscribeMethod.ChatInputAudio) base64(wav) else "data:audio/wav;base64,${base64(wav)}"
                val payload = buildJsonObject {
                    put("model", model)
                    put("stream", false)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "input_audio")
                                    putJsonObject("input_audio") { put("data", data); put("format", "wav") }
                                })
                                // 音訳に特化したモデルはテキスト ブロックを受け入れません。一般的なモデルにはこのコマンドはなく、チャットの返信として扱われます。
                                if (method != TranscribeMethod.ChatInputAudioOnly) {
                                    add(buildJsonObject { put("type", "text"); put("text", INSTRUCTION) })
                                }
                            })
                        })
                    })
                }
                builder("$base/chat/completions", key, gemini).post(payload.toString().toRequestBody(JSON)).build()
            }
            TranscribeMethod.GeminiInline -> {
                val payload = buildJsonObject {
                    put("contents", buildJsonArray {
                        add(buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    putJsonObject("inline_data") { put("mime_type", "audio/wav"); put("data", base64(wav)) }
                                })
                                add(buildJsonObject { put("text", INSTRUCTION) })
                            })
                        })
                    })
                }
                // は会話アダプターと同じです。インベントリ アドレスには /v1beta が含まれていません。ここに追加してください。
                builder("$base/v1beta/models/$model:generateContent", key, gemini).post(payload.toString().toRequestBody(JSON)).build()
            }
        }
        val reply = ModelHttp.send(http, request)
        if (!reply.ok) {
            throw if (gemini) ModelHttp.geminiError(reply.status, reply.retryAfter, reply.body, model)
            else ModelHttp.openAiError(reply.status, reply.retryAfter, reply.body, model)
        }
        return parse(method, reply.body)
    }

    /**
     * モデルを選択するときに切り替えを試します。インターフェイス タイプごとに順番に試し、最初に合格したものを返します。それが機能しない場合は、最後のエラーがスローされます。
     * は = を通じて 2xx を返し、それを解析してテキスト フィールドを取得します (空の文字列もカウントされます)。キーの問題 (401/403/limit) については、他の方法を試しても無駄です。そのまま捨ててください。
     */
    suspend fun detect(p: Provider, key: String?, model: String): TranscribeMethod {
        val order = if (p.kind == ProviderConfig.Kind.Gemini) listOf(TranscribeMethod.GeminiInline)
        // は指示なしで音声のみを送信し、最後に配置されます。一般的なモデルは 2xx (録音をチャットとして扱って段落に返信) にも応答するため、最初にランク付けされた場合は誤って選択されます。
        else listOf(
            TranscribeMethod.AudioTranscriptions, TranscribeMethod.ChatInputAudio,
            TranscribeMethod.ChatInputAudioUri, TranscribeMethod.ChatInputAudioOnly,
        )
        val wav = Wav.silence(1000)
        var last: ModelError? = null
        for (method in order) {
            try {
                transcribe(p, key, model, method, wav, null)
                return method
            } catch (e: ModelError) {
                last = e
                if (!triesNext(e)) throw e
            }
        }
        throw last ?: ModelError.BadResponse("no method")
    }

    /** 「このインターフェースがこのように使用されていない」場合にのみ、次のインターフェースに変更します (パスが見つからない、メソッドが間違っている、リクエストに一貫性がなく、応答がこの形式ではない)。 */
    private fun triesNext(e: ModelError): Boolean = e is ModelError.ModelNotFound || e is ModelError.BadResponse

    private fun builder(url: String, key: String?, gemini: Boolean): Request.Builder = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
        if (!key.isNullOrEmpty()) { if (gemini) header("x-goog-api-key", key) else header("Authorization", "Bearer $key") }
    }

    private fun parse(method: TranscribeMethod, body: String): String {
        val root = try { json.parseToJsonElement(body) as? JsonObject } catch (e: Exception) { null }
            ?: throw ModelError.BadResponse("not json: ${body.take(120)}")
        val text = when (method) {
            TranscribeMethod.AudioTranscriptions -> root.str("text")
            TranscribeMethod.ChatInputAudio, TranscribeMethod.ChatInputAudioUri, TranscribeMethod.ChatInputAudioOnly -> {
                val msg = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
                when (val c = msg?.get("content")) {
                    is JsonPrimitive -> c.contentOrNull
                    // の内容はブロックの配列です。テキスト ブロックをまとめます。
                    is JsonArray -> c.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("")
                    else -> null
                }
            }
            TranscribeMethod.GeminiInline -> {
                val cand = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                val parts = (cand?.get("content") as? JsonObject)?.get("parts") as? JsonArray
                // テキスト ブロックがない場合 (たとえば、ミュート時に終了理由のみが返される場合)、空の文字列を押します。候補がない場合は、形式が間違っています。
                if (cand == null) null else parts.orEmpty().mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("")
            }
        }
        return (text ?: throw ModelError.BadResponse("no text field: ${body.take(120)}")).trim()
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun base64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    companion object {
        private val JSON = "application/json".toMediaType()

        /** モデルに送信される指示: 元の言葉だけで、説明はありません (モデルは英語です)。 */
        private const val INSTRUCTION = "Transcribe this recording word for word. Output only the transcript text."

        private val SPEECH_TOKENS = listOf("asr", "transcribe", "whisper", "paraformer", "sensevoice", "stt", "speech")

        /** この会社は音声からテキストへの変換を行うことができますか? OpenAI と Key、Responses、および Gemini の互換性は問題ありません。 Anthropic とアカウント ログインはそうではありません。 */
        fun supports(p: Provider): Boolean = when (p.kind) {
            ProviderConfig.Kind.OpenAiCompat, ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.Gemini -> p.signIn == null
            else -> false
        }

        /**
         * リストは音声モデルのように見えます。名前は英数字以外の文字に従ってセグメントに分割され、1 つのセグメントは asr、trancribe、wissers などで始まります。
         * のモデルリストには「対話モデルかどうか」のみ記載されています。個別のボイスマークがないため、名前のみが表示されます。
         */
        fun looksLikeSpeech(m: ListedModel): Boolean =
            m.id.lowercase().split(Regex("[^a-z0-9]+")).any { seg -> SPEECH_TOKENS.any { seg.startsWith(it) } }
    }
}
