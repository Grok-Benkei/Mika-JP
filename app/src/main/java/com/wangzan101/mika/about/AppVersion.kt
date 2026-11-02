package com.wangzan101.mika.about

/**
 * バージョン番号の比較。「major.minor.revision[-pre-release][+build]」の習慣に従って、先頭の v を削除し、数値フィールドを 1 つずつ比較します (欠落しているものは 0 として扱われます)。
 * 番号が同じ場合、プレリリース版よりも正式版の方が新しいことを示します。両方にサフィックスがある場合、比率は点線のセグメントに基づきます (数値はサイズに応じて、テキストは辞書順に並べられ、数値フィールドはテキスト フィールドの前に並べ替えられます)。
 * ビルド サフィックス (+ の後) は比較に参加しません。
 */
object AppVersion {
    fun normalize(v: String): String = v.trim().removePrefix("v").removePrefix("V").trim()

    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val c = coreA.getOrElse(i) { 0 }.compareTo(coreB.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            preA == null && preB == null -> 0
            preA == null -> 1
            preB == null -> -1
            else -> comparePre(preA, preB)
        }
    }

    /** [candidate] は [current] よりも新しいです。 */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    private fun split(v: String): Pair<List<Int>, String?> {
        val s = normalize(v).substringBefore('+')
        val core = s.substringBefore('-')
        val pre = if ('-' in s) s.substringAfter('-').takeIf { it.isNotEmpty() } else null
        val parts = core.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        return parts to pre
    }

    private fun comparePre(a: String, b: String): Int {
        val pa = a.split('.')
        val pb = b.split('.')
        for (i in 0 until minOf(pa.size, pb.size)) {
            val na = pa[i].toIntOrNull()
            val nb = pb[i].toIntOrNull()
            val c = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1
                nb != null -> 1
                else -> pa[i].compareTo(pb[i])
            }
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }
}
