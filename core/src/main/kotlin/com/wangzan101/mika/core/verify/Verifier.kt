package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.agent.JapaneseOutput
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelJson
import com.wangzan101.mika.core.model.ModelJson.field
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.vision.ImageOps
import com.wangzan101.mika.core.vision.ModelImage

/**
 * チェック時の比較基準: [items] は作成後に確認されるべき事実です。 [kind] は読み取り/取り消し不可です (送信、送信、注文、削除、支払いなど、回復できない操作が実行されました)。
 * [fallback] は、他にリストする標準がないことを意味し、[items] はユーザーのオリジナルの単語そのものです (これはタスク パスでは常に当てはまり、リストするための別のモデル呼び出しは必要ありません)。
 */
data class Criteria(val items: List<String>, val kind: String, val fallback: Boolean) {
    companion object {
        /** タスク パスに使用される標準: ユーザーのオリジナルの単語そのもの。 */
        fun of(goal: String, irreversible: Boolean) = Criteria(listOf(goal), if (irreversible) "irreversible" else "read", fallback = true)
    }
}

enum class Verdict(val wire: String) { Pass("pass"), Fail("fail"), CannotVerify("cannot_verify") }

/** は標準のチェック結果です。 [status] は満たされている / 満たされていない / 不明であり、[quote] はそれをサポートする元の画面テキストです。 */
data class CriterionCheck(val criterion: String, val status: String, val quote: String?)

/**
 * [groundedBy] は「画像」です。画面上のステータス (数字のない引用) のみがテキスト証拠では見つかりません。スクリーンショットを見た検証者が合格します。
 * は、走行記録のテキストによって検証されたパスとは区別されます。 [notFound] は、どの証拠ソースにも見つからない答えです。
 */
data class Verification(
    val verdict: Verdict,
    val checks: List<CriterionCheck>,
    val reason: String,
    val groundedBy: String? = null,
    val notFound: List<String> = emptyList(),
    /** 表示文を日本語へ置き換えた場合の原文。作業モデルへの修正指示にだけ使う。 */
    val originalReason: String? = null,
) {
    val feedbackReason: String get() = originalReason ?: reason
    /** 基準が満たされていません。ベリファイアが失敗した場合に示された理由を使用しますが、どの理由であるかは示されていません。 */
    val unmet: List<String>
        get() = checks.filter { it.status == "unmet" }.map { it.criterion }.ifEmpty { if (verdict == Verdict.Fail) listOf(reason) else emptyList() }
}

/**
 * 証拠が検証者に渡されました。 [fresh] は、回答後に私が最近使用している能力を示しています ([com.wangzan101.mika.core.capability.Capability.freshLook]:
 * インターフェイスのステータス、Web ページのテキストの先頭...)、[freshImage] は同時に画面です (画像を添付する場合にのみ利用可能)。
 * [ledger] は、このタスクで読み取られたすべての観察です (証拠台帳、[EvidenceLedger] を参照。検証中の回答に従って関連エントリを選択します)。
 * [actions] は、簡略化されたアクション リストです (「ステップ 3 タップ (検索) が有効になります」)。
 * [protected] は、現時点でページがスクリーンショットから保護されていることを示します。 [appOpened] が false の場合、このタスクでアプリが正常に開かれていないことを意味します。
 */
class Evidence(
    val fresh: List<Observation>,
    val freshImage: ByteArray?,
    val ledger: EvidenceLedger,
    val actions: List<String>,
    val protected: Boolean,
    val appOpened: Boolean = true,
    /** 各テイクオーバーの終了は、アクション リストとともにロールアウトされません (スキップまたはタイムアウトは、ユーザーが処理しなかったことを示します)。 */
    val takeovers: List<String> = emptyList(),
    /** ユーザーから提供された事実: メモリ検索結果、このタスクの質問への回答。 */
    val userFacts: List<String> = emptyList(),
    /** 稼働時計: 現在の時刻とタイムゾーンを確認します。回答テキストの「明日」と「日曜日」の隣の日付は、それに応じて変換され、チェックされる必要があります ([Grounding.check] を参照)。 */
    val nowMs: Long = System.currentTimeMillis(),
    val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
    /** 時計が動作している日付。 */
    val today: java.time.LocalDate get() = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()

    /** 読んだテキストをもう一度見て、各段落を観察してください。 */
    val freshText: String get() = fresh.joinToString("\n") { it.text }

    /** はアプリを開いておらず、インターフェイス、Web ページ、またはコマンド出力も読みませんでした。答えは会話または常識に基づいてのみ可能であり、参考資料はありません。 */
    val nothingRead: Boolean get() = !appOpened && !protected && freshText.isBlank() && ledger.isEmpty && userFacts.isEmpty()

    /** 台帳内の特定の種類の観察の元のテキスト (台帳に記録されている内容を参照) が読み取られた順序で表示されます。 */
    fun read(vararg kinds: ObsKind): List<String> = ledger.all.filter { it.kind in kinds }.map { it.text }

    /** は、回答の全文を比較し、一度に 1 か所ずつ読み取ります。台帳 [chosen] (全文) 内の選択されたエントリ、ユーザーが指定した各事実をもう一度確認します。 */
    fun sources(chosen: List<Observation>): List<String> =
        (listOf(freshText) + chosen.map { it.text } + userFacts).filter { it.isNotBlank() }
}

/**
 * 最終的な回答が渡される前の独立した検証 (不可逆的なまたは外部的な何かを行った場合、または文字通りの検証が正しくない場合にのみ質問してください。AnswerReview を参照): 新しいモデルの呼び出し。ツールや作業モデルの履歴は使用せず、目標、基準、回答、証拠のみを確認します。
 * スクリーンショットとビジュアル モデルがある場合は、[visionModel] を使用して写真を添付し​​、そうでない場合は [textModel] を使用します。
 * 分業: コード概要 ([Grounding]) は文字通りの意味のみを検証します - モデルは合格しますが、回答内の特定の事実 (引用、単位付きの数字、数値、日付) が証拠テキストで見つかりません。
 * → 検証できません。スクリーンショットが添付されていますが、唯一見つけられないのは、画面上のステータス→パス、写真を見て確認としてマークされています。値は回答に記載されているものに属しますか?
 * (どのページ、どの製品、どの行) は検証モデルによって判断されます。検証モデルに渡される台帳エントリは抜粋 ([EvidenceLedger.excerpt]) であり、出典が記載されています。
 * エラー、応答を解析できません →Cannot_verify;タイムアウトは証拠の量に応じて緩和されます ([timeoutMs])。タイムアウト後、証拠は整理されて再度質問され、タイムアウトが継続するまで検証できません。
 * 何も読んでいないタスク ([Evidence.nothingRead]) には、比較する材料がありません。これは行われていません。チェッカーには資料が読まれていないことだけが通知され、回答が適切かどうかが判断されます。
 */
class Verifier(
    private val textModel: ChatModel,
    private val visionModel: ChatModel?,
    /** のスクリーンショットは、[ModelImage] の統一コードに従って送信されます。空の場合はそのまま送信されます。 */
    private val ops: ImageOps? = null,
) {
    /** に画像を添付する価値があるのは、ビジュアル モデルがある場合のみです。 */
    val wantsImage: Boolean get() = visionModel != null

    /**
     * [earlierGoals] は、このダイアログの前のラウンドでユーザーが言ったこと (作業モデルからのものは何も含まず、ユーザーのオリジナルの言葉のみ) であり、質問するときに参照を理解するために使用されます。
     * ターゲット、回答、画面読み上げ、アクションリストはすべて信頼できない資料です。これらは JSON オブジェクトにコンパイルされて送信されます。改行を入れても新しい段落は作成できません。
     */
    suspend fun verify(goal: String, criteria: Criteria, answer: String, evidence: Evidence, earlierGoals: List<String> = emptyList()): Verification {
        val chosen = evidence.ledger.forAnswer(goal, answer)
        val reads = evidence.sources(chosen)
        if (evidence.protected && reads.isEmpty()) return cannot("結果画面はシステムのスクリーンショット保護により読み取れないため、確認できません")
        val image = evidence.freshImage?.takeIf { visionModel != null && !evidence.protected }
        val model = if (image != null) visionModel!! else textModel
        val original = when (val first = ask(model, goal, criteria, answer, evidence, chosen, EvidenceLedger.EXCERPT_CHARS, earlierGoals, image)) {
            is Asked.Done -> first.v
            is Asked.Failed -> return cannot(first.reason)
            // 大量の証拠を伴う長いタスクはタイムアウトになりやすいです。合理化してもう一度質問してください
            Asked.TimedOut -> when (val second = ask(model, goal, criteria, answer, trimmed(evidence), chosen, TRIM_EXCERPT_CHARS, earlierGoals, image)) {
                is Asked.Done -> second.v
                is Asked.Failed -> return cannot(second.reason)
                Asked.TimedOut -> return cannot("検証がタイムアウトしました")
            }
        }
        val v = original
        val result = when (v.verdict) {
            Verdict.Pass -> when {
                // 各規格を一つ一つチェックしないパスはカウントされません。
                v.checks.size < criteria.items.size -> v.copy(verdict = Verdict.CannotVerify, reason = NOT_EVERY_CRITERION)
                evidence.nothingRead -> v
                else -> backstop(v, goal, answer, reads, imageSent = image != null, evidence.today)
            }
            // 上記のフォローアップの質問には基準が記載されていませんでした。始まりと終わりのない原文との比較しかできず、不合格は信頼性が低いと判断されました。
            Verdict.Fail -> if (criteria.fallback && earlierGoals.isNotEmpty()) v.copy(verdict = Verdict.CannotVerify, reason = FOLLOW_UP_UNCLEAR)
                else if (v.reason.isBlank()) v.copy(reason = FAIL_WITHOUT_REASON) else v
            Verdict.CannotVerify -> v
        }
        return if (result.reason.isBlank() || JapaneseOutput.isJapaneseProse(result.reason)) result
            else result.copy(reason = "検証モデルから日本語の理由説明が得られませんでした。", originalReason = result.reason)
    }

    private fun cannot(reason: String) = Verification(Verdict.CannotVerify, emptyList(), reason)

    private sealed interface Asked {
        class Done(val v: Verification) : Asked
        class Failed(val reason: String) : Asked
        data object TimedOut : Asked
    }

    private suspend fun ask(
        model: ChatModel, goal: String, criteria: Criteria, answer: String, evidence: Evidence, chosen: List<Observation>, budget: Int,
        earlierGoals: List<String>, image: ByteArray?,
    ): Asked {
        val body = request(goal, criteria, answer, evidence, excerpts(goal, answer, chosen, budget, evidence.today), earlierGoals)
        val parts = listOfNotNull(Part.Text(body), image?.let { ModelImage.part(it, ops) })
        var unparsed = false
        return try {
            withTimeoutOrNull(timeoutMs(body.length, if (image != null) 1 else 0)) {
                // はフォールトトレラントな解析で応答します。読み取れない場合は、JSON のみを再度尋ねます。それでも失敗する場合は、解析できないとみなされます。
                ModelJson.completeParsed(model, PROMPT, listOf(Message(Role.User, parts, Author.Mika)), ::parse).also { unparsed = it == null }
            }?.let { Asked.Done(it) } ?: if (unparsed) Asked.Failed("検証モデルの応答を解析できません") else Asked.TimedOut
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Asked.Failed("検証モデルでエラーが発生しました:${e.message ?: e::class.simpleName}")
        }
    }

    /** タイムアウト後の再検査のための合理化された証拠: 現時点では、1 つの画面とスクリーンショットが保持され、最新のアクションのみが保持され、各段落は切り詰められます。台帳エントリの抜粋には、より短い文字数制限 ([TRIM_EXCERPT_CHARS]) が適用されます。 */
    private fun trimmed(e: Evidence) = Evidence(
        trimmedFresh(e.fresh), e.freshImage, e.ledger,
        e.actions.takeLast(TRIM_ACTIONS), e.protected, e.appOpened, e.takeovers,
        e.userFacts.map { it.take(TRIM_CHARS) }, e.nowMs, e.zone,
    )

    /** もう一度見ると、[TRIM_FRESH_CHARS] 個の単語になります (順番に切り取っていきます)。 */
    private fun trimmedFresh(fresh: List<Observation>): List<Observation> {
        var room = TRIM_FRESH_CHARS
        return fresh.mapNotNull { o ->
            if (room <= 0) null else o.copy(text = o.text.take(room)).also { room -= it.text.length + 1 }
        }
    }

    private fun request(goal: String, c: Criteria, answer: String, e: Evidence, shown: List<Observation>, earlierGoals: List<String>): String {
        val material = buildJsonObject {
            put("goal", goal)
            if (earlierGoals.isNotEmpty()) put("earlierGoals", JsonArray(earlierGoals.map(::JsonPrimitive)))
            put("criteria", JsonArray(c.items.map(::JsonPrimitive)))
            if (c.fallback) put("criteriaNote", "個別の基準はありません。criteriaはユーザーの依頼の原文です。ユーザーの目標に照らして検証してください")
            put("kind", c.kind)
            put("answer", answer)
            put("freshLook", if (e.nothingRead) NOTHING_READ else freshShown(goal, answer, e).ifBlank { if (e.protected) "（スクリーンショット保護のため読み取れません）" else "（文字を読み取れませんでした）" })
            put("ledger", JsonArray(shown.map { r ->
                buildJsonObject {
                    if (r.step > 0) put("step", r.step)
                    put("kind", r.kind.wire)
                    r.source?.let { put("source", it) }
                    r.where?.let { put("where", it) }
                    r.title?.let { put("title", it) }
                    put("text", r.text)
                }
            }))
            put("actions", JsonArray(e.actions.map(::JsonPrimitive)))
            if (e.takeovers.isNotEmpty()) put("takeovers", JsonArray(e.takeovers.map(::JsonPrimitive)))
            if (e.userFacts.isNotEmpty()) put("userFacts", JsonArray(e.userFacts.map(::JsonPrimitive)))
        }
        return "次の資料を検証してください（JSON。フィールドの説明はシステム指示を参照）:\n$material"
    }

    companion object {
        /** 検証によりタスクが遅れてはなりません: 基本的な待ち時間。 [MAX_TIMEOUT_MS] までの証拠がたくさんある場合は、[timeoutMs] を押してリラックスします。 */
        const val TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 150_000L
        private const val PER_IMAGE_MS = 20_000L
        private const val PER_KB_MS = 1_000L
        private const val TRIM_FRESH_CHARS = 6_000
        private const val TRIM_CHARS = 3_000
        private const val TRIM_ACTIONS = 10
        private const val TRIM_EXCERPT_CHARS = EvidenceLedger.EXCERPT_CHARS / 2
        /** 二度見がこの文字数より長い場合は、抜粋（冒頭と事実を答える行）のみが表示されます。 */
        private const val FRESH_CHARS = 4_000
        const val IMAGE = "image"

        /** 待ち時間は読みたい内容に比例します。基本 60 秒に加え、各画像と KB のテキストごとに追加され、上限は 150 秒です。 */
        fun timeoutMs(textChars: Int, images: Int): Long =
            minOf(MAX_TIMEOUT_MS, TIMEOUT_MS + images * PER_IMAGE_MS + textChars / 1024 * PER_KB_MS)
        const val NOT_IN_READS = "読み取った画面・ページ・コマンド出力に回答の根拠が見つかりません"
        const val NOT_EVERY_CRITERION = "検証モデルが各基準を個別に確認していません"
        const val FAIL_WITHOUT_REASON = "検証モデルは未完了と判断しましたが、未達成の基準を示していません"
        const val FOLLOW_UP_UNCLEAR = "前の会話への追加質問で、具体的な基準を設定できないため、正確に検証できません"
        const val NOTHING_READ = "このタスクではアプリを開いておらず、資料も読み取っていません"
        const val TRUNCATED = "回答の根拠は画面上で省略された文字（末尾が…）です。一部しか読み取れておらず、全体を確認できません"
        private val STATUSES = setOf("met", "unmet", "unknown")

        val PROMPT: String by lazy { Prompts.role("verifier") }

        /**
         * 検証モデルに送信された台帳エントリ: 各エントリは抜粋 ([EvidenceLedger.excerpt]) に置き換えられ、事実に答える行は残される必要があり、エントリは失われません。
         * まず前後の[EvidenceLedger.EXCERPT_CONTEXT]行を抽出します。総ワード数が[budget]を超える場合は前後の行数を減らしてください。それを超える場合は、各行を平均シェア (少なくとも [EvidenceLedger.MIN_EXCERPT] ワード) にカットします。
         */
        internal fun excerpts(goal: String, answer: String, chosen: List<Observation>, budget: Int, today: java.time.LocalDate? = null): List<Observation> {
            val hits = chosen.map { Grounding.hitLines(goal, answer, it.text, today) }
            for (ctx in EvidenceLedger.EXCERPT_CONTEXT downTo 0) {
                val ex = chosen.mapIndexed { i, r -> EvidenceLedger.excerpt(r.text, hits[i], ctx) }
                if (ex.sumOf { it.length } <= budget) return chosen.mapIndexed { i, r -> r.copy(text = ex[i]) }
            }
            val cap = maxOf(EvidenceLedger.MIN_EXCERPT, budget / chosen.size.coerceAtLeast(1))
            return chosen.mapIndexed { i, r -> r.copy(text = EvidenceLedger.excerpt(r.text, hits[i], 0, cap)) }
        }

        /** 別の外観のためにモデルをチェックした人にそれを渡します。それは長い間そのままではありませんが、最初と右側に事実に答えるためのより長い行があります。 合格判定後の */
        private fun freshShown(goal: String, answer: String, e: Evidence): String {
            val t = e.freshText
            if (t.length <= FRESH_CHARS) return t
            return EvidenceLedger.excerpt(t, Grounding.hitLines(goal, answer, t, e.today), 1, FRESH_CHARS, withHead = true)
        }

        /**
         * モデル (ルールについては、[Grounding.check] を参照): リテラルのみをチェックし、値が何に属するかは判定しません。本文が一致しない場合：スクリーンショットが添付されており、画面上のステータスのみが一致しません（数字なしの引用。
         * 引用では、回答がテキスト内の他の場所で一致していることも必要です)。写真を見たことがある場合はパスがカウントされ、写真を見ることで認証として記録されます。数字と日付を文字で書くこともできますが、一致しない場合は拒否されます。
         * [today] は、実行中のクロックの日付です (相対日付はそれに応じて変換されます。[Grounding.check] を参照)。記録された実行のオフライン再計算にも使用されます。
         */
        internal fun backstop(v: Verification, goal: String, answer: String, screens: List<String>, imageSent: Boolean, today: java.time.LocalDate? = null): Verification {
            val g = Grounding.check(goal, answer, screens, today)
            val ok = when (g.found) {
                true -> true
                false -> false
                // 回答にチェックするものが何もないか、ペアが数個しかなく、引用の配置がありません。少なくとも 1 つの一致する引用が画面上に存在する必要があります。
                null -> v.checks.any { it.status == "met" && it.quote != null && Grounding.quoteInReads(it.quote, screens) }
            }
            val missing = g.unmatched.map { it.text }
            val result = when {
                ok -> v
                imageSent && g.unmatched.all { it.visualOnly } && (g.anchored || g.unmatched.none { it.kind == Grounding.Kind.Quote }) ->
                    v.copy(groundedBy = IMAGE, notFound = missing)
                else -> v.copy(verdict = Verdict.CannotVerify, reason = NOT_IN_READS, notFound = missing)
            }
            // は、画面上の切り詰められたテキスト (... で終わる) に基づいています。一部のみが表示され、全体像を確認することはできません。
            return if (result.verdict == Verdict.Pass && Grounding.builtOnTruncated(answer, screens)) result.copy(verdict = Verdict.CannotVerify, reason = TRUNCATED) else result
        }

        /**
         * 応答内の最初の読み取り可能な JSON オブジェクト ([ModelJson]: 許容コード ブロック、前後の単語、キー名の書き込みを参照)。結論が欠落しているか不明な場合は null を返します。
         * 各結論と一般的な結論の間に矛盾がある場合は、個別の結論が考慮されます。
         */
        internal fun parse(text: String): Verification? {
            val obj = ModelJson.firstObject(text) ?: return null
            val verdict = when ((obj.field("verdict", "result", "conclusion") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
                "pass", "passed" -> Verdict.Pass
                "fail", "failed" -> Verdict.Fail
                "cannot_verify", "cannotverify", "unknown" -> Verdict.CannotVerify
                else -> return null
            }
            val checks = (obj.field("checks", "criteria_checks", "results") as? JsonArray).orEmpty().mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                val criterion = (o.field("criterion", "criteria", "item") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val status = (o.field("status", "state") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf { it in STATUSES } ?: "unknown"
                val quote = (o.field("quote", "evidence") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)
                CriterionCheck(criterion, status, quote)
            }
            val reason = (obj.field("reason", "explanation") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val effective = when {
                checks.any { it.status == "unmet" } -> Verdict.Fail
                verdict == Verdict.Pass && checks.any { it.status == "unknown" } -> Verdict.CannotVerify
                else -> verdict
            }
            return Verification(effective, checks, reason)
        }
    }
}

