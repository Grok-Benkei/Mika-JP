package com.wangzan101.mika.voice

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.settings.KeyCipher
import com.wangzan101.mika.settings.KeystoreKeyCipher
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Speech to Text のユーザー選択と設定でのそのアクセス。使用する特定のエンジンは、[SpeechResolver] と携帯電話の機能の組み合わせによって決まります。 */
sealed interface SpeechChoice {
    /** 未選択: ID が組み込まれた携帯電話がある場合は、それを使用します。 */
    data object Auto : SpeechChoice

    /** は携帯電話の内蔵 ID を明確に選択しました。 */
    data object System : SpeechChoice

    /** は音声入力を必要としません。 */
    data object Off : SpeechChoice

    /** は特定のサービス プロバイダーの特定のモデルを使用して転写され、[method] はリクエストの送信方法を決定します。 */
    data class Provider(val providerId: String, val model: String, val method: TranscribeMethod) : SpeechChoice

    /** Native streaming ASR, with credentials stored separately from this non-secret selection. */
    data class Doubao(val resourceId: String = "volc.seedasr.sauc.duration") : SpeechChoice
}

/**
 * サービスプロバイダーによるオーディオトランスコーディングのインターフェイス形式。保存されるのは名前であり、後からのみ追加でき、名前を変更したり削除したりすることはできません。古い設定のみを読み戻すことができます。
 * ChatInputAudio プレフィックスが付いている 3 つのタイプは、すべてダイアログ インターフェイスの input_audio ですが、記述方法が異なります。トライアル転送するモデルを選択するときは、すべてを順番に試してください。
 * [ChatInputAudio] のデータはbase64に入れられ、変換命令が付加されます。 [ChatInputAudioUri] のデータは次のように置き換えられます。
 * `data:audio/wav;base64,...` のデータ URI (一部のサービスはこの種類のみを認識します)。 [ChatInputAudioOnly] もデータ URI を使用します。
 * ですが、コマンドなしで音声のみを送信します (文字起こしに特化したモデルはテキスト ブロックを受け入れません)。
 */
enum class TranscribeMethod { AudioTranscriptions, ChatInputAudio, GeminiInline, ChatInputAudioUri, ChatInputAudioOnly }

/**
 * が選択され、[FeatureFlags] の設定に保存されます。キーと JSON の一部。読み出せない場合は[SpeechChoice.Auto]とみなします。
 * 「携帯電話の内蔵認識が応答しません」の別の記録 ([systemSilent]): 押しても話してもサービスが応答せず、連続して [SILENT_STRIKES] 回記録されました。
 * (スタック時間は、おそらく認識サービスのコールド スタートが遅いことが原因であるため、今後はこのパスを閉じないでください);途中で返信した場合は、もう一度数えてください。
 * ユーザーが設定で「自動」または「電話に含まれる」を選択すると、マークと時間がクリアされるため、認識サービスを変更し、システムを更新した電話は再試行できます。
 */
class SpeechSettings(private val prefs: SharedPreferences, private val cipher: KeyCipher = KeystoreKeyCipher("mika-speech-keys")) {
    private val _choice = MutableStateFlow(read())
    val choice: StateFlow<SpeechChoice> = _choice.asStateFlow()

    val hasDoubaoCredentials: Boolean get() = prefs.contains(DOUBAO_KEY)

    /** Save without making a billable network request. Both authentication generations are supported. */
    internal fun saveDoubao(credentials: DoubaoCredentials, resourceId: String) {
        require(credentials.valid && resourceId in DoubaoProtocol.resources)
        val json = buildJsonObject {
            put("apiKey", JsonPrimitive(credentials.apiKey))
            put("appId", JsonPrimitive(credentials.appId))
            put("accessKey", JsonPrimitive(credentials.accessKey))
        }.toString().toByteArray(Charsets.UTF_8)
        val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(json))
        val selected = SpeechChoice.Doubao(resourceId)
        prefs.edit().putString(DOUBAO_KEY, encrypted).putString(KEY, encode(selected).toString()).apply()
        _choice.value = selected
    }

    internal fun doubaoCredentials(): DoubaoCredentials? = runCatching {
        val encrypted = prefs.getString(DOUBAO_KEY, null) ?: return null
        val obj = Json.parseToJsonElement(String(cipher.decrypt(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)).jsonObject
        DoubaoCredentials(
            obj["apiKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            obj["appId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            obj["accessKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        ).takeIf { it.valid }
    }.getOrNull()

    fun clearDoubao() {
        prefs.edit().remove(DOUBAO_KEY).apply()
        if (_choice.value is SpeechChoice.Doubao) set(SpeechChoice.Off)
    }

    private val _systemSilent = MutableStateFlow(prefs.getBoolean(SILENT, false))
    val systemSilent: StateFlow<Boolean> = _systemSilent.asStateFlow()

    fun set(choice: SpeechChoice) {
        // これらは両方とも携帯電話によって認識されます。ユーザーが積極的に選択した場合は、もう一度チャンスを与えてください。
        val retry = choice == SpeechChoice.System || choice == SpeechChoice.Auto
        val edit = prefs.edit().putString(KEY, encode(choice).toString())
        if (retry) edit.remove(SILENT).remove(STRIKES)
        edit.apply()
        _choice.value = choice
        if (retry) _systemSilent.value = false
    }

    /** 携帯電話の組み込み認識は、今回はコールバックを提供しませんでした。[SILENT_STRIKES] 時間まで応答しないことが記録されました。 */
    fun noteSystemSilent() {
        val strikes = prefs.getInt(STRIKES, 0) + 1
        val edit = prefs.edit().putInt(STRIKES, strikes)
        if (strikes >= SILENT_STRIKES) edit.putBoolean(SILENT, true)
        edit.apply()
        if (strikes >= SILENT_STRIKES) _systemSilent.value = true
    }

    /**
     * 点呼テストに合格した識別サービス (システムはデフォルトでは応答せず、応答します)、次回最初に試してください。 null は、存在しないこと、またはシステムがデフォルトで応答することを意味します。
     * [SystemSpeech.attempts] を参照。
     */
    val systemService: String? get() = prefs.getString(SERVICE, null)

    /** 携帯電話には、今回通話に応答したことを示す認識機能が組み込まれています ([service] が通話に応答した人、null がシステムのデフォルトです)。前回の無応答回数は無効であり、コールバックのサービスは記憶されています。 */
    fun noteSystemAnswered(service: String? = null) {
        val edit = prefs.edit()
        var changed = false
        if (prefs.getInt(STRIKES, 0) != 0) { edit.remove(STRIKES); changed = true }
        if (service != systemService) {
            if (service == null) edit.remove(SERVICE) else edit.putString(SERVICE, service)
            changed = true
        }
        if (changed) edit.apply()
    }

    private fun read(): SpeechChoice {
        val raw = prefs.getString(KEY, null) ?: return SpeechChoice.Auto
        return runCatching { decode(Json.parseToJsonElement(raw).jsonObject) }.getOrNull() ?: SpeechChoice.Auto
    }

    private fun encode(choice: SpeechChoice): JsonObject = buildJsonObject {
        when (choice) {
            SpeechChoice.Auto -> put(TYPE, JsonPrimitive("auto"))
            SpeechChoice.System -> put(TYPE, JsonPrimitive("system"))
            SpeechChoice.Off -> put(TYPE, JsonPrimitive("off"))
            is SpeechChoice.Doubao -> {
                put(TYPE, JsonPrimitive("doubao"))
                put("resourceId", JsonPrimitive(choice.resourceId))
            }
            is SpeechChoice.Provider -> {
                put(TYPE, JsonPrimitive("provider"))
                put("providerId", JsonPrimitive(choice.providerId))
                put("model", JsonPrimitive(choice.model))
                put("method", JsonPrimitive(choice.method.name))
            }
        }
    }

    /** フィールドが欠落している場合、型が認識されない場合、またはメソッド名が存在しない場合は、null が返され、呼び出し側サイトによって自動的に返されます。 */
    private fun decode(obj: JsonObject): SpeechChoice? = when (obj[TYPE]?.jsonPrimitive?.contentOrNull) {
        "auto" -> SpeechChoice.Auto
        "system" -> SpeechChoice.System
        "off" -> SpeechChoice.Off
        "doubao" -> obj["resourceId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in DoubaoProtocol.resources }?.let { SpeechChoice.Doubao(it) }
        "provider" -> {
            val id = obj["providerId"]?.jsonPrimitive?.contentOrNull
            val model = obj["model"]?.jsonPrimitive?.contentOrNull
            val method = TranscribeMethod.entries.firstOrNull { it.name == obj["method"]?.jsonPrimitive?.contentOrNull }
            if (id == null || model == null || method == null) null else SpeechChoice.Provider(id, model, method)
        }
        else -> null
    }

    companion object {
        private const val KEY = "speech_choice"
        private const val DOUBAO_KEY = "speech_doubao_credentials_encrypted"
        private const val TYPE = "type"
        private const val SILENT = "speech_system_silent"
        private const val STRIKES = "speech_system_silent_strikes"
        private const val SERVICE = "speech_system_service"

        /** は、何度も応答しなかった後、それに注目しました。 */
        const val SILENT_STRIKES = 2

        fun get(context: Context) = SpeechSettings(
            context.applicationContext.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE),
        )
    }
}
