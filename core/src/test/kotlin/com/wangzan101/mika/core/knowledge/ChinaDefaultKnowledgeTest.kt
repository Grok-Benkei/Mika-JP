package com.wangzan101.mika.core.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChinaDefaultKnowledgeTest {
    private val chineseApps = setOf("baijiahao", "bilibili", "boss-zhipin", "dedao", "douyin", "feishu", "jike", "kuaishou", "qqmail", "toutiao", "wechat", "weibo", "xianyu", "xiaohongshu", "zhihu")
    private val chineseSites = setOf("bilibili.com", "douban.com", "douyin.com", "tieba.baidu.com", "weibo.com", "weixin.sogou.com", "xiaohongshu.com", "xiaoyuzhoufm.com", "zhihu.com", "zhipin.com")

    @Test fun defaultCatalogDoesNotRecommendChineseAppsOrSites() {
        val catalog = GuideCatalog.loadBundled()
        assertFalse(catalog.rows.any { it.kind == KnowledgeIndex.APP && it.name in chineseApps })
        assertFalse(catalog.rows.any { it.kind == KnowledgeIndex.SITE && it.name in chineseSites })
        assertTrue(catalog.find("WeChat", kind = KnowledgeIndex.APP).isEmpty())
        assertTrue(catalog.find("zhihu.com", kind = KnowledgeIndex.SITE).isEmpty())
        val store = KnowledgeStore(KnowledgeStore.loadBundled())
        assertNull(store.packageFor("微信"))
        assertNull(store.byDomain("www.xiaohongshu.com"))
        assertTrue(catalog.hasRoute("line", "android"))
        assertTrue(catalog.hasRoute("yahoo.co.jp", "browser"))
    }

    @Test fun optionalSourcesAreAbsentFromTheDefaultClasspath() {
        val loader = KnowledgeStore::class.java.classLoader
        for (name in chineseApps) assertNull(name, loader.getResource("knowledge/apps/$name/APP.md"))
        for (name in chineseSites) assertNull(name, loader.getResource("knowledge/sites/$name/SITE.md"))
    }

    @Test fun oldForeignDataCanStillBeTestedByExplicitOptIn() {
        val optional = OptionalChinaKnowledge.catalog()
        assertEquals(chineseApps, optional.rows.filter { it.kind == KnowledgeIndex.APP && it.name in chineseApps }.map { it.name }.toSet())
        assertEquals(chineseSites, optional.rows.filter { it.kind == KnowledgeIndex.SITE && it.name in chineseSites }.map { it.name }.toSet())
        assertTrue(optional.hasRoute("wechat", "android"))
        assertTrue(optional.hasRoute("zhihu.com", "browser"))
    }
}
