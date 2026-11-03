package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.ui.components.CapabilityTag
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechOptions
import com.wangzan101.mika.voice.SystemSpeech
import com.wangzan101.mika.voice.DoubaoCredentials
import com.wangzan101.mika.voice.DoubaoProtocol

/** ページ上のアクションを選択します。 */
class SpeechPickActions(
    /** 「携帯電話に同梱」または「不要」：その場で有効となり返却されます。 */
    val pick: (SpeechChoice) -> Unit = {},
    /** (サービスプロバイダー ID、モデル名): 最初に転送を試し、転送が完了したら選択して戻ります。リスト内でクリックされたものと手動で入力されたものの両方がここに表示されます。 */
    val pickModel: (String, String) -> Unit = { _, _ -> },
    /** ビーンバッグストリーミング認識の設定ページに入ります。 */
    val openDoubao: () -> Unit = {},
    /** (API キー、アプリ ID、アクセス トークン、請求方法): 資格情報を暗号化して選択します。インターネットに接続されていません。 */
    val saveDoubao: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    /** 保存した認証情報を使用し、この請求方法に切り替えて選択します。 */
    val useDoubao: (String) -> Unit = {},
    val clearDoubao: () -> Unit = {},
)

/**
 * 設定 › モデル › 音声テキスト変換: モデルのステータスを自分で取得し、他の場所から直接入力できます (入力ボックスの「音声入力はまだ利用できません」など)。
 * (電話機に付属しており、使用されておらず、トライアル転送に合格したサービス プロバイダー モデル) を選択した後、入力したページに戻ります。
 */
@Composable
fun SpeechModelScreen(store: ProviderStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = (context.applicationContext as? MikaApp)?.speech
    var doubaoConfigured by remember { mutableStateOf(settings?.hasDoubaoCredentials == true) }
    // Doubao の設定ページはこのページに積み重ねられています。戻るボタンを押して最初に閉じてください
    var doubaoOpen by remember { mutableStateOf(false) }
    val vm = remember {
        ModelSetupViewModel(
            store, scope,
            speech = settings,
            systemSpeech = {
                val silent = settings?.systemSilent?.value ?: false
                SystemSpeech.capability(context.applicationContext, silent)
            },
        )
    }
    val ui by vm.state.collectAsState()
    val probe by vm.speechProbe.collectAsState()
    LaunchedEffect(Unit) { vm.ensureListed(); vm.clearSpeechProbe() }
    val actions = SpeechPickActions(
        pick = { vm.pickSpeech(it); onBack() },
        pickModel = { pid, model -> vm.pickSpeechModel(pid, model, onPicked = onBack) },
        openDoubao = { doubaoOpen = true },
        // 保存後、音声テキスト変換ページに戻り、選択されていることを確認します。
        saveDoubao = { key, appId, token, resource ->
            checkNotNull(settings).saveDoubao(DoubaoCredentials(key, appId, token), resource)
            doubaoConfigured = true
            vm.pickSpeech(SpeechChoice.Doubao(resource))
            doubaoOpen = false
        },
        useDoubao = { resource -> vm.pickSpeech(SpeechChoice.Doubao(resource)); doubaoOpen = false },
        clearDoubao = {
            settings?.clearDoubao()
            doubaoConfigured = false
            settings?.let { vm.pickSpeech(it.choice.value) }
            doubaoOpen = false
        },
    )
    if (doubaoOpen) {
        BackHandler { doubaoOpen = false }
        DoubaoSetupPage(ui.speech, doubaoConfigured, actions) { doubaoOpen = false }
    } else {
        SpeechModelContent(ui, probe, actions, onBack, doubaoConfigured)
    }
}

/** ステータスのない選択ページ。テストに便利です。上部の段落には「電話機に付属」と「必要ありません」と記載されており、以下は単一選択用にサービス プロバイダーごとにグループ化されています。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeechModelContent(ui: ModelSetupUi, probe: Map<String, String>, actions: SpeechPickActions, onBack: () -> Unit, doubaoConfigured: Boolean = false) {
    val c = MikaTheme.colors
    val system = ui.systemSpeech
    val choice = ui.speech
    // 選択していないときは、付属の携帯電話を持っていてそれを使用していたので、選択しなくても選択済みにしていました。
    // は、応答なしとしてマークされている場合はマークされません。線はまだクリックできるので、再度選択してマークをクリアし、もう一度試してください。
    val systemSelected = system.usable && (choice == SpeechChoice.System || choice == SpeechChoice.Auto)
    Page(tr(R.string.role_speech), onBack) {
        Text(
            tr(R.string.speech_hint), Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 14.5.sp, lineHeight = 22.sp, color = c.ink2,
        )
        GroupCard {
            RadioRow(
                tr(R.string.speech_system), selected = systemSelected, enabled = system.available,
                onClick = { actions.pick(SpeechChoice.System) }, first = true,
                subtitle = tr(
                    when {
                        !system.available -> R.string.speech_system_none_note
                        !system.responsive -> R.string.speech_system_silent_note
                        system.onDevice -> R.string.speech_system_note
                        else -> R.string.speech_system_note_online
                    },
                ),
                trailing = {
                    when {
                        system.usable -> StatusPill(tr(R.string.speech_tag_ok), PillTone.Ok)
                        system.available -> StatusPill(tr(R.string.speech_tag_silent), PillTone.Neutral)
                        else -> StatusPill(tr(R.string.speech_tag_none), PillTone.Neutral)
                    }
                },
            )
            RadioRow(
                tr(R.string.models_off), selected = choice == SpeechChoice.Off, onClick = { actions.pick(SpeechChoice.Off) },
                subtitle = tr(R.string.speech_off_note),
            )
        }
        var showOtherRegions by remember { mutableStateOf(false) }
        Text(tr(R.string.models_other_regions), Modifier.padding(vertical = 10.dp)
            .clickable { showOtherRegions = !showOtherRegions }.testTag("speech-other-regions"), color = c.accent)
        if (showOtherRegions || doubaoConfigured || choice is SpeechChoice.Doubao) DoubaoRow(choice, doubaoConfigured, actions)
        SpeechOptions.groups(ui.providers).forEach { g ->
            val p = g.provider
            val testing = ui.speechTesting == p.id
            GroupLabel(ModelsCopy.providerName(p))
            // 手動で選択したモデルはリストに含まれていないため、現在どのモデルが使用されているかがわかるように、線が追加されて選択済みとしてマークされます。
            val picked = (choice as? SpeechChoice.Provider)?.takeIf { it.providerId == p.id }?.model
            val rows = if (picked != null && picked !in g.models) g.models + picked else g.models
            GroupCard {
                rows.forEachIndexed { i, id ->
                    RadioRow(
                        id, selected = id == picked, onClick = { actions.pickModel(p.id, id) }, first = i == 0,
                        titleStyle = ModelIdStyle, enabled = ui.speechTesting == null,
                        trailing = { CapabilityTag(tr(R.string.speech_tag_speech)) },
                    )
                }
                // リストにない（リストが不完全、または当社がリストを提供していない）手動で記入
                Box(Modifier.testTag("speech-manual-${p.name}")) {
                    ManualModel(p, if (testing) ListState.Running else null, first = rows.isEmpty()) { actions.pickModel(p.id, it) }
                }
            }
            when {
                probe[p.id] != null -> {
                    // リストが画面外に出て長すぎるため、クリックしても反応がありません。リストが出たら、表示されるまでスクロールしてください。
                    val why = probe.getValue(p.id)
                    val into = remember { BringIntoViewRequester() }
                    LaunchedEffect(why) { into.bringIntoView() }
                    FooterNote(why, Modifier.bringIntoViewRequester(into).testTag("speech-pick-failed"), color = c.bad)
                }
                testing && rows.isNotEmpty() -> FooterNote(ModelSetupCopy.TESTING, Modifier.testTag("speech-pick-running"))
            }
        }
        FooterNote(tr(R.string.speech_footer), Modifier.padding(top = 8.dp))
    }
}

/**
 * 「音声転送サービス」グループ: 1 行のみ。認証情報が保存されていない場合は、認証情報をクリックして設定ページに入ります。保存後、円をクリックして使用し、右側の「設定」に移動して資格情報を変更または削除します。
 * 資格情報はチャット サービス プロバイダーとは別に保存され、モデルはリストされず、トライアル転送は行われません。
 */
@Composable
private fun DoubaoRow(choice: SpeechChoice, configured: Boolean, actions: SpeechPickActions) {
    val c = MikaTheme.colors
    GroupLabel(tr(R.string.speech_doubao_group))
    GroupCard {
        val selected = configured && choice is SpeechChoice.Doubao
        val plan = (choice as? SpeechChoice.Doubao)?.resourceId ?: DoubaoProtocol.DURATION
        RadioRow(
            tr(R.string.speech_doubao_model), selected = selected, first = true,
            onClick = { if (configured) actions.pick(SpeechChoice.Doubao(plan)) else actions.openDoubao() },
            subtitle = if (configured && selected) tr(R.string.speech_doubao_configured, tr(planName(plan))) else tr(R.string.speech_doubao_note),
            trailing = {
                if (configured) {
                    Row(
                        Modifier.clip(MikaShapes.Pill).clickable(onClick = actions.openDoubao).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
                            .testTag("speech-doubao-edit"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr(R.string.speech_doubao_edit), style = MikaType.Value, color = c.ink2)
                        RowChevron()
                    }
                } else {
                    StatusPill(tr(R.string.settings_not_set), PillTone.Neutral)
                    RowChevron()
                }
            },
        )
    }
}

private fun planName(resource: String): Int =
    if (resource == DoubaoProtocol.CONCURRENT) R.string.speech_doubao_concurrent else R.string.speech_doubao_duration

/**
 * Beanbao ストリーミング識別設定ページ: 認証情報 (新しいバージョンのコンソールの API キー、または古いバージョンのアプリ ID + アクセス トークン) を入力し、請求方法を選択します。
 * はインターネットに接続せずに保存されます。保存された資格情報はバックフィルされず、入力ボックスは回復可能な状態になりません。請求方法のみを変更し、認証情報を再入力しない場合は、既存の認証情報が使用されます。 修復アクションは、
 */
@Composable
fun DoubaoSetupPage(choice: SpeechChoice, configured: Boolean, actions: SpeechPickActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    var legacy by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var resource by remember { mutableStateOf((choice as? SpeechChoice.Doubao)?.resourceId ?: DoubaoProtocol.DURATION) }
    var failed by remember { mutableStateOf(false) }
    val credentials = DoubaoCredentials(if (legacy) "" else key.trim(), if (legacy) appId.trim() else "", if (legacy) token.trim() else "")
    val unchanged = key.isBlank() && appId.isBlank() && token.isBlank()
    Page(tr(R.string.speech_doubao_model), onBack) {
        Text(tr(R.string.speech_doubao_setup_hint), Modifier.padding(top = 4.dp, bottom = 4.dp), fontSize = 14.5.sp, lineHeight = 22.sp, color = c.ink2)
        GroupLabel(tr(R.string.speech_doubao_credentials))
        SegmentedControl(
            listOf(tr(R.string.speech_doubao_new), tr(R.string.speech_doubao_legacy)), if (legacy) 1 else 0,
            onSelect = { legacy = it == 1; key = ""; appId = ""; token = "" },
            Modifier.padding(bottom = 8.dp),
        )
        GroupCard {
            if (legacy) {
                InputRow("App ID", appId, { appId = it }, tr(R.string.speech_doubao_enter_app), first = true, labelWidth = 110.dp)
                InputRow("Access Token", token, { token = it }, tr(R.string.speech_doubao_enter_key), secret = true, labelWidth = 110.dp)
            } else {
                InputRow("API Key", key, { key = it }, tr(R.string.speech_doubao_enter_key), first = true, secret = true, labelWidth = 110.dp)
            }
        }
        GroupLabel(tr(R.string.speech_doubao_plan))
        GroupCard {
            RadioRow(
                tr(R.string.speech_doubao_duration), selected = resource == DoubaoProtocol.DURATION, first = true,
                onClick = { resource = DoubaoProtocol.DURATION }, subtitle = tr(R.string.speech_doubao_duration_note),
            )
            RadioRow(
                tr(R.string.speech_doubao_concurrent), selected = resource == DoubaoProtocol.CONCURRENT,
                onClick = { resource = DoubaoProtocol.CONCURRENT }, subtitle = tr(R.string.speech_doubao_concurrent_note),
            )
        }
        FooterNote(tr(R.string.speech_doubao_privacy), Modifier.padding(top = 8.dp))
        MikaButton(
            tr(R.string.speech_doubao_save),
            onClick = {
                failed = runCatching {
                    if (configured && unchanged) actions.useDoubao(resource)
                    else actions.saveDoubao(credentials.apiKey, credentials.appId, credentials.accessKey, resource)
                }.isFailure
                key = ""; appId = ""; token = ""
            },
            Modifier.fillMaxWidth().padding(top = 8.dp).testTag("speech-doubao-save"),
            enabled = credentials.valid || configured && unchanged,
        )
        if (failed) FooterNote(tr(R.string.speech_doubao_save_failed), Modifier.padding(top = 8.dp), color = c.bad)
        if (configured) {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                Text(
                    tr(R.string.speech_doubao_remove),
                    Modifier.clip(MikaShapes.Pill).clickable(onClick = actions.clearDoubao).padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("speech-doubao-remove"),
                    style = MikaType.Body, color = c.bad,
                )
            }
        }
    }
}
