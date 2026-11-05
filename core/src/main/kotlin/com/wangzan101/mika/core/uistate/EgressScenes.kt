package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.security.EgressScene
import com.wangzan101.mika.core.security.EgressWindow

fun ScreenSnapshot.egressScene(): EgressScene {
    val webView = elements.any { it.role.contains("webview", ignoreCase = true) } ||
        windows.any { it.nodes.any(::nodeIsWebView) }
    val password = elements.any { it.secret } || windows.any { it.nodes.any(::nodeHasPassword) }
    val primary = EgressWindow(
        packageName = packageName ?: unreadableApp,
        webUrl = web?.url,
        isWeb = web != null,
        isWebView = web != null || webView,
        hasPassword = password,
        hasContent = hasWindow || elements.isNotEmpty() || web != null || !unreadableApp.isNullOrBlank() || windows.isNotEmpty(),
    )
    val self = packageName ?: unreadableApp
    val others = windows.filter { it.packageName != self }.map { w ->
        EgressWindow(
            packageName = w.packageName,
            isWebView = w.nodes.any(::nodeIsWebView),
            hasPassword = w.nodes.any(::nodeHasPassword),
            hasContent = true,
        )
    } + listOfNotNull(opened?.packageName?.takeIf { it != self }?.let { EgressWindow(packageName = it, hasContent = true) })
    return EgressScene(primary, others)
}

private fun nodeIsWebView(n: UiTreeNode): Boolean =
    n.role.contains("webview", ignoreCase = true) || n.children.any(::nodeIsWebView)

private fun nodeHasPassword(n: UiTreeNode): Boolean = n.secret || n.children.any(::nodeHasPassword)
