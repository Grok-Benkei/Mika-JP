package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.BARRIER_KINDS
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.put

/** 現在のアプリまたは Web ページをブロックしているものを特定し、それに対処するための次のステップを取得します。 */
internal object HandleObstacle : SurfaceAware {
    override val verb = Verb.HandleObstacle
    override val label = "障害に対応"
    override val risk = Risk.Read
    override val withScreens = true
    override val def = ToolDef("handle_obstacle",
        Prompts.tool("handle_obstacle"),
        mapOf("kind" to str("障害の種類: popup = 閉じられる広告・案内・更新ダイアログ、login = ログインが必要、captcha = 人間による認証が必要、access_blocked = アプリやサイトのアクセス制限（操作頻度・環境の異常、繰り返す認証など）、payment = 支払い・パスワード入力・本人確認。", BARRIER_KINDS),
            "reason" to str("What on the screen shows it.")),
        listOf("kind"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        checkSurface(a, ctx)
        val k = a.str("kind")!!
        if (k !in BARRIER_KINDS) throw ArgError("kind", "must be one of ${BARRIER_KINDS.joinToString("|")}")
        a.str("reason", max = 300)
        return ok { put("kind", k); put("hint", hint(k)) }
    }

    private fun hint(kind: String): String = when (kind) {
        "popup" -> Feedback["barrier.popup"]
        "login" -> Feedback["barrier.login"]
        "captcha" -> Feedback["barrier.captcha"]
        "access_blocked" -> Feedback["barrier.access_blocked"]
        else -> Feedback["barrier.payment"]
    }
}
