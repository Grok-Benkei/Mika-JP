package com.wangzan101.mika.cap.web.cdp

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.wangzan101.mika.core.cap.web.cdp.CdpStream
import com.wangzan101.mika.core.cap.web.cdp.CdpTransport
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * このプロセスの WebView デバッグ ソケットに接続する 2 つの方法。エンドポイントは抽象的なソケット名です。
 * 読み書きの制限時間はプロトコル層で管理されます（その時点でフローがオフになり、ブロックされた読み書きが返されます）。ここにはソケットのタイムアウトはありません。
 */
internal class DirectTransport : CdpTransport {
    override fun open(endpoint: String): CdpStream {
        val s = LocalSocket()
        try {
            s.connect(LocalSocketAddress(endpoint, LocalSocketAddress.Namespace.ABSTRACT))
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        return object : CdpStream {
            override val input: InputStream = s.inputStream
            override val output: OutputStream = s.outputStream
            override fun close() {
                runCatching { s.shutdownInput() }
                runCatching { s.shutdownOutput() }
                s.close()
            }
        }
    }
}

/** は、他の人 (組み込みアシスタント、シェル ID): [relay] に代わって接続されています。接続が完了すると、ソケットが返されます。接続を代理する側が利用できない場合は、IOException をスローします。 */
internal class RelayTransport(
    private val relay: (String) -> ParcelFileDescriptor?,
) : CdpTransport {
    override fun open(endpoint: String): CdpStream {
        val pfd = try {
            relay(endpoint)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("phone helper could not relay: ${e.message}", e)
        } ?: throw IOException("phone helper refused or could not connect $endpoint")
        return FdStream(pfd)
    }
}

/** はソケット fd を返します。シャットダウンするときは、まずシャットダウンして、読み取りでブロックされていたスレッドを復帰させます。 */
private class FdStream(private val pfd: ParcelFileDescriptor) : CdpStream {
    override val input: InputStream = FileInputStream(pfd.fileDescriptor)
    override val output: OutputStream = FileOutputStream(pfd.fileDescriptor)

    override fun close() {
        runCatching { Os.shutdown(pfd.fileDescriptor, OsConstants.SHUT_RDWR) }
        pfd.close()
    }
}
