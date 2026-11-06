# Phase 6 signed release evidence

Built source: `b855fa7685fc84ee7058ef5de202474f7a49b785` on `security/a-grade-hardening`.
This report is a later documentation commit. The APKs were built from `b855fa76`, not from this report commit.

This is not a grade, a personal-phone clearance, or a published release. Nothing was tagged or merged. PR #3 stays draft. PR #2 stays draft at `67217715`.

## Linkage

| Step | Value |
| --- | --- |
| Pushed source | `b855fa7685fc84ee7058ef5de202474f7a49b785` |
| Event | `push` on `refs/heads/security/a-grade-hardening` |
| Run | [42167248514](https://github.com/Grok-Benkei/Mika-JP/actions/runs/42167248514) |
| Run headSha | `b855fa7685fc84ee7058ef5de202474f7a49b785` |
| Conclusion | success |
| Release artifact | [Mika-JP-Release-APK 13480410183](https://github.com/Grok-Benkei/Mika-JP/actions/runs/42167248514/artifacts/13480410183) |
| Test artifact | [test-results 13479414221](https://github.com/Grok-Benkei/Mika-JP/actions/runs/42167248514/artifacts/13479414221) |
| Job `git rev-parse HEAD` | `b855fa7685fc84ee7058ef5de202474f7a49b785` |
| `BuildConfig.GIT_HASH` string in both APKs | `b855fa7` |

`BuildConfig.GIT_HASH` is a 7-character string embedded by one builder. It is not a reproducibility proof. The linkage used here is the push SHA, the Actions `headSha`, the checkout `git rev-parse`, and an independent hash of the downloaded APKs. A second builder was not run.

`workflow_dispatch` returned HTTP 403 for this app. The deliverable is the `push` run above. Earlier workflow-only commits did start runs; the first attempts failed on missing checksums and were not used as the deliverable. Baseline run `42037688352` was not downloaded.

## CI tests on `b855fa76`

Strict dependency verification. No lock or verification-metadata write flags. Counts are from the downloaded JUnit XML, which matches the job log.

| Suite | Tests | Failures | Errors | Skips | Exit |
| --- | --- | --- | --- | --- | --- |
| `:core:test` | 3001 | 0 | 0 | 2 | job success |
| `:app:testDebugUnitTest` | 1489 | 0 | 0 | 0 | job success |
| `:core:cliContractTest` | 1 | 0 | 0 | 0 | job success |
| `:core:cliTest` Python | 48 | 0 |  |  | `OK` in 13.499s |
| `tools/upstream` | 30 | 0 |  |  | `OK` |
| `tools/release` | 8 | 0 |  |  | `OK` |

Skips: `RescoreRecordedRuns.rescore`, `RescoreEvalRun.rescore`.

Local suites before the signing push, on the parent trees, were also 3001/0/2, 1489/0/0, Python 48 OK, each Gradle client exit 0. The checksum-only commits did not change test code.

## APKs

Independent hashes of the downloaded files match `SHA256SUMS` in the artifact.

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `app-arm64-v8a-release.apk` | 79959812 | `20526ceb219eba71d92a91c4804a88b7d2155aa23e49058824f8bfc672ab1cf4` |
| `app-universal-release.apk` | 92555955 | `da86ed527246e987b762c79e5c2fe690412cf337d53cb77cf02f75dd3ce4db86` |

Both filenames are the signed release names. No debug APK was uploaded. No unsigned APK was uploaded.

## Signature

`apksigner verify --verbose --print-certs` exit 0 on both files, run locally after download.

| Check | Result |
| --- | --- |
| Signers | 1 |
| Certificate SHA-256 | `b0a8f428f96164ceee83a34bcf22a4e4d21588251547cf448ccc4b13f3072f1e` PASS |
| DN | `CN=Mika JP, OU=Android, O=Grok Benkei, C=JP` |
| Key | RSA 4096 |
| v2 | true PASS |
| v1 | false, verified absent |
| v3 | false, verified absent |
| v3.1 | false, verified absent |
| v4 | false, verified absent |

v2 is the scheme that verifies. The absent schemes are what `apksigner` reported, not an unverified gap. The keystore used by the job was a temporary PKCS#12 file of 4356 bytes. The log did not print secret values. The temp file was removed by the job cleanup. The downloaded artifact has no `.keystore`, `.jks`, `.p12`, or `.pk8` file, and neither APK contains a private-key marker.

## Manifest

Binary `AndroidManifest.xml` via `aapt dump xmltree`. Both APKs agree.

| Check | Result |
| --- | --- |
| `android:debuggable` | absent in the binary manifest. Badging has no `application-debuggable`. PASS as release false |
| minSdk | 30 |
| targetSdk | 35 |
| versionName | `0.1.36-jp.1` |
| versionCode | 38 |
| package | `jp.studioyebisu.mika` |
| compileSdk | 36 |
| cleartext | `network_security_config`: base-config `cleartextTrafficPermitted=false`, domain-config true only for `localhost` with subdomains. No `usesCleartextTraffic` on the application tag |
| exported true | 7: `MainActivity`, `SelfCheckTargetActivity`, `MikaAccessibilityService`, `McpService`, `HelperHandoffProvider`, `BootRestoreReceiver`, `ProfileInstallReceiver` |
| exported false | 18 |

## Phase 4 and Phase 5 inside the APK

Present in both APKs:

- Rootfs pin `fc08417a99d94fa4a2195d273565751969b90f227783ad7b4a94373c57c3caaa` in `classes2.dex`.
- `assets/runtime/packages.json` version `3.22.7-fc08417a99d9`.
- `egress_blocked:` in `classes3.dex`.
- `notification_withheld` in `classes2.dex`.
- `destination_unconfirmed` in `classes3.dex`.
- `confirmation_required` in `classes3.dex`.

DEX: four files, 30369 class defs (8882 + 9501 + 9136 + 2850). That class-def total matches the Phase 4 unsigned count. It does not mean the method bodies match, and it is not a behavior pass.

Native `lib/arm64-v8a` hashes match the Phase 4 inventory for `libmikarun.so`, `libproot.so`, and `libproot_loader.so`. The universal APK has 11 native libraries and the arm64 APK has 5. NDK rebuild remains UNVERIFIED. ML Kit OCR was not rebuilt from upstream.

## Failed attempts that were not the deliverable

| Run | headSha | Cause |
| --- | --- | --- |
| [42165814310](https://github.com/Grok-Benkei/Mika-JP/actions/runs/42165814310) and [42165821608](https://github.com/Grok-Benkei/Mika-JP/actions/runs/42165821608) | `a5cdb1a9` | Clean resolve lacked checksums for `junit-bom` 5.9.2 and 5.9.3 `.module` and `kotlinx-coroutines-bom` 1.8.0 `.pom` |
| [42166521979](https://github.com/Grok-Benkei/Mika-JP/actions/runs/42166521979) and [42166526919](https://github.com/Grok-Benkei/Mika-JP/actions/runs/42166526919) | `9df36358` | Same three missing checksums |
| [42166827428](https://github.com/Grok-Benkei/Mika-JP/actions/runs/42166827428) and its pull-request twin | `060a40a5` | Next missing checksum was `junit-bom-5.13.1.module` |

Those checksums were fetched from `repo1.maven.org`, matched the files Gradle had cached, and were added without rewriting existing entries and without a write-metadata flag in CI. The test-results upload also failed on those runs because the build stopped before any JUnit XML existed.

## UNVERIFIED

- Method bodies that jadx could not recover stay unverified. This pass did not mark the DEX set PASS.
- Native behavior and an NDK rebuild stay unverified. Matching `.so` hashes only say the bytes match the earlier inventory.
- No device, emulator, or adb run. `/dev/kvm` exists and is group-writable by gid 103. This user is not in that group. `adb` and `emulator` are not on `PATH`, and there is no AVD directory. That is availability only. It is not Phase 7.
- Vision-only locate, OCR handed to the model, and quoting an on-screen label remain limitations.
- Checksums detect a later swap. `verify-signatures` is false. They do not prove the repository was authentic when the checksum was recorded, and they do not cover the Android SDK, NDK, rootfs archive, or `jniLibs`.
- One GitHub-hosted builder produced these APKs. A second independent rebuild was not done.

Runtime code is frozen at `b855fa76` for Phase 7 and for the independent Phase 8 audit. Phase 7 was not started.
