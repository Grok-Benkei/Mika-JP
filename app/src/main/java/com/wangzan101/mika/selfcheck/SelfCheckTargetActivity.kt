package com.wangzan101.mika.selfcheck

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Display
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.cap.phone.shell.SystemIntents
import java.lang.ref.WeakReference

/**
 * 互換性セルフテストがミカ仮想画面上で動作するページ: カウント ボタン、入力ボックス、スクロール可能な行、飛び続ける数字の行、色が前後に変わる水平バー。
 * (画面は変化し続けるため、数秒後に仮想画面の動きが止まるかどうかを確認できます)。このページには、これがミカ自身のテストページであると記載されています。
 *
 * はリストからエクスポートされますが、呼び出し元は INTERACT_ACROSS_USERS_FULL (シェルとシステムでは利用可能ですが、通常のアプリでは利用できません) を保持する必要があり、インテント フィルターはありません。
 * は、組み込みアシスタントによってコンポーネント名によるシェルとしてのみミカの画面に開かれます ([intent] を参照)。
 * ホーム画面（画面番号が 0）が開いた場合は、ユーザーの邪魔にならないようにすぐにオフにしてください。コントロールは通常のビュー ID とリソース ID を使用します。
 * テキストは、アプリ言語 ([tr]) に従って取得されます。セルフテスト側で同じテキストを押してコントロールを識別します (画面読み取り中の単語を含むコントロールにはリソース ID がありません)。
 */
class SelfCheckTargetActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var taps = 0
    private var ticks = 0
    private lateinit var count: TextView
    private lateinit var tick: TextView
    private lateinit var strip: View

    private val ticker = object : Runnable {
        override fun run() {
            ticks++
            tick.text = tr(R.string.selfcheck_target_tick, ticks)
            strip.setBackgroundColor(if (ticks % 2 == 0) STRIP_DARK else STRIP_LIGHT)
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((display?.displayId ?: Display.DEFAULT_DISPLAY) == Display.DEFAULT_DISPLAY) {
            finish()
            return
        }
        current = WeakReference(this)
        setContentView(content())
    }

    // 再度開くと最初から開始します。カウントをクリアし、入力ボックスをクリアして、一番上までスクロールします。
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setContentView(content())
    }

    // は表示されている限りジャンプし続けます (仮想画面上のページはフォーカスされていない可能性があり、onResume が押された場合はカウントされません)
    override fun onStart() {
        super.onStart()
        main.removeCallbacks(ticker)
        if (::tick.isInitialized) main.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun content(): View {
        taps = 0
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        fun text(id: Int, s: String, size: Float) = TextView(this).apply {
            this.id = id
            text = s
            textSize = size
            setTextColor(Color.BLACK)
            setPadding(0, pad / 4, 0, pad / 4)
        }
        column.addView(text(R.id.selfcheck_title, tr(R.string.selfcheck_target_title), 20f))
        column.addView(text(R.id.selfcheck_note, tr(R.string.selfcheck_target_note), 14f))
        count = text(R.id.selfcheck_count, tr(R.string.selfcheck_target_taps, 0), 16f)
        column.addView(count)
        column.addView(Button(this).apply {
            id = R.id.selfcheck_button
            text = tr(R.string.selfcheck_target_button)
            // ボタンはテーマに応じてデフォルトですべて大文字で表示され、画面を読むときに読む内容が変わり、書かれたとおりに表示されます。
            isAllCaps = false
            setOnClickListener {
                taps++
                count.text = tr(R.string.selfcheck_target_taps, taps)
            }
        })
        column.addView(EditText(this).apply {
            id = R.id.selfcheck_input
            hint = tr(R.string.selfcheck_target_input)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        tick = text(R.id.selfcheck_tick, tr(R.string.selfcheck_target_tick, ticks), 14f)
        column.addView(tick)
        strip = View(this).apply { setBackgroundColor(STRIP_DARK) }
        column.addView(strip, LinearLayout.LayoutParams(MATCH_PARENT, pad * 4))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in 1..ROWS) rows.addView(text(View.NO_ID, tr(R.string.selfcheck_target_row, i), 16f).apply { setPadding(0, pad, 0, pad) })
        column.addView(ScrollView(this).apply {
            id = R.id.selfcheck_list
            addView(rows)
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return column
    }

    companion object {
        private const val TICK_MS = 250L
        private const val ROWS = 60
        private val STRIP_DARK = Color.rgb(0x33, 0x33, 0x33)
        private val STRIP_LIGHT = Color.rgb(0xEE, 0xEE, 0xEE)

        @Volatile private var current: WeakReference<SelfCheckTargetActivity>? = null

        /** このページを開くために使用されるインテント: コンポーネント、単一タスクを指定し、最近のタスクを入力しません。 */
        fun intent(context: Context): Intent = Intent()
            .setClassName(context.packageName, SystemIntents.OWN_PAGE_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** セルフテストが完了またはキャンセルされました: まだオンになっている場合はオフにします。主ネジの調整。 */
        fun finishOpen() {
            current?.get()?.finish()
            current = null
        }
    }
}
