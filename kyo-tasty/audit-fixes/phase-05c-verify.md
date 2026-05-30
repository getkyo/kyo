# Phase 05c verify report

Run-id: phase-05c-verify-1
HEAD baseline: d72193baa (Phase 05b)
Phase: 05c — Accept Mapped ByteView in ConstantPool Utf8Lazy (addresses C3)

Status: PASS

## Class-A gates (mechanical, commit-blocking)

- log-gated pass: green
  - JVM testOnly: `kyo-tasty/audit-fixes/runs/phase-05c-flow-verify-testOnly-jvm-1.log` — `Tests: succeeded 2, failed 0`, `[success] Total time: 1 s`.
  - JS testOnly: `kyo-tasty/audit-fixes/runs/phase-05c-flow-verify-testOnly-js-1.log` — `Tests: succeeded 2, failed 0`, `[success] Total time: 8 s`.
  - Native testOnly: `kyo-tasty/audit-fixes/runs/phase-05c-flow-verify-testOnly-native-1.log` — `Tests: succeeded 2, failed 0`, `[success] Total time: 19 s`.

- reward-hacking grep: 0 hits, 0 overridden.

- fp-discipline grep: 18 raw hits, 0 overridden. Per-diff NEW hits in 05c-authored content: 1 (`bare-var` at ConstantPool.scala:224 — local loop counter `var i = 0` in the new Mapped arm; matches the identical pre-existing `var i = 0` at line 211 inside the same outer loop, idiomatic counter for a fixed-length copy, NOT a state variable; no semantic reuse concern). The remaining 17 hits are all PRE-EXISTING in ConstantPool.scala (lines 19, 23, 71, 86, 92, 201, 203, 204, 211, 305, 306, 308, 309 — AtomicReference import, null sentinels, AllowUnsafe.embrace.danger blocks for Sync interop, Right/Left constructors, var idx/errorMsg) and are unchanged by this phase. Verdict: PASS for 05c scope.

- llm-tells grep: 7 raw hits, 0 overridden. All 7 are em-dashes in `kyo-tasty/audit-fixes/phase-05b-audit.md` (PRE-EXISTING untracked prior-phase audit artifact; not authored in 05c). Authorized 05c files (ConstantPool.scala, ConstantPoolTest.scala, phase-05c-decisions.md) grep clean for em-dash/en-dash. Verdict: PASS for 05c scope.

- dev-tag grep: 0 hits, 0 overridden.

- plan-diff (with baseline):
  - AUTHORIZED: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/classfile/ConstantPool.scala` (plan.files_modified.path)
  - AUTHORIZED: `kyo-tasty/shared/src/test/scala/kyo/ConstantPoolTest.scala` (plan.tests.files)
  - DRIFT-FROM-IMPL: NONE
  - PRE-EXISTING (untracked artifacts, supervisor judgment): `kyo-tasty/audit-fixes/phase-05b-audit.md`, `kyo-tasty/audit-fixes/phase-05c-baseline.txt`, `kyo-tasty/audit-fixes/phase-05c-decisions.md`. None are source/test code; all are phase-process artifacts.
  - MISSING=0 DRIFT-FROM-IMPL=0 AUTHORIZED=2 PRE-EXISTING=3
  - Note: `flow-verify-plan-diff.sh` reports a false DRIFT-FROM-IMPL for ConstantPool.scala because its yq expression cannot extract `path:` from object-form `files_modified` entries (same known script limitation documented in phase-05b-verify.md). Manual classification per plan yaml is authoritative.

- test-count: expected=2 actual=2 (parity).
  - Plan declares 2 leaves (`Utf8Lazy.decode reads from Mapped view`, `Utf8Lazy.decode preserves cursor`); ConstantPoolTest.scala contains exactly 2 `" in run` blocks matching those leaf names. Inner test fixture `HeapMappedStub` is a helper class, not a test leaf.

- stowaway-commit: NONE.
  - HEAD is still d72193baa (Phase 05b commit). Impl agent did not create any new commit; all 05c changes are in the dirty tree as expected.

- cross-platform: JVM: 2/2 | JS: 2/2 | Native: 2/2 (all green).

## Class-B findings (opus judgment)

None.

- Held-out acceptance check 1 (derived from design): Utf8Lazy decoded from a Mapped ByteView must produce the same string the source bytes encode, independent of the mmap arena lifetime. Verified by Test 1 (`utf8(1) == "foo"`) and by inspection of the impl: the new arm allocates a fresh `Array[Byte](len)` on the JVM heap and copies via `peekByte`, so the heap copy is decoupled from the mapped region. PASS.
- Held-out acceptance check 2 (derived from design): the read cursor must still advance past the consumed UTF-8 payload regardless of which ByteView variant matched. Verified by Test 2 (cursor at `bytes.length` after read). Inspection: the cursor-advance loop at ConstantPool.scala lines 211-214 (`readByte()` per byte) runs unconditionally BEFORE the view-variant dispatch, so cursor invariant is preserved. PASS.

Other class-B catalog rows checked and clear:
- Specific-to-catchall: tests pattern-match `Result.Failure`/`Result.Panic`/`Result.Success` distinctly; no catch-all clauses.
- Hash/value collision: expected `"foo"` / `"bar"` are literals; computed via `pool.utf8(1)` which traverses Utf8Lazy.decode through the Interner. No same-function double-source.
- Coverage claim mismatch: test names directly assert the C3 invariant exercised by the impl.
- Bespoke-where-canonical-primitive-exists: the peekByte loop is a 7-line raw copy; `Array.copy`/`System.arraycopy` was an alternative but the per-byte form mirrors the existing `view.readByte()` loop already on lines 211-214 (idiomatic for the file). Not a class-B finding.
- Stringly-typed dispatch: dispatch is on the sealed `ByteView` hierarchy (`Heap` / `Mapped`).
- Frame propagation gap: ConstantPool.read already takes a `path: String` and propagates Sync; no new public API surface.
- Refactor invariant drift: no config/default surface in this phase.
- Re-framing failure as success: N/A; logs show literal `[success]`.
- Extension-on-owned-type: no new extension methods.
- Test-infra drift: ConstantPoolTest extends the existing `kyo.Test` base used by every prior phase test; no new test-base imports.
- Stub returns the expected value: `HeapMappedStub` is a test-only ByteView.Mapped impl that fully implements the abstract API from real data; it does NOT short-circuit `peekByte` to a hardcoded literal. The data buffer is constructed from the classfile format and passes through ConstantPool.read's full parsing path.
- Test controls its own signal: assertion is on `pool.utf8(1)` (system under test materialized via Interner), not on a value the test mutated.
- Test bypasses the API under test: tests invoke `ConstantPool.read(view, interner, "<test>")` directly with a Mapped view (the exact code path the design targets).
- Fabricated or stale facts: decisions doc cites only verified file lines.

## Overrides

None. (no `// flow-allow:` annotations introduced in 05c diff)

## Exit code: 0
