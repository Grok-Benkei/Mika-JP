package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.risk.IrrevAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** エージェントへのツールのサイクルを終了/中断するための信号。通常はツールなしで完了します。電話をかけずに応答することがユーザーに与えられる答えです。 */
sealed interface Outcome {
    data class Failure(val message: String, val subtype: String = "interaction_failed", val recoverable: Boolean = false) : Outcome
    /** お支払いの場合、お支払いの確認となる引継ぎカードに [item]/[amount]/[address] が別途記載されます。 */
    data class Takeover(
        val reason: String, val message: String,
        val item: String? = null, val amount: String? = null, val address: String? = null,
    ) : Outcome
    data class Input(val prompt: String, val options: List<String>? = null) : Outcome
    /** は、モデル宣言の不可逆的な操作です。 [foregroundApp] は宣言時に読み取られるフロントエンド パッケージ名で、読み取られない場合は null になります。 */
    data class Confirm(val request: ConfirmationRequest, val foregroundApp: String?) : Outcome
}

/**
 * [changed]: ポートによって検証されたアクションの前後でインターフェイスが変更されたかどうか。検証されていない場合は null です。
 * [frame]: 走行記録の画面読み取り専用 (モデルには送信されません)。 [image] が空でない場合に使用します。
 * [images]: 結果とともにモデルに送信された画像 (過去のスクリーンショットを振り返ると、複数の画像が存在する可能性があります)。その中の最初の写真が[image]です。
 * [record]: 実行記録のみを入力し、フィールド (安定した番号に対応するポート番号など) をモデルに送信しません。
 * [screenElements]: 現時点での完全な要素リストを含む書き込み操作の結果 (差分のみが送信されました) を示す画面。スタック防止ガードがそれを押して画面を比較します。
 * [imageIds]: [images] に 1 対 1 で対応する画面番号 (インターフェース画面のみ、読み込んだ画像ファイルは null、足りない場合は null を押してください)。
 * [observations]: この呼び出しで読み取られる内容 (能力が指定されています。[Observation] を参照)。流通業者はそれを証拠台帳に記録し、台帳の項目（番号付き）と置き換えます。
 * [place]: この呼び出し後のタスクはどこにありますか (機能側の結果によって与えられます)。指定されていない場合は、null (前のものが使用されます)。
 * [waitMs]: この呼び出しでは、リズムのために能力が意図的に停止される時間 (ページが十分に読み取られる前にページが変更されるときに作られる一時停止)。実行記録ではこれを待機中としてカウントします。
 */
class ToolExecution(
    val content: JsonObject,
    image: ByteArray? = null,
    val changed: Boolean? = null,
    val outcome: Outcome? = null,
    val frame: ByteArray? = null,
    val images: List<ByteArray> = listOfNotNull(image),
    val record: JsonObject = JsonObject(emptyMap()),
    val screenElements: JsonArray? = null,
    val imageIds: List<Int?> = emptyList(),
    val observations: List<Observation> = emptyList(),
    val place: Place? = null,
    val waitMs: Long = 0,
) {
    val image: ByteArray? get() = images.firstOrNull()

    /** それらのいくつかを置き換えますが、その他 (観察された観測値を含む) は同じままです。 */
    fun with(
        content: JsonObject = this.content, changed: Boolean? = this.changed, frame: ByteArray? = this.frame, images: List<ByteArray> = this.images,
        record: JsonObject = this.record, screenElements: JsonArray? = this.screenElements, imageIds: List<Int?> = this.imageIds,
        observations: List<Observation> = this.observations, place: Place? = this.place, waitMs: Long = this.waitMs,
    ) = ToolExecution(content, changed = changed, outcome = outcome, frame = frame, images = images, record = record,
        screenElements = screenElements, imageIds = imageIds, observations = observations, place = place, waitMs = waitMs)

    /** [more] にアクセスしました。 */
    fun observing(vararg more: Observation?): ToolExecution = more.filterNotNull().let { if (it.isEmpty()) this else with(observations = observations + it) }
}

/** Android パッケージ名: 各段落はドットで区切られ、最初の段落は文字で始まります。 launch_app、宣言の確認、ジャンプ アプリのリンク共有。 */
val PACKAGE_NAME_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

/** レビューでは、最後の数回の画面読み取り値が保持されます。一度に確認できる写真はせいぜい数枚です。 */
internal const val RECALL_KEEP = 20
internal const val RECALL_MAX_IDS = 5

val FAILURE_SUBTYPES = listOf("blocked", "login_incomplete", "captcha_incomplete", "route_failed", "device_unavailable", "interaction_failed")
/** request_confirmation のターゲット/コンテンツはこの長さに切り詰められます (モデルに記述されているため、インターフェイスはそのまま表示される必要があります)。 */
const val CONFIRM_FIELD_MAX = 200
/** request_confirmation アクションが受け入れられました。支払いはユーザーの手元に届きます。 */
val CONFIRM_ACTIONS = IrrevAction.entries.filter { it != IrrevAction.pay }.map { it.name }
val BARRIER_KINDS = listOf("popup", "login", "captcha", "access_blocked", "payment")
val TAKEOVER_REASONS = listOf("captcha", "login", "access_blocked", "payment", "decision", "other")
/** 一般的な引継ぎ事由やスキル書類の名義変更前の事由は上記の事由に置き換えることが可能です。インポートしたドキュメントと保存したスキルを変更する必要はありません。 */
val TAKEOVER_REASON_ALIASES = mapOf("password_required" to "login", "2fa" to "login", "manual_review" to "other", "risk_control" to "access_blocked")

/** 接続のローカルは、ユーザーがすべてのジョブを取り消したため、中止されました。 */
enum class HandBackOutcome { complete, skipped, timeout, aborted }

/**
 * 引き継ぎの結果は、hand_to_user のツール結果としてモデルに与えられます。
 * [userFinished]: ユーザーは自分で完了したと言いました。 [durationMs] ユーザーが使える時間。 [inputCount] ユーザーがアプリで実行した操作の数。
 * [stoppedAt] 返されたときのアプリのページ。不明はnullです。
 */
data class HandBack(
    val outcome: HandBackOutcome, val userFinished: Boolean = false,
    val durationMs: Long? = null, val inputCount: Int? = null, val stoppedAt: String? = null,
    /** ユーザーが返却時に書いたメモ（思い出の材料となるユーザーオリジナルの言葉）。 */
    val note: String? = null,
)


