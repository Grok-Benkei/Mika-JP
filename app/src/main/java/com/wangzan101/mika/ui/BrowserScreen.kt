package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.rememberCoroutineScope
import com.wangzan101.mika.browser.PageAskMode
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wangzan101.mika.browser.BrowserFavorites
import com.wangzan101.mika.browser.SiteLooks
import com.wangzan101.mika.browser.StartPageModel
import com.wangzan101.mika.browser.UserBrowser
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import com.wangzan101.mika.core.uistate.PageDialog
import com.wangzan101.mika.ui.components.PageBackground

/**
 * ブラウザ ページ (サイドバー「ブラウザ」): 開いている Web ページがない場合は、スタート ページ ([BrowserStart]: 検索ボックス、よく使用する Web サイト、最近開いた Web サイト)、
 * は Web ページを開きます ([BrowserPageBar] + Web ページ + [BrowserToolbar]、「詳細」は [BrowserMoreSheet])。
 * Web ページの下部には「Ask Mito」([PageAskBar]) があります。このページに質問すると、このページが現在の Web ページ ラベルとしてそのタスクに貸与されます。作業中、Web ページの下半分はダイアログ パネルで覆われます
 * ([PagePanel])、ページの周囲に明るい境界線が点灯します ([PageGlow])。ページにタッチするとすぐにミカは一時停止し、パネルには上部に「続行」([PausedBanner]) と書かれた小さなカード ([PageCard]) が表示されます。
 * ウェブページ上でテキストを選択すると、テキスト選択メニューに「ミカに聞く」という追加項目があり、選択した単語が取り込まれます。ステータスは[PageChat]です。
 * システムの戻るボタンは、最初に Web ページに戻ります。スタートページに戻れない場合は、スタートページでもう一度押すと元の位置に戻ります。
 * Web ページ ビューはプロセス ([UserBrowser]) に配置され、画面が回転しても引き続き使用されます。このページを離れると閉じられます (画面を回転させず、他の場所にブラウザ ページはありません)。
 */
@Composable
fun BrowserScreen(browser: UserBrowser, onBack: () -> Unit) {
    val context = LocalContext.current
    val page by browser.page.collectAsState()
    val dialog by browser.dialog.collectAsState()
    val notice by browser.notice.collectAsState()
    val registry by browser.sites.entries.collectAsState()
    val recentPages by browser.recent.entries.collectAsState()
    val edits by browser.favorites.edits.collectAsState()
    val looks by browser.looks.entries.collectAsState()
    val activity = remember(context) { context.findActivity() }
    var moreOpen by rememberSaveable { mutableStateOf(false) }

    val face = remember(looks) { { host: String -> SiteLooks.faceOf(host, looks, browser.looks::iconFile) } }
    val favorites = remember(edits, registry) { BrowserFavorites.combine(edits, registry) }
    val recent = remember(recentPages, looks) { StartPageModel.recentRows(recentPages) { face(it).name } }
    val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    val scope = rememberCoroutineScope()
    val chat = remember(browser) { PageChat(context.applicationContext, browser, scope) }
    // ページを変更すると (このページではまだ質問していません)、次の会話が再び見つかりました。ウェブページを閉じて、何も表示されない状態に戻りました。
    val pageUrl = page?.url
    LaunchedEffect(pageUrl) { chat.onPage(pageUrl) }

    // このブラウザ ページ: 来店時に登録し、フロント デスクに戻ったときに Web ページの表示を要求します (インターフェイスのインスタンスが 2 つある場合は、表示されているインスタンスを待ってください)。
    // 退去時に返却してください。最後の場所から離れてからのみ Web ページを閉じてください (画面を回転させない)
    val place = remember(browser, activity) { browser.newHost(activity ?: context.applicationContext) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(browser, place, lifecycle) {
        browser.openHost(place)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_START) browser.hostStarted(place) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            browser.closeHost(place, keepPage = activity?.isChangingConfigurations == true)
        }
    }
    BackHandler(enabled = page != null) { browser.systemBack() }
    LaunchedEffect(notice) {
        notice?.let {
            toast(it)
            browser.noticeShown()
        }
    }

    PageBackground {
        val p = page
        if (p == null) {
            LaunchedEffect(Unit) { moreOpen = false }
            BrowserStart(
                sites = favorites, recent = recent, face = face,
                onBack = onBack,
                onSubmit = { input -> browser.submit(input)?.let(toast) },
                onOpenUrl = { url -> browser.open(url)?.let(toast) },
                onOpenSite = { site -> browser.openFavorite(site)?.let(toast) },
                onRemoveSite = { site ->
                    browser.favorites.remove(site.host)
                    toast(tr(R.string.browser_removed, face(site.host).name))
                },
                onAddHint = { toast(BROWSER_ADD_HINT) },
                onClearRecent = browser.recent::clear,
            )
        } else {
            val host = WebUrlPolicy.host(p.url)
            val loginBanner = p.loginFor != null && !BrowserFavorites.loggedIn(registry, p.loginFor)
            Column(Modifier.fillMaxSize()) {
                BrowserPageBar(
                    p, host?.let(face),
                    onClose = browser::closePage,
                    onSubmit = { input -> browser.submit(input)?.let(toast) },
                    onReload = browser::reload, onStop = browser::stop,
                )
                if (loginBanner) LoginBanner(desktop = p.desktop, onDone = browser::confirmLogin)
                val mode = chat.mode
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Web ビューは、UserBrowser によってこのコンテナーに配置されます (ラベルが変更されても、同じものが同じコンテナーに配置されます)。指で押すとページを借りるというタスクが指示され、テキストを選択するとメニューに「ミカに聞く」が表示されます。
                    AndroidView(
                        factory = { ctx ->
                            PageFrame(ctx, onTouch = chat::touched, onAsk = { done ->
                                browser.readSelection { text ->
                                    done()
                                    text?.let(chat::askAbout)
                                }
                            }).also { browser.setContainer(place, it) }
                        },
                        modifier = Modifier.fillMaxSize().testTag("browser-page"),
                        onRelease = { browser.setContainer(place, null) },
                    )
                    PageAskOverlay(chat, site = host?.let { face(it).name } ?: p.url, pageTitle = p.title)
                }
                if (mode == PageAskMode.Bar && p.loginFor == null) PageAskBar(chat, Modifier.imePadding())
                BrowserToolbar(
                    p, onBackPage = browser::back, onForward = browser::forward, onHome = browser::closePage,
                    onShare = { share(context, p.url, p.title)?.let(toast) },
                    onMore = { moreOpen = true },
                )
            }
            if (moreOpen) {
                val favorite = host != null && BrowserFavorites.contains(favorites, host)
                BrowserMoreSheet(
                    face = host?.let(face), host = host, favorite = favorite, desktop = p.desktop,
                    onDismiss = { moreOpen = false },
                    onReload = browser::reload,
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(p.title.ifBlank { p.url }, p.url))
                        toast(tr(R.string.browser_link_copied))
                    },
                    onOpenOutside = { openOutside(context, p.url)?.let(toast) },
                    onToggleFavorite = {
                        if (host == null) return@BrowserMoreSheet
                        if (favorite) {
                            browser.favorites.remove(host)
                            toast(tr(R.string.browser_unfavorited))
                        } else {
                            browser.favorites.add(host)
                            toast(tr(R.string.browser_favorited))
                        }
                    },
                    onToggleDesktop = {
                        browser.setDesktop(!p.desktop)
                        toast(if (p.desktop) tr(R.string.browser_switched_mobile) else tr(R.string.browser_switched_desktop))
                    },
                )
            }
        }
    }

    dialog?.let { d -> PageDialogBox(d, browser::answerDialog) }
}

/** システム共有パネル: URL、テーマとしてのタイトル。開けない場合は理由を返します。 */
private fun share(context: Context, url: String, title: String): String? {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    if (title.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TITLE, title)
    return try {
        context.startActivity(Intent.createChooser(send, null))
        null
    } catch (e: ActivityNotFoundException) {
        tr(R.string.browser_no_share_app)
    }
}

/** は、システムのデフォルトのブラウザーによって開かれます。開けない場合は理由を返します。 */
private fun openOutside(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    null
} catch (e: ActivityNotFoundException) {
    tr(R.string.browser_no_other_browser)
}

/** ページのポップアップ ボックス: プロンプトは「OK」のみです。確認ページと終了ページには「キャンセル」があります。入力ボックス ポップアップ ボックスには入力ボックスがあります。 */
@Composable
private fun PageDialogBox(d: PageDialog, answer: (Boolean, String?) -> Unit) {
    var input by remember(d) { mutableStateOf(d.defaultText.orEmpty()) }
    val leave = d.kind == PageDialog.BEFORE_UNLOAD
    AlertDialog(
        onDismissRequest = { answer(false, null) },
        title = { Text(if (leave) tr(R.string.browser_leave_q) else tr(R.string.browser_page_says)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (d.message.isNotBlank()) Text(d.message)
                if (leave && d.message.isBlank()) Text(tr(R.string.browser_leave_body))
                if (d.kind == PageDialog.PROMPT) TextField(input, { input = it }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({ answer(true, if (d.kind == PageDialog.PROMPT) input else null) }) { Text(if (leave) tr(R.string.browser_leave) else tr(R.string.ok_yes)) }
        },
        dismissButton = if (d.kind == PageDialog.ALERT) null else ({ TextButton({ answer(false, null) }) { Text(tr(R.string.cancel)) } }),
    )
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
