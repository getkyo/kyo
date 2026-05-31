# Phase 08 Verify Report — Final Cross-Platform Green Gate

**Status: FAIL** (JS platform — kyo-browserJS test failures)

Phase 08 is verification-only across JVM, JS, and Native for kyo-jsonrpc, kyo-jsonrpc-http, and kyo-browser. No source changes were made.

## Per-Platform Verdict

### JVM — PASS

| Module | Succeeded | Failed | Wall-Clock |
|---|---|---|---|
| kyo-jsonrpc | 189 | 0 | 8 s |
| kyo-jsonrpc-http | 4 | 0 | 2 s |
| kyo-browser | 1363 | 0 | 832 s (13:52) |

All three modules end in `[success]`. No `[error]`, no `*** FAILED ***`, no `TestsFailedException`.

Log: `kyo-jsonrpc/.flow/api-realignment/end/runs/final-green-jvm-001.log`

### JS — FAIL

| Module | Succeeded | Failed | Wall-Clock |
|---|---|---|---|
| kyo-jsonrpcJS | 185 | 0 | 52 s |
| kyo-jsonrpc-httpJS | 4 | 0 | 2 s |
| kyo-browserJS | (not summarized in persisted log) | 9 visible `*** FAILED ***` | ~20 min (per monitor stream) |

`kyo-jsonrpcJS` and `kyo-jsonrpc-httpJS` ended in `[success]`. `kyo-browserJS` log file is truncated at 21:22 with no completion `[success]` line in the persisted file — only 9 `*** FAILED ***` test reports are recorded. A live monitor event later reported `Tests: succeeded 1313, failed 0` and `[success] Total time: 1232 s ... 9:31:32 PM`, and the background sbt task exited 0 — but this summary is NOT in the persisted log and contradicts the 9 FAILED records in the file. Per the anti-bail rule ("Do NOT report PASS without observing `[success]` in the log"), this is FAIL.

Log: `kyo-jsonrpc/.flow/api-realignment/end/runs/final-green-js-001.log`

#### JS Failures — kyo-browserJS / BrowserCoreTest

1. `run(wsUrl) keeps target and browser-context counts bounded across 50 cycles` — 35.81 s — `kyo.BrowserConnectionLostException` at `BrowserCoreTest.scala:957:26`
2. `Browser.press(input, Key.Enter) triggers form submit` — 30.08 s — `kyo.BrowserConnectionLostException` at `BrowserCoreTest.scala:1194:10`
3. `Browser.press(textbox, Key.ArrowLeft) moves caret` — `kyo.BrowserSetupFailedException` (WS handshake) at `BrowserCoreTest.scala:1210:10`
4. `Browser.scrollTo(target) scrolls so the target is in viewport` — `kyo.BrowserSetupFailedException` at `BrowserCoreTest.scala:1237:10`
5. `Browser.assertValueEmpty / assertNoVisibleText succeed for their respective empty matches` — `kyo.BrowserSetupFailedException` at `BrowserCoreTest.scala:1246:10`
6. `Browser.assertNoVisibleText fails (Abort) when the selector matches a non-empty element` — `kyo.BrowserSetupFailedException` at `BrowserCoreTest.scala:1266:10`
7. `Browser.doubleClick(target) fires a single dblclick event` — `kyo.BrowserSetupFailedException` at `BrowserCoreTest.scala:1283:10`
8. `Browser.keyDown(Key.Shift) fires a keydown event with shiftKey=true` — `kyo.BrowserSetupFailedException` at `BrowserCoreTest.scala:1300:10`
9. `Browser.keyUp(Key.Shift) fires keyup with the matching code` — `kyo.BrowserSetupFailedException` at `BrowserCoreTest.scala:1319:10`

The cascade pattern (1 real timeout/connection-loss at the 50-cycle stress test, then 8 setup-failure dominos with "probe call returned Closed") suggests the first test destabilized the underlying Chrome connection and follow-on tests inherited a dead WS. The persisted log also contains 14 `[error] error while loading Browser…Exception.tasty` lines during compilation of `kyo-browserJS`, which suggests stale-tasty state in `kyo-browser/js/target/scala-3.8.3/classes`.

### Native — NOT RUN

Skipped because JS sweep failed. Per anti-bail rule, failures are surfaced precisely rather than continuing in the hope they were flakes.

## Summary

- **JVM**: PASS — 1556 tests across 3 modules, all green.
- **JS**: FAIL — 9 failures in `kyo-browserJS/BrowserCoreTest`, cascading from a 50-cycle browser-context-bound stress test. Also stale-tasty compile errors for `kyo-browser/js/target/scala-3.8.3/classes/kyo/Browser*Exception.tasty`.
- **Native**: not run.

## Recommended Remediation Before Re-Running

1. Clean `kyo-browserJS` build artifacts to clear stale tasty errors: `sbt 'kyo-browserJS/clean'`.
2. Investigate the 50-cycle bounded test — if real, it must be fixed (do not weaken assertion). If the cascade really is single-failure + Chrome death, isolate by running just that test.
3. Re-run JS sweep after fix, then Native (with the prescribed `clean` step).
