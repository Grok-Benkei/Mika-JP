package com.wangzan101.mika.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageHostsTest {
    private val hosts = PageHosts<String, String>()
    private fun host(name: String) = PageHosts.Host<String, String>(name)

    @Test
    fun viewGoesToTheHostThatCameToFrontLast() {
        val under = host("under")
        val top = host("top")
        hosts.open(under)
        hosts.started(under)
        hosts.setContainer(under, "c-under")
        assertSame(under, hosts.target())
        // は別のインターフェイスを開いてブラウザ ページで停止しました: 表示されているインターフェイスを表示します
        hosts.open(top)
        hosts.started(top)
        hosts.setContainer(top, "c-top")
        assertSame(top, hosts.target())
        // カバーされていたコンテナは後で再構築されたため、取得されませんでした。
        hosts.setContainer(under, null)
        hosts.setContainer(under, "c-under-2")
        assertSame(top, hosts.target())
        // 元に戻す
        hosts.started(under)
        assertSame(under, hosts.target())
    }

    @Test
    fun onlyTheLastHostLeavingClosesThePage() {
        val a = host("a")
        val b = host("b")
        hosts.open(a)
        hosts.open(b)
        hosts.started(a)
        hosts.started(b)
        hosts.setContainer(a, "ca")
        hosts.setContainer(b, "cb")
        assertFalse(hosts.close(b))
        assertNull(b.container)
        assertSame(a, hosts.target())
        assertTrue(hosts.close(a))
        assertNull(hosts.target())
    }

    @Test
    fun hostsWithoutContainerOrNotOpenAreSkipped() {
        val a = host("a")
        val b = host("b")
        // コンテナーは登録前に構築されます。登録前はカウントされません。
        hosts.setContainer(a, "ca")
        assertNull(hosts.target())
        hosts.open(a)
        assertSame(a, hosts.target())
        // はフォアグラウンドに戻りましたが、(開始ページ上に) コンテナーがありません: カウントされません
        hosts.open(b)
        hosts.started(b)
        assertSame(a, hosts.target())
        // はフロントデスクに戻ってきません。最後に来た人が優先されます。
        val c = host("c")
        hosts.open(c)
        hosts.setContainer(c, "cc")
        assertSame(c, hosts.target())
        hosts.close(c)
        assertSame(a, hosts.target())
    }
}
