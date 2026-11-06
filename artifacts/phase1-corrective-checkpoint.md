# Phase 1 corrective checkpoint

This is not a grade. Phase 2 and Phase 3 gaps stay open. No release was built.

HEAD before this commit: `d35c3a5205163d9b9438d4d2f484b9a87283356c`.
Base of the branch remains `6721771517569c6f0d6a78b83f2db48e3946b3df`.

## Final suites

Run independently after the last edit. Exit 0 on each.

| Suite | Tests | Failed | Skipped |
| --- | ---: | ---: | ---: |
| `:core:test` | 2993 | 0 | 2 |
| `:app:testDebugUnitTest` | 1487 | 0 | 0 |
| `:core:cliTest` | 1 | 0 | 0 |

The two core skips are the existing ones: `RescoreRecordedRuns.rescore` and `RescoreEvalRun.rescore`.

The inventory run on the tree before these repairs was `:core:test` 2992 tests, 69 failed, 2 skipped (exit 1, 2m 31s, no hang). `:app:testDebugUnitTest` and `:core:cliTest` were already green on that tree (exit 0).

## Contract that the 69 failures were hitting

An identified ordinary app with content is `text_withheld`: refs, roles, and bounds may be returned; names, titles, OCR, resource ids, and screenshots may not. A change in the withheld text is a `screenDigest` so a reread of a changed screen is not treated as the same page. A phrase is reported in `userTextOnScreen` only when that phrase is in the trusted user utterance and also on the local screen, and only on a structure-only screen. At most eight phrases, each at most eight characters. Browser, WebView, password, bank, unknown package, sensitive app, and a second window stay fully blocked, including history replay and the verifier's copy of the observation. A model quote of withheld text is not verified. A route is not learned from that quote. Redaction alone is not evidence.

## What was a product hole

- History replay copied the original page text into verifier observations after the tool result had been replaced. Both paths now use `egress_blocked:replay`.
- Stripped element lists hashed the same when only the label changed, so a long task of distinct screens stopped on `read_loop`. The read fingerprint now mixes `screenDigest`. The digest is not the text.
- A batch continued on an unlabeled screen because the image reason was dropped whenever text was withheld. The reason code is kept and no image is attached, so the batch stops for review.
- A missing feedback placeholder made a download navigation throw. The hint uses the existing `navigate_became_download` text with an empty file name. The path and file name are not returned. Status and byte count remain.
- Bank hosts are omitted from place-change metadata (`permittedHost`). A non-bank host the model already asked to open can still appear as `currentSite`. The path, query, and title do not.

## Assertion changes, and why they are stricter

Each row keeps the original scenario. The new check is the privacy result plus the action that does not need the withheld text.

| Scenario | What stayed | What the assertion now requires |
| --- | --- | --- |
| Screen diff and stable refs | The added and removed refs, the next tap on the new snapshot, ref identity across observes | `新的`, `项3`, and `搜索` are absent. Roles and refs remain. |
| Evidence, records, recall | The tap or the recorded step and place | `搜索`, `明天晴`, `第N屏`, and `确认支付` are absent. Images are empty. The reason is present. A quoted screen answer is not grounded. |
| Repeated reads | One stored read for an unchanged screen | The stored read is the withhold reason, and `项1` is absent. |
| Memory quote of an address | `quote_not_from_user`, store stays empty | The address is also absent from the observe result. |
| History of a web observation | The trusted note and the sandbox output | The menu price and title are absent. The web body is `egress_blocked:replay`. |
| Web navigation evidence | HTTP status, launch of the requested app | Page title and full URL are absent. `currentSite` may be the host already requested. A download does not include the file name. |
| Place change on the web | A new path is still a place change. A hash-only change is not. | `url` and `title` are absent. The host and `browser_or_webview` remain. |
| Ordinary vision locate with a unique tree label | The WLAN element match and the tap by that ref | A vision-only lookup returns `egress_blocked` and does not call the vision model. |
| Described typing and blank-screen locate | Nothing is typed or tapped when the box cannot be resolved locally | The error names `egress_blocked`. No screenshot is sent. A send is not performed. |
| Screenshot and frame attachment | The action still returns ok, and the JPEG encoder is checked on a fixture byte array that was not captured from the screen | Content-screen results have zero images and do not contain `搜索`. |
| Verifier | An unread change still consults the verifier. A pass whose quote is not in the reads stays unverified. | `搜索` is absent from the tool result and from the verifier request. `verified` is false. |
| Route learning | A completion with no screen quote can still count as done. A give-up does not. | A quote of `新笔记` that was not in the reads does not increment successes. |
| Compaction | Rewrites are compression markers. Prefix share stays above 0.85. | The old step lists (`10, 18, 27` and the adapter lists) are not pinned. Gaps are at least six requests, because withheld names make tool results shorter and the 80k trigger is crossed later. |
| Loop gold files | The taps, steers, language notice, and task completion | Regenerated only after those asserts passed. Tool-result lines no longer contain `"name"`. Outcomes that quoted a withheld label are `verified=false`. |
| Prompt gold files | The tool catalog examples | The navigate example drops URL and title and records `browser_or_webview` plus `currentSite`. The observe example drops the button name and records `text_withheld`. |

`LocatorTest` vision-only cases assert `egress_blocked` and an empty vision-model request list. The unique tree match and the protected-content and empty-screen cases stay.

## Local match

`UserUtterances.seenOnScreen` returns only substrings of what the user said. `ToolDispatcherTest.userPhraseIsMatchedLocallyAndTheRestOfTheScreenStays` checks that `蓝牙` from `看看蓝牙开没开` is reported on a settings screen and `已开启` is not, and that the same screen inside Chrome reports neither string.

## Still unsupported, and not marked as a pass

Finding a control by sending the screenshot, OCR for the model, and treating a model's quotation of an on-screen label as verified. Those paths now fail closed. A non-bank `currentSite` host is still present in navigation and place-change results. That is the host the model already requested, not the page title, and it is not a claim that the payload has no page metadata.

Phase 2 (autonomous mode still skips high-risk confirmation; spend-only refusal copy) and Phase 3 (`userConfirmed` still defaults to true) are unchanged by this commit.
