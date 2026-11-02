package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.DwellInterrupted
import com.wangzan101.mika.core.uistate.IdleScroll
import com.wangzan101.mika.core.uistate.PageLimitReached
import com.wangzan101.mika.core.uistate.PlacePace
import com.wangzan101.mika.core.run.waitOrSteer
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * [AppPacing] を押して、タスク中にアプリを一時停止します。画面を変更する各アクション ([after]) を書き留め、次のアクションまでの不十分な時間を埋め合わせます ([before])。
 * [pace] パッケージ名に基づいて、このアプリの閲覧速度に関するアプリ知識の意見を提供します。
 * が [idle] に与えられると、今度は範囲が許可され、呼び出し元は画面ポートを渡し (次のアクションは画面上の位置に基づいていません)、滞在中にその配置に従って数回ロールします ([ScreenPort.idleScroll])。
 * ブラウジング速度を制限するアプリ。このタスクでは画面遷移が上限に達しています。 [before] は待機せず、[PageLimitReached] をスローします (Web ページの page_limit と同じ結果)。
 * ユーザーは一時停止中に中断し ([waitOrSteer])、[DwellInterrupted] をスローしました: 呼び出し元はこのアクションを実行せず、一時停止されていない部分は次回も作成されます。
 */
internal class AppDwell(
    private val pacing: AppPacing,
    private val pace: (String) -> PlacePace = { PlacePace.NORMAL },
    private val idle: IdleScroll? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** は長時間停止し、滞在中に数回回転しました。 */
    class Dwelt(val waitMs: Long, val idleScrolls: Int)

    /** は、滞在中にわずかに回転した後に呼び出されます (モデルの手の画面の位置は正確ではなくなります)。 */
    var onIdleScroll: () -> Unit = {}

    private val paces = HashMap<String, PlacePace>()

    val paced: Boolean get() = pacing.paced

    /** は [app] で画面変更アクションを完了しました: [newScreen] は新しい画面に変更され、[counts] は画面の数をカウントしました ([AppPacing.noteAction] を参照)。 */
    fun after(app: String?, newScreen: Boolean, counts: Boolean = newScreen) {
        if (!paced) return
        pacing.noteAction(app, clock(), newScreen, app?.let(::paceOf) ?: PlacePace.NORMAL, counts)
    }

    /**
     * [app] で画面を変更する次のアクションを実行する前に、十分に停止していない場合は補ってください。 [textChars] は、現時点でのこの画面上の単語数です (新しい画面を押して、読書時間を追加します)。
     * [gate]の場合は、画面数の上限を確認してから[PageLimitReached]をスローしてください。 [port] 与えた後は、抱いたまま軽く数回転がしてください。
     */
    suspend fun before(app: String?, textChars: Int, gate: Boolean, port: ScreenPort? = null): Dwelt {
        if (!paced) return Dwelt(0, 0)
        val p = app?.let(::paceOf) ?: PlacePace.NORMAL
        if (gate && pacing.capReached(app, p)) throw PageLimitReached(app!!, pacing.screensIn(app), pacing.screenCap)
        val wait = pacing.waitBefore(clock(), textChars)
        if (wait <= 0) return Dwelt(0, 0)
        val plan = idle?.takeIf { port != null && pacing.idleAllowed() }
        val scrolls = if (plan != null) idleWait(port!!, plan, wait) else 0.also { pause(wait) }
        return Dwelt(wait, scrolls)
    }

    /** ユーザーは次のように回答しました: 各アプリの画面数が再計算されました。 */
    fun userAnswered() = pacing.userAnswered()

    /** は [app] で外部アクションを完了します。 [done]（完了）の場合、このアプリは画面数の上限を設定しなくなりました。 */
    fun outwardEnd(app: String, done: Boolean) {
        if (done) pacing.outwardDone(app)
    }

    /** は [ms] ミリ秒間停止します。ユーザーが中断して [DwellInterrupted] をスローすると、停止が停止します。 */
    private suspend fun pause(ms: Long) {
        waitOrSteer { sleep(ms) } ?: throw DwellInterrupted()
    }

    /** は [wait] ミリ秒間一時停止します。その間、[plan] を押して軽くスクロールします。この時点でスクロール (ポップアップ ボックス、入力方法、フォーカス) するのが不便であるとポートが判断した場合、ポートはスクロールを停止しますが、それでも十分です。戻って数回回転させます。 アプリ内での */
    private suspend fun idleWait(port: ScreenPort, plan: IdleScroll, wait: Long): Int {
        val start = clock()
        var done = 0
        for (step in plan.plan(wait)) {
            val left = step.atMs - (clock() - start)
            if (left > 0) pause(left)
            val ok = try { port.idleScroll(step.fraction) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            if (!ok) break
            done++
            onIdleScroll()
        }
        val rest = wait - (clock() - start)
        if (rest > 0) pause(rest)
        return done
    }

    private fun paceOf(app: String): PlacePace = synchronized(paces) { paces.getOrPut(app) { pace(app) } }
}
