package com.wangzan101.mika.core.uistate

/**
 * モデルは、このタスクのアプリの「タスク終了時の動作」を決定します。理由を付けて、それを保持する (keep) か閉じる (close) かを決定します。
 * マークされていない場合は、デフォルトを押します。バックグラウンドに留まり、終了時にサウンドがオフになります（タスクの最後に電話側によって実行されます）。同じアプリが再度マークされた場合は、最後にマークされた時間が優先されます。
 * [userId] は null、元のコピーです。もう一方のコピー（クローン、作品情報）にユーザー番号を書き込みます。
 */
class AfterTaskMarks {
    data class Mark(val packageName: String, val userId: Int?, val keep: Boolean, val reason: String) {
        /** と携帯電話側の間で合意されたキー: 元のコピーはパッケージ名、もう一方のコピーはパッケージ名@ユーザーです。 */
        val key: String get() = key(packageName, userId)
    }

    private val marks = LinkedHashMap<String, Mark>()

    /** 項目を書き留めます。このアプリの前の項目を返します (null はありません)。 */
    @Synchronized
    fun mark(packageName: String, userId: Int?, keep: Boolean, reason: String): Mark? {
        val m = Mark(packageName, userId, keep, reason)
        return marks.remove(m.key).also { marks[m.key] = m }
    }

    @Synchronized
    fun all(): List<Mark> = marks.values.toList()

    fun keepKeys(): List<String> = all().filter { it.keep }.map { it.key }
    fun closeKeys(): List<String> = all().filter { !it.keep }.map { it.key }

    companion object {
        fun key(packageName: String, userId: Int?): String = if (userId == null) packageName else "$packageName@$userId"
    }
}
