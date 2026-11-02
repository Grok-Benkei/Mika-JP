package com.wangzan101.mika.core.vision

import java.util.UUID

/** 0 ～ 999 正規化された座標。タップと同じ座標系。 */
data class Point(val x: Int, val y: Int)

/**
 * find_visually found Points: (ref, snapshotId) を押して登録し、最新の [capacity] (先入れ先出し) のみを残します。
 * スクリーンショットを見つけたらそのハッシュを書き留め、それをタップしたときに画面が変化したかどうかを判断するために使用します。書き込み操作後の呼び出し元による [clear]。
 */
class LocateRegistry(private val capacity: Int = 20) {
    private class Entry(val point: Point, val hash: Long)

    private var next = 1
    private val entries = LinkedHashMap<String, Entry>()

    /** は、(ref、snapshotId) を ("@v3"、"loc_…") の形式で返します。この番号はミッション内では再利用されません。 */
    fun put(x: Int, y: Int, hash: Long): Pair<String, String> {
        val ref = "@v${next++}"
        val snapshotId = "loc_${UUID.randomUUID()}"
        entries[key(ref, snapshotId)] = Entry(Point(x, y), hash)
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return ref to snapshotId
    }

    fun get(ref: String, snapshotId: String): Pair<Point, Long>? = entries[key(ref, snapshotId)]?.let { it.point to it.hash }

    fun clear() = entries.clear()

    /** [snapshotId] はここに登録され、今もここにあります。 */
    fun has(snapshotId: String): Boolean = entries.keys.any { it.startsWith("$snapshotId|") }

    private fun key(ref: String, snapshotId: String) = "$snapshotId|$ref"
}
