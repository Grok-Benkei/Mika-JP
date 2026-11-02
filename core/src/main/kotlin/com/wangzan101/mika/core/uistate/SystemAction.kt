package com.wangzan101.mika.core.uistate

/**
 * システム アクション (インテント): 目覚まし時計の設定、タイマー、目覚まし時計リストを開く、事前に入力された新しいスケジュールを開きます。
 * は、携帯電話にインストールされている時計とカレンダーのアプリによって引き継がれます。アプリがない場合、タイムポートは [SystemActionResult.NOT_SUPPORTED] を報告し、モデルは操作インターフェイスに戻ります。
 */
sealed interface SystemAction {
    /** [days] 毎週繰り返される日、java.util.Calendar の記述方法に従って (1 = 日曜日... 7 = 土曜日)、空 = 1 回だけ鳴ります。 */
    data class SetAlarm(val hour: Int, val minute: Int, val label: String? = null, val days: List<Int> = emptyList(), val vibrate: Boolean? = null) : SystemAction
    data class SetTimer(val seconds: Int, val label: String? = null) : SystemAction
    data object ShowAlarms : SystemAction
    /** はミリ秒のタイムスタンプで開始および終了します。 [allDay] は日付のみを参照します。 */
    data class AddCalendarEvent(
        val title: String, val beginMs: Long, val endMs: Long? = null, val allDay: Boolean = false,
        val location: String? = null, val description: String? = null,
    ) : SystemAction

    /** App へ渡した後は UI を残さない（アラーム、タイマー）; それ以外は Mika の画面でその App のページを開く。 */
    val silent: Boolean get() = this is SetAlarm || this is SetTimer
}

/**
 * システムアクションの結果。[result] が失敗のときの理由は [NOT_SUPPORTED]（そのアクションを受ける App がない）、app_in_use_by_user、launch_failed など;
 * [handledBy] は引き受けた App のパッケージ名; [nextAlarmBefore] / [nextAlarmAfter] はアクションの前後にシステムが報告した次のアラーム（ミリ秒のタイムスタンプ、なければ null）、
 * [alarmReadable] が false のときは読めない; [showing]: アクションの後に Mika の画面で [handledBy] のページが開いている（本来静かに設定されるはずが、App が UI を開いた）。
 */
data class SystemActionResult(
    val result: ActionResult,
    val handledBy: String? = null,
    val nextAlarmBefore: Long? = null,
    val nextAlarmAfter: Long? = null,
    val alarmReadable: Boolean = false,
    val showing: Boolean = false,
) {
    companion object {
        const val NOT_SUPPORTED = "not_supported"
    }
}
