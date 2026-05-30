# Phase 06 verify report

Status: FAIL (class-A: cross-platform link failure on JS)

Run-id: phase-06-verify-1
HEAD: a57dde403 (Phase 05c)
Baseline: kyo-tasty/audit-fixes/phase-06-baseline.txt (empty, only itself)

## Class-A gates (mechanical, commit-blocking)

- log-gated pass: green for JVM/Native, RED for JS
  - JVM testOnly: `kyo-tasty/audit-fixes/runs/phase-06-flow-verify-testOnly-jvm-1.log` — `Tests: succeeded 6, failed 0, canceled 0, ignored 0`, `[success] Total time: 3 s`.
  - JS testOnly: `kyo-tasty/audit-fixes/runs/phase-06-flow-verify-testOnly-js-1.log` — `[error] There were linking errors`, `(kyo-tastyJS / Test / fastLinkJS) There were linking errors`. Linker rejects `java.lang.Thread.<init>(Runnable)`, `Thread.start`, `Thread.join` references from Tests 5 and 6 of `OnceCellTest.scala`.
  - Native testOnly: `kyo-tasty/audit-fixes/runs/phase-06-flow-verify-testOnly-native-1.log` — `Tests: succeeded 4, failed 0, canceled 0, ignored 2`, `[success] Total time: 15 s`. The two `jvmOnly`-tagged tests skipped cleanly; Native does link `java.lang.Thread`.
- reward-hacking grep: 0 hits, 0 overridden.
- fp-discipline grep: 12 raw hits, 0 overridden. Classified:
  - PRE-EXISTING in `OnceCell.scala`: `juc-atomic-import` line 3 (`AtomicReference` was already there at HEAD), `juc-tree` line 3 (same import), `asInstanceOf` lines 39, 42, 53 (all carry canonical `// Unsafe:` prefixes per A3, design §370), `private-over-annotation` line 60 (`private val Unset`), `null-literal` line 28 (comment text "strict-null").
  - NEW in `OnceCellTest.scala`: `juc-atomic-import` line 3 (`AtomicInteger`), `juc-tree` lines 3, 72, 103, 130 (`AtomicInteger`, three `CopyOnWriteArrayList`). Test-file JUC use is the established convention in this module (`InternerTest.scala`, `QueryApiTest.scala` cited as precedent). No new source-side juc.
  - Verdict: PASS for Phase 06 scope; all NEW hits are conventional test-infra and all source hits are PRE-EXISTING canonical-Unsafe annotated.
- llm-tells grep: 6 raw hits, 0 overridden. Classified:
  - `hedge-harness` in `kyo-tasty/audit-fixes/phase-06-decisions.md:23` — the literal token is the word "harness" in `from the existing `Test.scala` harness`, conventional sbt/scalatest term in a decision doc; not LLM hedging.
  - `em-dash` x5 in `kyo-tasty/audit-fixes/phase-05c-audit.md` — PRE-EXISTING untracked prior-phase audit artifact (was uncommitted in 05c verify, still uncommitted now); not authored in 06.
  - Authorized 06 files (OnceCell.scala, OnceCellTest.scala, phase-06-decisions.md, phase-06-baseline.txt) grep clean for em-dash/en-dash. Verdict: PASS for 06 scope.
- dev-tag grep: 0 hits, 0 overridden.
- plan-diff (with baseline): script-output is misleading due to a known yq limitation (`flow-verify-plan-diff.sh` cannot extract `path:` from object-form `files_modified` entries, same artifact noted in phase-05c-verify.md). Manual classification:
  - AUTHORIZED: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/OnceCell.scala` (plan.files_modified.path), `kyo-tasty/shared/src/test/scala/kyo/OnceCellTest.scala` (plan.tests.files).
  - DRIFT-FROM-IMPL: NONE.
  - PRE-EXISTING (untracked artifacts, supervisor judgment): `kyo-tasty/audit-fixes/phase-05c-audit.md`, `kyo-tasty/audit-fixes/phase-06-baseline.txt`, `kyo-tasty/audit-fixes/phase-06-decisions.md`. All phase-process artifacts.
  - MISSING=0 DRIFT-FROM-IMPL=0 AUTHORIZED=2 PRE-EXISTING=3.
- test-count: expected=3 actual=5 (new leaves), 6 total in file. Plan declared 3 leaves; impl produced 5 new (Tests 2-6). Tests 2-4 cover the plan's 3 declared leaves (init result, cached, debug-mode flag); Tests 5-6 add JVM-only concurrent-race coverage. This is scope **expansion** above plan, not below. Not commit-blocking, but flagged.
- stowaway-commit: NONE. No phase-06 impl stdout log on disk; HEAD is still Phase 05c; no commit by impl agent.
- cross-platform (plan declares `platforms: [jvm, js, native]`):
  JVM: 6/6 passed | JS: LINK FAILED (0/6 runnable) | Native: 4/6 passed + 2/6 ignored (jvmOnly skip).
  A failure on ANY declared platform is FAIL. Phase 06 is FAIL.

## INV-009 verdict

INV-009 (`OnceCell.init` lambdas idempotent; debug-mode `IllegalStateException` on CAS-losing diverging value) is correctly produced in source:
- `OnceCell.scala:42-50` — CAS-loser branch computes equality via `winner.equals(v)` (not `!=`, per decisions doc rationale on E172 strict-equality); throws `IllegalStateException` with `idempotence violated` message when `OnceCell.debugIdempotent` is true.
- `OnceCell.scala:60-64` — `private[kyo] val debugIdempotent` reads `kyo.tasty.OnceCell.debug` system property at object-init, case-insensitive comparison to `"true"`. Production overhead: zero (JIT constant-folds the val).
- Scaladoc added at `OnceCell.scala:13-15` documenting the IDEMPOTENT INIT requirement and the debug-mode escape hatch, matching design §560-563.
- Test 4 confirms the flag reflects the system property default.
- Test 6 confirms the IllegalStateException semantics (when debug on) and the silent-discard semantics (when debug off).

**INV-009 source: OK. INV-009 cross-platform: BLOCKED by JS link failure.**

## Class-B findings (opus judgment)

1. **Cross-platform test structure (BLOCKER).** `OnceCellTest.scala:65-95, 99-145` instantiate `java.lang.Thread` in two `jvmOnly`-tagged test bodies. Scala.js linker resolves all referenced symbols at link time, regardless of `taggedAs jvmOnly` runtime skip. The decisions doc (`phase-06-decisions.md:23`) claims "JS/Native still compile" but JS does NOT link. Native happens to provide `Thread` stubs so it links and skips. The fix is to gate the Thread-using bodies behind a JVM-only file (`kyo-tasty/jvm/src/test/scala/kyo/OnceCellJvmTest.scala`) or wrap the bodies in a platform-conditional helper so JS never sees the Thread references at link time. Cite: feedback_all_platforms_all_tests warns against demoting tests to a platform folder, but the alternative here is rewriting Tests 5-6 to avoid raw Thread (e.g. drive concurrency from `kyo.Async.foreach` so the linker only sees Kyo primitives). Supervisor decides.
2. **Test-count expansion above plan (advisory).** Plan declared 3 leaves; impl wrote 5 new leaves (Tests 2-6). The expansion adds JVM concurrency coverage that was not in the plan budget. Not commit-blocking, but should be reflected in the plan slice or commit message.

## Overrides

(none)

## Exit code: 1
