package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.core.oauth.AccountSignInException
import com.wangzan101.mika.core.oauth.AccountSignInFailure
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.chatgpt.SignInFailure
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.PillTone
import com.wangzan101.mika.ui.components.RadioRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.SearchField
import com.wangzan101.mika.ui.components.StatusPill
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaRadius
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaSize
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.voice.SpeechChoice
import com.wangzan101.mika.voice.SpeechOptions

/** 設定 › モデル: 開始時の構成モデル ([ModelSetupScreen]) と同じページであり、ブート プロセスにはありません。 */
@Composable
fun ModelsScreen(store: ProviderStore, onBack: () -> Unit, onOpenSpeech: (() -> Unit)? = null, onOpenUsage: (() -> Unit)? = null) {
    ModelSetupScreen(store, onboarding = false, onBack = onBack, onDone = onBack, onOpenSpeech = onOpenSpeech, onOpenUsage = onOpenUsage)
}

/** サブページで共有されるスケルトン: トップ バー + スクロール可能なコンテンツ。 */
@Composable
internal fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(title, MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                content()
            }
        }
    }
}

/** 使用方法カード: 大きなアイコン ブロック、「名前・何をするか」、選択した内容 (モデル名は等幅文字)、および説明。 */
@Composable
internal fun RoleCard(icon: ImageVector, label: String, line: ModelsCopy.RoleLine, first: Boolean = false, tag: String, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column {
        if (!first) Hairline(start = 0.dp)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(40.dp).background(c.neutralSoft, RoleTileShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(21.dp), tint = c.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.ink3)
                Text(
                    line.title, Modifier.padding(top = 1.dp), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = if (line.isModel) FontFamily.Monospace else null, color = if (line.unset) c.ink3 else c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (line.subtitle.isNotEmpty()) {
                    Text(
                        line.subtitle, Modifier.padding(top = 1.dp), style = MikaType.Caption,
                        color = if (line.subtitleOk) c.ok else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RowChevron()
        }
    }
}

private val RoleTileShape = RoundedCornerShape(12.dp)

/** サービス プロバイダーの行: 左側には、名前のイニシャルが入ったアイコン ブロックがあります。 [warning] は、リマインドする文です (リマインダーの色を使用して新しい行を開始します)。 */
@Composable
internal fun ProviderRow(p: Provider, line: String, first: Boolean, warning: String? = null, onClick: () -> Unit) {
    val c = MikaTheme.colors
    Column {
        if (!first) Hairline()
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("provider-${p.name}").padding(horizontal = 16.dp).padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(MikaSize.Tile).background(c.neutralSoft, MikaShapes.Tile), contentAlignment = Alignment.Center) {
                Text(p.name.take(1).uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(ModelsCopy.providerName(p), style = MikaType.Body, color = c.ink, maxLines = 1)
                Text(line, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
                warning?.let { Text(it, Modifier.padding(top = 2.dp).testTag("provider-warning-${p.name}"), style = MikaType.Caption, color = c.warn) }
            }
            RowChevron()
        }
    }
}

// ---------- 用途に合わせて機種を選びましょう ----------

class RolePickerActions(
    /** (サービスプロバイダーID、モデル名) */
    val pick: (String, String) -> Unit = { _, _ -> },
    /** リストを提供しないサービス プロバイダーによって手動で入力されたモデル名 (サービス プロバイダー ID、モデル名)。まず呼び出しを試み、成功した場合は選択します。 */
    val pickManual: (String, String) -> Unit = { _, _ -> },
    /** 画像を参照してください。軽量固定オプション (メインモデルを使用し、使用しません)。 */
    val choose: (RoleChoice) -> Unit = {},
    val addProvider: () -> Unit = {},
)

/** ページ上部の固定オプションを選択します。画像には「メイン モデルを使用する」または「使用しない」、軽量には「メイン モデルを使用」。メインモデルはありません。 */
internal fun fixedChoices(role: ModelRole): List<RoleChoice> = when (role) {
    ModelRole.Main -> emptyList()
    ModelRole.Vision -> listOf(RoleChoice.SameAsMain, RoleChoice.Off)
    ModelRole.Light -> listOf(RoleChoice.SameAsMain)
}

/**
 * 目的を選択してください: 写真の表示、軽量。一番上は固定オプションです。以下では、接続されているすべてのサービス プロバイダーのすべてのモデルがサービス プロバイダーごとにグループ化されています (記憶されたリストを使用します)。
 * タグは参照専用であり、能力によってフィルタリングされたりグレー表示されたりすることはありません。各モデルの下にモデル名を手動で入力することができます (リストにない、または当社がリストを提供しません)。手動で入力されたモデル名には「Hand-Filled」とマークされます。クリックすると有効になり、戻ります。
 */
@Composable
fun RolePickerContent(ui: ModelSetupUi, role: ModelRole, actions: RolePickerActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    var filter by rememberSaveable { mutableStateOf("") }
    val groups = ui.providers.map { it to ui.options(it) }
    val total = groups.sumOf { it.second.size }
    val q = filter.trim()
    Page(ModelsCopy.roleName(role), onBack) {
        Text(ModelsCopy.roleHint(role), Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 14.5.sp, lineHeight = 22.sp, color = c.ink2)
        val fixed = fixedChoices(role)
        if (fixed.isNotEmpty()) {
            GroupCard {
                fixed.forEachIndexed { i, choice ->
                    RadioRow(
                        ModelsCopy.choiceTitle(choice), selected = ui.roles[role] == choice, onClick = { actions.choose(choice) },
                        subtitle = ModelsCopy.choiceNote(role, choice), first = i == 0,
                    )
                }
            }
        }
        if (total > FILTER_FROM) {
            GroupLabel(ModelsCopy.PICK_FROM, Modifier.padding(top = 8.dp))
            Box(Modifier.padding(bottom = 4.dp)) {
                SearchField(filter, { filter = it }, ModelsCopy.FILTER_HINT, Modifier.testTag("model-filter"))
            }
        }
        var anyShown = false
        groups.forEach { (p, options) ->
            val shown = options.filter { q.isEmpty() || it.id.contains(q, ignoreCase = true) }
            // この会社はモデルをリストできません: 手充填が唯一のオプションです
            val noList = p.models?.isEmpty() == true
            if (q.isNotEmpty() && shown.isEmpty()) return@forEach
            anyShown = true
            val state = ui.listing[p.id]
            GroupLabel(ModelsCopy.providerName(p))
            GroupCard {
                shown.take(PICK_AT_MOST).forEachIndexed { i, o ->
                    RadioRow(
                        o.id, selected = ui.picked(role, p.id, o.id), onClick = { actions.pick(p.id, o.id) }, first = i == 0,
                        titleStyle = ModelIdStyle, below = { Tags(o, forVision = role == ModelRole.Vision) },
                    )
                }
                ManualModel(p, ui.manualPick[p.id], first = shown.isEmpty()) { actions.pickManual(p.id, it) }
            }
            val manualState = ui.manualPick[p.id]
            when {
                manualState is ListState.Failed -> FooterNote(manualState.message, Modifier.testTag("manual-model-failed"), color = c.bad)
                // 選択リスト内のモデルを最初に呼び出すこともできます (ModelSetupUi.probeBeforePick を参照)。手書きの線は進行状況を単独で表示します
                !noList && manualState == ListState.Running -> FooterNote(ModelSetupCopy.TESTING, Modifier.testTag("pick-probe-running"))
                state == ListState.Running -> FooterNote(ModelsCopy.LISTING)
                state is ListState.Failed -> FooterNote(ModelSetupCopy.failed(state.message), color = c.bad)
                shown.size > PICK_AT_MOST -> FooterNote(ModelsCopy.more(shown.size - PICK_AT_MOST))
            }
        }
        if (q.isNotEmpty() && !anyShown) FooterNote(ModelsCopy.NO_MATCH)
        if (ui.providers.isEmpty()) FooterNote(ModelsCopy.ROLE_EMPTY)
        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(MikaIcons.Plus, ModelsCopy.ADD_PROVIDER, first = true, titleColor = c.accent, tone = TileTone.Accent, onClick = actions.addProvider)
        }
        FooterNote(ModelsCopy.PICKER_NOTE)
    }
}

/** 手動入力のモデル名 (リストにない、またはサービスがリストを提供しない): サービス プロバイダーのドキュメントに従って入力します。 「使用」をクリックして一度呼び出してみて（[state] が進行状況です）、それを選択して手入力として記録するだけです。 */
@Composable
internal fun ManualModel(p: Provider, state: ListState?, first: Boolean, onUse: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val running = state == ListState.Running
    val enabled = name.isNotBlank() && !running
    val example = ModelSetupCopy.modelExample(ProviderPresets.forHost(p.baseUrl)?.kind ?: p.kind)
    InputRow(ModelsCopy.MODEL_NAME, name, { name = it }, example, first = first, modifier = Modifier.testTag("manual-model")) {
        Text(
            if (running) ModelSetupCopy.TESTING else ModelsCopy.USE, Modifier.clickable(enabled = enabled) { onUse(name.trim()) }.testTag("manual-model-use"),
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (enabled) MikaTheme.colors.accent else MikaTheme.colors.ink3,
        )
    }
}

private const val FILTER_FROM = 8
private const val PICK_AT_MOST = 80

/** サービス プロバイダーの詳細には、最大でいくつかのモデルを含めることができます。残りは「すべてのモデル」ページに移動します。 */
internal const val MODEL_PREVIEW = 3

// ---------- サービスプロバイダー ----------

class ProviderDetailActions(
    val refresh: () -> Unit = {},
    val changeKey: (String) -> Unit = {},
    val remove: () -> Unit = {},
    /** 特定の目的で当社のモデルを使用します（「使用」パネルをクリックします）。 */
    val use: (ModelRole, String) -> Unit = { _, _ -> },
    /** ユーザーは、このモデルのモデルの 1 つをパネル内のスイッチとして使用できるかどうか (パネル内のスイッチとして「使用」) と質問しました。 */
    val setVision: (String, Boolean) -> Unit = { _, _ -> },
    val showAll: () -> Unit = {},
    /** 使用中だが再度検証されていない手入力モデルを呼び出してみます ([ModelSetupUi.unverified] を参照)。 */
    val retest: () -> Unit = {},
    /** アカウントでログインしているサービスプロバイダー（ChatGPT、xAI、OpenRouter）の場合：再度ログインし、ログアウトして、相手の利用状況またはアカウントページを開きます。 */
    val signIn: () -> Unit = {},
    val signOut: () -> Unit = {},
    val manageUsage: () -> Unit = {},
    /** このデフォルト モデルをメイン モデルとして設定します (アカウント ログインの詳細の行)。 */
    val useAsMain: () -> Unit = {},
)

/**
 * サービス プロバイダー: 接続 (ステータス、インターフェイス、アドレス、キーは置き換え可能)、いくつかのモデル (最初に使用中のモデル、最大 [MODEL_PREVIEW]、残りは「すべて表示」をクリック)、削除します。
 * モデルをクリックすると、下から「使用する」が表示されます。基本的にページの長さは固定されており、モデル数が多くても削除が大幅に遅れることはありません。
 */
@Composable
fun ProviderDetailContent(ui: ModelSetupUi, p: Provider, actions: ProviderDetailActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    var changingKey by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var using by remember { mutableStateOf<ModelOption?>(null) }
    val state = ui.listing[p.id]
    val list = ui.sortedOptions(p)
    Page(ModelsCopy.providerName(p), onBack) {
        GroupLabel(ModelsCopy.CONNECTION)
        if (p.kind == ProviderConfig.Kind.ChatGpt) {
            ChatGptConnection(ui, state, actions)
        } else if (p.kind == ProviderConfig.Kind.Xai || p.signIn == ProviderStore.OPENROUTER_SIGN_IN) {
            AccountConnection(ui, p, state, actions)
        } else {
            GroupCard {
                ListRow(null, ModelsCopy.STATUS, first = true) {
                    when (state) {
                        ListState.Running -> RowValue(ModelsCopy.LISTING)
                        is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                        else -> RowValue(ModelsCopy.CONNECTED, ValueTone.Ok)
                    }
                }
                ListRow(null, ModelsCopy.KIND) { RowValue(ModelsCopy.kindLabel(p.kind)) }
                ListRow(null, ModelsCopy.BASE_URL) { RowValue(ModelsCopy.shortUrl(p.baseUrl)) }
                ListRow(null, ModelsCopy.API_KEY, onClick = { changingKey = true }) {
                    RowValue(if (p.id in ui.keyed) ModelsCopy.KEY_SAVED else ModelsCopy.KEY_NONE)
                    RowValue(ModelsCopy.CHANGE, ValueTone.Link)
                }
            }
        }
        (state as? ListState.Failed)?.let { FooterNote(ModelSetupCopy.failed(it.message), color = c.bad) }
        if (ui.pickToProbe == p.id) {
            ui.probeNote?.let { FooterNote(it, Modifier.testTag("provider-selection-note"), color = c.warn) }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GroupLabel(ModelsCopy.MODELS, Modifier.weight(1f))
            BarIconButton(MikaIcons.Refresh, ModelsCopy.REFRESH, actions.refresh, enabled = state != ListState.Running)
        }
        if (list.isNotEmpty()) {
            GroupCard {
                list.take(MODEL_PREVIEW).forEachIndexed { i, o -> ModelRow(o, ui.rolesOf(p.id, o.id), first = i == 0) { using = o } }
                if (list.size > MODEL_PREVIEW) {
                    ListRow(null, ModelsCopy.viewAll(list.size), titleColor = c.accent, onClick = actions.showAll) { RowChevron() }
                }
            }
            FooterNote(ModelsCopy.MODELS_NOTE)
        }
        // はテストをスキップし、直接保存しました。テストされていないことを明確にし、「この会社はモデル リストを提供していない」とは言わないでください。その記述は当てはまりません。
        val untested = ui.untested(p)
        val unverified = ui.unverified(p)
        if (untested) {
            FooterNote(ModelsCopy.UNTESTED_NOTE, Modifier.testTag("provider-untested"), color = c.warn)
        } else if (unverified.isNotEmpty()) {
            FooterNote(ModelsCopy.unverifiedNote(unverified), Modifier.testTag("provider-unverified"), color = c.warn)
        }
        // 使用中のモデルがある場合は、その場で再テストできます。使用中のモデルがない場合は、最初にリストを更新するか、モデルを選択します。
        if (unverified.isNotEmpty()) {
            val retest = ui.retest[p.id]
            MikaButton(
                if (retest == ListState.Running) ModelSetupCopy.TESTING else ModelsCopy.RETEST, actions.retest,
                Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("provider-retest"),
                kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = retest != ListState.Running,
            )
            (retest as? ListState.Failed)?.let { FooterNote(it.message, Modifier.testTag("provider-retest-failed"), color = c.bad) }
        }
        when {
            // はテストをスキップし、モデルをリストせずに保存しました。これでは「この会社はリスティングを提供していない」という証拠にはなりません。
            p.models?.isEmpty() == true && !untested -> FooterNote(ModelsCopy.NO_LIST_NOTE)
            p.models == null && list.isEmpty() && state != ListState.Running -> FooterNote(ModelsCopy.NOT_LISTED)
        }

        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(MikaIcons.Trash, ModelsCopy.DELETE, titleColor = c.bad, tone = TileTone.Bad, first = true, onClick = { confirmDelete = true })
        }
    }

    using?.let { o -> UseAsSheet(ui, p, o.id, onUse = { role -> actions.use(role, o.id) }, onVision = { actions.setVision(o.id, it) }, onDismiss = { using = null }) }
    if (changingKey) {
        var key by remember { mutableStateOf("") }
        MikaDialog(
            onDismissRequest = { changingKey = false },
            title = { Text(ModelsCopy.CHANGE_KEY) },
            text = {
                KeyboardDismissArea {
                    Box(Modifier.fillMaxWidth().keepsKeyboard().background(c.fill, MikaShapes.Button).padding(12.dp)) {
                        if (key.isEmpty()) Text(ModelsCopy.NEW_KEY_HINT, style = MikaType.Body, color = c.ink3)
                        BasicTextField(
                            key, { key = it }, Modifier.fillMaxWidth().testTag("new-key"), singleLine = true,
                            textStyle = MikaType.Body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton({
                    changingKey = false
                    if (key.isNotBlank()) actions.changeKey(key)
                }) { Text(ModelsCopy.SAVE) }
            },
            dismissButton = { TextButton({ changingKey = false }) { Text(ModelSetupCopy.CANCEL) } },
        )
    }
    if (confirmDelete) {
        MikaDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(ModelsCopy.deleteTitle(ModelsCopy.providerName(p))) },
            text = { Text(ModelsCopy.deleteBody(ui.rolesOf(p))) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    actions.remove()
                }, Modifier.testTag("confirm-delete")) { Text(ModelsCopy.DELETE_CONFIRM, color = c.bad) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(ModelSetupCopy.CANCEL) } },
        )
    }
}

/**
 * ChatGPT 接続: アカウント (電子メール)、ログアウトまたは再ログイン、使用状況の管理 (ChatGPT 使用状況ページを開く)。
 * ログアウト後、この電話のトークンのみがクリアされ、サービス プロバイダー、モデル、目的は保持されます。
 */
@Composable
private fun ChatGptConnection(ui: ModelSetupUi, state: ListState?, actions: ProviderDetailActions) {
    val c = MikaTheme.colors
    val account = ui.chatGptAccount
    val signedIn = account?.signedIn == true
    GroupCard {
        ListRow(null, ModelsCopy.STATUS, first = true) {
            when {
                !signedIn -> RowValue(ModelsCopy.CHATGPT_SIGNED_OUT, ValueTone.Warn)
                state == ListState.Running -> RowValue(ModelsCopy.LISTING)
                state is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                else -> RowValue(ModelsCopy.CHATGPT_SIGNED_IN, ValueTone.Ok)
            }
        }
        account?.email?.takeIf { it.isNotEmpty() }?.let { email -> ListRow(null, ModelsCopy.CHATGPT_ACCOUNT) { RowValue(email) } }
        ListRow(null, ModelsCopy.CHATGPT_MANAGE_USAGE, titleColor = c.accent, onClick = actions.manageUsage) { RowChevron() }
        if (signedIn) {
            ListRow(null, ModelsCopy.CHATGPT_SIGN_OUT, titleColor = c.bad, onClick = actions.signOut)
        } else {
            ListRow(
                null, if (ui.chatGptSignIn == ListState.Running) ModelsCopy.CHATGPT_WAITING else ModelsCopy.CHATGPT_CONTINUE,
                titleColor = c.accent, onClick = actions.signIn,
            )
        }
    }
    FooterNote(if (signedIn) ModelsCopy.CHATGPT_PLAN_NOTE else ModelsCopy.CHATGPT_SIGNED_OUT_NOTE)
    if (ui.chatGptRevokeUnconfirmed) FooterNote(ModelsCopy.CHATGPT_REVOKE_UNCONFIRMED, color = c.warn)
    (ui.chatGptSignIn as? ListState.Failed)?.let { FooterNote(it.message, color = c.bad) }
}

/**
 * xAI または OpenRouter アカウントでログインするための接続: アカウント、ステータス、メインモデルとして設定、相手のアカウント ページ (OpenRouter は Keys ページ) を開き、ログアウトまたは再ログインします。
 * 終了では、この電話機のログイン情報 (xAI トークン、OpenRouter 交換キー) のみがクリアされ、サービス プロバイダー、モデル、目的はすべて保持されます。
 */
@Composable
private fun AccountConnection(ui: ModelSetupUi, p: Provider, state: ListState?, actions: ProviderDetailActions) {
    val c = MikaTheme.colors
    val kind = if (p.kind == ProviderConfig.Kind.Xai) SignInKind.Xai else SignInKind.OpenRouter
    val account = if (kind == SignInKind.Xai) ui.xaiAccount else ui.openRouterAccount
    val signedIn = account?.signedIn == true
    val (_, waiting, failure) = ui.signInState(kind)
    GroupCard {
        account?.label?.takeIf { it.isNotEmpty() }?.let { label -> ListRow(null, ModelsCopy.CHATGPT_ACCOUNT, first = true) { RowValue(label) } }
        ListRow(null, ModelsCopy.STATUS, first = account?.label.isNullOrEmpty()) {
            when {
                !signedIn -> RowValue(ModelsCopy.CHATGPT_SIGNED_OUT, ValueTone.Warn)
                state == ListState.Running -> RowValue(ModelsCopy.LISTING)
                state is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                else -> RowValue(ModelsCopy.CHATGPT_SIGNED_IN, ValueTone.Ok)
            }
        }
    }
    GroupCard {
        // のメインモデルはすでに存在しているため、このラインには入れません。
        val isMain = ui.configFor(ModelRole.Main)?.providerId == p.id
        if (signedIn && !isMain && ui.options(p).isNotEmpty()) {
            ListRow(null, ModelsCopy.SET_MAIN, ModelsCopy.SET_MAIN_NOTE, first = true, onClick = actions.useAsMain) { RowChevron() }
        }
        ListRow(null, ModelsCopy.accountManage(kind), titleColor = c.accent, first = !(signedIn && !isMain && ui.options(p).isNotEmpty()), onClick = actions.manageUsage) { RowChevron() }
        if (signedIn) {
            ListRow(null, ModelsCopy.CHATGPT_SIGN_OUT, ModelsCopy.SIGN_OUT_NOTE, titleColor = c.bad, onClick = actions.signOut)
        } else {
            ListRow(null, if (waiting) ModelsCopy.SIGN_IN_RUNNING else ModelsCopy.signInStart(kind), titleColor = c.accent, onClick = actions.signIn)
        }
    }
    FooterNote(ModelsCopy.accountNote(kind, signedIn))
    if (signedIn && kind == SignInKind.Xai) FooterNote(ModelsCopy.XAI_403_NOTE, Modifier.testTag("account-403-note"), color = c.warn)
    failure?.let { FooterNote(it, color = c.bad) }
}

/**
 * "<サービス プロバイダー> · すべてのモデル": 検索ボックスはトップ バーの下に固定されており、リストは下にスクロールするだけです (モデルが多い場合はタイプセットが必要です)。右上隅が更新されて再リストされます。
 * ポイント モデルも「使用」をポップアップ表示します。
 */
@Composable
fun AllModelsContent(ui: ModelSetupUi, p: Provider, actions: ProviderDetailActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    var filter by rememberSaveable { mutableStateOf("") }
    var using by remember { mutableStateOf<ModelOption?>(null) }
    val state = ui.listing[p.id]
    val all = ui.sortedOptions(p)
    val q = filter.trim()
    val shown = all.filter { q.isEmpty() || it.id.contains(q, ignoreCase = true) }
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(ModelsCopy.allModelsTitle(ModelsCopy.providerName(p)), MikaIcons.Back, onBack, stringResource(R.string.back), right = {
                BarIconButton(MikaIcons.Refresh, ModelsCopy.REFRESH, actions.refresh, enabled = state != ListState.Running)
            })
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)) {
                SearchField(filter, { filter = it }, ModelsCopy.FILTER_HINT, Modifier.testTag("all-models-filter"))
            }
            when (state) {
                ListState.Running -> FooterNote(ModelsCopy.LISTING)
                is ListState.Failed -> FooterNote(ModelSetupCopy.failed(state.message), color = c.bad)
                else -> Unit
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                itemsIndexed(shown, key = { _, o -> o.id }) { i, o ->
                    Box(Modifier.fillMaxWidth().clip(rowShape(i, shown.size)).background(c.surface)) {
                        ModelRow(o, ui.rolesOf(p.id, o.id), first = i == 0) { using = o }
                    }
                }
                item {
                    FooterNote(
                        if (q.isNotEmpty() && shown.isEmpty()) ModelsCopy.NO_MATCH else ModelsCopy.allModelsNote(all.size),
                        Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
    using?.let { o -> UseAsSheet(ui, p, o.id, onUse = { role -> actions.use(role, o.id) }, onVision = { actions.setVision(o.id, it) }, onDismiss = { using = null }) }
}

/** カードが遅延ロード行に分割されると、各行の角が丸くなります。最初の行は切り上げられ、最後の行は切り捨てられます。 */
private fun rowShape(i: Int, n: Int): RoundedCornerShape {
    val r = MikaRadius.Card
    return RoundedCornerShape(topStart = if (i == 0) r else 0.dp, topEnd = if (i == 0) r else 0.dp,
        bottomStart = if (i == n - 1) r else 0.dp, bottomEnd = if (i == n - 1) r else 0.dp)
}

/** モデル ライン: モデル名 (同じ幅) とラベル。右側は、どのような目的で機能するかを示します。 */
@Composable
private fun ModelRow(o: ModelOption, roles: List<ModelRole>, first: Boolean, onClick: () -> Unit) {
    Column {
        if (!first) Hairline(start = 16.dp)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("model-${o.id}").padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(o.id, style = ModelIdStyle, color = MikaTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.padding(top = 4.dp)) { Tags(o, accent = false) }
            }
            roles.forEach { StatusPill(ModelsCopy.roleName(it), PillTone.Accent) }
            RowChevron()
        }
    }
}

/**
 * モデルをクリックして下から上がります。「<サービス プロバイダー> · メイン モデル / 画像閲覧モデル / 軽量モデルとして使用されます。クリックするとすぐに有効になります。
 * 以下は「写真を表示できる」スイッチです。リストされていないモデルはユーザーに任されており、リストされているモデルは変更することもできます。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UseAsSheet(ui: ModelSetupUi, p: Provider, model: String, onUse: (ModelRole) -> Unit, onVision: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("use-as")) {
            Text(model, Modifier.padding(horizontal = 16.dp), style = ModelIdStyle, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ModelsCopy.useAsSubtitle(ModelsCopy.providerName(p)), Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp), style = MikaType.Caption, color = c.ink3)
            ModelRole.entries.forEachIndexed { i, role ->
                val on = ui.picked(role, p.id, model)
                RadioRow(
                    ModelsCopy.roleName(role), selected = on, first = i == 0,
                    subtitle = if (on) ModelsCopy.IN_USE else ModelsCopy.roleWhat(role),
                    onClick = { onUse(role); onDismiss() },
                )
            }
            ui.options(p).firstOrNull { it.id == model }?.let { o ->
                val usedForVision = ModelRole.Vision in ui.rolesOf(p.id, model)
                val sees = ModelOptions.seesImages(o.capabilities.vision, o.visionByUser, usedForVision)
                ListRow(null, ModelsCopy.SEES_IMAGES, ModelsCopy.seesNote(o, usedForVision)) {
                    MikaSwitch(sees, onVision, Modifier.testTag("model-sees-images"))
                }
            }
            ListRow(null, ModelSetupCopy.CANCEL, titleColor = c.ink2, onClick = onDismiss)
        }
    }
}

/** モデル ページのテキスト (目的、選択ページ、サービス プロバイダーの詳細、全モデル) (純粋な機能、テストが容易)。 3つの使い方はすべて、ここでのみアプリで利用できます。 */
object ModelsCopy {
    val TITLE: String get() = tr(R.string.settings_model)
    val LEAD: String get() = tr(R.string.models_lead)
    val ROLES: String get() = tr(R.string.models_roles)
    val PROVIDERS: String get() = tr(R.string.models_providers)
    val ADD_PROVIDER: String get() = tr(R.string.models_add_provider)
    val KEY_NOTE: String get() = tr(R.string.models_key_note)
    val UNSET: String get() = tr(R.string.settings_not_set)
    val MAIN_ADD_FIRST: String get() = tr(R.string.models_main_add_first)
    val MAIN_PICK: String get() = tr(R.string.models_main_pick)
    val SAME_AS_MAIN: String get() = tr(R.string.models_same_as_main)
    val MAIN_NOT_PICKED: String get() = tr(R.string.models_main_not_picked)
    val OFF: String get() = tr(R.string.models_off)
    val OFF_NOTE: String get() = tr(R.string.models_off_note)
    val VISION_SAME_NOTE: String get() = tr(R.string.models_vision_same_note)
    val LIGHT_SAME_NOTE: String get() = tr(R.string.models_light_same_note)
    val PICK_FROM: String get() = tr(R.string.models_pick_from)
    val FILTER_HINT: String get() = tr(R.string.models_filter_hint)
    val NO_MATCH: String get() = tr(R.string.models_no_match)
    val MODEL_NAME: String get() = tr(R.string.models_model_name)
    val USE: String get() = tr(R.string.models_use)
    val ROLE_EMPTY: String get() = tr(R.string.models_role_empty)
    val PICKER_NOTE: String get() = tr(R.string.models_picker_note)
    val LISTING: String get() = tr(R.string.models_listing)
    val CONNECTION: String get() = tr(R.string.models_connection)
    val STATUS: String get() = tr(R.string.models_status)
    val CONNECTED: String get() = tr(R.string.phone_connected)
    val LIST_FAILED: String get() = tr(R.string.models_list_failed)
    val KIND: String get() = tr(R.string.model_kind)
    val BASE_URL: String get() = tr(R.string.models_url)
    const val API_KEY = "API Key"
    val KEY_SAVED: String get() = tr(R.string.models_key_saved)
    val KEY_NONE: String get() = tr(R.string.models_key_none)
    val CHANGE: String get() = tr(R.string.models_change)
    val CHANGE_KEY: String get() = tr(R.string.models_change_key)
    val NEW_KEY_HINT: String get() = tr(R.string.models_new_key_hint)
    val SAVE: String get() = tr(R.string.save)
    val MODELS: String get() = tr(R.string.models_models)
    val MODELS_NOTE: String get() = tr(R.string.models_models_note)
    val NO_LIST_NOTE: String get() = tr(R.string.models_no_list_note)
    val UNTESTED_NOTE: String get() = tr(R.string.models_untested_note)
    val UNVERIFIED: String get() = tr(R.string.models_unverified)
    val RETEST: String get() = tr(R.string.models_retest)
    val SEES_IMAGES: String get() = tr(R.string.models_sees_images)

    /** 「写真を表示できます」スイッチの下の説明: ユーザーが言ったこと、写真を表示するために使用されたもの、リストに記載されているもの、未確認のもの。 */
    fun seesNote(o: ModelOption, usedForVision: Boolean): String = tr(when {
        o.visionByUser != null -> R.string.models_sees_note_user
        usedForVision -> R.string.models_sees_note_vision
        o.visionKnown -> R.string.models_sees_note_listed
        else -> R.string.models_sees_note_unconfirmed
    })

    fun unverifiedNote(models: List<String>) = tr(R.string.models_unverified_note, models.joinToString(tr(R.string.list_sep)))
    val NOT_LISTED: String get() = tr(R.string.models_not_listed)
    val REFRESH: String get() = tr(R.string.refresh)
    val IN_USE: String get() = tr(R.string.models_in_use)
    val DELETE: String get() = tr(R.string.models_delete)
    val DELETE_CONFIRM: String get() = tr(R.string.confirm_delete)
    val CHATGPT_TITLE: String get() = tr(R.string.chatgpt_use_plan_title)
    val CHATGPT_BODY: String get() = tr(R.string.chatgpt_use_plan_body)
    val CHATGPT_CONTINUE: String get() = tr(R.string.chatgpt_continue)
    val CHATGPT_WAITING: String get() = tr(R.string.chatgpt_waiting)
    val CHATGPT_WELCOME_TITLE: String get() = tr(R.string.chatgpt_welcome_title)
    val CHATGPT_WELCOME_BODY: String get() = tr(R.string.chatgpt_welcome_body)
    val CHATGPT_GOT_IT: String get() = tr(R.string.chatgpt_got_it)
    val CHATGPT_ACCOUNT: String get() = tr(R.string.chatgpt_account)
    val CHATGPT_SIGNED_IN: String get() = tr(R.string.chatgpt_signed_in)
    val CHATGPT_SIGNED_OUT: String get() = tr(R.string.chatgpt_signed_out)
    val CHATGPT_SIGN_OUT: String get() = tr(R.string.chatgpt_sign_out)
    val CHATGPT_MANAGE_USAGE: String get() = tr(R.string.chatgpt_manage_usage)
    val CHATGPT_PLAN_NOTE: String get() = tr(R.string.chatgpt_plan_note)
    val CHATGPT_SIGNED_OUT_NOTE: String get() = tr(R.string.chatgpt_signed_out_note)
    val CHATGPT_REVOKE_UNCONFIRMED: String get() = tr(R.string.chatgpt_revoke_unconfirmed)

    /**
     * ChatGPT を使用したログインが失敗した理由 (インターフェイスとコールバック Web ページは共有されています)。具体的な理由を教えてください。サーバーのみがパッケージと権限について言及しました。 Plus と Pro については、
     * の場合、または付与された権限にパッケージ オプションがない場合にのみ言及してください。そうしないと、アプリケーションが拒否された場合にエラー コード [code] (機密ではありません) が含まれます。
     */
    fun chatGptFailure(reason: SignInFailure, code: String? = null): String = when (reason) {
        SignInFailure.Denied -> tr(R.string.chatgpt_fail_denied)
        SignInFailure.StateMismatch, SignInFailure.ClientMismatch -> tr(R.string.chatgpt_fail_mismatch)
        SignInFailure.NoClientId -> tr(R.string.chatgpt_fail_no_client)
        SignInFailure.TimedOut -> tr(R.string.chatgpt_fail_timed_out)
        SignInFailure.Exchange -> tr(R.string.chatgpt_fail_exchange, code?.takeIf { it.isNotBlank() } ?: "unknown")
        SignInFailure.CodeExpired -> tr(R.string.chatgpt_fail_code_expired)
        SignInFailure.Region -> tr(R.string.chatgpt_fail_region)
        SignInFailure.Blocked -> tr(R.string.chatgpt_fail_blocked)
        SignInFailure.IdToken -> tr(R.string.chatgpt_fail_id_token)
        SignInFailure.NoPlanScope -> tr(R.string.chatgpt_fail_no_plan)
        SignInFailure.Network -> tr(R.string.chatgpt_fail_network)
    }

    // ---------- アカウントでログインします (xAI、OpenRouter; ChatGPT は上記) ----------

    val SIGN_IN_GROUP: String get() = tr(R.string.signin_group)
    val KEY_GROUP: String get() = tr(R.string.signin_key_group)
    val SIGN_IN_RUNNING: String get() = tr(R.string.signin_running)
    val SIGN_IN_USE_KEY: String get() = tr(R.string.signin_use_key)
    val SIGN_IN_BUSY: String get() = tr(R.string.signin_busy)

    /** インターフェース上のサービスプロバイダーの名前: 認可された OpenRouter と、ユーザーがキーを入力するものとは別のソース。 */
    fun providerName(p: Provider): String =
        if (p.signIn == ProviderStore.OPENROUTER_SIGN_IN) tr(R.string.signin_openrouter_name, p.name) else p.name
    val XAI_CODE_TITLE: String get() = tr(R.string.signin_xai_code_title)
    val XAI_CODE_BODY: String get() = tr(R.string.signin_xai_code_body)
    val XAI_CODE_COPY_REOPEN: String get() = tr(R.string.signin_xai_code_copy_reopen)
    val XAI_CODE_WAITING: String get() = tr(R.string.signin_xai_code_waiting)
    val XAI_403_NOTE: String get() = tr(R.string.signin_xai_403_note)
    val SET_MAIN: String get() = tr(R.string.signin_set_main)
    val SET_MAIN_NOTE: String get() = tr(R.string.signin_set_main_note)
    val SIGN_OUT_NOTE: String get() = tr(R.string.signin_sign_out_note)
    /** xAIのアカウントページ。 */
    const val ACCOUNT_MANAGE_URL = "https://accounts.x.ai"

    fun codeValid(minutes: Int) = tr(R.string.signin_xai_code_valid, minutes)

    /** 「アカウントでログイン」は、グループ内の各家の下にある小さな文字の行です。 */
    fun signInRowLine(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> tr(R.string.signin_row_chatgpt)
        SignInKind.Xai -> tr(R.string.signin_row_xai)
        SignInKind.OpenRouter -> tr(R.string.signin_row_openrouter)
    }

    fun signInTitle(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_TITLE
        SignInKind.Xai -> tr(R.string.signin_xai_title)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_title)
    }

    fun signInBody(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_BODY
        SignInKind.Xai -> tr(R.string.signin_xai_body)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_body)
    }

    /** ログイン前に説明が必要なリスク (黄色の下)。そうでない場合は null です。 */
    fun signInRisk(kind: SignInKind): String? = if (kind == SignInKind.Xai) tr(R.string.signin_xai_risk) else null

    /** ログイン前の補足説明 (灰色の背景)。そうでない場合は null。 */
    fun signInInfo(kind: SignInKind): String? = when (kind) {
        SignInKind.ChatGpt -> null
        SignInKind.Xai -> tr(R.string.signin_stored_note)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_note)
    }

    fun signInStart(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_CONTINUE
        SignInKind.Xai -> tr(R.string.signin_continue)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_authorize)
    }

    fun signInWaiting(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_WAITING
        SignInKind.Xai -> tr(R.string.signin_xai_starting)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_waiting)
    }

    fun accountManage(kind: SignInKind) = when (kind) {
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_manage)
        else -> tr(R.string.account_manage_xai)
    }

    /** アカウントのログイン詳細の下にある手順: 使用している内容とログアウト後の動作。 */
    fun accountNote(kind: SignInKind, signedIn: Boolean) = when (kind) {
        SignInKind.OpenRouter -> tr(if (signedIn) R.string.signin_openrouter_signed_in_note else R.string.signin_openrouter_signed_out_note)
        else -> tr(if (signedIn) R.string.account_signed_in_note_xai else R.string.account_signed_out_note)
    }

    fun accountFailure(e: AccountSignInException): String = when (e.reason) {
        AccountSignInFailure.Denied -> tr(R.string.account_fail_denied)
        AccountSignInFailure.Expired -> tr(R.string.account_fail_expired)
        AccountSignInFailure.TimedOut -> tr(R.string.account_fail_timed_out)
        AccountSignInFailure.Rejected -> tr(R.string.account_fail_rejected, e.code ?: "unknown")
        AccountSignInFailure.NoSubscription -> tr(R.string.account_fail_no_sub)
        AccountSignInFailure.Network -> tr(R.string.account_fail_network)
        AccountSignInFailure.BadIssuer -> tr(R.string.account_fail_issuer)
    }

    /** 利用カードの一行：[title]はモデル名（[isModel]）、「メインモデルを使用」「未使用」または「未設定」（[unset]）、[subtitle]は補足説明です。 */
    data class RoleLine(
        val title: String, val subtitle: String, val isModel: Boolean = false, val unset: Boolean = false,
        /** 字幕には緑色で「利用可能」が使用されています (音声からテキストへのテキストには「オフラインで利用可能」と表示されます)。 */
        val subtitleOk: Boolean = false,
    )

    fun roleName(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main)
        ModelRole.Vision -> tr(R.string.role_vision)
        ModelRole.Light -> tr(R.string.role_light)
    }

    /** これは何に使用されますか? */
    fun roleWhat(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main_what)
        ModelRole.Vision -> tr(R.string.role_vision_what)
        ModelRole.Light -> tr(R.string.role_light_what)
    }

    fun roleLabel(role: ModelRole) = "${roleName(role)} · ${roleWhat(role)}"

    /** ページの上部にある手順を選択します。 */
    fun roleHint(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main_hint)
        ModelRole.Vision -> tr(R.string.role_vision_hint)
        ModelRole.Light -> tr(R.string.role_light_hint)
    }

    /** 「音声テキスト変換」の 4 番目の使用法は、[ModelRole] にはありません (対話モデルではありません)。名前、アイコン、カードの右側の 2 本の線がここにあります。 */
    val SPEECH_LABEL: String get() = "${tr(R.string.role_speech)} · ${tr(R.string.role_speech_what)}"
    val SPEECH_ICON: ImageVector get() = MikaIcons.Mic

    fun speechLine(ui: ModelSetupUi): RoleLine {
        val s = SpeechOptions.summary(ui.speech, ui.systemSpeech, ui.providers)
        val unset = s.title == UNSET
        return RoleLine(s.title, s.sub.orEmpty(), isModel = !unset && (ui.speech is SpeechChoice.Provider || ui.speech is SpeechChoice.Doubao), unset = unset, subtitleOk = s.subOk)
    }

    fun roleIcon(role: ModelRole): ImageVector = when (role) {
        ModelRole.Main -> MikaIcons.Spark
        ModelRole.Vision -> MikaIcons.Eye
        ModelRole.Light -> MikaIcons.Leaf
    }

    /** 目的カードに記入する内容: モデルを選択した場合は、モデル名とサービスプロバイダーを記入します。 「メインモデルを使用する」とメインモデルが誰であるかを書きます。書くことは「やめて」、テキストに頼って画面を読むだけです。 */
    fun roleLine(ui: ModelSetupUi, role: ModelRole): RoleLine {
        fun modelLine(cfg: ProviderConfig) = RoleLine(cfg.model, ui.providerOf(cfg)?.let(::providerName) ?: cfg.name, isModel = true)
        val main = ui.configFor(ModelRole.Main)
        return when (val choice = ui.roles[role]) {
            null -> RoleLine(UNSET, if (ui.providers.isEmpty()) MAIN_ADD_FIRST else MAIN_PICK, unset = true)
            RoleChoice.SameAsMain -> RoleLine(SAME_AS_MAIN, main?.model ?: MAIN_NOT_PICKED)
            RoleChoice.Off -> RoleLine(OFF, OFF_NOTE)
            is RoleChoice.Model -> ui.configs.firstOrNull { it.id == choice.configId }?.let(::modelLine)
                ?: RoleLine(UNSET, MAIN_PICK, unset = true)
        }
    }

    fun choiceTitle(choice: RoleChoice) = if (choice == RoleChoice.Off) OFF else SAME_AS_MAIN

    fun choiceNote(role: ModelRole, choice: RoleChoice) = when {
        choice == RoleChoice.Off -> OFF_NOTE
        role == ModelRole.Vision -> VISION_SAME_NOTE
        else -> LIGHT_SAME_NOTE
    }

    fun roleNames(roles: List<ModelRole>) = roles.joinToString(tr(R.string.list_sep)) { roleName(it) }

    fun deleteTitle(name: String) = tr(R.string.models_delete_title, name)

    /** 削除する前に、どの用途に影響するかを説明してください。 */
    fun deleteBody(roles: List<ModelRole>) =
        tr(R.string.models_delete_body) + if (roles.isEmpty()) tr(R.string.models_delete_unused) else tr(R.string.models_delete_used, roleNames(roles))

    fun more(n: Int) = trPlural(R.plurals.models_more, n)

    fun viewAll(n: Int) = trPlural(R.plurals.models_view_all, n)

    fun allModelsTitle(name: String) = tr(R.string.models_all_title, name)

    fun allModelsNote(n: Int) = trPlural(R.plurals.models_all_note, n)

    fun useAsSubtitle(name: String) = tr(R.string.models_use_as, name)

    fun kindLabel(kind: ProviderConfig.Kind) = when (kind) {
        ProviderConfig.Kind.OpenAiCompat -> tr(R.string.models_openai_compat)
        ProviderConfig.Kind.Anthropic -> "Anthropic"
        ProviderConfig.Kind.Gemini -> "Gemini"
        ProviderConfig.Kind.OpenAiResponses -> "OpenAI Responses"
        ProviderConfig.Kind.ChatGpt -> "ChatGPT"
        ProviderConfig.Kind.Xai -> "xAI Responses"
    }

    fun shortUrl(url: String) = url.removePrefix("https://").removePrefix("http://")

    /** 記憶されているモデルの数。まだリストされていない場合は空になり、サービスがリストを提供しない場合は記載されます。 */
    fun modelCount(p: Provider): String? = when {
        p.models == null -> null
        p.models.isEmpty() -> tr(R.string.models_no_list)
        else -> trPlural(R.plurals.models_count, p.models.count { it.usable })
    }

    /** サービス プロバイダー回線の説明: 接続済み · N モデル · どの目的で使用されているか。リフレッシュに失敗した場合の説明。 */
    fun providerLine(p: Provider, state: ListState?, roles: List<ModelRole>): String =
        if (state is ListState.Failed) LIST_FAILED
        else listOfNotNull(CONNECTED, modelCount(p), roles.takeIf { it.isNotEmpty() }?.let { tr(R.string.models_used_by, roleNames(it)) }).joinToString(" · ")
}
