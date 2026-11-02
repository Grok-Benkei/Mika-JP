package com.wangzan101.mika.core.record

/** 走行記録の受信機。実装はディスク書き込みエラーを単独で飲み込みます。つまり、記録の失敗がタスクに影響を与えることはありません。 */
interface RunSink {
    fun event(e: RunEvent)

    /** [png] は、保存された画像 [mediaId] です。 */
    fun media(mediaId: Int, png: ByteArray) {}

    companion object {
        val NONE: RunSink = object : RunSink {
            override fun event(e: RunEvent) {}
        }
    }
}
