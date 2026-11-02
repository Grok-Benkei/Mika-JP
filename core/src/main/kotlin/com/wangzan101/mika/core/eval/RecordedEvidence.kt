package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.verify.EvidenceLedger
import com.wangzan101.mika.core.observation.InputHints
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.record.Redactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 実行記録から証拠台帳を再構築します。新しいレコードに入力される各観測値は、観測されたものとして (番号付きで) 記録されます。実行中の台帳と同じように、そのまま ([EvidenceLedger.restore]) に戻されます。
 * 古いレコード (観察された番号なし) は、その時点で各ツールによって会計帳簿に記録されたフィールドに従って再構築されます: 読み取りインターフェイスの要素テキスト (古い screen_text、読み取り時に観察された番号 0 に置き換えられました)
 * およびツール結果で読み取られるフィールド、結果内の作業モデルに与えられたヒント、指示、およびエラーはカウントされません。アシスタント自身が書いたもの (コマンドの実行、ファイルに書き込まれた後に読み戻される内容) は証拠として使用されません。
 * コマンドはソース内に配置され、コマンド内にそのまま表示される行 (エコー) は出力には記録されません。両方のレコードには、呼び出しパラメータに従ってアシスタントが入力ボックスに入力した単語が入力されます。
 */
internal object RecordedEvidence {
    fun ledger(events: List<RunEvent>, tools: ToolIndex): EvidenceLedger =
        if (events.any { it is RunEvent.Observed && it.id > 0 }) restored(events, tools) else legacy(events, tools)

    private fun restored(events: List<RunEvent>, tools: ToolIndex): EvidenceLedger {
        val l = EvidenceLedger()
        for (e in events) when (e) {
            is RunEvent.ModelReply -> typed(e, tools)?.let(l::noteTyped)
            is RunEvent.State -> if (e.name == TAKEOVER_STATE) l.noteTakeover()
            is RunEvent.Observed -> if (e.id > 0) l.restore(e.observation())
            else -> Unit
        }
        return l
    }

    /** 今回は、入力ボックスに入力された単語が返信されます（走行記録に入力された単語はカウントされません）。 */
    private fun typed(e: RunEvent.ModelReply, tools: ToolIndex): String? =
        if (e.acted && tools.verb(e.tool) == Verb.TypeText) obj(e.argsJson.orEmpty())?.s("text")?.takeIf { it != Redactor.MASK } else null

    private fun legacy(events: List<RunEvent>, tools: ToolIndex): EvidenceLedger {
        val l = EvidenceLedger()
        val args = mutableMapOf<String, JsonObject>()
        val labels = mutableMapOf<String, String>()
        val written = mutableSetOf<String>()
        var site: String? = null
        // 最近開いて読んだ Web ページのタイトル: その後ページを変更する可能性がない場合、スクリーン リーダーはこのページを読み上げます。
        var title: Pair<String, Int>? = null
        for (e in events) when (e) {
            is RunEvent.ModelReply -> if (e.acted) {
                val a = obj(e.argsJson.orEmpty()) ?: JsonObject(emptyMap())
                e.tool?.let { args[it] = a }
                if (tools.verb(e.tool) == Verb.TypeText) a.s("text")?.let(l::noteTyped)
            }
            is RunEvent.State -> if (e.name == TAKEOVER_STATE) l.noteTakeover()
            is RunEvent.Observed -> {
                val onSite = e.place?.kind == PlaceKind.Site
                if (onSite) site = e.place?.name
                val head = title?.takeIf { onSite }?.first
                l.add(e.observation().copy(title = head), e.step, e.atMs)
            }
            is RunEvent.ToolResult -> {
                val r = obj(e.resultJson) ?: continue
                when (tools.verb(e.tool)) {
                    Verb.Navigate, Verb.ReadPage, Verb.Tabs -> title = r.s("title")?.takeIf { r["ok"] == JsonPrimitive(true) }?.let { it to e.step }
                    // クリック、戻る、キーを押す、入力する、アプリを変更した後に表示される内容は、同じページではない可能性があります。
                    in PAGE_CHANGING -> title = null
                    else -> Unit
                }
                note(l, e, tools, r, args[e.tool] ?: JsonObject(emptyMap()), labels, written, site)
            }
            else -> Unit
        }
        return l
    }

    private fun note(
        l: EvidenceLedger, e: RunEvent.ToolResult, tools: ToolIndex, r: JsonObject, a: JsonObject,
        labels: MutableMap<String, String>, written: MutableSet<String>, site: String?,
    ) {
        val ok = r["ok"] == JsonPrimitive(true)
        // 旧レコードの名前変更前のツール名が、実行時と同じ現在の名前で記録されます。
        val name = tools[e.tool]?.name ?: e.tool
        fun add(kind: ObsKind, text: String, place: Place? = null, authored: String? = null, title: String? = null) =
            l.add(Observation(kind, text, place = place, authored = authored, title = title), e.step, e.atMs)
        fun page() = add(ObsKind.Fact, "$name:URL ${r.s("url")},ページタイトル「${r.s("title").orEmpty()}」" + (r.s("httpStatus")?.let { ",HTTP状態 $it" } ?: ""))
        when (tools.verb(e.tool)) {
            Verb.Navigate -> if (ok) page()
            Verb.ReadPage -> if (ok) {
                val links = r.list("links").joinToString("\n") { "リンク「${it.s("text")}」${it.s("url")}" }
                val images = r.list("images").filter { !it.s("alt").isNullOrBlank() }.joinToString("\n") { "画像「${it.s("alt")}」${it.s("url")}" }
                val read = listOf(r.s("text").orEmpty(), links, images).filter { it.isNotBlank() }.joinToString("\n")
                if (read.isNotBlank()) add(ObsKind.Read, "ページ本文（${r.s("url")},開始位置 ${a.s("offset") ?: "0"}文字目）\n$read", r.s("url")?.let(Place::page), title = r.s("title"))
                page()
            }
            Verb.Tabs -> if (ok) {
                val tabs = r.list("tabs").ifEmpty { listOf(r) }.filter { it.s("title") != null }
                if (tabs.isNotEmpty()) add(ObsKind.Fact, tabs.joinToString("\n", "$name:\n") { "タブ ${it.s("tabId")}:URL ${it.s("url")},ページタイトル「${it.s("title")}」" })
            }
            Verb.EvaluateJs -> if (ok) r.s("result")?.let { add(ObsKind.Read, "ページデータスクリプトの結果(${site ?: "現在のページ"})\n$it", site?.let(Place::site)) }
            Verb.SiteCommand -> if (ok) r.s("result")?.let { add(ObsKind.Read, "サイトコマンド ${a.s("name")}の結果（${site ?: "現在のページ"})\n$it", site?.let(Place::site)) }
            Verb.Script -> r.s("result")?.takeIf { it != "null" }?.let { add(ObsKind.Read, "ページスクリプトの結果(${site ?: "現在のページ"})\n$it", site?.let(Place::site)) }
            Verb.Downloads -> if (ok) r.list("downloads").takeIf { it.isNotEmpty() }?.let { list ->
                add(ObsKind.Read, list.joinToString("\n", "ダウンロード一覧（ファイル名はページ上の表記）:\n") { d ->
                    listOf("file", "status", "bytes", "error").mapNotNull { k -> d.s(k)?.let { "$k=$it" } }.joinToString(" ")
                })
            }
            Verb.Capture -> if (ok) r.s("body")?.let { add(ObsKind.Read, "ネットワーク応答(${r.s("url")},HTTP ${r.s("status")})\n$it", r.s("url")?.let(Place::page)) }
            Verb.RunCommand -> r.s("output")?.let { add(ObsKind.Output, "終了コード ${r.s("exitCode")}:\n$it", Place.command("run_command「${a.s("command")?.take(200)}」"), a.s("command")) }
            Verb.RunBundledScript -> r.s("output")?.let { add(ObsKind.Output, "終了コード ${r.s("exitCode")}:\n$it", Place.command("run_bundled_script ${a.s("source") ?: a.s("skill")}/${a.s("script")}"), a["args"]?.toString()) }
            Verb.ReadFile -> if (ok && r.s("path") !in written) r.s("content")?.let { add(ObsKind.Output, it, Place.file("read_file ${r.s("path")}")) }
            Verb.WriteFile -> if (ok) {
                r.s("path")?.let { written += it }
                add(ObsKind.Output, "write_fileの保存先: ${r.s("path")}(${r.s("bytesWritten")}バイト、現在のファイルサイズ: ${r.s("size")}バイト）")
            }
            Verb.SearchFiles -> if (ok) r.list("matches").let { m ->
                add(ObsKind.Output, m.joinToString("\n") { "${it.s("file")}:${it.s("line")}: ${it.s("text")}" }, Place.command("search_files「${a.s("pattern")?.take(100)}」"))
            }
            Verb.ShareFile -> if (ok) add(ObsKind.Output, "share_fileの保存先: ${r.s("savedAs")}")
            Verb.ListApps -> if (ok) r.list("apps").forEach { app -> app.s("package")?.let { p -> app.s("label")?.let { labels[p] = it } } }
            Verb.LaunchApp -> if (ok) a.s("package")?.let { p -> add(ObsKind.Fact, "$name が$p を開きました" + (labels[p]?.let { "（アプリ名「$it」）" } ?: "")) }
            Verb.ShareText -> if (ok) a.s("package")?.let { p -> add(ObsKind.Fact, "$name が$p の共有画面を開きました（まだ送信していません）" + (labels[p]?.let { "（アプリ名「$it」）" } ?: "")) }
            else -> Unit
        }
    }

    private const val TAKEOVER_STATE = "waiting_takeover"
    private val PAGE_CHANGING = setOf(Verb.Tap, Verb.Back, Verb.PressKey, Verb.TypeText, Verb.Swipe, Verb.Interact, Verb.Script, Verb.OpenLink, Verb.LaunchApp, Verb.ShareText,
        Verb.ShowAlarms, Verb.AddCalendarEvent, Verb.SetWheel)

    private fun obj(s: String): JsonObject? = runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull()
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.list(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
}
