# Phase 5 Audit: PositionsUnpickler IntMap (commit eaf7970f7)

Audit of commit `eaf7970f7 kyo-reflect Phase 5: addrMap to IntMap for boxing elimination`
against `execution-plan-perf.md` Phase 5, `PHASE-5-PREP.md`, `PERF-VERIFICATION.md` §6,
and CONTRIBUTING.md. Read of HEAD only (committed content).

(This file supersedes any earlier PHASE-5-AUDIT.md content from a previous unrelated
audit pass.)

---

## Test count

Plan: 1 new test (T-P5-1: 10,000-entry IntMap addrMap, all returns correct positions, spot checks).

- **T-P5-1 PRESENT_STRICT.** `kyo-reflect/shared/src/test/scala/kyo/PositionsUnpicklerTest.scala`,
  test block "T-P5-1: PositionsUnpickler.readSync with IntMap addrMap of 10,000 entries
  returns correct positions". Builds `IntMap.from((0 until 10000).map(...))` with N=10000
  symbols, constructs the Positions section payload, asserts `result.size == N`, then
  iterates spot-check indices `Seq(0, 999, 4999, 7777, 9999)` and asserts (line, column,
  sourceFile) per entry. Matches the planned scenario exactly: same N, same spot indices,
  same `IntMap` construction shape.

---

## Type-change verification

All PRESENT_STRICT (verified at file:line within `git show eaf7970f7:<path>`):

| Site | Expected | Found | Result |
|------|----------|-------|--------|
| `AstUnpickler.scala:65` `Pass1Result.addrMap` | `IntMap[Reflect.Symbol]` | `addrMap: IntMap[Reflect.Symbol]` | PRESENT_STRICT |
| `Reflect.scala:777` `TastyOrigin._addrMap` | `SingleAssign[IntMap[Reflect.Symbol]]` | `SingleAssign[IntMap[Reflect.Symbol]]` | PRESENT_STRICT |
| `Reflect.scala:780` `TastyOrigin.addrMap` accessor return | `IntMap[Reflect.Symbol]` (narrowed) | `def addrMap(using AllowUnsafe): IntMap[Reflect.Symbol]` returning `_addrMap.get()` or `IntMap.empty` | PRESENT_STRICT |
| `PositionsUnpickler.scala:45,60` `read`/`readSync` `addrMap` param | `IntMap[Reflect.Symbol]` | `addrMap: IntMap[Reflect.Symbol]` on both | PRESENT_STRICT |
| `CommentsUnpickler.scala:36,48` `read`/`readSync` `addrMap` param | `IntMap[Reflect.Symbol]` | `addrMap: IntMap[Reflect.Symbol]` on both | PRESENT_STRICT |
| `TypeUnpickler.scala:98` `TreeTypeSession.addrMap` field | `IntMap[Reflect.Symbol]` | `val addrMap: IntMap[Reflect.Symbol]` | PRESENT_STRICT |
| `TypeUnpickler.scala:209` `DecodeCtx.addrMap` field | `IntMap[Reflect.Symbol]` | `val addrMap: IntMap[Reflect.Symbol]` | PRESENT_STRICT |
| `TypeUnpickler.scala:63` `readType` `addrMap` param | `IntMap[Reflect.Symbol]` | `addrMap: IntMap[Reflect.Symbol]` | PRESENT_STRICT |
| `TreeUnpickler.scala:113` `DecodeCtx.addrMap` field | `IntMap[Reflect.Symbol]` | `val addrMap: IntMap[Reflect.Symbol]` | PRESENT_STRICT |
| `TypeUnpickler.scala:173` `readTypeIntoSession` snapshot | `IntMap.from(session.liveAddrMap.iterator)` (replaced `.toMap`, NOT removed) | exact source: `IntMap.from(session.liveAddrMap.iterator)`; snapshot still per-call inside `readTypeIntoSession` | PRESENT_STRICT |

---

## Pattern verification

- **Single conversion in `runPass1`.** `AstUnpickler.scala:153`:
  ```
  val intMap = IntMap.from(addrMap.iterator)
  ```
  This is the only `IntMap.from(addrMap.iterator)` site in `runPass1`. It is positioned
  after `walkStats(...)` returns and before the TastyOrigin stash loop and before
  `Pass1Result(...)`. Verified single occurrence by source read; `runPass1` body shows
  one and only one conversion. PRESENT_STRICT.

- **Conversion happens once per file, not per lookup.** `intMap` is built once after
  `walkStats` completes, then (a) `set(intMap)` on every `TastyOrigin._addrMap` slot and
  (b) stored into `Pass1Result.addrMap`. All downstream consumers (`PositionsUnpickler`,
  `CommentsUnpickler`, `TreeUnpickler.decodeSync` via `origin.addrMap`, type decode via
  `TreeTypeSession.addrMap`) operate on the already-built `IntMap`. No per-lookup
  conversion. PRESENT_STRICT.

- **Per-call `readTypeIntoSession` snapshot.** Phase 5 preserves the per-call snapshot
  pattern (`IntMap.from(session.liveAddrMap.iterator)`) intentionally; this is the
  Pass-1-type-decode "live view" semantics flagged in the prep doc. Not a regression.

---

## CONTRIBUTING.md violations

- **Core principles (effects-as-data, single-allocation discipline):** Conversion
  happens at a controlled boundary (after walkStats) and produces an immutable `IntMap`
  shared across all consumers. No violation.
- **API design (`kyo` package = public API, `kyo.internal` = impl):** All affected types
  (`AstUnpickler`, `PositionsUnpickler`, `CommentsUnpickler`, `TypeUnpickler`,
  `TreeUnpickler`) are in `kyo.internal.reflect.tasty`. `TastyOrigin` is a nested member
  under `kyo.Reflect.Symbol`, `_addrMap` is `private[kyo]`; the public accessor
  `addrMap` already required `AllowUnsafe`. No public-API delta from Phase 5.
- **Code conventions:** No semicolons introduced. No em-dashes in source. No casts.
  No emoji. Imports use `scala.collection.immutable.IntMap` consistently across all six
  modified source files (matches prep doc style). No effect aliases.
- **Testing patterns (`feedback_tests_use_public_api`):** Test T-P5-1 constructs values
  via `Reflect.Symbol.make(...)`, calls `PositionsUnpickler.read(view, addrMap, sourceFile)`.
  `IntMap` on the LHS for the test fixture addrMap is justified because the Phase 5
  type change made `IntMap` the parameter type the test is exercising. Asserts on
  `pos.line`, `pos.column`, `pos.sourceFile`. No internals on LHS.
- **Unsafe Boundary:** Pre-existing `AllowUnsafe` requirements on `TastyOrigin.addrMap`
  accessor and on `_addrMap.set(...)` in `AstUnpickler.runPass1` are preserved
  unchanged. No new `AllowUnsafe` import sites added.

No violations.

---

## Unsafe markers

Grep over the Phase 5 diff:

- New `asInstanceOf`: **none**. The accessor narrowing avoided the cast, as planned.
- New `Frame.internal`: **none**.
- New `AllowUnsafe`: **none added**. The two `AllowUnsafe.embrace.danger` imports
  remain in the same positions as before (Reflect.scala accessor signature kept as
  `using AllowUnsafe`; `AstUnpickler.runPass1` `_addrMap.set` block kept with embrace).
  This is the **pre-existing** unsafe usage explicitly approved (write-once + lazy body
  decode), not new.
- New `Sync.Unsafe.defer`: **none**.
- New `null`: **none added** (existing `null` sentinels in `TreeUnpickler.decodeSync`
  and `TypeUnpickler.readTypeIntoSession` ctx construction were already there).

Clean.

---

## Cross-platform consistency

- `scala.collection.immutable.IntMap` is in `scala-library` (Scala 2 carryover, Scala 3
  re-exported under the same FQN). Available on JVM, Scala.js, Scala Native.
- All six modified production sources live in `kyo-reflect/shared/src/main/scala/kyo/...`.
  No JVM-only conditional code added. No platform-specific imports.
- Commit message reports: "Native and JS Test/compile clean". Source-only diff
  inspection confirms no platform-divergent code introduced.

PRESENT_STRICT.

---

## Steering deviation (files-touched vs Phase 5 "Files to modify")

Files in commit (`git show eaf7970f7 --name-only`):

Production (6):
- `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` (in plan as TastyOrigin update)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/AstUnpickler.scala` (in plan)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/CommentsUnpickler.scala` (in plan)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/PositionsUnpickler.scala` (in plan)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TreeUnpickler.scala` (anticipated in prep doc Downstream impact)
- `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/tasty/TypeUnpickler.scala` (anticipated in prep doc Concerns #1 and #3)

Tests (5):
- `PositionsUnpicklerTest.scala` (T-P5-1 new; existing tests rewritten to `IntMap`)
- `AstUnpicklerTest.scala` (existing T1 assertion changed `mutable.HashMap` to `IntMap`)
- `CommentsUnpicklerTest.scala` (`Map(...)` to `IntMap(...)`)
- `TreeUnpicklerTest.scala` (one `mutable.HashMap.empty` to `IntMap.empty`)
- `TypeUnpicklerTest.scala` (default `addrMap` param to `IntMap.empty`; 9 call-sites
  switched to `IntMap(...)`)

Scope-preserving check: every test diff is a one-token swap (`Map` to `IntMap`,
`mutable.HashMap.empty` to `IntMap.empty`) plus the import. No test semantics changed.
Production diffs are pure type renames plus the single `IntMap.from(...iterator)`
conversion at AstUnpickler.scala:153 and the `IntMap.from(...iterator)` snapshot at
TypeUnpickler.scala:173. No behavioral logic edits, no skipped tests, no scope cuts.

Plan Phase 5 listed: PositionsUnpickler.scala, AstUnpickler.scala, CommentsUnpickler.scala,
TastyOrigin (= Reflect.scala). The prep doc additionally identified `TypeUnpickler.scala`
(`DecodeCtx.addrMap`, `TreeTypeSession.addrMap`, `readTypeIntoSession` snapshot) and
`TreeUnpickler.scala` (`DecodeCtx.addrMap`) as required for the change to compile (prep
Downstream impact and Concerns #1, #3). Both are present in the commit. **No
unsanctioned files touched.**

---

## Anti-flakiness measures

- **T-P5-1:** pure functional. No concurrency primitives, no time, no I/O, no
  scheduling. Builder produces a deterministic `Map`. Spot checks are exact equality.
  No flakiness vector.
- **Existing test updates:** mechanical type-symbol substitution. No semantic delta.

PRESENT_STRICT.

---

## Specific Phase 5 concern: accessor narrowing

Verified `Reflect.scala:780`:

```
def addrMap(using AllowUnsafe): IntMap[Reflect.Symbol] =
    if _addrMap.isSet then _addrMap.get()
    else IntMap.empty
```

The accessor returns `IntMap[Reflect.Symbol]` directly (narrowed from the prior
`scala.collection.Map[Int, Reflect.Symbol]`). `TreeUnpickler.decodeSync:38`
(`val addrMap = origin.addrMap`) now infers `IntMap[Reflect.Symbol]`, which flows
unchanged into `new TypeUnpickler.TreeTypeSession(names, addrMap, ...)` at line 55
because `TreeTypeSession.addrMap` is now `IntMap[Reflect.Symbol]`. **No
`asInstanceOf` was needed and none was introduced**. This confirms the prep doc's
Concerns #2 resolution was applied correctly.

---

## Categorized findings

### BLOCKER
**None.** Nothing in the commit halts Phase 7 SLOT-A launch.

### WARN
**None.** All planned + prep-anticipated changes present; no scope drift; no unsafe
expansion; no test weakening; no platform-specific code; no public API churn.

### NOTE
- Commit message correctly states T-P5-1's allocation-elimination assertion is
  deferred to Phase 8 re-profiling (matches plan).
- The `TypeUnpickler.readTypeIntoSession` snapshot change extends boxing elimination
  to the Pass-1 type-decode path as well (free additional win, flagged in prep
  Concerns #1; explicitly intentional).
- `mutable.HashMap` is still used inside `AstUnpickler.runPass1` for the *building*
  phase of `addrMap` (insertion-heavy `walkStats` loop) and for `DecodeSession.liveAddrMap`
  (mutated as new symbols are discovered). Conversion to `IntMap` happens once at the
  end, exactly as documented in prep "AstUnpickler runPass1 (conversion site)".
  Not a defect.

---

## Verdict

Phase 5 is **complete and correct**. Phase 7 SLOT-A is unblocked.
