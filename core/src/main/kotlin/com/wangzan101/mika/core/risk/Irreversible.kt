package com.wangzan101.mika.core.risk

/** 不可逆操作の種類（確認カード、判定結果、申告共通）。 */
@Suppress("EnumEntryName")
enum class IrrevAction { send, pay, delete, order, submit, other }

/**
 * ツールは、「不可逆かどうかを決定するもの」を宣言します。タップ要素または座標をクリックします。 FileControl ポイント ファイル コントロール (クリックと同じですが、画像を見ることで取得されるポイントではありません)。
 * Enter: 入力後に入力します。キーボタン;スワイプ (スライドして削除); Web ページ上でキーの組み合わせ、ドラッグ、ドロップダウン選択を操作します。
 */
enum class GateTarget { Tap, FileControl, Enter, Key, Swipe, Interact }
