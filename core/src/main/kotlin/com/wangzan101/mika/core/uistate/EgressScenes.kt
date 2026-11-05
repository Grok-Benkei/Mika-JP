package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.security.EgressScene
import com.wangzan101.mika.core.security.EgressWindow

fun ScreenSnapshot.egressScene(): EgressScene {
    val webView = elements.any { it.role.contains("webview", ignoreCase = true) } ||
        windows.any { it.nodes.any(::nodeIsWebView) }
    val password = elements.any { it.secret } || windows.any { it.nodes.any(::nodeHasPassword) }
    val payment = elements.any { paymentCue(it.role, it.name) } || windows.any { it.nodes.any(::nodePaymentCue) }
    val primary = EgressWindow(
        packageName = packageName ?: unreadableApp,
        webUrl = web?.url,
        isWeb = web != null,
        isWebView = web != null || webView,
        hasPassword = password,
        hasContent = hasWindow || elements.isNotEmpty() || web != null || !unreadableApp.isNullOrBlank() || windows.isNotEmpty(),
        paymentCue = payment,
    )
    val others = windows.map { w ->
        EgressWindow(
            packageName = w.packageName,
            isWebView = w.nodes.any(::nodeIsWebView),
            hasPassword = w.nodes.any(::nodeHasPassword),
            hasContent = true,
            paymentCue = w.nodes.any(::nodePaymentCue),
        )
    } + listOfNotNull(opened?.packageName?.let { EgressWindow(packageName = it, hasContent = true) })
    return EgressScene(primary, others)
}

private fun nodeIsWebView(n: UiTreeNode): Boolean =
    n.role.contains("webview", ignoreCase = true) || n.children.any(::nodeIsWebView)

private fun nodeHasPassword(n: UiTreeNode): Boolean = n.secret || n.children.any(::nodeHasPassword)

private fun nodePaymentCue(n: UiTreeNode): Boolean =
    paymentCue(n.role, listOfNotNull(n.text, n.description, n.hintText).joinToString(" ")) || n.children.any(::nodePaymentCue)

private fun paymentCue(role: String, name: String): Boolean {
    if (role.equals("text", ignoreCase = true)) return false
    val n = name.lowercase()
    return PAYMENT_CUES.any { n.contains(it) }
}

private val PAYMENT_CUES = listOf("支付", "支払い", "決済", "password", "パスワード", "暗証", "cvv", "cvc", "sign in", "ログイン")
