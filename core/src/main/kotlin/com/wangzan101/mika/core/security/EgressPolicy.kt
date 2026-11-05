package com.wangzan101.mika.core.security

/**
 * 画面・通知・OCR・スクリーンショットをモデルへ出す共通判定。
 * 画面全体が安全だと説明できないときは送らない。ぼかして送ることはしない。
 * モデルやツール結果からこの判定を無効にはできない。
 */
data class EgressWindow(
    val packageName: String? = null,
    val webUrl: String? = null,
    val isWeb: Boolean = false,
    val isWebView: Boolean = false,
    val hasPassword: Boolean = false,
    /** 要素やウィンドウがある。空の仮想画面は false。 */
    val hasContent: Boolean = false,
    /** ボタン等の名前が支払い・認証。本文中の偶然の単語は対象にしない。 */
    val paymentCue: Boolean = false,
)

data class EgressScene(
    val primary: EgressWindow,
    val others: List<EgressWindow> = emptyList(),
)

data class EgressVerdict(
    val allowBody: Boolean,
    val allowScreenshot: Boolean,
    val reason: String,
) {
    val allowsAll: Boolean get() = allowBody && allowScreenshot

    companion object {
        fun deny(reason: String) = EgressVerdict(false, false, reason)
        val AppScreen = EgressVerdict(true, true, "app_screen")
    }
}

object EgressPolicy {
    /** モデル出力では常に拒否。保護を切るスイッチは無い。 */
    fun refusesModelOverride(text: String): Boolean = true

    fun assess(scene: EgressScene): EgressVerdict {
        val windows = listOf(scene.primary) + scene.others
        val reasons = windows.mapNotNull { unsafe(it) }
        if (reasons.isEmpty()) {
            if (scene.primary.packageName.isNullOrBlank()) {
                return EgressVerdict(allowBody = true, allowScreenshot = false, reason = "no_app_open")
            }
            return EgressVerdict.AppScreen
        }
        if (scene.others.isNotEmpty()) return EgressVerdict.deny("multiwindow_unproven")
        return EgressVerdict.deny(reasons.first())
    }

    fun notification(packageName: String?): EgressVerdict {
        val pkg = packageName?.trim()?.lowercase()
        if (pkg.isNullOrBlank()) return EgressVerdict.deny("unknown_package")
        if (isBrowser(pkg) || SensitiveApps.blocks(pkg) || looksSensitive(pkg)) {
            return EgressVerdict.deny("sensitive_notification")
        }
        return EgressVerdict(allowBody = true, allowScreenshot = false, reason = "notification_ok")
    }

    /** 履歴・再送。安全だと再判定できない画面本文は出さない。 */
    fun replayBody(untrusted: Boolean, source: String?, place: String?): Boolean {
        if (!untrusted) return true
        val src = source?.lowercase().orEmpty()
        val where = place?.lowercase().orEmpty()
        if (src == "web" || src == "phone" || where.startsWith("http") || isBrowser(where)) return false
        return true
    }

    private fun unsafe(w: EgressWindow): String? {
        if (w.hasPassword) return "password_visible"
        if (w.paymentCue) return "payment_or_auth"
        if (w.isWeb || w.isWebView) {
            val host = hostOf(w.webUrl)
            if (host != null && looksLikeBankHost(host)) return "bank_page"
            return "browser_or_webview"
        }
        val pkg = w.packageName?.trim()?.lowercase()
        if (pkg.isNullOrBlank()) return if (w.hasContent || w.isWeb || w.isWebView) "unknown_package" else null
        if (isBrowser(pkg)) return "browser_or_webview"
        if (SensitiveApps.blocks(pkg) || looksSensitive(pkg)) return "sensitive_app"
        return null
    }

    fun isBrowser(packageName: String): Boolean {
        val pkg = packageName.trim().lowercase()
        if (pkg in BROWSERS) return true
        return BROWSER_PREFIXES.any { pkg == it.removeSuffix(".") || pkg.startsWith(it) }
    }

    private fun looksSensitive(pkg: String): Boolean {
        val parts = pkg.split('.', '_', '-')
        return parts.any { it in SENSITIVE_SEGMENTS }
    }

    private fun looksLikeBankHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h in BANK_HOSTS) return true
        val labels = h.split('.')
        return labels.any { it in SENSITIVE_SEGMENTS || it == "bank" }
    }

    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val rest = url.trim().substringAfter("://", url.trim())
        return rest.substringBefore('/').substringBefore(':').substringBefore('?').ifBlank { null }
    }

    private val SENSITIVE_SEGMENTS = setOf(
        "bank", "banking", "wallet", "payment", "payments", "authenticator",
        "password", "passwd", "passwords", "bitwarden", "lastpass", "dashlane", "1password",
    )

    private val BANK_HOSTS = setOf(
        "paypal.com", "www.paypal.com", "chase.com", "www.chase.com",
        "wellsfargo.com", "www.wellsfargo.com", "bankofamerica.com",
        "smbc.co.jp", "www.smbc.co.jp", "mizuhobank.co.jp", "rakuten-bank.co.jp",
    )

    private val BROWSERS = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.google.android.apps.chrome",
        "com.android.browser",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "org.mozilla.fenix",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.brave.browser",
        "com.kiwibrowser.browser",
        "com.ucmobile.intl",
    )

    private val BROWSER_PREFIXES = listOf(
        "com.android.chrome.",
        "com.google.android.apps.chrome.",
        "org.mozilla.firefox.",
        "com.sec.android.app.sbrowser.",
    )
}
