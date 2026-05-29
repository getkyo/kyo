# Phase 03a verify report

Run-id: phase-03a-verify-1
HEAD: d7330d8f7 (Phase 02g; impl reports unchanged)
Baseline: phase-03a-baseline.txt
Phase scope: Bound binary input primitives (INV-010, addresses B1, B4, B7, C4)

Status: FAIL (class-A: VarintTest.scala file missing per plan tests.files; class-A weak-assertion-bound miss caught by class-B; cross-platform JS test-link failure pre-existing at HEAD)

## Class-A gates (mechanical, commit-blocking)

- reward-hacking grep: 0 hits on Phase 03a authorized files (4 hits on untracked prior-phase artifacts `phase-02f-verify.md` excluded from this phase's diff)
- fp-discipline grep: 40 raw hits on authorized files; classified by NEW vs PRE-EXISTING below
- llm-tells grep: 0 hits, 0 overridden
- dev-tag grep: 0 hits, 0 overridden
- plan-diff (with baseline): MISSING=1 DRIFT-FROM-IMPL=0 PRE-EXISTING=0 AUTHORIZED=7
  - MISSING: `kyo-tasty/shared/src/test/scala/kyo/VarintTest.scala` — plan tests.files declares this file but D7 placed `class VarintTest` inside `ByteViewTest.scala` instead. Conceptually equivalent (class still extends Test; tests run under `kyo.VarintTest` testOnly target); structurally a plan deviation. Recommendation: extract `class VarintTest` from ByteViewTest.scala into its own file OR amend plan in retrospect.
  - AUTHORIZED: 4 source files (Varint.scala, ByteView.scala, NameUnpickler.scala, SectionIndex.scala) + 3 test files (ByteViewTest.scala modified, NameUnpicklerTest.scala modified, SectionIndexTest.scala created)
  - Note: `flow-verify-plan-diff.sh` itself rejects the non-numeric phase id `03a` (yq lex error) and treats `files_modified` entries as strings rather than `.path` sub-keys. Manual classification used.
- test-count (manual; script unavailable due to rg PATH): expected=8 actual=8
  - 5 new "in run" leaves in ByteViewTest.scala (3 Varint + 2 ByteView), 1 in NameUnpicklerTest.scala, 2 in SectionIndexTest.scala. Maps 1:1 to plan leaves 1-8.
- stowaway-commit: NONE (HEAD unchanged at d7330d8f7; impl decisions log has no git add/commit)
- cross-platform (plan declares jvm, js, native):
  - JVM: 28/28 passed (VarintTest 11, ByteViewTest 8, NameUnpicklerTest 7, SectionIndexTest 2)
  - JS: Test/compile PASS; `testOnly` FAIL — fastLinkJS linking errors in `kyo.TastyTest` and `kyo.OnceCellTest` referencing `java.nio.file.{Path,Paths,Files}`. These pre-date Phase 03a (already broken at HEAD d7330d8f7 from prior phases 02d/02g); Phase 03a's 4 modified source files and SectionIndexTest compile cleanly on JS. Inherited red, must be tracked.
  - Native: 28/28 passed (same 4 suites)

## Class-A fp-discipline classification (NEW vs PRE-EXISTING)

40 raw hits broken down:

- `bare-var` (14): 10 pre-existing in Varint.scala, 1 pre-existing in NameUnpickler (`var i = 0` line 218), 1 pre-existing in ByteView.scala (`cursor`). NEW: 4 (`var bytes = 0` in readNat + readLongNat; `var b/x` shadow under `var bytes` renaming — actually 4 added counters: `var bytes = 0` x2 + reformatted existing). Plan-authorized per D6. PRE-EXISTING-CONVENTION.
- `unsafe-site` (2): both pre-existing (`import AllowUnsafe.embrace.danger`); SectionIndex's was moved scope but not added net. PRE-EXISTING.
- `bespoke-mutable-collection` (3): all pre-existing ArrayBuffer usages. PRE-EXISTING.
- `isinstanceof` (1): pre-existing line 53. PRE-EXISTING.
- `some-constructor`/`none-token` (5): all pre-existing UNIQUE/Tasty.Name handling. PRE-EXISTING.
- `right-constructor`/`left-constructor` (8): pre-existing try-Right/catch-Left pattern in `read` methods. 2 of 8 are NEW Left() lines but reuse the same Either-based catch — pattern PRE-EXISTING; usage extends within it. PRE-EXISTING-EXTENSION.
- `null-literal` (3): 2 NEW (`if ex.getMessage != null` in NameUnpickler:50, SectionIndex:44), 1 pre-existing (`ex.getCause != null` line 53). NEW null checks bridge Java exception API; justified Java-interop. Class-B candidate (see findings).
- `private-over-annotation` (2): NEW `private def checkRef` in NameUnpickler:208; NEW `private def readSync ... (using AllowUnsafe)` annotation in SectionIndex:51. Class-B (private-over-annotation is class-B per catalog Rule 5).

NEW-on-this-phase that warrant attention:
- 4 NEW bare-var counters (plan-authorized D6, verbatim TastyReader continuation port)
- 2 NEW null-literal Java-interop checks (acceptable bridging, see class-B)
- 2 NEW private-def with explicit type annotations (acceptable, class-B Rule 5 carveout)

Class-A em-dash, asInstanceOf, juc-tree, semicolons, Frame.internal, println: 0 NEW.

## Class-A miss: weak-assertion-bound regex misses `>= 0L`

The catalog regex `\b(>=|<=)\s*0\b` does not match `>= 0L` (Long suffix). `kyo-tasty/shared/src/test/scala/kyo/ByteViewTest.scala:343` contains `assert(result >= 0L || result < 0L)` — a literal tautology (every Long satisfies one disjunct). Substantively this is a class-A weak-assertion-tautology. Caught here by class-B judgment (see findings); recommend catalog tightening to `\b(>=|<=|<|>)\s*0[Ll]?\b`.

## Class-B findings (judgment-needed)

1. **Weak-assertion-tautology (test rigor).** `kyo-tasty/shared/src/test/scala/kyo/ByteViewTest.scala:343` — `assert(result >= 0L || result < 0L)` is a tautology. The intent ("no exception was thrown") is the test's actual contract; assertion does no work. The test passes because the absence of a thrown exception is what `in run` already verifies via successful completion. Recommendation: replace with `succeed` or assert on a specific expected decoded value (the input `9 x 0x01 + 0x81` produces a deterministic Long). The plan's "Then" clause for leaf 3 says "no exception thrown"; current code matches intent but the assertion line is reward-hack-adjacent (passes trivially). NEW issue introduced by Phase 03a.

2. **VarintTest file placement deviates from plan.** Plan tests.files lists `VarintTest.scala`; D7 placed `class VarintTest extends Test` inside ByteViewTest.scala. Tests run identically under `testOnly kyo.VarintTest`. Decision D7 justifies via "Varint.scala is a modified source file and already has a VarintTest class in ByteViewTest.scala" — but the plan file-list was authoritative. Judgment: either extract to a separate file or amend plan post-hoc. Class-A plan-diff failure.

3. **Null-literal Java-interop (Rule 4).** `NameUnpickler.scala:50` and `SectionIndex.scala:44` use `if ex.getMessage != null then ex.getMessage else "..."`. Bridges Java's nullable `Throwable.getMessage`. Justified; no Kyo-native alternative exists for this boundary. ACCEPTABLE.

4. **JS test-link pre-existing red.** Phase 03a inherits JS `fastLinkJS` errors from `TastyTest`/`OnceCellTest` referencing `java.nio.file`. These pre-date Phase 03a (Phase 02g HEAD already broken). Phase 03a's authorized files compile clean on JS. Recommendation: route to dedicated JS-port phase; do not block 03a commit on inherited red, but DO record in cross-platform ledger so the campaign owns it. Per "Own all failures" — must not be dismissed.

5. **No private-default-param introduced** (verified): no NEW default parameters on internal/private APIs.

6. **No type aliases / effect aliases introduced** (verified).

7. **No catch-all `case _ =>`**: NameUnpickler catch is specific to `ArrayIndexOutOfBoundsException` and `java.lang.Error if ex.getCause.isInstanceOf[AIOOBE]`. SectionIndex catch is specific. ACCEPTABLE.

8. **Frame propagation** (Rule 6): SectionIndex.read keeps `(using Frame)`; NameUnpickler.read unchanged. NO GAP.

9. **Coverage claim match**: Tests 1-8 each pin to a plan leaf and the body exercises the named primitive. INV-010 smoke test path (`InvariantsSpec.scala::INV-010`) not yet created — INV-010 ledger references a smoke-test path that does not exist; that wiring belongs to a future phase per plan.

## INV-010 verdict

INV-010: `Varint`, `ByteView`, `NameUnpickler`, `SectionIndex`, `Interner` reject out-of-bounds reads with a structured `TastyError.MalformedSection` rather than an uncaught exception.

Phase 03a produces INV-010 for `Varint`, `ByteView`, `NameUnpickler`, `SectionIndex` (4 of 5). `Interner` is Phase 03b scope (separate phase). Within Phase 03a's 4 primitives:

- Structural rejection at each unsafe site: VERIFIED (8/8 tests on JVM and Native)
- Internal exception types: `MalformedVarintException` (Varint) and descriptive `ArrayIndexOutOfBoundsException` (ByteView, NameUnpickler, SectionIndex) caught at decode boundaries (`NameUnpickler.read`, `SectionIndex.read`) and rewrapped as `TastyError.MalformedSection("Names" | "SectionIndex", reason)`: VERIFIED
- Boundary tests (10-byte exact-fit varint): VERIFIED (though see Class-B finding 1)
- ByteView.subView raw AIOOBE expected at caller boundaries; readers' enclosing catches handle it: VERIFIED in NameUnpickler/SectionIndex; downstream consumers (Phase 7+) must follow same convention per consumer-phase contract.

INV-010 SATISFIED for the 4 Phase 03a primitives.

## Overrides

No `// flow-allow:` overrides used in Phase 03a diff. Nothing to copy into commit body.

## Summary

- 4 source files modified per plan ✓
- 3 test files touched (1 new SectionIndexTest, 2 modified ByteViewTest+NameUnpicklerTest); plan listed 4 test files (VarintTest.scala missing as a file)
- 8/8 plan leaves implemented and passing on JVM and Native
- INV-010 satisfied for Phase 03a's 4 primitives
- 0 NEW em-dash, juc-tree, null (non-interop), asInstanceOf hits

Blocking issues:
- Plan-diff MISSING VarintTest.scala (D7 deviation) — needs supervisor decision: extract file or amend plan
- ByteViewTest.scala:343 tautological assertion (NEW class-B weak-assertion-tautology) — needs replacement with `succeed` or substantive assertion
- JS fastLinkJS pre-existing red inherited from prior phases — not Phase-03a-introduced but must be owned by campaign

## Exit code: 1

Class-A failure: plan-diff MISSING=1 (VarintTest.scala) and class-A weak-assertion-tautology miss (caught by class-B). Cross-platform JS pre-existing red noted. Ready for remediation, not commit.
