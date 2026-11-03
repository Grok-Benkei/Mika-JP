package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.browser.PageAskMode
import com.wangzan101.mika.browser.PageAskModel
import com.wangzan101.mika.browser.PageConversations
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.capsule.CapsuleModel
import com.wangzan101.mika.capsule.CapsuleRun
import com.wangzan101.mika.capsule.CapsuleThreads
import com.wangzan101.mika.capsule.CardActions
import com.wangzan101.mika.capsule.CardContent
import com.wangzan101.mika.capsule.CardRun
import com.wangzan101.mika.capsule.CapsuleCards
import com.wangzan101.mika.capsule.ChatThread
import com.wangzan101.mika.capsule.PastTurn
import com.wangzan101.mika.capsule.Pinned
import com.wangzan101.mika.capsule.Steps
import com.wangzan101.mika.capsule.StopKey
import com.wangzan101.mika.capsule.ThreadList
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.confirm.ConfirmationNotifier
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.cap.web.AttachedPage
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.task.TaskRun
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.task.TaskService.Companion.isActive
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.floatShadow
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** ブラウザ ページの「ミカに聞く」セクションの固定語句。 */
object PageAskCopy {
    val HINT: String get() = tr(R.string.browser_ask_hint)
    val FROM_PAGE: String get() = tr(R.string.browser_ask_from_page)
    val OPEN_CHAT: String get() = tr(R.string.browser_ask_open_chat)
    val MENU: String get() = tr(R.string.browser_ask_menu)
    val SELECTION: String get() = tr(R.string.browser_ask_selection)
    val DROP_SELECTION: String get() = tr(R.string.browser_ask_drop_selection)
    val PAUSED: String get() = tr(R.string.browser_ask_paused)
    val PAUSED_CARD: String get() = tr(R.string.browser_ask_paused_card)
    val PAUSED_HINT: String get() = tr(R.string.browser_ask_paused_hint)
    val EXPAND: String get() = tr(R.string.browser_ask_expand)
    val WEB: String get() = tr(R.string.browser_ask_web)
    val APP: String get() = tr(R.string.browser_ask_app)
    val HANDLING: String get() = tr(R.string.browser_ask_handling)
    fun continuing(title: String): String = tr(R.string.browser_ask_continuing, title)
}

/** 新旧の記録形式から、ステップが操作した画面の種類を示す。 */
internal fun pageStepTag(target: String?): String? {
    val place = target?.trim().orEmpty()
    return when {
        recordedSiteHost(place) != null -> PageAskCopy.WEB
        PACKAGE_NAME_RE.matches(place) -> PageAskCopy.APP
        else -> null
    }
}

/**
 * ブラウザ ページは、このページでのミカのステータスについて尋ねます。このページに続く会話は次のとおりです ([conversationId]、同じページまたは Web サイトの最新の質問については、[PageConversations] を参照してください。
 * 「新しいダイアログ」が選択されている場合 ([fresh])、一番下のブロックが開いているかどうか ([open])、入力ボックス内の単語と選択されたテキスト。そのダイアログ内の現在のタスク ([run]、[card])
 * とセリフの往復（[thread]、サスペンションカプセルのセリフカードと同じ配置）。
 * によって送信された最初の文には、このページ ([AttachedPage]) の説明が含まれており、ページはそのタスク ([UserBrowser.lend]) に貸与されています。実行中にタスクが言うことは間投詞です (質問に答えるときは答えになります)。
 * はすべて、タスク サービスの既存の入り口を使用します。メインスレッドでのみ使用されます。
 */
@Stable
class PageChat(private val context: Context, private val browser: UserBrowser, private val scope: CoroutineScope) {
    private val app = context.applicationContext as MikaApp
    private val store = ConversationStore.shared(context)
    private val model = CapsuleModel()
    private val toolLabels by lazy { ToolLabels(knownTools()) }
    private val appLabels = HashMap<String, String>()

    var conversationId by mutableStateOf<String?>(null)
        private set
    /** 次の会話のタイトル (「Continue "...」行に指定されます)。 */
    var title by mutableStateOf<String?>(null)
        private set
    /** はすでにこのページで質問しています (この会話は今回このページで開始または継続されました) ため、「新しい会話を続ける」は表示されなくなります。 */
    var started by mutableStateOf(false)
        private set
    var fresh by mutableStateOf(false)
    var open by mutableStateOf(false)
    var draft by mutableStateOf("")
    var selection by mutableStateOf<String?>(null)
    /** テキスト選択メニューで「ミカに聞く」をクリックしました: 入力ボックスにフォーカスが置かれる必要があります (毎回 1 つずつ増えます)。 現時点での */
    var focusAsk by mutableIntStateOf(0)
        private set

    var card by mutableStateOf<CardRun?>(null)
        private set
    var thread by mutableStateOf<ChatThread?>(null)
        private set
    /** のその手順 (「ウェブページ」「アプリ」) はどこにありますか。これは、[CardContent.Working] の手順と 1 対 1 で対応しています。 */
    var stepTags by mutableStateOf<List<String?>>(emptyList())
        private set
    private var run: TaskRun? = null
    private var past: List<PastTurn>? = null
    private var shown = CapsuleThreads.PAGE
    private var watching: Job? = null
    private var loading: Job? = null
    private var lastState: TaskState? = null
    private var exchange: Exchange? = null

    val state: TaskState? get() = card?.run?.state
    val mode: PageAskMode get() = PageAskModel.mode(state?.takeIf { it.isActive() }, open)
    val target: String? get() = if (fresh) null else conversationId

    /** がページを開きました (またはページを変更しました): このページでまだ質問していない場合は、このページをクリックして次の会話を見つけてください。 */
    fun onPage(url: String?) {
        if (url == null) {
            reset()
            return
        }
        if (started) return
        scope.launch {
            val pick = withContext(Dispatchers.IO) { runCatching { PageConversations.pick(store.list(), url, System.currentTimeMillis()) }.getOrNull() }
            if (started) return@launch
            follow(pick?.id, pick?.title)
        }
    }

    /** ページは閉じられています。何も返されません。 */
    private fun reset() {
        started = false
        fresh = false
        open = false
        selection = null
        follow(null, null)
    }

    /** 選択したテキストをミカに尋ねる必要があります。それを持ってくると、入力ボックスにフォーカスが置かれます。 */
    fun askAbout(text: String) {
        selection = AttachedPage.clip(text)
        focusAsk++
    }

    /** は入力ボックスに単語を出力します。実行中のそれは間投詞 (質問されたときの答え) であり、それ以外の場合はこのページから始まります (ページの説明を表示し、ページを貸します)。 */
    fun send() {
        val words = draft.trim()
        val sel = selection
        val live = run?.takeIf { it.state.value.isActive() && it.conversationId == target }
        if (live != null) {
            if (words.isEmpty() && sel == null) return
            if (live.state.value is TaskState.WaitingInput) TaskService.answer(context, words.ifEmpty { sel.orEmpty() }, live.key)
            else {
                // は新しく選択された単語を挿入します: 冒頭のようにページの説明を書きます (このページは現時点でどのように表示されているか)
                val text = sel?.let { browser.pageInfo()?.let { p -> AttachedPage.compose(p.copy(selection = it), words) } } ?: words
                TaskService.start(context, text, live.conversationId, messageId = UUID.randomUUID().toString())
            }
            draft = ""
            selection = null
            return
        }
        if (words.isEmpty() && sel == null) return
        val page = browser.pageInfo() ?: return
        val id = target ?: store.newConversation()
        val origin = PageConversations.origin(page.url, page.title)
        browser.lend(id)
        TaskService.start(context, AttachedPage.compose(page.copy(selection = sel), words), id)
        if (origin != null) scope.launch(Dispatchers.IO) { runCatching { store.setPageOrigin(id, origin) } }
        draft = ""
        selection = null
        fresh = false
        started = true
        open = true
        follow(id, title.takeIf { id == conversationId })
    }

    /** 会話をフォローするように変更します [id]: 記録されたターンを読み、現在のタスクを見つめます。 */
    private fun follow(id: String?, title: String?) {
        this.title = title
        if (id == conversationId && watching != null) return
        conversationId = id
        past = null
        shown = CapsuleThreads.PAGE
        card = null
        thread = null
        run = null
        exchange = null
        lastState = null
        watching?.cancel()
        if (id == null) {
            watching = null
            return
        }
        loadPast(id)
        watching = scope.launch {
            launch { store.changes.collect { if (it == id) loadPast(id) } }
            watch(id)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun watch(id: String) {
        app.taskState.runs.map { runs -> runs.firstOrNull { it.conversationId == id } }.flatMapLatest { r ->
            if (r == null) flowOf(null)
            else combine(r.state, r.takeoverStage, r.activities, r.steers) { s, st, acts, steers ->
                r to CapsuleRun(r.key, r.conversationId, s, st, r.goal, exchangeOf(r, s, acts), runId = r.runId.value, onScreen = true, steers = steers)
            }
        }.collect { pair ->
            val r = pair?.first
            if (r != null) run = r
            val c = pair?.second ?: card?.run?.takeIf { !it.state.isActive() } ?: run?.let { gone ->
                // タスクはリストから削除されました。タスクを最後の状態に保ち、このラウンドがダイアログに記録されるまで待ちます。
                CapsuleRun(gone.key, gone.conversationId, gone.state.value, Takeover.Stage.None, gone.goal, exchange, runId = gone.runId.value)
            }
            val next = c?.let(model::cardRunOf)
            val before = lastState
            lastState = next?.run?.state
            card = next
            stepTags = tagsOf(next)
            PageAskModel.follow(before, lastState)?.let { open = it }
            if (before?.isActive() == true && lastState?.isActive() != true) loadPast(id)
            rebuild()
        }
    }

    private fun exchangeOf(r: TaskRun, s: TaskState, acts: List<RunEvent.Activity>): Exchange {
        val before = exchange?.takeIf { it.goal == r.goal } ?: Exchange(r.goal, emptyList(), ExchangeStatus.Working, live = true)
        return ChatViewModel.reduce(before.copy(activities = acts), s, ::appLabel, toolLabels::label).also { exchange = it }
    }

    // 各ステップの場所: そのステップのアクティビティ オブジェクトは「website...」です。名前が付いているものは Web ページであり、パッケージ名が付いているものはアプリです。
    private fun tagsOf(c: CardRun?): List<String?> {
        val ex = c?.run?.exchange ?: return emptyList()
        val content = CapsuleCards.content(c) as? CardContent.Working ?: return emptyList()
        val rows = ex.steps
        return content.steps.mapIndexed { i, _ ->
            val row = rows.getOrNull(i) ?: return@mapIndexed null
            pageStepTag(ex.activities.lastOrNull { it.step == row.step }?.target)
        }
    }

    private fun loadPast(id: String) {
        loading?.cancel()
        val n = shown
        loading = scope.launch {
            val (turns, name) = withContext(Dispatchers.IO) {
                runCatching { store.turns(id) to store.title(id) }.getOrDefault(emptyList<com.wangzan101.mika.core.agent.Turn>() to null)
            }
            if (conversationId != id) return@launch
            if (name != null && title == null) title = name
            past = turns.takeLast(n.coerceAtLeast(turns.size)).map { PastTurn(it) }
            rebuild()
        }
    }

    private fun rebuild() {
        val id = conversationId ?: return
        val p = past ?: return
        val live = listOfNotNull(card).let { CapsuleThreads.liveTail(it, id, p.mapNotNull { t -> t.turn.runId }.toSet()) }
        thread = CapsuleThreads.build(p, shown, live, app.taskState.summarizing.value) { r ->
            CapsuleThreads.places(r.run.exchange?.activities.orEmpty(), ::appLabel)
        }
    }

    private fun appLabel(pkg: String): String = appLabels.getOrPut(pkg) { ConfirmationNotifier.appLabel(context, pkg) }

    /** パネルと小さなカードのボタンはすべて、その番号を使用してタスク サービスの既存のアクションを実行します。 */
    val actions = object : CardActions {
        override fun collapse() { open = false }
        override fun stop(runKey: String) = TaskService.cancel(context, runKey)
        override fun takeOver(runKey: String) = TaskService.takeOver(context, waitKey = run?.takeover?.waitKey, runKey = runKey)
        override fun resume(runKey: String) = TaskService.resume(context, runKey)
        override fun answer(runKey: String, text: String) = TaskService.answer(context, text, runKey)
        override fun approve(runKey: String, requestKey: Int) = TaskService.approve(context, requestKey, runKey)
        override fun decline(runKey: String, requestKey: Int) = TaskService.decline(context, requestKey, runKey)
        override fun openMika(conversationId: String) {
            runCatching { context.startActivity(TaskService.openAppIntent(context, conversationId)) }
                .onFailure { Log.w(TAG, "could not open the conversation", it) }
        }
        override fun zoom(on: Boolean) = Unit
        override fun openInput() = Unit
        override fun draft(text: String) { draft = text }
        override fun send() = this@PageChat.send()
        override fun freshChat(fresh: Boolean) { this@PageChat.fresh = fresh }
        override fun loadEarlier() {
            val id = conversationId ?: return
            if ((past?.size ?: 0) < shown) return
            shown += CapsuleThreads.PAGE
            loadPast(id)
        }
        override fun captureScreen() = Unit
        override fun dropScreen() = Unit
    }

    /** インターフェイス テスト: タスクなしで、[card] および [thread] を直接表示します (ダイアログ [id])。 */
    @androidx.annotation.VisibleForTesting
    internal fun show(id: String?, card: CardRun?, thread: ChatThread?) {
        watching?.cancel()
        watching = null
        conversationId = id
        this.card = card
        this.thread = thread
        PageAskModel.follow(lastState, card?.run?.state)?.let { open = it }
        lastState = card?.run?.state
    }

    /** は、引き継がれた (「処理された」) ページを返します。 */
    fun handBack() {
        val r = run ?: return
        TaskService.handBack(context, waitKey = r.takeover?.waitKey, runKey = r.key)
    }

    /** ユーザーが自分の手でページに触れました。 */
    fun touched() = browser.userTouched()

    private companion object {
        const val TAG = "MikaPageChat"
    }
}

/**
 * Web ビュー用コンテナ: 指で押すと [onTouch] と伝えます。窓を通して送信された実際のタッチだけがここを通過します。ミカ自身の入力 (DevTools のマウスとキーボードがページに直接入力され、
 * のネイティブ タッチは Web ビューを経由せずに直接送信されるため、自身のアクションはユーザーの操作とは見なされません。
 * Webページのテキスト選択メニューに項目「ミカに聞く」([onAsk])を追加します。クリックすると、選択した単語が最初に読み上げられます。読み取った後、呼び出してメニューを閉じます。
 */
class PageFrame(context: Context, private val onTouch: () -> Unit, private val onAsk: (done: () -> Unit) -> Unit) : FrameLayout(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onTouch()
        return super.dispatchTouchEvent(ev)
    }

    override fun startActionModeForChild(originalView: View, callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionModeForChild(originalView, AskCallback(callback), type)

    private inner class AskCallback(private val inner: ActionMode.Callback) : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = inner.onCreateActionMode(mode, menu).also { if (it) addAsk(menu) }

        // Web ページ カーネルがメニューを準備するとき、メニューはクリアされて再配置されます。配置が完了してから追加してください。
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = inner.onPrepareActionMode(mode, menu).also { addAsk(menu) }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != ASK_ID) return inner.onActionItemClicked(mode, item)
            // メニューを閉じる前に選択した単語を読み上げます（閉じると選択した単語がキャンセルされる場合があります）
            onAsk { mode.finish() }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            (inner as? ActionMode.Callback2)?.onGetContentRect(mode, view, outRect) ?: super.onGetContentRect(mode, view, outRect)
        }

        private fun addAsk(menu: Menu) {
            if (menu.findItem(ASK_ID) == null) menu.add(Menu.NONE, ASK_ID, 0, PageAskCopy.MENU).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
    }

    private companion object {
        val ASK_ID = View.generateViewId()
    }
}

/**
 * Web ページでカバーされているもの (Web ページ領域のボックスに配置): 作業中の周囲のライトエッジ。ページで作業しているときにミカが一時停止すると、「続行」というメッセージが表示されます。
 * パネルが開いているときは下半分の対話パネル ([PagePanel])、物事がまだ終わっていないときは下部の小さなカード ([PageCard])。通常の入力バーはここ (Web ページの下) にはありません。
 */
@Composable
fun BoxScope.PageAskOverlay(chat: PageChat, site: String, pageTitle: String) {
    val state = chat.state
    if (PageAskModel.glow(state)) PageGlow()
    if (PageAskModel.pausedForUser(state)) {
        PausedBanner({ chat.card?.run?.key?.let(chat.actions::resume) }, Modifier.align(Alignment.TopCenter).padding(10.dp))
    }
    when (chat.mode) {
        PageAskMode.Panel -> PagePanel(chat, site, pageTitle, Modifier.align(Alignment.BottomCenter).fillMaxHeight(PANEL_SHARE).imePadding())
        PageAskMode.Card -> PageCard(chat, Modifier.align(Alignment.BottomCenter).padding(10.dp))
        PageAskMode.Bar -> Unit
    }
}

/** Web ページ領域に対するダイアログ パネルの高さの比率: 上で公開されている Web ページは、Mika が操作しているページです。 */
private const val PANEL_SHARE = 0.58f

/** ページの周りのラベンダー色の境界線: ミカがこのページの作業を行っています。タッチを妨げません。 */
@Composable
fun PageGlow(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().testTag("page-glow").drawBehind {
            val w = 2.dp.toPx()
            for (i in 0 until GLOW_LAYERS) {
                val inset = w / 2 + i * w * 2
                drawRoundRect(
                    GlowColor.copy(alpha = 0.7f / (i + 1)), topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(w),
                )
            }
        },
    )
}

private const val GLOW_LAYERS = 4
/** ミカがページで作業しているときの周囲の色 (ラベンダー、ページ自体の色とは別)。 */
private val GlowColor = Color(0xFF7B5CFF)

/**
 * ブラウザ ページの下部にある通常の行:「✦ Mikaに聞く...」入力ボックス。このページに後続の会話がある場合、「Continue "..." · 新しい会話」の上の行が表示されます。
 * テキストを選択すると上に「選択されたテキスト」と×が表示されます。
 */
@Composable
fun PageAskBar(chat: PageChat, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    Column(modifier.fillMaxWidth().background(c.bg).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp).testTag("page-ask-bar")) {
        val continuing = chat.conversationId
        if (continuing != null && !chat.started) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (chat.fresh) CardCopyText.NEW_CHAT else PageAskCopy.continuing(ChatCopy.plainTitle(AttachedPage.words(chat.title.orEmpty())).take(TITLE_MAX)),
                    Modifier.weight(1f, fill = false), style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(" · ", style = MikaType.Hint, color = c.ink3)
                Text(
                    if (chat.fresh) CardCopyText.CONTINUE_LAST else CardCopyText.NEW_CHAT,
                    Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button) { chat.fresh = !chat.fresh }.padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag("page-ask-fresh"),
                    style = MikaType.Hint, color = c.accent,
                )
            }
        }
        chat.selection?.let { SelectionChip(it) { chat.selection = null } }
        AskField(chat, PageAskCopy.HINT, stop = null)
    }
}

private const val TITLE_MAX = 16

/** カード内の「新しい会話」と「最後の会話の続き」という単語 (サスペンション カプセルと同じ単語のセット)。 */
private object CardCopyText {
    val NEW_CHAT: String get() = tr(R.string.chat_new)
    val CONTINUE_LAST: String get() = tr(R.string.card_continue_last)
}

/** 入力ボックスで選択されたテキスト: 明るい色のバー、右側のバーなしの ×。 */
@Composable
private fun SelectionChip(text: String, onRemove: () -> Unit) {
    val c = MikaTheme.colors
    Row(
        Modifier.padding(bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.ink.copy(alpha = 0.05f))
            .padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp).testTag("page-ask-selection"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(PageAskCopy.SELECTION, style = MikaType.Hint, color = c.ink3)
            Text(text, style = MikaType.Caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(28.dp).clip(MikaShapes.Pill).clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = PageAskCopy.DROP_SELECTION },
            contentAlignment = Alignment.Center,
        ) { Icon(MikaIcons.Close, null, Modifier.size(15.dp), tint = c.ink3) }
    }
}

/** 入力ボックス: 左側に✦、右側に書かれた単語 (または選択した単語) が送信用です。 [stop] がある場合、右側に常に停止ボタンがあります。 */
@Composable
private fun AskField(chat: PageChat, hint: String, stop: (() -> Unit)?) {
    val c = MikaTheme.colors
    val shape = RoundedCornerShape(16.dp)
    val focus = remember { FocusRequester() }
    LaunchedEffect(chat.focusAsk) { if (chat.focusAsk > 0) runCatching { focus.requestFocus() } }
    val canSend = chat.draft.isNotBlank() || chat.selection != null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(shape).background(c.fill).padding(start = 12.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MikaIcons.Spark, null, Modifier.size(17.dp), tint = GlowColor)
        Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
            if (chat.draft.isEmpty()) Text(hint, style = MikaType.Body.copy(fontSize = 14.5.sp), color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                chat.draft, { chat.draft = it },
                Modifier.fillMaxWidth().focusRequester(focus).testTag("page-ask-field").semantics { contentDescription = hint },
                textStyle = MikaType.Body.copy(fontSize = 14.5.sp, color = c.ink), maxLines = 4, cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) chat.send() }),
            )
        }
        stop?.let { StopKey(it) }
        if (stop == null || canSend) {
            Box(
                Modifier.testTag("page-ask-send").size(32.dp).clip(MikaShapes.Pill).background(if (canSend) c.ink else c.ink3.copy(alpha = 0.3f))
                    .clickable(enabled = canSend, role = Role.Button, onClick = chat::send)
                    .semantics { contentDescription = tr(R.string.chat_send) },
                contentAlignment = Alignment.Center,
            ) { Icon(MikaIcons.Up, null, Modifier.size(16.dp), tint = c.bg) }
        }
    }
}

private val PanelShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
private val CardShape = RoundedCornerShape(16.dp)

/**
 * 半画面ダイアログパネル: Web ページの下半分を覆っており、上に露出している Web ページがミカが操作しているページです。一番上にはサイト名「このページから始める・ページタイトル」、
 * 「チャットで開いて」閉じます。真ん中はこの会話の前後です（浮遊カプセルの会話カードと同じ配置）。以下は作業中の手順です。Web ページまたはアプリ上でどの手順であるかをマークしてください。
 * クリックするボタン (回答オプション、同意、引き継ぎ、続行) は下部に固定されています。一番下は入力ボックスです。走っているときに言うことは中断することです（右側の停止ボタン）。ミカが尋ねたら、それは答えることです、
 * の後には、このページの質問が続きます。ページは、ユーザーの手に渡されたとき (管理下にあるとき)、「処理」されます。
 */
@Composable
fun PagePanel(chat: PageChat, site: String, pageTitle: String, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val card = chat.card
    val content = card?.let(CapsuleCards::content)
    val live = card?.run?.state?.isActive() == true
    Column(
        modifier.fillMaxWidth().clip(PanelShape).background(c.bg).border(0.5.dp, c.hair, PanelShape)
            .padding(horizontal = 14.dp).testTag("page-panel"),
    ) {
        Box(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = tr(R.string.collapse)) { chat.open = false }.padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.width(36.dp).height(4.dp).clip(MikaShapes.Pill).background(c.hair)) }
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(site, style = MikaType.Label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(PageAskCopy.FROM_PAGE, pageTitle).filter(String::isNotBlank).joinToString(" · "),
                    style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            chat.conversationId?.let { id ->
                Row(
                    Modifier.clip(MikaShapes.Pill).clickable(role = Role.Button) { chat.actions.openMika(id) }.padding(horizontal = 6.dp, vertical = 6.dp)
                        .testTag("page-panel-open-chat"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(PageAskCopy.OPEN_CHAT, style = MikaType.Hint.copy(fontSize = 12.5.sp), color = c.accent)
                    Icon(MikaIcons.External, null, Modifier.size(13.dp), tint = c.accent)
                }
            }
            Box(
                Modifier.size(30.dp).clip(MikaShapes.Pill).clickable(role = Role.Button, onClick = chat.actions::collapse)
                    .semantics { contentDescription = tr(R.string.collapse) }.testTag("page-panel-collapse"),
                contentAlignment = Alignment.Center,
            ) { Icon(MikaIcons.Close, null, Modifier.size(17.dp), tint = c.ink3) }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(c.hair))
        // ステップはダイアログの最終行 (「Web サイト・作業中」行) のすぐ下にあり、パネルの一番下まで押すことなく、会話ページと同じ長さで進みます。
        val steps: (@Composable () -> Unit)? = if (content is CardContent.Working && content.steps.isNotEmpty()) {
            { Box(Modifier.padding(bottom = 6.dp)) { Steps(content.steps, StepsMax, chat.stepTags) } }
        } else null
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
            chat.thread?.let { t -> androidx.compose.runtime.key(chat.conversationId) { ThreadList(t, chat.conversationId, chat.actions, footer = steps) } }
        }
        if (card != null && card.run.stage == Takeover.Stage.HandedOver) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(PageAskCopy.HANDLING, style = MikaType.Caption, color = c.ink2)
                MikaButton(tr(R.string.action_hand_back), chat::handBack, Modifier.fillMaxWidth().testTag("page-panel-hand-back"), kind = ButtonKind.Primary, size = ButtonSize.Medium)
            }
        } else if (card != null && content != null && live) {
            Box(Modifier.padding(top = 4.dp)) { Pinned(content, card.run.key, chat.actions) }
        }
        val hint = when (content) {
            is CardContent.Takeover, is CardContent.Confirm -> null
            is CardContent.Question -> CapsuleCards.HINT_ANSWER
            is CardContent.Working, is CardContent.Held -> CapsuleCards.HINT_STEER
            else -> CapsuleCards.HINT_CONTINUE
        }
        val stoppable = live && (content is CardContent.Working || content is CardContent.Question || content is CardContent.Held)
        if (hint != null) Column(Modifier.padding(top = 8.dp, bottom = 10.dp)) {
            chat.selection?.let { SelectionChip(it) { chat.selection = null } }
            AskField(chat, hint, stop = if (stoppable) ({ card?.run?.key?.let(chat.actions::stop) }) else null)
        } else Box(Modifier.height(10.dp))
    }
}

private val StepsMax = 132.dp

/**
 * パネルが格納されているときに下部にある小さなカード: あれは今何をしているのでしょうか?クリックして展開します。ページの作業中に一時停止する場合は、「一時停止」と「続行」を書きます。
 */
@Composable
fun PageCard(chat: PageChat, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val card = chat.card ?: return
    val paused = PageAskModel.pausedForUser(card.run.state)
    val warn = paused || card.kind.needsYou
    Row(
        modifier.fillMaxWidth().floatShadow(c, CardShape).clip(CardShape).background(c.bg).border(0.5.dp, c.hair, CardShape)
            .clickable(role = Role.Button, onClickLabel = PageAskCopy.EXPAND) { chat.open = true }
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp).testTag("page-card"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(MikaShapes.Pill).background(if (warn) c.warnSolid else GlowColor))
        Column(Modifier.weight(1f)) {
            Text(
                if (paused) PageAskCopy.PAUSED_CARD else card.line.ifBlank { CapsuleCards.status(card.kind) },
                style = MikaType.Label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (paused) Text(PageAskCopy.PAUSED_HINT, style = MikaType.Hint, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (paused) MikaButton(tr(R.string.chat_continue), { chat.actions.resume(card.run.key) }, Modifier.testTag("page-card-resume"), kind = ButtonKind.Primary, size = ButtonSize.Small)
        Icon(MikaIcons.Up, PageAskCopy.EXPAND, Modifier.size(18.dp), tint = c.ink3)
    }
}

/** あなたがページ上で何かをしていてミカが停止すると、一番上の行は「あなたは操作中です、ミカは一時停止して続行しました。」です。 */
@Composable
fun PausedBanner(onResume: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1D1F24))
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).testTag("page-paused-banner"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MikaIcons.Hand, null, Modifier.size(16.dp), tint = Color.White)
        Text(PageAskCopy.PAUSED, Modifier.weight(1f), style = MikaType.Caption, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            tr(R.string.chat_continue),
            Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White).clickable(role = Role.Button, onClick = onResume)
                .padding(horizontal = 12.dp, vertical = 5.dp).testTag("page-paused-resume"),
            style = MikaType.Label.copy(fontWeight = FontWeight.SemiBold), color = Color(0xFF111111),
        )
    }
}

/** 会話ページ上の文はブラウザ ページ上のページに質問されました。バブルの上には「Web ページ・Web サイト」の小さな部分があり、その下に選択された単語がその単語です。 */
@Composable
internal fun SentPage(page: AttachedPage.Page, maxWidth: androidx.compose.ui.unit.Dp) {
    val c = MikaTheme.colors
    val host = com.wangzan101.mika.core.cap.web.WebUrlPolicy.host(page.url) ?: page.url
    Column(
        Modifier.padding(bottom = 4.dp).widthIn(max = maxWidth).background(c.ink.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp).testTag("sent-page"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(MikaIcons.Globe, null, Modifier.size(13.dp), tint = c.ink3)
            Text("${PageAskCopy.WEB} · $host", style = MikaType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        page.selection?.let { Text(it, style = MikaType.Caption, color = c.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
}
