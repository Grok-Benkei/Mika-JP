package com.wangzan101.mika.cap.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SiteRegistryTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 1_000L

    private fun registry() = SiteRegistry(tmp.root, clock = { now })

    @Test
    fun orderAndPersistence() {
        val r = registry()
        r.visited("a.example.com"); now += 10
        r.visited("WWW.B.example.org"); now += 10
        r.visited("c.example.net"); now += 10
        r.markLoggedIn("a.example.com")
        val hosts = r.list().map { it.host }
        // 最初にログインしてから、最近のアクセスをクリックした人
        assertEquals(listOf("a.example.com", "c.example.net", "b.example.org"), hosts)
        val again = registry().list()
        assertEquals(hosts, again.map { it.host })
        assertTrue(again.first().loggedInByUser)
        assertEquals(1_000L, again.first().lastVisitMs)
    }

    @Test
    fun revisitUpdatesTimeAndRemoveForgets() {
        val r = registry()
        r.visited("a.example.com"); now += 10
        r.visited("b.example.com"); now += 10
        r.visited("a.example.com")
        assertEquals(listOf("a.example.com", "b.example.com"), r.list().map { it.host })
        r.remove("a.example.com")
        assertEquals(listOf("b.example.com"), registry().list().map { it.host })
    }

    @Test
    fun markLoggedInSurvivesRestart() {
        registry().markLoggedIn("shop.example.com")
        val e = registry().list().single()
        assertEquals("shop.example.com", e.host)
        assertTrue(e.loggedInByUser)
    }

    @Test
    fun ignoresBlankAndNonHosts() {
        val r = registry()
        r.visited("")
        r.visited("not a host")
        r.visited("https://x.example.com/path")
        assertEquals(listOf("x.example.com"), r.list().map { it.host })
    }

    @Test
    fun corruptFileMovedAsideNotOverwritten() {
        val f = tmp.root.resolve("sites.json")
        f.writeText("{ not json")
        val r = registry()
        assertTrue(r.list().isEmpty())
        r.visited("a.example.com")
        val aside = tmp.root.listFiles()!!.filter { it.name.startsWith("sites.json.corrupt-") }
        assertEquals(1, aside.size)
        assertEquals("{ not json", aside.single().readText())
        assertEquals(listOf("a.example.com"), registry().list().map { it.host })
    }

    @Test
    fun clearForgetsEverything() {
        val r = registry()
        r.visited("a.example.com")
        r.markLoggedIn("b.example.com")
        r.clear()
        assertTrue(registry().list().isEmpty())
    }

    @Test
    fun loginObservationsFollowThePage() {
        val r = registry()
        assertTrue(r.loginObserved("a.example.com", LoginState.SignedIn))
        assertTrue(registry().list().single().loggedInByUser)
        // は表示されず、移動しません。同じ結果は書き換えられません
        assertFalse(r.loginObserved("a.example.com", LoginState.Unknown))
        assertFalse(r.loginObserved("a.example.com", LoginState.SignedIn))
        // セッションの有効期限が切れました: セッションが終了したことがわかったら削除してください
        assertTrue(r.loginObserved("a.example.com", LoginState.SignedOut))
        assertFalse(registry().list().single().loggedInByUser)
        // 登録されていないサイトは終了したものとみなされ、作成されません。
        assertFalse(r.loginObserved("b.example.com", LoginState.SignedOut))
        assertEquals(listOf("a.example.com"), r.list().map { it.host })
    }

    @Test
    fun challengeLifecycle() {
        val r = registry()
        r.markLoggedIn("shop.example.com")
        r.challenged("shop.example.com", "access_blocked")
        // は再起動後も保持され、ログイン ステータスとアクセス時間は影響を受けません。
        val e = registry().list().single()
        assertTrue(e.challenged(now))
        assertEquals("access_blocked", e.challenge)
        assertTrue(e.loggedInByUser)
        now += 10
        r.visited("shop.example.com")
        assertEquals(setOf("shop.example.com"), registry().challengedSites())
        // ログインして確認してもクリアできません
        r.markLoggedIn("shop.example.com")
        assertEquals(setOf("shop.example.com"), r.challengedSites())
        // は有効期限が切れるとカウントされなくなります
        now += SiteRegistry.CHALLENGE_TTL_MS
        assertTrue(r.challengedSites().isEmpty())
        assertFalse(r.list().single().challenged(now))
        // が再度発生し、再計算されました。局データをクリア（登録テーブルから削除）したら消えてしまいました。
        r.challenged("shop.example.com", "captcha")
        assertEquals(setOf("shop.example.com"), r.challengedSites())
        r.remove("shop.example.com")
        assertTrue(registry().challengedSites().isEmpty())
    }

    @Test
    fun repeatedChallengeWithinTheRefreshWindowIsNotRewritten() {
        val r = registry()
        r.challenged("shop.example.com", "captcha")
        val file = tmp.root.resolve("sites.json")
        val first = file.readText()
        now += SiteRegistry.CHALLENGE_REFRESH_MS - 1
        r.challenged("shop.example.com", "captcha")
        assertEquals(first, file.readText())
        // は、タイプの変更後、またはリフレッシュ時間後に書き換えられました。
        r.challenged("shop.example.com", "access_blocked")
        assertEquals("access_blocked", registry().list().single().challenge)
        now += SiteRegistry.CHALLENGE_REFRESH_MS
        r.challenged("shop.example.com", "access_blocked")
        assertEquals(now, registry().list().single().challengedAtMs)
        assertEquals(setOf("captcha", "access_blocked"), SiteRegistry.CHALLENGE_KINDS)
    }
}
