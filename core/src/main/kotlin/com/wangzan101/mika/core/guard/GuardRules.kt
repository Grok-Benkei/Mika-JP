package com.wangzan101.mika.core.guard

/**
 * ガードを操作するための判定ルール。各ルールは同じ事実、つまりどのようなアクションが実行され、どのターゲットに作用するかを調べます (2 つのアクションのシグネチャを組み合わせたものは [actionSignature])。
 * アクションの実際の効果と、その後インターフェイスが到達する状態。 [OperationGuards] はこれらの事実に従ってカウントしますが、ここではカウントのみが指定されたレベルに変更されます。純粋な機能。
 *
 * 行き止まりに到達したら、まず思い出させます（ナッジ）：どこで行き詰まっているのかを伝え、3つの解決策を提示します（明らかに異なる道に変更する、ユーザーに尋ねる、持っているものを渡し、何が欠けているのか説明します）。
 * 各ラダーの最終ステップが停止されます。これは最後のセーフティネットにすぎず、その閾値はリマインダーよりはるかに遅れています。ゆっくりと循環するステップは、このタスクのステップ数の上限によって制限されます ([lateStop] を参照)。
 *
 * |信号 |リマインダー |停止（セーフティネット） |
 * |---|---|---|
 * |同じアクション + 同じターゲットが繰り返されました (最後の [LOOP_WINDOW] 回) | 3、6 | 12 |
 * |上記と同じ、診断インターフェイス ツール (ツールによって計算) | 6、9 | 15 |
 * |アクションには効果はありません (効果は no_effect であるか、移動後もインターフェイスは変更されません)、継続的 | 3、6、9 | 12 |
 * |ページが変更されていないときにもう一度読みましたが、新しいコンテンツは読み込まれませんでした。 5、8 | 12 |
 * |連続失敗（4回目からは抜け道をつける） | 2、4 | 8 |
 * |回復不能な障害 | 1 | 3 |
 * |新しい状態に到達せずに N 回同じ状態に戻る | 3、6 |ステップ上限の 1/4 (10 以上) |
 * | 2 つまたは 3 つの状態の間の円 (完全な円の数) | 2、4 |ステップ上限の1/5（6以上） |
 * |新しい状態に到達しない N 回の連続アクション | 8、16 |ステップ上限の 1/2 (24 以上) |
 *
 * ステップの上限がわからない場合は、[DEFAULT_BUDGET] を使用して計算してください。
 */
internal object GuardRules {
    /** は、ラダーのどのレベルまでカウントします: [threshold] がこのレベルのしきい値です。 */
    data class Rung(val threshold: Int, val severity: Severity)

    /** アクションを繰り返して、最近実行されたアクションの数を確認します。 */
    const val LOOP_WINDOW = 16

    /** タスクのステップ数の上限がわからないので、このステップ数から計算します。 */
    const val DEFAULT_BUDGET = 60

    /** 繰り返されるアクションのラダー: リマインド、再度リマインド、停止。診断インターフェイスは繰り返し行われることが多く、しきい値は高くなります。 */
    fun repeatLadder(diagnostic: Boolean): List<Int> = if (diagnostic) listOf(6, 9, 15) else listOf(3, 6, 12)

    /** アクションはラダーには影響しません。 */
    val NO_EFFECT_LADDER = listOf(3, 6, 9, 12)

    /** ページが変更されていない場合の再読み込みの手順。 */
    val REREAD_LADDER = listOf(5, 8, 12)

    /** 連続失敗用のラダー。回復不可能な障害の場合は、[UNRECOVERABLE_LADDER] を押してください。 */
    val FAILURE_LADDER = listOf(2, 4, 8)
    val UNRECOVERABLE_LADDER = listOf(1, 3)

    /** は今まで失敗し続けており、リマインダーに回避方法が添付されています。 */
    const val FAILURE_WAYS_OUT = 4

    /** は、サークル内の最大で複数の状態をチェックできます。 */
    const val MAX_CYCLE = 3

    /** は同じ状態に戻るラダーです([budget]はタスクのステップ数の上限)。 */
    fun sameStateLadder(budget: Int?) = listOf(3, 6, lateStop(budget, 4, 10))

    /** 円のはしご（完了したターン数）。 */
    fun cycleLadder(budget: Int?) = listOf(2, 4, lateStop(budget, 5, 6))

    /** 連続して新しい状態に到達しなかったアクションの数。 */
    fun noNewStateLadder(budget: Int?) = listOf(8, 16, lateStop(budget, 2, 24))

    /** セーフティ ネットのしきい値: ステップの上限の 1/[divisor]、少なくとも [floor] (リマインダーよりはるかに後)。 */
    fun lateStop(budget: Int?, divisor: Int, floor: Int): Int = maxOf(floor, (budget ?: DEFAULT_BUDGET) / divisor)

    /**
     * は、[count] がラダー [ladder] でどのレベルに到達したかをカウントします (昇順で、最後のレベルが停止します)。最初のレベルに達していないか、このレベル ([warnedAt] は通知されたしきい値です) が通知されており、これは null です。
     * 最上位に達し、かつ [canStop] のときは停止を呼びかけ、そうでなければ知らせる。
     */
    fun climb(count: Int, ladder: List<Int>, warnedAt: Int, canStop: Boolean): Rung? {
        val threshold = ladder.lastOrNull { count >= it } ?: return null
        if (threshold <= warnedAt) return null
        return Rung(threshold, if (threshold == ladder.last() && canStop) Severity.Stop else Severity.Warn)
    }

    /** 末尾が [period] 個の異なる状態の間を循環している: [length] はその循環に合致する末尾の長さ、[rounds] は何周したか。 */
    data class Cycle(val period: Int, val length: Int) {
        val rounds: Int get() = length / period
    }

    /**
     * 順に到達した各状態（隣り合うものは異なる）の末尾が、2〜[MAX_CYCLE] 個の状態の間を循環しているかどうか: A B A B は2個で1周が2周、
     * A B C A B C は3個で1周が2周。少なくとも1ステップの繰り返しがなければ null; 複数の周長が合致する場合は最も長く進んだものを取る。
     */
    fun cycle(trail: List<Long>): Cycle? {
        var best: Cycle? = null
        for (p in 2..MAX_CYCLE) {
            if (trail.size < p + 1) continue
            if (trail.takeLast(p).toSet().size != p) continue
            var len = p
            var i = trail.size - 1
            while (i - p >= 0 && trail[i] == trail[i - p]) { len++; i-- }
            if (len > p && (best == null || len > best.length)) best = Cycle(p, len)
        }
        return best
    }

    /** [consecutive] 回連続障害が発生し、そのうち [unrecoverable] 回が回復不可能な場合に与えられるレベル。そうでない場合は null です。 */
    fun failures(consecutive: Int, unrecoverable: Int): Severity? = when {
        consecutive >= FAILURE_LADDER.last() || unrecoverable >= UNRECOVERABLE_LADDER.last() -> Severity.Stop
        consecutive >= FAILURE_LADDER.first() || unrecoverable >= UNRECOVERABLE_LADDER.first() -> Severity.Warn
        else -> null
    }
}
