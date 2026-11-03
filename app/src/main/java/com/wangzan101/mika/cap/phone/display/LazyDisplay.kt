package com.wangzan101.mika.cap.phone.display

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * が使用されている場合にのみ構築されます。仮想画面は、タスクが最初にアプリを開いたとき、またはシステム コマンドやイベント ツールを初めて使用したときに、[get] によって構築され、それ以来同じです。純粋な対話タスクは最初から最後まで構築されるわけではありません。
 * [width] および [height] は構築される画面のサイズです。これらを使用して、構築前に画面を読み取るときにサイズを報告します。
 * ジェネリックは、単体テストを偽のオブジェクトに置き換えることを可能にするためにのみ使用されます。製品では[LazyAgentDisplay]となります。
 */
class LazyDisplay<T : Any>(val width: Int, val height: Int, private val create: suspend () -> T) {
    private val lock = Mutex()
    @Volatile private var made: T? = null

    /** 画面が構築されました。まだ構築されていない場合は null です。建てないよ。 */
    val current: T? get() = made

    /** は、初めて呼び出されたときに画面を取得して構築します。ビルドに失敗した場合はスローされ、次回再試行されます。 */
    suspend fun get(): T = made ?: lock.withLock { made ?: create().also { made = it } }
}

typealias LazyAgentDisplay = LazyDisplay<AgentDisplay>
