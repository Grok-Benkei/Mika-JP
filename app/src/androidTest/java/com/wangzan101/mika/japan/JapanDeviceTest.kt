package com.wangzan101.mika.japan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.cap.phone.a11y.PhoneOcr
import com.wangzan101.mika.i18n.AppLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JapanDeviceTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun freshInstallStartsInJapaneseAsASeparateApp() {
        assertEquals("jp.studioyebisu.mika", app.packageName)
        assertEquals("0.1.33-jp.4", BuildConfig.VERSION_NAME)
        assertEquals(37, BuildConfig.VERSION_CODE)
        assertEquals(AppLanguage.Japanese, AppLanguage.current(app))
    }

    @Test fun anyPreviousChoiceIsForcedToJapanese() {
        // 日本語専用: 以前に中国語・英語・システム追従が保存されていても日本語へ寄せ直される
        for (previous in listOf(AppLanguage.SimplifiedChinese, AppLanguage.TraditionalChinese, AppLanguage.English, AppLanguage.System)) {
            AppLanguage.set(app, previous)
            AppLanguage.initializeDefault(app)
            assertEquals(previous.toString(), AppLanguage.Japanese, AppLanguage.current(app))
            assertEquals(previous.toString(), "ja", AppLanguage.resources(app).configuration.locales[0].language)
            assertEquals(previous.toString(), "ja", app.resources.getString(com.wangzan101.mika.R.string.ui_locale))
        }
        assertEquals("ja-JP", com.wangzan101.mika.voice.SystemSpeech.speechTag(AppLanguage.English))
    }

    @Test fun bundledOcrReadsHiraganaKatakanaAndKanji() = runBlocking {
        val image = Bitmap.createBitmap(1200, 220, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.WHITE)
            drawText("かな カナ 東京駅", 40f, 135f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 90f })
        }
        val text = PhoneOcr.read(image).joinToString("") { it.text }.filterNot(Char::isWhitespace)
        assertTrue(text, text.contains("かな"))
        assertTrue(text, text.contains("カナ"))
        assertTrue(text, text.contains("東京駅"))
    }
}
