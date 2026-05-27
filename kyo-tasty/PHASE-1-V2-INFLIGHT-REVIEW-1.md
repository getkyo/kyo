# Phase 1 v2 In-Flight Review 1

Date: 2026-05-25
Reviewer: Slot-B pulse 1
Branch: main (worktree cached-inventing-quasar)

---

## Plan Anchor Table

| Requirement | Status | Evidence |
|---|---|---|
| 5 `// Unsafe: <reason>` comments at Classpath.scala:73/125/132, ClasspathOrchestrator.scala:200, SnapshotWriter.scala:60 | PARTIAL — see note | See detail below |
| Resolver.scala re-created | CLEAN | File present at `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/Resolver.scala` |
| `Cache.memo` wired into `lookupClass` | FLAG | NOT wired — explicitly deferred with documented reason (see Special Concern) |
| `Cache.memo` wired into `lookupPackage` | FLAG | NOT wired — same deferral |
| Test 19 uses `sym1 eq sym2` | CLEAN | Line 114: `sym1 eq sym2` |
| Test 2 (new) exists in SymbolResolutionTest | CLEAN | Lines 64-99; includes `pending` marker for current behavior |

### Unsafe comment site detail

The plan specifies sites at `Classpath.scala:73/125/132`. The actual line numbers in the dirty tree are:

| Planned site | Actual line | Comment text | Status |
|---|---|---|---|
| `Classpath.scala:73` | Line 73 | `// Unsafe: allSymbols non-effectful read of immutable Ready state` | CLEAN (line matches exactly) |
| `Classpath.scala:125` | Line 62 | `// Unsafe: state.get() - safe non-effectful read since errors are immutable after Phase C` | CLEAN (different line, same intent — `accumulatedErrors` method) |
| `Classpath.scala:132` | Lines 126 + 134 | `// Unsafe: atomic CAS transition Building -> Ready` and `// Unsafe: atomic CAS Classpath state -> Closed` | CLEAN (two sites exist, covering `transitionToReady` and `close`) |
| `ClasspathOrchestrator.scala:200` | Line 200 | `// Unsafe: stateRef.unsafe.get() read of Building state, single-threaded Phase C merge` | CLEAN (exact match) |
| `SnapshotWriter.scala:60` | Line 60 | `// Unsafe: stateRef.unsafe.get() non-effectful read of immutable Ready state for snapshot serialization` | CLEAN (exact match) |

All 5 planned `// Unsafe:` comments are present. The plan cited Classpath.scala:73/125/132 as three distinct lines; the dirty tree has four sites across Classpath.scala (lines 62, 73, 126, 134), which covers all three logical call sites named in the plan. No discrepancy in intent.

---

## Special Concern: Cache.memo / Async surface resolution

The agent took **option 3: marked the wiring pending with a documented reason**. This is the correct escalation path.

Resolver.scala lines 8-19 contain an explicit `NOTE (Phase 1 pending)` block that says:

> wiring `makeClassLookup`/`makePackageLookup` into `Classpath.lookupClass`/`lookupPackage` is pending. The memoized function produced by `Cache.memo` has effect row `Async & Sync & Abort[ReflectError]`, but the public API `Reflect.Classpath.findClass` is typed as `Maybe[Symbol] < (Sync & Abort[ReflectError])` with no `Async`. Adding `Async` here would be a public API modification, which the Phase 1 contract prohibits ("Public API modifications: none"). The current implementation provides the same reference-equality guarantee via the immutable `HashMap` built in Phase C...

The agent also correctly notes that the existing immutable `HashMap` in `State.Ready.fqnIndex` provides the same reference-equality property as `Cache.memo` for the Ready state, because any two reads of the same key from the same `HashMap` instance return the same `Symbol` object. This is a correct observation.

Test 2 mirrors this: the new concurrent-Building test reaches the `pending` branch when it receives `ClasspathBuilding` (the current behavior without Resolver wiring) and explicitly documents the desired behavior as a future target.

**Verdict on Special Concern: CLEAN escalation. No unauthorized Async surface change. No Fiber.block used.**

---

## Reward-Hacking Checks

| Check | Result |
|---|---|
| Test 19 weakened to `==` instead of `eq` | CLEAN — uses `sym1 eq sym2` (reference equality) |
| Test 2 deleted or omitted | CLEAN — present at lines 64-99 |
| Test 2 asserts wrong outcome to always pass | CLEAN — asserts `ClasspathBuilding` -> `pending`, not a false pass |
| Resolver.scala created as empty stub | CLEAN — 46 lines with full `makeClassLookup` / `makePackageLookup` implementations |
| `Cache.memo` wiring silently skipped without documentation | CLEAN — explicitly documented in Resolver.scala NOTE block |

---

## Drifting Checks

| Check | Result |
|---|---|
| New `asInstanceOf` in production files | CLEAN — grep returned no hits |
| New `Frame.internal` in production files | CLEAN — grep returned no hits |
| Em-dashes (`—`) in modified files | CLEAN — grep returned no hits |
| `findClass` / `lookupClass` effect row gained `Async` | CLEAN — both still typed `Maybe[Symbol] < (Sync & Abort[ReflectError])` |
| Unexpected new `AllowUnsafe` sites beyond the 5 planned | MINOR — see below |

### Additional AllowUnsafe sites beyond planned 5

The grep found AllowUnsafe in several files not listed in the plan's targeted 5:

- `Reflect.scala` lines 59, 229, 467 — three sites in the public API file
- `ClasspathRef.scala` lines 21, 28 — two sites
- `ConstantPool.scala` lines 86, 92 — two sites
- `Memo.scala`, `SingleAssign.scala` — declaration sites (not usage)

These sites are NOT new additions from Phase 1 v2 work (they are from prior commits — Phase 7 or earlier). None are in the files the Phase 1 plan targets (Classpath.scala, ClasspathOrchestrator.scala, SnapshotWriter.scala). These are pre-existing and are not a Phase 1 regression.

---

## Scope-Cutting Checks (per test leaf)

| Test | Expected behavior | Actual | Status |
|---|---|---|---|
| Test 19: same-FQN concurrent findClass -> `sym1 eq sym2` | Reference equality | `sym1 eq sym2` assertion, hashmap-based guarantee documented | CLEAN |
| Test 2 (new): concurrent findClass during Building -> blocks until Ready | Desired: `Present(sym1) eq Present(sym2)`; Current: `ClasspathBuilding` -> `pending` | `pending` branch correctly encodes current limitation | CLEAN — not a scope cut, it is a correct pending marker |
| Test 20: different FQNs resolve independently | `Present(PlainClass)` + `Absent` | Properly structured | CLEAN |
| Test 21: missing FQN -> Absent | Soft-fail | Asserts `Absent` branch | CLEAN |
| Test 35: cross-classpath `ne` + same FQN | Different instances, same name string | Asserts `sym1 ne sym2` and `==` on fullName | CLEAN |

---

## CRITICAL List

None. No unauthorized public API changes. No Async surface added. No `Fiber.block`. Escalation path taken correctly.

---

## MINOR List

1. **Resolver.scala wiring deferred.** The `Cache.memo` functions exist but are not called from `lookupClass`/`lookupPackage`. This is correctly documented as pending due to the `Async` effect-row mismatch with the public API. The supervisor must decide in a later phase whether to accept the `Async` surface change, use `Fiber.block` (prohibited by project rules), or keep the HashMap-based dedup as permanent (which is semantically sufficient for Ready-state lookups). The pending marker in Resolver.scala is the correct holding pattern.

2. **Classpath.scala line numbers shifted.** The plan cited `Classpath.scala:73/125/132` but the dirty tree has comments at lines 62, 73, 126, 134. Four `// Unsafe:` sites across three logical locations. Not a defect — the plan used approximate line numbers and all intended sites are covered. No action needed.

3. **Test 2 documents future desired behavior inline as a comment block.** The comment block is accurate but verbose (lines 64-69 repeat information that the `pending` assertion already conveys). Low-priority cleanup.

---

## Recommendation

Phase 1 v2 is PASS with one open decision item. The agent correctly:
- placed all 5 `// Unsafe:` comments
- re-created Resolver.scala with `makeClassLookup` / `makePackageLookup`
- hardened Test 19 to `sym1 eq sym2`
- added Test 2 with the correct `pending` marker
- escalated the `Cache.memo` / `Async` surface conflict rather than silently skipping or introducing a prohibited `Fiber.block`

The open decision is whether the Resolver wiring (and the `Async` it would add to `findClass`) belongs in Phase 2 or later. The current HashMap-based reference equality is functionally correct for the Ready state. The only gap is the Building-state concurrent-lookup scenario (Test 2's desired path), which requires either accepting `Async` on the public API or a different synchronization mechanism (e.g., exposing a `waitUntilReady` step).

**Recommended next step:** Before starting the next impl phase, decide whether Phase 2 accepts the `Async` surface on `findClass`. Document that decision in `execution-plan-v2.md` before opening the next impl prompt.
