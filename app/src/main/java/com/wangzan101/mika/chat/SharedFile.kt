package com.wangzan101.mika.chat

/** 「Download/Mika」にエクスポートされたファイルがダイアログにファイルカードとして表示されます。 [uri] は MediaStore を開くために使用されるコンテンツ URI です。 */
data class SharedFile(val name: String, val size: Long, val savedAs: String, val uri: String)
