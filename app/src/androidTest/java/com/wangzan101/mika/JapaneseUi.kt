package com.wangzan101.mika

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.i18n.AppLanguage
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * 端末テストで Mika の界面を日本語に固定する（日本語専用フォーク用）。
 * Android 13 以降はシステムのアプリ言語を一時的に日本語へ寄せ、終わったら戻す。
 * 最も外側（`@get:Rule(order = Int.MIN_VALUE)`）に置き、画面が上がる前に揃える。
 * Android 12 以前はアプリ側のラップが日本語を強制するため、そのまま実行する。
 */
class JapaneseUi : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return base.evaluate()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val japanese = { context.resources.getString(R.string.ui_locale) == AppLanguage.Japanese.tag }
            if (japanese()) return base.evaluate()
            val lm = context.getSystemService(LocaleManager::class.java)
            val before = lm.applicationLocales
            lm.applicationLocales = LocaleList.forLanguageTags(AppLanguage.Japanese.tag)
            waitFor(japanese)
            try {
                base.evaluate()
            } finally {
                lm.applicationLocales = before
            }
        }
    }

    private fun waitFor(ok: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5_000
        while (!ok() && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
    }
}

/** 旧テストからの移行用エイリアス。実体は日本語固定。 */
typealias ChineseUi = JapaneseUi
