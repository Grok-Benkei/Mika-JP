package com.wangzan101.mika.voice

import com.wangzan101.mika.settings.Provider

/**
 * 携帯電話には認識機能が組み込まれています。システムは、携帯電話が ([available]) であるかどうか、マシン上で認識されているか (インターネットに接続されていない)、
 * であり、この電話で試したときは無視されました ([responsive]; システムは利用可能であると示し、要求は送信されましたが、コールバックが与えられなかったため、false として記録されました)。
 */
data class SystemCapability(val available: Boolean, val onDevice: Boolean, val responsive: Boolean = true) {
    /** はディクテーションに実際に使用できます。システムにはそれが備わっており、応答なしとして記録されません。 */
    val usable get() = available && responsive
}

/** 今回使用する実際の認識エンジン。選択したものが利用できない場合は、[Unavailable] とその理由が表示され、インターフェイスにプロンプ​​トが表示されます。 */
sealed interface SpeechEngineSpec {
    data class System(val onDevice: Boolean) : SpeechEngineSpec
    data class Provider(val provider: com.wangzan101.mika.settings.Provider, val model: String, val method: TranscribeMethod) : SpeechEngineSpec
    data class Doubao(val resourceId: String) : SpeechEngineSpec
    data class Unavailable(val why: Why) : SpeechEngineSpec
    /** [SystemNoResponse]: 携帯電話の内蔵識別システムは、それを持っていると言っていますが、リクエストを送信した後、応答がありません (メモされており、ユーザーが再度選択するとクリアされます)。 */
    enum class Why { Off, NoSystemNoModel, SystemGone, SystemNoResponse, ProviderGone, DoubaoNotConfigured }
}

/** は、ユーザーの選択、携帯電話の機能、既存のサービス プロバイダーを比較して、今回どのエンジンを使用するかを決定します。純粋な機能であり、システムには影響しません。 */
object SpeechResolver {
    fun resolve(choice: SpeechChoice, system: SystemCapability, providers: List<Provider>): SpeechEngineSpec = when (choice) {
        SpeechChoice.Off -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.Off)
        is SpeechChoice.Doubao -> if (choice.resourceId in DoubaoProtocol.resources) SpeechEngineSpec.Doubao(choice.resourceId)
            else SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.DoubaoNotConfigured)
        // の内蔵認識が応答しない場合は、真実を伝えて設定を依頼してください。お金のかかるサービスプロバイダーモデルに黙って切り替えないでください。
        SpeechChoice.Auto -> when {
            system.usable -> SpeechEngineSpec.System(system.onDevice)
            system.available -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)
            else -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.NoSystemNoModel)
        }
        SpeechChoice.System -> when {
            system.usable -> SpeechEngineSpec.System(system.onDevice)
            system.available -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)
            else -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemGone)
        }
        is SpeechChoice.Provider -> {
            val p = providers.firstOrNull { it.id == choice.providerId }
            if (p == null) SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.ProviderGone)
            else SpeechEngineSpec.Provider(p, choice.model, choice.method)
        }
    }
}
