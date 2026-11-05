package com.wangzan101.mika.core.uistate

import kotlin.math.hypot

/**
 * は、新しいインターフェースにおける「同じ要素」の特徴を認識するために使用されます。 [context] は、走査順序でその前にある最新の名前付き要素です。
 * は、スクロール後に他の場所にある同じ名前のボタンが誤って認識されるのを防ぎます。 [hint]: 名前は入力ボックスのプロンプト/プリセット単語です (回転します)。
 * [inList]: 要素はリスト コンテナー内にあります (リスト項目は再利用され、同じ位置が他の項目に置き換えられる可能性があります)。
 */
data class ElementLocator(
    val role: String,
    val name: String,
    val resourceId: String?,
    val centerX: Int,
    val centerY: Int,
    val context: String?,
    val hint: Boolean = false,
    val inList: Boolean = false,
)

sealed interface Relocation {
    /** [index] は、新しいリストの添字です。 */
    data class Found(val index: Int) : Relocation
    /** はまだ存在しますが、コンテキストが変更されているか、移動しすぎています。 */
    data object Moved : Relocation
    /** には名前やリソース ID がないか、同一のものが複数存在します。 */
    data object Ambiguous : Relocation
    /** は新しいインターフェイスでは使用できなくなりました。 */
    data object Gone : Relocation
}

/** 取得時に許可される最大中心オフセット: 画面の短辺のこの比率。 */
const val RELOCATE_MAX_SHIFT = 0.15

/** 「静止」オフセットの上限: 画面の短辺の比率。 */
const val SAME_SPOT_SHIFT = 0.02

/**
 * 番号の有効期限が切れたら、[after] で唯一対応する [previous] 番号を見つけます。これは、タイプ、名前、リソース ID が同じという同じ条件が満たされる場合にのみ見つかります。
 * で、古いリスト [before] と新しいリストにはこれしかありません。コンテキストは同じです。中心の移動は短辺 [RELOCATE_MAX_SHIFT] を超えません。
 *
 * ページ全体が動かない場合 ([stationary] を参照)、カルーセルやカウントダウンなど、部分的にのみ変化するページに対処するために 2 つの点を緩和します。
 * - コンテキストは変更されましたが、要素はまだ所定の位置にあり、リスト コンテナー内にはありません (リスト項目は再利用されます)。要素はまだ見つかります。
 * - 名前は変更されましたが、新しいものと古いものは同じリソース ID、同じタイプを持ち、依然として同じ場所にあり、それが唯一の場所にあり、名前と名前の唯一の違いは数字とスペースです。
 *   (「スキップ 3」→「スキップ 2」)、または古い名前が入力ボックス回転のプリセットワード (ヒント) であることも見つかります。
 *   「フォロー」→「フォロー済み」、「次のステップ」→「支払い確認」等の意味変更はカウントされません。
 */
fun relocate(previous: ElementLocator, before: List<ElementLocator>, after: List<ElementLocator>, shortSide: Int): Relocation {
    if (previous.name.isBlank() && previous.resourceId.isNullOrBlank()) return Relocation.Ambiguous
    val spot = shortSide * SAME_SPOT_SHIFT
    val still by lazy { stationary(before, after, spot) }
    fun same(e: ElementLocator) = e.role == previous.role && e.name == previous.name && e.resourceId == previous.resourceId
    val candidates = after.withIndex().filter { same(it.value) }
    if (candidates.isEmpty()) {
        if (previous.resourceId.isNullOrBlank() || !still) return Relocation.Gone
        val inPlace = after.withIndex().filter { (_, e) ->
            e.role == previous.role && e.resourceId == previous.resourceId && shift(e, previous) <= spot
        }
        val found = inPlace.singleOrNull() ?: return Relocation.Gone
        val renamedAlike = previous.hint || stripDigits(found.value.name) == stripDigits(previous.name)
        return if (renamedAlike) Relocation.Found(found.index) else Relocation.Gone
    }
    if (candidates.size > 1 || before.count(::same) > 1) return Relocation.Ambiguous
    val (index, found) = candidates.single()
    val moved = shift(found, previous)
    if (moved > shortSide * RELOCATE_MAX_SHIFT) return Relocation.Moved
    if (found.context != previous.context && !(still && moved <= spot && !previous.inList)) return Relocation.Moved
    return Relocation.Found(index)
}

private fun stripDigits(s: String) = s.filterNot { it.isDigit() || it.isWhitespace() }

private fun shift(a: ElementLocator, b: ElementLocator) =
    hypot((a.centerX - b.centerX).toDouble(), (a.centerY - b.centerY).toDouble())

/**
 * ページは全体としては移動していません。前後にのみ表示される有名な要素が少なくとも 2 つあり (開いているページにはページ要素がほとんどありません)、それらの 80% 以上はまだ残っています。
 * リストをスクロールしたりページを切り替えたりすると、ほとんどの要素が移動したり消えたりしますが、満足のいくものではありません。
 */
internal fun stationary(before: List<ElementLocator>, after: List<ElementLocator>, spot: Double): Boolean {
    fun key(e: ElementLocator) = Triple(e.role, e.name, e.resourceId)
    val afterByKey = after.filter { it.name.isNotBlank() }.groupBy(::key)
    var shared = 0
    var inPlace = 0
    for ((k, list) in before.filter { it.name.isNotBlank() }.groupBy(::key)) {
        val a = afterByKey[k]?.singleOrNull() ?: continue
        if (list.size != 1) continue
        shared++
        if (shift(list[0], a) <= spot) inPlace++
    }
    return shared >= 2 && inPlace >= shared * 0.8
}

/** 入力ボックスの読み取りと書き込み。 [read] は、ユーザーに表示される実際のテキスト (プロンプトが表示されるときは空の文字列) を返し、それが読めない場合 (パスワード ボックスとノードが存在しない場合) は null を返します。 */
interface TextField {
    fun read(): String?
    fun write(text: String): Boolean
    /** 初回の読戻しが一致しない場合は別の入力経路で再試行します。代替がなければ同じ経路を使います。 */
    fun retry(text: String): Boolean = write(text)
}

/** 比較する前にすべての空白スペースを削除します。入力ボックスにより、番号グループにスペースが自動的に追加される場合があります。 */
private fun normText(s: String) = s.filterNot { it.isWhitespace() }

/**
 * は [text] を書き込み、検証のためにそれを読み取ります。矛盾がある場合は、最大 [attempts] 回再試行します。 [settle] は、書き込みのたびに入力ボックスが更新されるのを待ちます。
 * は「書き込み後に読み取られたテキストがたまたまターゲットである」ことのみを認識します。書き込む前にすでにターゲットになっている場合は、最初にそれをクリアする必要があります。クリアできない場合は、書き込まれているかどうか区別できなくなり、UNKNOWNが返されます。
 * 空の文字列はクリアを意味し、空として読み戻すことは MATCH を意味します。
 * 一部の入力ボックスは、アクセシビリティのために常に同じテキスト (プロンプトなど) を報告し、読み戻しは決して一致しません。読み戻されるたびに、書き込み前と同じになります。
 * が少なくとも 1 回書き込みに成功し、[reacted] が入力したテキスト (連想語など) を含む新しいテキストが入力後にインターフェイスに表示されると言う場合、
 * アプリは実際に入力を受け取り、MISMATCH ではなく UNKNOWN を返しました。
 * [secret] (パスワード ボックス) は 1 回だけ書き込み、再試行も読み取りも行いません。パスワード ボックスは元のテキストを読み取ることができず、再試行しても同じ段落がボックスに再度書き込まれるだけです。
 */
suspend fun typeAndVerify(
    field: TextField,
    text: String,
    attempts: Int = 2,
    settle: suspend () -> Unit = {},
    reacted: () -> Boolean = { false },
    secret: Boolean = false,
): Verification {
    if (secret) {
        return if (field.write(text)) Verification.UNKNOWN else Verification.MISMATCH
    }
    val want = normText(text)
    var baseline = field.read() ?: run {
        // 読取り不能でも1回書き込みますが、書込み自体の拒否は UNKNOWN にせず MISMATCH を返します。
        return if (field.write(text)) Verification.UNKNOWN else Verification.MISMATCH
    }
    if (want.isNotEmpty() && normText(baseline) == want) {
        val cleared = field.write("")
        settle()
        baseline = field.read() ?: return if (cleared) Verification.UNKNOWN else Verification.MISMATCH
        if (normText(baseline) == want) {
            return if (field.write(text)) Verification.UNKNOWN else Verification.MISMATCH
        }
    }
    var stuck = true
    var wrote = false
    repeat(attempts) { attempt ->
        val accepted = if (attempt == 0) field.write(text) else field.retry(text)
        if (!accepted) return@repeat
        wrote = true
        settle()
        val actual = field.read() ?: return Verification.UNKNOWN
        if (normText(actual) == want) return Verification.MATCH
        if (actual != baseline) stuck = false
    }
    return if (wrote && stuck && reacted()) Verification.UNKNOWN else Verification.MISMATCH
}
