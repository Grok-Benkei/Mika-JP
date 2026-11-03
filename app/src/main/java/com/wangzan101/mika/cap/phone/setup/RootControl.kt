package com.wangzan101.mika.cap.phone.setup

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.core.shell.HelperHandoff
import com.wangzan101.mika.core.shell.RootGrant
import com.wangzan101.mika.core.shell.RootStart
import com.wangzan101.mika.core.shell.SuAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * root 化済みの端末にある2つのスイッチ。どちらも既定はオフで、su が見つかるときだけ表示する。
 * 「root でスマホのヘルパーを起動」は、ウォッチドッグが su 経由で shell ユーザーとして起動する設定
 * （[com.wangzan101.mika.core.shell.BuiltinShellEngine]）。「Mika に root コマンドの実行を許可」はエージェント用。
 * オンにしたときに一度 su へ許可を求め、応答を保存する。拒否後はユーザーの再試行を待ち、自動では要求し直さない。
 * su への要求は IO スレッドで行う。root による起動可否が変わると [changed] でウォッチドッグに再評価を知らせる。
 */
class RootControl internal constructor(
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val changed: () -> Unit,
    private val findSu: () -> Boolean,
    private val ask: suspend (Int, Long) -> SuAnswer?,
) {
    constructor(context: Context, scope: CoroutineScope, changed: () -> Unit) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE), scope, changed,
        { RootShell.su() != null },
        { uid, timeout -> withContext(Dispatchers.IO) { RootShell.ask(uid, timeout) } },
    )

    private var commandsRevision = 0L

    /**
     * 設定画面の状態。[suPresent] は su の有無（なければグループ全体を非表示）、[start] と [commands] はスイッチ。
     * [startGrant] と [commandsGrant] は su の応答で、要求中は [RootGrant.Pending]。
     */
    data class State(
        val suPresent: Boolean = false,
        val start: Boolean = false,
        val startGrant: RootGrant = RootGrant.Unknown,
        val commands: Boolean = false,
        val commandsGrant: RootGrant = RootGrant.Unknown,
    )

    private val _state = MutableStateFlow(
        State(
            suPresent = false,
            start = prefs.getBoolean(KEY_START, false),
            startGrant = RootGrant.of(answer(KEY_START_ANSWER)),
            commands = prefs.getBoolean(KEY_COMMANDS, false),
            commandsGrant = RootGrant.of(answer(KEY_COMMANDS_ANSWER)),
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** 初回だけファイルの存在で su を探し、その後は [refresh] で更新する。許可要求はしない。 */
    @Volatile private var suFound: Boolean? = null

    private fun suPresent(): Boolean = suFound ?: findSu().also { found ->
        suFound = found
        _state.update { it.copy(suPresent = found) }
    }

    /** 起動時や設定画面を開いたときに su を再検出し、変更があればウォッチドッグに再評価させる。 */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            val before = suFound
            val found = findSu()
            suFound = found
            _state.update { it.copy(suPresent = found) }
            if (before != null && before != found && _state.value.start) changed()
        }
    }

    /** ウォッチドッグが参照する、root によるヘルパー起動の現在の可否。 */
    fun rootStart(): RootStart = RootStart.of(_state.value.start, suPresent(), answer(KEY_START_ANSWER))

    /** ヘルパー起動時の su の応答。null は、ユーザーの再試行に伴う前回の拒否の消去。 */
    fun setStartAnswer(a: SuAnswer?) {
        prefs.edit().putString(KEY_START_ANSWER, a?.name).apply()
        _state.update { it.copy(startGrant = RootGrant.of(a)) }
    }

    /** su 経由で起動しようとした際、su が見つからなくなったことを記録する。 */
    fun suGone() {
        if (suFound == false) return
        suFound = false
        _state.update { it.copy(suPresent = false) }
    }

    /**
     * root による起動を切り替える。オンにすると shell ユーザーへの切り替えを su に一度要求する。
     * 初回は許可画面が開き、許可されるとウォッチドッグが起動する。拒否・応答なし・UID 不一致は保存し、
     * スイッチはオンのままで、理由と再試行方法を設定画面に表示する。
     */
    fun setStart(on: Boolean) {
        prefs.edit().putBoolean(KEY_START, on).apply()
        _state.update { it.copy(start = on) }
        if (!on) {
            changed()
            return
        }
        askForStart()
    }

    /** ユーザーが再試行したときだけ、su に再度要求する。 */
    fun retryStart() {
        if (_state.value.start) askForStart()
    }

    private fun askForStart() {
        _state.update { it.copy(startGrant = RootGrant.Pending) }
        scope.launch {
            val a = ask(HelperHandoff.SHELL_UID, ASK_TIMEOUT_MS)
            if (a == null) suGone() else setStartAnswer(a)
            changed()
        }
    }

    /** ペア設定の解除では root 起動もオフにし、ウォッチドッグがヘルパーを復帰させないようにする。 */
    fun turnOffStart() {
        prefs.edit().putBoolean(KEY_START, false).apply()
        _state.update { it.copy(start = false) }
    }

    /** root ツールは、ユーザーがオンにし、su が許可済みで、su が現在も存在するときだけ使用できる。 */
    fun commandsAllowed(): Boolean = _state.value.commands && answer(KEY_COMMANDS_ANSWER) == SuAnswer.Granted && suPresent()

    /** root コマンドの許可を切り替える。オンにすると su に一度要求し、root として許可された場合だけ有効にする。 */
    @Synchronized
    fun setCommands(on: Boolean) {
        val revision = ++commandsRevision
        if (!on) {
            prefs.edit().putBoolean(KEY_COMMANDS, false).apply()
            _state.update { it.copy(commands = false, commandsGrant = RootGrant.of(answer(KEY_COMMANDS_ANSWER))) }
            return
        }
        _state.update { it.copy(commandsGrant = RootGrant.Pending) }
        scope.launch {
            val a = ask(0, ASK_TIMEOUT_MS)
            synchronized(this@RootControl) {
                // オフへの変更や新しい許可要求を、古い su の応答で取り消さない。
                if (commandsRevision != revision) return@synchronized
                if (a == null) suGone()
                val ok = a == SuAnswer.Granted
                prefs.edit().putBoolean(KEY_COMMANDS, ok).putString(KEY_COMMANDS_ANSWER, a?.name).apply()
                _state.update { it.copy(commands = ok, commandsGrant = RootGrant.of(a)) }
            }
        }
    }

    /** 実行時に su が拒否したらオフにして記録する。再要求はユーザーが次にオンにしたときだけ行う。 */
    @Synchronized
    fun commandsDenied(a: SuAnswer) {
        commandsRevision++
        prefs.edit().putBoolean(KEY_COMMANDS, false).putString(KEY_COMMANDS_ANSWER, a.name).apply()
        _state.update { it.copy(commands = false, commandsGrant = RootGrant.of(a)) }
    }

    private fun answer(key: String): SuAnswer? = prefs.getString(key, null)?.let { n -> SuAnswer.entries.firstOrNull { it.name == n } }

    private companion object {
        const val PREFS = "root"
        const val KEY_START = "start"
        const val KEY_START_ANSWER = "startAnswer"
        const val KEY_COMMANDS = "commands"
        const val KEY_COMMANDS_ANSWER = "commandsAnswer"
        /** ユーザーが許可画面を読んで操作できるよう、スイッチをオンにした際は最大60秒待つ。 */
        const val ASK_TIMEOUT_MS = 60_000L
    }
}
