package com.wangzan101.mika.ui.viewer

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wangzan101.mika.ui.MarkdownText
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** テキスト クラスと Markdown はこのバイト数まで読み込むことができ、それ以降は最初の部分のみが表示されます。 */
private const val TEXT_CAP = 1 shl 20

/** デコードされるピクチャの最長の辺。最長辺を超える場合は、大きな画像がメモリをいっぱいにしないように、比例して縮小されます。 */
private const val IMAGE_MAX_SIDE = 4096

/** 各テキストの行数: 長いテキストはセグメント単位で遅延読み込みリストに入れられ、表示可能なセグメントのみが配置されます。 */
private const val LINES_PER_CHUNK = 200

private val MonoText = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp)

/** によって読み取られたコンテンツ。 PDF はここにはありません。ページごとに読み込まれて描画されます。 */
private sealed interface Loaded {
    data class Text(val text: String, val truncated: Boolean) : Loaded
    data class Html(val html: String) : Loaded
    class Picture(val bitmap: Bitmap) : Loaded
}

/**
 * ファイル表示ページ: 上部のバーはリターン、ファイル名、「共有」、「他のアプリケーションで開く」です。以下の[kind]を押して内容を表示します。
 * [kind] が null (ここでは認識されないタイプ) の場合は、一文を言って「他のアプリケーションで開く」と入力するだけです。
 */
@Composable
fun FileViewerScreen(uri: Uri, name: String, kind: ViewerKind?, onBack: () -> Unit) {
    val context = LocalContext.current
    val openElsewhere = { FileViewer.openElsewhere(context, uri, name) }
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(name, MikaIcons.Back, onBack, stringResource(R.string.back), right = {
                BarIconButton(MikaIcons.Share, stringResource(R.string.share), { FileViewer.share(context, uri, name) })
                BarIconButton(MikaIcons.External, stringResource(R.string.viewer_open_elsewhere), openElsewhere)
            })
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                when (kind) {
                    null -> Failure(stringResource(R.string.viewer_unsupported), openElsewhere, Modifier.testTag("viewer.unsupported"))
                    ViewerKind.Pdf -> PdfView(uri, openElsewhere)
                    else -> LoadedView(uri, kind, openElsewhere)
                }
            }
        }
    }
}

@Composable
private fun LoadedView(uri: Uri, kind: ViewerKind, openElsewhere: () -> Unit) {
    val context = LocalContext.current
    val result by produceState<Result<Loaded>?>(null, uri, kind) {
        value = withContext(Dispatchers.IO) { runCatching { load(context, uri, kind) } }
    }
    val loaded = result
    when {
        loaded == null -> Loading()
        loaded.isFailure -> Failure(stringResource(R.string.viewer_read_error), openElsewhere)
        else -> when (val l = loaded.getOrThrow()) {
            is Loaded.Html -> HtmlView(l.html)
            is Loaded.Picture -> PictureView(l.bitmap)
            is Loaded.Text -> if (kind == ViewerKind.Markdown) MarkdownView(l) else PlainTextView(l)
        }
    }
}

private fun load(context: Context, uri: Uri, kind: ViewerKind): Loaded = when (kind) {
    ViewerKind.Html -> Loaded.Html(open(context, uri).use { it.readBytes() }.toString(Charsets.UTF_8))
    ViewerKind.Image -> Loaded.Picture(
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > IMAGE_MAX_SIDE) {
                val f = IMAGE_MAX_SIDE.toFloat() / longest
                decoder.setTargetSize((info.size.width * f).roundToInt().coerceAtLeast(1), (info.size.height * f).roundToInt().coerceAtLeast(1))
            }
        },
    )
    else -> {
        // さらに 1 バイトを読み取って、切り捨てられているかどうかを確認します。マルチバイト文字の途中で切り詰められた場合、デコードは置換文字に置き換えられますが、以前の内容には影響しません。
        val bytes = open(context, uri).use { input ->
            val buf = ByteArray(TEXT_CAP + 1)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            buf.copyOf(n)
        }
        val truncated = bytes.size > TEXT_CAP
        val text = String(bytes, 0, minOf(bytes.size, TEXT_CAP), Charsets.UTF_8).removePrefix("﻿")
        Loaded.Text(text, truncated)
    }
}

private fun open(context: Context, uri: Uri) =
    context.contentResolver.openInputStream(uri) ?: error("ファイルを開けません: $uri")

// ---------- 各種コンテンツ ----------

@Composable
private fun MarkdownView(l: Loaded.Text) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("viewer.markdown"),
    ) {
        if (l.truncated) TruncatedNote(Modifier.padding(bottom = 12.dp))
        MarkdownText(l.text, MikaType.Body)
    }
}

/** は同じ幅で選択可能です。それは行ごとにセグメント化された遅延読み込みリストに入れられ、長いファイルの目に見える部分だけが配置されます。長い行は自動的に折り返されます。 */
@Composable
private fun PlainTextView(l: Loaded.Text) {
    val c = MikaTheme.colors
    val chunks = remember(l.text) { l.text.lines().chunked(LINES_PER_CHUNK).map { it.joinToString("\n") } }
    SelectionContainer {
        LazyColumn(
            Modifier.fillMaxSize().testTag("viewer.text"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        ) {
            if (l.truncated) item { TruncatedNote(Modifier.padding(bottom = 12.dp)) }
            itemsIndexed(chunks) { _, chunk -> Text(chunk, style = MonoText, color = c.ink) }
            item { Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
        }
    }
}

/** Web ページ: 新しい WebView、スクリプトが開き、2 本の指でズームでき、ワイド ビューポートに従ってタイプセットできます。リンクをクリックしてシステムに処理させます。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlView(html: String) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                setBackgroundColor(android.graphics.Color.WHITE)
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).testTag("viewer.html"),
        onRelease = { it.destroy() },
    )
}

/** 画像: 表示領域を (均等に) カバーし、2 本の指でズームし、ズームインした後に 1 本の指でドラッグし、ダブルクリックして拡大または復元します。 */
@Composable
private fun PictureView(bitmap: Bitmap) {
    val zoom = remember { Zoom() }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        image, null,
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).testTag("viewer.image")
            .pointerInput(zoom) { detectTapGestures(onDoubleTap = { zoom.toggle() }) }
            .zoomable(zoom, panWithOneFinger = true),
        contentScale = ContentScale.Fit,
    )
}

// ---------- PDF ----------

/** によって開かれた PDF: 各ページの元のサイズが最初に読み取られ (植字に使用され)、ページが 1 つずつ描画されます (レンダラーは同時に 1 ページしか開くことができません)。 */
private class PdfDoc(private val fd: ParcelFileDescriptor) {
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    val sizes: List<IntSize> = (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { IntSize(it.width, it.height) } }

    suspend fun render(index: Int, widthPx: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val height = (widthPx.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(android.graphics.Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    /** このページを閉じる前に、描画が完了するまで待ってください。 */
    fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            lock.withLock {
                runCatching { renderer.close() }
                runCatching { fd.close() }
            }
        }
    }
}

/** PDF: ページは画面幅に従って 1 列に配置され、表示されているページのみが描画されます (スライド ページはビットマップとして再利用されます)。 2 本の指で列全体をズームします。一番下に「3/12」。 */
@Composable
private fun PdfView(uri: Uri, openElsewhere: () -> Unit) {
    val context = LocalContext.current
    val doc by produceState<Result<PdfDoc>?>(null, uri) {
        val opened = withContext(Dispatchers.IO) {
            runCatching { PdfDoc(context.contentResolver.openFileDescriptor(uri, "r") ?: error("ファイルを開けません: $uri")) }
        }
        value = opened
        try { awaitCancellation() } finally { opened.getOrNull()?.close() }
    }
    val d = doc
    when {
        d == null -> Loading()
        d.isFailure -> Failure(stringResource(R.string.viewer_read_error), openElsewhere)
        else -> PdfPages(d.getOrThrow())
    }
}

@Composable
private fun PdfPages(doc: PdfDoc) {
    val c = MikaTheme.colors
    val list = rememberLazyListState()
    val zoom = remember { Zoom() }
    val current by remember(list) { derivedStateOf { currentPage(list) } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("viewer.pdf").zoomable(zoom, panWithOneFinger = false),
            state = list,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(doc.sizes) { index, size -> PdfPage(doc, index, size) }
        }
        if (doc.sizes.isNotEmpty()) {
            Text(
                "${current + 1} / ${doc.sizes.size}",
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 12.dp)
                    .background(c.ink.copy(alpha = 0.72f), MikaShapes.Pill).padding(horizontal = 12.dp, vertical = 4.dp),
                style = MikaType.Label, color = c.bg,
            )
        }
    }
}

/** ビューポートの中心線が配置されているページ。 */
private fun currentPage(list: LazyListState): Int {
    val info = list.layoutInfo
    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.lastOrNull { it.offset <= middle }?.index ?: list.firstVisibleItemIndex
}

@Composable
private fun PdfPage(doc: PdfDoc, index: Int, size: IntSize) {
    val c = MikaTheme.colors
    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(size.width.toFloat() / size.height.coerceAtLeast(1))
            .background(Color.White).border(0.5.dp, c.edge),
    ) {
        val widthPx = constraints.maxWidth
        val bitmap by produceState<Bitmap?>(null, doc, index, widthPx) {
            if (widthPx <= 0) return@produceState
            val b = runCatching { doc.render(index, widthPx) }.getOrNull() ?: return@produceState
            value = b
            // がリストから外れるか、サイズが変更されました。最初に削除してからリサイクルし、インターフェースにリサイクルされたビットマップを描画させないでください。
            try { awaitCancellation() } finally { value = null; b.recycle() }
        }
        bitmap?.let { b ->
            val image = remember(b) { b.asImageBitmap() }
            Image(image, stringResource(R.string.viewer_page, index + 1), Modifier.fillMaxSize())
        }
    }
}

// ---------- ズーム ----------

/** ズームとパンのステータス: 1 ～ 5 倍、パンは拡大側を超えません。 */
private class Zoom {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun apply(factor: Float, pan: Offset, size: IntSize) {
        scale = (scale * factor).coerceIn(1f, 5f)
        val maxX = (scale - 1f) * size.width / 2f
        val maxY = (scale - 1f) * size.height / 2f
        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
    }

    fun toggle() {
        scale = if (scale > 1f) 1f else 2.5f
        offset = Offset.Zero
    }
}

/**
 * は 2 本の指を使用してズームとパンを行います。 [panWithOneFinger] が true の場合、ズームイン後に 1 本の指をドラッグすることもできます。
 * はイベントの最初のラウンドで最初に取得されます。 2本指でリストをスクロールすると受信できません。通常どおり 1 本の指でスクロールできます。
 */
private fun Modifier.zoomable(zoom: Zoom, panWithOneFinger: Boolean): Modifier = this
    .pointerInput(zoom) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2 || (panWithOneFinger && zoom.scale > 1f)) {
                    zoom.apply(event.calculateZoom(), event.calculatePan(), size)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .graphicsLayer {
        scaleX = zoom.scale
        scaleY = zoom.scale
        translationX = zoom.offset.x
        translationY = zoom.offset.y
    }

// ---------- ステータス ----------

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize().testTag("viewer.loading"), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = MikaTheme.colors.accent, strokeWidth = 2.5.dp)
    }
}

@Composable
private fun Failure(message: String, openElsewhere: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MikaType.Body, color = MikaTheme.colors.ink2, textAlign = TextAlign.Center)
        MikaButton(stringResource(R.string.viewer_open_elsewhere), openElsewhere, kind = ButtonKind.Secondary, size = ButtonSize.Medium)
    }
}

@Composable
private fun TruncatedNote(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.viewer_truncated), modifier, style = MikaType.Caption, color = MikaTheme.colors.ink3)
}
