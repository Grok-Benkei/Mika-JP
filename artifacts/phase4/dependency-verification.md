# Gradle lock and checksum bootstrap

`subprojects` in the root `build.gradle.kts` lock every configuration. `gradle/verification-metadata.xml` stores SHA-256 of the artifacts resolved while that file was written.

Bootstrap trust: the generator used the artifacts already cached from `google()` and `mavenCentral()` on this machine. A checksum match means the file is the same bytes as that resolution. It does not prove those bytes were the upstream publisher's artifact, and it does not cover the Android SDK, the NDK, `rootfs.tar.xz`, or `jniLibs`.

No dependency version was changed to produce the lock. If a lock or checksum write fails, the failure is the result; versions are not relaxed to force a match.

Generated on 2026-11-06 with:

`bash ./gradlew --no-daemon --write-locks --write-verification-metadata sha256 :core:test :app:testDebugUnitTest :core:cliTest :app:assembleRelease`

That command exited 0 (`BUILD SUCCESSFUL in 3m 48s`) and ran those four tasks. The metadata file has `verify-metadata` true and `verify-signatures` false: 480 components and 848 artifacts. A later `./gradlew :core:cliTest :app:assembleRelease` without the write flags also exited 0, so the recorded checksums match the files Gradle resolved.
