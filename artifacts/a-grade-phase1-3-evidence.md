# Mika-JP A-grade hardening, Phases 1–3 evidence

This run is the implementation owner for Phases 1–3 only. It does not certify grade A. Phases 4–8 are not done. An independent auditor must review a frozen signed APK before any A grade.

## Preflight

- Repository: `Grok-Benkei/Mika-JP` only. Upstream `yebisu0529-ship-it/Mika-JP` and fork `main` were not touched.
- Base branch `security/harden-android-release` and existing draft PR #2 head were `6721771517569c6f0d6a78b83f2db48e3946b3df` before edits. No divergence from that parent confirmation was found.
- New work is only on `security/a-grade-hardening`, created from that commit. The branch did not already exist, so nothing on it was reset.
- PR #2 stays open, draft, and unmerged. This branch's pull request is a separate draft based on `security/harden-android-release`.
- `gradlew.bat` has a pre-existing line-ending change and was not committed.

## Commits

| Phase | Commit | Subject |
| --- | --- | --- |
| 1 | `16b736680724b6574f1732fe40b417bcc1336838` | fail closed on screen, browser, and notification egress |
| 2 | `87448ccf` | confirm send, delete, settings, and external actions |
| 3 | `80180409b666a56cedbb4a392126a9eca719cd05` | confirm model-host changes; redaction and destination tests |

## Phase 1 — egress

Common decision: `EgressPolicy` plus `ScreenSnapshot.egressScene()`.

Allow body and screenshot only for an identified, non-browser app with no password node, no payment/auth control, and no other unsafe window. Otherwise the tool result is `egressBlocked` plus a reason code. Pixel contents are not blurred and then sent.

Blocked reasons include `browser_or_webview`, `bank_page`, `password_visible`, `payment_or_auth`, `sensitive_app`, `unknown_package`, `multiwindow_unproven`, and `sensitive_notification`. An empty virtual screen with no window is `no_app_open`: the launch hint can still be shown, and no screenshot is sent.

Wired paths:

- `ScreenSession` observe, fresh look, and post-action screen attachment
- screenshot tool and vision locator capture
- web page read, JavaScript result, page script result/logs, and network capture body
- web evidence page text (`pageStart` no longer reads the page into the model)
- notification bodies in `ScreenAccessibility`
- history replay of untrusted phone/web observations

Tests in `EgressPolicyTest` cover a banking package, a bank URL inside Chrome, a WebView, a password node, an unknown package, a sensitive notification, a second window, and a model-text override (the policy has no off switch). Existing web tests that expected page text, titles, or capture bodies to reach the model now expect the reason code and the absence of that text.

Tradeoff: the in-app browser and WebView no longer send page text, element names, screenshots, script results, or response bodies to the model. Navigation status can still include the URL and page title. That title is a remaining body-sized leak and is not a PASS. The payment/auth cue matches control names (not ordinary text nodes) against a short marker list. It does not cover every bank, wallet, or PII screen. Unsupported coverage is not labeled PASS.

Phase 1 regression after the commit, before Phase 2:

- `:core:test` 2985 tests, 2983 passed, 0 failed, 2 skipped
- `:app:testDebugUnitTest` 1486 passed
- `:core:cliTest` 1 passed

## Phase 2 — confirmation

`ApprovalPolicy` now requires a user decision for `External`, `Irreversible`, and `Unknown` actions, not only for payments and tools that already declared an ask. Read and ordinary `Change` actions still run. On a UI run with the user present, the decision is Ask. Unattended, channel, trigger, MCP, and child runs are Deny.

The confirmation is the existing one-shot grant. Settings changes ask for the exact key and value. Schedule changes other than `list` ask for the exact argument object. MCP open-world and destructive tools ask for the tool name plus the exact arguments. A model argument such as `approved: true` is not a grant.

`McpToolSession` no longer sets `autonomous = true`. An unattended MCP send is denied and the remote tool is not called. MCP and relay switches were already default off, persist that off state, and do not listen until the user turns them on. Private-network bind behavior from the previous hardening commit is unchanged.

The explicit user autonomous setting still skips the ask. The model cannot flip that setting; those keys stay locked. This is a user control, not a model bypass.

Tradeoff: send, delete, submit, and payment controls now stop for confirmation even when they do not spend money. The refusal text still says the action involves spending. That wording is broader than the new rule and should be corrected later. It does not weaken the block.

Phase 2 regression:

- `:core:test` 2988 tests, 2986 passed, 0 failed, 2 skipped
- `:app:testDebugUnitTest` 1486 passed
- `:core:cliTest` 1 passed

Command: `ANDROID_HOME=$HOME/android-sdk ./gradlew --no-daemon :core:test :app:testDebugUnitTest :core:cliTest`

## Phase 3 — model destination, logs, OTLP

Model HTTP was already HTTPS-by-default, with loopback HTTP rewritten to `localhost` and public HTTP rejected. The model client does not follow redirects itself; `ModelUrlInterceptor` checks each hop and refuses an HTTPS-to-HTTP downgrade or a disallowed host. API keys are not put in the exception message. DeepSeek Flash retry, SSE, and Retry-After behavior was not edited. `DeepSeekFlashRetryTest` is 3 tests, 0 failures.

`ModelDestinationConsent.needsConfirmation` is true when a saved host would change to a different non-loopback host. `ProviderStore.connect` throws `destination_unconfirmed` and does not save the provider when `userConfirmed` is false. The model setup save paths pass `userConfirmed = true` because that save is the user action. The default of the flag is still true so existing store tests keep compiling. A caller that passes false cannot move the host. There is no model tool that calls `connect`.

Android KeyStore AES-GCM key storage was not changed. `Redactor` still masks `sk-`, Google, GitHub, and Bearer tokens in log text. A new test uses a dummy `sk-dummy…` value and checks that the dummy token does not remain. OTLP export is not started unless the user sends a trace. `OtlpSender.send` keeps `includeBody = false`, refuses redirects, and rejects public HTTP endpoints. `OtlpSenderTest` is 2 tests, 0 failures. This does not prove every log line or crash path. That remains unverified.

Phase 3 tests run:

- `ModelDestinationConsentTest` 2 passed
- `RedactorTest` 7 passed
- `DeepSeekFlashRetryTest` 3 passed
- `EndpointUrlPolicyTest` 9 passed
- `ProviderStoreTest` 55 passed, including `unconfirmedDestinationChangeIsNotSaved`
- `OtlpSenderTest` 2 passed
- `ModelSetupViewModelTest` 60 passed

## What this run does not claim

- No signed release was rebuilt. The previous signed APKs on `67217715` do not contain these commits. ASK-in-APK, DEX, and certificate checks wait for Phase 6.
- The 3238 rootfs entries, native provenance, full Gradle transitives, and OSV reachability were not inspected.
- No emulator or device run was performed. Dynamic destination and payload checks are UNVERIFIED. Destination-only observation would not prove payload safety anyway.
- Browser navigation can still return a page title. The payment/auth list is a heuristic. Free-text PII inside an ordinary app is not fully classified.
- Grade A is not assigned.
