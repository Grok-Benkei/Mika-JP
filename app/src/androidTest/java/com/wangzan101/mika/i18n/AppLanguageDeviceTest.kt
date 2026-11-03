package com.wangzan101.mika.i18n

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.MikaApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AppLanguageDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun wrappingPreservesAnExistingDisplayOverride() {
        val base = configured(Configuration.ORIENTATION_LANDSCAPE, Configuration.UI_MODE_NIGHT_YES, 320)
        val recorded = RecordingContext(base)

        val wrapped = AppLanguage.wrap(recorded)

        assertLocaleOnly(recorded.overrides.first())
        assertInherited(base.resources.configuration, wrapped.resources.configuration)
    }

    @Test fun wrappingWithoutADisplayOverrideDoesNotPinDisplayConfiguration() {
        val base = context.createConfigurationContext(Configuration().apply {
            setLocales(LocaleList.forLanguageTags("fr-FR"))
        })
        val recorded = RecordingContext(base)

        val wrapped = AppLanguage.wrap(recorded)

        assertLocaleOnly(recorded.overrides.single())
        assertInherited(base.resources.configuration, wrapped.resources.configuration)
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun systemManagedJapaneseContextKeepsItsFrameworkResources() {
        val base = configured(Configuration.ORIENTATION_LANDSCAPE, Configuration.UI_MODE_NIGHT_YES, 320, "ja")
        val recorded = RecordingContext(base)

        val wrapped = AppLanguage.wrap(recorded)

        assertSame(recorded, wrapped)
        assertEquals(0, recorded.overrides.size)
        assertInherited(base.resources.configuration, wrapped.resources.configuration)
    }

    @Test fun resourcesFollowTheContextConfigurationAndKeepJapanese() {
        val previousLocale = Locale.getDefault()
        try {
            AppLanguage.applyDefaultLocale(context)
            val before = configured(Configuration.ORIENTATION_PORTRAIT, Configuration.UI_MODE_NIGHT_NO, 240)
            val changing = ChangingContext(before)
            val first = AppLanguage.resources(changing)
            assertInherited(before.resources.configuration, first.configuration)

            // Activity 単位の変更は Application の通知より先でも現在の構成を使う。
            val after = configured(Configuration.ORIENTATION_LANDSCAPE, Configuration.UI_MODE_NIGHT_YES, 480)
            changing.current = after
            val refreshed = AppLanguage.resources(changing)
            assertInherited(after.resources.configuration, refreshed.configuration)

            Locale.setDefault(Locale.FRANCE)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                (context.applicationContext as MikaApp).onConfigurationChanged(Configuration(context.resources.configuration))
            }

            assertEquals(AppLanguage.JAPANESE_LOCALE, Locale.getDefault())
            assertInherited(after.resources.configuration, AppLanguage.resources(changing).configuration)
        } finally {
            AppLanguage.applyDefaultLocale(context)
            Locale.setDefault(previousLocale)
        }
    }

    private fun configured(orientation: Int, night: Int, density: Int, language: String = "fr-FR"): Context =
        context.createConfigurationContext(Configuration().apply {
            setLocales(LocaleList.forLanguageTags(language))
            this.orientation = orientation
            uiMode = Configuration.UI_MODE_TYPE_NORMAL or night
            densityDpi = density
            screenWidthDp = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 900 else 450
            screenHeightDp = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 450 else 900
        })

    private fun assertLocaleOnly(delta: Configuration) {
        assertEquals("ja", delta.locales.toLanguageTags())
        assertEquals(Configuration.ORIENTATION_UNDEFINED, delta.orientation)
        assertEquals(0, delta.uiMode)
        assertEquals(Configuration.DENSITY_DPI_UNDEFINED, delta.densityDpi)
        assertEquals(Configuration.SCREEN_WIDTH_DP_UNDEFINED, delta.screenWidthDp)
        assertEquals(Configuration.SCREEN_HEIGHT_DP_UNDEFINED, delta.screenHeightDp)
    }

    private fun assertInherited(base: Configuration, localized: Configuration) {
        assertEquals("ja", localized.locales[0].language)
        assertEquals(base.orientation, localized.orientation)
        assertEquals(base.uiMode, localized.uiMode)
        assertEquals(base.densityDpi, localized.densityDpi)
        assertEquals(base.screenWidthDp, localized.screenWidthDp)
        assertEquals(base.screenHeightDp, localized.screenHeightDp)
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val overrides = mutableListOf<Configuration>()
        override fun createConfigurationContext(overrideConfiguration: Configuration): Context {
            overrides += Configuration(overrideConfiguration)
            return super.createConfigurationContext(overrideConfiguration)
        }
    }

    private class ChangingContext(var current: Context) : ContextWrapper(current) {
        override fun getResources(): Resources = current.resources
        override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
            current.createConfigurationContext(overrideConfiguration)
    }
}
