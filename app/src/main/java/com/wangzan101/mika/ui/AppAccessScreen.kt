package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.launchableApps
import com.wangzan101.mika.core.access.*
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** カテゴリ名はインターフェイスにのみ表示され、コアには中国語は含まれません。カテゴリ自体はAI判定のためのものであり、権限を変更するものではありません。 */
@Composable
private fun AppCategory.title(): String = stringResource(
    when (this) {
        AppCategory.Finance -> R.string.appaccess_category_finance
        AppCategory.Social -> R.string.appaccess_category_social
        AppCategory.Game -> R.string.appaccess_category_game
        AppCategory.Shopping -> R.string.appaccess_category_shopping
        AppCategory.Productivity -> R.string.appaccess_category_productivity
        AppCategory.Media -> R.string.appaccess_category_media
        AppCategory.Other -> R.string.appaccess_category_other
        AppCategory.Unknown -> R.string.appaccess_category_unknown
    },
)

/** ユーザーに表示される障害: インターフェイス言語の文章。トラブルシューティングを容易にするために元の説明に従うことができます。 */
private class Failure(val message: Int, val detail: String? = null)

/**
 * 分類するときに、モデルにインターフェイスの言語で理由を記述させます。同じアプリケーションの言語を変更した後でも、古い理由は古い言語のままです。これは問題ではありません。次の分類は上書きされます。
 * 中国語は、リソース ディレクトリ (values-zh / value-b+zh+Hant) と一致する書記体系に従って分割されます。
 */
private fun classifierLanguage(locale: java.util.Locale): String = "Japanese"

@Composable
fun AppAccessScreen(onBack: () -> Unit, classifyApps: Boolean = true) {
    val app = LocalContext.current.applicationContext as MikaApp
    val store = app.appAccess
    val state by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    val language = classifierLanguage(LocalConfiguration.current.locales[0])
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Failure?>(null) }
    var confirmingClassify by remember { mutableStateOf(false) }
    var recommending by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    DisposableEffect(store) { val protection = store.protectUi(); onDispose { protection.close() } }

    // は、許容範囲内にある金融アプリケーションのみを推奨します。無効化されたアプリケーションは再び表示されません。
    val recommended = apps.filter { (pkg, _) ->
        state.classifications[pkg]?.category == AppCategory.Finance && pkg !in state.blocked
    }

    fun save(action: () -> Unit) {
        runCatching(action).onFailure { failure = Failure(R.string.appaccess_error_save, it.message) }
    }
    suspend fun loadApps() {
        apps = withContext(Dispatchers.IO) { launchableApps(app.packageManager, app.packageName) }
    }
    suspend fun refresh(classifyNow: Boolean) {
        busy = true
        failure = null
        try {
            loadApps()
            if (!classifyNow || !classifyApps) return
            val latest = store.current()
            if (!latest.available) return
            val pending = apps.filter { latest.classifications[it.first] == null }
            if (pending.isEmpty()) return
            val model = app.providers.modelFor(ModelRole.Light) ?: app.providers.modelFor(ModelRole.Main)
            if (model == null) {
                failure = Failure(R.string.appaccess_error_no_model)
                return
            }
            val classifier = AppClassifier(model, app.providers.modelFor(ModelRole.Main), language)
            for (batch in pending.chunked(30)) {
                val classified = withContext(Dispatchers.IO) { classifier.classify(batch) }
                save {
                    store.classify(batch.associate { it.first to (classified[it.first] ?: AppClassification(AppCategory.Unknown)) })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = Failure(R.string.appaccess_error_refresh, e.message)
        } finally {
            busy = false
        }
    }
    // がページを開くと、ネイティブ アプリケーション リストを読み取るだけで、コンテンツはモデルに送信されません。分類するには、ユーザーが「更新」をクリックして確認する必要があります。
    LaunchedEffect(Unit) {
        try {
            loadApps()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = Failure(R.string.appaccess_error_load, e.message)
        }
    }

    PageScaffold(stringResource(R.string.appaccess_title), onBack) {
        Text(stringResource(R.string.appaccess_intro))
        Text(stringResource(R.string.appaccess_privacy))
        failure?.let { f ->
            val detail = f.detail?.takeIf { it.isNotBlank() }
            Text(
                if (detail == null) stringResource(f.message) else "${stringResource(f.message)} — $detail",
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (!state.available) {
            Text(stringResource(R.string.appaccess_unavailable))
            TextButton({ save { store.reset() } }) { Text(stringResource(R.string.appaccess_reset)) }
        } else {
            OutlinedTextField(
                search, { search = it },
                label = { Text(stringResource(R.string.appaccess_search)) }, singleLine = true,
            )
            TextButton({
                if (busy) return@TextButton
                val pending = apps.filter { state.classifications[it.first] == null }
                if (classifyApps && pending.isNotEmpty()) confirmingClassify = true else scope.launch { refresh(false) }
            }, enabled = !busy) { Text(stringResource(if (busy) R.string.appaccess_refreshing else R.string.appaccess_refresh)) }
            TextButton({ recommending = true }) {
                Text(
                    if (recommended.isEmpty()) stringResource(R.string.appaccess_recommend)
                    else stringResource(R.string.appaccess_recommend_count, recommended.size),
                )
            }
            TextButton({ save { store.allowAll() } }) { Text(stringResource(R.string.appaccess_allow_all)) }
            AppCategory.entries.forEach { category ->
                val rows = apps.filter { (pkg, label) ->
                    (state.classifications[pkg]?.category ?: AppCategory.Unknown) == category &&
                        (pkg.contains(search, true) || label.contains(search, true))
                }
                if (rows.isNotEmpty()) {
                    GroupLabel("${category.title()} · ${rows.size}")
                    if (category == AppCategory.Game || category == AppCategory.Social) Text(stringResource(R.string.appaccess_risk_note))
                    GroupCard {
                        rows.forEachIndexed { index, (pkg, label) ->
                            val classification = state.classifications[pkg]
                            val detail = when {
                                classification == null -> null
                                classification.manual -> stringResource(R.string.appaccess_category_manual)
                                else -> classification.reason.ifBlank { null }
                            }
                            ListRow(null, label, pkg, first = index == 0, detail = detail, onClick = { editing = pkg }) {
                                Text(stringResource(if (pkg in state.blocked) R.string.appaccess_blocked else R.string.appaccess_allowed))
                                Switch(pkg !in state.blocked, { allowed -> save { store.setBlocked(pkg, !allowed) } })
                            }
                            if (pkg in state.shareAllowed) {
                                TextButton({ save { store.setShareAllowed(pkg, false) } }) {
                                    Text(stringResource(R.string.appaccess_revoke_share, label))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmingClassify) {
        MikaDialog(
            onDismissRequest = { confirmingClassify = false },
            title = { Text(stringResource(R.string.appaccess_send_title)) },
            text = { Text(stringResource(R.string.appaccess_send_body)) },
            confirmButton = { TextButton({
                confirmingClassify = false
                scope.launch { refresh(true) }
            }) { Text(stringResource(R.string.appaccess_send_confirm)) } },
            dismissButton = { TextButton({ confirmingClassify = false }) { Text(stringResource(R.string.appaccess_cancel)) } },
        )
    }
    if (recommending) {
        MikaDialog(
            onDismissRequest = { recommending = false },
            title = { Text(stringResource(R.string.appaccess_recommend_title)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.appaccess_recommend_body))
                    if (recommended.isEmpty()) {
                        Text(stringResource(R.string.appaccess_recommend_empty), Modifier.padding(top = 12.dp))
                    }
                    recommended.forEach { (pkg, label) ->
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) { Text(label) }
                            TextButton({ save { store.blockPackages(setOf(pkg)) } }) { Text(stringResource(R.string.appaccess_block)) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    { save { store.blockPackages(recommended.map { it.first }.toSet()) }; recommending = false },
                    enabled = recommended.isNotEmpty(),
                ) { Text(stringResource(R.string.appaccess_block_all)) }
            },
            dismissButton = { TextButton({ recommending = false }) { Text(stringResource(R.string.appaccess_cancel)) } },
        )
    }
    editing?.let { pkg ->
        MikaDialog(onDismissRequest = { editing = null }, title = { Text(stringResource(R.string.appaccess_edit_category)) }, text = {
            Column { AppCategory.entries.forEach { category ->
                TextButton({
                    save { store.classify(mapOf(pkg to AppClassification(category, "", manual = true))) }
                    editing = null
                }) { Text(category.title()) }
            } }
        }, confirmButton = { TextButton({ editing = null }) { Text(stringResource(R.string.appaccess_cancel)) } })
    }
}
