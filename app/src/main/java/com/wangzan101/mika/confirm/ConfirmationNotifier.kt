package com.wangzan101.mika.confirm

import com.wangzan101.mika.core.cap.root.ROOT_CONFIRM_APP
import com.wangzan101.mika.i18n.tr
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.wangzan101.mika.R
import com.wangzan101.mika.task.alertQuietly
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction

/**
 * カードと通知のテキストを確認します。フィールドはモデルによって書き込まれます。フィールドはプレーン テキストとしてのみ使用できます (タグは解析されません)。
 * 改行と行区切り文字はスペースに押し込まれ、制御文字、ゼロ幅文字、およびテキスト方向制御文字は削除されます。
 * フィールド内の「・」とコロンを他の記号に置き換えると、偽の「・Amount:」フィールドをスペルアウトできなくなります。
 */
object ConfirmationCopy {
    /** 折りたたまれたときにその行に表示される単語の数。 */
    const val CONTENT_MAX = 60
    /** 拡張カードおよび通知に表示できる最大文字数（宣言時の上限と同じ）。 */
    const val DETAIL_MAX = 200
    /** 確認カードの指示: お金を使う操作に同意する必要があるのは、一度に 1 回だけです。 */
    val NOTE: String get() = tr(R.string.confirm_note)

    fun actionLabel(action: IrrevAction): String = when (action) {
        IrrevAction.send -> tr(R.string.chat_send)
        IrrevAction.pay -> tr(R.string.confirm_pay)
        IrrevAction.delete -> tr(R.string.confirm_delete)
        IrrevAction.order -> tr(R.string.confirm_order)
        IrrevAction.submit -> tr(R.string.confirm_submit)
        IrrevAction.other -> tr(R.string.confirm_other)
    }

    /** root コマンドの確認は特定アプリ内の操作ではない。タイトルは root コマンド、対象はスマホ全体とし、コマンド全体を表示する。 */
    private fun root(r: ConfirmationRequest) = r.app == ROOT_CONFIRM_APP

    fun title(r: ConfirmationRequest) = tr(R.string.confirm_title, if (root(r)) tr(R.string.confirm_root) else actionLabel(r.action))

    /** は行を折りたたみます: "<App 名> · オブジェクト: <誰に> · 内容: <最初の 60 単語> · 金額: <いくら>";空のフィールドを書き込まないでください。 */
    fun body(r: ConfirmationRequest, appName: String): String = fields(r, appName, CONTENT_MAX).joinToString(" · ")

    /** 展開カードは1行1項目。通常は最大200文字、root コマンドは全体を表示する。 */
    fun detail(r: ConfirmationRequest, appName: String): String = fields(r, appName, if (root(r)) Int.MAX_VALUE else DETAIL_MAX).joinToString("\n")

    private fun fields(r: ConfirmationRequest, appName: String, contentMax: Int): List<String> {
        val content = plain(r.content).let { if (it.length > contentMax) it.take(contentMax) + "…" else it }
        return listOfNotNull(
            tr(R.string.confirm_in_app, if (root(r)) tr(R.string.confirm_root_where) else plain(appName)),
            plain(r.target).takeIf { it.isNotEmpty() }?.let { tr(R.string.confirm_target, it) },
            content.takeIf { it.isNotEmpty() }?.let { tr(R.string.confirm_content, it) },
            r.amount?.let(::plain)?.takeIf { it.isNotEmpty() }?.let { tr(R.string.payment_amount, it) },
        )
    }

    private val INVISIBLE = Regex("[\\u061C\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF]")
    private val SPACES = Regex("[\\s\\u2028\\u2029\\u0085\\u00A0\\u3000]+")

    private fun plain(s: String) = s.replace(INVISIBLE, "").replace(SPACES, " ")
        .filterNot { Character.isISOControl(it) }
        .replace('·', '/').replace(':', '∶').replace('：', '∶')
        .trim()
}

/** レベルの目を引く通知を確認します: 引き継ぎと同じ高優先チャネルであり、ボタンは「同意する」と「同意しない」です。 */
object ConfirmationNotifier {
    /** 通知ボタンとカード ボタンを使用すると、古い通知と古いカードでクリックされた回答がサービスによって破棄されます。 */
    const val EXTRA_REQUEST = "confirmation_request"

    /** アプリの表示名。パッケージ名を読み取れない場合（インストールされていない場合、パッケージが表示されない場合）には、パッケージ名を使用してください。 */
    fun appLabel(context: Context, packageName: String): String = if (packageName == ROOT_CONFIRM_APP) tr(R.string.confirm_root_where) else try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().ifBlank { packageName }
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    /** は毎回番号を宣言します。ループが宣言されるたびに新しい待機状態が作成され、同じプロセス内のサービスとインターフェイスは同じオブジェクトを取得します。 */
    fun requestKey(state: TaskState.WaitingConfirmation): Int = System.identityHashCode(state)

    /** ボタン インテントデータ: 各通知と各ボタンは異なります。システムは古い通知のボタンを新しい通知に置き換えません。 */
    fun intentData(key: Int, approve: Boolean) = "mika-confirm://request/$key/" + if (approve) "approve" else "decline"

    fun build(
        context: Context, channel: String, state: TaskState.WaitingConfirmation, service: Class<*>, approveAction: String, declineAction: String,
        contentIntent: PendingIntent,
        /** 複数のタスクが同時に確認を待機している場合、それぞれがリクエスト コードを使用し、どのタスクであるかを示すボタンに [runExtra] が追加されます。 */
        codeOffset: Int = 0,
        runExtra: Pair<String, String>? = null,
        /** は引き続きメッセージを送信しますが、音は鳴らず、バナーもポップアップしません (中断されたカプセルが表示されている場合)。[alertQuietly] を参照してください。 */
        quiet: Boolean = false,
    ): Notification {
        val r = state.request
        val key = requestKey(state)
        val app = appLabel(context, r.app)
        fun button(title: Int, action: String, approve: Boolean, code: Int): Notification.Action {
            val pi = PendingIntent.getService(
                context, code + codeOffset,
                Intent(context, service).setAction(action).setData(Uri.parse(intentData(key, approve))).putExtra(EXTRA_REQUEST, key)
                    .apply { runExtra?.let { (k, v) -> putExtra(k, v) } },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            )
            return Notification.Action.Builder(null, context.getString(title), pi).apply {
                // まずロック画面で「同意する」をクリックしてロックを解除します
                if (approve && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAuthenticationRequired(true)
            }.build()
        }
        // ロック画面には同意する操作の種類のみが表示されます。オブジェクトとコンテンツはロックを解除した後にのみ表示されます
        val public = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ConfirmationCopy.title(r))
            .setContentText(context.getString(R.string.confirmation_locked_hint))
            .build()
        return Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ConfirmationCopy.title(r))
            .setContentText(ConfirmationCopy.body(r, app))
            .setStyle(Notification.BigTextStyle().bigText(ConfirmationCopy.detail(r, app)))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(button(R.string.action_approve, approveAction, true, 20))
            .addAction(button(R.string.action_decline, declineAction, false, 21))
            .apply { if (quiet) alertQuietly() }
            .build()
    }
}
