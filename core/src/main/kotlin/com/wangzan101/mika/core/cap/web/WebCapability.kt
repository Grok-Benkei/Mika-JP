package com.wangzan101.mika.core.cap.web

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.uistate.Surface
import com.wangzan101.mika.core.observation.Look
import com.wangzan101.mika.core.net.UrlHost
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.workspace.WorkspacePathCheck
import kotlinx.coroutines.CancellationException
import com.wangzan101.mika.core.uistate.sessions
import com.wangzan101.mika.core.uistate.DwellStop
import com.wangzan101.mika.core.uistate.IdleScroll
import com.wangzan101.mika.core.uistate.PlacePace

private const val EVIDENCE_PAGE_CHARS = 3000
private val HOST_RE = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")

/**
 * 内蔵ブラウザ: Web ページ用の一連の画面ツールに加え、Web ページを開く、ページの読み取り、ページ スクリプト、ダウンロードとアップロード。ツールは、Web ポート ([port]) に接続した後にのみ提供されます。
 * [workspacePath] は、アップロード時のワークスペース パスのチェックです (正規化とシンボリック リンクの解決はアプリ側で行われます)。
 * [pacing] は、ページごとに読むリズムです ([PagePacing] を参照)。このページでの一時停止は、ページ変更アクションの前に完了します。 [sitePace] は、ホスト名と閲覧速度に応じてサイトの知識を提供します。
 * [idle] それを与えるときは、立ち止まってページを数回そっとスクロールしてください ([IdleScroll] を参照)。
 */
internal class WebCapability(
    val port: WebPort?,
    val workspacePath: ((String) -> WorkspacePathCheck)?,
    pacing: PagePacing = PagePacing.Off,
    sitePace: (String) -> PlacePace = { PlacePace.NORMAL },
    idle: IdleScroll? = null,
) : Capability {
    override val id = WEB

    val dwell = PageDwell(pacing, sitePace, idle)

    override val folds = true

    override fun status(): CapabilityStatus = if (port != null) CapabilityStatus.Available else CapabilityStatus.Unavailable("no browser")

    override fun tools(): List<AgentTool> = WebGroup.tools

    /** 今回は、ブラウザのページからページ（[AttachedPage]の説明付き）への出題です。そのページはすでに現在のタブになっており、最初はWebページのグループが展開されます。 */
    override fun preload(task: String): Boolean = port != null && AttachedPage.has(task)

    /** ページ説明内のタイトル、URL、および選択された単語は Web ページから取得され、削除された後もユーザーが入力した単語は残ります。 */
    override fun userWords(text: String): String = AttachedPage.words(text)

    override val family = WEB_FAMILY

    override val surface = port?.let { p ->
        Surface(WEB_FAMILY, WebScreen(p, dwell), declaredTarget = ::declaredHost, targetNote = "the site host shown as currentSite (e.g. shop.example.com)",
            mode = { siteMode(p) })
    }

    /** Web ページのセットは、収集時に提供されません。 */
    override fun sections(env: PromptEnv): List<String> =
        if (env.isOpen(id) && WebGroup.tools.any { it.name in env.offered }) listOf(env.gate(Prompts.capability(WEB))) else emptyList()

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
        dwell.onWait = { ctx.pacedWaitMs += it }
        dwell.onIdleScroll = { ctx.idleScrolls += it }
        dwell.afterIdle = { ctx.sessions[id]?.reprobe() }
    }

    /** ユーザーは次のように回答しました: 同じ Web サイトのページ数の上限が再計算されます (ユーザーは同意して読み続けました)。 */
    override fun onUserAnswered(ctx: ToolContext) = dwell.userAnswered()

    /** Web ページ上の外部アクション: 今回はページ制限によって制限されません。完成後はウェブサイトのページ数制限はなくなります（一括連絡や配信はウェブサイト独自の制限内となります）。 */
    override fun onOutwardStart(ctx: ToolContext, site: String) = dwell.outwardStart(site)

    override fun onOutwardEnd(ctx: ToolContext, site: String, done: Boolean) = dwell.outwardEnd(site, done)

    /** もう一度見てください: 現時点でのこのページのインターフェイス ステータスと、ページ全体のテキストの先頭 (インターフェイス ステータスにはビューポート内の要素のみが含まれます)。 */
    override suspend fun freshLook(ctx: ToolContext, withImage: Boolean): Look? {
        val p = port ?: return null
        return ctx.sessions[id]?.freshLook(withImage) { pageStart(p)?.let { webRead(it, null) } }
    }

    /** 宣言されたアプリが Web ページ上のサイト ホスト名 (国際化ドメイン名は ASCII 形式) であり、Web ページ スクリーン リーダーのパッケージ名と一致していることを確認します。 */
    private fun declaredHost(app: String): String {
        val host = app.takeIf { it.isNotEmpty() && it.none { c -> c in "/:?#@\\ " } }?.let { UrlHost.of("https://$it") }
        if (host == null || !HOST_RE.matches(host))
            throw ArgError("app", "on the web surface, must be the site host shown as currentSite, e.g. shop.example.com")
        return host
    }

    /** テキストのページ全体の始まり。 */
    private suspend fun pageStart(p: WebPort): String? {
        val text = try { p.readPage(EVIDENCE_PAGE_CHARS, 0, scrollLazy = false).text } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        return text?.takeIf { it.isNotBlank() }?.let { "Webページ本文（冒頭）:\n$it" }
    }

    /** 現時点で現在のタグで使用されているバージョン (モバイル/デスクトップ)。読み取られない場合は null です。 */
    private suspend fun siteMode(p: WebPort): String? = try { p.siteMode() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /**
     * は、Web ページの画面ツールのセットを使用して、画面を読み取って操作します。Return キーはページに戻ることです。画面読み取り用のパッケージ名はサイトのホスト名に置き換えられます。
     * (レベルを確認し、クリックして「報告しているのが目の前のレベルかどうか」を確認します)。画面を読み取るときは、新しいページを認識するためにアドレスを [dwell] に与えます。
     * は、ページ変更アクション (クリック、戻る、ボタン、下スクロール、スライド)、入力、消去、上スクロールなどの前にこのページで一時停止した時間を補うことができます。
     * 番号の付いたポイントを押す、戻る、キーを押す、または下にスクロールする場合、画面座標は押されません。一時停止中にページを数回ゆっくりスクロールできます。座標点を押して一時停止をスライドしてもスクロールしません（座標は一致しません）。
     * この Web サイトのこのタスクのページが上限に達すると、下にスクロールする以外のアクションは実行されず、page_limit が報告されます。
     */
    private class WebScreen(private val port: WebPort, private val dwell: PageDwell) : ScreenPort by port {
        override suspend fun back(): ActionResult = paced(idle = true) { port.historyBack() }
        override suspend fun snapshot() = port.snapshot().withSite()
        override suspend fun peek() = port.peek().withSite()
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = paced(idle = true) { port.tapRef(ref, snapshotId) }
        override suspend fun tapXY(x: Int, y: Int): ActionResult = paced(idle = false) { port.tapXY(x, y) }
        override suspend fun doubleTapRef(ref: String, snapshotId: String): ActionResult = paced(idle = true) { port.doubleTapRef(ref, snapshotId) }
        override suspend fun doubleTapXY(x: Int, y: Int): ActionResult = paced(idle = false) { port.doubleTapXY(x, y) }
        override suspend fun pressKey(keyCode: Int): ActionResult = paced(idle = true) { port.pressKey(keyCode) }
        override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult =
            paced(idle = false) { port.swipe(x1, y1, x2, y2, durationMs) }

        override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult {
            if (!down) return port.scroll(ref, snapshotId, false)
            // ページの残りを読むには下にスクロールします。ページ制限に達した場合でもスクロールします。ユーザーが滞在中に中断するとスクロールしません。 最初に
            try { dwell.before(port = port, gate = false) } catch (e: DwellStop) { return ActionResult.Failed(e.code, e.detail) }
            val mark = dwell.scrollMark(port)
            return port.scroll(ref, snapshotId, true).also { dwell.afterScroll(port, mark) }
        }

        /** が滞留を補い (ページ制限に達し、滞留中にユーザーが中断した場合、滞留は行われず、page_limit または Skiped_for_new_message が報告されます)、次に [action] が行われます。 */
        private suspend fun paced(idle: Boolean, action: suspend () -> ActionResult): ActionResult {
            try {
                dwell.before(port = port.takeIf { idle })
            } catch (e: DwellStop) {
                return ActionResult.Failed(e.code, e.detail)
            }
            return action()
        }

        private fun ScreenSnapshot.withSite() = web?.let { w ->
            dwell.seen(w.url)
            copy(packageName = UrlHost.of(w.url) ?: packageName)
        } ?: this
    }

    companion object {
        val KEY = CapabilityKey<WebCapability>(WEB)
    }
}

/** このタスクの Web ページ ポート。 Web ページがない場合は null になります。 */
internal val ToolContext.web: WebPort? get() = this[WebCapability.KEY]?.port

/** このタスクはページごとに行われます。ページなしの機能は null です。 */
internal val ToolContext.dwell: PageDwell? get() = this[WebCapability.KEY]?.dwell

internal val ToolContext.workspacePath: ((String) -> WorkspacePathCheck)? get() = this[WebCapability.KEY]?.workspacePath
