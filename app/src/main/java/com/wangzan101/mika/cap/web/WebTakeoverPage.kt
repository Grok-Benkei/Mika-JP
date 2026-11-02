package com.wangzan101.mika.cap.web

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.AppLanguage
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.wangzan101.mika.web.WebTakeoverActivity

/**
 * Web ページ引き継ぎインターフェイス: メイン画面は全画面表示で、上部に「Mika が待っています: <理由>」とボタンがあり、その下に **同じ** WebView (バックグラウンド ホストから移動) が表示されます。
 * タスクの引き継ぎ: 「渡されました」「自分で完了しました」「タスクをキャンセルしました」;システムリターンキー = そのままにしておきます (タスクはまだ引き継がれるのを待っており、通知もまだ存在します)。
 * 設定ページログイン：「完了」のみ、リターンキーは直接オフになります。
 * テイクオーバーが終了すると ([WebHandover.end])、インターフェイスは自動的に閉じられ、ビューはバックグラウンド ホストに返されます。
 */
open class WebTakeoverPage : Activity() {
    private var session: WebHandover.Session? = null
    private lateinit var container: FrameLayout
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ticker = Handler(Looper.getMainLooper())
    private val pageRect = Rect()

    private val recordPage = object : Runnable {
        override fun run() {
            record()
            ticker.postDelayed(this, RECORD_INTERVAL_MS)
        }
    }

    // Android 12 以前の場合、アプリで選択した言語が適用されます。 13 以降、システムは単独で適用できます。
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = WebHandover.session.value
        if (s == null || s.tab.crashed) {
            finish()
            return
        }
        session = s
        container = FrameLayout(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            fitsSystemWindows = true
            addView(header(s), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        // 同じ WebView: バックグラウンド ホストから削除され、このインターフェイスに配置されました
        val view = s.tab.view
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.requestFocus()
        ticker.post(recordPage)
        // テイクオーバーが別の場所で終了しました (通知ボタン、タイムアウト、キャンセル): このインターフェイスを閉じると、ビューが WebHandover によって元に戻されます
        scope.launch {
            WebHandover.session.collect { now -> if (now !== s) leave() }
        }
    }

    private fun header(s: WebHandover.Session): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(8))
            setBackgroundColor(Color.rgb(242, 242, 247))
        }
        bar.addView(TextView(this).apply {
            text = if (s.loginMode) tr(R.string.web_takeover_login) else tr(R.string.web_takeover_waiting, reasonLabel(s.reason))
            textSize = 15f
            setTextColor(Color.BLACK)
        })
        s.message?.takeIf { it.isNotBlank() }?.let { m ->
            bar.addView(TextView(this).apply {
                text = m
                textSize = 13f
                setTextColor(Color.DKGRAY)
            })
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun add(label: String, action: WebHandover.Action) = buttons.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { act(action) }
        })
        if (s.loginMode) {
            add(tr(R.string.done), WebHandover.Action.Done)
        } else {
            add(tr(R.string.action_hand_back), WebHandover.Action.HandBack)
            add(tr(R.string.action_finished_myself), WebHandover.Action.FinishedMyself)
            add(tr(R.string.action_cancel_task), WebHandover.Action.Cancel)
        }
        bar.addView(buttons)
        return bar
    }

    private fun act(action: WebHandover.Action) {
        val s = session ?: return
        record()
        s.onAction(action)
    }

    // ページ上でユーザーが押したすべてのタッチ (押す) とキーが記録されます。上部のボタンをクリックしてもカウントされません。
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val s = session
        if (s != null && ev.actionMasked == MotionEvent.ACTION_DOWN && ::container.isInitialized) {
            container.getGlobalVisibleRect(pageRect)
            if (pageRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) s.inputs++
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val s = session
        if (s != null && event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK && event.repeatCount == 0) s.inputs++
        return super.dispatchKeyEvent(event)
    }

    /** システムリターンキー: タスク引き継ぎ = そのままにしておきます。設定ページのログイン = オフにします。 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val s = session ?: return super.onBackPressed()
        record()
        if (s.loginMode) s.onAction(WebHandover.Action.Cancel) else s.onAction(WebHandover.Action.PutAside)
    }

    private fun record() {
        val s = session ?: return
        s.url = s.tab.view.url ?: s.url
        s.title = s.tab.view.title?.takeIf { it.isNotBlank() } ?: s.title
    }

    private fun leave() {
        if (isFinishing) return
        record()
        finish()
    }

    override fun onDestroy() {
        ticker.removeCallbacks(recordPage)
        scope.cancel()
        // このインターフェイスと一緒にビューを破棄することはできません。最初にビューを削除し、WebHandover によってバックグラウンド ホストに戻してください。
        if (::container.isInitialized) container.removeAllViews()
        // インターフェイスには取り消し線が引かれていますが、引き継ぎはまだ終了していません。タスクの引き継ぎは「最初に脇に置いておく」として処理され、設定ページのログインは直接終了します。
        session?.let { s ->
            if (WebHandover.session.value === s && isFinishing && !isChangingConfigurations) {
                if (s.loginMode) WebHandover.end() else s.onAction(WebHandover.Action.PutAside)
            }
        }
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val RECORD_INTERVAL_MS = 1_000L

        fun intent(context: Context): Intent =
            Intent(context, WebTakeoverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun reasonLabel(reason: String?) = when (reason) {
            "login" -> tr(R.string.takeover_reason_login)
            "captcha" -> tr(R.string.takeover_reason_captcha)
            "access_blocked" -> tr(R.string.web_reason_security)
            "payment" -> tr(R.string.takeover_reason_payment)
            "decision" -> tr(R.string.takeover_reason_decision)
            else -> tr(R.string.takeover_title)
        }
    }
}
