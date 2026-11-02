package com.wangzan101.mika.core.userscreen

import com.wangzan101.mika.core.observation.Untrusted
import com.wangzan101.mika.core.prompt.Feedback
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * はここだけのモデルにユーザー画面付きの文章を書き込む方法です。アルバム内の写真とは別にします (添付ファイル、静止画ファイル):
 * - ユーザーの文自体は、「ユーザーは見ている画面 (アプリ、時間) のスクリーンショットを撮ったところです。追記なし / PS:...」 (スクリーンショットを撮るときに真実を語ることは許可されていません) と書かれています。
 * - 画面上の単語と数字は別のコンテキスト ([detail]) です。追記がない場合は画面を押して意図を推測するルールに従ってください。
 * - 以前のラウンドで画面に残っているのは 1 行 ([earlier]) だけです: アプリ、時間、画面上の最初のいくつかの単語。もう一度見たい場合は、番号を押すと戻ります。
 */
object UserScreenPrompt {
    /** 前のラウンドの画面上のいくつかの単語の始まり。 */
    const val GIST_CHARS = 40

    private val TIME = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH)

    fun time(at: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(at).atZone(zone))

    /** ユーザーは次のように述べています: [screen] は null です。これは、スクリーンショットがもう存在しないことを意味します。 */
    fun said(id: String, screen: UserScreen?, words: String, zone: ZoneId): String {
        if (screen == null) return listOf(Feedback.format("user_screen.missing", "id" to id), words).filter(String::isNotBlank).joinToString("\n")
        val vars = arrayOf("app" to screen.app, "time" to time(screen.takenAt, zone), "words" to words)
        return when {
            screen.blocked && words.isEmpty() -> Feedback.format("user_screen.blocked", *vars)
            screen.blocked -> Feedback.format("user_screen.blocked_with_words", *vars)
            words.isEmpty() -> Feedback.format("user_screen.sent", *vars)
            else -> Feedback.format("user_screen.sent_with_words", *vars)
        }
    }

    /**
     * この画面の詳細 (コンテキスト テキスト): 数字、アプリ、時間、画面上の単語 (信頼できないマークを付けます: 画面上の単語は他の人またはアプリによって書かれたものです)。
     * と[noWords]については、画面を押すことで意図を推測するルールに従います。 [askVision] の場合、写真が表示されない場合は、写真のモデルを確認するように依頼できます。スクリーンショットを禁止するページには、スクリーンショットが撮れないことと返信方法のみが記載されています。
     */
    fun detail(screen: UserScreen, noWords: Boolean, askVision: Boolean, zone: ZoneId): String {
        if (screen.blocked) return Feedback["user_screen.blocked_reply"]
        val lines = if (screen.lines.isEmpty()) Feedback["user_screen.no_text"]
            else Untrusted.wrap(screen.lines.joinToString("\n") { "(${it.x},${it.y}) ${it.text}" })
        val head = Feedback.format(
            "user_screen.detail", "id" to screen.id, "app" to screen.app,
            "package" to (screen.packageName?.takeIf { it != screen.app }?.let { "($it)" }.orEmpty()),
            "time" to time(screen.takenAt, zone), "lines" to lines,
        )
        return listOfNotNull(head, Feedback["user_screen.ask_vision"].takeIf { askVision }, Feedback["user_screen.no_words"].takeIf { noWords })
            .joinToString("\n\n")
    }

    /** 前のラウンドでは、1 つの画面と 1 つのラインが収集されました。スクリーンショットがなくなったときは、もう存在しないと表示されました。 */
    fun earlier(id: String, screen: UserScreen?, zone: ZoneId): String {
        if (screen == null) return Feedback.format("user_screen.missing", "id" to id)
        val vars = arrayOf("id" to id, "app" to screen.app, "time" to time(screen.takenAt, zone))
        if (screen.blocked) return Feedback.format("user_screen.earlier_blocked", *vars)
        val gist = gist(screen) ?: return Feedback.format("user_screen.earlier_blank", *vars)
        return Feedback.format("user_screen.earlier", *vars, "gist" to gist)
    }

    /** 画面上の最初のいくつかの単語 (非常に短いフラグメントをスキップ) は、前のラウンドの行に使用されます。単語がない場合は null です。 */
    fun gist(screen: UserScreen): String? {
        val text = screen.lines.map { it.text }.filter { it.length >= 2 }.joinToString(" ").ifEmpty { return null }
        return if (text.length <= GIST_CHARS) text else text.take(GIST_CHARS) + "…"
    }

    /** 前のラウンドでユーザーが言ったこと: 画面上の行を [earlier] に置き換えると、追記が続きます (1 行)。スクリーン無しでそのまま返却。 */
    fun collapse(text: String, lookup: (String) -> UserScreen?, zone: ZoneId): String {
        val p = UserScreenMessage.parse(text)
        if (!p.hasScreen) return text
        return (p.screens.map { earlier(it, lookup(it), zone) } + p.words.replace('\n', ' ')).filter(String::isNotBlank).joinToString(" ")
    }
}
