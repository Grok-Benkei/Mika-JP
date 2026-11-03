package com.wangzan101.mika.cap.phone.shell

/**
 * システム インターフェイスがどのページがインテントを受信できるかを確認できない場合 (一部のシステムは、アプリケーション側のクエリで追加の設定を読み取り、シェル ID を読み取れない場合はエラーをスローします)、
 * は質問を `cmd package query-activities` に変更しました: クエリはシステム サービスで実行され、シェル ID のみが認識されます。 Android クラスに依存せず、単体テストを直接調整できます。
 */
internal object ActivityQuery {
    /** コマンド パラメーター: [action]、オプションの [type]、[uri]、[category]、[packageName] が制限されている場合にのみこのパッケージをチェックします。 [user] は、どのユーザー (デフォルトの現在のユーザー) を確認します。 */
    fun args(action: String, type: String? = null, uri: String? = null, category: String? = null, packageName: String? = null, user: String = "current"): List<String> =
        listOf("cmd", "package", "query-activities", "--brief", "--user", user, "-a", action) +
            listOfNotNull(type?.let { "-t" }, type) + listOfNotNull(uri?.let { "-d" }, uri) +
            listOfNotNull(category?.let { "-c" }, category) + listOfNotNull(packageName?.let { "-p" }, packageName)

    /** 出力から各ページの (パッケージ名、完全なクラス名) を取得します。パッケージ名には、ドットで始まる短いクラス名を入力します。 */
    fun parse(output: String): List<Pair<String, String>> = output.lineSequence().mapNotNull { line ->
        val m = COMPONENT.matchEntire(line.trim()) ?: return@mapNotNull null
        val (pkg, cls) = m.destructured
        pkg to if (cls.startsWith(".")) pkg + cls else cls
    }.distinct().toList()

    private val COMPONENT = Regex("""([A-Za-z][\w.]*)/([\w.$]+)""")
}
