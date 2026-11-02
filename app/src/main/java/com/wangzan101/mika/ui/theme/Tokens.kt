package com.wangzan101.mika.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** のフォント サイズとウェイト。サイズ、フォントの太さ、行の高さのみが決まり、色は使用される場所によって決まります。 */
object MikaType {
    /** タイトル: 空のステータス、ようこそページ。 */
    val LargeTitle = TextStyle(fontSize = 28.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold)
    /** ページ タイトル: トップバー。 */
    val Title = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    /** テキスト、行タイトル。 */
    val Body = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    /** 強調する必要があるカードのタイトルとその他のテキスト。 */
    val BodyStrong = TextStyle(fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    /** の説明テキスト。 */
    val Caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)
    /** グループのサブタイトル。 */
    val Label = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    /** プロンプト、フッター。 */
    val Hint = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal)
    /** 行末の値。 */
    val Value = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
}

/** ピッチ。ページの左右に [Page] を残します。 */
object MikaSpace {
    val S4 = 4.dp
    val S8 = 8.dp
    val S12 = 12.dp
    val S16 = 16.dp
    val S20 = 20.dp
    val S24 = 24.dp
    val Page = 16.dp
}

/** さまざまな要素の寸法。 */
object MikaSize {
    val TopBar = 56.dp
    val IconButton = 40.dp
    val RowMin = 52.dp
    val Button = 52.dp
    val Composer = 52.dp
    val Icon = 22.dp
    /** リスト行の左側にあるアイコン ブロック、内部のグラフィックは [TileIcon] です。 */
    val Tile = 30.dp
    val TileIcon = 18.dp
    /** 行末矢印などの小さなアイコン。 */
    val SmallIcon = 16.dp
    const val IconStroke = 1.7f
}

/** 角が丸い。 */
object MikaRadius {
    val Card = 16.dp
    val Bubble = 20.dp
    val BubbleTail = 6.dp
    val Button = 14.dp
    val Tile = 9.dp
}

/** 一般的に使用される形状。最上位の定数。再編成中に新たに作成されたものではありません。 */
object MikaShapes {
    val Card = RoundedCornerShape(MikaRadius.Card)
    /** ユーザー メッセージ バブル: 右下隅は末尾の隅です。 */
    val UserBubble = RoundedCornerShape(MikaRadius.Bubble, MikaRadius.Bubble, MikaRadius.BubbleTail, MikaRadius.Bubble)
    /** ミカの答え: 左下隅は尻尾の隅で、ユーザー バブルと対称です。 */
    val AnswerBubble = RoundedCornerShape(MikaRadius.Bubble, MikaRadius.Bubble, MikaRadius.Bubble, MikaRadius.BubbleTail)
    val Button = RoundedCornerShape(MikaRadius.Button)
    val Tile = RoundedCornerShape(MikaRadius.Tile)
    val Pill = CircleShape
}
