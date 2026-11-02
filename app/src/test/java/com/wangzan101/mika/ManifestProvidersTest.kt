package com.wangzan101.mika

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** プロセスが開始されるとすぐにクラッシュするリスト内の構成は、最初にコンピューター上でブロックする必要があります (実マシンでのみ公開される種類)。 */
class ManifestProvidersTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test
    fun fileProvidersGrantUriPermissionsAndStayPrivate() {
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("manifest not found from ${File(".").absolutePath}", manifest.exists())
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val providers = doc.getElementsByTagName("provider")
        var fileProviders = 0
        for (i in 0 until providers.length) {
            val p = providers.item(i) as Element
            val metas = p.getElementsByTagName("meta-data")
            val isFileProvider = (0 until metas.length).any {
                (metas.item(it) as Element).getAttributeNS(androidNs, "name") == "android.support.FILE_PROVIDER_PATHS"
            }
            if (!isFileProvider) continue
            fileProviders++
            val name = p.getAttributeNS(androidNs, "name")
            // GrantUriPermissions が true でない場合、FileProvider.attachInfo は SecurityException をスローし、プロセスを開始できません。
            assertEquals("$name grantUriPermissions", "true", p.getAttributeNS(androidNs, "grantUriPermissions"))
            assertEquals("$name exported", "false", p.getAttributeNS(androidNs, "exported"))
        }
        assertTrue("expected at least one FileProvider", fileProviders > 0)
    }

    @Test
    fun helperHandoffProviderIsOnlyReachableWithShellLevelPermission() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val providers = doc.getElementsByTagName("provider")
        val p = (0 until providers.length).map { providers.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".shell.HelperHandoffProvider" }
        // シェルはそれを調整できる必要があります。通常のアプリにはこの権限がなく、プロバイダーを取得することさえできません。
        assertEquals("true", p.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", p.getAttributeNS(androidNs, "permission"))
        assertEquals("\${applicationId}.helper", p.getAttributeNS(androidNs, "authorities"))
    }

    /** コンピューター接続サービスは、adb シェルが開始できるようにするためだけに外部に公開されています。通常のアプリにはこの権限がないため、USB モードを開始したり切り替えたりすることはできません。 */
    @Test
    fun computerAccessServiceIsOnlyStartableWithShellLevelPermission() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val services = doc.getElementsByTagName("service")
        val s = (0 until services.length).map { services.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".mcp.McpService" }
        assertEquals("true", s.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", s.getAttributeNS(androidNs, "permission"))
        assertEquals("specialUse", s.getAttributeNS(androidNs, "foregroundServiceType"))
        // にはインテントフィルターがありません。コンポーネント名によってのみ明示的に開始できます。
        assertEquals(0, s.getElementsByTagName("intent-filter").length)
    }

    /** リストに登録されているすべてのコンポーネントは、クラスごとにフルネームで検索できます (コードが移動されたエントリは、同じ名前の薄いシェルのままです)。 */
    @Test
    fun everyComponentClassExists() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val names = listOf("application", "activity", "service", "receiver", "provider").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
        }
        assertTrue(names.size > 5)
        for (n in names) {
            val full = if (n.startsWith(".")) "com.wangzan101.mika$n" else n
            assertTrue("$full is not a class", runCatching { Class.forName(full, false, javaClass.classLoader) }.isSuccess)
        }
    }

    /**
     * テスト APK リストのページとサービスは、テスト パッケージ独自のプロセスで実行され、そこには Kotlin 標準ライブラリはありません (アプリとの重複した依存関係はテスト APK に含まれません)。
     * それぞれに同じ名前の Java ソース ファイルが必要です。クラス名が一致していなかったり、Kotlinと書かれていると、実機を開いた瞬間にクラッシュしてしまい、画面を読み込むと真っ白な画面しか読み込まれません。
     */
    @Test
    fun testApkComponentsAreJavaClassesWithMatchingNames() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/androidTest/AndroidManifest.xml"))
        val names = listOf("activity", "service", "receiver", "provider").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(androidNs, "name") }
        }
        assertTrue(names.isNotEmpty())
        for (n in names) {
            val path = "src/androidTest/java/${n.replace('.', '/')}"
            assertTrue("$n has no Java source at $path.java", File("$path.java").isFile)
        }
    }

    /**
     * カメラ アクセサリはシステム カメラを使用します。カメラが使用されると記載されていますが、必要ありません (カメラのないデバイスは通常どおりインストールできます)。このステートメントは削除できません:
     * 一部のシステムは、APK の特性に従ってアプリケーションを分類します。これがないと、Mika がゲームとして扱われ、リフレッシュ レートが制限される可能性があります。
     */
    @Test
    fun cameraIsDeclaredAsOptionalFeature() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val features = doc.getElementsByTagName("uses-feature")
        val camera = (0 until features.length).map { features.item(it) as Element }
            .singleOrNull { it.getAttributeNS(androidNs, "name") == "android.hardware.camera" }
        assertTrue("uses-feature android.hardware.camera is missing", camera != null)
        assertEquals("false", camera!!.getAttributeNS(androidNs, "required"))
    }

    /**
     * 互換性セルフテストのテスト ページは、組み込みアシスタント (シェル) が開くためにのみ公開されています。呼び出し元は INTERACT_ACROSS_USERS_FULL を保持する必要があり、通常のアプリでは開くことができません。
     * にはインテント フィルターがなく、コンポーネント名でのみ開くことができます。単一のタスクは最近のタスクには入力されません。アシスタントによって認識されたクラス名はリストと一致します。
     */
    @Test
    fun selfCheckTargetIsOnlyStartableWithShellLevelPermission() {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = doc.getElementsByTagName("activity")
        val a = (0 until activities.length).map { activities.item(it) as Element }
            .single { it.getAttributeNS(androidNs, "name") == ".selfcheck.SelfCheckTargetActivity" }
        assertEquals("true", a.getAttributeNS(androidNs, "exported"))
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", a.getAttributeNS(androidNs, "permission"))
        assertEquals(0, a.getElementsByTagName("intent-filter").length)
        assertEquals(com.wangzan101.mika.cap.phone.shell.SystemIntents.OWN_PAGE_CLASS, "com.wangzan101.mika" + a.getAttributeNS(androidNs, "name"))
        assertEquals("true", a.getAttributeNS(androidNs, "excludeFromRecents"))
        assertTrue(a.hasAttributeNS(androidNs, "taskAffinity"))
        assertEquals("", a.getAttributeNS(androidNs, "taskAffinity"))
    }

    /** 組み込みヘルパー プロセスは、このフルネームで開始されます。 */
    @Test
    fun helperEntryClassExists() {
        val c = Class.forName(com.wangzan101.mika.core.shell.HelperHandoff.HELPER_CLASS, false, javaClass.classLoader)
        assertTrue(c.methods.any { it.name == "main" && java.lang.reflect.Modifier.isStatic(it.modifiers) })
    }
}
