package com.wangzan101.mika.settings

import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.core.security.SafetyControls
import com.wangzan101.mika.core.settings.SelfConfig

/**
 * モデルではど​​のような設定を変更できますか? [FeatureFlags] はストレージです。ここでは、可逆設定のみが選択され、モデルに渡されます。
 *
 * **指定されていません**: モデルとキー (自分の頭脳を交換し、ユーザーのお金を使います)、削除されたデータ (ワークスペースをクリアし、実行環境をリセットし、会話を削除します) - 元に戻すことはできません。
 * **指定なし**: コンテナ ネットワーキングとプロキシ - 開いた後にデータが電話から離れる可能性があります。この行はユーザーが設定で決定する必要があります。
 * は簡単に元に戻すことができます。
 */
class SelfConfigImpl(
    private val flags: FeatureFlags,
) : SelfConfig {
    override fun options(): List<SelfConfig.Option> = listOf(
            SelfConfig.Option(KEY_PACE, "操作の速度", flags.operatingPace().name.lowercase(), listOf("fast", "standard", "human")),
            SelfConfig.Option(KEY_MUTE, "操作中のアプリをミュート", onOff(flags.muteTarget()), ON_OFF),
            SelfConfig.Option(KEY_THEME, "外観", flags.themeMode().name.lowercase(), listOf("system", "light", "dark")),
            SelfConfig.Option(KEY_CAPSULE, "フローティングカプセル", flags.capsuleMode().name.lowercase(), listOf("off", "duringtasks", "always")),
            SelfConfig.Option(KEY_KEEP_ON, "操作中は画面を点灯", onOff(flags.keepScreenOn()), ON_OFF),
    )

    override fun set(key: String, value: String): String? {
        if (SafetyControls.isLocked(key)) return "safety controls cannot be changed by the model"
        val v = value.trim().lowercase()
        fun onOff(): Boolean? = when (v) {
            "on", "true", "yes" -> true
            "off", "false", "no" -> false
            else -> null
        }
        fun bool(write: (Boolean) -> Unit): String? {
            val on = onOff() ?: return ON_OFF_HINT
            write(on)
            return null
        }
        return when (key) {
            KEY_PACE -> {
                val pace = OperatingPace.entries.firstOrNull { it.name.equals(v, true) }
                    ?: return "operating pace is one of fast, standard, human"
                flags.setOperatingPace(pace)
                null
            }
            KEY_MUTE -> bool { flags.setMuteTarget(it) }
            KEY_THEME -> {
                val mode = ThemeMode.entries.firstOrNull { it.name.equals(v, true) }
                    ?: return "appearance is one of system, light, dark"
                flags.setThemeMode(mode)
                null
            }
            KEY_CAPSULE -> {
                val mode = com.wangzan101.mika.capsule.CapsuleMode.entries.firstOrNull { it.name.equals(v, true) }
                    ?: return "capsule is one of off, duringtasks, always"
                flags.setCapsuleMode(mode)
                null
            }
            KEY_KEEP_ON -> bool { flags.setKeepScreenOn(it) }
            else -> "there is no such setting"
        }
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private companion object {
        val ON_OFF = listOf("on", "off")
        const val ON_OFF_HINT = "value is on or off"
        const val KEY_PACE = "operating_pace"
        const val KEY_MUTE = "mute_apps"
        const val KEY_THEME = "appearance"
        const val KEY_CAPSULE = "capsule"
        const val KEY_KEEP_ON = "keep_screen_on"
    }
}
