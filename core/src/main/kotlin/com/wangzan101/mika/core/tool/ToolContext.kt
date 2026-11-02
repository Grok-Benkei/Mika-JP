package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.memory.Change
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.memory.ProfileStore
import com.wangzan101.mika.core.memory.UserUtterances
import com.wangzan101.mika.core.vision.ImageOps
import com.wangzan101.mika.core.vision.VisionService
import com.wangzan101.mika.core.visual.Shots
import com.wangzan101.mika.core.capability.CapabilityKey
import com.wangzan101.mika.core.knowledge.BundledScripts
import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.ScheduleAdmin
import com.wangzan101.mika.core.child.ChildRunHost
import com.wangzan101.mika.core.run.RunSpec

/** このタスクの構成 (モデル、スキル、メモリ、スイッチ)。アビリティのそれぞれのポートとスイッチはアビリティにあります。 */
internal class ToolConfig(
    val visionEnabled: Boolean,
    val skills: GuideCatalog?,
    val recordFrames: Boolean,
    val vision: VisionService?,
    val inlineScreen: Boolean,
    /** 要素の優先順位: 読み取り可能なページはスクリーンショットをメイン モデルに送信せず、読み取りできない場合は画像を自動的に追加します。 */
    val adaptiveVision: Boolean,
    val memory: MemoryStore?,
    val profile: ProfileStore?,
    val utterances: UserUtterances?,
    val conversationId: String?,
    val imageOps: ImageOps?,
    /** は、スケジュールされたタスクを保存し、それが時間通りに実行できるかどうかを示します ([Scheduled] を参照)。 */
    val scheduleTrigger: ((TriggerDefinition) -> Scheduled)? = null,
    val scheduleAdmin: ScheduleAdmin? = null,
    val runSpec: RunSpec? = null,
    val childHost: ChildRunHost? = null,
    /** この会話でユーザーによって送信された画面。 view_user_screen がない場合、view_user_screen は提供されません。 */
    val userScreens: com.wangzan101.mika.core.userscreen.UserScreenSource? = null,
    /** ユーザーの個人的なスキル。 skill_save は、それが与えられた後にのみ提供されます (スキル カタログと今回ユーザーが言ったことも存在します)。 */
    val personalSkills: com.wangzan101.mika.core.skills.PersonalSkills? = null,
    /** は、Mika によって設定されたホワイトリスト エントリを変更します。空の場合 (ユーザーが許可していない場合)、settings_list と settings_set は提供されません。 */
    val selfConfig: com.wangzan101.mika.core.settings.SelfConfig? = null,
    /** 保存されたランニング記録。 History_read は、この会話が以前に実行中のラウンドを記録した場合に提供されます。 */
    val runRecords: com.wangzan101.mika.core.record.RunRecords? = null,
)

/**
 * ツールの実行時に共有されるもの: 構成、検証マテリアル、機能によって入力されるもの ([get])、およびツール間の少量のステータス (拒否された支払い申告、許可されたリンクなど)。
 * ツール自体には状態がなく、すべてがここから取得されます。インターフェース機能による画面読み取りステータスも入れてインターフェースステータス側からアクセスします。
 */
internal class ToolContext(
    val config: ToolConfig,
    val evidence: EvidenceLog,
    /** このタスクによって提供されるすべてのツールのステートメント (ページ スクリプトはアクションによってツールを検索します)。 */
    val tools: ToolIndex,
    /** スキル スクリプトを実行する機能。そうでない場合は null。 */
    val scripts: BundledScripts?,
    val shots: Shots,
) {
    private val parts = HashMap<CapabilityKey<*>, Any>()

    /** ミッションの開始時に機能が投入されるもの。入れない場合はnull。 */
    @Suppress("UNCHECKED_CAST")
    operator fun <T : Any> get(key: CapabilityKey<T>): T? = parts[key] as T?

    operator fun <T : Any> set(key: CapabilityKey<T>, value: T) { parts[key] = value }

    /** 拒否された最新の支払い申告。代金引き継ぎの際は、モデルに記載されていない商品と金額を補填してください。 */
    var refusedPay: ConfirmationRequest? = null
    /** このタスクでアプリは正常に開かれましたか?そうでない場合、チェッカーは比較する画面が存在しないことを認識します。 */
    var appOpened = false
    /** この通話の番号。ページ スクリプト内のアクションには、この番号に従って番号が付けられます。 */
    var callId = ""
    /** インターフェイスを移動しないツール (ページ スクリプト) 内のアクションは、インターフェイスを介して実行入口に返されます。読み取り専用の呼び出しまたはテストで指定されていない場合、その中のアクションは実行されません。 */
    var sub: SubActions? = null
    /** このコールが存在するラウンド (サイクルの「N 番目のステップ」)。 モデルへのリンクは、 */
    var currentStep: Int
        get() = shots.step
        set(v) { shots.step = v }

    /** 参考資料に記載されています。アクションを行わない open_link はこれらのみを開くことができます (uri とパッケージ名は一貫している必要があります)。 */
    val allowedLinks = mutableSetOf<Pair<String, String?>>()

    private val saved = mutableListOf<Change>()
    val savedChanges: List<Change> get() = synchronized(saved) { saved.toList() }
    fun noteSaved(c: Change) = synchronized(saved) { saved += c }

    /** 今回というツールは販売元から実際の効果（効果）を「変化なし」と報告します。効果に関しては、これ以上のプロンプトはありません。 */
    var reportsEffect = false

    /** この呼び出しの能力は、リズムが意図的に停止される時間です ([ToolExecution.waitMs] を参照)。停止すると能力が増加し、コールごとにディストリビュータがクリアされた後に結果が渡されます。 */
    var pacedWaitMs = 0L

    /** は、この通話中に数回ページをわずかにスクロールしました ([com.wangzan101.mika.core.uistate.IdleScroll] を参照)。 [pacedWaitMs] のように、呼び出すたびにクリアされ、走行記録のみが入力されます。 */
    var idleScrolls = 0
}

/** モデルに記述された宣言フィールドは、インターフェイス上でそのまま表示できるように切り詰められています。 */
internal fun capField(s: String): String = s.trim().let { if (it.length <= CONFIRM_FIELD_MAX) it else it.take(CONFIRM_FIELD_MAX - 1) + "…" }
