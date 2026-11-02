package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.capability.Capability
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.capability.CapabilityStatus
import com.wangzan101.mika.core.capability.Paced
import com.wangzan101.mika.core.uistate.DwellStop
import com.wangzan101.mika.core.uistate.IdleScroll
import com.wangzan101.mika.core.uistate.PlacePace
import com.wangzan101.mika.core.uistate.dwellStopped
import com.wangzan101.mika.core.capability.PromptEnv
import com.wangzan101.mika.core.uistate.Surface
import com.wangzan101.mika.core.observation.Look
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.tool.AgentTool
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.PACKAGE_NAME_RE
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.uistate.EffectKind
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.sessions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 携帯電話: 携帯電話上で開くアプリとリンクの画面ツールのセット。
 * [pacing] は、アプリの段階的な操作のリズムです ([AppPacing] を参照)。画面のアクションを変更する前の一時停止を補い、ブラウジング速度を制限するアプリの画面の数を制限します。
 * [appPace] は、パッケージ名に応じたブラウジング速度に関する情報をアプリに提供します。 [idle] は Shi Xin に画面上で一時停止を与え、ゆっくりと数回スクロールします ([IdleScroll] を参照)。
 */
internal class PhoneCapability(
    val screen: ScreenPort,
    /** スピードアップ スイッチ deepLinks: リンクを開くためのツールを提供します。 */
    val deepLinks: Boolean,
    pacing: AppPacing = AppPacing.Off,
    appPace: (String) -> PlacePace = { PlacePace.NORMAL },
    idle: IdleScroll? = null,
) : Capability {
    val dwell = AppDwell(pacing, appPace, idle)

    override val id = PHONE
    private val tools = PhoneGroup.tools

    override val folds = true

    override fun status(): CapabilityStatus = CapabilityStatus.Available

    override fun tools(): List<AgentTool> = tools

    override val family = PHONE_FAMILY

    override val surface = Surface(PHONE_FAMILY, screen, declaredTarget = { app ->
        if (!PACKAGE_NAME_RE.matches(app)) throw ArgError("app", "must be an Android package name")
        app
    }, targetNote = "the exact package name of the app")

    /**
     * 携帯電話グループは、アプリを開いて一覧表示するためのツールに従います。携帯電話グループは保存時に指定されません。
     * アプリケーション アクセスのルールは、ユーザーがアプリを無効にしている (または設定を読み取れない) 場合にのみ適用されます。リストが空の場合、それを理由にモバイル ツールが拒否されることはなく、モデルにはこの段落は必要ありません。
     */
    override fun sections(env: PromptEnv): List<String> {
        if (!env.isOpen(id) || tools.none { it.verb in SECTION_VERBS && it.name in env.offered }) return emptyList()
        val access = screen.appAccess.current()
        return listOfNotNull(
            env.gate(Prompts.capability(PHONE)),
            Prompts.capability(APP_ACCESS).takeIf { !access.available || access.blocked.isNotEmpty() },
        )
    }

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
        dwell.onIdleScroll = { ctx.sessions[id]?.movedByMika() }
    }

    /** ユーザーの回答: 閲覧速度を制限するアプリの画面数は再計算されます (ユーザーは同意して閲覧を続けました)。 */
    override fun onUserAnswered(ctx: ToolContext) = dwell.userAnswered()

    /** アプリの外部アクションが完了すると、アプリは画面数の上限を設定しなくなります (バッチ連絡と配信はアプリ自体の上限に達します)。 */
    override fun onOutwardEnd(ctx: ToolContext, site: String, done: Boolean) = dwell.outwardEnd(site, done)

    /**
     * 画面を切り替える前に、アプリのリズムに従って停止します。現在どのアプリを使用しているか、この画面の単語数は、モデルが最近読み取った画面に基づいて計算されます。
     * ブラウジング速度を制限するアプリでは、画面数が上限に達すると、画面変更アクション (クリック、スライド、リターン、キー押下、ジェスチャー) が実行されなくなり、page_limit が報告されます。タイピング、クリア、スクロール、
     * は、スクロール ホイールを回したり、他のアプリを開いたり、外部アクションを行ったりしてもブロックされません。画面上の位置に応じて次の動作が行われない場合（戻る、ボタンを押す、方向に合わせてスクロールする）、滞在中に数回ロールすることができます。
     */
    override suspend fun beforeWrite(ctx: ToolContext, verb: Verb?, outward: Boolean): Paced {
        if (!dwell.paced) return Paced.NONE
        val shown = ctx.sessions[id]?.lastSnapshot
        return try {
            val d = dwell.before(shown?.packageName, shown?.let(::textChars) ?: 0, gate = !outward && verb in BROWSES,
                port = screen.takeIf { verb in IDLE_BEFORE })
            Paced(d.waitMs, d.idleScrolls)
        } catch (e: DwellStop) {
            Paced(stop = dwellStopped(e))
        }
    }

    /** アクションが完了しました: インターフェイスが大幅に変更されたか、アプリが新しい画面に変更されました (同じページの残りのコンテンツを読むために下にスクロールしても、入力された画面の数としてカウントされません)。 */
    override fun afterWrite(ctx: ToolContext, verb: Verb?, result: JsonObject) {
        if (!dwell.paced) return
        val effect = result["effect"] as? JsonObject
        val kind = EffectKind.of((effect?.get("kind") as? JsonPrimitive)?.contentOrNull)
        val newScreen = kind == EffectKind.Done || kind == EffectKind.PlaceChanged
        val app = (effect?.get("to") as? JsonPrimitive)?.contentOrNull ?: ctx.sessions[id]?.lastSnapshot?.packageName
        dwell.after(app, newScreen, counts = verb != Verb.Scroll)
    }

    /** もう一度見てください。現時点での電話機のアプリのインターフェイスのステータスです。 */
    override suspend fun freshLook(ctx: ToolContext, withImage: Boolean): Look? = ctx.sessions[id]?.freshLook(withImage)

    /** システム コマンドが数回連続して失敗しました。一度無事クリアされました。 */
    var commandFailures = 0

    /** このタスクは、システム コマンドに特別なツールに戻すよう通知しました。後でそのような種類の処理を実行するコマンドは実行されません。 */
    var commandSteered = false

    companion object {
        val KEY = CapabilityKey<PhoneCapability>(PHONE)
        private val SECTION_VERBS = setOf(Verb.LaunchApp, Verb.ListApps)

        /** アプリケーション アクセス ルールのセクション (capabilities/app-access.md)。 */
        private const val APP_ACCESS = "app-access"

        /** アプリ内の画面を変更するアクション: アプリ内の画面の最大数は、閲覧速度によって制限されます。 */
        private val BROWSES = setOf(Verb.Tap, Verb.Swipe, Verb.Back, Verb.PressKey, Verb.PhoneGesture)

        /** 画面上の位置に従わないアクション: 前回の停止中に数回ローリングしても、位置はずれません。 */
        private val IDLE_BEFORE = setOf(Verb.Back, Verb.PressKey, Verb.Scroll)

        /** 1 画面に表示される単語の数 (入力ボックスとプロンプト内の単語はカウントされません)。 */
        internal fun textChars(s: ScreenSnapshot): Int =
            s.elements.filter { !it.editable && !it.hint }.sumOf { it.name.codePointCount(0, it.name.length) }

        /** 携帯電話のセットが提供するすべてのツール。 */
        fun tools(): List<AgentTool> = PhoneGroup.tools
    }
}

/** このタスクのための携帯電話の機能。 */
internal val ToolContext.phone: PhoneCapability get() = this[PhoneCapability.KEY]!!
