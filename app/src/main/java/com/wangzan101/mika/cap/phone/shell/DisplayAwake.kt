package com.wangzan101.mika.cap.phone.shell

import java.lang.reflect.Method

/**
 * は、Mika の仮想画面をオンのままにします。 OWN_DISPLAY_GROUP で構築された画面 (Android 13 以降の信頼された画面) は、独自の表示グループを形成し、独自の画面一時停止タイミングを持ちます。
 * によって挿入された入力は、ユーザー操作としてカウントされません。放っておくと、画面休止時間になると画面がスリープ状態になり、画面が合成されなくなり、スクリーンショットがそのまま残り、クリックしても反応がなくなります。
 * 画面がオンの場合、[PERIOD_MS] はその操作に対するユーザー操作 (システムで許可されている最短の画面オフ時間よりもはるかに短い) を報告し、画面が放されると停止します。
 * [poke] を報告できない場合 (システムに画面上で報告するためのインターフェイスがない)、再試行しないでください。画面の写真が一般的に使用されます。
 */
internal class DisplayAwake(
    /** は、この画面に対するユーザー操作を報告します。報告できない場合は false を返します。 */
    private val poke: (Int) -> Boolean,
    /** はすぐに 1 回実行され、その後は [PERIOD_MS] ごとに実行されます。停止方法に戻ります。 */
    private val repeat: (Runnable) -> () -> Unit,
) {
    private val running = HashMap<Int, () -> Unit>()
    @Volatile private var unavailable = false

    fun start(displayId: Int) {
        if (unavailable) return
        val cancel = repeat(Runnable { if (!poke(displayId)) giveUp() })
        synchronized(running) { running.put(displayId, cancel) }?.invoke()
        // 初めて登録が失敗した場合、giveUp は登録よりも早く実行を終了する可能性があります: 1 つの停止を埋め合わせます
        if (unavailable) stopAll()
    }

    fun stop(displayId: Int) {
        synchronized(running) { running.remove(displayId) }?.invoke()
    }

    fun stopAll() {
        synchronized(running) { running.values.toList().also { running.clear() } }.forEach { it() }
    }

    private fun giveUp() {
        unavailable = true
        stopAll()
    }

    companion object {
        const val PERIOD_MS = 4_000L
        // PowerManager.USER_ACTIVITY_EVENT_TOUCH
        const val EVENT_TOUCH = 2
        // Display.DEFAULT_DISPLAY_GROUP (隠し定数)
        private const val DEFAULT_DISPLAY_GROUP = 0

        /**
         * この画面についてレポートしますか: 本当に独自のグループを形成している場合のみレポートします。デフォルト グループ (ユーザーのホーム画面が配置されているグループ) にチェックインすると、ユーザーのホーム画面は維持されます。
         * [groupId] が読み取れない場合は null になります。この場合、画面作成時に[ownGroupFlag]が含まれているかどうかによります。
         */
        fun needed(flags: Int, ownGroupFlag: Int, groupId: Int?): Boolean =
            flags and ownGroupFlag != 0 && groupId != DEFAULT_DISPLAY_GROUP

        /**
         * `userActivity(int displayId, long time, int event, int flags)` (Android 12 以降) を画面上にリロードして、IPowerManager でのユーザー操作を報告します。 画面番号のない古いリロードのみの場合、
         * は null です。デフォルト グループに報告され、ユーザーのホーム画面が維持され、使用されません。
         */
        fun userActivityMethod(cls: Class<*>): Method? = cls.methods.firstOrNull {
            it.name == "userActivity" && it.parameterTypes.contentEquals(PER_DISPLAY)
        }

        private val PER_DISPLAY = arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
    }
}
