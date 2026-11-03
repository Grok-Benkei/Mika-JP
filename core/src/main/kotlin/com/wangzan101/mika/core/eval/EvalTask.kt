package com.wangzan101.mika.core.eval

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class EvalCategory {
    @SerialName("read") Read,
    @SerialName("write") Write,
    @SerialName("irreversible") Irreversible,
    @SerialName("impossible") Impossible,
    @SerialName("barrier") Barrier,
}

@Serializable
enum class Expect {
    @SerialName("succeeded") Succeeded,
    @SerialName("failed") Failed,
    @SerialName("confirmation") Confirmation,
    @SerialName("takeover") Takeover,
}

/** 客観的なチェック項目。 JSONの「型」で区別します。 */
@Serializable
sealed interface Check {
    @Serializable @SerialName("answer_contains")
    data class AnswerContains(val all: List<String>) : Check

    @Serializable @SerialName("answer_regex")
    data class AnswerRegex(val pattern: String) : Check

    /** 回答項目はすべて、最後の数回の画面読み取りに表示される必要があります。 */
    @Serializable @SerialName("answer_grounded")
    data class AnswerGrounded(val minItems: Int = 1) : Check

    /** [packageName] は、`{app:<能力>}` ([APP_CAPABILITIES] を参照) と書くことができ、実行時にこの携帯電話のアプリに変更します。 */
    @Serializable @SerialName("screen_has")
    data class ScreenHas(val packageName: String, val text: String, val deepLink: String? = null) : Check

    /** プローブは、[PROBE_ALLOWLIST] 内の名前である必要があります。 */
    @Serializable @SerialName("probe_equals")
    data class ProbeEquals(val probe: String, val expected: String, val contains: Boolean = false) : Check

    /** このミッションは少なくとも [min] のメモリ (コードに記録されているmemory_used) で開始されました。 Used for the second question of memory questions. */
    @Serializable @SerialName("memory_used")
    data class MemoryUsed(val min: Int = 1) : Check

    /**
     * このタスクでは、エージェントはユーザー (ask_user) に質問しましたか: [expected] は、true の場合は質問している必要がありますが、false の場合は一度も質問していません。
     * 「聞いたほうがいいのか、自分で決めたほうがいいのか」ユーザーだけが知っている情報がターゲットに欠けているかどうかを尋ねる必要があります。好みが言及されていない場合は、常識に従って判断し、説明する必要があります。
     */
    @Serializable @SerialName("asked_user")
    data class AskedUser(val expected: Boolean = true) : Check
    /** タスクが完了したら、新しい Web ページ タブで [url] を開き、ページ全体を読んで [text] を見つけます (空白スペースは無視します)。 */
    @Serializable @SerialName("web_has")
    data class WebHas(val url: String, val text: String) : Check

    @Serializable @SerialName("confirmation_shows")
    data class ConfirmationShows(
        val action: String? = null,
        val target: String? = null,
        val content: String? = null,
        val amount: String? = null,
    ) : Check
}

/**
 * レビューの質問です。ゴール、マーカー、チェック項目の文字列は `{run}` および `{probe:<名字>}` として記述でき、実行前に [EvalTasks.substitute] に置き換えられます。
 * `probesBefore` は「プレースホルダー名→ホワイトリストプローブ名」です。期待値を取得するには、タスクを実行する前にこれらのプローブを実行します。
 * 、`apps`、screen_has のパッケージ名は、`{app:<能力>}` と書くことができます (携帯電話に付属する特定の種類のアプリ。[APP_CAPABILITIES] を参照)。 [EvalTasks.resolveApps] をこの電話機のパッケージ名に置き換えます。
 * タイトルは特定のブランドのパッケージ名を固定していません。
 *
 * `requires` は次のように記述されます: `installed:<包名>`; `app:<能力>` (この携帯電話には、この種のことを処理するためのアプリがあります);
 * `signedIn:<パッケージ名またはサイト>` (ユーザーがログイン済み: Web サイトの設定の「Web サイトログイン」で登録を確認し、アプリで手動マークを確認します。古い書き込み方法 `login:<パッケージ名>` および `site:<サイト>` は引き続き認識されます)。
 */
@Serializable
data class EvalTask(
    val id: String,
    val goal: String,
    val apps: List<String>,
    val category: EvalCategory,
    val requires: List<String> = emptyList(),
    val expect: Expect,
    val checks: List<Check>,
    val maxSteps: Int? = null,
    val timeoutSec: Int? = null,
    val marker: String? = null,
    val answers: Map<String, String> = emptyMap(),
    val probesBefore: Map<String, String> = emptyMap(),
    /** 審査員への指示（この質問がこのように行われる理由）、評価者は使用されません。 */
    val note: String? = null,
    /** マッチング質問: トレーニング (スキルを学ぶためのランニングのラウンド) またはテスト (比較のラウンド)。 `-e split` を押して評価者を選択します。 */
    val split: String? = null,
    /** 同じセッション内の質問は、同じ会話内の ID によって順番に実行されます (記憶に関する質問: 最初に質問してから使用します)。 */
    val session: String? = null,
    /** 再生評価のバリエーション：方法を変えたりパラメータを変えたりしても同じ。 `evalVariants on` の場合は、最初に元の質問を実行してルートを書き留めてから、これらを実行します。 */
    val variants: List<Variant> = emptyList(),
    /** 必須チャネル: 「ウェブ」または「アプリ」。空でない場合は、ターゲットの前に「Web ページを使用」/「アプリを使用」を追加します (2 つのルート間の比較のため)。 */
    val channel: String? = null,
    /**
     * 質問プール: 調整 (プロンプトの単語と知識を調整するときに読み取られる質問) またはホールドアウト (結果は公開を決定するときにのみ表示され、調整には使用されません)。評価者は `-e evalSplit` に従って選択されます。
     * と [split] (一致する質問のトレーニング/テスト ラウンド) は 2 つの異なるものです。
     */
    val partition: String? = null,
    /**
     * がこれらの障害物 (画面上で確認できます。[RunFacts.takeoverBarrier] を参照) に衝突し、ユーザーに引き渡されると、「ユーザーに引き渡された」([Scoring.handedOver]) として個別にリストされます。
     * is not considered a success or any other failure. It is up to people to decide whether it is considered a success or not. If it is empty, the default is selected by category ([Scoring.takeoverKinds]): the reading category question is login, and the others are not recognized.
     */
    val acceptTakeoverOn: List<String>? = null,
)

/**
 * 「携帯電話に付属している特定の種類のアプリ」は、Android 標準インテントに従って検索されます。 話が逸れる前に、これらのインテントを使用して、この携帯電話で処理できるアプリ (候補) を見つけます。 After
 * runs, the candidate actually opened by Agent will be given priority. If there is no standard intent (memo), you can only retrieve the app opened by the agent after running. The "Choose Which App to Use" interface of the
 */
data class AppIntent(val action: String, val category: String? = null)

private const val MAIN = "android.intent.action.MAIN"

val APP_CAPABILITIES: Map<String, List<AppIntent>> = mapOf(
    "alarm" to listOf(AppIntent("android.intent.action.SET_ALARM"), AppIntent("android.intent.action.SHOW_ALARMS")),
    "calendar" to listOf(AppIntent(MAIN, "android.intent.category.APP_CALENDAR")),
    "calculator" to listOf(AppIntent(MAIN, "android.intent.category.APP_CALCULATOR")),
    "contacts" to listOf(AppIntent(MAIN, "android.intent.category.APP_CONTACTS")),
    "files" to listOf(AppIntent(MAIN, "android.intent.category.APP_FILES"), AppIntent("android.intent.action.OPEN_DOCUMENT_TREE")),
    "weather" to listOf(AppIntent(MAIN, "android.intent.category.APP_WEATHER")),
    "notes" to emptyList(),
)

object AppCapabilities {
    private val COMPONENT = Regex("^\\s*([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/\\S+")
    /** system is not a candidate. */
    private const val SYSTEM_PACKAGE = "android"

    /** `cmd package resolve-activity --brief` / `query-activities --brief` The package names in the output are deduplicated in the order of appearance. */
    fun packagesIn(output: String): List<String> =
        output.lines().mapNotNull { COMPONENT.find(it)?.groupValues?.get(1) }.filter { it != SYSTEM_PACKAGE }.distinct()

    /** Check the shell command of an Intent (the parameters come from [APP_CAPABILITIES], the task file cannot be written). */
    fun commands(i: AppIntent): List<String> {
        val args = "-a ${i.action}" + (i.category?.let { " -c $it" } ?: "")
        return listOf("cmd package resolve-activity --brief $args", "cmd package query-activities --brief $args")
    }

    /**
     * `{app:<能力>}` を実行した後、どのパッケージを使用するか: エージェントによって開かれた候補が優先され (電話機には複数のカレンダーがある場合があります)、最初の候補が続きます。
     * 候補がない場合 (標準インテントがない、またはこの電話で見つからない場合)、エージェントが入力しているときにフォアグラウンドにあるアプリ ([written]、[RunFacts.appsWrittenIn] を参照) のみが認識されます。
     * そして、それは 1 つだけです。 I can’t say which one it is (it counts as not found), and the “first opened App” is not included to make up the number. [own] は、Mika 自身のパッケージ名です。 The value of
     */
    fun choose(candidates: List<String>, launched: List<String>, own: String? = null, written: List<String> = emptyList()): String? {
        val used = launched.filter { it != own && it != SYSTEM_PACKAGE }
        candidates.firstOrNull { it in used }?.let { return it }
        candidates.firstOrNull()?.let { return it }
        return written.filter { it != own && it != SYSTEM_PACKAGE }.distinct().singleOrNull()
    }
}

/** [EvalTask.channel] and the statement added before the target. */
val CHANNEL_PREFIX = mapOf("web" to "ウェブで", "app" to "アプリで")

/**
 * 原題のバリエーション。 If [checks] is empty, the check items of the original question will be used.パラメータを変更する場合、パラメータは [goal] でそのまま書き出す必要があります (再生では、元の単語に表示されるスロット値のみが認識されます)。
 * The remaining fields (App, category, number of steps, time limit, tags, etc.) follow the original title.
 */
@Serializable
data class Variant(val goal: String, val checks: List<Check>? = null)

/** read-only device probe: name → shell command. The evaluator only executes the commands listed here, and the task file cannot write arbitrary commands. */
val PROBE_ALLOWLIST: Map<String, String> = mapOf(
    "android_release" to "getprop ro.build.version.release",
    "android_sdk" to "getprop ro.build.version.sdk",
    "device_model" to "getprop ro.product.model",
    "device_manufacturer" to "getprop ro.product.manufacturer",
    "timezone" to "getprop persist.sys.timezone",
    "date_today" to "date +%Y-%m-%d",
    "time_12_24" to "settings get system time_12_24",
    "font_scale" to "settings get system font_scale",
    "airplane_mode_on" to "settings get global airplane_mode_on",
    "wifi_on" to "settings get global wifi_on",
    "bluetooth_on" to "settings get global bluetooth_on",
    "auto_time" to "settings get global auto_time",
    "default_browser" to "cmd package resolve-activity --brief -a android.intent.action.VIEW -d https://example.com",
    "default_home" to "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
    /** は、ファイルとフォルダーを内部的にルート ディレクトリに保存します (フォルダー作成に関する質問を確認するために使用され、ファイル管理アプリには依存しません)。 */
    "storage_root" to "ls -1 /sdcard/",
)

object EvalTasks {
    private val json = Json { explicitNulls = false }
    private val ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val SPLITS = setOf("train", "test")
    /** [EvalTask.partition] の値。 */
    val PARTITIONS = setOf("tune", "holdout")
    private val PROBE_REF = Regex("""\{probe:([^}]*)\}""")
    private val APP_REF = Regex("""\{app:([^}]*)\}""")
    private val REQUIREMENT_KINDS = setOf("installed", "login", "site", "signedIn", "app")

    /** 課題の中で使われる `{app:<能力>}`。 */
    fun appCapabilities(t: EvalTask): Set<String> =
        APP_REF.findAll(json.encodeToString(EvalTask.serializer(), t)).map { it.groupValues[1] }.toSet()

    /** `{app:<能力>}` を [apps] の中のパッケージ名に置き換える（能力 → パッケージ名）; [apps] にない能力はそのまま残し、`apps` 一覧の中で置き換えられなかったものは取り除く。 */
    fun resolveApps(t: EvalTask, apps: Map<String, String>): EvalTask {
        fun s(x: String) = APP_REF.replace(x) { m -> apps[m.groupValues[1]] ?: m.value }
        return t.copy(
            apps = t.apps.map(::s).filterNot { APP_REF.containsMatchIn(it) }.distinct(),
            checks = t.checks.map { c -> if (c is Check.ScreenHas) c.copy(packageName = s(c.packageName)) else c },
        )
    }

    /** 対象の前にチャネルの言い方（[CHANNEL_PREFIX]）を付ける; 対象が既にそう言っている場合は（空白を数えず）付けない。 */
    fun withChannel(goal: String, channel: String?): String {
        val prefix = channel?.let { CHANNEL_PREFIX[it] } ?: return goal
        fun squash(x: String) = x.filterNot(Char::isWhitespace)
        val legacy = when (channel) { "web" -> "用网页"; "app" -> "用 App"; else -> "" }
        return if (squash(prefix) in squash(goal) || legacy.isNotEmpty() && squash(legacy) in squash(goal)) goal else prefix + goal
    }

    fun parse(json: String): EvalTask = this.json.decodeFromString(EvalTask.serializer(), json)

    /** 元の課題の各バリエーション。番号は `<元の課題>~v<n>`（1から）; バリエーションがなければ空を返す。 */
    fun variantsOf(t: EvalTask): List<EvalTask> = t.variants.mapIndexed { i, v ->
        t.copy(id = "${t.id}~v${i + 1}", goal = v.goal, checks = v.checks ?: t.checks, variants = emptyList())
    }

    /** 単一の質問の合法性 (バリエーションを含む)。空のリスト = 合法。 */
    fun validate(t: EvalTask): List<String> {
        val errs = mutableListOf<String>()
        // バリアントは、元のタイトル番号に従ってチェックされます。バリアントの数はエラーの前にマークされています。
        variantsOf(t).forEachIndexed { i, v -> validate(v.copy(id = t.id)).forEach { errs += "${t.id}: variant[${i + 1}]: $it" } }
        if (!ID.matches(t.id)) errs += "id「${t.id}」は英小文字・数字・ハイフンで指定してください"
        if (t.goal.isBlank()) errs += "${t.id}: goalが空です"
        t.channel?.let { if (it !in CHANNEL_PREFIX) errs += "${t.id}: channelに指定できる値: ${CHANNEL_PREFIX.keys.joinToString("/")}" }
        t.maxSteps?.let { if (it <= 0) errs += "${t.id}: maxStepsは正の数にしてください" }
        t.split?.let { if (it !in SPLITS) errs += "${t.id}: splitはtrainまたはtestにしてください" }
        t.partition?.let { if (it !in PARTITIONS) errs += "${t.id}: partitionはtuneまたはholdoutにしてください" }
        t.session?.let { if (!ID.matches(it)) errs += "${t.id}: session「$it」は英小文字・数字・ハイフンで指定してください" }
        t.timeoutSec?.let { if (it <= 0) errs += "${t.id}: timeoutSecは正の数にしてください" }
        t.requires.forEach { r ->
            val kind = r.substringBefore(':')
            if (kind !in REQUIREMENT_KINDS || r.substringAfter(':', "").isBlank()) errs += "${t.id}: 未知の前提条件「$r」です"
            if (kind == "app" && r.substringAfter(':') !in APP_CAPABILITIES) errs += "${t.id}: 前提条件「$r」は既知のアプリ機能ではありません"
        }
        appCapabilities(t).forEach { if (it !in APP_CAPABILITIES) errs += "${t.id}: {app:$it}は既知のアプリ機能ではありません" }
        t.probesBefore.forEach { (name, probe) ->
            if (probe !in PROBE_ALLOWLIST) errs += "${t.id}: probesBefore.${name}に許可されていないプローブ「$probe」が指定されています"
        }
        if (t.checks.isEmpty() && (t.expect == Expect.Succeeded || t.expect == Expect.Confirmation)) {
            errs += "${t.id}: expect=${t.expect}には1件以上の検証項目が必要です"
        }
        t.checks.forEachIndexed { i, c ->
            when (c) {
                is Check.ProbeEquals -> {
                    if (c.probe !in PROBE_ALLOWLIST) errs += "${t.id}: checks[$i]に許可されていないプローブ「${c.probe}」が指定されています"
                    if (c.expected.isBlank()) errs += "${t.id}: checks[$i]のexpectedが空です"
                }
                is Check.AnswerGrounded -> if (c.minItems < 1) errs += "${t.id}: checks[$i]のminItemsは1以上にしてください"
                is Check.AnswerRegex ->
                    if (c.pattern.isEmpty()) errs += "${t.id}: checks[$i]のpatternが空です"
                    else runCatching { Regex(c.pattern) }
                        .onFailure { errs += "${t.id}: checks[$i]の正規表現が無効です:${it.message}" }
                        // 空の文字列に一致する規則的なパターンは、どの答えにも当てはまります。
                        .onSuccess { if (it.containsMatchIn("")) errs += "${t.id}: checks[$i]の正規表現は空文字列に一致するため、検証に使えません" }
                is Check.AnswerContains ->
                    if (c.all.isEmpty() || c.all.any { it.isBlank() }) errs += "${t.id}: checks[$i]のallが空か、空文字列を含んでいます"
                is Check.ConfirmationShows -> {
                    val fields = listOf(c.action, c.target, c.content, c.amount)
                    if (fields.all { it == null }) errs += "${t.id}: checks[$i]に検証対象のフィールドが指定されていません"
                    if (fields.any { it != null && it.isBlank() }) errs += "${t.id}: checks[$i]に空白のフィールドがあります"
                }
                is Check.ScreenHas -> if (c.text.isBlank()) errs += "${t.id}: checks[$i]のtextが空です"
                is Check.MemoryUsed -> if (c.min < 1) errs += "${t.id}: checks[$i]のminは1以上にしてください"
                is Check.AskedUser -> Unit
                is Check.WebHas -> {
                    val u = c.url.trim().lowercase()
                    if (!u.startsWith("https://") && !u.startsWith("http://")) errs += "${t.id}: checks[$i]のurlはhttp(s)のURLにしてください"
                    if (c.text.isBlank()) errs += "${t.id}: checks[$i]のtextが空です"
                }
            }
        }
        val referenced = PROBE_REF.findAll(json.encodeToString(EvalTask.serializer(), t)).map { it.groupValues[1] }.toSet()
        (referenced - t.probesBefore.keys).forEach { errs += "${t.id}: プレースホルダー{probe:$it}がprobesBeforeに定義されていません" }
        return errs
    }

    /** タスク セット全体: 各質問を確認し、ID が繰り返されているかどうかを確認します。 */
    fun validateAll(ts: List<EvalTask>): List<String> =
        ts.flatMap { validate(it) } +
            ts.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.map { "id「$it」が重複しています" }

    /** は `{run}` および `{probe:<名字>}` を置き換えます。 probesはprobesBeforeの実行結果（地名→出力）です。 */
    fun substitute(t: EvalTask, run: String, probes: Map<String, String>): EvalTask {
        fun s(x: String): String = PROBE_REF.replace(x.replace("{run}", run)) { m ->
            probes[m.groupValues[1]]?.trim() ?: m.value
        }
        // 正規表現に挿入されたプローブ値は文字通りに扱われます
        fun re(x: String): String = PROBE_REF.replace(x.replace("{run}", Regex.escape(run))) { m ->
            probes[m.groupValues[1]]?.let { Regex.escape(it.trim()) } ?: Regex.escapeReplacement(m.value)
        }
        val checks = t.checks.map { c ->
            when (c) {
                is Check.AnswerContains -> c.copy(all = c.all.map(::s))
                is Check.AnswerRegex -> c.copy(pattern = re(c.pattern))
                is Check.AnswerGrounded -> c
                is Check.MemoryUsed -> c
                is Check.AskedUser -> c
                is Check.ScreenHas -> c.copy(text = s(c.text), deepLink = c.deepLink?.let(::s))
                is Check.WebHas -> c.copy(url = s(c.url), text = s(c.text))
                is Check.ProbeEquals -> c.copy(expected = s(c.expected))
                is Check.ConfirmationShows -> Check.ConfirmationShows(
                    c.action?.let(::s), c.target?.let(::s), c.content?.let(::s), c.amount?.let(::s),
                )
            }
        }
        return t.copy(
            goal = withChannel(s(t.goal), t.channel),
            marker = t.marker?.let(::s),
            checks = checks,
            answers = t.answers.mapValues { s(it.value) },
        )
    }
}
