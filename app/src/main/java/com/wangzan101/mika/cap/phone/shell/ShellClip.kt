package com.wangzan101.mika.cap.phone.shell

/**
 * は、システムのクリップボードに触れることなく、クリップボードをスワップアウトするか復元するかを判断できるため、単一のテストに便利です。
 * クリップボードの読み取りがシステムによってサイレントにブロックされる場合があります。取得されたコンテンツは空ですが、「コンテンツはありますか」と「コンテンツの説明」が表示されます。この時点では「空いてた」とは言えませんでした。
 * および「コンテンツはありますが表示できません」は読み取り不可として扱われ、空としてクリアまたは上書きすることはできません。
 */
internal object ShellClip {
    enum class Read {
        /** は確かに空です (コンテンツ、存在、説明はすべて「いいえ」です)。 */
        Empty,
        /** コンテンツを読み取ります。 */
        Has,
        /** にはコンテンツがありますが、読み取れません。 */
        Unreadable,
    }

    fun read(clipPresent: Boolean, hasPrimaryClip: Boolean, descriptionPresent: Boolean): Read = when {
        clipPresent -> Read.Has
        hasPrimaryClip || descriptionPresent -> Read.Unreadable
        else -> Read.Empty
    }

    /** は、現在のクリップボードの外観を復元します。 */
    enum class Now { Ours, Empty, Other, Unreadable }

    enum class Restore {
        /** ユーザーのオリジナルのものに戻します。 */
        PutBack,
        /** は空であることが判明し、ミカのものはクリアされます。 */
        Clear,
        /** 移動していません: ユーザーが途中で別のものをコピーしたか、現在それを読み取ることができません。 */
        Leave,
    }

    /**
     * [priorEmpty] は true です。これは、スワップアウト前に読み取られたものが実際に空であることを意味します (読み取れない場合は、まったくスワップアウトされません)。
     * クリップボードは他の人によってクリアされ (たとえば、システムが定期的に自動的にクリアします)、元のコンテンツが戻されます。空のクリップボードは、ユーザーが新たにコピーしたものではありません。
     * 元に戻しても、ユーザーのコンテンツは隠されません。元に戻さないと、ユーザーの元のコンテンツが失われます。
     */
    fun restore(priorEmpty: Boolean, now: Now): Restore = when (now) {
        Now.Ours -> if (priorEmpty) Restore.Clear else Restore.PutBack
        Now.Empty -> if (priorEmpty) Restore.Leave else Restore.PutBack
        Now.Other, Now.Unreadable -> Restore.Leave
    }
}
