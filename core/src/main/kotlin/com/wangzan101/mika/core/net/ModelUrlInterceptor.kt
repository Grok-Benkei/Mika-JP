package com.wangzan101.mika.core.net

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** 許可されないモデル宛先。メッセージには URL も鍵も含めない。 */
class BlockedEndpoint(val reason: String) : IOException("blocked endpoint: $reason")

/**
 * モデル用クライアントに付けるアプリケーションインターセプタ。
 * クライアント側の自動リダイレクトは切っておく。ここで次の URL を検査してから追う。
 * https から http へのダウングレードは、接続する前に拒否する。
 */
class ModelUrlInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request().url.toString()
        var request = chain.request()
        var hops = 0
        while (true) {
            val now = request.url.toString()
            val allowLan = CleartextPolicy.allowLanHttp()
            val verdict = if (now == original) EndpointUrlPolicy.checkModel(now, allowLan)
                else EndpointUrlPolicy.checkRedirect(original, now, allowLan)
            if (!verdict.allowed) throw BlockedEndpoint(verdict.reason)
            val rewritten = EndpointUrlPolicy.loopbackHttp(request.url)
            if (rewritten != request.url) request = request.newBuilder().url(rewritten).build()
            val response = chain.proceed(request)
            if (response.code !in 300..399) return response
            val location = response.header("Location")
            response.close()
            if (location.isNullOrBlank() || hops++ >= MAX_REDIRECTS) throw BlockedEndpoint("bad_redirect")
            val next = request.url.resolve(location) ?: throw BlockedEndpoint("bad_url")
            request = request.newBuilder().url(next).build()
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 5
    }
}
