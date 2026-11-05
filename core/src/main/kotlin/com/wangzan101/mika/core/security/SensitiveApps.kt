package com.wangzan101.mika.core.security

/**
 * 銀行・認証アプリ・パスワードマネージャ。スクリーンショットと通知本文の送信を止める。
 * パッケージが分からない送信は閉じる（フェイルクローズ）。
 */
object SensitiveApps {
    fun blocks(packageName: String?): Boolean {
        val pkg = packageName?.trim()?.lowercase() ?: return false
        if (pkg in EXACT) return true
        return PREFIXES.any { pkg == it.removeSuffix(".") || pkg.startsWith(it) }
    }

    /** スクリーンショットまたは通知本文をモデルへ送ってよいか。不明なパッケージは拒否。 */
    fun withholdSend(packageName: String?): Boolean = packageName.isNullOrBlank() || blocks(packageName)

    /** Web ページは URL が識別子。電話画面でパッケージが無い、または機密アプリなら送らない。 */
    fun withholdScreenshot(packageName: String?, isWeb: Boolean): Boolean =
        !isWeb && withholdSend(packageName)

    private val EXACT = setOf(
        "com.google.android.apps.authenticator2",
        "com.azure.authenticator",
        "com.authy.authy",
        "com.beemdevelopment.aegis",
        "org.fedorahosted.freeotp",
        "com.onepassword.android",
        "com.agilebits.onepassword",
        "com.lastpass.lpandroid",
        "com.x8bit.bitwarden",
        "com.bitwarden.authenticator",
        "com.dashlane",
        "com.keepersecurity.passwordmanager",
        "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "jp.co.smbc.direct",
        "jp.co.mizuhobank.banking",
        "jp.co.rakuten_bank.rakutenbank",
        "jp.mufg.bk.appli",
        "jp.co.netbk",
        "com.chase.sig.android",
        "com.wf.wellsfargo",
        "com.infonow.bofa",
    )

    private val PREFIXES = listOf(
        "com.onepassword.",
        "com.agilebits.",
        "com.lastpass.",
        "com.bitwarden.",
        "com.x8bit.bitwarden",
        "com.dashlane.",
        "com.authy.",
        "com.beemdevelopment.aegis",
    )
}
