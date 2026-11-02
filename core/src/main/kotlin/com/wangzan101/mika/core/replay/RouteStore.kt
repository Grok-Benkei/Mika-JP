package com.wangzan101.mika.core.replay

/** が学んだこと。 */
interface RouteStore {
    fun all(): List<Route>
    fun put(r: Route)
    fun remove(id: String)
    fun clear()
    fun count(): Int

    /** は [id] を読み出し、書き換えて、書き戻します。 [change] は null を返し、変更されません。同時使用の実装は全体としてロックする必要があり、読み取りと書き込みの間に他の変更を挿入することはできません。 */
    fun update(id: String, change: (Route) -> Route?) {
        all().firstOrNull { it.id == id }?.let(change)?.let(::put)
    }

    /** 同じテンプレートにすでにルートがある場合は、古いルートを新しいルートで置き換えます ([replacing] を参照)。同時使用の実装には全体的なロックが必要です。 */
    fun putReplacing(r: Route) {
        val old = all().filter { it.template == r.template }
        put(replacing(r, old))
        old.filter { it.id != r.id }.forEach { remove(it.id) }
    }
}

/** 同じパス: 各ステップのツール、パラメーター、目標は同じです (アンカー ポイントは画面を読み取るたびにわずかに異なるため、カウントされません)。 */
fun samePath(a: Route, b: Route): Boolean =
    a.steps.size == b.steps.size && a.steps.zip(b.steps).all { (x, y) -> x.tool == y.tool && x.argsJson == y.argsJson && x.target == y.target }

/**
 * 古いルートを同じテンプレートで置き換えた場合、新しいルートは何をもたらしますか。最大成功数を取得します。パスが変更されない場合は、エラー数と非アクティブ化ステータスが使用されます。
 * (同じパス上のどのステップが以前に一致しなかったため、新しいレコードを消去すべきではありません);パスの変更は新しいアプローチであり、エラー カウントは 0 から始まります。
 */
fun replacing(r: Route, old: List<Route>): Route {
    val successes = maxOf(r.successes, old.maxOfOrNull { it.successes } ?: 0)
    val same = old.firstOrNull { samePath(it, r) } ?: return r.copy(successes = successes)
    return r.copy(successes = successes, consecutiveMisses = same.consecutiveMisses, missStep = same.missStep, disabled = same.disabled)
}
