package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.uistate.PlacePace
import com.wangzan101.mika.core.uistate.Pace
import kotlin.random.Random
import com.wangzan101.mika.core.uistate.IdleScroll

/**
 * のステップバイステップ操作のリズム (時計やインターフェースに触れずに純粋なロジック): ユーザーは何かをクリックした後、クリックする前に必ずそれを見る必要があり、新しい画面に到達したら、しばらくそれを見つめる必要があります。
 * 画面を変更する操作 ([noteAction]) を完了した後、それを押して、新しい画面に変更されたかどうかを確認します。次のアクションまでに少なくともどのくらいの時間がかかるかを決定します。次のアクションの前に、[waitBefore] に質問してください。
 * インターバルが十分になる前に残り時間を補ってください。
 *
 * - 同じ画面上で作業を続行します (入力、スイッチの切り替え、1 つまたは 2 つの要素の変更のみ): [Limits.sameScreenMs] のランダムなセクションを停止します。
 * - 新しい画面に切り替えました (インターフェイスが大幅に変更され、アプリが変更されました): [Limits.newScreenMs] で段落を停止し、この画面上の単語数を押して、一定期間の読書時間を追加します
 *   ([Limits.readMsPerChar]、最大 [Limits.maxReadMs]);
 * - 時々 ([Limits.pauseChance]) しばらく停止します ([Limits.pauseMs])。一度に [Limits.maxDwellMs] を超えて滞在しないでください。
 * - モデルの思考や画面の読み取りに費やした時間を含め、前のアクションの完了からカウントするのをやめ、足りない部分だけを補います。
 * - アプリの知識によると、このアプリは閲覧速度 ([PlacePace.CAREFUL]) を制限し、[careful] (長い) の範囲に応じて停止します。
 *   本アプリのタスク内での新しい画面への切り替え回数には上限([screenCap])がございます。制限に達すると、[capReached] が true になり、呼び出し元は画面を変更しなくなります。
 *   ユーザーが質問に回答した後にカウントが再開されます ([userAnswered])。このアプリで外部アクション ([outwardDone]、送信および送信) を実行した後、このタスクには上限がなくなります。
 *   は、Web ページを 1 ページずつ読むリズム ([com.wangzan101.mika.core.cap.web.PagePacing]) と同じです。
 *
 * [Off] は待機せず、上限も設定しません (「高速」、テスト、およびリズムを必要としない発信者に使用します)。
 */
class AppPacing(
    private val random: Random?,
    val limits: Limits = Limits(),
    val careful: Limits = Limits.CAREFUL,
    /** アプリの閲覧速度を制限します。ユーザーが 1 つのタスク (質問に回答する間) で新しい画面に切り替えることができる最大回数。 */
    val screenCap: Int = CAREFUL_SCREEN_CAP,
) {
    /** ミリ秒単位の継続時間。 */
    data class Limits(
        /** 同じ画面で作業を続ける場合の滞在範囲。 */
        val sameScreenMs: LongRange = 500L..1_200L,
        /** 新しい画面に切り替えた後の滞在範囲（読み取り時間を除く）。 */
        val newScreenMs: LongRange = 900L..2_000L,
        /** 新しい画面の各単語に追加される読み取り時間。 0は加算なしを意味します。 */
        val readMsPerChar: Long = 0L,
        /** 読み取り時間の上限。 */
        val maxReadMs: Long = 0L,
        /** ちょっと立ち止まるチャンス。 */
        val pauseChance: Double = 0.05,
        /** 一時停止時の追加航続距離。 */
        val pauseMs: LongRange = 600L..1_500L,
        /** 1回の滞在の上限です。 */
        val maxDwellMs: Long = 3_500L,
        /** 新しい画面が十分な時間停止されている場合は、そのまま数回ゆっくりスクロールすることができます ([com.wangzan101.mika.core.uistate.IdleScroll] を参照)。 */
        val idleScroll: Boolean = false,
    ) {
        companion object {
            /** 閲覧速度を制限するアプリ (および「いいね!」の場合はすべてのアプリ): 一時停止を長くし、単語数ごとに読み、滞在中に数回スクロールします。 */
            val CAREFUL = Limits(
                sameScreenMs = 900L..2_200L, newScreenMs = 2_500L..5_000L, readMsPerChar = 15L, maxReadMs = 8_000L,
                pauseChance = 0.1, pauseMs = 2_000L..5_000L, maxDwellMs = 15_000L, idleScroll = true,
            )
        }
    }

    private class Last(val app: String?, val atMs: Long, val newScreen: Boolean, val baseMs: Long, val limits: Limits)

    private var last: Last? = null

    /** このタスクでは (ユーザーが最後に回答して以来)、閲覧速度を制限するさまざまなアプリが何度か新しい画面に変更されました。 */
    private val screens = HashMap<String, Int>()

    /** このタスクで外部アクションを実行したアプリ: 画面数の上限はカウントされません。ユーザーの反応も不明瞭です。 */
    private val outward = HashSet<String>()

    /** リズムがある（[Off] ではない）。 */
    val paced: Boolean get() = random != null

    /**
     * は [app] (パッケージ名。null かどうかはわかりません) で画面を変更するアクションを完了しました: [newScreen] は新しい画面に変更されました。
     * [counts] の場合、この画面は閲覧速度を制限するアプリの画面数にカウントされます (同じページの残りのコンテンツを読むために下にスクロールすることはカウントされません)。 [pace] は、このアプリの閲覧速度に関するアプリのナレッジの説明です。
     */
    @Synchronized
    fun noteAction(app: String?, nowMs: Long, newScreen: Boolean, pace: PlacePace = PlacePace.NORMAL, counts: Boolean = newScreen) {
        val r = random ?: return
        val l = limitsFor(pace)
        var base = Pace.pick(r, if (newScreen) l.newScreenMs else l.sameScreenMs)
        if (r.nextDouble() < l.pauseChance) base += Pace.pick(r, l.pauseMs)
        last = Last(app, nowMs, newScreen, base.coerceAtMost(l.maxDwellMs), l)
        if (newScreen && counts && pace == PlacePace.CAREFUL && app != null) screens[app] = (screens[app] ?: 0) + 1
    }

    /**
     * 次のアクションまでの待機時間: 最後のアクションが十分に停止していない場合の残り時間、それ以外の場合は 0。
     * [textChars] は、現時点でのこの画面上の単語数です。前のアクションが新しい画面に変わったら、それを押して読書時間を追加します。 [Off] は常に 0 です。
     */
    @Synchronized
    fun waitBefore(nowMs: Long, textChars: Int = 0): Long {
        val l = last ?: return 0
        val read = if (l.newScreen) (textChars.coerceAtLeast(0) * l.limits.readMsPerChar).coerceAtMost(l.limits.maxReadMs) else 0L
        val dwell = (l.baseMs + read).coerceAtMost(l.limits.maxDwellMs)
        return (dwell - (nowMs - l.atMs)).coerceIn(0, l.limits.maxDwellMs)
    }

    /** この滞在中に数回スクロールできます。前のアクションが新しい画面に変わり、押す範囲が許可されます。 */
    @Synchronized
    fun idleAllowed(): Boolean = last?.let { it.newScreen && it.limits.idleScroll } == true

    /** このタスクでは (最後のユーザーが回答して以来) [app] が新しい画面に数回変更されました。 */
    @Synchronized
    fun screensIn(app: String): Int = screens[app] ?: 0

    /** [app] 新しい画面への変更回数が上限に達しました。再度画面を変更する前に停止してください(ユーザーが同意した後、もう一度参照してください)。通常のアプリ、外部アクションを実行したアプリ、および [Off] は常に false です。 */
    @Synchronized
    fun capReached(app: String?, pace: PlacePace): Boolean =
        random != null && app != null && pace == PlacePace.CAREFUL && app !in outward && screensIn(app) >= screenCap

    /** このタスクは [app] で外部アクション (送信、送信) を実行しました。このアプリには残りの時間の画面制限がありません。 */
    @Synchronized
    fun outwardDone(app: String) {
        if (random != null) outward += app
    }

    /** ユーザーの回答 (フォローアップの質問に回答し、引き継ぎを引き継ぎました): 各アプリの画面数が再計算されました。 */
    @Synchronized
    fun userAnswered() = screens.clear()

    private fun limitsFor(pace: PlacePace) = if (pace == PlacePace.CAREFUL) careful else limits

    companion object {
        /** は、アプリの閲覧速度を制限します。 1回のタスクで新しい画面に遷移できる最大回数（アプリ内の1画面はWebページの1ページより小さいため、クリックして開く、戻るは1回とカウントされます）。 */
        const val CAREFUL_SCREEN_CAP = 24

        /** は決して待ちません。 */
        val Off = AppPacing(null)

        /** は、アプリを段階的に操作する人のようなものです。 [random] は固定シード (テスト) に置き換えることができます。 */
        fun human(random: Random = Random.Default, limits: Limits = Limits(), careful: Limits = Limits.CAREFUL) =
            AppPacing(random, limits, careful)
    }
}
