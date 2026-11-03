package com.wangzan101.mika.ui.viewer

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.AppLanguage
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff
import com.wangzan101.mika.ui.theme.systemBarsFor

/**
 * アプリのファイル表示ページ: Web ページ、マークダウン、PDF、画像、テキスト ファイルは、別のアプリを探すことなく、ここで直接表示できます。
 * は独自のアクティビティです。会話内のファイル カードに加えて、他の場所 (一時停止中のカプセルや通知) も [FileViewer.open] で開くことができます。
 */
class FileViewerActivity : ComponentActivity() {
    // Android 12 以前の場合、アプリで選択した言語が適用されます。 13 以降、システムは単独で適用できます。
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: uri.lastPathSegment.orEmpty()
        val kind = FileViewer.kindOf(this, uri, name)
        setContent {
            val dark = com.wangzan101.mika.ui.theme.appearanceDark()
            androidx.compose.runtime.LaunchedEffect(dark) { systemBarsFor(dark) }
            MikaTheme(dark = dark, reduceMotion = animationsOff(this)) {
                FileViewerScreen(uri, name, kind, onBack = ::finish)
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
    }
}

/** ファイルを開く入り口: [FileViewerActivity] はアプリで表示でき、残りはシステムで開くことができるアプリに任せられます。 */
object FileViewer {
    fun kindOf(context: Context, uri: Uri, name: String): ViewerKind? =
        ViewerKinds.of(runCatching { context.contentResolver.getType(uri) }.getOrNull(), name)

    fun open(context: Context, uri: Uri, name: String) {
        if (kindOf(context, uri, name) == null) { openElsewhere(context, uri, name); return }
        val intent = Intent(context, FileViewerActivity::class.java).setData(uri).putExtra(FileViewerActivity.EXTRA_NAME, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 他のアプリケーションで開く: 開けるアプリがない場合、システム選択パネルにメッセージが表示されます。 */
    fun openElsewhere(context: Context, uri: Uri, name: String) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(context, uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        start(context, Intent.createChooser(intent, name), tr(R.string.viewer_no_open_app))
    }

    /** 共有: ファイルを他のアプリに送信します (読み取り権限あり)。 */
    fun share(context: Context, uri: Uri, name: String) {
        val intent = Intent(Intent.ACTION_SEND).setType(mimeOf(context, uri)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(intent, name), tr(R.string.viewer_no_share_app))
    }

    private fun mimeOf(context: Context, uri: Uri): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"

    private fun start(context: Context, chooser: Intent, failure: String) {
        try {
            context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
        }
    }
}
