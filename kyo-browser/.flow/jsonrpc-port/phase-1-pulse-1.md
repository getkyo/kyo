# Phase 1 pulse 1

Time: 2026-05-29T11:30:00Z
Files reviewed:
- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala` (829 LoC, lines 1-829)
- `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendSmokeTest.scala` (319 LoC)
- `kyo-browser/shared/src/test/scala/kyo/internal/JsonRpcPortInvariantsSpec.scala` (452 LoC)
- `kyo-browser/shared/src/main/scala/kyo/internal/CdpTypes.scala` (diff: +14 LoC, probe types)
- `build.sbt` (diff: kyo-browser dependsOn change)
- `kyo-browser/.flow/jsonrpc-port/phase-1-decisions.md` (14 decisions, last at 00:00Z)
- `kyo-browser/.flow/jsonrpc-port/05-plan.md` Phase 01 section
- `kyo-browser/.flow/jsonrpc-port/05-plan.yaml` phases[0]
- `kyo-browser/.flow/jsonrpc-port/phase-1-prep.md`

Plan cites: ./05-plan.md §Phase 01

---

## Plan anchor

- Files to produce: 2 expected (`CdpBackend.scala` new runtime class, `CdpBackendSmokeTest.scala`, `JsonRpcPortInvariantsSpec.scala`) / ALL PRESENT in dirty tree
- Files to modify: 1 expected (`build.sbt`) / PRESENT AND CORRECT
- Tests: 24 expected (10 SmokeTest + 14 InvariantsSpec) / 27 found (10 SmokeTest + 17 InvariantsSpec)
- Public API additions: none expected / none present (all `private[kyo]`)

---

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| `@nowarn` / `@SuppressWarnings` / `cancel()` / `pending()` in test bodies | CLEAN | grep on both test files: 0 matches |
| `case _ =>` swallowing assertions in test bodies | CLEAN | One instance at `JsonRpcPortInvariantsSpec.scala:364` is inside a server-side method handler (`case _ => NavigateResult("f")`) not in a test assertion path; not a swallow |
| `Result.Panic` not surfaced in test assertion matches | CLEAN | No bare `Result.Panic` match arms; test outcomes branch on `Result.Failure` and `Result.Success` explicitly |
| Verification commands actually run | UNKNOWN | The decisions log is 80+ min stale; no evidence of compile or test runs in the dirty tree; can't confirm the verification command was ever executed against the current state |
| Compile-only "success" claim | FLAG: INV-020 test is a bare `succeed` | `JsonRpcPortInvariantsSpec.scala:395` - INV-020 ("green-build on JVM, JS, Native") is implemented as `succeed` unconditionally. The plan's INV-020 leaf calls for actual `sbt ... Test/compile` invocations. The test correctly explains this is a compile gate, but the test itself does not perform the sbt invocations. Acceptable if the supervisor ran the sbt verification separately; not acceptable as the only gate. |
| Priority inference | CLEAN | No plan-trimming or "later" deferrals found |
| Scope substitution | FLAG: forwarding methods and off-plan overload | See drifting checks below |
| Foreach-discards-assert | CLEAN | No `foreach` over assertion sequences without threading results |
| Stale-state passing | CLEAN | No test passes a pre-initialized state that bypasses the init path |

---

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signature matches plan | CLEAN | No public API additions; all new types are `private[kyo]`; `BrowserGetVersionParams` / `BrowserVersionResult` in `CdpTypes.scala` are `private[kyo]` |
| `kyo.Browser`, `kyo.BrowserException`, `kyo.BrowserTab`, `kyo.BrowserSnapshot` unchanged | CLEAN | `git diff` shows only `CdpBackend.scala` and `CdpTypes.scala` modified; no changes to `Browser.scala`, `BrowserException.scala`, `BrowserTab.scala`, `BrowserSnapshot.scala` |
| No manual JSON (INV-011) | CLEAN | No `Json.parseString`, no `"jsonrpc"` string literal, no `derives Json` in `CdpBackend.scala` |
| No bare `var` for shared state | CLEAN | Zero `var ` matches in `CdpBackend.scala` |
| No `Fiber.block` | CLEAN | Zero matches in `CdpBackend.scala` |
| No em-dashes or en-dashes in added lines | CLEAN | No em/en-dashes in `CdpBackend.scala`; the characters in `JsonRpcPortInvariantsSpec.scala:167` are inside a char literal comparison `c == '—'` (the invariant check itself), not prose |
| `// flow-allow:` markers have rationale | CLEAN | Two occurrences at lines 225 and 639 both carry full rationale text |
| `CdpClient.scala` UNCHANGED in Phase 01 | CLEAN | `git diff` shows 0 lines changed in `CdpClient.scala` |
| `build.sbt` dependency addition correct | CLEAN | `.dependsOn(`kyo-http`, `kyo-jsonrpc`, `kyo-jsonrpc-http`)` - exact match to plan |
| No off-plan architecture substitution | FLAG: THREE deviations | See findings below |
| No cross-cutting refactor outside phase | CLEAN | Only `kyo-browser` files touched |
| Internal helpers stay internal | MINOR: transport-based `initUnscoped` is `private[kyo]` not `private` | `CdpBackend.scala:359` - plan did not specify this overload; it is accessible outside the companion |

---

## Scope-cutting checks

| Leaf | Status | Notes |
|---|---|---|
| 1: init via inMemory transport returns live backend | PRESENT_STRICT | `CdpBackendSmokeTest.scala:64` |
| 2: init fails fast when probe returns Closed | WEAKENED | Test at line 75 checks for `BrowserConnectionLostException` but plan (05-plan.md:492-494) + Decision 1 require `BrowserSetupFailedException`. The implementation re-raises `BrowserConnectionLostException` (line 208 of CdpBackend.scala) rather than converting to `BrowserSetupFailedException`. The test was written to match the implementation, not the plan. |
| 3: send writes wire bytes matching legacy CDP envelope | PRESENT_STRICT | `CdpBackendSmokeTest.scala:88` |
| 4: session-scoped backend stamps sessionId via ExtrasEncoder | PRESENT_STRICT | `CdpBackendSmokeTest.scala:113` |
| 5: Page.javascriptDialogOpening routes via ctx.extras | PRESENT_STRICT | `CdpBackendSmokeTest.scala:142` |
| 6: Page.javascriptDialogOpening auto-dismisses when no handler | PRESENT_STRICT | `CdpBackendSmokeTest.scala:170` |
| 7: Runtime.executionContextCreated routes via ctx.extras | PRESENT_STRICT | `CdpBackendSmokeTest.scala:201` |
| 8: Page.downloadWillBegin routes via ctx.extras | PRESENT_STRICT | `CdpBackendSmokeTest.scala:239` |
| 9: close(gracePeriod) sequences endpoint.close, dialogQueue.close, dialogDrainer.interrupt | PRESENT_STRICT | `CdpBackendSmokeTest.scala:273` |
| 10: dialog drainer issues sendUnmatched with negative JsonRpcId.Num | PRESENT_STRICT | `CdpBackendSmokeTest.scala:289` |
| 11: INV-008 source paired with test in same commit | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:87` - checks file existence |
| 12: INV-009 package and top-level type check | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:111` |
| 13: INV-011 no manual JSON | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:126` |
| 14: INV-012 no `var` for shared state | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:136` |
| 15: INV-013 no unannotated AllowUnsafe | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:152` |
| 16: INV-014 no em-dashes | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:164` |
| 17: INV-015 round-trip ExtrasEncoder + ctx.extras | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:184` |
| 18: INV-016 Browser.getVersion probe converts Closed | WEAKENED | Test checks `BrowserConnectionLostException` but plan requires `BrowserSetupFailedException` (see leaf 2) |
| 19: INV-017 Closed -> BrowserConnectionLostException | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:262` |
| 20: INV-017 JsonRpcError -> BrowserProtocolErrorException | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:284` |
| 21: INV-017 Timeout -> BrowserConnectionLostException | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:304` |
| 22: INV-018 dialogIdCounter negative ids disjoint from SequentialInt | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:348` |
| 23: INV-019 no Fiber.block | PRESENT_STRICT | `JsonRpcPortInvariantsSpec.scala:403` |
| 24: INV-020 green-build JVM/JS/Native | WEAKENED | `JsonRpcPortInvariantsSpec.scala:414` is bare `succeed` - no actual sbt invocation |
| Q-002 ratification: Browser.getVersion probe | PRESENT but wrong exception type | `CdpBackend.scala:206-218` - probe present, but raises `BrowserConnectionLostException` not `BrowserSetupFailedException` |
| 5 notification handlers | PRESENT_STRICT | All 5: `Page.javascriptDialogOpening`, `Runtime.executionContextCreated`, `Runtime.executionContextDestroyed`, `Page.downloadWillBegin`, `Page.downloadProgress` at lines 439, 452, 462, 476, 487 |
| ExtrasEncoder for sessionId wired | PRESENT_STRICT | `CdpBackend.scala:40-44` (send) and `CdpBackend.scala:508-512` (drainer) |
| Dialog drainer fiber | PRESENT_STRICT | `buildDialogDrainer` at `CdpBackend.scala:499-524` |
| lastEvaluateParams AtomicRef field | PRESENT_STRICT | Class field at line 25; write site at lines 37-39 |
| CdpBackendOld object renamed + annotated | PRESENT_STRICT | `CdpBackend.scala:641` with `// flow-allow: phase-01 byte-equivalent coexistence; deleted in Phase 02` |

---

## CRITICAL findings

**CRITICAL-1: Probe failure raises wrong exception type (silent design change from plan + Decision 1)**

`CdpBackend.scala:207-211`: The `Abort.recover[BrowserReadException]` block around the `Browser.getVersion` probe catches `BrowserConnectionLostException` and re-raises it as `BrowserConnectionLostException` (same type, augmented message). The plan pseudocode (`05-plan.md:234-241`) maps this to `BrowserSetupException(...)`. Decision 1 in the decisions log correctly notes `BrowserSetupException` is a sealed trait and corrects to `BrowserSetupFailedException`. The actual implementation silently uses `BrowserConnectionLostException` instead - a third choice that is neither the plan nor Decision 1. The smoke test (line 75) and INV-016 test (line 233) both check `BrowserConnectionLostException`, meaning they validate the wrong exception type. The test-implementation alignment hides the deviation from the plan.

Specific steer: Change the probe recovery in `CdpBackend.scala:207-211` to raise `BrowserSetupFailedException` (the concrete instantiable subtype of `BrowserSetupException`), matching Decision 1 in the decisions log. Update the smoke test (line 75-83) and INV-016 test (line 245-244) to assert `BrowserSetupFailedException`. Log this as Decision 15 in `phase-1-decisions.md`.

**CRITICAL-2: `initUnscoped` return type carries `Scope` - contradicts prep doc gotcha #1 and Decision 2**

`CdpBackend.scala:149`: `initUnscoped(wsUrl, launchCfg)` is declared as returning `CdpBackend < (Async & Scope & Abort[BrowserReadException])`. Prep doc gotcha #1 and Decision 2 both explicitly state that `Scope.run` must wrap the for-comprehension body to discharge the `Scope` from `JsonRpcHttpTransport.webSocket` and `JsonRpcEndpoint.init`, so `initUnscoped` returns `Scope`-free. The implementation does the opposite: it keeps `Scope` in the return type, meaning `CdpBackend.init` (line 141) calls `Scope.acquireRelease(initUnscoped(...))` which double-nests `Scope`. Decision 2 text explicitly warns against this. The second `initUnscoped` overload (transport-based, line 361) makes the same choice. Neither deviation is logged.

This may or may not compile correctly depending on how Kyo's `Scope.acquireRelease` handles a `Scope`-bearing input. If it type-checks, the behavior might be correct (the outer Scope.acquireRelease absorbs the inner Scope), but it contradicts the design doc and decisions log. This must be logged as a decision with a rationale, or reverted to use `Scope.run { ... }` as specified.

Specific steer: Either (a) add a `Scope.run { ... }` wrapping the for-comprehension in `initUnscoped` so the return type becomes `CdpBackend < (Async & Abort[BrowserReadException])` matching Decision 2, or (b) log Decision 15 explaining why keeping `Scope` in `initUnscoped` is correct and update `CdpBackend.init` accordingly. Do not leave the decisions log contradicting the implementation.

**CRITICAL-3: 28 off-plan forwarding methods in `CdpBackend` companion**

`CdpBackend.scala:221-353`: The companion object contains 28 forwarding methods that delegate every typed CDP call to `CdpBackendOld`. These are marked with `// flow-allow: phase-01 byte-equivalent coexistence; deleted in Phase 02` at line 225. The plan does NOT specify these forwarding methods in the companion. The plan's design is: `CdpBackendOld` contains the 228 LoC of legacy wrappers verbatim; the companion holds only constants + init + `initUnscoped` + the notification-builder helpers. The forwarding layer adds ~130 LoC of unmandated code. The plan at §Phase 01 "Files to produce" lists no forwarding methods in the companion object.

This is an off-plan architectural substitution. The forwarding methods do function to keep call sites compiling (the same goal as `CdpBackendOld`), but they duplicate the forwarding path unnecessarily. The call sites in `Browser.scala` call `CdpBackend.<method>` which now delegates to `CdpBackendOld.<method>`. The `CdpBackendOld` wrapper already achieves byte-equivalent coexistence as designed.

Specific steer: Remove the 28 forwarding methods from the `CdpBackend` companion (`CdpBackend.scala:221-353`). Existing call sites that reference `CdpBackend.navigate(...)` etc. should reference `CdpBackendOld.navigate(...)` directly in Phase 01. Log this removal in `phase-1-decisions.md` as a scope correction.

---

## MINOR findings (queue for post-commit audit)

**MINOR-1: Off-plan `initUnscoped(transport, launchCfg)` overload**

`CdpBackend.scala:359-422`: A second `initUnscoped` overload taking a pre-wired `JsonRpcTransport` instead of a WS URL. This convenience method is used by the smoke tests and InvariantsSpec tests. It is NOT in the plan (plan specifies one `initUnscoped(wsUrl, launchCfg)` only). The smoke tests instead use this overload. This could be replaced by calling `JsonRpcTransport.inMemory` at the test level and passing the result to the standard code path, without an extra overload.

The overload is marked `private[kyo]` (visible across the package) rather than `private`. Per feedback on `feedback_no_backcompat`, adding surface not in the plan is problematic. However since it's `private[kyo]` and genuinely useful for testing, this is a MINOR finding rather than CRITICAL.

**MINOR-2: Decision log silent on three design choices**

The decisions log has no entry for:
- Why `BrowserConnectionLostException` was chosen over `BrowserSetupFailedException` for probe failure (Decision 1 contradicted by implementation)
- Why `Scope` is kept in `initUnscoped` return type (Decision 2 contradicted by implementation)
- Why 28 forwarding methods were added to the companion (no plan basis)
- How `Browser.DialogEvent` constructor adaptation was made (plan uses 4-arg form, actual type has 3-arg form with `DialogType`)

The decisions log was last updated 80+ minutes ago and is missing at least 4 decisions that should have been logged.

**MINOR-3: `handleDialogOpening` constructs `Browser.DialogEvent` differently from plan pseudocode**

`CdpBackend.scala:562-571`: The implementation maps `params.\`type\`` (a String) to `Browser.DialogType` ADT and constructs `Maybe[String] response` based on whether it's a prompt. The plan's pseudocode at `05-plan.md:366` calls `Browser.DialogEvent(params.\`type\`, params.message, decision._1, decision._2)` (4-arg String form). The actual `Browser.DialogEvent` case class (verified in `Browser.scala:3019`) takes `(kind: DialogType, message: String, response: Maybe[String])` - 3 args. The agent correctly adapted to the real API, but this deviation from the plan pseudocode was not logged.

**MINOR-4: Test count is 27 vs plan's 24**

`JsonRpcPortInvariantsSpec.scala` has 17 tests vs plan's 14. The extra 3 are INV-022, INV-023, INV-024 - all valid invariant checks that are in the plan's "produced invariants" list but not in the test IDs 11-24. Having extra tests is not a red flag; it is a coverage increase. However the discrepancy should be logged.

**MINOR-5: INV-020 and INV-023 are stub `succeed` tests**

INV-020 ("green-build on JVM, JS, Native") and INV-023 ("no git push") are both bare `succeed` stubs. The plan describes behavioral verification for INV-020 (actual sbt invocations). Decision 10 in the decisions log explains the structural-grep fallback for git-based properties, which is reasonable for INV-023, but INV-020 (compile success) should be a real compile check run by the supervisor rather than a stub.

---

## Plan-vs-actual LoC analysis

The 808 LoC (actual file reads 829) breaks down as:
- New `CdpBackend` class: ~98 LoC (on-plan)
- `CdpBackend` companion (constants + init + initUnscoped + builders + helpers): ~505 LoC
  - On-plan portion (constants, init, WS-based initUnscoped, 5 builder methods, helpers): ~280 LoC
  - Off-plan forwarding methods (28 delegates to CdpBackendOld): ~128 LoC
  - Off-plan transport-based initUnscoped overload: ~63 LoC
- `CdpBackendOld` object (verbatim 228 LoC carryover): ~197 LoC

Expected new additions: ~376 LoC (98 class + 280 companion) + 228 LoC CdpBackendOld carryover = ~604 LoC total
Actual: 829 LoC
Unexplained surplus: ~225 LoC, attributable to the two off-plan additions (forwarding methods + transport overload).

The bloat is NOT due to reward hacking (no tests are weakened by the extra code, no assertions are hidden). The agent invented two structural additions that are architecturally reasonable but not in the plan.

---

## Decisions the agent SHOULD have logged but did not

1. **Probe exception type (contradicts Decision 1)**: Why `BrowserConnectionLostException` was chosen for probe failure re-raise instead of `BrowserSetupFailedException`. Decision 1 explicitly says `BrowserSetupFailedException` is correct; the implementation uses a third approach without recording the rationale.

2. **Scope in initUnscoped (contradicts Decision 2)**: Why `Scope` was preserved in `initUnscoped` return type instead of discharging via `Scope.run`. Decision 2 explicitly documents Scope.run wrapping; the implementation does the opposite without explaining.

3. **28 forwarding methods in companion**: Why this structural pattern was chosen over the plan's approach of having call sites reach `CdpBackendOld` directly.

4. **Transport-based `initUnscoped` overload**: The addition of a second `initUnscoped(transport, launchCfg)` overload not in the plan, its `private[kyo]` visibility scope, and why tests use it instead of the WS-based factory.

5. **`Browser.DialogEvent` constructor mismatch**: The plan's pseudocode uses a 4-arg constructor that does not exist in the actual codebase; the agent adapted to the 3-arg `(DialogType, String, Maybe[String])` form. This design adaptation affects the dialog-recording behavior (the meaning of the `response` field differs from the plan's `(accept, promptText)` pair).

---

## Recommendation: STEER

Write the following specific instructions to `steering.md` immediately:

**STEER-1 (CRITICAL)**: `CdpBackend.scala:207-211` - change the probe recovery to raise `BrowserSetupFailedException(...)` instead of re-raising `BrowserConnectionLostException`. The concrete exception type is `BrowserSetupFailedException(message, cause: Maybe[Throwable])`. Update `CdpBackendSmokeTest.scala:81` and `JsonRpcPortInvariantsSpec.scala:239` to assert `BrowserSetupFailedException`. Log as Decision 15 in `phase-1-decisions.md`.

**STEER-2 (CRITICAL)**: Resolve the `Scope`-in-`initUnscoped` contradiction: either (a) add `Scope.run { ... }` around the for-comprehension body in both `initUnscoped` overloads to match Decision 2, or (b) explicitly log why keeping `Scope` is correct and verify `Scope.acquireRelease(initUnscoped(...))` does not double-nest. Log the resolution as Decision 16.

**STEER-3 (CRITICAL)**: Remove the 28 forwarding methods from the `CdpBackend` companion (`CdpBackend.scala:221-353`). Call sites that currently call `CdpBackend.navigate(...)` should call `CdpBackendOld.navigate(...)` directly. This restores the plan's intended structure and removes ~128 LoC of unmandated code. Log as Decision 17.

**STEER-4 (MINOR)**: Log the transport-based `initUnscoped` overload as Decision 18 in `phase-1-decisions.md` with a rationale. If it remains, change visibility from `private[kyo]` to `private` to prevent accidental external use.

**STEER-5 (MINOR)**: Log the `Browser.DialogEvent` constructor adaptation as Decision 19 in `phase-1-decisions.md`.
