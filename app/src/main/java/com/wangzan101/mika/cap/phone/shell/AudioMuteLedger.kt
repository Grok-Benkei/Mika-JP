package com.wangzan101.mika.cap.phone.shell

import java.io.File

/**
 * アプリがミカによってミュートされました → ミュート前に PLAY_AUDIO モードになり、同時にファイルに落ちました: ミュート中に組み込みアシスタント プロセスが強制終了されました。
 * 電話機が再起動した後は、この記録を利用して次回起動時にサウンドを返すことができます。ミュートすると、録音され、正常に回復した後に削除されます。ファイルの読み書きに失敗した場合は、ファイルが返され、メモリにのみ記録されます。
 * レコード内のパッケージのみが復元され、他の人が設定した値には影響しません。記録されたキーについては、「[AppAudioKey]: 別のユーザーの単一アイテム」を参照してください。
 */
class AudioMuteLedger(private val file: File?) {
    private val modes = LinkedHashMap<String, String>()

    init {
        val text = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
        modes.putAll(parse(text))
    }

    fun contains(packageName: String): Boolean = packageName in modes
    operator fun get(packageName: String): String? = modes[packageName]
    fun packages(): List<String> = modes.keys.toList()

    fun put(packageName: String, mode: String) {
        modes[packageName] = mode
        save()
    }

    fun remove(packageName: String) {
        if (modes.remove(packageName) != null) save()
    }

    /** は、レコード内の各パッケージを [restoreMode] に復元します。 [write] は、true を返した後にのみレコードを削除します。失敗した場合はそのままにして、次回もう一度試してください。すべて復元されたかどうかを返します。 */
    fun restoreAll(write: (packageName: String, mode: String) -> Boolean): Boolean {
        packages().forEach { pkg -> if (write(pkg, restoreMode(modes.getValue(pkg)))) remove(pkg) }
        return modes.isEmpty()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            if (modes.isEmpty()) {
                f.delete()
            } else {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(format(modes))
                if (!tmp.renameTo(f)) error("rename failed")
            }
        }
    }

    companion object {
        private val LINE = Regex("""([A-Za-z0-9_.]+(?:@[0-9]+)?) ([a-z_]+)""")

        /**
         * リカバリ中のライトバック モード。明らかな場合は、allow/ignore/deny などがそのまま書き戻されることがわかります。設定されていない場合 (デフォルトで解析される)、allow と書き込みます。
         * Android のオーディオ システムは、サウンドを再生する前に PLAY_AUDIO のみを許可として認識します。明示的なデフォルトでは、アプリはサイレントになります。
         * は設定されておらず、古いシステムで採用されているデフォルト値が元々許可されています。書き込み許可後、`appops get`も「未設定」に戻ります。
         * レコードに表示されるデフォルトのソースは 2 つだけです。ミュート前に設定されていないか、古いバージョンが書き込まれた後に書き込まれました。許可するように変更しても、偶発的な損傷は発生しません。
         */
        fun restoreMode(prior: String?): String = if (prior.isNullOrEmpty() || prior == "default") "allow" else prior

        /** `appops get <pkg> PLAY_AUDIO` の出力: "PLAY_AUDIO:ignore; time=..." → "ignore"; 「操作はありません。」 → null (設定されていません)。 */
        fun parseSetMode(output: String): String? = Regex("""PLAY_AUDIO:\s*([a-z_]+)""").find(output)?.groupValues?.get(1)

        internal fun format(modes: Map<String, String>): String = modes.entries.joinToString("") { "${it.key} ${it.value}\n" }

        internal fun parse(text: String): Map<String, String> =
            text.lineSequence().mapNotNull { LINE.matchEntire(it.trim())?.destructured }
                .associate { (pkg, mode) -> pkg to mode }
    }
}

/**
 * ミュートキー。 PLAY_AUDIO は、「パッケージ + ユーザー」に従って個別に開かれます。元のコピー (Mika が存在するユーザー内) がパッケージ名です。
 * 別のユーザーにインストールされたコピー（クローン、作品情報）は「パッケージ名@ユーザー番号」です。各コピーの元の値は個別に記録され、復元されます。
 */
object AppAudioKey {
    private val KEY = Regex("""([A-Za-z0-9_.]+)(?:@([0-9]+))?""")

    /** [user] は空で、元の共有です。 */
    fun of(packageName: String, user: Int?): String = if (user == null) packageName else "$packageName@$user"

    /** 取得 (パッケージ名、ユーザー);元のユーザーは空です。未フォーマットは null です。 */
    fun parse(key: String): Pair<String, Int?>? =
        KEY.matchEntire(key)?.destructured?.let { (pkg, user) -> pkg to user.takeIf { it.isNotEmpty() }?.toIntOrNull() }
}
