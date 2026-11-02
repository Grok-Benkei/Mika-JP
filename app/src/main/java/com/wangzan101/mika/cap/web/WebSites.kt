package com.wangzan101.mika.cap.web

import android.content.Context
import android.webkit.WebViewDatabase

/** サイト登録フォームの設定ページ操作;登録フォームはプロセス中のものです（アプリによって提供され、設定ページとタグプールは共有されます）。 */
object WebSites {
    fun registry(context: Context): SiteRegistry = WebOwner.of(context).webSites

    /** 設定ページの終了操作: mika Web ページ構成の Cookie と Web ページの保存。メインスレッドによって呼び出されます。 */
    fun login(context: Context): WebLogin = WebLogin(WebTab.cookieManager(), WebTab.webStorage(), registry(context))

    /**
     * 設定ページの「すべて終了してWebページデータをクリア」：タブを開いていない場合でもキャッシュをクリアする必要があるため、一時的に同じ設定の非表示タブを開いてクリアし、使用後は閉じてください。メインスレッドによって呼び出されます。
     */
    fun logoutAll(context: Context) {
        val tmp = WebTab(context.applicationContext, "clear", "settings")
        try {
            login(context).logoutAll(listOf(tmp.view), WebViewDatabase.getInstance(context))
        } finally {
            tmp.destroy()
        }
    }
}
