# Phase 03 pulse 2

Time: 2026-05-30T20:00:00Z
Files reviewed: dirty tree (23 modified files: 13 src + 10 test), pulse-1.md, steering.md STEER block, JsonRpcError.scala, ProgressEngine.scala
Plan cites: ./design/realignment-plan.md §Phase C (lines 103-346)

## Plan anchor

- Files to produce: 11 leaf case classes + 4 traits + JsonRpcApplicationError — ALL PRESENT inside JsonRpcError.scala (consolidated)
- Files to modify: JsonRpcError.scala, JsonRpcHandler.scala, JsonRpcRoute.scala, JsonRpcCodecImpl.scala, JsonRpcEndpointImpl.scala, CancellationEngine.scala, ProgressEngine.scala + 10 test files + 3 kyo-browser files — ALL PRESENT
- Tests: JsonRpcErrorTest.scala — 10 test cases (184 total JVM tests green as of impl-test-jvm-001.log)
- Public API additions: sealed base + 4 traits + 11 leaves + JsonRpcApplicationError + fromWire + Schema[JsonRpcError]

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | CLEAN: 4 log files in runs/ — compile (jsonrpc, http, browser) + full test run. 184 tests passed. | phases/phase-03/runs/ |
| Compile-only "success" claim | CLEAN: impl-test-jvm-001.log shows "184 tests passed / All tests passed" | log tail |
| Priority inference | CLEAN: no deprioritization; steering STEER block items visible but not resolved (see below) | — |
| Scope substitution | CLEAN: hierarchy matches plan exactly; no collapsing of leaves | all 11 leaves in JsonRpcError.scala |
| Foreach-discards-assert | CLEAN: JsonRpcErrorTest shows concrete leaf-type + fromWire round-trip assertions | impl-test-jvm-001.log |
| Stale-state passing | CLEAN: tests ran against current dirty tree, not a prior commit | log timestamp 7:43:59 PM May 30 |

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signature matches plan | FLAG (MINOR, NOT FIXED): 3 em-dash sites survive from pulse-1 steering. ConfigurationError:277 message string still uses `—`; HandlerPanicError scaladoc at line 324 still uses `—`; JsonRpcError.scala:9-12 scaladoc still uses 4 `—` in list. ProgressEngine.scala stale comment is CLEAN (no em-dash, stale API ref only). | JsonRpcError.scala:9,10,11,12,277,324 |
| No off-plan architecture substitution | CLEAN: 4 traits top-level, all 11 leaves in same file (consolidated vs plan's separate files, but structurally equivalent and plan does not mandate separate files per leaf) | JsonRpcError.scala:35-450 |
| No cross-cutting refactor outside phase | CLEAN: modifications limited to error-hierarchy files + direct callers (browser, test files) | git status --porcelain |
| Internal helpers stay internal | CLEAN: no new public leakage; aux enums nested under their leaf companions | per-file confirmed |
| ProgressEngine.scala stale comment | FLAG (MINOR, NOT FIXED): line 13 still references `JsonRpcError.internalError(...)` in comment; code at line 23 is correct | ProgressEngine.scala:13 |

## Scope-cutting checks

| Leaf | Status | Notes |
|---|---|---|
| JsonRpcParseError | PRESENT_STRICT | Reason enum nested; 5 cases; describe method |
| JsonRpcInvalidRequestError | PRESENT_STRICT | code -32600 |
| JsonRpcMethodNotFoundError | PRESENT_STRICT | code -32601 |
| JsonRpcInvalidParamsError | PRESENT_STRICT | ParamError + Problem nested |
| JsonRpcConfigurationError | PRESENT (em-dash in message NOT FIXED) | message = `s"Configuration error: '$setting' — $reason"` at line 277; plan specifies ` ; ` |
| JsonRpcLifecycleError | PRESENT_STRICT | Stage enum nested |
| JsonRpcTransportError | PRESENT_STRICT | detail + cause fields |
| JsonRpcHandlerPanicError | PRESENT (em-dash in scaladoc NOT FIXED) | line 324 scaladoc uses `—` |
| JsonRpcInternalError | PRESENT_STRICT | Operation enum nested |
| JsonRpcImplementationError | PRESENT_STRICT | private constructor + range check |
| JsonRpcCustomError | PRESENT_STRICT | extends JsonRpcApplicationError |
| JsonRpcApplicationError (abstract open) | PRESENT_STRICT | non-sealed |
| 4 out-of-spec -32602 sites (EndpointImpl:107,203,518,534) | PRESENT_STRICT | reclassified to JsonRpcInternalError(DecodeResult, e) |
| 5 internalError callsites migration | PRESENT_STRICT | HandlerPanic/Configuration/Lifecycle/Transport leaves used correctly |
| CdpBackend.scala:65-71 typed-pattern migration | PRESENT_STRICT | typed pattern match on specialized execution leaves |
| fromWire decoder | PRESENT_STRICT | all 7 standard codes + unknown → JsonRpcCustomError |
| Schema[JsonRpcError] | PRESENT_STRICT | writeFn + readFn via fromWire |
| JsonRpcErrorTest.scala | PRESENT_STRICT | 10 test cases, concrete assertions, wire round-trip; 184 total JVM tests green |
| kyo-jsonrpc-http compile | PRESENT_STRICT | impl-compile-http-jvm-001.log [success] |
| kyo-browser compile | PRESENT_STRICT | impl-compile-browser-jvm-001.log [success] |

## CRITICAL (steer immediately)

None.

## MINOR (queue for post-commit audit)

1. `JsonRpcError.scala:9-12` (4 lines): scaladoc list uses em-dashes as separators. Steering.md STEER block item 2 was NOT applied. Fix: rewrite as `errors surfaced during JSON-text parsing or envelope shape validation` (drop `—` prefix entirely or use `:` after the crossref).
2. `JsonRpcError.scala:277`: message string still uses em-dash `—`. Steering.md STEER block item 1 was NOT applied. Fix: `s"Configuration error: '$setting'; $reason"`.
3. `JsonRpcError.scala:324` (HandlerPanicError scaladoc): `panicked — information that` still present. Steering.md STEER block item 2 was NOT applied. Fix: rewrite sentence without em-dash.
4. `ProgressEngine.scala:13`: stale comment references old `JsonRpcError.internalError(...)` API. Steering.md STEER block item 3 was NOT applied. Fix: update to reference `JsonRpcInternalError(Operation.Other, ...)`.

All 4 items are the identical 3 STEER items from pulse-1 that were queued in steering.md but not yet applied by the impl agent. Tests and compile are green; these are convention violations only. Class-A regex `llm-tells` catalog will reject the commit if items 1-3 are not fixed before the commit gate.

## Recommendation: STEER

The phase is structurally complete and tests are green (184/184, all 3 modules compile). However, all 3 em-dash STEER items from pulse-1 remain unresolved in the dirty tree. The impl agent did not apply the steering.md fixes. These are NOT cosmetic-only: the class-A regex `llm-tells` catalog blocks commit on em-dashes in source output. Recommend repeating the STEER instruction to fix before attempting the commit gate.

STEER: Fix 3 em-dash violations before running flow-verify — JsonRpcError.scala lines 9-12 (scaladoc list dashes), line 277 (message string `—` → `; `), line 324 (HandlerPanicError scaladoc sentence), and ProgressEngine.scala:13 (stale comment) — then re-run `sbt 'kyo-jsonrpc/test'` to confirm green.
