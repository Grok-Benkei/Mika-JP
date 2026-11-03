package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * このタスクでは携帯電話の使用が必要ですが、内蔵アシスタントは操作中に手を挙げません。ダイアログ ページのタスク カードの下に「設定に移動」という行を入れます。
 * Web ページおよびファイル タスクでは、この問題は発生しません。 [com.wangzan101.mika.cap.phone.PhoneServices] によって作成され、タスクと会話ページ間で共有されます。
 */
class PhoneSetupPrompt(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _needed = MutableStateFlow(false)
    val needed: StateFlow<Boolean> = _needed.asStateFlow()

    fun request() {
        _needed.value = true
    }

    fun clear() {
        _needed.value = false
    }

    /** は、初めて開くときに自動的に起動します。表示した後（完了または「後で」をクリックした後）、自動的に起動しなくなります。 */
    fun guideSeen(): Boolean = prefs.getBoolean(SEEN, false)

    fun markGuideSeen() {
        prefs.edit().putBoolean(SEEN, true).apply()
    }

    private companion object {
        const val PREFS = "phone_setup"
        const val SEEN = "guide_seen"
    }
}
