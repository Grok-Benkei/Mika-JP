package com.wangzan101.mika.browser

/**
 * ブラウザ ページの Web ビューはどこでハングしていますか? Web ページ タグ (プロセス内に配置) は 1 つだけですが、同時に複数のブラウザ ページが存在する可能性があります: アプリ インターフェイスに複数のインスタンスがある場合
 * (元のインスタンスをカバーして、通知、デスクトップ、および他のアプリから別のインスタンスを開く)、各インスタンスはブラウザー ページで停止し、Web ページのコンテナーを残します。
 * ビューは同時に 1 つのコンテナーでのみハングできます。間違ったコンテナ (カバーされているコンテナ) に吊るされている場合、ユーザーには何も表示されません。
 *
 * ルール：最後にコンテナをフロントに返却した場所（[started]）に吊るしておくと、同じ新しいコンテナが回収されます。そのエリアを出た後、残りの部屋に移動し、最近フロントデスクに戻りました。
 * [close] それが最後の場所であるかどうかを発信者に伝えます。Web ページは、最後の場所がなくなった場合にのみ閉じられます (画面の切り替えではありません)。ブラウザ ページを別の場所に残しても、他の人が見ている Web ページは閉じられません。
 *
 * [C] はビューを保持するコンテナで、[A] はこの部分が配置されるインターフェイスです (ハングアップするとビュー環境がそれに変更されます)。メインスレッドでのみ呼び出されます。
 */
class PageHosts<C : Any, A : Any> {
    /** ブラウザページ。 */
    class Host<C : Any, A : Any>(val activity: A) {
        /** これは、現時点で Web ページ用に予約されているコンテナです。開いている Web ページがない場合は null になります。 */
        var container: C? = null
            internal set
        internal var startedAt = 0L
    }

    private val hosts = mutableListOf<Host<C, A>>()
    private var clock = 0L

    /** このポイントはブラウザ ページに入ります。 */
    fun open(host: Host<C, A>) {
        if (host !in hosts) hosts += host
    }

    /** このポイントはブラウザ ページから離れています (またはインターフェイスが消えています)。 true を返すということは、他のブラウザ ページが存在しないことを意味します。 */
    fun close(host: Host<C, A>): Boolean {
        hosts.remove(host)
        host.container = null
        return hosts.isEmpty()
    }

    /** この点はフロントデスク（可視）に戻ります。 */
    fun started(host: Host<C, A>) {
        host.startedAt = ++clock
    }

    /** ここのコンテナーが存在するか、存在しません。 */
    fun setContainer(host: Host<C, A>, container: C?) {
        host.container = container
    }

    /** ビューをハングする場所: まだブラウザー ページ上にあり、最近コンテナーの最前面に戻ったビュー。そうでない場合は null です。 */
    fun target(): Host<C, A>? =
        hosts.withIndex().filter { it.value.container != null }
            .maxWithOrNull(compareBy<IndexedValue<Host<C, A>>>({ it.value.startedAt }, { it.index }))?.value
}
