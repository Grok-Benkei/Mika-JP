package com.wangzan101.mika.core.net

/**
 * 平文 HTTP を LAN へ出すかどうか。既定は拒否。
 * ループバック（Ollama / LM Studio）はこのスイッチの対象外。
 * アプリ起動時に保存済みの値を読み、設定の変更で更新する。
 */
object CleartextPolicy {
    @Volatile private var allowLan = false

    fun allowLanHttp(): Boolean = allowLan

    fun setAllowLanHttp(on: Boolean) {
        allowLan = on
    }
}
