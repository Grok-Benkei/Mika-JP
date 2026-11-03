package com.wangzan101.mika.cap.phone.setup

import android.content.SharedPreferences
import com.wangzan101.mika.core.shell.RootGrant
import com.wangzan101.mika.core.shell.RootStart
import com.wangzan101.mika.core.shell.SuAnswer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootControlTest {
    @Test fun discoveringSuDoesNotRequestEitherPermissionByDefault() = runTest {
        var asks = 0
        val root = RootControl(MemoryPrefs(), this, {}, { true }) { _, _ ->
            asks++
            SuAnswer.Granted
        }

        root.refresh()
        root.state.first { it.suPresent }

        assertFalse(root.state.value.start)
        assertFalse(root.state.value.commands)
        assertEquals(RootStart.Off, root.rootStart())
        assertFalse(root.commandsAllowed())
        assertEquals(0, asks)
    }

    @Test fun turningCommandsOffDiscardsALatePermissionGrant() = runTest {
        val prefs = MemoryPrefs()
        val answer = CompletableDeferred<SuAnswer>()
        val root = RootControl(prefs, this, {}, { true }) { _, _ -> answer.await() }

        root.setCommands(true)
        runCurrent()
        assertEquals(RootGrant.Pending, root.state.value.commandsGrant)
        root.setCommands(false)
        answer.complete(SuAnswer.Granted)
        runCurrent()

        assertFalse(root.state.value.commands)
        assertFalse(root.commandsAllowed())
        assertFalse(prefs.getBoolean("commands", false))
        assertEquals(RootGrant.Unknown, root.state.value.commandsGrant)
    }

    @Test fun onlyTheLatestExplicitRequestCanEnableCommands() = runTest {
        val first = CompletableDeferred<SuAnswer>()
        val second = CompletableDeferred<SuAnswer>()
        var asks = 0
        val root = RootControl(MemoryPrefs(), this, {}, { true }) { _, _ ->
            if (asks++ == 0) first.await() else second.await()
        }
        root.setCommands(true)
        runCurrent()
        root.setCommands(true)
        runCurrent()
        second.complete(SuAnswer.Denied)
        runCurrent()
        first.complete(SuAnswer.Granted)
        runCurrent()

        assertFalse(root.commandsAllowed())
        assertEquals(RootGrant.Denied, root.state.value.commandsGrant)
    }

    @Test fun grantedCommandsStillRequireTheSwitchAndAnAvailableSu() = runTest {
        var present = true
        var requestedUid: Int? = null
        val root = RootControl(MemoryPrefs(), this, {}, { present }) { uid, _ ->
            requestedUid = uid
            SuAnswer.Granted
        }

        root.setCommands(true)
        runCurrent()
        assertEquals(0, requestedUid)
        assertTrue(root.commandsAllowed())
        assertFalse(root.state.value.start)
        present = false
        root.suGone()
        assertFalse(root.commandsAllowed())
        root.setCommands(false)
        assertFalse(root.state.value.commands)
    }

    private class MemoryPrefs : SharedPreferences {
        private val values = HashMap<String, Any?>()
        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String, defValue: String?) = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
        override fun getInt(key: String, defValue: Int) = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as? Boolean ?: defValue
        override fun contains(key: String) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { values[key] = value }
            override fun putStringSet(key: String, value: Set<String>?) = apply { values[key] = value }
            override fun putInt(key: String, value: Int) = apply { values[key] = value }
            override fun putLong(key: String, value: Long) = apply { values[key] = value }
            override fun putFloat(key: String, value: Float) = apply { values[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
            override fun remove(key: String) = apply { values.remove(key) }
            override fun clear() = apply { values.clear() }
            override fun commit() = true
            override fun apply() = Unit
        }
    }
}
