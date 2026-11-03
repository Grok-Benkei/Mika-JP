package com.wangzan101.mika.capsule

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 現時点では、Mika 自身のインターフェイス (ダイアログ ページ、Web ページ引き継ぎページなどのアクティビティ) が表示されています。onStart があり、まだ onStop がないアクティビティをカウントします。
 * 画面回転などの再構築を行う場合、古い方が先に停止し、新しい方が再度開始されるため、フロントから抜けた処理としてカウントされません。これはアプリの起動時に登録され、メインスレッドでのみコールバックされます。
 */
class AppForeground : Application.ActivityLifecycleCallbacks {
    private var started = 0
    /** 再構築により停止され、新しいインスタンスがまだ開始されていないインスタンスの数。 */
    private var recreating = 0
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    override fun onActivityStarted(activity: Activity) {
        if (recreating > 0) recreating-- else started++
        _visible.value = started > 0
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity.isChangingConfigurations) recreating++ else started = (started - 1).coerceAtLeast(0)
        _visible.value = started > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
