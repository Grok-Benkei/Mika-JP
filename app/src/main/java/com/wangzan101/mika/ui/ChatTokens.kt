package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.wangzan101.mika.core.record.ConversationTokens
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.record.RunStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** ダイアログ ページでのトークンの使用法: 各ラウンドの最後の行にはこのラウンドの行が含まれており、最上部の列はダイアログがアイドル状態のときに書き込まれます。番号は「プロセスの表示」の先頭の行と同じです。 */
object ChatTokensCopy {
    /** 「12,000トークン」はトークン利用ページと同じように書かれています。 */
    fun amount(n: Long): String = "${UsageCopy.tokens(n)} ${UsageCopy.UNIT}"

    /** 一番上の列のサブタイトル: 「この会話には合計 86,000 のトークンがあります」。 「この会話には少なくとも...」というフルタイムとしてカウントされないラウンドもあります。 */
    fun conversation(t: ConversationTokens): String =
        tr(if (t.partial) R.string.chat_conversation_tokens_partial else R.string.chat_conversation_tokens, amount(t.tokens))

    /**
     * 上部列のサブタイトルの会話の総数。数値がない場合は null。一番上の列はこの場所 (`ChatScreen` の `sub` の最後の `?:`) でのみ使用します。
     * このサブタイトルが不要な場合は、その行を削除してください。各ラウンド終了時のその行のトークンは影響を受けません。
     */
    fun conversationSubtitle(endedRuns: List<String>, metrics: Map<String, RunMetrics>): String? =
        ConversationTokens.of(endedRuns, metrics)?.let(::conversation)
}

/**
 * これらのラウンドの実行概要を読み取ります (実行記録がオフになっている場合、書き込みスレッドによって記録され、ラウンドの終わりにはまだ書き込まれていない可能性があります。すべてのラウンドを読んでいない場合は、しばらくしてから欠落しているラウンドを読んでください)。
 * は実行番号に従って取得され、概要全体は解析されません。ディスクは IO スレッドで読み取られます。
 * ラウンドを変更する場合は、前回読み込んだ内容を保持し、読み込んだ後に変更します。各ラウンドの一番上の列と行は点滅しません。
 */
@Composable
fun rememberRunMetrics(runIds: List<String>): Map<String, RunMetrics> {
    val context = LocalContext.current
    val store = remember { RunStore(context).metrics }
    var found by remember { mutableStateOf(emptyMap<String, RunMetrics>()) }
    LaunchedEffect(runIds) {
        val wanted = runIds.toSet()
        if (wanted.isEmpty()) {
            found = emptyMap()
            return@LaunchedEffect
        }
        // 前回読んでまだ必要なものは残しておき、不足しているものだけを読みます。
        var read = found.filterKeys { it in wanted }
        for (wait in RETRY_MS) {
            val missing = wanted - read.keys
            if (missing.isEmpty()) break
            read = read + withContext(Dispatchers.IO) { store.byIds(missing) }
            found = read
            if (read.keys.containsAll(wanted)) break
            delay(wait)
        }
        found = read
    }
    return found
}

/** フルタイム読み取りと再読み取りの間に間隔はありません。これらを数回読んだ後に不足しているもの（古いため消去されているもの）は、不足しているものとしてカウントされます。 */
private val RETRY_MS = listOf(300L, 1_000L, 2_000L, 4_000L, 0L)
