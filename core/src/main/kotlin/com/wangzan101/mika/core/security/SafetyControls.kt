package com.wangzan101.mika.core.security

import com.wangzan101.mika.core.action.ApprovalSettings

/**
 * モデル出力と画面上の文字は安全制御を変更できない。
 * 承認モードの既定は [ApprovalSettings.ASK]（autonomous = false）。
 */
object SafetyControls {
    fun isLocked(key: String): Boolean {
        val k = key.trim().lowercase()
        if (k.isEmpty()) return false
        if (k in LOCKED) return true
        return k.contains("approval") || k.contains("autonomous") || k.contains("accessibility") || k.contains("cleartext")
    }

    /** 画面やモデルの文面を適用しても承認設定は変わらない。 */
    fun applyUntrusted(text: String, settings: ApprovalSettings): ApprovalSettings = settings

    private val LOCKED = setOf(
        "approval",
        "autonomous",
        "approval_mode",
        "accessibility",
        "mcp",
        "mcp_enabled",
        "allow_lan_cleartext",
        "cleartext",
        "write_secure_settings",
        "network_security",
        "debuggable",
    )
}
