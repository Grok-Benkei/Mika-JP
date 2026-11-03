package com.wangzan101.mika.core.memory

import kotlin.math.ln
import kotlin.math.max

/**
 * ネイティブ語彙単語分割: 中国語の文字は隣接する 2 つの文字から取得され (単一の漢字が単語に形成されるときに単一の文字が保持されます)、英語と数字は単語と小文字に分割されます。
 * [withUnigrams] また、各漢字の 1 文字が与えられます (クエリ用。「辛」などの 1 文字の検索語に一致するようになります)。
 */
object Tokenize {
    private val WORD = Regex("[A-Za-z0-9]+")

    fun terms(text: String, withUnigrams: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        val han = StringBuilder()
        fun flushHan() {
            if (han.length == 1) out += han.toString()
            else {
                for (i in 0 until han.length - 1) out += han.substring(i, i + 2)
                if (withUnigrams) han.forEach { out += it.toString() }
            }
            han.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isHan(c)) {
                han.append(c); i++; continue
            }
            flushHan()
            val m = WORD.matchAt(text, i)
            if (m != null) { out += m.value.lowercase(); i = m.range.last + 1 } else i++
        }
        flushHan()
        return out
    }

    internal fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    internal fun isUnigram(term: String) = term.length == 1 && isHan(term[0])
}

/**
 * コンテンツ + キーワード組み込み反転、BM25 スコア (k1=1.2、b=0.75、検索語 tf は 2 回カウント)。
 * メモリの数が少ない場合、IDF は少なくとも [MIN_CORPUS] で平滑化する必要があります。そうしないと、記憶が 1 つや 2 つあるときは、何もしきい値を超えることができません。
 * は「関連」エントリのみを返します。検索用語が一致するか、少なくとも 2 つの異なるクエリ用語 (2 つの単語/単語) がヒットします。
 * もしくはヒットワードはこれだけでその検索ワードです。
 * という 1 つの単語はスコアリングにのみ関与し、これら 3 つの判定にはカウントされません。
 */
class MemoryIndex(private val items: List<MemoryItem>) {
    private val docs: List<Map<String, Int>> = items.map { item ->
        val counts = HashMap<String, Int>()
        Tokenize.terms(item.content).forEach { counts.merge(it, 1, Int::plus) }
        // 単一文字の検索語 (「スパイシー」) 自体が単一文字に分割されます。単一単語のノイズが 2 単語のヒットに影を落とすのを防ぐため、複数文字の検索語は個別の文字に分割されません。
        item.keywords.flatMap { Tokenize.terms(it) }.forEach { counts.merge(it, KEYWORD_TF, Int::plus) }
        counts
    }
    private val lengths = docs.map { d -> d.values.sum() }
    private val keywordTerms: List<Set<String>> = items.map { it.keywords.flatMap { k -> Tokenize.terms(k.lowercase()) }.toSet() }

    /** スコアの高い順に並べ替えます。 */
    fun search(query: String, limit: Int, includePending: Boolean = false): List<Pair<MemoryItem, Double>> {
        val eligible = items.indices.filter { includePending || items[it].status == MemoryStatus.active }
        if (eligible.isEmpty() || limit <= 0) return emptyList()
        val corpus = max(eligible.size, MIN_CORPUS).toDouble()
        val avgLen = eligible.sumOf { lengths[it] }.toDouble() / eligible.size
        val q = query.lowercase()
        val terms = Tokenize.terms(q, withUnigrams = true).distinct().filter { it !in STOP_TERMS }
        if (terms.isEmpty()) return emptyList()
        val termSet = terms.toSet()
        val df = terms.associateWith { t -> eligible.count { t in docs[it] } }
        val idf = df.mapValues { (_, n) -> ln(1 + (corpus - n + 0.5) / (n + 0.5)) }

        return eligible.mapNotNull { i ->
            val doc = docs[i]
            val matched = terms.filter { it in doc }
            if (matched.isEmpty()) return@mapNotNull null
            val multi = matched.filterNot(Tokenize::isUnigram)
            val keywordHit = items[i].keywords.any { kw ->
                val k = kw.lowercase()
                val kt = Tokenize.terms(k)
                q.contains(k) || (kt.isNotEmpty() && kt.all { it in termSet })
            }
            // は 1 つの単語のみヒットします。この単語はこの記事内にのみ存在し、この記事の検索語である必要があります (「後で」などの一般的な単語はカウントされません)
            val relevant = keywordHit || multi.size >= 2 ||
                (multi.size == 1 && df.getValue(multi.single()) == 1 && multi.single() in keywordTerms[i])
            if (!relevant) return@mapNotNull null
            val norm = K1 * (1 - B + B * lengths[i] / avgLen.coerceAtLeast(1.0))
            val score = matched.sumOf { t ->
                val tf = doc.getValue(t)
                idf.getValue(t) * tf * (K1 + 1) / (tf + norm)
            }
            items[i] to score
        }.sortedByDescending { it.second }.take(limit)
    }

    private companion object {
        const val K1 = 1.2
        const val B = 0.75
        const val KEYWORD_TF = 2
        const val MIN_CORPUS = 50
        // は、クエリ語、つまりほぼすべての文に含まれる言語機能語の組み合わせに対してのみ機能します。それらだけでは関連性があると見なすことはできません。
        val STOP_TERMS = setOf(
            "帮我", "给我", "一下", "我的", "不要", "这个", "那个", "可以", "一个", "用户",
            "今天", "这次", "现在", "什么", "怎么", "我们", "一些", "看看",
        )
    }
}
