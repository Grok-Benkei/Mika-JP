package com.wangzan101.mika.cap.phone.shell

import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.wangzan101.mika.core.uistate.SystemAction

/**
 * システムアクションの Android 標準インテント: 目覚まし時計、タイマーの設定、目覚まし時計リスト (AlarmClock) を開く、事前に入力された新しいスケジュール (CalendarContract 挿入ページ) を開きます。
 * は、最初は Android とは関係のない [Spec] (単一テストの直接比較) として書き込まれ、その後 [Intent] に置き換えられます。組み込みアシスタントはこれらのタイプ ([allowed]) のみを許可し、Mika のインテントを開始しません。
 */
object SystemIntents {
    /** タイプによるスケジュール挿入ページの一致: カレンダー プロバイダーのチェックに依存せず、タイプを直接示します (チェックにはカレンダーの表示が必要です)。 */
    const val EVENTS_TYPE = "vnd.android.cursor.dir/event"
    const val EVENTS_URI = "content://${CalendarContract.AUTHORITY}/events"

    /** ミカの互換性セルフチェック ページ: 内蔵アシスタントがこのコンポーネントを認識し、[allowed] を押すことなくシェルとしてミカの画面に直接開きます。 */
    const val OWN_PAGE_CLASS = "com.wangzan101.mika.selfcheck.SelfCheckTargetActivity"

    /** は、このインテントを受信できるページです。 [system] はシステムに付属するアプリです。 */
    data class Handler(val packageName: String, val className: String, val system: Boolean)

    /**
     * どれを渡すか: 1 つだけあり、それがそれです。ユーザーはデフォルトのもの ([defaultPackage]、セレクターではなくデフォルトでシステムによって解析されるパッケージ) を設定しており、デフォルトのものを使用します。
     * それ以外の場合は、システムに付属しているものを最初に選択し、その後の順序に従います。そのページに直接割り当てる: 静かに設定されたアクションは、Mika の画面に「どれを使用するか」の選択ボックスを残すべきではありません。
     * は null (サポートされていません。モデルは操作インターフェイスに戻ります)。
     */
    fun choose(handlers: List<Handler>, defaultPackage: String?): Handler? =
        handlers.singleOrNull()
            ?: handlers.firstOrNull { it.packageName == defaultPackage }
            ?: handlers.firstOrNull { it.system }
            ?: handlers.firstOrNull()

    /** インテントの内容: アクション、データとタイプ、追加の値 (Int、Long、Boolean、String、整数リストのみ)。 */
    data class Spec(val action: String, val data: String? = null, val type: String? = null, val extras: Map<String, Any> = emptyMap())

    fun spec(a: SystemAction): Spec = when (a) {
        is SystemAction.SetAlarm -> Spec(AlarmClock.ACTION_SET_ALARM, extras = buildMap {
            put(AlarmClock.EXTRA_HOUR, a.hour)
            put(AlarmClock.EXTRA_MINUTES, a.minute)
            // インターフェイス: 直接設定します
            put(AlarmClock.EXTRA_SKIP_UI, true)
            a.label?.let { put(AlarmClock.EXTRA_MESSAGE, it) }
            if (a.days.isNotEmpty()) put(AlarmClock.EXTRA_DAYS, ArrayList(a.days))
            a.vibrate?.let { put(AlarmClock.EXTRA_VIBRATE, it) }
        })
        is SystemAction.SetTimer -> Spec(AlarmClock.ACTION_SET_TIMER, extras = buildMap {
            put(AlarmClock.EXTRA_LENGTH, a.seconds)
            put(AlarmClock.EXTRA_SKIP_UI, true)
            a.label?.let { put(AlarmClock.EXTRA_MESSAGE, it) }
        })
        SystemAction.ShowAlarms -> Spec(AlarmClock.ACTION_SHOW_ALARMS)
        is SystemAction.AddCalendarEvent -> Spec(Intent.ACTION_INSERT, EVENTS_URI, EVENTS_TYPE, buildMap {
            put(CalendarContract.Events.TITLE, a.title)
            put(CalendarContract.EXTRA_EVENT_BEGIN_TIME, a.beginMs)
            a.endMs?.let { put(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            if (a.allDay) put(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            a.location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            a.description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        })
    }

    /** の組み込みアシスタントは、Mika に対して次のタイプのアクションのみをアクティブにします。アクションはテーブル内にあり、スケジュールのみを挿入できます。 */
    fun allowed(action: String?, data: String?): Boolean = when (action) {
        AlarmClock.ACTION_SET_ALARM, AlarmClock.ACTION_SET_TIMER, AlarmClock.ACTION_SHOW_ALARMS -> data == null
        Intent.ACTION_INSERT -> data == EVENTS_URI
        else -> false
    }

    fun toIntent(s: Spec): Intent {
        val i = Intent(s.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        when {
            s.data != null && s.type != null -> i.setDataAndType(Uri.parse(s.data), s.type)
            s.data != null -> i.data = Uri.parse(s.data)
            s.type != null -> i.type = s.type
        }
        for ((k, v) in s.extras) when (v) {
            is Int -> i.putExtra(k, v)
            is Long -> i.putExtra(k, v)
            is Boolean -> i.putExtra(k, v)
            is String -> i.putExtra(k, v)
            is ArrayList<*> -> i.putIntegerArrayListExtra(k, ArrayList(v.filterIsInstance<Int>()))
            else -> error("unsupported extra $k")
        }
        return i
    }
}
