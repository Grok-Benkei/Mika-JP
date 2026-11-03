package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.tool.Entry
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.uistate.dialogJson
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.EvidenceLog
import com.wangzan101.mika.core.tool.HINT_NOT_SETTLED
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.failCode
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.uistate.session
import com.wangzan101.mika.core.uistate.DwellStop
import com.wangzan101.mika.core.uistate.dwellStopped

/** Web ページを開きます。アプリへのリンク (アプリ ストアの Web リンクを含む) は、内蔵ブラウザーでは開かれません。 */
internal object Navigate : WebTool {
    override val verb = Verb.Navigate
    override val label = "Webページを開く"
    override val risk = Risk.Write
    override val entry = Entry.Opens
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("web_navigate",
        Prompts.tool("web_navigate"),
        mapOf(
            "url" to str("Full http:// or https:// address."),
            "newTab" to buildJsonObject { put("type", "boolean"); put("description", "Open in a new tab instead of the current one (default false).") },
        ), listOf("url"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val url = a.str("url", max = 4000)!!.trim()
        val newTab = a.bool("newTab", false)
        WebUrlPolicy.external(url)?.let { return externalLink(it.url, it.packageName) }
        WebUrlPolicy.checkNavigable(url)?.let { throw ArgError("url", it) }
        // 同じ Web サイトの前のページを十分に見ていません。まず滞在期間を埋め合わせてください (滞在中は、前のページを軽くスクロールできます)。このウェブサイトのこのタスクのページは、上限に達すると開かれなくなります。
        try { ctx.dwell?.before(url, ctx.web) } catch (e: DwellStop) { return dwellStopped(e) }
        val r = try { ctx.web!!.navigate(url, newTab) } catch (e: WebTabCrashed) { return failCode("tab_crashed") } catch (e: WebTabHandedOver) { return failCode("handed_over") } catch (e: WebDialogOpen) { return failCode(DIALOG_OPEN) }
        r.externalLink?.let { return externalLink(it.url, it.packageName) }
        // がページを変更しました: 以前の画面読み取りの数値を座標に変換できなくなりました
        ctx.session.lastSnapshot = null
        if (r.loaded) ctx.dwell?.seen(r.tab.url)
        // Web サイトのアドレスはファイルです。これを開くとダウンロードになります。ダウンロード ステータスは true ですが、Web ページが開かれたことを意味するものではありません。
        r.download?.let { d ->
            return ok {
                put("loaded", false); put("tabId", r.tab.tabId)
                put("download", buildJsonObject {
                    put("file", d.file); put("status", d.status); put("bytes", d.bytes)
                    d.mime?.let { put("mime", it) }; d.error?.let { put("error", it) }
                })
                put("hint", Feedback.format("navigate_became_download", "file" to JsonPrimitive(d.file).toString(), "status" to d.status))
            }
        }
        // メイン ドキュメントが開かれていません: ブラウザのエラー コードをモデルに与えると、成功は報告されません。
        r.netError?.let { e ->
            return ToolExecution(buildJsonObject {
                put("ok", false); put("error", "navigation_failed"); put("netError", e); put("url", r.upgradedFrom ?: url)
                r.upgradedFrom?.let { put("triedHttps", true) }
                put("message", Feedback.format("navigation_failed", "code" to e))
                put("recoverable", true)
            })
        }
        return ok {
            r.dialog?.let { put("dialog", dialogJson(it)) }
            r.upgradedFrom?.let { put("upgradedToHttps", true); put("requestedUrl", it) }
            put("tabId", r.tab.tabId); put("url", r.tab.url); put("title", r.tab.title)
            r.httpStatus?.let { put("httpStatus", it) }
            put("loaded", r.loaded); put("newTab", r.newTab)
            if (r.settled == false) put("settled", false)
            if (r.dialog != null) put("hint", Feedback["page_dialog"])
            else if (!r.loaded) put("hint", Feedback["page_loading"])
            else if (r.settled == false) put("hint", HINT_NOT_SETTLED)
        }
    }

    private fun externalLink(url: String, pkg: String?) = ToolExecution(buildJsonObject {
        put("ok", false); put("error", "external_app_link"); put("url", url)
        pkg?.let { put("package", it) }
        put("message", Feedback["external_app_link"])
        put("recoverable", true)
    })

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("url")

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog) = listOfNotNull(pageFact(name, r))
}

/** 開いたWebページのURL、タイトル、HTTPステータスが引用できることも事実です。 */
internal fun pageFact(name: String, r: ToolExecution): Observation? {
    if (r.content["ok"] != JsonPrimitive(true)) return null
    fun res(k: String) = (r.content[k] as? JsonPrimitive)?.contentOrNull
    return webFact("$name: URL ${res("url")}、Webページタイトル「${res("title").orEmpty()}」" + (res("httpStatus")?.let { "、HTTPステータス $it" } ?: ""))
}

/** Web ページによって指定されたファクト (Web アドレス、タイトル、タグ)。 */
internal fun webFact(text: String) = Observation(ObsKind.Fact, text, source = WEB)

internal object Tabs : WebTool {
    override val verb = Verb.Tabs
    override val label = "Webタブを切り替え"
    override val risk = Risk.Write
    override val logged = true
    override val def = ToolDef("web_tabs",
        Prompts.tool("web_tabs"),
        mapOf(
            "action" to str("What to do.", listOf("list", "switch", "close")),
            "tabId" to str("Tab to switch to or close; required for switch and close."),
        ), listOf("action"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val action = a.str("action")!!
        val id = a.str("tabId", max = 200)
        val w = ctx.web!!
        return when (action) {
            "list" -> {
                val all = w.tabs()
                ok {
                    put("tabs", JsonArray(all.map { t ->
                        buildJsonObject { put("tabId", t.tabId); put("title", t.title); put("url", t.url); put("current", t.current) }
                    }))
                }
            }
            "switch" -> {
                if (id == null) throw ArgError("tabId", "required for switch")
                val t = try { w.switchTab(id) } catch (e: WebTabHandedOver) { return failCode("handed_over") }
                    ?: return fail("tab_not_found", Feedback.format("tab_not_found", "id" to id))
                // がラベルを変更しました: 以前の画面表示はこのページではなくなりました
                ctx.session.lastSnapshot = null
                ok { put("tabId", t.tabId); put("title", t.title); put("url", t.url) }
            }
            "close" -> {
                if (id == null) throw ArgError("tabId", "required for close")
                if (!w.closeTab(id)) fail("tab_not_found", Feedback.format("tab_not_found", "id" to id))
                else { ctx.session.lastSnapshot = null; ok { put("closed", id) } }
            }
            else -> throw ArgError("action", "must be list, switch or close")
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        listOfNotNull(o.argStr("action"), o.argStr("tabId")).joinToString(" ")

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        val tabs = (r.content["tabs"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOf(r.content)
        return listOfNotNull(tabs.mapNotNull { t ->
            val title = (t["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            "タブ ${(t["tabId"] as? JsonPrimitive)?.contentOrNull}: URL ${(t["url"] as? JsonPrimitive)?.contentOrNull}、Webページタイトル「$title」"
        }.takeIf { it.isNotEmpty() }?.let { webFact(it.joinToString("\n", "$name:\n")) })
    }
}

internal object SiteMode : WebTool {
    override val verb = Verb.SiteMode
    override val label = "Webページ表示を切り替え"
    override val risk = Risk.Write
    override val def = ToolDef("web_site_mode",
        Prompts.tool("web_site_mode"),
        mapOf("mode" to str("Version of the site to use.", listOf("desktop", "mobile"))),
        listOf("mode"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val mode = a.str("mode")!!
        if (mode != "desktop" && mode != "mobile") throw ArgError("mode", "must be desktop or mobile")
        val r = try { ctx.web!!.setSiteMode(mode == "mobile") } catch (e: WebTabCrashed) { return failCode("tab_crashed") } catch (e: WebTabHandedOver) { return failCode("handed_over") }
            catch (e: UnsupportedOperationException) { return failCode("site_mode_unavailable") }
            ?: return failCode("no_tab_open")
        // がリロードされました: 前の画面読み取り値の数値はカウントされません。
        ctx.session.lastSnapshot = null
        return ok { put("site", r.host); put("mode", if (r.mobile) "mobile" else "desktop"); put("reloaded", r.reloaded) }
    }
}
