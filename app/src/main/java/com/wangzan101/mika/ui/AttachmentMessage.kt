package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.ui.viewer.ViewerKind
import com.wangzan101.mika.ui.viewer.ViewerKinds

/**
 * ユーザーの言葉による添付ファイル (メッセージの最後に添付ファイルごとに 1 行、書き方については [AttachmentLines] を参照): 会話ページでは、添付ファイルが写真グリッドとファイル カードに適宜描画されます。
 * パスの行はユーザーには表示されません。
 */
object AttachmentMessage {
    /** 添付ファイル: ワークスペース内のパスとメッセージに記載されたサイズ (「2 KB」)。 */
    data class Item(val path: String, val size: String) {
        val name: String get() = path.substringAfterLast('/')
        /** 拡張子で写真を認識します。写真はグリッドに配置され、残りはファイルカードになります。 */
        val photo: Boolean get() = isPhoto(name)
    }

    /** はアンパックされた文です。出現順に [photos]、[files]、[words] は添付行以外（先頭と末尾の空白を削除）です。 アタッチメントラインを削除した後の */
    data class Parsed(val photos: List<Item>, val files: List<Item>, val words: String) {
        val hasAttachments: Boolean get() = photos.isNotEmpty() || files.isNotEmpty()
    }

    fun parse(text: String): Parsed {
        val p = AttachmentLines.parse(text)
        val items = p.files.map { (path, size) -> Item(path, size) }
        return Parsed(items.filter { it.photo }, items.filterNot { it.photo }, p.words)
    }

    /** 。添付ファイルのみ送信する場合はタイトル([label])となります。タイトルやサイドバーに使用します。 */
    fun plain(text: String): String {
        val p = parse(text)
        return if (!p.hasAttachments) text else p.words.ifEmpty { label(p) }
    }

    /** 添付ファイルのみを送信する場合の呼び出し先: 「写真」、「写真 3 枚」、「contract.pdf」、「contract.pdf とその他の 2 つのファイル」、「写真と文書」。 */
    fun label(p: Parsed): String = when {
        p.photos.isNotEmpty() && p.files.isNotEmpty() -> tr(R.string.attach_photos_and_files)
        p.photos.size == 1 -> tr(R.string.attach_photo)
        p.photos.isNotEmpty() -> trPlural(R.plurals.attach_n_photos, p.photos.size)
        p.files.size == 1 -> p.files[0].name
        p.files.isNotEmpty() -> tr(R.string.attach_file_and_more, p.files[0].name, p.files.size)
        else -> ""
    }

    fun isPhoto(name: String): Boolean = ViewerKinds.of(null, name) == ViewerKind.Image
}

/** 写真をグリッドに配置する方法: 1 枚の写真を大きなサイズ (比率を維持)、2 枚の写真を並べて、3 枚の写真を 3 列に配置します。さらに写真がある場合は、新しい行で囲みます。 */
object PhotoGrid {
    fun columns(count: Int): Int = when {
        count <= 1 -> 1
        count == 2 -> 2
        else -> 3
    }

    fun rows(count: Int): Int = if (count <= 0) 0 else (count + columns(count) - 1) / columns(count)
}

/** ファイル カードに下付き文字を入力します: 拡張子 (最大 4 文字) と色のグループ。 */
object FileBadge {
    enum class Kind { Pdf, Sheet, Doc, Slides, Archive, Other }

    fun text(name: String): String = ext(name).uppercase().take(4).ifEmpty { tr(R.string.attach_files) }

    fun kind(name: String): Kind = when (ext(name)) {
        "pdf" -> Kind.Pdf
        "xls", "xlsx", "csv", "tsv", "numbers", "ods" -> Kind.Sheet
        "doc", "docx", "txt", "md", "rtf", "pages", "odt" -> Kind.Doc
        "ppt", "pptx", "key", "odp" -> Kind.Slides
        "zip", "rar", "7z", "gz", "tar", "tgz" -> Kind.Archive
        else -> Kind.Other
    }

    private fun ext(name: String): String = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
}

/** 入力ボックスの上に送信する添付ファイル: [LIMIT] まで (写真とファイルは一緒にカウントされます)、同じアドレスは 1 回だけカウントされます。 */
object AttachTray {
    const val LIMIT = 9

    /** が追加されました。既存のアイテムはスキップされ、上限を超えたアイテムは収集されません。新しいリストが返され、次の番号は没収されます。 */
    fun add(current: List<PendingAttachment>, items: List<PendingAttachment>): Pair<List<PendingAttachment>, Int> {
        val out = current.toMutableList()
        var dropped = 0
        for (i in items) {
            if (out.any { it.uri == i.uri }) continue
            if (out.size >= LIMIT) dropped++ else out += i
        }
        return out to dropped
    }

    /** をクリックします。選択したものを削除し、選択されていないものを追加します (いっぱいの場合は追加しないでください。[Toggle.full] が true です)。 */
    fun toggle(current: List<PendingAttachment>, item: PendingAttachment): Toggle =
        if (current.any { it.uri == item.uri }) Toggle(current.filterNot { it.uri == item.uri }, full = false)
        else if (current.size >= LIMIT) Toggle(current, full = true)
        else Toggle(current + item, full = false)

    data class Toggle(val items: List<PendingAttachment>, val full: Boolean)

    /** 待機リスト内の写真の番号は何ですか (1 から始まり、写真のみを数えます)。選択されていない場合は null。写真グリッドの右上隅にあるシリアル番号。 */
    fun order(current: List<PendingAttachment>, uri: String): Int? =
        current.filter { it.photo }.indexOfFirst { it.uri == uri }.takeIf { it >= 0 }?.plus(1)

    /** には、コピー中に送信できない単語または添付ファイルがあります。 */
    fun canSend(text: String, items: List<PendingAttachment>, importing: Boolean): Boolean =
        (text.isNotBlank() || items.isNotEmpty()) && !importing
}
