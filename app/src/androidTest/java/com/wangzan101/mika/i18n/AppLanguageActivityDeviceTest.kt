package com.wangzan101.mika.i18n

import android.app.UiModeManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AppLanguageActivityDeviceTest {
    @Test fun nonJapaneseWrappedActivityFollowsRotation() {
        Session().use { session ->
            val initial = session.open()
            assertLocalized(initial)
            val orientation = if (initial.framework.orientation == Configuration.ORIENTATION_PORTRAIT) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
            val requested = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }

            val changed = session.command("orientation", { putInt("orientation", requested) }) {
                it.framework.orientation == orientation && it.changes > initial.changes
            }

            assertEquals("構成変更で Activity が作り直されています", initial.instance, changed.instance)
            assertLocalized(changed)
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun nonJapaneseWrappedActivityFollowsApplicationNightMode() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val originalTarget = Configuration(app.resources.configuration)
        Session().use { session ->
            val initial = session.open()
            assertLocalized(initial)
            val night = if (initial.framework.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
                Configuration.UI_MODE_NIGHT_NO
            } else {
                Configuration.UI_MODE_NIGHT_YES
            }
            val mode = if (night == Configuration.UI_MODE_NIGHT_YES) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO

            val changed = session.command("night", { putInt("night", mode) }) {
                it.framework.uiMode and Configuration.UI_MODE_NIGHT_MASK == night && it.changes > initial.changes
            }

            assertEquals("構成変更で Activity が作り直されています", initial.instance, changed.instance)
            assertLocalized(changed)
        }
        assertEquals(originalTarget.locales, app.resources.configuration.locales)
        assertEquals(originalTarget.uiMode and Configuration.UI_MODE_NIGHT_MASK, app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
    }

    private fun assertLocalized(state: State) {
        assertEquals("fr-FR", state.data.getString("source_locale"))
        assertEquals("ja", state.localized.locales[0].language)
        assertTrue("日本語へのラップ経路が実行されていません: ${state.data}", state.data.getInt("override_count") > 0)
        assertEquals(state.framework.orientation, state.localized.orientation)
        assertEquals(state.framework.uiMode, state.localized.uiMode)
        assertEquals(state.framework.densityDpi, state.localized.densityDpi)
        assertEquals(state.framework.screenWidthDp, state.localized.screenWidthDp)
        assertEquals(state.framework.screenHeightDp, state.localized.screenHeightDp)
    }

    private class State(val data: Bundle) {
        val framework: Configuration get() = checkNotNull(data.getParcelable("framework"))
        val localized: Configuration get() = checkNotNull(data.getParcelable("localized"))
        val changes: Int get() = data.getInt("changes")
        val instance: Int get() = data.getInt("instance")
    }

    private class Session : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val reports = LinkedBlockingQueue<Bundle>()
        private val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
                reports.offer(resultData)
            }
        }
        private var request = 0
        private var initial: State? = null

        fun open(): State = command("snapshot") { true }.also { initial = it }

        fun command(command: String, extras: Bundle.() -> Unit = {}, ready: (State) -> Boolean): State {
            send(command, extras)
            val deadline = SystemClock.uptimeMillis() + 10_000
            var last: State? = null
            while (SystemClock.uptimeMillis() < deadline) {
                val data = reports.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (data.getInt("request") != request) continue
                data.getString("error")?.let { throw AssertionError(it) }
                val state = State(data)
                last = state
                if (data.getBoolean("resumed") && ready(state)) return state
            }
            throw AssertionError("Activity の構成変更が完了しませんでした: command=$command, 最後の状態=${last?.data}")
        }

        private fun send(command: String, extras: Bundle.() -> Unit = {}) {
            request++
            val intent = Intent().apply {
                component = ComponentName(instrumentation.context.packageName, LocaleConfigTestActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtras(Bundle().apply(extras))
                putExtra("command", command)
                putExtra("request", request)
                putExtra("receiver", receiver)
            }
            instrumentation.targetContext.startActivity(intent)
        }

        override fun close() {
            try {
                initial?.let { before ->
                    val restored = command("restore") {
                        it.framework.orientation == before.framework.orientation &&
                            it.framework.uiMode and Configuration.UI_MODE_NIGHT_MASK == before.framework.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    }
                    assertLocalizedConfiguration(before.framework, restored.framework)
                }
            } finally {
                send("finish")
                instrumentation.waitForIdleSync()
            }
        }

        private fun assertLocalizedConfiguration(before: Configuration, restored: Configuration) {
            assertEquals(before.orientation, restored.orientation)
            assertEquals(before.uiMode, restored.uiMode)
            assertEquals(before.densityDpi, restored.densityDpi)
            assertEquals(before.screenWidthDp, restored.screenWidthDp)
            assertEquals(before.screenHeightDp, restored.screenHeightDp)
        }
    }
}
