package com.wangzan101.mika.about

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.net.NetProxy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 今回は結果を手動で確認して更新します。自動チェックでは変更されません (失敗しても気にする必要はありません)。 */
enum class CheckStatus { Idle, Checking, Done, Failed }

/**
 * アップデートの状況を確認してください。 [latest] は見つかった最新バージョン (更新レコードに使用) で、[update] は新しいバージョン (赤い点があります) です。
 * [manualRounds] 手動チェックが完了するたびに 1 つ追加され、それに応じてページに「すでに最新です」や「接続できません」などのプロンプトが表示されます。
 */
data class UpdateState(
    val latest: ReleaseInfo? = null,
    val update: ReleaseInfo? = null,
    val status: CheckStatus = CheckStatus.Idle,
    val manualRounds: Int = 0,
) {
    val hasUpdate: Boolean get() = update != null
}

/**
 * はアプリを開くたびに自動的にチェックされ、連続するもののみがブロックされます。前回の自動チェック時よりも小さい値です。 [MIN_GAP_MS]（数回前後に切り替え、画面を回転）は再度チェックされません。
 * 前回の時刻は現在より遅い (システム時刻が変更されている) ため、期限切れとみなされます。
 */
object UpdateSchedule {
    const val MIN_GAP_MS = 2 * 60 * 1000L

    fun due(lastAttempt: Long?, now: Long): Boolean = lastAttempt == null || now - lastAttempt >= MIN_GAP_MS || now < lastAttempt
}

/**
 * は更新をチェックしますが、独自のサーバーはありません。GitHub リリース ページ インターフェイスを問い合わせるだけです。接続できない場合、フローが制限されている場合、または読み取れない場合は、今度は見つかりません。
 * 見つかったバージョンと最後の自動チェックの時刻は [file] です。アプリを再度開いた後も、赤い点はまだ表示されています。新しいバージョンをインストールすると、現在のバージョンの遅れはなくなり、赤い点が自然に消えます。
 * はここでバージョンのみをチェックします。ユーザーが開始したダウンロードとインストールは、[ApkUpdater] によって処理されます。
 */
class UpdateChecker(
    private val file: File,
    private val currentVersion: String,
    private val client: OkHttpClient = defaultClient(),
    private val releaseApi: String = MikaLinks.LATEST_RELEASE_API,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** でサポートされている ABI (Build.SUPPORTED_ABIS) は、リリースで適切な APK を選択するために使用されます。空の場合は、完全なアーキテクチャ パッケージが使用されます。 */
    private val deviceAbis: List<String> = emptyList(),
) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var lastAttempt: Long? = null
    private var loaded = false

    /** アプリを開くたびに、「有効期限が切れたらバックグラウンドで一度チェックしてください」が呼び出され、失敗すると音は鳴りません。 */
    fun autoCheckInBackground() {
        scope.launch { autoCheck() }
    }

    /** ユーザーが「アップデートの確認」をクリックしました。 */
    fun checkInBackground() {
        scope.launch { check(manual = true) }
    }

    /** は保存された結果を読み取り (1 回のみ読み取り)、赤い点はインターネット接続を待つ必要はありません。 */
    suspend fun load() = mutex.withLock { loadLocked() }

    /**
     * 有効期限が切れたら一度確認してください。今度は本当にチェックしたかどうかを確認してください。調査を開始する前に、時間を書き留めてください。同時に 2 回目に訪問するとき (調査はまだ終わっていません)、先ほど記録した時間が表示されます。
     * は再度チェックしません。
     */
    suspend fun autoCheck(): Boolean {
        val due = mutex.withLock {
            loadLocked()
            UpdateSchedule.due(lastAttempt, now()).also { if (it) lastAttempt = now() }
        }
        if (due) check(manual = false)
        return due
    }

    suspend fun check(manual: Boolean) {
        mutex.withLock {
            loadLocked()
            if (manual) _state.update { it.copy(status = CheckStatus.Checking) }
            val found = withContext(Dispatchers.IO) { fetchLatest() }
            if (found != null) {
                _state.update { it.withLatest(found) }
            }
            if (manual) _state.update { it.copy(status = if (found != null) CheckStatus.Done else CheckStatus.Failed, manualRounds = it.manualRounds + 1) }
            save()
        }
    }

    /** GitHub の最新リリース。見つからない場合は空です。 */
    internal fun fetchLatest(): ReleaseInfo? = runCatching { Releases.fromGitHub(get(releaseApi), deviceAbis) }.getOrNull()

    private fun get(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: error("bad update url")
        val loopback = com.wangzan101.mika.core.net.LocalHosts.isLoopback(parsed.host)
        if (!parsed.isHttps && !loopback) error("update check requires HTTPS")
        if (parsed.isHttps && parsed.host != "api.github.com") error("update check host is not allowed")
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mika/$currentVersion")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string() ?: error("empty body")
        }
    }

    private fun UpdateState.withLatest(r: ReleaseInfo?): UpdateState =
        copy(latest = r, update = r?.takeIf { AppVersion.isNewer(it.version, currentVersion) })

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val o = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return
        lastAttempt = (o["lastAttempt"] as? JsonPrimitive)?.longOrNull
        val latest = (o["latest"] as? JsonObject)?.let(Releases::fromJson)
        _state.update { it.withLatest(latest) }
    }

    private fun save() {
        val o = buildJsonObject {
            lastAttempt?.let { put("lastAttempt", it) }
            _state.value.latest?.let { put("latest", Releases.toJson(it)) }
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(o.toString())
                tmp.delete()
            }
        }
    }

    companion object {
        /** 一部のネットワークは、GitHub への接続時に高速な場合と低速な場合があります。タイムアウトを短縮し、失敗した場合はバックアップ ソースに切り替えます。設定 › ネットワーク プロキシに移動します。 */
        fun defaultClient(): OkHttpClient = NetProxy.shared.client(OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS))
    }
}
