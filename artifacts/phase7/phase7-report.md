# Phase 7 dynamic analysis

Runtime source stayed at `b855fa7685fc84ee7058ef5de202474f7a49b785`. No signing keys were read. No APK was rebuilt or re-signed. This is not a grade, and Phase 8 was not started.

The device is a disposable emulator, not a phone. Most required behaviors were not executed, so those rows are UNVERIFIED.

## Environment

| Item | Value |
| --- | --- |
| Emulator | Android emulator 37.2.13.0 (build_id 16740729), from the SDK `emulator` package |
| System image | `system-images;android-35;google_apis;x86_64`, Pkg.Revision 9, ApiLevel 35, vendor Google Inc. |
| Fingerprint | `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys` |
| Guest ABI / API | `x86_64`, SDK 35, Android 15 |
| Acceleration | `/dev/kvm` exists and is mode `crw-rw----` root:gid 103. This user cannot open it (`Permission denied`). Group membership was not changed. Boot used `-accel off -gpu swiftshader_indirect -no-window -no-audio -no-snapshot` |
| Boot | Process started 05:29:49 UTC. `sys.boot_completed=1` at 05:38:48 UTC. One attempt. It completed inside the 12-minute wait |
| Play Store | Emulator log: `PlayStore.enabled=no` |
| Host | 4 CPUs, software emulation. The guest showed “System UI isn't responding” once; Wait was tapped and the app UI then appeared |

This image is `userdebug` with dev-keys. `adb root` was used only to read the app’s private files and `ss`. That is not a production-locked device.

ABI coverage: the running process extracted `lib/x86_64/libmlkit_google_ocr_pipeline.so` and `libandroidx.graphics.path.so`. It did not extract `libmikarun.so`, `libproot.so`, or `libproot_loader.so`. An x86_64 guest does not validate the arm64 sandbox launchers. The arm64-only APK installed (`adb install` exit 0) and was then replaced by the universal APK. The installed `base.apk` hash is the universal file.

## Binaries

Rehashed immediately before `adb install`, and the installed `base.apk` was hashed again.

| File | SHA-256 | Role |
| --- | --- | --- |
| `app-universal-release.apk` | `da86ed527246e987b762c79e5c2fe690412cf337d53cb77cf02f75dd3ce4db86` | Installed. `base.apk` on the guest matches this hash. `primaryCpuAbi=x86_64`, versionName `0.1.36-jp.1`, versionCode 38, minSdk 30, targetSdk 35. Package flags do not include `DEBUGGABLE` |
| `app-arm64-v8a-release.apk` | `20526ceb219eba71d92a91c4804a88b7d2155aa23e49058824f8bfc672ab1cf4` | Hashed and installed once, then replaced. Not the process under test |

Process log at start: `Not starting debugger since process cannot load the jdwp agent.`

## Capture

| Window (UTC) | What |
| --- | --- |
| 05:29:49–05:38:48 | Boot |
| launch window file `05:46` start through first UI | `am start` of `MainActivity`. `am start -W` returned `Status: timeout` after 48049 ms, then the process was alive (pid 3916) |
| 05:49 | Onboarding UI and accessibility setting |
| 05:50 | Model-setup screen, synthetic notification, force-stop, relaunch (pid 5076 at 05:51:22) |

`emulator -tcpdump /tmp/p7/boot.pcap` produced 402 bytes, SHA-256 `09a59ab3a7cab9edf8744d6d47d8f57d91c573381755c44a9afb8a5f01b5315d`. The file is a pcap header with no guest packets. It was not committed. Destinations and SNI were not recovered from it.

`/proc/net/tcp` and `/proc/net/tcp6` had no rows whose uid column was `10210` (the app uid, `u0a210`) in the two snapshots after launch and after setup. That is a point-in-time socket table, not a continuous capture, and it does not show DNS questions that already finished. Payload contents of TLS were not decrypted. No proxy or extra CA was installed. Payload safety is UNVERIFIED wherever only destinations would have been available, and here even destinations were not in the capture.

## Matrix

| Item | Result | Observation |
| --- | --- | --- |
| Startup egress | UNVERIFIED | No uid sockets in two `/proc/net/tcp` snapshots. Pcap has no packets. No process-attributed DNS or SNI |
| Accessibility default | PASS | Before any change, `enabled_accessibility_services` was `null` and `accessibility_enabled` was `0` |
| Accessibility service connects when named in the secure setting | PASS | After the setting was set to `jp.studioyebisu.mika/com.wangzan101.mika.a11y.MikaAccessibilityService`, logcat recorded `MikaA11y: ユーザー補助サービスが接続されました` |
| Bank, browser, WebView, password, and unknown-package screen text and images blocked | UNVERIFIED | No second app was shown to the service, and no model task was subscribed. Screen text was not sent to an external model |
| Notification capture and egress | UNVERIFIED | `cmd notification post` used the synthetic text `P7SENTINEL-NOTICE-ALPHA`. That string was absent from the following logcat, and so was `egress_blocked` / `notification_withheld`. The withheld path was not observed |
| OCR first use | UNVERIFIED | The x86_64 ML Kit library is on disk. No recognition call was made |
| ML Kit or Play services egress | UNVERIFIED | No OCR call. This image has no Play Store. No uid sockets were seen |
| High-risk confirmation, missing grant, autonomous mode, injected `approved` field | UNVERIFIED | No tool ran. Fresh private files had no approval or autonomous preference |
| Model UI consent for a destination | UNVERIFIED | The setup screen was reached (`モデルを設定`, provider list, `API Key`, `次へ`). No URL was entered, confirmed, or saved |
| API key recipients and redirects | UNVERIFIED | No key was entered. No request left the process in the socket snapshots. DeepSeek processing was not attempted and is UNVERIFIED |
| MCP, relay, and local server default off | PASS for listening and stored files in this snapshot | No mcp, relay, or otlp file under the app data dir. Root `ss -lntu` had no listener on 8765, 80, or 443 |
| MCP enabled: auth, bind address, and restart | UNVERIFIED | The switch was not turned on |
| Updater destination | UNVERIFIED | The update flow was not opened |
| Exit, restart, and boot | PASS for force-stop and relaunch only | `am force-stop` left no pid. A later start resumed `MainActivity` as pid 5076. After force-stop, the accessibility setting read back as `null`. The emulator was not rebooted, so boot behavior is UNVERIFIED |
| Arm64 sandbox launcher | UNVERIFIED as a sandbox test. The x86_64 miss is observed | `RuntimePreparer` logged `Cannot run program ".../lib/x86_64/libmikarun.so": error=2, No such file or directory` from `AndroidSandbox.launch`. That does not exercise the arm64 `libmikarun` / `libproot` binaries |
| Release not debuggable | PASS | Package flags omit `DEBUGGABLE`. The process refused the JDWP agent |
| Crash, log, and OTLP sentinels | UNVERIFIED beyond the log buffer that was read | The synthetic notice was absent from that logcat. No API key was present to leak. No OTLP file and no OTLP send were observed. The empty pcap cannot prove an export did not happen |
| Vision-only locate, OCR for the model, quoting an on-screen label | UNVERIFIED | Not exercised. These remain limitations, not a no-major-regression pass |
| DEX method bodies and native behavior | UNVERIFIED as a clearance | One path ran: the x86_64 process reached `AndroidSandbox.launch` and failed to exec a missing `libmikarun.so`. That is not clearance for the other unrecovered methods or for the arm64 libraries |

## What was not done

No dummy API token was submitted. No screen was sent to DeepSeek or any other external model. No production key was requested. An authentication failure was not treated as a successful model call. The release trust store was not patched. GitHub-hosted emulator CI was not added, because this guest did boot. `workflow_dispatch` was not retried.

PR #2 remains the draft at `67217715`. PR #3 remains a draft. `gradlew.bat` was left unstaged.
