# Phase 4 supply-chain and binary inventory

This is an implementation inventory. It is not a security approval, not grade A, and not a signed release. Phases 5–8 were not started.

## Source and interim binary

| Item | Value |
| --- | --- |
| Source SHA | `215db9369b2f0f7cab8c8d28647ad0b59c7f3929` |
| `BuildConfig.GIT_HASH` | `215db93` |
| Build | `./gradlew --no-daemon :app:assembleRelease` with no `MIKA_RELEASE_*` variables |
| Config | AGP 8.7.3, Gradle 8.11.1, Kotlin 2.1.20, compileSdk 36, minSdk 30, targetSdk 35, versionName `0.1.36-jp.1`, versionCode 38, `isDebuggable = false` in the release build type |
| Universal unsigned APK | 92537803 bytes, SHA-256 `74a6e90e3b20fab77b5453c3dc5518256630abbf09060f2a31a0aa32eaaba80a` |
| arm64-v8a unsigned APK | 79941660 bytes, SHA-256 `767be8515065e8986825852a4cebe93b5ae5d9350daada9923bb2703b0d50121` |
| Signing | `apksigner verify` reports `DOES NOT VERIFY` for both. No signing secret was present. |
| Manifest | `aapt dump xmltree` of `AndroidManifest.xml` has `extractNativeLibs` and does not contain `android:debuggable`. |

These APKs are interim analysis binaries built from `215db936`. They are not the signed baseline at `6721771517569c6f0d6a78b83f2db48e3946b3df`. The baseline APK bytes are not in this workspace, so DEX equality with that build is **UNVERIFIED**. Phase 6 has to hash the final signed APK and diff it against this interim pair. The APKs themselves are not committed.

`gradlew.bat` stayed unstaged. It is not an input to this Linux build.

## Phase 2 sequencing

Phase 3 commit `77a20309` sits on top of Phase 2 commit `a3e7f0eb`, and no earlier committed file records a suite run of that clean SHA. A detached worktree at `a3e7f0eb73415a93a0165382484889e75c2505ee` was tested without changing the branch checkout.

| Task | Tests | Failed | Skipped |
| --- | --- | --- | --- |
| `:core:test` | 2995 | 0 | 2 |
| `:app:testDebugUnitTest` | 1487 | 0 | 0 |
| `:core:cliTest` | 1 | 0 | 0 |

The Gradle client stayed up after each `BUILD SUCCESSFUL` and was stopped. Counts are from the JUnit XML. See `phase2-clean-sha-suites.json`. The later HEAD suite (core 2996) covers Phase 2 and Phase 3 together. It does not by itself prove the historical order.

## 1. DEX

Both unsigned APKs contain the same four DEX files:

| File | Class defs | SHA-256 |
| --- | --- | --- |
| `classes.dex` | 8882 | `5abfd236bf49…` full hash in `dex-universal.json` |
| `classes2.dex` | 9501 | `fd4a7e3e7515…` |
| `classes3.dex` | 9136 | `ca0a2fa661c2…` |
| `classes4.dex` | 2850 | `0b8ed73b5e5a…` |

Header parse: magic `dex\n`, file size matches the zip entry, class-def total 30369. The prior note that the baseline had four DEX files matches this count only as a count. The baseline file hashes were not recomputed.

jadx 1.5.1 `--no-res` exited 1 with `finished with errors, count: 101`. At `--log-level WARN` the log has 64 `Method:` errors (45 in `com.wangzan101.mika`, mostly Compose UI), plus `JadxOverflowException` (33), `ConstructorVisitor` (15), type inference, and similar reconstruction failures. The list is `jadx-method-failures.txt`. One duplicated class, `kotlin.coroutines.jvm.internal.DebugProbesKt`, is loaded once. Decompiled sources were not committed.

Classification: DEX structure **PASS** for these interim APKs. Full Java recovery **UNVERIFIED** for the 101 jadx nodes. Baseline byte identity **UNVERIFIED**.

## 2. Rootfs

`app/src/main/assets/runtime/rootfs.tar.xz`

| Check | Result |
| --- | --- |
| Entries | 3238, matches `BundledRuntime.ENTRIES` |
| Archive SHA-256 | `744047570fd84e94996623e8347efce9528da6d073966e040d064f0f2676f2a2`, matches `BundledRuntime.SHA256` |
| Archive size | 31466196 |
| Regular file bytes | 128240382 |
| ELF files | 172, hashed, not executed |
| `lib/apk/db/installed` | 65 packages, same set as `packages.json` |

Every tar member is in `rootfs-inventory.json` with mode, uid, gid, type, and SHA-256 for regular files. `readelf -d` recorded `NEEDED`/`RPATH` when a dynamic section exists. No member was executed.

Suspicious mode bits: `tmp` and `var/tmp` are `01777`. That is the normal sticky temp directory. No setuid or setgid member. No private-key banner in the scanned bytes.

Configured endpoints, read as text:

- `etc/apk/repositories`: `https://dl-cdn.alpinelinux.org/alpine/v3.22/main` and `.../community`
- `etc/secfixes.d/alpine`: `https://secdb.alpinelinux.org/v3.22/main.json` and `community.json`

`etc/inittab` names `/sbin/openrc` and getty. That file comes with the minirootfs. This inventory does not show the app booting openrc. Whether any code path executes it is **UNVERIFIED**. Other `http://` hits are comments and metadata inside CPython, Pillow, and pypdf, not a second configured client.

`tools/sandbox/build-runtime.py` pins two scripts it does not run: `icu-data-en/.post-install` and `ca-certificates/.trigger`. Those script bodies were not re-executed here. Their behavior beyond the pinned comment is **UNVERIFIED**.

Classification: archive identity and entry hashes **PASS**. Runtime behavior of ELF files, inittab, and unrun install scripts **UNVERIFIED**.

## 3. Native launchers and packaged libraries

Tree `app/src/main/jniLibs/arm64-v8a`, and the same bytes in `lib/arm64-v8a/` of the arm64 APK:

| File | Bytes | SHA-256 | Link |
| --- | --- | --- | --- |
| `libmikarun.so` | 16472 | `bbe3356d77ed174b36dcc88e90811ef4dcda9223ce60bbba22f48c487331ecbb` | dynamic, `libdl.so`, `libc.so`, linker64 |
| `libproot.so` | 181824 | `3184f54e363b560f128ce888c69d434fa6bfe80293c9fff47108456e79e48fe2` | dynamic, `libdl.so`, `libc.so`, linker64 |
| `libproot_loader.so` | 17672 | `a73384d2f78b535936401ddee33909ae91966812e56437a16486934e0faa588c` | static |

Source archives were downloaded and match `tools/sandbox/sources.lock`:

- PRoot 5.4.0 `29248aac2a7ce10c3bd5ee5602742ec33b2532310ff9cf73b79f3c133e5a5f68`
- talloc 2.4.3 `dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd`

NDK `28.2.13676358` is not installed, so `build-native.sh --check` was not run. Byte-for-byte rebuild of the three `.so` files is **UNVERIFIED**.

Third-party `.so` files inside the APK, hashed in `dex-universal.json`:

- `libmlkit_google_ocr_pipeline.so` on arm64-v8a, armeabi-v7a, x86, x86_64
- `libandroidx.graphics.path.so` on the same four ABIs

The arm64 split omits the non-arm64 copies. Sandbox launchers are arm64 only. Maven coordinates for those libraries are in the SBOM (`com.google.mlkit:text-recognition-bundled-common`, `androidx.graphics:graphics-path`). Their upstream rebuild is **UNVERIFIED**.

OCR for the model, vision-only locate, and quoting on-screen labels remain functional limitations. Shipping ML Kit does not make those flows a PASS.

## 4. Gradle release runtime SBOM

Resolved with `tools/security/phase4-resolve.init.gradle` (no version changes):

- `:app` `releaseRuntimeClasspath`: 109 files, 107 unique Maven coordinates
- `:core` `runtimeClasspath`: 14 files, all also on the app graph

`sbom.cdx.json` is CycloneDX 1.5. `sbom.spdx.json` is SPDX 2.3. Each component has a `pkg:maven` purl and SHA-256 of the resolved file. AAR/JAR `.so` entries are listed on the component. `jniLibs` and `rootfs.tar.xz` are outside this Maven graph and are covered above.

Repositories are `google()` and `mavenCentral()` only (`FAIL_ON_PROJECT_REPOS`). There is no `gradle/verification-metadata.xml` and no Gradle lockfile.

Classification: resolution **PASS** (coordinates and file hashes were produced from the release runtime classpath). Dependency locking and checksum verification **FAIL** as a missing control. That is a remediation item, not a changed dependency. Build-time plugins (AGP 8.7.3, Kotlin 2.1.20) are not in this runtime graph, so their advisory coverage is **UNVERIFIED**.

## 5. OSV

| Query | When (HTTP Date) | Scope | Matches |
| --- | --- | --- | --- |
| `POST https://api.osv.dev/v1/querybatch` | Fri, 06 Nov 2026 03:48:06 GMT | `Alpine:v3.22`, 65 packages from `packages.json` | `python3` `3.12.15-r0` |
| same API | Fri, 06 Nov 2026 04:00:16 GMT | `Maven`, 107 release-runtime coordinates | none |

A control query for `org.apache.logging.log4j:log4j-core` 2.14.0, which is not in the graph, returned 8 GHSA ids. The empty Maven result is an OSV result for this graph, not a statement that every byte is vulnerability-free.

`python3` 3.12.15-r0 is inside the introduced range and fixed at `3.12.16-r0` for all four ids:

| ID | What the advisory describes | Reachability from this tree |
| --- | --- | --- |
| `ALPINE-CVE-2024-3220` | Windows `mimetypes` path is writable by other users. CVSS 4.0 integrity/availability low, attack vector network, privileges required. | The rootfs is Linux musl. This Windows default-path bug is not a reachable phone path. |
| `ALPINE-CVE-2026-114583` | `tarfile` `filter="tar"` can be bypassed and write outside the destination. CVSS 4.0 local, integrity high. | `python3` is a sandbox interpreter (`SANDBOX_PACKAGES`, `ScriptManifest`). Reachable if a script extracts an untrusted tar with that filter. Not shown as an app-process bug. |
| `ALPINE-CVE-2026-115557` | Python `ssl` is missing the denylist of known compromised certificates. CVSS 4.0 network, high impact, user interaction. | Reachable when sandbox Python performs TLS. |
| `ALPINE-CVE-2026-12345` | `TemporaryDirectory` cleanup can follow a swapped symlink. CVSS 4.0 local, high integrity/availability, attack requirements present. | Needs a local writer racing cleanup inside the sandbox. |

Remediation, not done in this commit: move the locked Alpine `python3` package to `3.12.16-r0` or newer and regenerate `rootfs.tar.xz` with `build-runtime.py`. Do not treat the other 64 Alpine packages or the 107 Maven coordinates as PASS. OSV does not cover the NDK, the Android platform, or the `.so` files as separate packages.

Classification: query execution **PASS**. `python3` below the Alpine fix **FAIL** for those four ids. Maven graph **UNVERIFIED** as a safety claim despite zero OSV rows. Overall vulnerability clearance is not PASS.

## 6. Area verdicts

| Area | Verdict |
| --- | --- |
| Interim DEX structure (4 files, headers, class counts) | PASS for `215db936` unsigned APKs |
| DEX equality with signed `67217715` | UNVERIFIED |
| jadx recovery of every method | UNVERIFIED (101 error nodes) |
| Rootfs entry count, archive hash, per-file hashes | PASS |
| Rootfs ELF / inittab / unrun install-script behavior | UNVERIFIED |
| jniLibs hashes match the arm64 APK | PASS |
| Native rebuild from the locked PRoot/talloc sources | UNVERIFIED (NDK absent; source tarball hashes match) |
| Third-party ML Kit and graphics `.so` provenance | UNVERIFIED beyond Maven file hashes |
| Release runtime coordinates and file hashes | PASS |
| Gradle lock and verification metadata | FAIL (controls absent) |
| OSV query of Alpine packages and Maven coordinates | PASS as a query; python3 advisories FAIL; zero Maven rows are not a safety PASS |
| Vision-only locate, OCR-for-the-model, quoting on-screen labels | Not PASS. Still limitations for the final auditor. |

## High findings (not fixed here)

1. Bundled Alpine `python3` `3.12.15-r0` is older than the `3.12.16-r0` fix for `ALPINE-CVE-2026-114583` and `ALPINE-CVE-2026-115557`. Impact is inside the sandbox interpreter, not demonstrated in the app process. Upgrade is a later rootfs rebuild, not a silent lock edit.
2. Release dependencies have no Gradle lockfile and no verification metadata. Resolution today is reproducible only by re-resolving the same repositories.

No Critical app-process exploit was identified in this pass. That is not a safety declaration. Phase 5 was not started. The signed APK for Phase 6 still has to be built and compared with this interim pair.

## Scripts

- `tools/security/phase4_inventory.py` inventories the rootfs and native launchers. `python3 tools/security/phase4_inventory.py --self-test` checks that a synthetic setuid file is flagged and that private-key bytes are not written into the JSON.
- `tools/security/phase4-resolve.init.gradle` resolves the runtime classpaths.
- `tools/security/phase4_sbom.py` writes CycloneDX and SPDX.
- `tools/security/phase4_dex.py` reads DEX headers and runs jadx when `JADX` is set.
