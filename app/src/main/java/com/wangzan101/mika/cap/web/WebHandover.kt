package com.wangzan101.mika.cap.web

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.takeover.HandoverActivity
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Web ページの引き継ぎ: **同じ** WebView をバックグラウンド ホストからユーザー操作用のメイン画面の [WebTakeoverPage] に移動し、使用後に元に戻します。
 * ページの状態（フォーム内のワンタイムトークン、スクロール、ログイン処理）はそのまま保持されます。プロセス中に同時に少なくとも 1 つのテイクオーバーが発生する可能性があります。
 *
 * には 2 つの用途があります。
 * - タスク引き継ぎ ([show]): インターフェース上のボタンは、[Session.onAction] 経由でタスク サービスに引き渡されます (処理済み/自分で終了しました/タスクをキャンセル/最初に戻るボタンをそのままにしておきます)。
 *   が返されると、テイクオーバー ステート マシンは [end] を呼び出し、テイクオーバー中に操作の数とページ タイトルを取得し、ビューを [Session.home] に戻します。
 * - 設定ページのログイン ([showLogin]): 新しい表示タブを開きます (「完了」のみ)。完了したら、ページをざっと見て、ログインしていないことが分からない場合は、Web サイトはログイン済みとして記録されます。
 * タスクを引き継いで引き継ぐ場合、ログインしているページ([LoginProbe])で確認できる場合のみ記録され、それ以外の場合は記録されません(ユーザーは確認コードのみ処理可能)。
 */
object WebHandover {
    enum class Action { HandBack, FinishedMyself, Cancel, PutAside, Done }

    /**
     * が一旦引き継ぎます。 [home] が null の場合、バックグラウンド ホスト (設定ページのログイン) は存在せず、ラベルは最後に破棄されます。ページのログイン モードを設定するには、[reason] を null にします。
     * [message] はユーザー向けの説明書です（支払い情報と一緒に持参してください）。
     */
    class Session internal constructor(
        val tab: WebTab,
        val home: WebHost?,
        val reason: String?,
        val message: String?,
        val waitKey: Int,
        val onAction: (Action) -> Unit,
    ) {
        /** 引き継ぎインターフェイスのページ上でのユーザーのタッチとキーストロークの数。 */
        @Volatile var inputs: Int = 0
            internal set

        /** インターフェイスのメイン スレッドでいつでも記録されているページ アドレスとタイトルを引き継ぎます。終了時には別のスレッドにある可能性があり、WebView を読み取ることができません。 */
        @Volatile var url: String? = null
            internal set
        @Volatile var title: String? = null
            internal set
        val loginMode: Boolean get() = reason == null
    }

    private val main = Handler(Looper.getMainLooper())
    /** は、ページログインポイントを「完了」に設定した後のページ応答の上限を設定します。 */
    private const val CONFIRM_WAIT_MS = 1_500L

    /** 設定ページの Web サイト ログインが開いています。タスクの Web ページの引き継ぎは、閉じられるまで待つ必要があります。 */
    val loginOpen: Boolean get() = _session.value?.loginMode == true
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /**
     * タスクの引き継ぎ: 引き継ぎインターフェイスを開き、ラベルを移動します。すでに進行中のテイクオーバーがある場合、タグが [taskId] に属していない場合、またはタグが使用できなくなった場合は、false を返します。
     * [currentKey] 現時点でのタスクの待機番号を示します。ボタンが押された時点で変更されており (この待機が経過した)、アクションは実行されません。
     */
    fun show(
        context: Context, tab: WebTab, home: WebHost, reason: String, message: String?, waitKey: Int, taskId: String,
        onAction: (Action) -> Unit = taskActions(context.applicationContext, waitKey, null),
        currentKey: (() -> Int?)? = null,
    ): Boolean {
        if (_session.value != null || tab.crashed || tab.destroyed || home.closed || tab.ownerTaskId != taskId) return false
        val guarded: (Action) -> Unit = { a ->
            val now = currentKey?.invoke()
            // 「タスクのキャンセル」はどれだけ待ってもカウントされます。
            if (a != Action.Cancel && currentKey != null && now != waitKey) android.util.Log.i("MikaWebHandover", "ignored $a for a past wait ($waitKey, now $now)")
            else onAction(a)
        }
        _session.value = Session(tab, home, reason, message, waitKey, guarded)
        context.startActivity(WebTakeoverPage.intent(context))
        return true
    }

    /** 設定ページ「ログインを開く」: ホーム画面に表示されているタブを開き、ユーザーがログインし、「完了」をクリックして終了します。メインスレッドによって呼び出されます。 メイン スレッドで */
    fun showLogin(context: Context, host: String, onDone: (String) -> Unit = { WebSites.registry(context).markLoggedIn(it) }): Boolean {
        if (_session.value != null) return false
        val url = "https://${host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')}/"
        if (WebUrlPolicy.checkNavigable(url) != null) return false
        val tab = WebTab(context.applicationContext, "login", "settings")
        tab.view.loadUrl(url)
        _session.value = Session(tab, null, null, null, -1) { action ->
            if (action != Action.Done) {
                end()
                return@Session
            }
            // ボタンが押されました。ユーザーは完了したと言っていますが、ページには表示されず、ログインもしていないため、書き留めておきます。読んだらタブを閉じてください
            val fallback = (tab.view.url ?: _session.value?.url)?.let(WebUrlPolicy::host)
            val decided = java.util.concurrent.atomic.AtomicBoolean(false)
            fun finish(host: String?, state: LoginState) {
                if (!decided.compareAndSet(false, true)) return
                if (host != null && state != LoginState.SignedOut) onDone(host)
                end()
            }
            LoginProbe.probe(tab) { host, state -> finish(host, state) }
            main.postDelayed({ finish(fallback, LoginState.Unknown) }, CONFIRM_WAIT_MS)
        }
        context.startActivity(WebTakeoverPage.intent(context))
        return true
    }

    /**
     * はこの引き継ぎを終了します。インターフェイスはオフになり、ビューはバックグラウンド ホストに戻ります (設定ページにログインすると、ラベルは破棄されます)。テイクオーバー中の操作の数とページ タイトルを返します。
     * テークオーバーが進行中でない間は null。 [loggedIn] (ユーザーが送信) の場合は、ビューを元に戻してページを見てください。ログインしたことが確認でき、[registry] に記録されます。どの糸でも調整可能です。
     */
    fun end(loggedIn: Boolean = false, registry: SiteRegistry? = null, tab: WebTab? = null): HandoverActivity? {
        val s = _session.value ?: return null
        // [tab] 指定した場合、このタグの引き継ぎのみが終了します (並列操作中に他のタスクは移動しません)。同時に終了するのは1回のみです。
        if (tab != null && s.tab !== tab) return null
        if (!_session.compareAndSet(s, null)) return null
        main.post {
            restore(s)
            if (loggedIn && registry != null) LoginProbe.probe(s.tab) { host, state ->
                if (state == LoginState.SignedIn) registry.markLoggedIn(host)
                else android.util.Log.i("MikaWebHandover", "takeover on $host handed back; sign-in not confirmed ($state), not recorded")
            }
        }
        return HandoverActivity(s.inputs, s.title?.takeIf { it.isNotBlank() })
    }

    /** ビューはバックグラウンド ホストに戻されます。ホスト（設定ページログイン）が存在しない場合は破棄されます。 */
    internal fun restore(s: Session) {
        if (s.tab.destroyed) return
        val home = s.home
        if (home == null) {
            s.tab.destroy()
            return
        }
        // タグがクラッシュしたか、ホストがシャットダウンされました (タスクが終了しました)。ハングバックせず、タグ プールに任せてクリーンアップしてください。
        if (s.tab.crashed || home.closed) {
            (s.tab.view.parent as? android.view.ViewGroup)?.removeView(s.tab.view)
            return
        }
        home.attach(s.tab)
    }

    /** タスク引き継ぎボタン → 待機番号とタスク番号を含むタスク サービス アクション (複数のタスクが同時に実行されている場合、このタスクにのみ影響します)。 */
    fun taskActions(context: Context, waitKey: Int, runKey: String?): (Action) -> Unit = { action ->
        when (action) {
            Action.HandBack, Action.Done -> TaskService.handBack(context, waitKey, runKey)
            Action.FinishedMyself -> TaskService.finishedMyself(context, waitKey, runKey)
            Action.Cancel -> TaskService.cancel(context, runKey)
            Action.PutAside -> TaskService.putAside(context, waitKey, runKey)
        }
    }
}
