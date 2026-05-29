# Phase 02c audit

Run-id: phase-02c-audit-1
HEAD audited: 1892acb54 ("kyo-tasty Phase 02c: propagate AllowUnsafe through ClasspathRef accessors")
Audit scope: HEAD vs 02-design.md INV-001 third surface; CONTRIBUTING.md §828 / §839; plan entry 02c.
Verdict: PASS. Ready for Phase 02d.

## Architecture substitution
PASS. `ClasspathRef.get()(using AllowUnsafe): Tasty.Classpath` and `ClasspathRef.isAssigned(using AllowUnsafe): Boolean` at HEAD (lines 26-28, 32-34) return raw values: `slot.get()` / `slot.isSet`. No `Sync.Unsafe.defer` wrap, no `Sync.defer`, no `Unsafe.embrace`. Matches §828 option 1 and INV-001 wording verbatim. Zero allocation per call preserved (INV-002 family preserved at this surface even though Phase 02c does not produce INV-002 itself).

## Documentation drift
PASS. Commit message accurately enumerates the 2 migrated accessors, the §839 case 3 `assign` preservation, and the 3 SUPPORTING-CASCADE files. Scaladoc on `get` / `isAssigned` updated to say "Requires proof that the caller holds AllowUnsafe." Decisions log records Decisions 2-4 as prep-doc deviations; commit body matches Decisions 2-4 word-for-word.

## API surface integrity
PASS. `ClasspathRef` remains in `kyo.internal.tasty.query`. Public-facing `Tasty.Symbol.home` is declared `private[kyo] val home: ClasspathRef` (line 521); the only outside-package observation of `ClasspathRef` is the test files explicitly importing `kyo.internal.tasty.query.ClasspathRef`. No leak to `kyo.*` public surface.

## `assign` preservation
PASS. Lines 19-23 retain `import AllowUnsafe.embrace.danger` inside `assign`. Justified by §839 case 3 (Phase 7 orchestration initialization write). Plan entry 02c §357-359 explicitly excludes `assign` from this phase. Comment on line 20 matches the §839 justification.

## Cascade containment
PASS. Tasty.scala diff is purely a position change: 3 lines added (2 comment + 1 import) at the `case o: Symbol.TastyOrigin =>` branch top, 2 lines removed (1 comment + 1 import) from the inner `else` block, 1 mechanical `end if` closer added. No behavioral edits. The relocated import covers both `home.isAssigned` (line 701) and `home.get()` (lines 703, 714) which now require the implicit. ClasspathTestHelpers.scala adds a single import inside `assignExtraHomes`; ClasspathRefDedupTest.scala adds 2 file-top imports.

## Test integrity
PASS. ClasspathRefTest.scala adds 2 runtime-invocation scenarios (`get returns assigned`, `isAssigned false-then-true`) — inherits the runtime form from Phase 02b NOTE-1. Both scenarios actually call `ref.get()` / `ref.isAssigned` under `import danger`; a missing `(using AllowUnsafe)` on either accessor would yield a compile error at the call site. Pin is structural + runtime. Plan leaf 3 (INV-002 alloc test) is correctly deferred — task brief expected 2 new scenarios + 2 unchanged ClasspathRefDedupTest scenarios, which matches what shipped.

## INV-001 completion (three surfaces)
PASS. Verified by `grep -rn 'import AllowUnsafe.embrace.danger' kyo-tasty/shared/src/main/scala/kyo/{Tasty.scala,internal/tasty/symbol/,internal/tasty/query/}`:
- Symbol surface (Phase 02a): no inner `import danger` in routine accessor bodies; all migrations live in `Tasty.scala` outside the routine accessor scope (Symbol body accessor is an `andThen`/`Abort.fail` block, not a routine accessor — it is a Sync-returning accessor, §839 case 3).
- Classpath surface (Phase 02b): clean per Phase 02b audit.
- ClasspathRef surface (Phase 02c): `get` / `isAssigned` migrated; `assign` preserved per §839 case 3.
Residual `import danger` occurrences in `kyo.internal.tasty.query` are all §839-justified: `ClasspathRef.assign` (case 3), `ClasspathOrchestrator` x7 (Phase 7 orchestration, case 3), `ClasspathTestHelpers` x2 (test fixture initialization).

## NOTE / WARN for Phase 02d prep
- NOTE-1: Phase 02c verify report records INV-002 (zero per-call `Sync.Unsafe.defer` alloc) as NOT YET PRODUCED. Plan entry 02c lists INV-002 under `produced_invariants`, but the alloc-counting test (plan leaf 3) was deferred. Phase 02d prep should explicitly state whether INV-002 production lands in 02d, 02e, or a later phase, and ship the alloc-counting test there.
- NOTE-2: Plan-diff yq query bug (verify report) silently flagged AUTHORIZED files as DRIFT because `files_modified` uses `path:` object form. Supervisor classification overrode; Phase 02d should keep manually classifying until the script is fixed.
- NOTE-3: ClasspathTestHelpers.scala still holds 2 inner `import danger` sites (lines 18, 31). Both are test fixture init writes (`assignHomesForTest`, `assignExtraHomes`) calling `assign` / `isAssigned`. They are §839 case 3 boundaries and are CORRECTLY NOT migrated; Phase 02d should not touch them.

## Overall
INV-001 third surface PRODUCED. Zero NEW class-A drift; cascade minimal and justified by Decisions 2-4. Tests pin the migrated signatures at runtime; compile-time call sites guarantee the implicit is required. Ready for Phase 02d.
