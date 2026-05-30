# Phase 07 pulse 1

Time: 2026-05-30T21:05Z
Files reviewed: 14 modified + 7 new untracked (21 total); compile + test logs in runs/
Plan cites: ./design/realignment-plan.md §Phase G

## Plan anchor
- Files to produce (new top-level): 7 expected / 7 present
  - JsonRpcCancellationPolicy.scala (PRESENT)
  - JsonRpcProgressPolicy.scala (PRESENT)
  - JsonRpcUnknownMethodPolicy.scala (PRESENT)
  - JsonRpcIdStrategy.scala (PRESENT)
  - JsonRpcExtrasEncoder.scala (PRESENT)
  - JsonRpcFramer.scala (PRESENT)
  - JsonRpcWireTransport.scala (PRESENT)
- Files modified: JsonRpcHandler.scala, JsonRpcTransport.scala, JsonRpcRoute.scala, JsonRpcCodec.scala, internal engine + transport files (14 total)
- Tests: 189 / 189 passed (impl-test-jvm-001.log: [success])
- Public API additions: JsonRpcCodec.default, JsonRpcRoute.request, Pending[+Out], backward-compat type aliases in JsonRpcHandler + JsonRpcTransport companions

## Reward-hacking checks
| Pattern | Verdict | Citation |
|---|---|---|
| Verification commands actually run | CLEAN | impl-compile-jvm-001.log: [success] 8:51:31 PM; impl-test-jvm-001.log: 189 tests passed 8:50:19 PM; impl-compile-http-jvm-001.log: [success] 8:50:43 PM; impl-compile-browser-jvm-001.log: [success] 8:51:15 PM |
| Compile-only "success" claim | CLEAN | Full test suite ran (189 tests, 5s 288ms), not compile-only |
| Priority inference | CLEAN | All 5 plan items addressed per decisions.md D1-D6 |
| Scope substitution | CLEAN | No off-plan types introduced; backward-compat aliases are explicitly plan-permitted (Decision #2) |
| Foreach-discards-assert | CLEAN | No test weakening detected; test count 189 matches prior phase baseline |
| Stale-state passing | CLEAN | Logs bear today's timestamps (May 30, 2026); all three compile targets independently green |

## Drifting checks
| Pattern | Verdict | Citation |
|---|---|---|
| Public API signature matches plan | CLEAN | Pending[+Out] confirmed (JsonRpcHandler.scala:168); JsonRpcCodec.default = Strict2_0 (JsonRpcCodec.scala:34); JsonRpcRoute.request present with apply alias (JsonRpcRoute.scala:123,132) |
| No off-plan architecture substitution | CLEAN | 7 new files + backward-compat aliases exactly match plan Phase G scope |
| No cross-cutting refactor outside phase | CLEAN | git diff covers only kyo-jsonrpc; no kyo-browser or kyo-jsonrpc-http source changes |
| Internal helpers stay internal | CLEAN | No new public API beyond plan items; engine files (CancellationEngine, IdStrategyEngine, ProgressEngine) remain in internal.engine |

## Scope-cutting checks
| Leaf | Status | Notes |
|---|---|---|
| 1: Hoist 7 nested types to top-level | PRESENT_STRICT | 7 new files confirmed; old nested defs removed from JsonRpcHandler + JsonRpcTransport (grep: zero class/trait definitions remaining for those names in owner files) |
| 2: +Out covariance on Pending[Out] | PRESENT_STRICT | JsonRpcHandler.scala:168: `final class Pending[+Out] private[kyo]` |
| 3: JsonRpcCodec.default preset | PRESENT_STRICT | JsonRpcCodec.scala:31-34: `val default: JsonRpcCodec = Strict2_0` with scaladoc |
| 4: JsonRpcMessageGate.noop present | PRESENT_STRICT | JsonRpcMessageGate.scala:48: `val noop: JsonRpcMessageGate` (Phase 06 artifact, confirmed) |
| 5: JsonRpcRoute.apply -> request rename | PRESENT_STRICT | JsonRpcRoute.scala:123 `def request`, 132 `inline def apply` delegates; symmetry with notification achieved |

## CRITICAL (steer immediately)
None.

## MINOR (queue for post-commit audit)

1. Tests still use backward-compat alias forms exclusively (e.g., `JsonRpcHandler.ExtrasEncoder`, `JsonRpcTransport.Framer`) rather than the new top-level names. The aliases resolve correctly (189/189 pass), so this is not a functional issue. Post-commit audit should decide: migrate test call-sites to top-level names to demonstrate the hoist is user-visible, or document that aliases are the intentional stable API surface.

2. `JsonRpcId.scala:18` scaladoc still references `JsonRpcHandler.ExtrasEncoder` (the alias path). Minor doc hygiene; no functional impact.

3. `git diff --stat` shows net -258 LoC (126 insertions, 384 deletions). Phase G estimated ~350 LoC. The net reduction is expected (dead nested bodies removed, new files are smaller extracts), but actual change volume is within plan estimate.

## Recommendation: CONTINUE

All 5 work items complete. Compile green on kyo-jsonrpc + kyo-jsonrpc-http + kyo-browser. 189/189 tests pass. No em-dashes detected in any changed file. 7 new top-level files present. No CRITICAL findings. Phase 07 is converging normally toward commit.
