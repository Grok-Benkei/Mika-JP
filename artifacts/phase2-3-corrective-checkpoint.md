# Phase 2 and Phase 3 corrective checkpoint

This file is the implementation record for the corrective passes. It does not assign grade A, does not describe a new signed APK, and does not close Phases 4–8.

## Recovery

The resume found the branch `security/a-grade-hardening` intact. No reset, rebase, force-push, or reclone was used.

| Point | Commit | State |
| --- | --- | --- |
| Phase 1 corrective | `8f82a16ccacc7cf1ab3850b57e5761f1e9a4e39d` | Ancestor. Not rewritten. |
| Privacy gate | `791528e85518e3a60b3e74adc6202098aef26350` | Ancestor. Session change token, not a content hash. |
| Phase 2 corrective (HEAD at recovery) | `a3e7f0eb73415a93a0165382484889e75c2505ee` | Already committed and pushed before this resume. |
| Phase 3 corrective | `77a2030906c3492cc080b6e67efd232dd8056f92` | Uncommitted destination-consent edits were preserved and finished here. |

`gradlew.bat` stayed unstaged. Its diff is line endings only. Fork `main`, upstream, and PR #2 were not modified. PR #3 stays an open draft against `security/harden-android-release`.

The last observed build error was `return` inside `withContext` in `ModelSetupViewModel`. Those returns now sit on the `launch` block, before `withContext`. That compile error is not claimed as the cause of the earlier Cloud Agent service error.

Phase 2 was already its own commit. This resume did not check out a clean `a3e7f0eb` and re-run its suites, because the Phase 3 edits were uncommitted on top of it and were kept. The full suites below were run after the final Phase 3 edits and include the Phase 2 code.

## Phase 2 boundary (commit `a3e7f0eb`)

Autonomous mode does not return `Clear` for a high-risk action. A missing confirmation gate is also not treated as clear when autonomous mode or `approvals.autonomous()` is on. The grant is the existing one-shot `ConfirmationRequest` for the exact action, arguments, and place. A tool argument `approved: true` is not a grant. Unknown risky sandbox commands ask. Obvious read-only sandbox commands stay allowed. Refusal copy for a non-payment confirmation says `確認が必要`. MCP and relay remain default off from the earlier commits. ASK remains `ApprovalSettings` with autonomous false.

## Phase 3 boundary

`ProviderStore.connect` takes `userConfirmed: Boolean = false`. A non-loopback destination that differs in host, scheme, explicit port, or explicit path from the saved key throws `destination_unconfirmed` and does not write the provider. The first saved destination in a process (null previous key) is recorded without a second prompt. Loopback does not ask. A bare legacy host key still compares the host only.

The setup screen shows the normalized `scheme://host:port/path` and enables the save only after `confirmDisplayedDestination()` records that exact string. Account sign-in to the pinned xAI or OpenRouter URL uses the same hold. The model list for xAI uses `XaiAuth.API_BASE`, not `session.apiBase`. `saveAccount` does not rewrite a stored base URL onto a destination that would need confirmation.

`ModelUrlInterceptor` still runs with client redirects and SSL redirects off. A hop to another host or another port is `cross_host` and is not requested, so `Authorization` and the body stay on the original hop. HTTPS to HTTP remains `http_downgrade`. Same host and port may still follow a path redirect, up to five hops.

OTLP export starts only from the trace-send dialog. The address field starts empty. `OtlpSender` uses `includeBody = false`, refuses public HTTP, and does not follow redirects (`OtlpSenderTest` covers a 307). DeepSeek Flash retry, SSE, and Retry-After were not edited. `DeepSeekFlashRetryTest` is 3 tests, 0 failures. These tests use a local mock server. They are not a live DeepSeek call.

## Suites after the final Phase 3 edit

JDK 17: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`. `ANDROID_HOME=$HOME/android-sdk`.

| Command | Result |
| --- | --- |
| `bash ./gradlew --no-daemon --console=plain :core:test` | exit 0, 2m 20s. 2996 tests, 0 failed, 2 skipped (`RescoreRecordedRuns.rescore`, `RescoreEvalRun.rescore`). |
| `bash ./gradlew --no-daemon --console=plain :app:testDebugUnitTest` | exit 0, 19s. 1487 tests, 0 failed, 0 skipped. |
| `bash ./gradlew --no-daemon --console=plain :core:cliTest` | exit 0, 19s. 1 test, 0 failed, 0 skipped. |

The three commands were separate. No test was deleted, disabled, or given a weaker expectation to go green. `EndpointUrlPolicyTest` now requires `cross_host` for a different host, and the interceptor test covers 301, 302, 303, 307, and 308. `ModelSetupViewModelTest.addingTwoProvidersInARowKeepsTheFirstMain` saves the second provider only after the displayed destination is confirmed; the unconfirmed save leaves the first provider in place.

## Open limitations

- Vision-only locate, OCR for the model, and quoting on-screen labels as verified text stay unsupported. They are not a PASS.
- A non-bank page can still expose the host the model already requested. Path, query, and title stay withheld.
- Same-host, same-port path redirects are still followed and keep the original headers. A different host or port is not.
- Sandbox reads use a reader-name allowlist plus a metacharacter check. That is not the root command parser.
- No emulator run. No new signed APK. The signed APKs at `67217715` do not contain these commits.
- Phases 4–8 are not started. Grade A is not assigned.
