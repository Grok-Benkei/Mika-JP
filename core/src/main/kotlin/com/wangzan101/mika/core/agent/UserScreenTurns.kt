package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.userscreen.UserScreen
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import com.wangzan101.mika.core.userscreen.UserScreenPrompt
import com.wangzan101.mika.core.userscreen.UserScreenSource
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/**
 * タスクでユーザーによって送信された画面: 説明とスクリーンショットを読み上げ、画面の文をモデルの画面に置き換えます ([UserScreenPrompt] を参照)。
 * この文の画面（冒頭の元の単語、中断部分）がいっぱいです：その文、スクリーンショット（[seesImages]のとき）、および詳細。前のラウンドでは 1 行だけが残っています。
 * に [source] がない場合 (この入口にユーザー画面がない場合)、行は変更されません。
 */
internal class UserScreenTurns(
    private val source: UserScreenSource?,
    private val zone: ZoneId,
    private val seesImages: Boolean,
    /** ビューモデルについて詳しく教えてください（view_user_screenがあり、ビューモデルがありますが、メインモデルでは画像が表示されません）。 */
    private val canAskVision: Boolean,
    private val image: (ByteArray) -> Part.Image,
    /** この文により、Quan は全画面表示されます (証拠台帳に記録されます)。 */
    private val seen: (UserScreen) -> Unit = {},
) {
    /**
     * は文に置き換えられます。[said] はユーザーの文 (モデルに与えられた元の単語)、[images] はそれに接続され、[details] は次の詳細 (ソース → テキスト) です。
     * [words] はユーザーが入力した単語です (記憶の引用、意図の判断、言語プロンプト、および回答の言語チェックのためにのみ参照してください)。
     */
    class Rendered(val said: String, val images: List<Part>, val details: List<Pair<String, String>>, val words: String)

    private val cache = HashMap<String, UserScreen?>()

    /** この文に画面が付いている場合は、モデルに置き換えられます。提供されない場合は null になります。 */
    suspend fun render(raw: String): Rendered? {
        val p = UserScreenMessage.parse(raw)
        if (!p.hasScreen) return null
        val screens = p.screens.map { it to load(it) }
        screens.forEach { (_, s) -> s?.let(seen) }
        val said = screens.mapIndexed { i, (id, s) -> UserScreenPrompt.said(id, s, if (i == screens.lastIndex) p.words else "", zone) }
            .joinToString("\n")
        val images = if (!seesImages) emptyList() else screens.mapNotNull { (id, s) ->
            if (s == null || s.blocked) null else read { source?.image(id) }?.let(image)
        }
        val details = screens.mapNotNull { (_, s) ->
            s?.let { "user's screen · ${it.app}" to UserScreenPrompt.detail(it, p.words.isEmpty(), canAskVision && !it.blocked, zone) }
        }
        return Rendered(said, images, details, p.words)
    }

    /** 前のラウンドでユーザーが言ったこと (元の単語と感動詞): 画面上の行に説明が表示されます。 */
    suspend fun collapse(turns: List<Turn>): List<Turn> {
        if (turns.none { t -> UserScreenMessage.has(t.userText) || t.steers.any(UserScreenMessage::has) }) return turns
        turns.flatMap { listOf(it.userText) + it.steers }.flatMap { UserScreenMessage.parse(it).screens }.distinct().forEach { load(it) }
        fun one(text: String) = UserScreenPrompt.collapse(text, { cache[it] }, zone)
        return turns.map { t -> t.copy(userText = one(t.userText), steers = t.steers.map(::one)) }
    }

    /** 画面で回答する場合（ミカが質問するときにカプセルをダブルクリックした場合）：テキストに変更し、レビューツールを使用してスクリーンショットを表示します。 */
    suspend fun asText(raw: String): String {
        val r = render(raw) ?: return raw
        return (listOf(r.said) + r.details.map { it.second }).joinToString("\n\n")
    }

    private suspend fun load(id: String): UserScreen? = cache.getOrPut(id) { read { source?.screen(id) } }

    private inline fun <T> read(block: () -> T?): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
}
