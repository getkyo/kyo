# Phase 04 pulse 1

Time: 2026-05-30T00:00:00Z
Files reviewed: JsonRpcRoute.scala (265 lines), JsonRpcEnvelope.scala (162 lines), JsonRpcHandler.scala (diff), JsonRpcEndpointImpl.scala (diff), CdpBackend.scala (diff), kyo-jsonrpc test files (5), kyo-browser test files (5), scenario test files (4)
Plan cites: ./design/realignment-plan.md §Phase D

## Plan anchor

- Files to produce: 0 new files expected (all changes in existing files) / 0 new files present -- CLEAN
- Files to modify: JsonRpcRoute.scala, JsonRpcEnvelope.scala, JsonRpcHandler.scala, JsonRpcEndpointImpl.scala, CdpBackend.scala + 14 test/scenario files -- all present in dirty tree
- Tests: 9 test files modified (kyo-jsonrpc x5, kyo-browser x4) + 4 scenario files -- PRESENT
- Public API additions: `JsonRpcResponse.Halt` case class, `JsonRpcResponse.halt(response)` convenience, `inline def error[E2](...)` on `JsonRpcRoute` sealed trait + both impls -- all PRESENT

## Reward-hacking checks

| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | FLAG: No test run recorded in phase-04/runs/ -- directory is empty | No sbt output captured yet |
| Compile-only "success" claim | CLEAN: No compile-only claim made; impl is in progress | n/a |
| Priority inference | CLEAN: Changed files match plan scope (kyo-jsonrpc + kyo-browser); no substitution detected | diff --stat |
| Scope substitution | CLEAN: All 19 changed files are in kyo-jsonrpc or kyo-browser; kyo-jsonrpc-http is unmodified (no route callers there yet) | git status |
| Foreach-discards-assert | CLEAN: JsonRpcRouteTest assertions use concrete values: `assert(result == Result.Success(...))`, `assert(err.code == -32603)`, `assert(withError.errorMappings.length == 1)` | JsonRpcRouteTest.scala |
| Stale-state passing | CLEAN: No compile artifact reuse pattern detected | n/a |

## Drifting checks

| Pattern | Verdict | Citation |
|---|---|---|
| Public API signature matches plan | FLAG: Factory handler row is `Abort[JsonRpcError \| JsonRpcResponse.Halt]` (no E in factory arg), but plan spec and resolution 7 specify `Abort[E \| JsonRpcError \| JsonRpcResponse.Halt]`. The factory starts E=Nothing and E accumulates via `.error[E2]` -- this is an intentional design deviation from the plan wording. The internal class stores E via `+E` covariance. Functionally equivalent but diverges from plan's stated handler row. | JsonRpcRoute.scala:118-120 vs plan Resolution 7 |
| No off-plan architecture substitution | CLEAN: `sealed trait JsonRpcRoute[In, Out, +E]` matches plan exactly | JsonRpcRoute.scala:38 |
| No cross-cutting refactor outside phase | CLEAN: No changes outside kyo-jsonrpc + kyo-browser; kyo-jsonrpc-http unchanged | git status |
| Internal helpers stay internal | CLEAN: `handle`, `schemaIn`, `schemaOut`, `errorMappings` all `private[kyo]`; `RequestRoute`/`NotificationRoute` are `final private class` | JsonRpcRoute.scala:51-57 |

## Scope-cutting checks

| Leaf | Status | Notes |
|---|---|---|
| 1: `sealed trait JsonRpcRoute[In, Out, +E]` replaces `JsonRpcRoute[+S]` | PRESENT_STRICT | JsonRpcRoute.scala:38; type params exact match |
| 2: Handler row `(In, Context) => Out < (Async & Abort[E \| JsonRpcError \| JsonRpcResponse.Halt])` | WEAKENED | Factory row drops E from call-site: `Abort[JsonRpcError \| JsonRpcResponse.Halt]` with E=Nothing start; E only accumulates via .error[]. See FLAG above. |
| 3: `JsonRpcResponse.Halt` case class under `JsonRpcResponse` companion | PRESENT_STRICT | JsonRpcEnvelope.scala:99; mirrors HttpResponse.Halt at HttpResponse.scala:169 |
| 4: `JsonRpcResponse.halt(response)` convenience | PRESENT_STRICT | JsonRpcEnvelope.scala:105 |
| 5: `inline def error[E2](using Schema[E2], ConcreteTag[E2])(code, message): JsonRpcRoute[In, Out, E \| E2]` | PRESENT_STRICT | JsonRpcRoute.scala:49; both RequestRoute:165 and NotificationRoute:209 implement it |
| 6: Engine boundary `Seq[JsonRpcRoute[?, ?, ?]]` | PRESENT_STRICT | JsonRpcEndpointImpl.scala diff; JsonRpcHandler.scala diff -- both updated |
| 7: CdpBackend caller migration (5 notification routes) | PRESENT_STRICT | CdpBackend.scala diff: 5 routes migrated from `JsonRpcRoute[Async & Abort[JsonRpcError]]` to `JsonRpcRoute[?, ?, Nothing]` |
| 8: Test suite migration (route construction sites) | PRESENT_STRICT | 9 test + 4 scenario files modified; assertions use concrete values |

## CRITICAL (steer immediately)

None.

## MINOR (queue for post-commit audit)

1. Factory handler row uses `E=Nothing` start with `Abort[JsonRpcError | JsonRpcResponse.Halt]` at call site, rather than `Abort[E | JsonRpcError | JsonRpcResponse.Halt]`. This is functionally sound (E accumulates via `.error[E2]` chaining; no E is ever lost), but deviates from plan Resolution 7's stated signature. The kyo-http HttpRoute.apply equivalent also starts with a constrained row. Verify kyo-http's exact pattern at HttpRoute.scala:95 during post-commit audit and document the deliberate deviation if it matches.

2. `kyo-jsonrpc-http` has no route caller sites in its main source -- confirmed by grep (no `JsonRpcRoute[` in `kyo-jsonrpc-http/src/main/`). If kyo-jsonrpc-http does have routes in examples or integration tests, those sites were not touched. Post-commit audit should confirm.

3. Em-dashes detected in `kyo-browser/shared/src/main/scala/kyo/internal/NavigationWatcher.scala` and `kyo-browser/shared/src/main/scala/kyo/internal/SharedChrome.scala` -- but `git diff` shows these files are NOT changed in this phase. The em-dashes are pre-existing in the repo, not introduced by phase-04. No action needed for this phase; flagged for a future cleanup sweep.

4. No test run has been captured in `phase-04/runs/`. The impl agent has not yet run `sbt 'kyo-jsonrpc/test'`. Verification is the next expected step before committing.

## Recommendation: CONTINUE

Impl is on-plan. All 8 plan leaves are present; the factory-row deviation is functionally equivalent and likely mirrors kyo-http's own pattern. No critical drift. Em-dashes are pre-existing, not phase-04 introductions. Next required step: run `sbt 'kyo-jsonrpc/test'` and capture results before committing.
