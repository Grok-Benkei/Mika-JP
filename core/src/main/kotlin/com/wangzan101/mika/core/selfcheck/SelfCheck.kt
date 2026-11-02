package com.wangzan101.mika.core.selfcheck

/**
 * は互換性セルフテストの項目です。列の順序は実行順序です。 [needs] に合格していない (失敗またはスキップした) 項目がある場合は、この項目をスキップし、どの項目が欠落しているかを示します。
 * [key] はレポート内の英語名、[timeoutMs] はこの項目の最大実行時間です (セルフテスト全体には合計時間制限があります。[SelfCheckRunner.TOTAL_BUDGET_MS] を参照)。
 */
enum class SelfCheck(val key: String, val timeoutMs: Long) {
    /** モデル、システム バージョン、プロセッサ アーキテクチャ、メモリ、画面、ワイヤレス デバッグの有無、およびミカのバージョン。 */
    DEVICE("device", 5_000),
    /** はオンで問題なくバインドされており、通知は許可されており、バッテリーは制限されていません。 */
    PERMISSIONS("permissions", 5_000),
    /** 内蔵アシスタントが実行中、その開始方法、および単純な通話にかかる時間。 */
    HELPER("helper", 15_000),
    /** はミカの仮想画面を構築できます。 Android 14 以降では、独自のフォーカスがありません。 */
    VIRTUAL_DISPLAY("virtual display", 10_000),
    /** 仮想画面でミカ自身のセルフテスト ページを開き、システム設定 (ビュー ポイントのみ) を開き、それがメイン画面ではなくミカの画面上にあることを確認します。 */
    APP_LAUNCH("app launch", 20_000),
    /** セルフテスト ページのコントロール ツリーを読み取ることができ、必要なコントロールがすべてそこにあります。仮想画面のスクリーンショットが黒または空ではありません。 */
    SCREEN_READING("screen reading", 10_000),
    /** セルフテスト ページをクリックし、英語と中国語を入力し、スクロールして戻り、読み返してすべてを確認します。 */
    ACTIONS("actions", 20_000),
    /** セルフテスト ページが移動している 10 秒間に、仮想画面が変化し、コントロール ツリーが読み取れるようになりました。 */
    STAYS_ALIVE("stays alive", 25_000),
    /** ホーム画面の上部にあるタスクは、最初から最後まで変更されません。 */
    MAIN_SCREEN("main screen", 5_000),
    /** の内蔵ブラウザは、ローカル Web ページ (インターネットに接続されていない) を開き、テキストを読み、ボタンをクリックして確認します。 */
    BROWSER("browser", 15_000),
    /** は、`echo ok` と 1 行の Python をサンドボックスで実行します (arm64 上のみ。実行環境は準備ができておらず、部分的に利用できるため、現在はインストールされていません)。 */
    SANDBOX("sandbox", 20_000),
    /** 別のユーザー (アバター、仕事用プロファイル) のコピーは何個まで内部でアプリを開くことができますか?これは単なるカウントであり、アプリではなく、プライベート スペースはカウントされません。 */
    APP_COPIES("app copies", 10_000),
    ;

    /** レポート内の番号は 1 から始まります。 */
    val number: Int get() = ordinal + 1

    /** 診断レポートに表示する日本語名。[key] は既存データとの互換性を保つ。 */
    val displayName: String get() = when (this) {
        DEVICE -> "端末"
        PERMISSIONS -> "権限"
        HELPER -> "操作アシスタント"
        VIRTUAL_DISPLAY -> "仮想画面"
        APP_LAUNCH -> "アプリの起動"
        SCREEN_READING -> "画面の読み取り"
        ACTIONS -> "画面操作"
        STAYS_ALIVE -> "継続動作"
        MAIN_SCREEN -> "メイン画面"
        BROWSER -> "ブラウザー"
        SANDBOX -> "サンドボックス"
        APP_COPIES -> "アプリの複製"
    }

    /** 最初に行うべきことがいくつかあります。 */
    val needs: List<SelfCheck>
        get() = when (this) {
            DEVICE, PERMISSIONS, BROWSER, SANDBOX -> emptyList()
            HELPER -> listOf(DEVICE)
            VIRTUAL_DISPLAY -> listOf(HELPER)
            APP_LAUNCH -> listOf(PERMISSIONS, VIRTUAL_DISPLAY)
            SCREEN_READING -> listOf(APP_LAUNCH)
            ACTIONS -> listOf(SCREEN_READING)
            STAYS_ALIVE -> listOf(SCREEN_READING)
            MAIN_SCREEN -> listOf(VIRTUAL_DISPLAY)
            APP_COPIES -> listOf(HELPER)
        }
}

enum class CheckStatus {
    Pending, Running, Pass, Partial, Fail, Skipped;

    /** 合格: 使用可能、または部分的に使用可能。これに依存する項目は期待どおりに実行されます。 */
    val passed: Boolean get() = this == Pass || this == Partial

    /** は結論に達しました (変更されなくなりました)。 */
    val final: Boolean get() = this != Pending && this != Running
}

/**
 * ユーザーへの文はどのような状況を表しますか (インターフェイスはそれに応じてローカライズされたテキストを取得し、レポートには書き込みません)。これらはすべて一般的な用語であり、理由と実行可能な次のステップを意味します。
 */
enum class Advice {
    /** のシステムバージョンはAndroid 12より低いため、携帯電話での操作は使用できません。 */
    PhoneTooOld,
    /** システムにはワイヤレス デバッグはありません。代わりにコンピューターを使用して USB 経由で起動します。 */
    NoWirelessDebugging,
    /** この環境では、開発者向けオプションやワイヤレス デバッグはなく、携帯電話上のアプリは操作できません。チャット、Web ページ、ファイル、計算は引き続き利用できます。 */
    PhoneControlUnavailable,
    /** アクセシビリティが有効になっていません。 */
    TurnOnAccessibility,
    /** Accessible はオンになっていますが、実行されていません。オフにしてから再度オンにします。 */
    RestartAccessibility,
    /** 通知は許可されていません: 進行状況と「必要な」リマインダーを表示できません。 */
    AllowNotifications,
    /** バッテリーが限られているため、バックグラウンドが停止する可能性があります。 */
    AllowBattery,
    /** 内蔵アシスタントが実行されていません: まず、携帯電話の操作の設定を完了してください。 */
    SetUpPhoneControl,
    /** では仮想画面を作成できません。 */
    DisplayFailed,
    /** Android 14 以下の仮想画面には独自のフォーカスがなく、バックグラウンド操作中にメイン画面の入力フォーカスが一時的に失われる場合があります。 ミカの画面では */
    FocusShared,
    /** ページが開きません。 */
    LaunchFailed,
    /** システム設定はホーム画面で開きます。 */
    OpenedOnMainScreen,
    /** system settings are being used on your home screen, so you don’t have to open it at this step. */
    SettingsInUse,
    /** The control tree cannot read or there are missing controls. */
    ReadFailed,
    /** のスクリーンショットは黒または空です。 */
    BlankFrames,
    /** Clicking, typing, scrolling or going back does not work. */
    ActionFailed,
    /** The virtual screen image stops moving after a while. */
    FramesStopped,
    /** The top task on the home screen has changed. There is no WebView available in the */
    MainScreenChanged,
    /** system. */
    NoWebView,
    /** The built-in browser is not read or clicked. The */
    WebFailed,
    /** sandbox is only available on arm64 phones. */
    SandboxUnsupported,
    /** The operating environment is not ready yet. The */
    SandboxNotPrepared,
    /** command did not produce the expected results. */
    SandboxFailed,
    /** このアイテムはタイムアウトしました。 */
    TimedOut,
    /** この商品は間違っています。 */
    Error,
    /** The time for the entire self-test has run out. */
    OutOfTime,
    /** ユーザーがキャンセルされました。 */
    Cancelled,
}

/**
 * ランニングの結論。 [detail] は、レポートに書き込まれた一連の事実です (英語、個人データは含まれません。アカウント番号、アプリ リスト、ユーザー コンテンツ、シリアル番号は含まれません)。
 * [advice] インターフェースにローカライズされた言語を与えます。通常、渡されるときは空です。
 */
data class CheckOutcome(val status: CheckStatus, val detail: String, val advice: Advice? = null) {
    companion object {
        fun pass(detail: String) = CheckOutcome(CheckStatus.Pass, detail)
        fun partial(detail: String, advice: Advice) = CheckOutcome(CheckStatus.Partial, detail, advice)
        fun fail(detail: String, advice: Advice) = CheckOutcome(CheckStatus.Fail, detail, advice)
        fun skipped(detail: String, advice: Advice? = null) = CheckOutcome(CheckStatus.Skipped, detail, advice)
    }
}

/** は、現時点での項目のステータス (未実行、実行中、または終了) です。 [blockedBy] は、失敗したためスキップされた項目です。 */
data class CheckResult(
    val check: SelfCheck,
    val status: CheckStatus = CheckStatus.Pending,
    val detail: String = "",
    val advice: Advice? = null,
    val blockedBy: SelfCheck? = null,
    val durationMs: Long = 0,
)

/**
 * レポート内のデバイスの事実は、システムのパブリック インターフェイスから取得されます。シリアル番号やアカウント番号など、個人を特定できるものは含まれません。
 * [wirelessDebugging] システムにワイヤレス デバッグがあるかどうかにかかわらず、値を null として読み取ることはできません。 [developerOptionsPage] システムで開発者向けオプション ページを開くことができるかどうか。不明な場合、値は null です。
 * [phoneControlUnavailable] この環境ではアシスタントをアクティブにする方法はありません (ブート ページと同じ判断)。
 */
data class DeviceFacts(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdk: Int,
    val abis: List<String>,
    val ramBytes: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val wirelessDebugging: Boolean?,
    val appVersion: String,
    val appBuild: String,
    val developerOptionsPage: Boolean? = null,
    val phoneControlUnavailable: Boolean = false,
) {
    /** モデル：型番にメーカー名が含まれる場合のみ型番を記載します。 */
    val modelName: String get() = if (model.startsWith(manufacturer, ignoreCase = true)) model.trim() else "$manufacturer $model".trim()

    val arm64: Boolean get() = ARM64 in abis

    companion object {
        const val ARM64 = "arm64-v8a"
        /** 携帯電話操作のための最小システム バージョン (Android 12)。 */
        const val MIN_PHONE_SDK = 31
        /** Android 14 以降、仮想画面に独自のフォーカスを設定できるようになりました。 */
        const val OWN_FOCUS_SDK = 34
    }
}

/**
 * セルフテストに必要な Android 機能。インターフェースの実装はアプリ内にあります。単体テストは偽のものに置き換えられます。
 * 各項目はタイムアウトまたはユーザーのキャンセルにより中断される可能性があるため、時間内に停止できなければなりません。エラーをした場合は、直接投げて、実行の反対側で失敗を押すことができます。
 */
interface SelfCheckPort {
    /** 最初の「デバイス」ファクト (読み取り専用システム インターフェイス、非常に高速)。 */
    suspend fun facts(): DeviceFacts

    /** は 1 つのアイテムを実行します ([SelfCheck.DEVICE] 以外。そのアイテムは [facts] によって判断されます)。 */
    suspend fun check(check: SelfCheck): CheckOutcome

    /** 実行プロセス中に占有されていたすべてのもの (仮想画面、セルフテスト ページ、Web ページ タグ、アシスタント接続) を手放します。実行、キャンセル、エラー後に調整されます。複数回調整する場合もあります。 */
    suspend fun release()
}
