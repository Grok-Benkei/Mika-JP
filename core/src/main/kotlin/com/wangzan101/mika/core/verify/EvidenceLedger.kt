package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.observation.InputHints
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.record.Redactor

/**
 * タスク (証拠台帳) でアシスタントによって実際に読み取られるすべての資料: 画面の読み取り、Web ページの読み取り、コマンドの出力と読み取りファイル、およびツールの結果の事実。
 * 実行時検証と評価検証では、同じ台帳セットと同じ選択ルール ([forAnswer]) が使用されるため、双方が相互に参照することはありません。
 *
 * - デポジット前の感度解除 ([Redactor.evidence])、各記事はソースに従って切り詰められています。同じ元の記事の最新バージョンのみが保持されます (ソースはそれに応じて更新されます)。
 *   引き継ぎ後に読み取られた入力ボックス内の単語は、アシスタントによって記録されません (引き継ぎ中にユーザーによって入力されます)。コマンド出力と同じようにコマンド自体に表示される行 (エコーされる) は記録されません。
 * - 合計金額には上限 ([capChars]) があり、その金額を超えると、最も古いものが破棄されます。これは、通常のタスクでは触れられないメモリの安全な上限です。
 * - 検証のために送信されたアイテム ([forAnswer]): 回答に特定の事実を含む (欠落していない) 各アイテム、回答に記載されている各アプリまたは URL に最も近いアイテム
 *   (後で読んだ内容は答えと矛盾する可能性があります)、および最後に読んだのは [RECENT] です。読んだ順に並べます。コードを確認するには、これらのエントリの全文を参照してください。
 *   検証モデルは、その抜粋 ([excerpt]: 事実に該当する行とその前後の行、総単語数には上限があります) とソース (どのステップ、どのソース、どのア​​プリまたは Web サイト、Web ページのタイトル) のみを参照します。
 *   はどれを表示するかを決定するだけで、検証ルール ([Grounding]) は変更されません。以前に読み取られていないものは台帳に含まれず、どれだけ選択しても検証できません。
 * - アシスタントによって入力ボックスに入力された単語は、回答によって確認されません。入力ボックス内の単語、入力ボックス、およびフォーカスがオンになっているときにこれらの単語で始まる関連行は、選択中に削除されます。
 *   検索語と偶然同じタイトルが結果に残ります。入力ボックスにどれが入っているのかわかりません（古い記録）。入力した単語と同じ行全体を削除します。
 */
class EvidenceLedger(private val capChars: Int = STORE_CHARS) {
    private val entries = ArrayDeque<Observation>()
    private val typed = mutableSetOf<String>()
    private var total = 0
    private var seq = 0
    private var afterTakeover = false

    val all: List<Observation> get() = entries.toList()
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * は観察を記録します。脱感作と切り捨て後に空の場合は、記録せず、記録したものに戻ります (番号、ステップ、時間を入力します。現時点では、[step] と [atMs] です)。 観測値内の
     * [Observation.inputs] は、その時点で入力ボックス内の単語です。 [Observation.authored] はアシスタントが自分で書いたもの (コマンドの実行) です。表示される行はそのままエコーされ、記録されません。
     */
    fun add(o: Observation, step: Int = o.step, atMs: Long = o.atMs): Observation? {
        val ins = o.inputs?.names?.map(::norm)?.filter { it.isNotEmpty() }
        var lines = Redactor.evidence(o.text).lines()
        // ユーザー自身が入力した単語 (アシスタントが入力ボックスに入力したものではない) は、引き継ぎ中に保持されません。
        if (afterTakeover && ins != null) lines = lines.filterNot { l -> norm(l).let { n -> n in ins && typed.none { it in n } } }
        o.authored?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { a -> lines = lines.filterNot { l -> norm(l).let { it.isNotEmpty() && it in a } } }
        val t = clip(o.kind, lines.joinToString("\n"))
        if (t.isBlank()) return null
        val place = o.place?.let { it.copy(name = Redactor.evidence(it.name)) }
        // エコーが削除され、書き留められた項目にはアシスタントが自分で書いた内容は含まれなくなりました。
        return insert(o.copy(text = t, place = place, step = step, atMs = atMs, id = ++seq, authored = null,
            inputs = ins?.let { InputHints(it, o.inputs.focused?.let(::norm)?.takeIf { f -> f.isNotEmpty() }) },
            title = o.title?.let(Redactor::evidence)?.takeIf { it.isNotBlank() }))
    }

    /**
     * は、実行中に記録された観測値をそのまま戻します (実行記録内の観測値は感度が下げられ、切り詰められ、エコーが削除されています。番号もその時点のものです)。
     * はフィルターを行わなくなり、支払い時と同じ古いものを削除し、合計上限を維持するだけです。これは、実行時の台帳と同じ台帳をオフラインで再構築するために使用されます。
     */
    fun restore(o: Observation): Observation {
        seq = maxOf(seq, o.id)
        return insert(o)
    }

    private fun insert(stored: Observation): Observation {
        entries.indexOfFirst { it.text == stored.text && it.place == stored.place }.takeIf { it >= 0 }?.let { total -= entries.removeAt(it).text.length }
        entries.addLast(stored)
        total += stored.text.length
        while (total > capChars && entries.size > 1) total -= entries.removeFirst().text.length
        return stored
    }

    /** この元のテキスト (入力時と同じように感度が下げられ、切り詰められた後) は、すでに会計帳簿にあります。 */
    fun has(kind: ObsKind, text: String): Boolean = clip(kind, Redactor.evidence(text)).let { t -> entries.any { it.text == t } }

    /** アシスタントは type_text を使用して単語を入力します。パスワードボックスには入っていないことに注意してください。 */
    fun noteTyped(text: String) {
        norm(text).takeIf { it.isNotEmpty() }?.let { typed += it }
    }

    /** はユーザーに引き渡されました。アシスタントが入力しなかった画面読み上げの入力ボックスに入力された単語はクレジットされなくなります。 */
    fun noteTakeover() {
        afterTakeover = true
    }

    /** 同じ元帳のコピー (実行時に元帳を検証者に渡すときに使用され、後続の画面読み取りはこのコピーに影響しません)。 */
    fun copy(): EvidenceLedger = EvidenceLedger(capChars).also { c ->
        c.entries.addAll(entries); c.typed.addAll(typed); c.total = total; c.seq = seq; c.afterTakeover = afterTakeover
    }

    /** 検証のために提出されたアイテム: クラスの説明を参照してください。削除された各項目の入力ボックスが表示されます。削除された後に単語が存在しない場合、その単語は選択されません。 */
    fun forAnswer(goal: String, answer: String): List<Observation> {
        val cands = entries.mapNotNull(::withoutEcho)
        val holds = cands.indices.filter { i -> Grounding.check(goal, answer, listOf(cands[i].text)).let { it.claims.size > it.unmatched.size } }
        val said = (goal + "\n" + answer).lowercase()
        val places = holds.mapNotNull { cands[it].where } + cands.mapNotNull { it.where }.filter { w -> placeNames(w).any { it in said } }
        val latest = places.distinct().map { w -> cands.indexOfLast { it.where == w } }
        val recent = (cands.size - RECENT until cands.size).filter { it >= 0 }
        return (holds + latest + recent).distinct().sorted().map { cands[it] }
    }

    /** 最後にクレジットされたアイテムの番号 (まだクレジットされていない場合は 0)。 [readSince] と合わせて、一定時間後に再読み込みされたかどうかを判定します。 */
    val lastId: Int get() = seq

    /** 番号 [id] それ以来、何かを読みました (画面、Web ページ、コマンド出力、およびファイルの読み取り。ツールによって報告された事実は読み取りとしてカウントされません)。 */
    fun readSince(id: Int): Boolean = entries.any { it.id > id && it.kind != ObsKind.Fact }

    /** 回答の事実はエントリと一致しています (入力ボックスのエコーは読み取られた順に削除されています)。回答に検証可能な事実がない場合は空になります。答えの源はそこから来ています。 */
    fun supporting(goal: String, answer: String): List<Observation> =
        entries.mapNotNull(::withoutEcho).filter { e -> Grounding.check(goal, answer, listOf(e.text)).let { it.claims.size > it.unmatched.size } }

    private fun withoutEcho(e: Observation): Observation? {
        if (typed.isEmpty() || e.kind != ObsKind.Read) return e
        val lines = e.text.lines()
        val ins = e.inputs?.names
        val kept = if (ins == null) lines.filterNot { norm(it) in typed } else {
            // 入力された単語を含む入力ボックス、各行から 1 行削除 (同じ単語を含む結果のタイトルは通常どおり残ります)
            val left = ins.filter { n -> typed.any { it in n } }.groupingBy { it }.eachCount().toMutableMap()
            // フォーカスのある入力ボックスに単語が入力されています。これらの単語で始まる長い行は Lenovo です
            val suggesting = e.inputs?.focused?.let { f -> typed.filter { it in f } }.orEmpty()
            lines.filterNot { l ->
                val n = norm(l)
                val k = left[n] ?: 0
                if (k > 0) { left[n] = k - 1; true } else n !in ins && suggesting.any { n.startsWith(it) && n.length > it.length }
            }
        }
        return if (kept.size == lines.size) e else kept.joinToString("\n").takeIf { it.isNotBlank() }?.let { e.copy(text = it) }
    }

    companion object {
        /** レジャー内の総単語数の安全な上限。 */
        const val STORE_CHARS = 400_000
        /** 検証モデルに送信される抜粋の総単語数の目標上限値 (事実への回答を含むエントリは失われず、各エントリが収まらない場合は短縮されます)。 */
        const val EXCERPT_CHARS = 6_000
        /** 抜粋する場合は、実際の行の前後を数行空けてください（収まりきらない場合は徐々に0にしてください）。 */
        const val EXCERPT_CONTEXT = 2
        /** 事実と一致しないエントリ (最新のどこか、最近読んだもの) の冒頭に記載されている単語は限られています。 */
        const val HEAD_CHARS = 600
        /** どうしても書き留められない場合は、各抜粋に少なくともこの単語数を残してください。 */
        const val MIN_EXCERPT = 300
        /** 検証のために送信された最新のアイテム。 */
        const val RECENT = 1
        /** 画面読み取り、Web ページ、その他の AI の回答には、それぞれ最大でこの数の単語を含めることができます。 */
        const val READ_CHARS = 6_000
        /** コマンド出力、ファイル コンテンツ、およびツール ファクトには、それぞれ最大でこの数の単語が含まれます (最初から最後まで切り詰められます)。 */
        const val OUTPUT_CHARS = 4_000

        private val WS = Regex("\\s+")
        private fun norm(s: String) = s.replace(WS, "")
        private val PLACE_SPLIT = Regex("[^\\p{L}\\p{N}]+")
        private val PLACE_NOISE = setOf("com", "cn", "net", "org", "www", "http", "https", "android", "app", "html", "m")

        /** アプリまたはウェブサイト内の質問と回答で言及できる単語 (パッケージ名やドメイン名の段落、短すぎるものや一般的なものはカウントされません)。 */
        private fun placeNames(where: String): List<String> =
            where.lowercase().split(PLACE_SPLIT).filter { it.length >= 3 && it !in PLACE_NOISE }

        /**
         * 検証モデル抜粋：[text]行と[context]行の前後の[hits]行の省略箇所に「[N行省略]」という行を記述します。一致する行がない場合
         * (または [withHead]) は、先頭の [HEAD_CHARS] を示します。
         * [cap] この記事の最大文字数は何文字ですか (行ごとに切り捨てられ、切り捨てられた部分にもマークが付けられます)。
         */
        fun excerpt(text: String, hits: Set<Int>, context: Int, cap: Int = Int.MAX_VALUE, withHead: Boolean = false): String {
            val ls = text.lines()
            var size = 0
            val head = ls.indices.takeWhile { i -> (size < HEAD_CHARS).also { size += ls[i].length + 1 } }.toSet()
            val near = hits.flatMap { h -> (h - context)..(h + context) }.filter { it in ls.indices }.toSet()
            val keep = if (hits.isEmpty() || withHead) head + near else near
            val out = StringBuilder()
            var skipped = 0
            fun flush() { if (skipped > 0) { out.append("[$skipped 行を省略]\n"); skipped = 0 } }
            var room = cap
            for (i in ls.indices) {
                if (i !in keep || room <= 0) { skipped++; continue }
                flush()
                val l = ls[i]
                if (l.length <= room) { out.append(l).append('\n'); room -= l.length + 1 }
                else { out.append(l.take(room)).append("[この行の末尾${l.length - room}文字を省略]\n"); room = 0 }
            }
            flush()
            return out.toString().trimEnd()
        }

        private fun clip(kind: ObsKind, t: String): String = when (kind) {
            ObsKind.Read, ObsKind.Reply, ObsKind.UserSaid, ObsKind.Attachment -> t.take(READ_CHARS)
            ObsKind.Output, ObsKind.Fact ->
                if (t.length <= OUTPUT_CHARS) t else t.take(OUTPUT_CHARS * 3 / 4) + "\n…（中略）…\n" + t.takeLast(OUTPUT_CHARS / 4)
        }
    }
}
