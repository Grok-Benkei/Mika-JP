package com.wangzan101.mika.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ブラウザ ページで見ているページをタスクに貸します (ユーザーがこのページでミカに質問します): ユーザーが尋ねたら、まずそのページをそのダイアログに貸し出すことに「同意」します ([offer])。
 * 対話タスク開始時に取得します（[claim]）。取得後、このラベルがタスクの現在のWebページラベルとなり、タスク側は([bind])に接続されます。
 * 同時に貸出できるのは1冊のみです（閲覧ページは1ページのみ）。生と死は依然としてブラウザ ページに属します。
 * - タスク終了 ([end]): 戻り、ページはユーザーの前に残ります。
 * - 表示ラベルが変更されました ([swapped]、ページを開いたり閉じたりしたときにポップアップするウィンドウ): 借用したラベルは表示ラベルに置き換えられます。
 * - ユーザーがページを閉じる ([closing]): ページはタスクに渡されて使用されます (ブラウザ ページは受け入れられた後に破棄されません)。受け入れられない場合、ブラウザ ページは閉じられます。
 * - ユーザーがページをタッチしました ([touched]): タスクに通知します (タスクはそれに応じて一時停止されます)。
 * ミッション側は[Borrower]を参照。 [T] はラベル (インターフェイスの WebTab) です。テスト用に別のラベルに変更します。メインスレッドでのみ呼び出されます。
 */
class PageLoans<T : Any> {
    /** ラベルのタスク側を借用して実行します。 */
    interface Borrower<T : Any> {
        /** が現在のラベルとして使用されます (借用)。借用できない場合は false を返します。 */
        fun take(tab: T): Boolean
        /** を返品します (もう使用しません)。 借用した */
        fun giveBack(tab: T)
        /** を[next]に置き換えました。 */
        fun swap(prev: T, next: T)
        /** ユーザーがページを閉じました。[tab] を受け入れ、それを独自のラベルとして使用します。受け入れられない場合は false を返します。 */
        fun adopt(tab: T): Boolean
        /** ユーザーがこのページを自分の手で触れました。 */
        fun touched()
    }

    /** のコピーが貸与されました。会話 [conversationId] で [runKey] を実行するタスクは、[tab] から借用されました。 [touches] は、貸出期間中にユーザーによって数回タッチされました。 現時点では、 */
    class Loan<T : Any> internal constructor(val conversationId: String, val runKey: String, tab: T) {
        var tab: T = tab
            internal set
        var touches: Int = 0
            internal set
        internal var borrower: Borrower<T>? = null
    }

    private var offered: String? = null
    private var loan: Loan<T>? = null

    private val _lentTo = MutableStateFlow<String?>(null)
    /** はページ上のダイアログを借用しています。借用されていない場合は null です。 */
    val lentTo: StateFlow<String?> = _lentTo.asStateFlow()

    /** 現時点で貸与されている株式。 nullではありません。 ユーザー */
    val current: Loan<T>? get() = loan

    /** はページ上で質問し、会話 [conversationId] に次のタスクを貸与することに同意しました (以前に同意したタスクを置き換えます)。 */
    fun offer(conversationId: String) {
        offered = conversationId
    }

    /** 約束されたのにまだ奪われていない会話。 */
    val offeredTo: String? get() = offered

    /** ページが失われ、約束された内容は無効です。 */
    fun withdraw() {
        offered = null
    }

    /**
     * ダイアログ [conversationId] タスク [runKey] 実行時に収集してください: 私はこのダイアログを提供することを約束しました、現時点では [tab] という表示可能なページがあり、他の人に貸していません。
     * が許可されている場合は、それが使用されます。それ以外の場合は null になります。
     */
    fun claim(conversationId: String, runKey: String, tab: T?): Loan<T>? {
        if (offered != conversationId || tab == null || loan != null) return null
        offered = null
        return Loan(conversationId, runKey, tab).also {
            loan = it
            _lentTo.value = conversationId
        }
    }

    /**
     * タスク側の準備が整いました。[borrower] を接続し、現在のタグをそこに借用します。ローンが取り戻された（ページが以前に閉じられていた）場合、またはローンを借りられない場合は false を返します。
     * 借用できないものは無効となります。
     */
    fun bind(l: Loan<T>, borrower: Borrower<T>): Boolean {
        if (loan !== l) return false
        if (!borrower.take(l.tab)) {
            clear()
            return false
        }
        l.borrower = borrower
        return true
    }

    /** タスク [runKey] 終了: 借りたものを返してください。借りられないわけではありません。 */
    fun end(runKey: String) {
        val l = loan?.takeIf { it.runKey == runKey } ?: return
        clear()
        l.borrower?.giveBack(l.tab)
    }

    /** の表示ラベルが [prev] から [next] に変更されました。借用したラベルが [prev] だったときに変更されました。 */
    fun swapped(prev: T, next: T) {
        val l = loan?.takeIf { it.tab == prev } ?: return
        l.tab = next
        l.borrower?.swap(prev, next)
    }

    /**
     * ブラウザ ページはタグを閉じる必要があります。 [tab]: タスクを受け入れる場合は true (呼び出し元はタスクを破棄せず、気にしなくなります)、それ以外の場合は false を返します。
     * 回収しても回収されなくても、これで終わりです。約束したのに奪われなかったものも無効です。
     */
    fun closing(tab: T): Boolean {
        offered = null
        val l = loan?.takeIf { it.tab == tab } ?: return false
        clear()
        val b = l.borrower ?: return false
        if (b.adopt(tab)) return true
        b.giveBack(tab)
        return false
    }

    /** ユーザーは目に見えるラベルを自分の手で触れました [tab]: その使命を通じてそれを知ります。 */
    fun touched(tab: T) {
        val l = loan?.takeIf { it.tab == tab } ?: return
        l.touches++
        l.borrower?.touched()
    }

    private fun clear() {
        loan = null
        _lentTo.value = null
    }
}
