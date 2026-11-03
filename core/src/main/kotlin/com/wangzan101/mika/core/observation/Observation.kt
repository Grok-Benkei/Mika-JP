package com.wangzan101.mika.core.observation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 観測値はどのカテゴリに属しますか: [Read] 読み取られたインターフェイスまたはページ (アプリのインターフェイスのステータス、Web ページのテキスト、Web ページのスクリプト、およびネットワーク応答)。 [Output] コマンドとスクリプト、ファイルの内容とパスの出力。
 * [Reply] 他の AI (サブタスク、外部サービス) からの回答。 [UserSaid] ユーザーによって提供されます (質問への回答、記憶)。 [Attachment] ユーザーまたはツールによって提供されたファイルと写真。
 * [Fact] ツール結果の事実 (どのアプリが開かれたか、URL とタイトル)。コアはこれらのカテゴリーに従ってのみ扱われ、特定の能力は認められません。
 */
enum class ObsKind(val wire: String) {
    Read("read"), Output("output"), Reply("reply"), UserSaid("user_said"), Attachment("attachment"), Fact("fact");

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun ofWire(w: String?): ObsKind? = w?.let(byWire::get)
    }
}

/** 観測情報のソースはどの程度まで信頼できるか。 [Untrusted]: Web ページ、プラットフォーム、その他の AI 応答上の単語は、指示ではなくデータとしてのみ扱われる必要があります。 */
enum class Trust { Trusted, Untrusted }

/** 場所とは: アプリ、サイト、Web ページ、コマンド、ファイル。 */
@Serializable
enum class PlaceKind(val wire: String) {
    @SerialName("app") App("app"),
    @SerialName("site") Site("site"),
    @SerialName("page") Page("page"),
    @SerialName("command") Command("command"),
    @SerialName("file") File("file"),
}

/**
 * の場所: アプリ (パッケージ名)、サイト (ホスト名)、Web ページ (Web アドレス)、コマンド、ファイル。 [name] はこの場所のオリジナルの文章です。検証とソースを確認するには、上記の回答に記載されているアプリまたは URL をクリックしてください。
 */
@Serializable
data class Place(val kind: PlaceKind, val name: String) {
    companion object {
        fun app(pkg: String) = Place(PlaceKind.App, pkg)
        fun site(host: String) = Place(PlaceKind.Site, host)
        fun page(url: String) = Place(PlaceKind.Page, url)
        fun command(label: String) = Place(PlaceKind.Command, label)
        fun file(label: String) = Place(PlaceKind.File, label)
    }
}

/** 一度読み取ると、インターフェイス上の入力ボックス内の単語 ([names]、空白を削除した後) と入力フォーカスのある単語 ([focused])。チェックする際、アシスタントが入力した単語はこれに基づいて区別されます。 */
data class InputHints(val names: List<String>, val focused: String? = null)

/**
 * 原点を伴う観測値: コア消費の唯一の単位。機能はツールの結果 ([com.wangzan101.mika.core.tool.ToolExecution.observations]) に示されています。
 * 証拠台帳に基づいて記録され、検証、回答元、操作記録、評価がすべて読み込まれます。
 *
 * - [source] は、その機能 (電話、Web、サンドボックス...; コアはコア) を与える ID です。ここで、[place] が読み取られます。 [title] はそのページのタイトルです。
 * ～ [media] は、同時に保存される画面番号です (空でない場合は視覚化モジュールによって与えられます)。
 * - [trust]: 信頼できないもの (Web ページ、その他の AI 回答) はデータとしてのみ扱われます。 [authored] はアシスタント (コマンド実行) によって書き込まれたものです。[text] に表示される行はエコーされ、読み取られません。
 * - [inputs]: インターフェイスを読み取る際の入力ボックス内の単語 ([InputHints] を参照)。不明な場合は null。
 * - [id]、[step]、[atMs] 台帳から入力する場合、このタスクのどの項目 (1 から開始)、どのステップ、いつを記入します。作成時に能力を記入する必要はありません。
 */
data class Observation(
    val kind: ObsKind,
    val text: String,
    val source: String? = null,
    val place: Place? = null,
    val title: String? = null,
    val media: List<Int> = emptyList(),
    val trust: Trust = Trust.Trusted,
    val authored: String? = null,
    val inputs: InputHints? = null,
    val step: Int = 0,
    val atMs: Long = 0,
    val id: Int = 0,
) {
    /** はそのまま書かれています。 nullではありません。 */
    val where: String? get() = place?.name

    /** は、それがどのようなものであるかによって分類されます (切削条件と界面条件は工具ではなくそれによって決まります)。 */
    val form: ObsForm get() = when (kind) {
        // インターフェイスのステータスを読み取るときに、常に入力ボックスがあります (おそらく何もありません)。ページ全体、スクリプトの結果、ネットワーク応答を読み取るときに入力ボックスはありません。
        ObsKind.Read -> if (inputs != null) ObsForm.Interface else ObsForm.Page
        ObsKind.Output -> if (place?.kind == PlaceKind.File) ObsForm.File else ObsForm.Command
        ObsKind.Reply -> ObsForm.Reply
        ObsKind.UserSaid -> ObsForm.UserSaid
        ObsKind.Attachment -> ObsForm.Image
        ObsKind.Fact -> ObsForm.Fact
    }
}

/** ツールで読み取られた観察結果: 元帳内の番号とその形状 (クリッパーは、それを短縮する方法と、短縮後にどの項目を指すかを決定します)。 */
@Serializable data class ReadTag(val obsId: Int, val form: ObsForm)

/** ツール結果におけるこの観察のマーク。 */
val Observation.tag: ReadTag get() = ReadTag(id, form)

/** 観察形式: インターフェイスのステータス、ページのコンテンツ、コマンド出力、ファイル、画像、応答、ユーザーのステートメント、事実。 */
@Serializable enum class ObsForm { Interface, Page, Command, File, Image, Reply, UserSaid, Fact }

/** コア自体によって与えられる観測のソース (どの能力ではない)。 */
const val CORE_SOURCE = "core"

/**
 * 検証の前に「もう一度見てください」: 現時点での機能 ([observations]、インターフェイスのステータス、Web ページのテキストの先頭など) と、同時に表示される画面 ([image]、接続されている場合のみ利用可能)。
 * [protected]: 現時点では、ページはシステムのスクリーンショット防止保護によって保護されており、画面は表示されません。見た目は機能によって定義されます (Capability.freshLook を参照)。
 */
class Look(val observations: List<Observation>, val image: ByteArray? = null, val protected: Boolean = false)
