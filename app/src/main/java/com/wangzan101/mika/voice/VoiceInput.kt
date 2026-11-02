package com.wangzan101.mika.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderStore

/** インターフェイス (入力フィールド、フローティング カプセル) は音声会話が行われる場所です。アプリは [VoiceInput] で、インターフェース テストは偽のものに置き換えられています。 */
interface VoiceSource {
    fun spec(): SpeechEngineSpec
    fun start(language: String? = null): SpeechSession
}

/**
 * 音声入力への入り口: ダイアログページの入力ボックスとフローティングカプセルにはここでのみアクセスできます。
 * まず、ユーザーの選択、携帯電話の機能、既存のサービスに従ってエンジン ([spec]) を設定し、次にセッション ([start]) を開きます。
 * [transcriber] 転記者はサービスプロバイダーに応じて指定されます。各社のリクエストヘッダー（クライアントID、必要なセッションヘッダー）は異なります。
 */
class VoiceInput(
    context: Context,
    private val settings: SpeechSettings,
    private val providers: ProviderStore,
    private val transcriber: (Provider) -> Transcriber = { p -> Transcriber(ProviderStore.modelHttp, providers.clientHeaders(p)) },
) : VoiceSource {
    private val context = context.applicationContext

    /** インターフェイスは、最初に、それが使用できるかどうか、およびどれが使用されるかを尋ねます (カード上部の小さなラベル)。 */
    override fun spec(): SpeechEngineSpec =
        SpeechResolver.resolve(settings.choice.value, SystemSpeech.capability(context, settings.systemSilent.value), providers.providers.value)

    /**
     * セッションを開き、押されたときに呼び出します (メインスレッド内にある必要があります)。 [language] は認識言語 (BCP-47) です。 null はアプリのインターフェース言語です。
     * に記録権限がなく、エンジンが使用できない場合は、失敗セッションが 1 つだけ送信され、インターフェイスは同様に処理されます。
     */
    override fun start(language: String?): SpeechSession {
        val spec = spec()
        if (spec is SpeechEngineSpec.Unavailable) return FailedSession(SpeechFailure.Unavailable(spec.why))
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return FailedSession(SpeechFailure.NoPermission)
        }
        val tag = language ?: SystemSpeech.speechTag(AppLanguage.current(context))
        return when (spec) {
            is SpeechEngineSpec.Doubao -> {
                val credentials = settings.doubaoCredentials()
                    ?: return FailedSession(SpeechFailure.Unavailable(SpeechEngineSpec.Why.DoubaoNotConfigured))
                DoubaoSpeech(PcmRecorder(streaming = true), { DoubaoTransport().connect(credentials, spec.resourceId) })
            }
            is SpeechEngineSpec.System -> SystemSpeechSession(
                context, spec.onDevice, tag,
                onNoResponse = settings::noteSystemSilent, onAnswered = settings::noteSystemAnswered,
                attempts = SystemSpeech.attempts(SystemSpeech.services(context), settings.systemService),
            )
            is SpeechEngineSpec.Provider -> {
                val p = spec.provider
                val t = transcriber(p)
                val iso = SystemSpeech.isoCode(tag)
                ProviderSpeech(PcmRecorder(), { wav ->
                    t.transcribe(p, providers.providerKey(p.id), spec.model, spec.method, wav, iso)
                })
            }
            is SpeechEngineSpec.Unavailable -> FailedSession(SpeechFailure.Unavailable(spec.why))
        }
    }
}
