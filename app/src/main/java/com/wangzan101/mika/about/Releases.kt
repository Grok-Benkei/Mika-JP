package com.wangzan101.mika.about

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** リリースされたバージョン: バージョン番号 (v なし)、タイトル、アップデートの説明 (Markdown)、リリース日、リリース ページ、APK の直接リンク。 */
data class ReleaseInfo(
    val version: String,
    val name: String,
    /** のアップデート手順。 */
    val notes: String,
    /** 発売日 yyyy-MM-dd;ソースが書き込まれていない場合は空になります。 */
    val date: String?,
    val pageUrl: String,
    val apkUrl: String?,
    /** APK 添付ファイルのバイト数。インターフェースが提供されていない場合は空です。 */
    val apkSize: Long? = null,
    /** APK 添付ファイルの概要は、`sha256:<hex>` の形式になっています。以前の添付ファイルにはそれが含まれていないため、パッケージ名、バージョン番号、署名のみに依存します。 */
    val apkDigest: String? = null,
) {
    /** によって開かれたアドレス「GitHub からダウンロード」: APK への直接リンクがある場合は、APK をダウンロードします。そうでない場合は、リリース ページを開きます。 */
    val downloadUrl: String get() = apkUrl ?: pageUrl
}

/**
 * GitHub リリース ページ インターフェイスのバージョン情報 (リリース/最新) を読み取ります: tag_name、name、body、published_at、html_url、
 * と、アセット内のこのマシンに適した APK の browser_download_url ([pickApk] を参照)。
 */
object Releases {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * リリースでは、完全なアーキテクチャ `Mika-v<バージョン>.apk` に加えて、名前が `-<サフィックス>.apk` で終わる、特定のアーキテクチャのネイティブ ライブラリのみを含むパッケージも存在する場合があります。
     * キーは Android ABI 名で、値は添付ファイル名のサフィックスです。
     */
    private val ABI_APK_SUFFIX = mapOf("arm64-v8a" to "arm64")

    /** [deviceAbis] は、このマシンでサポートされている ABI であり、優先順位 (Build.SUPPORTED_ABIS) によって並べ替えられています。空の場合、完全なアーキテクチャ パッケージのみが取得されます。 */
    fun fromGitHub(text: String, deviceAbis: List<String> = emptyList()): ReleaseInfo {
        val o = json.parseToJsonElement(text).jsonObject
        val tag = o.str("tag_name") ?: error("no tag_name")
        val body = o.str("body").orEmpty()
        val apk = o["assets"]?.let { a ->
            runCatching { a.jsonArray }.getOrNull()?.map { it.jsonObject }?.let { pickApk(it, deviceAbis) }
        }
        val version = AppVersion.normalize(tag)
        return ReleaseInfo(
            version = version,
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: version,
            notes = body.trim(),
            date = o.str("published_at")?.take(10)?.takeIf { it.length == 10 },
            pageUrl = o.str("html_url") ?: "${MikaLinks.RELEASES}/tag/$tag",
            apkUrl = apk?.str("browser_download_url"),
            apkSize = apk?.strOrNumber("size")?.toLongOrNull(),
            apkDigest = apk?.str("digest"),
        )
    }

    /**
     * このマシンが特定のアーキテクチャをサポートしており、リリースにこのアーキテクチャ用の特別なパッケージがある場合は、特別なパッケージ (小さい方) を選択してください。それ以外の場合は、完全なアーキテクチャ パッケージを使用してください。
     * には、利用できない (インストールできない) 他のアーキテクチャ用の特別なパッケージのみがあり、リリース ページが開きます。
     */
    private fun pickApk(assets: List<JsonObject>, deviceAbis: List<String>): JsonObject? {
        val apks = assets.filter { it.str("name")?.endsWith(".apk", ignoreCase = true) == true }
        fun named(suffix: String) = apks.firstOrNull { it.str("name")!!.endsWith("-$suffix.apk", ignoreCase = true) }
        val forDevice = deviceAbis.firstNotNullOfOrNull { abi -> ABI_APK_SUFFIX[abi]?.let(::named) }
        val universal = apks.firstOrNull { a -> ABI_APK_SUFFIX.values.none { a.str("name")!!.endsWith("-$it.apk", ignoreCase = true) } }
        return forDevice ?: universal
    }

    /** 保存用: 見つかった最新バージョンを書き留めます。次回アプリを開いたときに赤い点が表示されたままになります。 */
    fun toJson(r: ReleaseInfo): JsonObject = buildJsonObject {
        put("version", r.version)
        put("name", r.name)
        put("notes", r.notes)
        r.date?.let { put("date", it) }
        put("pageUrl", r.pageUrl)
        r.apkUrl?.let { put("apkUrl", it) }
        r.apkSize?.let { put("apkSize", it) }
        r.apkDigest?.let { put("apkDigest", it) }
    }

    fun fromJson(o: JsonObject): ReleaseInfo? = runCatching {
        ReleaseInfo(
            version = o.str("version")!!, name = o.str("name") ?: o.str("version")!!, notes = o.str("notes").orEmpty(),
            date = o.str("date"), pageUrl = o.str("pageUrl") ?: MikaLinks.RELEASES, apkUrl = o.str("apkUrl"),
            apkSize = o.strOrNumber("apkSize")?.toLongOrNull(), apkDigest = o.str("apkDigest"),
        )
    }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strOrNumber(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
