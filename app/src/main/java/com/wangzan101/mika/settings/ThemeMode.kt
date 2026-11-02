package com.wangzan101.mika.settings

/** インターフェイスの色: システムに従います (デフォルト)、常に明るい、常に暗い。 */
enum class ThemeMode {
    System, Light, Dark;

    companion object {
        fun parse(s: String?): ThemeMode = entries.firstOrNull { it.name == s } ?: System
    }
}
