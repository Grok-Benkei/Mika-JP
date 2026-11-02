package com.wangzan101.mika.cap.web

import android.content.Context
import com.wangzan101.mika.cap.web.cdp.WebDevtools

/** DevTools セッションの入り口、サイト登録フォーム、タグの総数の上限、プロセス中に Web ページの背景に残されるページはアプリによって提供され、設定ページは各タスクで共有されます。 */
interface WebOwner {
    val webDevtools: WebDevtools
    val webSites: SiteRegistry
    /** 各タスクのタグプールが共有するタグの総数の上限。 */
    val tabBudget: TabBudget
    /** ページはバックグラウンドに残され、タスクの完了後も実行され続けます (グローバルに最大 1 ページ)。 */
    val pageKeeper: PageKeeper

    companion object {
        fun of(context: Context): WebOwner = context.applicationContext as? WebOwner
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement WebOwner to give the browser's shared state")
    }
}
