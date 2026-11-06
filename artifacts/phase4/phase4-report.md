# Phase 4 current checkpoint

This file describes the tree whose product commit is `10504b0b89c7f6c36f91dae325942f863c0c4424`. A later commit that only rewrites this wording is documentation. It does not change the rootfs, native libraries, DEX, Gradle locks, or verification metadata. This is not a security approval and not grade A. No signed APK is claimed.

The Phase 2 suite in `phase2-clean-sha-suites.json` was run after Phase 3 already existed, on a detached worktree at `a3e7f0eb73415a93a0165382484889e75c2505ee`. That is a retrospective check. It is not proof those tests ran before commit `77a20309`.

## Current binaries and pins

| Item | Current value |
| --- | --- |
| Product commit | `10504b0b89c7f6c36f91dae325942f863c0c4424` |
| Rootfs | `app/src/main/assets/runtime/rootfs.tar.xz` |
| Rootfs SHA-256 | `fc08417a99d94fa4a2195d273565751969b90f227783ad7b4a94373c57c3caaa` |
| Rootfs size / regular-file bytes / entries | 31472744 / 128249283 / 3238 |
| `BundledRuntime.VERSION` | `3.22.7-fc08417a99d9` |
| `python3`, `python3-pyc`, `python3-pycache-pyc0`, `pyc` | `3.12.16-r0` |
| `libexpat` | `2.10.0-r0` |
| `tzdata` | `2026e-r0` (that URL still returned HTTP 200; it was not bumped) |
| Per-file inventory | `rootfs-inventory.json` and `rootfs-summary.json` (archive SHA above) |
| Native launchers | `native-libs.json` (unchanged bytes; rebuild still unverified) |
| Maven SBOM | `sbom.cdx.json`, `sbom.spdx.json` (107 release-runtime coordinates; rootfs is not in this graph) |
| Gradle locks | `app/gradle.lockfile`, `core/gradle.lockfile`, `settings-gradle.lockfile` |
| Checksums | `gradle/verification-metadata.xml` (480 components, 848 artifacts, `verify-signatures` false) |
| Unsigned universal APK | 92544743 bytes, SHA-256 `84da4598ff3f7b8e129bcb641a3a0fb2badcff9ba85485677dd633c47e3c2db8` |
| Unsigned arm64 APK | SHA-256 `4b9c2653ca7f5de03d199f79b5aeedd8e6d9f0f3698c5b3c69d6aa79cd66e1d5` |
| DEX | four files, 30369 class defs. Hashes in `dex-universal.json`. `classes.dex` `5abfd236bf49b89ea1ddf5d33cdbcd65afbcdf94a3031d826a8b7e9135b044f2`, `classes2.dex` `c9cc9eb64156b18909bb3ba37f55e8530302e0a1c91be1a0836119eb8296c352`, `classes3.dex` `9820212cf5ac7e7523f9bb2ebb22ce9b3ca0f4bae41fe2c3ec2d067ff8a14dfc`, `classes4.dex` `0b8ed73b5e5a74bf1d034298d0a42ce8ef3c2a6d3ee3421354b76e057a44065c` |

The unsigned APKs were built from `4ad77db6` with no `MIKA_RELEASE_*` variables. `10504b0` adds only Gradle lock and checksum files, so the APK bytes were not rebuilt for that commit. `apksigner verify` reported `DOES NOT VERIFY`. They are not the signed baseline at `67217715`. DEX equality with that baseline APK is **UNVERIFIED**. The APKs are not in git.

`gradlew.bat` stays unstaged and is not an input to this Linux build.

## Advisory status for the current pins

Evidence files and their SHA-256 are in `artifacts/phase4/evidence/`. OSV and cvelistV5 returned HTTP 200 on 2026-11-06. The Alpine APKBUILD URL returned HTTP 418, so that recipe was not read. The aarch64 APKINDEX signature `.SIGN.RSA.alpine-devel@lists.alpinelinux.org-616ae350.rsa.pub` verified with a key from the pinned minirootfs.

| ID | Current pin | What was confirmed |
| --- | --- | --- |
| `CVE-2024-3220` | `python3` `3.12.16-r0` is outside the PSF range `3.12.0` lessThan `3.12.16`. | Windows `mimetypes` paths. CVSS 4.0 base 2.3 LOW. Not a Linux runtime bug even on the old pin. |
| `CVE-2026-114583` | `3.12.16-r0` is the first 3.12 release outside the PSF range. | `tarfile` filter bypass. CVSS 4.0 base 6.9 MEDIUM. The old pin `3.12.15-r0` was inside the range. Applies to sandbox Python only if a script extracts an untrusted tar. |
| `CVE-2026-115557` | Same fix boundary, `3.12.16`. | Missing `ssl` denylist on older 3.12. CNA score CVSS 4.0 base 9.4 CRITICAL. Applies when sandbox Python does TLS. |
| `CVE-2026-12345` | PSF range is lessThan `3.12.15`. | **Retracted** as a hole in `3.12.15-r0`. Alpine’s secfixes map still names `3.12.16-r0` as the fix package, which disagrees with the PSF range. CVSS 4.0 base 5.9 MEDIUM. |
| `CVE-2026-113351`, `CVE-2026-115506`, `CVE-2026-117610`, `CVE-2026-118370` | `libexpat` `2.10.0-r0`. OSV package `expat` at `2.10.0-r0` returned no rows on 2026-11-06 04:09:07 GMT. | VulnCheck cvelist says libexpat through 2.9.0. Scores 8.2–8.7 HIGH, mostly denial of service, plus an integer overflow. The old apk URL was HTTP 404, so `2.9.0-r0` could not stay pinned. Assigner is VulnCheck, not the libexpat project. |

OSV `querybatch` at 04:08:57 GMT, HTTP 200: `python3` `3.12.16-r0` returned no ids; `python3` `3.12.15-r0` still returned the four Alpine ids, including the retracted `CVE-2026-12345` mapping. Zero rows on the current pin is not a statement that every byte is vulnerability-free. The other 64 Alpine packages and the 107 Maven coordinates were not re-queried in this checkpoint. The Maven query at 04:00:16 GMT returned no rows and a log4j-core 2.14.0 control, which is not a Mika dependency, returned 8 GHSA ids. NDK, Android platform, and `.so` files are outside that query.

## What the hashes do and do not prove

Entry hashes in `rootfs-inventory.json` match the current archive. No archive member was executed. ELF behavior, `etc/inittab` / openrc, and the two pinned-but-unrun install scripts (`icu-data-en/.post-install`, `ca-certificates/.trigger`) stay **UNVERIFIED**. `tmp` and `var/tmp` are mode `01777`. No setuid or setgid member and no private-key banner were recorded.

`libmikarun.so`, `libproot.so`, and `libproot_loader.so` hashes in `native-libs.json` still match the arm64 APK copies from the unsigned build. PRoot 5.4.0 and talloc 2.4.3 source tarball hashes matched `tools/sandbox/sources.lock`. NDK `28.2.13676358` was not installed, so `build-native.sh --check` was not run. Byte-for-byte rebuild of those three files is **UNVERIFIED**. ML Kit and AndroidX graphics `.so` files are hashed in `dex-universal.json`. Their upstream rebuild is **UNVERIFIED**.

jadx 1.5.1 reported 101 error nodes (`jadx-method-failures.txt`, 64 method lines, 45 in `com.wangzan101.mika`). `dexdump` of `classes3.dex` shows `ActionExecutor.aimed` invokes `ConfirmDecision.judge`, `OperationGuards.beforeAction`, and `FollowUp.approve`, and `AgentLoop.loop` invokes `ConfirmationGate.getHasGrant`. That is an invoke-level check. `PhoneRun.runCommands`, `DeviceAccess.unlock`, `TaskGate.pass`, and `ModelCaller.complete` stay **UNVERIFIED** as recovered logic. The DEX set is not PASS.

Gradle locks and `gradle/verification-metadata.xml` exist as of `10504b0`. `verify-signatures` is false. A checksum match means the file is the same bytes as the resolution that wrote the metadata. It does not prove those bytes were the publisher’s artifact at generation time. See `dependency-verification.md`.

Strict re-run on 2026-11-06, no `--write-locks` and no `--write-verification-metadata`. JDK 17. A mismatch would fail the build; no new hash was accepted.

| Command | Process exit | JUnit XML |
| --- | --- | --- |
| `:core:test` | 0 at 04:32:52 UTC. `BUILD SUCCESSFUL in 2m 23s`. | 2996 tests, 0 failed, 2 skipped. XML time 04:32:52 UTC. |
| `:app:testDebugUnitTest` | Not a clean process exit. The log says `BUILD SUCCESSFUL in 1m 23s` and the XML is 1487 tests, 0 failed, 0 skipped, written at 04:34:16 UTC. After that line the Gradle wrapper JVM stayed in kernel state `T` (`do_signal_stop`), `TracerPid` 0. `SIGCONT` returned success and the process stayed stopped. The single-use daemon had already left. The stopped wrapper was then killed so the project lock could be released. That kill is not a test failure and is not exit 0. | 1487 / 0 / 0 |
| `:core:cliTest --rerun-tasks` | 0. `BUILD SUCCESSFUL in 1m 24s`. | 1 test, 0 failed, 0 skipped. XML time 04:37:41 UTC. |

OCR for the model, vision-only locate, and quoting on-screen labels remain limitations. They are not a PASS.

## Current verdicts

| Area | Verdict |
| --- | --- |
| Rootfs archive identity and per-file hashes | Inventory matches `BundledRuntime`. Not a behavior PASS. |
| ELF, inittab, unrun install scripts | UNVERIFIED |
| jniLibs hashes vs arm64 APK | Match for the three launchers. Not a rebuild PASS. |
| Native rebuild and third-party `.so` provenance | UNVERIFIED |
| DEX structure of the unsigned post-rootfs APK | Four files, headers parsed. Not a full-decompilation PASS. |
| jadx recovery | UNVERIFIED for 101 nodes |
| DEX vs signed `67217715` | UNVERIFIED |
| Maven coordinate resolution and SBOM file hashes | Present. Not an authenticity PASS. |
| Gradle lock and checksum file | Present since `10504b0`. Signature verification is off. Not an upstream-authenticity PASS. |
| Current `python3` `3.12.16-r0` and `libexpat` `2.10.0-r0` vs the advisories above | Pins are at the stated fix boundary. Not a blanket vulnerability PASS. |
| Vision / OCR / on-screen label quotation | Limitation. Not PASS. |

## Historical snapshot (not the current tree)

These sentences describe commits before `4ad77db6`. They are not the current pins.

- Rootfs SHA-256 `744047570fd84e94996623e8347efce9528da6d073966e040d064f0f2676f2a2`, 31466196 bytes, `python3` `3.12.15-r0`, `libexpat` `2.9.0-r0`.
- Unsigned APKs built from `215db936`: universal `74a6e90e3b20fab77b5453c3dc5518256630abbf09060f2a31a0aa32eaaba80a` (92537803 bytes), arm64 `767be8515065e8986825852a4cebe93b5ae5d9350daada9923bb2703b0d50121`. `classes2.dex` then began `fd4a7e3e7515` and `classes3.dex` began `ca0a2fa661c2`.
- At `e952c83a` there was no Gradle lockfile and no `verification-metadata.xml`. `10504b0` added them.
- The first OSV note treated all four Python ids, including `CVE-2026-12345`, as open on `3.12.15-r0` and said remediation was not done. That remediation is commit `4ad77db6`. The `CVE-2026-12345` claim against `3.12.15-r0` is retracted.

## Scripts

- `tools/security/phase4_inventory.py` (`--self-test` flags a synthetic setuid file and does not write private-key bytes)
- `tools/security/phase4-resolve.init.gradle`
- `tools/security/phase4_sbom.py`
- `tools/security/phase4_dex.py`
