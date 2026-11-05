package com.wangzan101.mika.core.gate

import com.wangzan101.mika.core.uistate.CdpKeys
import com.wangzan101.mika.core.uistate.KeyCombo
import com.wangzan101.mika.core.uistate.KeyModifier
import com.wangzan101.mika.core.uistate.ScreenDiagnosis
import com.wangzan101.mika.core.uistate.ElementContext
import com.wangzan101.mika.core.uistate.Hant
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.risk.GateTarget
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.uistate.PressKeys
import com.wangzan101.mika.core.tool.LOCATED_REF
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** [matched]: ヒットしたテキスト (ボタン名、リソース ID、ターゲット パラメーター、入力ボックス、またはスライドの開始点)。 */
data class Irreversible(val action: IrrevAction, val matched: String)

/**
 * 書き込み操作を実行する前の単語リスト: ボタン名、入力/確認キー、および水平スライド リスト ライン信号のみを認識し、意味的な理解はありません。
 * よくわからない場合は、さらにブロックすることをお勧めします (モデルは request_confirmation を一度入力した後に続行できます)。意図的にのみ提出することは、完全な平等を認識するだけです。
 *
 * 画面読み取り: [classify] のスナップショットは ref が属する画面読み取り (ref がない場合は最新のもの)、Fresh は現時点で再読み取りされている画面です。
 * 座標、Enter キーを押し、スライドして新規キーを押して判断します (読み取れない場合のみスナップショットに戻ります)。審判は両方のコピーを判断し、どちらかがカウントされます。 名前 (短縮名、または綴り名のすべての段落) に
 */
object IrreversibleDetector {
    /**
     * が含まれている場合でも、ただし、その前に「ワイ」、「すでに」が付いています。
     * に続く [STATUS_SUFFIXES] は注文ステータスまたはエントリ (支払い保留中、支払い成功、購入記録) であり、カウントされません。
     */
    val STRONG: Map<String, IrrevAction> = linkedMapOf(
        "支払い" to IrrevAction.pay, "振込" to IrrevAction.pay, "振り込" to IrrevAction.pay, "送金" to IrrevAction.pay,
        "送信取消" to IrrevAction.delete, "削除" to IrrevAction.delete, "退会" to IrrevAction.delete,
        "送信" to IrrevAction.send,
        "購入" to IrrevAction.order, "注文を確定" to IrrevAction.order, "注文確定" to IrrevAction.order,
        "注文する" to IrrevAction.order, "注文をキャンセル" to IrrevAction.order, "予約を確定" to IrrevAction.order,
        "支付" to IrrevAction.pay, "付款" to IrrevAction.pay, "转账" to IrrevAction.pay,
        "删除" to IrrevAction.delete, "撤回" to IrrevAction.delete,
        "下单" to IrrevAction.order, "购买" to IrrevAction.order, "结算" to IrrevAction.order, "结账" to IrrevAction.order,
        "取消订单" to IrrevAction.order, "确认收货" to IrrevAction.order, "退款" to IrrevAction.order,
        "转发" to IrrevAction.send,
    ).mapKeys { (word, _) -> Hant.fold(word) }
    private const val STRONG_MAX_LEN = 12
    private val STATUS_PREFIXES = listOf('待', '已')
    val STATUS_SUFFIXES = listOf("成功", "记录", "售后", "明细", "方式", "列表", "中心", "入口", "码", "履歴", "済み", "完了", "方法", "一覧", "情報", "設定", "手続きの説明").map(Hant::fold)

    /** これらの支払い単語はボタン自体のみを認識するか、「即時」、「確認」、「実行」が前に付いています (「リチャージ センター」、「電話リチャージ」、「割引支払い」は入り口であり、カウントされません)。 */
    val EXACT_PAY = listOf("收银台", "买单", "充值", "储值", "提现", "转出", "打赏", "缴费", "捐赠", "捐款", "寄付する", "寄付を確定", "支払う", "支払いを確定", "決済する", "決済を確定", "送金する", "振り込む", "振込実行", "チャージする", "チャージを確定", "出金する").map(Hant::fold)
    private val EXACT_PAY_PREFIXES = listOf("", "立即", "确认", "去")

    /** 赤い封筒はボタン上の単語のみを認識します (「WeChat 赤い封筒」はチャット内のバブルです)。 */
    val RED_PACKET = listOf("红包", "发红包", "塞钱进红包")

    /** 中国語: word に等しい、または word で始まり、さらにいくつかの単語のみが含まれます (「送信 (1)」など)。 */
    val PREFIX_WORDS: Map<IrrevAction, List<String>> = linkedMapOf(
        IrrevAction.send to listOf("发送", "传送", "发布", "发表", "送信", "投稿", "ポスト", "公開", "返信する", "リポスト"),
        IrrevAction.delete to listOf("清空", "移除", "注销", "退出群聊", "削除", "すべて削除", "アカウントを削除"),
        IrrevAction.order to listOf("提交订单", "注文を確定", "購入を確定", "購入する", "予約を確定"),
    ).mapValues { (_, words) -> words.map(Hant::fold) }
    private const val PREFIX_SLACK = 4

    /** 操作ラベルと具体的な確認文だけに使う。単独の「キャンセル」や、説明・履歴・設定への入口は含めない。 */
    private val JAPANESE_ORDER_TARGETS = listOf("予約", "注文", "申込", "申し込み").map(Hant::fold).toSet()
    private const val JP_ORDER_TARGET = "予約|注文|申込|申し込み"
    private const val JP_DATA_TARGET = "データ|すべてのデータ|全てのデータ|履歴|記録|設定|アカウント|契約|送信"
    private val JAPANESE_CANCEL_LABEL = Regex(Hant.fold("(?:$JP_ORDER_TARGET)を(?:キャンセル(?:する)?|取り消す|取消す)"))
    private val JAPANESE_DELETE_LABEL = Regex(Hant.fold(
        "(?:(?:$JP_DATA_TARGET)を)?(?:取り消す|取消す|キャンセルする|解約(?:する)?|消去(?:する)?|初期化(?:する)?|リセット(?:する)?|(?:すべて|全て)を?クリア)",
    ))
    private val JAPANESE_CONFIRM = Regex(Hant.fold(
        "(?:この|選択した|選択中の)?($JP_ORDER_TARGET|$JP_DATA_TARGET)を" +
            "(取り消|取消|キャンセル|解約|消去|初期化|リセット|(?:すべて|全て)クリア)(?:しますか|しても(?:よろしい|いい)ですか)",
    ))
    private val JAPANESE_NO_ACTION = Regex(Hant.fold(
        "(?:この)?(?:(?:$JP_ORDER_TARGET|$JP_DATA_TARGET)を)?" +
            "(?:(?:キャンセル|解約|消去|初期化|リセット|クリア)(?:しない|しません)|(?:取り消|取消)(?:さない|しません))(?:でください)?",
    ))
    /** 「記録」「一覧」の状態語除外より優先する、従来の明確な削除ラベルだけ。 */
    private val CLEAR_RECORD_LABELS = setOf("清空记录", "清空列表").map(Hant::fold).toSet()

    /** は単語に等しいか、句読点のみが続きます (「コメント (23)」と「返信 3」はエントリおよびカウントであり、カウントされません)。 */
    val PUNCT_WORDS: Map<IrrevAction, List<String>> = linkedMapOf(IrrevAction.send to listOf("评论", "回复", "返信")).mapValues { (_, words) -> words.map(Hant::fold) }

    /** は単語と等しいか、「immediately」または「confirm」が前に付きます。 */
    val BOOKING_WORDS = listOf("预约", "报名", "予約する", "予約を確定する", "申し込む", "申込を確定する", "申し込みを確定する").map(Hant::fold)
    private val BOOKING_PREFIXES = listOf("", "立即", "确认")

    /** は完全な同等性のみを認識します。「検索の送信」カテゴリはブロックされません。 */
    val SUBMIT_WORDS = listOf("提交", "确认提交", "送出", "submit", "提出", "提出する", "送信する", "応募する", "応募を確定", "申し込む").map(Hant::fold)

    /**
     * ボタンをクリックして他の人に連絡し、ユーザーの申請を送信し (Web サイトをクリックして挨拶と履歴書を相手に送信します)、[送信] をクリックします。
     * は単語と等しいか、「immediately」、「immediately」、「one click」、「go」が前にあり、その後に句読点のみが続きます。
     * 「コミュニケーションを続ける」、「送信済み」、「コミュニケーション記録」、「カスタマーサービスに連絡する」、「お問い合わせ」、「プライベートメッセージ」、「メッセージ」はカウントされません。これらは既存の会話、記録、カスタマーサービスページです。
     * または、メッセージが書かれている場所を開くだけです (実際に送信されるのはその後の送信です)。
     */
    val CONTACT_WORDS = listOf(
        "沟通", "打招呼", "打个招呼", "发起聊天", "发起沟通", "联系ta", "联系他", "联系她", "联系对方",
        "投递", "投简历", "投递简历", "应聘",
    )
    private val CONTACT_PREFIXES = listOf("", "立即", "马上", "一键", "去")
    /** 「申請」自体が一般的すぎます（返金申請、許可申請）：私はこれらの用語しか認識していません。 */
    val APPLY_WORDS = listOf("立即申请", "一键申请", "申请职位", "申请岗位", "申请该职位")
    /** 英語: 名前全体はこれらと同じです (大文字と小文字は区別されず、空白で結合され、末尾の句読点が削除されます)。個別の適用（フィルターパネルの適用）、連絡先、およびメッセージはカウントされません。 */
    val CONTACT_ENGLISH = listOf(
        "say hi", "say hello", "send greeting", "apply now", "easy apply", "quick apply", "apply for this job", "apply to this job",
        "submit application", "contact now", "contact recruiter", "contact employer", "contact seller", "contact host", "contact owner",
        "contact landlord", "contact agent",
    )

    /** 英語: 名前はこの単語で始まり (大文字と小文字は関係なく単語の境界に従って)、「メッセージを送信する」や「会話を削除する」など、長さに制限はありません。 */
    val ENGLISH: Map<String, IrrevAction> = linkedMapOf(
        "send" to IrrevAction.send, "post" to IrrevAction.send, "reply" to IrrevAction.send,
        "pay" to IrrevAction.pay, "donate" to IrrevAction.pay,
        "delete" to IrrevAction.delete, "remove" to IrrevAction.delete, "clear all" to IrrevAction.delete,
        "place order" to IrrevAction.order, "buy now" to IrrevAction.order, "checkout" to IrrevAction.order,
    )

    /** ポップアップ ウィンドウ、下部パネルの普遍的な確認: 同じ画面上に強い単語や金額がある場合のみカウントされます。 */
    val GENERIC = listOf("确认", "确定", "ok", "完成", "confirm", "確認", "確定", "実行", "完了", "はい").map(Hant::fold)
    private const val AMOUNT_NUMBER = "[0-9０-９][0-9０-９,，]*(?:[.．][0-9０-９]+)?"
    private const val CURRENCY_CODE = "(?:JPY|CNY|USD|RMB|ＪＰＹ|ＣＮＹ|ＵＳＤ|ＲＭＢ)"
    private const val CURRENCY_PREFIX = "(?:[¥￥$＄]|(?<![A-Za-zＡ-Ｚａ-ｚ])$CURRENCY_CODE)"
    private const val CURRENCY_SUFFIX = "(?:元|円|$CURRENCY_CODE(?![A-Za-zＡ-Ｚａ-ｚ]))"
    private const val NUMBER_ATOM = "(?:$AMOUNT_NUMBER|[〇零一二三四五六七八九壱弐参]+)"
    /** 換算に未対応の数表記。数字・空白・位取りだけを結合し、件数などの語をまたいで通貨へ結び付けない。 */
    // 空白は直前の数字・位取りにだけ属させ、反復の境界で同じ空白を分け合わない。
    private const val UNSCALED_AMOUNT_NUMBER = "(?:$NUMBER_ATOM\\s*)?(?:[十拾百佰千仟万萬億亿兆京]\\s*(?:$NUMBER_ATOM\\s*)?)+"
    /** 金額の申告欄では通貨の書き方に依存せず位取りを検出する。 */
    internal val UNSCALED_NUMBER = Regex(UNSCALED_AMOUNT_NUMBER)
    /** [com.wangzan101.mika.core.action.ClassifiedRisk] と共有する通貨付き金額。全角数字・桁区切り・通貨コードも認識します。 */
    internal val AMOUNT = Regex(
        "$CURRENCY_PREFIX\\s*(?:$UNSCALED_AMOUNT_NUMBER|$AMOUNT_NUMBER)|(?:$UNSCALED_AMOUNT_NUMBER|$AMOUNT_NUMBER)\\s*$CURRENCY_SUFFIX",
        RegexOption.IGNORE_CASE,
    )
    /** 認識済み通貨の位取り付き金額は、未換算の数値部分だけで承認しない。 */
    internal val UNSCALED_AMOUNT = Regex(
        "$CURRENCY_PREFIX\\s*$UNSCALED_AMOUNT_NUMBER|$UNSCALED_AMOUNT_NUMBER\\s*$CURRENCY_SUFFIX",
        RegexOption.IGNORE_CASE,
    )
    /** これらの単語が含まれる金額は合計であり、スクロール可能領域内にあるかどうかに関係なく、コンテキストとみなされます。 */
    val TOTAL_LABELS = listOf("合计", "实付", "应付", "总计", "合計", "お支払い金額", "支払金額", "ご請求額", "注文合計").map(Hant::fold)
    /** 同じ画面に金額がある場合、これらの文字は支払いパネル (支払い方法、レジ係、パスワード認証) を示します。 */
    val PAY_SHEET_WORDS = listOf("支付方式", "付款方式", "支付宝", "银行卡", "收银台", "免密", "支払い方法", "お支払い方法", "決済方法", "クレジットカード", "振込先").map(Hant::fold)
    /** という単語は、通常のページ (アカウント残高、ログイン パスワード) にもよく見られます。画面の下半分と同じ領域に確認/支払いボタンがある場合にのみ、支払いパネルとみなされます。 */
    val WEAK_PAY_SHEET_WORDS = listOf("余额", "密码", "指纹", "零钱", "残高", "暗証番号", "指紋").map(Hant::fold)
    /** お支払い方法を紹介する文言（商品ページや仕様パネルによくあります）は、お支払い行為ではありませんので、削除してからご判断ください。 */
    private val PAYMENT_ADS = Regex("支持[^\\s，,。]{0,8}?(?:付款|支付)|货到付款|先用后付|花呗|分期|付款方式说明")
    /** ページにこれらの注文ボタンがある場合、それは商品ページまたは仕様パネルです。支払いパネルという言葉で判断しないでください。 */
    private val ORDER_CONTEXT = listOf("立即购买", "加入购物车", "立即下单", "カートに入れる", "今すぐ買う", "購入手続きへ").map(Hant::fold)
    /** 0–999 では、この行は画面の下部領域 (支払いパネル、下部ポップアップ レイヤー) をカウントします。 */
    private const val BOTTOM_REGION = 500
    private val PAY_SHEET_ENGLISH = Regex("(?<![A-Za-z0-9_])(pay|payment|checkout total)(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)

    /** まず名前から削除してから、支払い方法のオプションは支払いアクションではないということを強く主張します。 */
    val EXCLUDED = listOf("支付宝", "支付方式", "支付设置", "支払い方法", "お支払い方法", "支払い設定", "購入履歴", "振込先", "送金履歴", "支払履歴", "支払方法", "送信先", "送信設定", "送信履歴").map(Hant::fold)

    /** リソース ID は、_ - で区切られた単語です。そしてキャメルケース。 submit と post はここにはありません (リソース ID ではあまりにも一般的です)。 */
    private val RESOURCE_WORDS: Map<String, IrrevAction> = mapOf(
        "send" to IrrevAction.send, "reply" to IrrevAction.send,
        "pay" to IrrevAction.pay,
        "delete" to IrrevAction.delete, "remove" to IrrevAction.delete,
        "checkout" to IrrevAction.order,
    )

    /** 入力ボックスが明らかに検索ボックスの場合は送信されません。他の入力ボックスで Enter を押した場合は、送信を押します。プロンプトでこれらの単語を検索し、リソース ID で単語で検索します。 */
    private val SEARCH_FIELD = listOf("search", "搜索", "搜寻", "検索", "探す", "キーワード").map(Hant::fold)
    private val SEARCH_BUTTONS = setOf("搜索", "搜寻", "search", "検索", "探す").map(Hant::fold).toSet()

    private const val KEY_ENTER = 66
    private const val KEY_NUMPAD_ENTER = 160
    private const val KEY_DPAD_CENTER = 23
    private const val KEY_SPACE = 62
    private const val KEY_DEL = 67
    private const val KEY_FORWARD_DEL = 112
    private val ACTIVATING_KEYS = setOf(KEY_ENTER, KEY_NUMPAD_ENTER, KEY_DPAD_CENTER, KEY_SPACE)

    /** 画面幅の比率（0～999）を超えて水平にスワイプし、リスト行から開始して「スライドして削除」を押します。 */
    private const val SWIPE_DELETE_MIN = 400
    /** 幅がこれ (0 ～ 999) を超えるポイント可能な要素の数テーブル行。 */
    private const val ROW_MIN_WIDTH = 600
    /** これより高い要素 (0 ～ 999) は、「同じ行上」 (コンテナー、テキストの大きなブロック) とみなされません。 */
    private const val ROW_MAX_HEIGHT = 150
    /** これより短いスクロール可能領域 (0 ～ 999) はカルーセルとしてカウントされます。 */
    private const val CAROUSEL_MAX_HEIGHT = 400

    /** 入力ボックスの横にある「クリア」ボタンの名前 (空白と小文字を削除)。 */
    private val FIELD_CLEAR_WORDS = setOf("清空", "清除", "清空输入", "清除输入", "清空搜索", "清除搜索", "清空内容", "清除内容",
        "clear", "cleartext", "clearsearch", "clearquery", "×", "✕", "x")
    /** クリア キーは、入力ボックスの左端と右端から最大でこの距離 (0 ～ 999) にあります。 */
    private const val FIELD_NEAR = 80

    // 何を判断するか、何を判断するかはツールによって宣言されます ([GateTarget]、外部ツールのみがこれを持っています)。戻って入力ボックスをクリアし、アプリを開いても何も送信されず、判定は行われません。
    /** 選択した項目を変更するためのキー: 方向キー、Home/End、ページ送り。 */
    private val SELECTION_KEYS = setOf(19, 20, 21, 22, 122, 123, 92, 93)
    /** 選択された項目が変更されたときにページ アクション (変更送信時) をトリガーするコントロール。 */
    private val SELECTION_ROLES = setOf("combobox", "listbox", "option", "radio", "radiogroup", "menu", "menubar", "menuitem",
        "menuitemradio", "menuitemcheckbox", "tree", "treeitem")
    private val OPTION_ROLES = setOf("option", "radio", "menuitem", "menuitemradio", "menuitemcheckbox", "treeitem")

    /** このコールを判定する必要があるかどうか (判定された場合のみ画面を読み取ります)。 */
    fun needsCheck(target: GateTarget?, args: JsonObject): Boolean = when (target) {
        // アップロードキーファイルコントロール、判定はクリックと同じです
        GateTarget.Tap, GateTarget.FileControl -> true
        // 入力してキャリッジを戻すと、間違った値になります。ポイントに入力するとき、クリックも間違っています（クリックされたボタンは送信ボタンである可能性があります）
        GateTarget.Enter -> bool(args, "pressEnter") || atPoint(args)
        GateTarget.Key -> key(args) in ACTIVATING_KEYS
        GateTarget.Swipe -> horizontal(args) != null
        // Web ページ上で送信をトリガーできるインタラクション: 各キーの組み合わせでフォーカスを押し、開始点と終了点に従ってドラッグします (スライドして支払い、削除領域にドラッグします)。
        // ドロップダウン選択 ドロップダウン ボックスと選択した項目を押します (ページは変更され次第送信されます)。ホバリングは送信されません
        GateTarget.Interact -> when (str(args, "action")) {
            "key" -> combo(args) != null
            "drag", "select" -> true
            // ページ上のポップアップ ボックスで [OK] をクリックすると、送信 (削除の確認、支払いの確認) が行われる場合があります。ポップアップ ボックス内の単語を押して判断します。キャンセルは判断されません
            "dialog" -> str(args, "answer") == "accept"
            else -> false
        }
        null -> false
    }

    /** null = 取り消し可能な操作ではありません。 */
    fun classify(target: GateTarget?, args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot? = null): Irreversible? {
        if (!needsCheck(target, args)) return null
        return when (target) {
            GateTarget.Tap, GateTarget.FileControl -> tapHit(args, snapshot, fresh)
            GateTarget.Enter -> (if (atPoint(args)) tapHit(args, snapshot, fresh) else null)
                ?: if (bool(args, "pressEnter")) enterHit(args, snapshot, fresh ?: snapshot, KEY_ENTER) else null
            GateTarget.Key -> enterHit(args, snapshot, fresh ?: snapshot, key(args)!!)
            GateTarget.Swipe -> swipeHit(args, fresh ?: snapshot)
            GateTarget.Interact -> when (str(args, "action")) {
                "key" -> comboHit(args, snapshot, fresh ?: snapshot)
                "select" -> selectHit(args, snapshot, fresh)
                "dialog" -> dialogHit(fresh ?: snapshot)
                else -> dragHit(args, snapshot, fresh)
            }
            null -> null
        }
    }

    private fun combo(args: JsonObject) = str(args, "key")?.let(CdpKeys::parseCombo)

    /** 入力するのは点 (座標、または座標に変更する前の @vN) であり、ツリー内の要素ではありません。 */
    private fun atPoint(args: JsonObject) = args["x"] != null || str(args, "ref")?.let(LOCATED_REF::matches) == true

    /**
     * キーの組み合わせを押して、フォーカスに対して何ができるかを判断します。
     * Enter またはスペース (修飾キーの有無にかかわらず) はフォーカスをアクティブにし、press_key の Enter/スペース ルールに従って判断されます (クリックするのと同じ、入力ボックス内のスペースは入力です)。
     * 方向キー、Home/End、ページめくりで、選択した項目が変更されます。これは、フォーカスが選択コントロールにある場合にのみ発生します。コントロールを押して項目を選択します (選択と同じ)。
     * フォーカスが入力ボックスにない場合、ページは削除、バックスペース、単一の英数字、Ctrl/Alt/Meta+英数字をコマンドとして扱うことがあります。フォーカスが要素にある場合は、それをクリックします。
     * (選択コントロール上の文字は最初の文字に従ってジャンプ選択され、コントロールとオプション項目に従って判断されます);フォーカスがページ自体にある場合 (フォーカス要素がない場合)、削除とバックスペースはブロックされます。
     * 他のコマンド キーは、ページがどのコントロールのショートカット キー (aria-keyshortcuts、accesskey) であるかを宣言しているときに、そのコントロールを押すことによってのみ判断されます。
     * Tab、Escape等はフォーカスの移動や閉じるだけであり、入力ボックス内のキーは編集中かどうかの判定はされません。
     */
    private fun comboHit(args: JsonObject, snapshot: ScreenSnapshot?, s: ScreenSnapshot?): Irreversible? {
        val combo = combo(args)!!
        val code = combo.key.androidKeyCode
        if (code == KEY_ENTER || code == KEY_SPACE) return enterHit(args, snapshot, s, code)
        val screen = s ?: snapshot ?: return null
        val focused = screen.elements.firstOrNull { it.focused }
        if (focused?.editable == true) return null
        // コントロールのキーを選択して、選択した項目を変更します (方向キー、最初の単語を押すと選択項目にジャンプします)。コントロールを押して項目を選択します。
        fun selection(e: UiElement) = selectionTargets(e, screen).firstNotNullOfOrNull { elementHit(it, screen) }
        if (code in SELECTION_KEYS) return focused?.takeIf { it.role in SELECTION_ROLES }?.let(::selection)
        val deleting = code == KEY_DEL || code == KEY_FORWARD_DEL
        val printable = combo.key.text != null && combo.key.text != " "
        if (!deleting && !printable) return null
        val typeAhead = printable && combo.modifiers.all { it == KeyModifier.Shift }
        if (focused != null) {
            val own = if (typeAhead && focused.role in SELECTION_ROLES) selection(focused) else elementHit(focused, screen)
            return own ?: declaredHit(combo, screen)
        }
        if (deleting) return Irreversible(IrrevAction.delete, "${combo.key.key}:焦点不明")
        return declaredHit(combo, screen)
    }

    /** ページでは、このキーの組み合わせ (aria-keyshortcuts、accesskey) の制御を宣言しており、それをクリックして判断します。 */
    private fun declaredHit(combo: KeyCombo, s: ScreenSnapshot): Irreversible? = s.elements
        .filter { e -> e.keyShortcuts.any { sameCombo(CdpKeys.parseCombo(it), combo) } }
        .firstNotNullOfOrNull { elementHit(it, s) }

    private fun sameCombo(a: KeyCombo?, b: KeyCombo) = a != null && a.key.code == b.key.code && a.modifiers.toSet() == b.modifiers.toSet()

    /** 選択タイプのコントロールと選択できる項目: コントロールのオプション。フォーカス自体がオプションの場合、同じタイプの他のオプション (ラジオ グループ、メニュー) があります。 */
    private fun selectionTargets(focused: UiElement, s: ScreenSnapshot): List<UiElement> {
        val inside = s.elements.filter { it !== focused && it.role in OPTION_ROLES && inside(it, focused) }
        val peers = if (focused.role in OPTION_ROLES) s.elements.filter { it !== focused && it.role == focused.role } else emptyList()
        return listOf(focused) + inside + peers
    }

    /** ページポップアップボックスの確認：ボタン名を押してポップアップフレーム内の単語を判定し（一般的な「OK」については同画面のコンテキストを参照）、文全体の中で強い単語をクリックして判定します。 */
    private fun dialogHit(s: ScreenSnapshot?): Irreversible? {
        val message = s?.web?.dialog?.message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val action = matchName(message, s) ?: message.split(Regex("[\\s，,。.！!？?：:；;]+")).firstNotNullOfOrNull { strong(it) } ?: return null
        return Irreversible(action, "dialog:${message.take(40)}")
    }

    /** ドロップダウン選択：ドロップダウンボックスをクリックして判定し、ボタンをクリックして選択した項目のテキストを判定します。 */
    private fun selectHit(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): Irreversible? {
        tapHit(args, snapshot, fresh)?.let { return it }
        val option = str(args, "option") ?: return null
        return matchName(option, fresh ?: snapshot)?.let { Irreversible(it, "option:${option.trim()}") }
    }

    /** ドラッグの開始点と終了点は、それぞれ 1 回のクリックで決定されます (数値は snapshotId を共有します)。 */
    private fun dragHit(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): Irreversible? {
        fun end(ref: String, x: String, y: String) = buildJsonObject {
            val r = str(args, ref)
            if (r != null) {
                put("ref", r)
                str(args, "snapshotId")?.let { put("snapshotId", it) }
            } else {
                args[x]?.let { put("x", it) }
                args[y]?.let { put("y", it) }
            }
        }
        return listOf(end("ref", "x", "y"), end("toRef", "toX", "toY")).firstNotNullOfOrNull { tapHit(it, snapshot, fresh) }
            ?.let { it.copy(matched = "drag:${it.matched}") }
    }

    /**
     * この画面は支払いページまたは支払いパネルです。支払いの確認や支払いパスワードなどの文字があったり、金額があり支払いパネルのように見えます。
     * （商品ページおよび仕様パネルは付属しておりません）。ヒットの根拠を返します。 nullではありません。
     */
    fun paymentPage(s: ScreenSnapshot): String? {
        ScreenDiagnosis.paymentText(s.elements.map { it.name })?.let { return it }
        val shown = s.elements.filter { !it.editable && !it.hint }
        val amount = shown.any { e -> TOTAL_LABELS.any { it in Hant.fold(e.name) } || AMOUNT.containsMatchIn(rowText(e, shown)) }
        return if (amount && paySheet(shown)) "payment sheet with an amount" else null
    }

    /** アクションに関連する合計・実払額・支払額や決済画面を確認します。単なる価格・待遇・範囲は対象外とし、明示的な支払い・注文は常に金額操作として扱います。 */
    fun hasPayableAmount(s: ScreenSnapshot): Boolean {
        val shown = s.elements.filter { !it.editable && !it.hint }
        val amount = shown.any { AMOUNT.containsMatchIn(rowText(it, shown)) }
        if (!amount) return false
        return shown.any { e -> TOTAL_LABELS.any { it in Hant.fold(e.name) } } ||
            ScreenDiagnosis.paymentText(shown.map { it.name }) != null || paySheet(shown)
    }

        /** クリックがどの要素に作用しているのかが見つかりません (画面読み取り値が欠落しており、数値がこの画面読み取り値に属しておらず、座標に要素がありません)。実行記録に痕跡を残すため。 */
    fun unresolved(target: GateTarget?, args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot? = null): Boolean =
        target == GateTarget.Tap && tapTargets(args, snapshot, fresh).isEmpty()

    /**
     * ボタン名一致単語リスト; [screen] 指定されたら、一般的な「確認/確認」を使用して、同じ画面に強い単語や金額があるかどうかを確認します。
     * [hit] は、クリックされた要素 (既知の場合) です。スクロール可能な領域内の量は、クリックされた要素がその領域にない場合にのみカウントされません。 名前
     */
    fun matchName(name: String, screen: ScreenSnapshot? = null, hit: UiElement? = null): IrrevAction? {
        val raw = Hant.fold(name).trim()
        if (raw.isEmpty()) return null
        val n = raw.filterNot(Char::isWhitespace).lowercase()
        if (n in EXCLUDED) return null
        if (n in setOf("送信しない", "削除しない", "購入しない", "注文しない", "予約しない", "支払わない", "キャンセル").map(Hant::fold)) return null
        if (JAPANESE_NO_ACTION.matches(n.trimEnd { !it.isLetterOrDigit() })) return null
        japaneseDestructive(raw)?.let { return it }
        if (n.trimEnd { !it.isLetterOrDigit() } in CLEAR_RECORD_LABELS) return IrrevAction.delete
        if (n in SUBMIT_WORDS) return IrrevAction.submit
        if (contacts(raw)) return IrrevAction.submit
        if (n in RED_PACKET) return IrrevAction.pay
        if (EXACT_PAY.any { w -> EXACT_PAY_PREFIXES.any { n == it + w } }) return IrrevAction.pay
        strong(raw)?.let { return it }
        for ((action, words) in PREFIX_WORDS) for (w in words) {
            if (n.startsWith(w) && n.length <= w.length + PREFIX_SLACK && actionAt(n, w, 0)) return action
        }
        for ((action, words) in PUNCT_WORDS) for (w in words) {
            if (n.startsWith(w) && n.drop(w.length).none { it.isLetterOrDigit() }) return action
        }
        if (BOOKING_WORDS.any { w -> BOOKING_PREFIXES.any { n == it + w } }) return IrrevAction.order
        val lower = raw.lowercase()
        for ((w, action) in ENGLISH) {
            if (lower.startsWith(w) && lower.getOrNull(w.length)?.isLetter() != true) return action
        }
        if (screen != null && n.trimEnd { !it.isLetterOrDigit() } in GENERIC) return contextAction(screen, hit)
        return null
    }

    /** は、他の人に連絡して申請するためのボタンです ([CONTACT_WORDS] を参照)。 */
    private fun contacts(raw: String): Boolean {
        val n = raw.filterNot(Char::isWhitespace).lowercase().trimEnd { !it.isLetterOrDigit() }
        if (n in APPLY_WORDS || CONTACT_WORDS.any { w -> CONTACT_PREFIXES.any { n == it + w } }) return true
        val e = raw.lowercase().trim().trimEnd { !it.isLetterOrDigit() }.replace(Regex("\\s+"), " ")
        return e in CONTACT_ENGLISH
    }

    /** 短い名前全体、または綴りの名前の各段落に強い単語が含まれています。まず支払い方法などの単語を削除してください。 */
    private fun strong(label: String): IrrevAction? {
        val folded = Hant.fold(label)
        val whole = folded.filterNot(Char::isWhitespace).trimEnd { !it.isLetterOrDigit() }
        if (JAPANESE_NO_ACTION.matches(whole)) return null
        japaneseDestructive(folded)?.let { return it }
        for (p in listOf(folded) + folded.split(Regex("\\s+"))) {
            var t = p.filterNot(Char::isWhitespace).replace(PAYMENT_ADS, "")
            EXCLUDED.forEach { t = t.replace(it, "") }
            if (t.isEmpty() || t.length > STRONG_MAX_LEN) continue
            STRONG.entries.firstOrNull { (w, _) -> occurrences(t, w).any { actionAt(t, w, it) } }?.let { return it.value }
        }
        return null
    }

    private fun japaneseDestructive(label: String): IrrevAction? {
        val n = Hant.fold(label).filterNot(Char::isWhitespace).trimEnd { !it.isLetterOrDigit() }
        if (JAPANESE_CANCEL_LABEL.matches(n)) return IrrevAction.order
        if (JAPANESE_DELETE_LABEL.matches(n)) return IrrevAction.delete
        val confirmation = JAPANESE_CONFIRM.matchEntire(n) ?: return null
        return if (confirmation.groupValues[1] in JAPANESE_ORDER_TARGETS &&
            confirmation.groupValues[2] in listOf("取り消", "取消", "キャンセル").map(Hant::fold)) IrrevAction.order else IrrevAction.delete
    }

    private fun occurrences(t: String, w: String) = generateSequence(t.indexOf(w).takeIf { it >= 0 }) { i -> t.indexOf(w, i + 1).takeIf { it >= 0 } }

    /** これは状態ではなくアクションです。前部は「待機中」または「すでに」ではなく、後部 (句読点をスキップする) はステータス単語ではありません。 */
    private fun actionAt(t: String, w: String, i: Int): Boolean {
        if (i > 0 && t[i - 1] in STATUS_PREFIXES) return false
        val rest = t.substring(i + w.length).dropWhile { !it.isLetterOrDigit() }
        return STATUS_SUFFIXES.none { rest.startsWith(it) }
    }

    /**
     * 同じ画面のコンテキスト (ポップアップ ウィンドウ、下部パネル): 支払いの単語がある場合、それは支払いを意味します。それ以外の場合は、順序のない最初の強い単語を押します (確認ボックスの削除など)。
     * 同じ画面上に金額と支払い方法、レジ担当者、パスワード認証などの単語がある場合 (または任意の長さの段落に支払い/支払いがある場合)、それも支払いです。
     * 入力する単語または金額がある場合は、注文を押します (注文確認は画面上の金額と一致する必要があります。[ConfirmationGate] を参照)。どちらでもない場合は、そうではありません。
     * 金額: 1 つのカテゴリの合計。他の金額は、スクロール可能な領域にあり、クリックされた要素がその領域 (価格ファイル、フィルター パネルの製品リスト) にない場合にはカウントされません。
     */
    private fun contextAction(screen: ScreenSnapshot, hit: UiElement?): IrrevAction? {
        val found = screen.elements.mapNotNull { strong(it.name) }
        if (IrrevAction.pay in found) return IrrevAction.pay
        found.firstOrNull { it != IrrevAction.order }?.let { return it }
        val shown = screen.elements.filter { !it.editable && !it.hint }
        val scrollables = screen.elements.filter { it.scrollable }
        val amount = shown.any { e ->
            // は、単語の 1 つのカテゴリを合計します (量は次のいくつかの段落に分割されるか、数値がまだロードされていない可能性があります)。それ以外の場合は、行全体をまとめることで金額を決定できます。
            TOTAL_LABELS.any { it in Hant.fold(e.name) } ||
                (AMOUNT.containsMatchIn(rowText(e, shown)) && scrollables.none { s -> inside(e, s) && hit != null && !inside(hit, s) })
        }
        if (amount && paySheet(shown)) return IrrevAction.pay
        return if (IrrevAction.order in found || amount) IrrevAction.order else null
    }

    /**
     * 金額を表示する画面は支払いパネルのようには見えません: 支払い方法、レジ係、パスワード認証などの単語、または支払い/支払いに関する任意の長さの段落
     * (最初に「Huabei 支払いのサポート」と「代金引換」の紹介を削除します);ページに [今すぐ購入] ボタンがある場合は、ショッピング カートに追加するときにクリックしないでください。
     * 残高、パスワード、指紋、釣銭は、画面の下半分に確認/支払いボタンがある場合にのみカウントされます。
     */
    private fun paySheet(shown: List<UiElement>): Boolean {
        if (shown.any { e -> ORDER_CONTEXT.any { it in Hant.fold(e.name).filterNot(Char::isWhitespace) } }) return false
        val bottomButton = shown.any { b ->
            b.clickable && top(b) >= BOTTOM_REGION && Hant.fold(b.name).filterNot(Char::isWhitespace).lowercase().let { n ->
                n.trimEnd { !it.isLetterOrDigit() } in GENERIC || "支付" in n || "付款" in n
            }
        }
        return shown.any { e ->
            val t = Hant.fold(e.name).filterNot(Char::isWhitespace).replace(PAYMENT_ADS, "")
            PAY_SHEET_WORDS.any { it in t } || PAY_SHEET_ENGLISH.containsMatchIn(e.name) ||
                listOf("支付", "付款").any { w -> occurrences(t, w).any { actionAt(t, w, it) } } ||
                (bottomButton && top(e) >= BOTTOM_REGION && WEAK_PAY_SHEET_WORDS.any { it in t })
        }
    }

    /**
     * [start] 以降の同一行のセグメントを横方向にまとめます（「実際の支払金額 | ¥ | 39.90」）。
     * 同じ行内: 両方の垂直方向の中心が互いの上端と下端の間にあり、画面全体の大きなコンテナが他の行をブロックしません。
     */
    internal fun rowText(start: UiElement, elements: List<UiElement>): String {
        if (start.bounds.size != 4) return start.name
        fun cy(e: UiElement) = (e.bounds[1] + e.bounds[3]) / 2
        val row = elements.filter { e ->
            e !== start && e.bounds.size == 4 && e.bounds[0] >= start.bounds[0] &&
                cy(e) in start.bounds[1]..start.bounds[3] && cy(start) in e.bounds[1]..e.bounds[3]
        }.sortedBy { it.bounds[0] }
        return start.name + row.joinToString("") { it.name.filterNot(Char::isWhitespace) }
    }

    private fun top(e: UiElement) = if (e.bounds.size == 4) e.bounds[1] else 0

    private fun matchResource(id: String): IrrevAction? = resourceWords(id).firstNotNullOfOrNull { RESOURCE_WORDS[it] }

    /** リソース ID は、_ - で区切られた単語です。そしてキャメルケース。 */
    private fun resourceWords(id: String): List<String> =
        id.substringAfterLast('/').replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase().split('_', '-', '.')

    /**
     * 入力ボックス内の単語を変更しても何も送信されません。入力ボックスをクリックし (フォーカスするだけです。ボックス内の単語は入力された内容です)、ボックス内に名前のない小さなアイコンをクリックします。
     * （クリア×、音声）またはクリアキー、またはボックスの横にある、同じ行にある、「クリア」という名前のボタンはすべて編集です。送信されるのは、後続の復帰、送信、および検索です。
     */
    internal fun editsField(e: UiElement, s: ScreenSnapshot): Boolean {
        if (e.editable) return true
        if (e.bounds.size != 4) return false
        val fields = s.elements.filter { it.editable && it.bounds.size == 4 && it !== e }
        val n = Hant.fold(e.name).filterNot(Char::isWhitespace).lowercase()
        val clears = n in FIELD_CLEAR_WORDS || e.resourceId?.let { r -> "clear" in resourceWords(r) } == true
        // ボックス内に名前のない小さなアイコン (透明)
        val bareIcon = n.isEmpty() && e.resourceId?.let(::matchResource) == null
        if ((clears || bareIcon) && fields.any { inside(e, it) }) return true
        if (!clears) return false
        val cy = (e.bounds[1] + e.bounds[3]) / 2
        return fields.any { f -> cy in f.bounds[1]..f.bounds[3] && e.bounds[0] >= f.bounds[0] - FIELD_NEAR && e.bounds[0] <= f.bounds[2] + FIELD_NEAR }
    }

    private fun elementHit(e: UiElement, s: ScreenSnapshot): Irreversible? {
        for (x in withChildren(e, s)) {
            if (editsField(x, s)) continue
            matchName(x.name, s, x)?.let { return Irreversible(it, x.name.trim()) }
            x.resourceId?.let { r -> matchResource(r)?.let { return Irreversible(it, r) } }
        }
        return null
    }

    private fun tapHit(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): Irreversible? {
        val targets = tapTargets(args, snapshot, fresh)
        // は、モデルに書かれたターゲットを見ずに、入力ボックスまたはそのクリア キーをクリックします (「検索ボックスをクリア」)。
        if (targets.isNotEmpty() && targets.all { (e, s) -> editsField(e, s) }) return null
        for ((e, s) in targets) elementHit(e, s)?.let { return it }
        // 座標点に名前の付いた要素がありません (自作のインターフェイス、名前のないアイコン、これは写真を見て見つけた点によくあります): 同じ行のテキストに基づいて判断します
        if (str(args, "ref") == null && targets.all { (e, s) -> withChildren(e, s).all { it.name.isBlank() } }) {
            val s = fresh ?: snapshot
            val y = num(args, "y")
            if (s != null && y != null) rowHit(s, y)?.let { return it }
        }
        // 要素が見つからない場合、またはアイコンにテキストがない場合は、モデル自体によって書き込まれたターゲットもカウントされます。
        // 絵を見て見つけたポイントに、元の文字（テキスト）とモデルが見た単語（見えた）を添えます。同様の判断がなされる。
        return listOf("target", "text", "seen").firstNotNullOfOrNull { k ->
            str(args, k)?.let { t -> matchName(t, fresh ?: snapshot)?.let { Irreversible(it, t.trim()) } }
        }
    }

    /**
     * クリックされた要素 (その上の画面とともに): ref → 元の読み取り画面の要素に、現時点での中央位置にある要素を加えたもの。
     * 座標 → 現時点でのその点の最小のポイント可能要素。
     */
    private fun tapTargets(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): List<Pair<UiElement, ScreenSnapshot>> {
        val ref = str(args, "ref")
        if (ref != null) {
            val s = snapshot?.takeIf { it.snapshotId == str(args, "snapshotId") } ?: return emptyList()
            val e = s.elements.firstOrNull { it.ref == ref } ?: return emptyList()
            val now = fresh?.let { f -> center(e)?.let { (x, y) -> at(f, x, y) }?.let { it to f } }
            return listOfNotNull(e to s, now)
        }
        val s = fresh ?: snapshot ?: return emptyList()
        val x = num(args, "x") ?: return emptyList()
        val y = num(args, "y") ?: return emptyList()
        return listOfNotNull(at(s, x, y)?.let { it to s })
    }

    /** [y] を縦にまたがる短い要素 (行内のテキスト、ボタン) の名前に元に戻せない単語はありますか? */
    private fun rowHit(s: ScreenSnapshot, y: Int): Irreversible? =
        rowElements(s, y).filterNot { editsField(it, s) }.firstNotNullOfOrNull { e -> matchName(e.name, s, e)?.let { Irreversible(it, e.name.trim()) } }

    private fun rowElements(s: ScreenSnapshot, y: Int) = s.elements
        .filter { it.name.isNotBlank() && !it.editable && it.bounds.size == 4 && y in it.bounds[1]..it.bounds[3] && height(it) <= ROW_MAX_HEIGHT }

    private fun at(s: ScreenSnapshot, x: Int, y: Int): UiElement? {
        val under = s.elements.filter { contains(it, x, y) }
        return under.filter { it.clickable }.minByOrNull(::area) ?: under.minByOrNull(::area)
    }

    /** 名前のないクリック可能なコンテナは、その中に含まれるクリック不可能なテキストのサブ要素とともに判断されます。クリックできない空白のノード (画面全体のルートなど) は展開されません。 */
    private fun withChildren(e: UiElement, s: ScreenSnapshot): List<UiElement> {
        if (e.name.isNotBlank() || !e.clickable) return listOf(e)
        return listOf(e) + s.elements.filter { it !== e && !it.clickable && it.name.isNotBlank() && inside(it, e) }
    }

    /**
     * 入力、確認、スペースはフォーカスに作用します。フォーカスが入力ボックスの場合、それが明らかに検索ボックスでない限り、送信としてカウントされます (スペースは入力しているだけでカウントされません)。
     * ボタンにフォーカスがある場合は単語リストに従って判定されます。フォーカスがどこにあるのかが明確でない場合、画面上の入力ボックスはすべて検索ボックスです。それ以外の場合は成功します。
     * refにtype_textを与えると入力ボックスが使用されます。入力メソッドの「検索」エンターキータイプは読み取れないため、基準として使用できません。
     */
    private fun enterHit(args: JsonObject, snapshot: ScreenSnapshot?, s: ScreenSnapshot?, key: Int): Irreversible? {
        val keyName = when (key) {
            KEY_SPACE -> "Space"
            KEY_DPAD_CENTER -> "DPAD_CENTER"
            KEY_NUMPAD_ENTER -> "NUMPAD_ENTER"
            else -> "Enter"
        }
        val ref = str(args, "ref")
        val field = if (ref == null) null
        else snapshot?.takeIf { it.snapshotId == str(args, "snapshotId") }?.elements?.firstOrNull { it.ref == ref }
        val focused = field ?: s?.elements?.firstOrNull { it.focused }
        if (focused != null) {
            if (focused.editable) {
                if (key == KEY_SPACE || isSearch(focused, s ?: snapshot)) return null
                return Irreversible(IrrevAction.send, "$keyName:${label(focused)}")
            }
            return (s ?: snapshot)?.let { elementHit(focused, it) }
        }
        val fields = s?.elements?.filter { it.editable }.orEmpty()
        if (fields.isNotEmpty() && fields.all { isSearch(it, s) }) return null
        return Irreversible(IrrevAction.send, "$keyName:焦点不明")
    }

    /**
     * は、要素の独自のセマンティクスに従って検索ボックスを認識します。ポートによって認識される検索ボックス コントロール。役割には検索 (Web ページの検索ボックス、Android の検索ボックス コントロール クラス) が含まれます。
     * プロンプト (入力中のコピー、または現在表示されているコピー) またはリソース ID に検索語があるか、同じ行にクリック可能な「検索」ボタンがあります。
     * 入力ボックスに単語がある場合、名前は入力された単語 (「検索を手伝ってください」がメッセージ) となり、名前はプロンプトにのみ表示されます。検索ボックスで Enter キーを押すと、送信ではなく検索が行われ、取り消すことができます。
     */
    private fun isSearch(e: UiElement, s: ScreenSnapshot?): Boolean {
        // 同じ行に送信ボタンがあります。これはメッセージを送信するためのボックス (「検索または質問する」ためのチャット ボックス) であり、プロンプトには、検索は送信によって判断されることが示されています。
        if (s != null && ElementContext.row(e, s).any { b -> b.clickable && !b.editable && sendLike(b.name) }) return false
        if (e.searchField || "search" in e.role.lowercase()) return true
        val hint = Hant.fold(listOfNotNull(e.name.takeIf { e.hint }, e.placeholder).joinToString(" ")).lowercase()
        if (SEARCH_FIELD.any { it in hint } || e.resourceId?.let { "search" in resourceWords(it) } == true) return true
        if (s == null || e.bounds.size != 4) return false
        return s.elements.any { b ->
            b !== e && b.clickable && !b.editable && b.bounds.size == 4 &&
                Hant.fold(b.name).filterNot(Char::isWhitespace).lowercase() in SEARCH_BUTTONS &&
                (b.bounds[1] + b.bounds[3]) / 2 in e.bounds[1]..e.bounds[3]
        }
    }

    /** ボタンには、送信 (送信、公開、公開、送信) のような文字が表示されます。 */
    private fun sendLike(name: String): Boolean {
        val n = Hant.fold(name).filterNot(Char::isWhitespace).lowercase()
        if (n.isEmpty()) return false
        return PREFIX_WORDS.getValue(IrrevAction.send).any { n.startsWith(it) && n.length <= it.length + PREFIX_SLACK } || n == "send" || n == "post"
    }

    /**
     * 水平スライドは十分な長さで、開始点はリスト行 (幅の広いクリック可能な要素、それ自体はスクロール不可) 上にあります。多くのアプリ リストはこの方法で行を削除します。
     * 画面読み取りにはコンテナの方向情報がありません。短いスクロール可能な領域 (カルーセル) のみが認識されます。画面全体の高さの水平コンテナ上の長いスライドは依然としてブロックされます (プロンプト ワードはモデルに代わりに短いスライドを使用するように指示します)。
     */
    private fun swipeHit(args: JsonObject, s: ScreenSnapshot?): Irreversible? {
        val (sx, sy) = horizontal(args) ?: return null
        s ?: return null
        // 開始点は短いスクロール可能な領域 (水平カルーセル タイプ) にあります。これは、削除ではなくページ送りを意味します。
        if (s.elements.any { it.scrollable && contains(it, sx, sy) && height(it) < CAROUSEL_MAX_HEIGHT }) return null
        val row = s.elements.filter { contains(it, sx, sy) && it.clickable && !it.scrollable && width(it) >= ROW_MIN_WIDTH }
            .minByOrNull(::area) ?: return null
        return Irreversible(IrrevAction.delete, "スワイプ: 「${label(row)}」")
    }

    /** 十分な長さの水平スライドの開始点 (0 ～ 999)。水平方向にスライドしない場合、または十分な長さがない場合は、null になります。スクロールはディストリビュータの変換に従って指の開始点を計算します。 */
    private fun horizontal(args: JsonObject): Pair<Int, Int>? {
        if (args.containsKey("startX")) {
            val x1 = num(args, "startX") ?: return null; val y1 = num(args, "startY") ?: return null
            val x2 = num(args, "endX") ?: return null; val y2 = num(args, "endY") ?: return null
            val dx = abs(x2 - x1)
            return if (dx >= SWIPE_DELETE_MIN && dx > abs(y2 - y1)) x1 to y1 else null
        }
        val dir = str(args, "direction") ?: return null
        if (dir != "left" && dir != "right") return null
        val dist = min(650, (num(args, "amount") ?: 3) * 100)
        if (dist < SWIPE_DELETE_MIN) return null
        val cx = num(args, "x") ?: 500; val cy = num(args, "y") ?: 500
        val dx = if (dir == "left") dist else -dist
        return max(50, min(949, (cx - dx / 2.0).roundToInt())) to cy
    }

    private fun key(args: JsonObject): Int? = str(args, "key")?.let(PressKeys::resolve)

    private fun label(e: UiElement) = e.name.trim().ifEmpty { e.resourceId ?: e.ref }

    private fun center(e: UiElement): Pair<Int, Int>? =
        if (e.bounds.size != 4) null else (e.bounds[0] + e.bounds[2]) / 2 to (e.bounds[1] + e.bounds[3]) / 2

    private fun contains(e: UiElement, x: Int, y: Int) =
        e.bounds.size == 4 && x in e.bounds[0]..e.bounds[2] && y in e.bounds[1]..e.bounds[3]

    private fun inside(child: UiElement, parent: UiElement): Boolean {
        val c = child.bounds; val p = parent.bounds
        return c.size == 4 && p.size == 4 && c[0] >= p[0] && c[1] >= p[1] && c[2] <= p[2] && c[3] <= p[3]
    }

    private fun height(e: UiElement) = if (e.bounds.size != 4) 0 else e.bounds[3] - e.bounds[1]

    private fun width(e: UiElement) = if (e.bounds.size != 4) 0 else e.bounds[2] - e.bounds[0]

    private fun area(e: UiElement): Long =
        if (e.bounds.size != 4) Long.MAX_VALUE else (e.bounds[2] - e.bounds[0]).toLong() * (e.bounds[3] - e.bounds[1])

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** は、タップと同じように [a,b] (中点を取る) を許容します。 */
    private fun num(o: JsonObject, k: String): Int? {
        fun d(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        val v = o[k]
        if (v is JsonArray && v.size == 2) {
            val a = d(v[0]); val b = d(v[1])
            if (a != null && b != null) return ((a + b) / 2).roundToInt()
        }
        return d(v)?.roundToInt()
    }

    private fun bool(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() == "true"
}
