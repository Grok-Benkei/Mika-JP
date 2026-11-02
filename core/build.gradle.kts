import com.google.protobuf.gradle.*

plugins {
    alias(libs.plugins.protobuf)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // サンドボックス パス コントラクト テストは testFixtures に配置され、アプリの AndroidSandbox も同じセットを実行します
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

val traceSchemas by configurations.creating { isTransitive = false }
val extractTraceSchemas by tasks.registering(Sync::class) {
    from({ zipTree(traceSchemas.singleFile) }) {
        include("opentelemetry/proto/common/v1/common.proto", "opentelemetry/proto/resource/v1/resource.proto",
            "opentelemetry/proto/trace/v1/trace.proto", "opentelemetry/proto/collector/trace/v1/trace_service.proto")
    }
    into(layout.buildDirectory.dir("trace-schemas"))
}
sourceSets.main { proto.srcDir(extractTraceSchemas) }
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    generateProtoTasks {
        all().configureEach { builtins { named("java") { option("lite") } } }
    }
}

dependencies {
    implementation(libs.protobuf.lite)
    traceSchemas(libs.otel.proto)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.commons.compress)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // テストでの TLS 1.3 とエクスポートされたキー: Android プラットフォームと同じ生成元を持つ TLS で実装され、テスト クラス パスのみを入力します。
    testImplementation(libs.conscrypt.openjdk.uber)
    testFixturesImplementation(libs.junit)
}

// 実行時にリソース ディレクトリを一覧表示できません: 構築時に、ナレッジ/アプリ、ナレッジ/サイト、およびスキルのヘッダーをスキャンし、検証後にインデックス リソースを生成します。検証後にビルドが失敗します。
val knowledgeIndex by tasks.registering(JavaExec::class) {
    val resources = file("src/main/resources")
    val out = layout.buildDirectory.dir("generated/knowledge-index")
    inputs.dir(resources.resolve("knowledge")).withPropertyName("knowledge")
    inputs.dir(resources.resolve("skills")).withPropertyName("skills")
    outputs.dir(out)
    classpath = sourceSets.main.get().output.classesDirs + configurations.runtimeClasspath.get()
    mainClass.set("com.wangzan101.mika.core.knowledge.KnowledgeIndex")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(resources.absolutePath, out.get().file("knowledge/index.json").asFile.absolutePath)
    })
}
sourceSets.main { resources.srcDir(knowledgeIndex) }

// README の変更リスト (組み込みナレッジ、サービス プロバイダーのデフォルト) はソースから再生成されます。testFixtures の ReadmeSections を参照してください。 ReadmeSectionsTest は両側の一貫性を維持します
val updateReadme by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "README.mdと旧翻訳パスのgeneratedマーカー間を日本語で再生成します"
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass.set("com.wangzan101.mika.core.docs.ReadmeSections")
    args(rootProject.projectDir.absolutePath)
    outputs.upToDateWhen { false }
}

// デスクトップ上の実際の OpenAI サービス「ChatGPT でログイン」(ログイン、列モデル、応答、ツール呼び出し、トークンの更新) について説明します。docs/dev/chatgpt-signin-check.md を参照してください。
// 個別のソース コード セット: コア製品もアプリもなし
val chatgptCheck by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[chatgptCheck.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[chatgptCheck.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

val chatgptSignInCheck by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "アプリの認証コードでChatGPTログインを検証します。-Ptimeout=<秒> -Pmodel=<slug> -Pkeep"
    classpath = chatgptCheck.runtimeClasspath
    mainClass.set("com.wangzan101.mika.core.chatgpt.check.ChatGptSignInCheckKt")
    args(listOfNotNull(
        providers.gradleProperty("timeout").orNull?.let { "--timeout=$it" },
        providers.gradleProperty("model").orNull?.let { "--model=$it" },
        if (providers.gradleProperty("keep").isPresent) "--keep" else null,
    ))
    outputs.upToDateWhen { false }
}

tasks.test {
    // 言語間の CLI コントラクトは、cliTest によって明示的に実行されます。純粋な JVM テストでは、コンピューターに Python がインストールされている必要はありません。
    exclude("**/RemoteCliContractTest*")
    // True Gateway の言語間テストは明示的に実行され、通常の JVM テストでは Go をインストールする必要はありません。
    exclude("**/RelayGatewayIntegrationTest*")
    // testFixtures は、コア jar をテスト クラス パスの前に置きます。ディレクトリを先頭に置くと、ディレクトリに従ってリソースを読み取るテストのみがファイルを見つけることができます
    classpath = sourceSets.main.get().output + classpath
    // TaskSetTest は、ウェアハウスのルート ディレクトリにある評価タスク セットを読み取ります
    inputs.dir(rootProject.file("eval")).withPropertyName("evalTasks")
    // DocsConsistencyTest と ReadmeTranslationsTest は、ウェアハウスのドキュメント (ルート ディレクトリ README.md、docs/readme/ にある各言語の翻訳は docs にあります) を読み取り、アプリのソース コードと比較します。ドキュメントまたはアプリのソース コードが変更された場合は、それを再実行する必要があります。
    inputs.files(rootProject.file("README.md"), rootProject.file("README.upstream.md"), rootProject.file("tools/sandbox/README.md")).withPropertyName("docsFiles")
    inputs.dir(rootProject.file("docs")).withPropertyName("docsDir")
    inputs.dir(rootProject.file("app/src")).withPropertyName("appSources")
    // updateReadme 最初に README を書き換え、同じビルドでテストして書き換えられたものを読み取ります
    mustRunAfter(updateReadme)
    // ループ シナリオのメッセージ シーケンス サンプル。 -PupdateSnapshots のときに再生成されます (LoopSnapshot を参照)
    systemProperty("mika.snapshotDir", file("src/test/resources/loop-snapshots").absolutePath)
    // プロンプト ワード サーフェスのゴールド サンプル (システム プロンプト ワード、ツール定義、フィードバック テキスト)。 -PupdateSnapshots を使用しても再生成されます (PromptSurface を参照)
    systemProperty("mika.promptSurfaceDir", file("src/test/resources/prompt-surface").absolutePath)
    val updateSnapshots = project.hasProperty("updateSnapshots")
    inputs.property("updateSnapshots", updateSnapshots)
    if (updateSnapshots) {
        systemProperty("mika.updateSnapshots", "true")
        outputs.upToDateWhen { false }
    }
}

val relayGatewayBinary = tasks.register<Exec>("relayGatewayBinary") {
    group = "verification"
    description = "言語間の統合テスト用にセルフホストのリモートMCPゲートウェイをビルドします"
    val output = layout.buildDirectory.file("relay-check/mika-relay")
    workingDir(rootProject.file("tools/relay"))
    inputs.files(fileTree(rootProject.file("tools/relay")) { include("*.go", "go.mod", "go.sum", "web/**") })
    outputs.file(output)
    environment("GOCACHE", rootProject.layout.buildDirectory.dir("go-cache").get().asFile.absolutePath)
    doFirst { output.get().asFile.parentFile.mkdirs() }
    commandLine("go", "build", "-trimpath", "-o", output.get().asFile.absolutePath, ".")
}

tasks.register<Test>("relayTest") {
    group = "verification"
    description = "GoゲートウェイとKotlinの端末トンネルで、リモートCLI / MCPの経路全体を検証します"
    dependsOn(relayGatewayBinary)
    inputs.file(layout.buildDirectory.file("relay-check/mika-relay")).withPropertyName("relayGatewayBinary")
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/RelayGatewayIntegrationTest*")
    systemProperty("mika.relayBinary", layout.buildDirectory.file("relay-check/mika-relay").get().asFile.absolutePath)
}

val cliContractTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Python CLIとKotlinタスクAPIの接続が/api/v1のみを使用することを検証します"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.main.get().output + sourceSets.test.get().runtimeClasspath
    include("**/RemoteCliContractTest*")
    inputs.dir(rootProject.file("tools/mika/scripts"))
    systemProperty("mika.python", providers.gradleProperty("mikaPython").getOrElse("python3"))
}

// には Python 3.10 以降が必要ですが、チェックでハングしません。必要に応じて ./gradlew :core:cliTest を明示的に実行します
val cliTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "CLIプロセス、仮のADB、独立スキル、実際のHTTPの契約をテストします（Python 3.10以降）"
    dependsOn(cliContractTest)
    workingDir(rootProject.projectDir)
    commandLine(providers.gradleProperty("mikaPython").getOrElse("python3"), "-m", "unittest", "discover", "-s", "tools/mika/tests", "-v")
}
