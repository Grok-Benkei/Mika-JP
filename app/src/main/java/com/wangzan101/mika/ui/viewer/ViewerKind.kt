package com.wangzan101.mika.ui.viewer

/** 内蔵ビューアで表示できるいくつかのタイプのファイル。ここにないものは他のアプリに任せてください。 */
enum class ViewerKind { Html, Markdown, Pdf, Image, Text }

object ViewerKinds {
    private val images = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
    private val texts = setOf(
        "txt", "text", "log", "csv", "tsv", "json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
        "properties", "env", "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "sh", "bash", "zsh", "c", "h", "cpp",
        "hpp", "cc", "go", "rs", "rb", "php", "swift", "sql", "css", "scss", "gradle", "diff", "patch", "srt", "vtt",
    )
    private val imageMimes = images.map { if (it == "jpg") "image/jpeg" else "image/$it" }.toSet() + "image/x-ms-bmp"
    private val textMimes = setOf(
        "application/json", "application/xml", "application/javascript", "application/x-javascript", "application/x-yaml",
        "application/yaml", "application/x-sh", "application/toml", "application/sql",
    )

    /**
     * は、コンテンツ プロバイダーによって指定された MIME タイプに従って認識されます。認識できない場合（空、`application/octet-stream`、一般 `text/plain`）は拡張子を見てください。
     * は、HTML/Markdown/PDF/画像タイプを直接決定します。 `text/plain` の .md はマークダウンで表示されたままです。どちらも null を返しません。
     */
    fun of(mime: String?, name: String): ViewerKind? {
        val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        when {
            m == "text/html" || m == "application/xhtml+xml" -> return ViewerKind.Html
            m == "text/markdown" || m == "text/x-markdown" -> return ViewerKind.Markdown
            m == "application/pdf" -> return ViewerKind.Pdf
            m in imageMimes -> return ViewerKind.Image
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "html" || ext == "htm" || ext == "xhtml" -> ViewerKind.Html
            ext == "md" || ext == "markdown" -> ViewerKind.Markdown
            ext == "pdf" -> ViewerKind.Pdf
            ext in images -> ViewerKind.Image
            ext in texts -> ViewerKind.Text
            m.startsWith("image/") -> null
            m.startsWith("text/") || m in textMimes || m.endsWith("+json") || m.endsWith("+xml") -> ViewerKind.Text
            else -> null
        }
    }
}
