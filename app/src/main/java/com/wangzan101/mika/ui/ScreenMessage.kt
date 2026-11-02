package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.agent.QuoteMessage
import com.wangzan101.mika.core.cap.web.AttachedPage
import com.wangzan101.mika.core.userscreen.UserScreen
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** インターフェイス上でユーザーによって送信される画面の説明 (会話ページ、カード、およびスクリーンショット ボタンで共有)。 */
object ScreenCopy {
    val TAG: String get() = tr(R.string.screen_tag)
    val BLOCKED: String get() = tr(R.string.screen_blocked)
    val CAMERA: String get() = tr(R.string.screen_camera)
    val REMOVE: String get() = tr(R.string.screen_remove)
    val SEND_SCREEN: String get() = tr(R.string.screen_send)

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    fun clock(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = CLOCK.format(Instant.ofEpochMilli(at).atZone(zone))

    /** スクリーンショットの下の行:「現在の画面 · 設定 · 19:42」。 */
    fun tag(screen: UserScreen): String = "$TAG · ${screen.app} · ${clock(screen.takenAt)}"

    /** 添付ファイル バーの 2 行目:「設定 · 19:42」。 */
    fun detail(screen: UserScreen): String = "${screen.app} · ${clock(screen.takenAt)}"

    /**
     * この文を使用して会話を呼び出し、リストに書き込みます。 PS;画面のみを送信する場合は「今のあなたの画面」となります。引用符で囲まれた行 ([QuoteMessage]) と Web ページの説明 ([AttachedPage]) はカウントされません。
     * は画面なしでそのまま戻ります。
     */
    fun plain(raw: String): String {
        val text = QuoteMessage.words(AttachedPage.words(raw))
        val p = UserScreenMessage.parse(text)
        return if (!p.hasScreen) text else p.words.ifEmpty { TAG }
    }
}

/** インターフェイス上に描画される画面: 説明とサムネイル (画像が読み取れない場合は null)。 */
class ShownScreen(val screen: UserScreen, val thumb: ImageBitmap?)

/** 会話と番号に応じて 1 つの画面を読み取ります。読み取れない場合は null になります。インターフェイス テストは偽のものに置き換えられます。 */
fun interface ScreenShots {
    suspend fun load(conversationId: String, id: String): ShownScreen?
}

/** が指定されている場合は、それを使用して画面を読み取ります (テスト)。そうでない場合は、アプリに保存されているコピーを読んでください。 */
val LocalScreenShots = staticCompositionLocalOf<ScreenShots?> { null }

/** アプリに保存された画面: 説明がダイアログ ワークスペースから読み取られ、サムネイルが必要な幅に縮小されてデコードされ、デコードされたメモが記録されます。 */
private class StoredScreenShots(private val context: Context) : ScreenShots {
    override suspend fun load(conversationId: String, id: String): ShownScreen? = withContext(Dispatchers.IO) {
        val key = "$conversationId/$id"
        cache.get(key)?.let { return@withContext it }
        val files = (context.applicationContext as MikaApp).userScreens
        val screen = files.screen(conversationId, id) ?: return@withContext null
        val thumb = if (screen.blocked) null else files.image(conversationId, id)?.let(::screenThumbnail)
        ShownScreen(screen, thumb).also { cache.put(key, it) }
    }

    companion object {
        val cache = LruCache<String, ShownScreen>(24)
    }
}

/** サムネイルの幅はこれと同じくらい (ピクセル) にする必要があります。会話ページの最大幅は 110 dp です。 */
private const val THUMB_PX = 360

/** のスクリーンショットはサムネイルにデコードされます (画像全体ではなく、[THUMB_PX] を押してズームアウトします)。デコードされていない場合は null です。バックグラウンドスレッドで調整しました。 */
fun screenThumbnail(bytes: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= THUMB_PX) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/** は 1 つの画面を (バックグラウンドで) 読み取ります。読み込まれていない場合、または読み取れない場合は null です。 */
@Composable
fun rememberShownScreen(conversationId: String?, id: String): ShownScreen? {
    val context = LocalContext.current
    val shots = LocalScreenShots.current ?: StoredScreenShots(context)
    val shown by produceState<ShownScreen?>(null, conversationId, id) {
        value = conversationId?.let { runCatching { shots.load(it, id) }.getOrNull() }
    }
    return shown
}

/**
 * ユーザーからの一言: 画面を撮影すると、スクリーンショットのサムネイル ([thumbWidth] 幅) と次の行「現在の画面 · アプリ · モーメント」とその下の別の吹き出しに追記が表示されます ([bubble])。
 * スクリーンショットを許可しないページは「キャプチャされていません」の行です。スクリーンショットが利用できなくなると、その行のテキストのみが表示されます。スクリーンなしの場合は、[bubble]です。
 */
@Composable
fun UserMessage(conversationId: String?, raw: String, thumbWidth: Dp, modifier: Modifier = Modifier, bubble: @Composable (String) -> Unit) {
    // ブラウザ ページ上のページについての質問: ページの説明行が表示されません (ダイアログ ページにも「Web ページ・Web サイト」が描画されます)。選択した単語のみが表示されます。
    val text = AttachedPage.parse(raw).let { pg -> pg.words.ifEmpty { pg.page?.selection?.let { "“$it”" }.orEmpty() } }
    val p = UserScreenMessage.parse(text)
    if (!p.hasScreen) {
        bubble(text)
        return
    }
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        p.screens.forEach { id -> ScreenItem(rememberShownScreen(conversationId, id), thumbWidth) }
        if (p.words.isNotEmpty()) bubble(p.words)
    }
}

private val ThumbShape = RoundedCornerShape(10.dp)
private val TagText = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)

/** 1 つの画面: サムネイルとその下の行。読む前に位置を占めます。 */
@Composable
private fun ScreenItem(shown: ShownScreen?, width: Dp) {
    val c = MikaTheme.colors
    val screen = shown?.screen
    if (screen?.blocked == true) {
        Row(
            Modifier.testTag("screen-blocked").background(c.ink.copy(alpha = 0.05f), ThumbShape).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(MikaIcons.Lock, null, Modifier.size(15.dp), tint = c.ink2)
            Text(ScreenCopy.BLOCKED, style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp), color = c.ink2)
        }
        return
    }
    val tag = screen?.let(ScreenCopy::tag) ?: ScreenCopy.TAG
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = tag }, horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenThumb(shown?.thumb, width, Modifier.testTag("screen-thumb"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(MikaIcons.Phone, null, Modifier.size(13.dp), tint = c.ink3)
            Text(tag, Modifier.testTag("screen-tag"), style = TagText, color = c.ink3)
        }
    }
}

/** スクリーンショットのサムネイル: 画像のアスペクト比に従って、角は丸く、エッジは薄くなります。画像がない場合は同サイズのライトグレーの一枚となります。 */
@Composable
fun ScreenThumb(thumb: ImageBitmap?, width: Dp, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val ratio = thumb?.let { (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(0.3f, 1.8f) } ?: DEFAULT_RATIO
    Box(modifier.width(width).aspectRatio(ratio).clip(ThumbShape).border(1.dp, c.glassEdge, ThumbShape).background(c.ink.copy(alpha = 0.05f))) {
        thumb?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}

/** 画像を読む前は、一般的な携帯電話の画面のアスペクト比に従って表示されていました。 */
private const val DEFAULT_RATIO = 0.46f
