# Phase 5 evidence

Tested source: `0e6f2096386b5f3b410314ea6f3a82f34a9f2fc2` on `security/a-grade-hardening`.
Parent evidence checkpoint: `1a1e829259214b481777d6cad61be7a6643c905b`.
This file is the documentation commit after that test run. The executable tree of `0e6f2096` is what the suites below executed. The later documentation commit adds this report only.

Phase 5 is a test checkpoint. It is not a grade, a personal-phone clearance, or a signed release. No new APK was built. The unsigned release recorded in the Phase 4 report remains the build from `4ad77db6` and does not contain the Phase 5 notification-wire change in `ScreenAccessibility`.

## Suites

Commands used `JAVA_HOME` 17 and did not pass `--write-locks` or `--write-verification-metadata`. Verification metadata stayed fail-closed. Each Gradle client exited on its own.

| Task | Result | Exit | Clock |
| --- | --- | --- | --- |
| `:core:test` | 3001 tests, 0 failures, 0 errors, 2 skips | 0 | 04:42:09–04:44:29 UTC |
| `:app:testDebugUnitTest` | 1489 tests, 0 failures, 0 errors, 0 skips | 0 | 04:44:29–04:44:50 UTC |
| `:core:cliTest` | Python unittest 48 OK; JVM `cliContractTest` 1/0/0 | 0 | 04:44:50–04:46:15 UTC |

The two core skips are `RescoreRecordedRuns.rescore` and `RescoreEvalRun.rescore`.

## Requirement matrix

Scope is the JVM and Python unit suites on source `0e6f2096`. These are not a device run, not an emulator run, and not proof of the installed APK. A mock server records requests inside the test process. It is not a network path on a phone.

| # | Requirement | Test | Result | What it does not prove |
| --- | --- | --- | --- | --- |
| 1 | Bank screen blocked | `EgressPolicyTest.bankingAppBlocksBodyAndScreenshot`; `Phase5EgressBehaviorTest.blockedScreensDoNotCaptureOrReturnSecrets` | Pass | The behavior test uses `FakeScreen`. It counts screenshot calls and checks the returned JSON. It does not drive Accessibility. |
| 2 | Bank page in a browser blocked | `EgressPolicyTest.bankPageInsideBrowserIsBlocked`; same behavior test, `bank-browser` case | Pass | The page URL is a fixture, not a loaded browser. |
| 3 | WebView blocked | `EgressPolicyTest.anyBrowserOrWebViewBlocksScreenBodyAndScreenshot`; behavior test `webview` case | Pass | Role string `android.webkit.WebView` is supplied by the fake snapshot. |
| 4 | Password image blocked | `EgressPolicyTest.passwordNodeBlocksScreenshotAndBody`; behavior test `password` case | Pass | A secret node is the fixture. Pixel classification of a real screenshot is not run. |
| 5 | Unknown package blocked | `EgressPolicyTest.unknownPackageFailsClosed`; `PromptInjectionRegressionTest.unknownPackageFailsClosedForScreenshotSend`; behavior test `unknown` case | Pass | Package `null` is the fixture. |
| 6 | Sensitive notification blocked | `EgressPolicyTest.sensitiveNotificationIsNotSent`; `Phase5EgressBehaviorTest.withheldNotificationDoesNotCarryTheNoticeBody` | Pass | `ScreenAccessibility` now emits `EgressPolicy.withheldNotification(package)`. The unit test checks that string. It does not post a real notification. |
| 7 | External prompt cannot bypass egress | `PromptInjectionRegressionTest` (screen text, model output, tool text, notification URL); `Phase5EgressBehaviorTest.aPromptOnTheScreenDoesNotUnlockAScreenshot` | Pass | The bait string is an element name. The test asserts zero screenshot captures and that the key string is absent from the tool result. |
| 8 | High-risk explicit consent in autonomous mode | `ActionExecutorOriginsTest.autonomousActionRunsWithoutApproval` (tap, empty `taps`); `RootCommandToolTest.autonomousModeDoesNotAsk` and `autonomousUnattendedModeKeepsTheOptedInPolicy`; `HighRiskConfirmationTest.settingsChangeDoesNotRunWithoutThatExactConfirmation` and `exactSettingsConfirmationRunsOnceAndAChangedValueDoesNot`; `Phase5EgressBehaviorTest.autonomousFileWriteAndShellDoNotRunWithoutThatGrant` | Pass | File write and shell assert `runs == 0` on a sandbox whose `run` would fail the test if called. `approved:true` in the tool JSON is not treated as consent. Exact settings grant is single-use; a changed value does not run. |
| 9 | MCP default off, relay and local server | `McpSettingsTest.offByDefaultAndTheSwitchSurvivesARestart`; `RelaySettingsTest.freshInstallLeavesRelayOff`; `EndpointUrlPolicyTest.localServerGetsLoopbackScreenTextAndDropsHttpCloudRedirect` | Pass | Settings objects and a local mock. No live relay socket. |
| 10 | API key, log, crash, and OTLP redaction | `RedactorTest.googleAuthKeyMaskedEverywhere`, `apiKeyLikeStringsMasked`, `authorizationAndScreenPayloadMarkersDoNotSurviveLogs`; `RedactorNetworkTest.headersAreMaskedByName`, `jsonSecretFieldsAreMaskedAtAnyDepth`; `RunTraceTest.realLoopPreservesWireRequestsAndRecordsRetriesToolsAndPortableUsage` | Pass | `archive.save(includeBody = false)` omits `PRIVATE_GOAL`. The ZIP path includes it. `OtlpSender` calls `save` with `includeBody = false` and disables redirect following. The OTLP sender itself is not executed against a collector here. |
| 11 | Authenticated redirect refusal | `EndpointUrlPolicyTest.httpDowngradeOnRedirectIsRejected`, `crossHostRedirectDoesNotForwardAuthorizationOrBody`, `localServerGetsLoopbackScreenTextAndDropsHttpCloudRedirect` | Pass | MockWebServer. Cross-host does not forward `Authorization` or the body. HTTPS to HTTP is `http_downgrade`. |
| 12 | DeepSeek Flash retry, SSE, and Retry-After | `DeepSeekFlashRetryTest.rateLimitThenEventStreamSendsExactlyTwoRequests` plus the existing delay tests | Pass | Local `MockWebServer` only. `Retry-After: 0`, then one `text/event-stream` body. Request count is 2. This is not a call to DeepSeek. |

## Destination consent

`ModelDestinationConsentTest` covers host, scheme, port, and path at the policy object.
`ModelSetupViewModelTest.confirmingOneDestinationDoesNotAuthorizeADifferentUrl` confirms `https://dashscope.aliyuncs.com/compatible-mode/v1`, then changes the form to `https://other.example/v1`. The second URL is not saved. After a new confirmation of that exact URL, it is saved. The listing client is a fake. No request leaves the test.

## Limits that stay open

- Vision-only locate, OCR supplied to the model, and quoting an on-screen label as a verified fact are not covered by a passing test. Keyword absence is not a safety pass.
- `PhoneRun.runCommands`, `DeviceAccess.unlock`, `TaskGate.pass`, and `ModelCaller.complete` stay UNVERIFIED at the DEX body level from Phase 4. Phase 5 did not disassemble a new APK.
- Native libraries were not rebuilt. NDK verification remains UNVERIFIED.
- Gradle checksums detect a later swap. They do not prove the bytes were authentic when first recorded, and they do not cover the Android SDK, NDK, rootfs, or `jniLibs`.
- No device or emulator was available. Dynamic app behavior is UNVERIFIED.
- Inventory and this matrix are not a behavior pass for the installed application.

## Historical sequencing

Phase 3 was committed before any committed file recorded a clean suite of Phase 2 commit `a3e7f0eb`. A later detached worktree at that commit was tested without moving the branch. That evidence is retrospective. The suites in this file cover `0e6f2096`, which includes Phase 2, Phase 3, the Phase 4 pin and lockfile, and the Phase 5 tests together.
