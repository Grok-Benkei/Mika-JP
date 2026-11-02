plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// About ページのビルド番号: 現在のコミットの短いハッシュ。 git がない場合 (ソース パッケージからコンパイルする場合など)、dev を書き込みます。
val gitHash: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "dev"

android {
    namespace = "com.wangzan101.mika"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.studioyebisu.mika"
        minSdk = 30
        targetSdk = 35
        versionCode = 34
        versionName = "0.1.33-jp.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        // 「バージョン情報」ページのバージョン番号の後のベータ マーク。正式版がリリースされたら false に変更します
        buildConfigField("boolean", "BETA", "true")
    }

    // サンドボックス プログラムには arm64 バージョン (jniLibs/arm64-v8a) のみがあります。 APK は ABI に限定されません。他のデバイスは通常どおりインストールでき、「このデバイスはサンドボックスをサポートしていません」と表示されます。
    // サンドボックス プログラムは、nativeLibraryDir から直接実行する必要があり、インストール中にディスクを取得する必要があります。
    packaging { jniLibs { useLegacyPackaging = true } }

    // は、アーキテクチャに従って別のパッケージ (perf、release) をリリースします。完全なアーキテクチャ パッケージに加えて、arm64-v8a ネイティブ ライブラリのみを含む小さなパッケージもあります。
    // 2 つのパッケージ コード、リソース、バージョン番号、および署名は、lib/ 下の他のアーキテクチャを除いて同じです。それぞれは完全な APK であり、一緒にインストールされる分割パッケージではありません。
    // 製品: app-universal-<タイプ>.apk、app-arm64-v8a-<タイプ>.apk。分割はすべてのビルド タイプに有効です。
    // したがって、このビルドに perf/release パッケージが必要な場合にのみ開かれます。デバッグ パッケージが個別にビルドされた場合、製品は依然として app-debug.apk です。
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { Regex("(?i)(perf|release)").containsMatchIn(it) }
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }
    // に付属の実行環境 (assets/runtime/rootfs.tar.xz、tools/sandbox/build-runtime.py によって生成) はすでに xz になっており、再度押す必要はありません。
    androidResources { noCompress += "xz" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 評価タスク セット (ウェアハウス ルート ディレクトリ eval/tasks) がテスト パッケージのアセットに入力され、実機評価ランナーがそこから質問を読み取ります。
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("eval/tasks"))

    buildTypes {
        // は、実機の受け入れ時に並行してインストールされ、リリース バージョンとユーザー データが保持されます。デフォルトのビルド ID は変更されません。
        if (providers.gradleProperty("devInstall").isPresent) getByName("debug") {
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Mika Dev")
        }
        // 流暢性をチェックするために使用されるパッケージ: リリース バージョンのようにデバッグ可能ではありません (デバッグ パッケージはプリコンパイルされておらず、インターフェイスは数倍遅く、感触はカウントされません)。
        // は、デバッグ パッケージを直接上書きしてデータを保持できるネイティブ デバッグ署名を使用します。パフォーマンスツールでサンプリングできます。 ./gradlew :app:assemblePerf
        create("perf") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isProfileable = true
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.protobuf.lite)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    // どちらの認識モデルも APK とともにインストールされます。最初の使用は Play Services のダウンロードに依存しません。
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.japanese)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // サンドボックス パス コントラクト (コアの testFixtures): AndroidSandbox は JVM と実マシンの両方で同じセットを実行します
    testImplementation(testFixtures(project(":core")))
    androidTestImplementation(testFixtures(project(":core")))

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.junit)
}

// LicensesTest は、ウェアハウスのライセンス/THIRD_PARTY_NOTICES.md を読み取ります。テストの増分判定に参加させます。
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("licenses/THIRD_PARTY_NOTICES.md")).withPropertyName("thirdPartyNotices")
}
