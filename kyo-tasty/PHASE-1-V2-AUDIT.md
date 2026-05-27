# Phase 1 v2 Audit

Commit: `bd33a9af77988b35d385357cae9abedcedefbecd`
Message: "kyo-reflect v2 Phase 1: AllowUnsafe comments + Resolver wired with Async + Test 19 hardening"

Audited against `execution-plan-v2.md` lines 17-55 and `STEERING.md` "v2 Phase 1 Async deviation".

---

## AllowUnsafe comment placement

The plan specified 5 `// Unsafe:` comment sites. The actual file has more than 5 `AllowUnsafe` import
sites because pre-existing sites (from v1 cleanup) are not re-counted here; the plan only required
adding comments to sites that were previously uncommented.

Verified sites:

**Classpath.scala**

- Line 20: field comment `// Unsafe: Latch.Unsafe held as a plain field; initialized once at allocate time, released once at transitionToReady.` (on the field declaration, not before an `AllowUnsafe` import -- this covers the readyLatch field).
- Line 81-82: `// Unsafe: state.get() - safe non-effectful read since errors are immutable after Phase C` before `import AllowUnsafe.embrace.danger` in `accumulatedErrors`.
- Line 92-93: `// Unsafe: allSymbols non-effectful read of immutable Ready state` before `import AllowUnsafe.embrace.danger` in `allSymbols`. PASS -- matches plan item "allSymbols non-effectful read".
- Line 135-138: `// Unsafe: Latch.Unsafe.init requires AllowUnsafe; safe here because this is the single allocation point for the latch and the result is immediately captured.` then `import AllowUnsafe.embrace.danger` in `allocate`. PASS.
- Line 160-161: `// Unsafe: atomic state write + latch release, called from single-threaded Phase C` before `import AllowUnsafe.embrace.danger` in `transitionToReady`. PASS -- matches plan item.
- Line 169-170: `// Unsafe: atomic CAS Classpath state -> Closed, called from Scope finalizer` before `import AllowUnsafe.embrace.danger` in `close`. PASS.

**ClasspathOrchestrator.scala**

- Line 200-201: `// Unsafe: stateRef.unsafe.get() read of Building state, single-threaded Phase C merge` before `import AllowUnsafe.embrace.danger`. PASS.

**SnapshotWriter.scala**

- Line 60-61: `// Unsafe: stateRef.unsafe.get() non-effectful read of immutable Ready state for snapshot serialization` before `import AllowUnsafe.embrace.danger`. PASS.

Summary: All 5 plan-mandated sites have justified `// Unsafe:` comments. The Classpath.scala `accumulatedErrors` site (line 81) was not in the plan's explicit list but received a comment anyway; this is strictly better, not worse. The `readyLatch` field comment (line 20) is a field-level comment rather than a pre-import comment because the field itself holds an `Unsafe` type; acceptable form.

---

## Resolver.scala wiring

`Resolver.scala` exists at `shared/src/main/scala/kyo/internal/reflect/query/Resolver.scala` with:
- `makeClassLookup(cp: Classpath, maxSize: Int): (String => Maybe[Reflect.Symbol] < (Async & Sync & Abort[ReflectError])) < Sync`
- `makePackageLookup(cp: Classpath, maxSize: Int): (String => Maybe[Reflect.Symbol] < (Async & Sync & Abort[ReflectError])) < Sync`

Both use `Cache.memo` wrapping `cp.lookupClass`/`cp.lookupPackage`.

WARN: `Resolver.makeClassLookup` and `Resolver.makePackageLookup` are never called from
`ClasspathOrchestrator` or any production path. Grep across all production `.scala` files shows
zero call sites. The plan's supervisor check states "Classpath.lookupClass calls
Resolver.makeClassLookup (or equivalent Cache.memo path)". The actual `lookupClass` and
`lookupPackage` in `Classpath.scala` perform a direct `fqnIndex.get(fqn).orNull` read from
the immutable `HashMap` without touching `Resolver` or `Cache.memo`.

The deduplication guarantee (test 19 `sym1 eq sym2`) holds anyway because an immutable
`HashMap` returns the same stored reference for the same key on every call. But `Cache.memo`
Promise-dedup for inflight-concurrent calls is not exercised by the production path. The
Resolver file exists but is effectively dead code.

Test 19 comment at line 101 explicitly says "Resolver.scala was deleted; the immutable HashMap
provides the same guarantee without Async overhead." This comment is inconsistent with v2's
stated goal of wiring Resolver; it reads as if this is still v1 (where Resolver was deleted).
The comment should say the dedup comes from the immutable HashMap, not from Resolver/Cache.memo.

Category: **WARN** -- Resolver file created but not called; plan supervisor check not fully
satisfied; commit message and PROGRESS.md claim "Cache.memo is now wired" but it is not on
any live call path.

---

## Effect row change: findClass / findPackage / findClassByBinary

`Reflect.scala` lines 479, 480, 489:
```
def findClass(fqn: String): Maybe[Symbol] < (Sync & Async & Abort[ReflectError])
def findPackage(fqn: String): Maybe[Symbol] < (Sync & Async & Abort[ReflectError])
def findClassByBinary(binaryName: String): Maybe[Symbol] < (Sync & Async & Abort[ReflectError])
```

All three match the supervisor-approved deviation in STEERING.md. PROGRESS.md documents it under
"v2 Phase 1 (Async expansion)". PASS.

---

## readyLatch pattern

`Classpath` has `val readyLatch: Latch.Unsafe` (initialized in `allocate`, released in
`transitionToReady`). `lookupClass` and `lookupPackage` both pattern-match on Building state and
call `readyLatch.safe.await`, then re-check state after waking. The state is written before the
latch is released (`cp.stateRef.unsafe.set(ready)` then `cp.readyLatch.release()`), so waking
callers observe Ready. PASS.

---

## Test count

Per plan: 1 new test (Test 2); Test 19 is a modification (strengthened), not a new leaf.

`SymbolResolutionTest` has:
- Test 2 (new): "concurrent findClass calls during Building state both receive reference-equal symbols after Ready"
- Test 19 (modified): "two concurrent findClass calls for the same FQN return reference-equal symbols"

Total new tests: 1. PASS.

---

## Test 2 semantics

Test 2 uses `Fiber.initUnscoped` for the background `openInto` fiber and `Async.zip` (not
`Async.parallel`) to launch the two concurrent `findClass` calls. The plan says "Fiber.initUnscoped
+ Async.parallel". `Async.parallel` does not exist in this codebase; `Async.zip` is the correct
API that parallelizes two effectful computations. The semantics are equivalent. NOTE only.

Test 2 correctly asserts `sym1 eq sym2` on the reference-equality path. The Building-blocking
semantics are covered: `openInto` is launched before the two `findClass` calls, and both
`findClass` calls use `lookupClass` which awaits `readyLatch` when in Building state. PASS.

However, there is a subtle race: `Fiber.initUnscoped(openInto(...)).andThen:` -- the `.andThen`
means the two `findClass` calls are only launched after `Fiber.initUnscoped` returns (which is
synchronous: it only forks the fiber, it does not wait for it to complete). So both `findClass`
calls may or may not arrive before `openInto` calls `transitionToReady`. If the fiber is fast
enough to complete before the two `findClass` calls start (e.g., scheduler executes it first),
the test degrades to the Ready-state path rather than the Building-blocking path. The test still
passes (reference equality holds in both paths) but may not exercise the latch-blocking branch.
This is a NOTE-level concern; the test is not wrong, just possibly less targeted than the plan
intended.

Category: **NOTE** -- `Async.parallel` does not exist; `Async.zip` is correct. Latch-blocking
branch may not be reliably exercised due to scheduling non-determinism.

---

## Test 19 assertion

`SymbolResolutionTest` test 19 asserts `sym1 eq sym2` (reference equality). The plan required
this change from the v1 FQN-string comparison. PASS.

---

## New AllowUnsafe sites introduced in this commit

The commit message notes: "3 AllowUnsafe sites added in this commit (Classpath.allocate Latch.init,
plus the unsafe Promise.get plumbing) carry // Unsafe: comments." Inspection confirms:

- `Classpath.allocate`: `Latch.Unsafe.init(1)` under `import AllowUnsafe.embrace.danger` with comment.
- `Classpath.transitionToReady`: `stateRef.unsafe.set(ready)` and `readyLatch.release()` (both unsafe calls) under a single import with comment. These are 2 unsafe operations in one block, not 2 sites.
- `Classpath.close`: `stateRef.unsafe.set(State.Closed)` under import with comment.

All new AllowUnsafe sites have `// Unsafe:` comments. No undocumented unsafe usage. PASS.

---

## Frame.internal check

`grep -rn "Frame.internal"` across `shared/src/main/scala/kyo/` returns zero results. PASS.

---

## Em-dash check

`grep -rn "—\|–"` across new source files returns zero results. PASS.

---

## Co-Authored-By check

Commit message does not contain "Co-Authored-By". PASS.

---

## CONTRIBUTING.md alignment

The `// Unsafe:` comment pattern used (comment immediately before `import AllowUnsafe.embrace.danger`,
or on the field holding an `Unsafe` type) matches CONTRIBUTING.md sections "AllowUnsafe Tiers"
and "Unsafe API Conventions". The pattern of scoping `AllowUnsafe` to the smallest block is
followed. PASS.

---

## Summary table

| # | Check | Status |
|---|-------|--------|
| 1 | 5 `// Unsafe:` comments at plan-specified sites | PASS |
| 2 | Resolver.scala re-created | PASS |
| 3 | Resolver wired into lookupClass/lookupPackage via Cache.memo | **WARN** -- file exists, never called |
| 4 | findClass/findPackage/findClassByBinary effect rows `Sync & Async & Abort[ReflectError]` | PASS |
| 5 | readyLatch pattern: Building lookups block until Ready | PASS |
| 6 | Test 2 exercises Building-blocking semantics with Fiber.initUnscoped | PASS (NOTE: scheduling non-determinism) |
| 7 | Test 19 uses `sym1 eq sym2` | PASS |
| 8 | No new undocumented AllowUnsafe sites | PASS |
| 9 | No Frame.internal | PASS |
| 10 | No em-dashes | PASS |
| 11 | No Co-Authored-By | PASS |
| 12 | CONTRIBUTING.md AllowUnsafe pattern followed | PASS |
| 13 | Test count: 1 new leaf | PASS |
| 14 | Test 19 comment consistency (says "Resolver deleted" but Resolver exists) | **WARN** |

---

## Action required before Phase 2

**WARN-1 (Resolver not wired)**: Either call `Resolver.makeClassLookup` from `ClasspathOrchestrator.open`
and store the resulting memoized function for use in `findClass`, OR document in STEERING.md and
PROGRESS.md that the HashMap-identity guarantee is an accepted substitute for Cache.memo and the
Resolver file is intentionally present for future use (Phase 3+ accessors may call `home.resolve`).

**WARN-2 (Test 19 comment)**: Line 101 comment in `SymbolResolutionTest` should not say "Resolver.scala
was deleted". It should either say "dedup is via immutable HashMap identity" (if that is the accepted
design) or be updated once Resolver is wired.

Neither WARN blocks Phase 2 from a compilation or test-correctness standpoint. The 203+1=204 tests
pass. Phase 2 (G13 placeholder resolution) does not directly depend on Cache.memo wiring.
