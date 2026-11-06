plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Checksums in gradle/verification-metadata.xml are the bytes resolved from
// google() and mavenCentral() when the file was generated. They detect a
// later swap of those files. They do not prove the repository was authentic
// at generation time.
subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
