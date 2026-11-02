package com.wangzan101.mika.takeover

import android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED
import android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED
import android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED
import android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandoverActivityCounterTest {
    private val counter = HandoverActivityCounter("a.app")

    @Test
    fun countsOnlyMainDisplayTargetPackage() {
        counter.onEvent(0, "a.app", TYPE_VIEW_CLICKED, null, 100)
        counter.onEvent(0, "a.app", TYPE_VIEW_TEXT_CHANGED, null, 200)
        counter.onEvent(0, "a.app", TYPE_VIEW_TEXT_CHANGED, null, 2_300)
        // 操作以外の他の画面、他のパッケージ、イベントはカウントされません。
        counter.onEvent(9, "a.app", TYPE_VIEW_CLICKED, null, 400)
        counter.onEvent(0, "other.app", TYPE_VIEW_CLICKED, null, 500)
        counter.onEvent(0, "a.app", TYPE_VIEW_FOCUSED, null, 600)
        counter.onEvent(0, null, TYPE_VIEW_CLICKED, null, 700)
        assertEquals(3, counter.inputCount)
    }

    @Test
    fun mergesScrollBursts() {
        // 1 回のスワイプで多くのスクロール イベントが連続して送信されます。間隔が1秒未満のものは同一イベントとしてカウントされます。
        listOf(1_000L, 1_100, 1_300, 1_900, 2_800).forEach { counter.onEvent(0, "a.app", TYPE_VIEW_SCROLLED, null, it) }
        assertEquals(1, counter.inputCount)
        counter.onEvent(0, "a.app", TYPE_VIEW_SCROLLED, null, 4_000)
        counter.onEvent(0, "a.app", TYPE_VIEW_SCROLLED, null, 4_200)
        assertEquals(2, counter.inputCount)
        // クリック数はスクロールマージの影響を受けません
        counter.onEvent(0, "a.app", TYPE_VIEW_CLICKED, null, 4_300)
        assertEquals(3, counter.inputCount)
    }

    @Test
    fun mergesTypingInSameFieldButNotAcrossFields() {
        listOf(1_000L, 1_300, 1_900).forEach { counter.onEvent(0, "a.app", TYPE_VIEW_TEXT_CHANGED, null, it, "f1") }
        assertEquals(1, counter.inputCount)
        counter.onEvent(0, "a.app", TYPE_VIEW_TEXT_CHANGED, null, 2_000, "f2")
        assertEquals(2, counter.inputCount)
        counter.onEvent(0, "a.app", TYPE_VIEW_TEXT_CHANGED, null, 5_000, "f2")
        assertEquals(3, counter.inputCount)
    }

    @Test
    fun longBurstCountsMoreThanOnce() {
        // 0.5 秒ごとに 12 秒間のローリング イベント: 5 秒ごとに上限あり -> 3 回
        (0..24).forEach { counter.onEvent(0, "a.app", TYPE_VIEW_SCROLLED, null, 1_000L + it * 500) }
        assertEquals(3, counter.inputCount)
    }

    @Test
    fun stoppedAtPrefersActivityWhenTitleIsJustTheAppLabel() {
        assertEquals("PayActivity", stoppedAt("微信", "微信", "PayActivity"))
        assertEquals("PayActivity", stoppedAt("  ", "微信", "PayActivity"))
        assertEquals("收银台", stoppedAt("收银台", "微信", "PayActivity"))
        assertEquals("微信", stoppedAt("微信", "微信", null))
        assertNull(stoppedAt(null, "微信", null))
    }

    @Test
    fun remembersLastActivitySimpleName() {
        assertNull(counter.lastActivity)
        counter.onEvent(0, "a.app", TYPE_WINDOW_STATE_CHANGED, "a.app.pay.PayActivity", 100)
        // 他のパッケージおよび他の画面は対象外です
        counter.onEvent(0, "other.app", TYPE_WINDOW_STATE_CHANGED, "o.MainActivity", 200)
        counter.onEvent(9, "a.app", TYPE_WINDOW_STATE_CHANGED, "a.app.OtherActivity", 300)
        assertEquals("PayActivity", counter.lastActivity)
        assertEquals(0, counter.inputCount)
    }
}
