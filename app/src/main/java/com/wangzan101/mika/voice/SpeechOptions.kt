package com.wangzan101.mika.voice

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.Provider

/**
 * 設定 › モデルの「音声テキスト変換」で使用される純粋なロジック: 選択ページでサービス プロバイダーをグループ化する方法と、使用状況カードの右側に何を書くか。
 * はインターフェイスやネットワークに触れないため、直接テストできます。
 */
object SpeechOptions {
    /**
     * ページ内のグループを選択します: [models] 音声モデルのみを表示します。各グループの下に別の手動エントリがあります (インターフェイスが担当します)。
     * サービス プロバイダーのモデル リストは不完全であることが多く、特に音声モデルは見落とされやすいか、リストがまったく提供されない場合があります。
     */
    data class Group(val provider: Provider, val models: List<String>)

    /** 文字起こしできるサービス プロバイダーのグループは 1 つあります。音声モデルを持つものだけがリストに残ります。 */
    fun groups(providers: List<Provider>): List<Group> = providers.filter(Transcriber::supports).map { p ->
        Group(p, p.models.orEmpty().filter(Transcriber::looksLikeSpeech).map { it.id })
    }

    /** カードの右側の 2 行: [title] は選択されたもの、[sub] は補足 (利用できない場合があります)、[subOk] は true の場合「利​​用可能」の緑色で補足されます。 */
    data class Summary(val title: String, val sub: String?, val subOk: Boolean)

    fun summary(choice: SpeechChoice, system: SystemCapability, providers: List<Provider>): Summary {
        val unset = Summary(tr(R.string.settings_not_set), null, false)
        // システムは「はい」と言っていますが、試しても応答がありません
        val silent = Summary(tr(R.string.speech_system), tr(R.string.speech_system_silent), false)
        return when (choice) {
            SpeechChoice.Off -> Summary(tr(R.string.models_off), null, false)
            is SpeechChoice.Doubao -> Summary(tr(R.string.speech_doubao_model), tr(R.string.speech_doubao_provider), false)
            SpeechChoice.Auto -> when {
                system.usable -> systemSummary(system)
                system.available -> silent
                else -> unset
            }
            SpeechChoice.System -> when {
                // 携帯電話と一緒に持ち込むことにしたのは明らかですが、今は使えません。実を言うと、黙って別の携帯電話に変更しないでください。
                system.usable -> systemSummary(system)
                system.available -> silent
                else -> Summary(tr(R.string.speech_system), tr(R.string.speech_system_gone), false)
            }
            is SpeechChoice.Provider -> {
                val p = providers.firstOrNull { it.id == choice.providerId } ?: return unset
                Summary(choice.model, p.name, false)
            }
        }
    }

    private fun systemSummary(system: SystemCapability) = Summary(
        tr(R.string.speech_system),
        tr(if (system.onDevice) R.string.speech_system_offline else R.string.speech_system_online),
        subOk = system.onDevice,
    )
}
