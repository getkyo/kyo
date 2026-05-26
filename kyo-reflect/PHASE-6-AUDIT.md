# Phase 6 Audit: Interner pre-sizing

Commit: `0c42049ed` ("kyo-reflect Phase 6: Interner pre-sizing")

## Test count

Plan §Phase 6 Tests calls for 3 new tests in `InternerTest.scala` (T-P6-1 ... T-P6-3, per the audit prompt's naming).

| Leaf | Status | Cite |
|---|---|---|
| T-P6-1 (pre-sized Interner, growCount==0 after 1000 entries) | PRESENT_WEAKENED | `kyo-reflect/shared/src/test/scala/kyo/InternerTest.scala:84-93` |
| T-P6-2 (pre-sizing does not affect identity semantics) | PRESENT_STRICT | `kyo-reflect/shared/src/test/scala/kyo/InternerTest.scala:98-114` |
| T-P6-3 (exact capacity / threshold behavior) | PRESENT_STRICT | `kyo-reflect/shared/src/test/scala/kyo/InternerTest.scala:121-141` |

T-P6-1 weakening rationale:
- Plan §Phase 6 T1 specifies `new Interner(numShards = 4, initialShardCapacity = 256)` + 1,000 entries + assert `growCount == 0`.
- The committed test uses `initialShardCapacity = 512` (not 256) for 1,000 entries.
- Load-factor math: shards = 4, threshold per shard = `cap * 3/4`. With 1,000 entries uniformly hashed across 4 shards, expected fill is ~250 per shard. At cap 256 threshold is 192; ~250 > 192, so at least one shard would grow. At cap 512 threshold is 384; ~250 < 384, growCount stays 0.
- The load-factor correction is sound. The plan's T1 numbers as written (cap=256, 1,000 entries, no grow) are arithmetically incompatible with the actual load policy in `Interner.internInShard` (`filled * 4 >= table.length * 3`, i.e. 75%). The agent picked a consistent cap to make the assertion truly mean "no resize when adequately pre-sized." This still exercises the spec invariant (Interner sized adequately => no grow), only the constant differs from the plan text.
- Categorized PRESENT_WEAKENED (not STRICT) only because the literal capacity differs from the plan; the test purpose and invariant are intact. T-P6-3 separately demonstrates the exact-threshold behavior the plan likely meant T1 to also illustrate.

## Implementation verification

- Constructor adds `initialShardCapacity: Int` with NO default: `Interner.scala:16` (`final class Interner(numShards: Int, initialShardCapacity: Int):`). No default per `feedback_no_default_params_internal`.
- `growCount: AtomicInteger`, `private[kyo]`: `Interner.scala:20-21` (`private[kyo] val growCount: java.util.concurrent.atomic.AtomicInteger = new java.util.concurrent.atomic.AtomicInteger(0)`).
- `grow` increments `growCount`: `Interner.scala:107` (`growCount.incrementAndGet(): Unit`).
- `require` checks for power-of-2 and >= 1: `Interner.scala:17-19` (both checks on `numShards` and `initialShardCapacity`).
- `ClasspathOrchestrator.countAllEntries` helper exists: `ClasspathOrchestrator.scala:127-138`. Multi-suffix `source.list(root, Chunk(".tasty", "module-info.class"))` matches the Phase 1 walker contract.
- `runPhaseAB` uses pre-walk + sizeHint to construct Interner: `ClasspathOrchestrator.scala:152-155` (`countAllEntries(roots, source).flatMap { entryCount => val sizeHint = (entryCount / numShards).max(16); val interner = new Interner(numShards = numShards, initialShardCapacity = sizeHint) ... }`).
  - NOTE: the plan text specified `Math.max(16, (allEntries.size / 128) * 2)` (i.e. `*2` headroom). The commit uses `(entryCount / numShards).max(16)` with NO `* 2` headroom. This means a fully balanced shard reaches exactly its load-factor threshold with zero spare; any hash skew above average will trigger one grow per affected shard. The 2x slack the plan called for is not present. Flagged as WARN (not BLOCKER) because the deviation only reduces the size of the win, not correctness.
- `Reflect.globalInterner` passes `initialShardCapacity = 16`: `Reflect.scala:35` (`private val globalInterner: Interner = new Interner(numShards = 32, initialShardCapacity = 16)`).
- `ModuleInfoReader` passes `initialShardCapacity = 16`: `ModuleInfoReader.scala:39` (`val interner = new Interner(numShards = 16, initialShardCapacity = 16)`).

## CONTRIBUTING.md / STEERING violations

- **No default params on internal API**: respected. Constructor has no defaults; every call site is explicit.
- **No new unsafe markers**: diff adds 0 `asInstanceOf`, 0 `Frame.internal`, 0 `AllowUnsafe`, 0 `Sync.Unsafe.defer`, 0 new `null` keywords. (Existing `null` sentinels in `Interner.internInShard` for empty slots are unchanged and legitimate concurrency.)
- **No effect aliases / no implicit handlers**: not applicable; pure data-structure change.
- **Tests use public API on LHS**: Interner constructor is the public-to-the-test API for this internal class; tests call `interner.intern(...)` (LHS). `growCount.get()` and `shardSize(...)` are package-private observers used only on RHS for verification. Compliant.
- **No em-dashes / LLM-tells in new code/text**: no em-dashes added in the diff.
- **No @nowarn additions, no opaque-state hacks, no AtomicReference->mutable-class regressions**: none.

## Unsafe markers

Grep over the committed diff (`+`-lines only): zero new `asInstanceOf`, zero new `Frame.internal`, zero new `AllowUnsafe`, zero new `Sync.Unsafe.defer`, zero new bare `null` writes. Pre-existing `AtomicReference` + CAS pattern in `Interner` is preserved verbatim. `AtomicInteger` introduced for `growCount` is JDK-stdlib concurrent primitive (legitimate, cross-platform).

## Cross-platform consistency

- `java.util.concurrent.atomic.AtomicInteger` exists on JVM, Scala.js (emulated), and Scala Native. Same for `AtomicReference`, already in use. Cross-platform fine.
- Commit message reports JVM 50 targeted tests + 28 regression tests passing, Native + JS `Test/compile` clean. I did not re-run these, taking the commit's report at face value as instructed (read-only audit).

## Steering deviation (files touched)

Phase 6 plan §Files to modify lists:
- `Interner.scala` (constructor)
- `ClasspathOrchestrator.scala` (new caller signature, plus computation of `sizeHint`)
- `InternerTest.scala` (3 new tests)
- Test files using `new Interner(...)` updated for mechanical compile fix.

Commit touches:
1. `kyo-reflect/shared/src/main/scala/kyo/Reflect.scala` (mechanical: globalInterner caller).
2. `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/classfile/ModuleInfoReader.scala` (mechanical: caller).
3. `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/query/ClasspathOrchestrator.scala` (planned).
4. `kyo-reflect/shared/src/main/scala/kyo/internal/reflect/symbol/Interner.scala` (planned).
5-17. 13 mechanical test-file caller updates: `AstUnpicklerTest.scala`, `AttributeUnpicklerTest.scala`, `ClassfileReaderTest.scala`, `ClasspathRefDedupTest.scala`, `CommentsUnpicklerTest.scala`, `JavaSignaturesTest.scala`, `JavaSymbolTest.scala`, `NameUnpicklerTest.scala`, `PositionsUnpicklerTest.scala`, `QueryApiTest.scala`, `Scala2PickleTest.scala`, `SymbolResolutionTest.scala`, `TreeUnpicklerTest.scala`, `UnifiedModelTest.scala`.
18. `InternerTest.scala` (planned: 3 new tests).

All within `kyo-reflect/`. All within prompt's "also acceptable mechanical compile-fix" list. **No steering deviation.**

## Anti-flakiness

- T-P6-1: deterministic. Constructed inputs (`s"entry-$i"`), count assertion (`growCount.get() == 0`). FNV-1a is deterministic; distribution across 4 shards for 1,000 keys is by hash, but with cap 512 the per-shard threshold (384) is well above any expected uniform fill (~250) AND above realistic worst-case skew (no single shard will hold 384+ entries out of 1,000 under FNV-1a unless pathologically adversarial inputs are chosen). Safe.
- T-P6-2: deterministic. Reference equality on 5 fixed strings, two pre-sized interners. No threading, no timing.
- T-P6-3: deterministic. 760 entries (under `4 * 192 = 768`) asserts `growCount == 0`; then adds up to 100 more entries until any shard exceeds 192, asserts `growCount > 0`. There IS a theoretical worst case where FNV-1a leaves all 4 shards under 192 after 860 entries; the test does not statically prove the worst case can't happen with the chosen key set. In practice this is robust. Flagged as NOTE, not a flakiness BLOCKER.

## Phase 6 concern: pre-walk overhead

Acknowledged in the audit prompt and respected. `countAllEntries` adds exactly one extra `source.list(root, suffixes)` per root per cold-load. This is duplicative with the `walkRoot` enumeration during `runPhaseAB`. The win is eliminating Interner resize events during decode; the cost is one extra directory/jar walk. Whether the win exceeds the cost is a Phase 8 measurement, not something this audit can verify.

NOTE for Phase 8: re-profiling must compare total cold-load time including the pre-walk, not just decode time, against the baseline. If the pre-walk cost dominates the saved resize cost, Phase 6 should be revisited to derive `entryCount` from the streaming walk (e.g. via an `AtomicInteger` counter incremented during `walkRoot` and used to lazily-pre-size the Interner on first use, or by accepting an over-estimate via a static heuristic).

Combined with W1 below (missing `*2` headroom), this is the highest-leverage finding to verify empirically in Phase 8.

## Categorized findings

### BLOCKER (halts Phase 8 SLOT-A launch)
None.

### WARN
- **W1: sizeHint formula drops the `*2` headroom called out in the plan** (`ClasspathOrchestrator.scala:154`). Plan: `Math.max(16, (allEntries.size / 128) * 2)`. Commit: `(entryCount / numShards).max(16)`. Without the `*2`, a fully balanced shard sits at exactly the 75% load threshold and one grow per shard is expected on any modest hash skew. Win magnitude is roughly half what the plan promised. Phase 8 re-profile will surface this if the resize-event count is non-zero after Phase 6.
- **W2: T-P6-1 capacity constant differs from the plan** (`InternerTest.scala:89`, plan §Phase 6 T1). The plan-as-written prescribes cap=256/1000 entries/growCount==0 which is arithmetically impossible at the actual 75% load policy; the agent corrected to cap=512. The correction is sound, but the plan text was not amended in the same commit, leaving a documentation drift. Recommend updating `execution-plan-perf.md` Phase 6 T1 to match the committed test (or re-deriving the plan's original intent: cap=256 with ~190 entries, OR cap=512 with 1000 entries).

### NOTE
- **N1: pre-walk overhead** is real and must be net-evaluated in Phase 8 alongside resize-savings. If pre-walk cost > resize savings, revisit (lazy counter, static heuristic, or skip pre-walk and accept some resize events).
- **N2: T-P6-3 second loop relies on FNV-1a not leaving all shards under-threshold after 860 entries**. Not adversarial, almost certainly fine, but the test does not statically prove the worst case. Acceptable for a unit test.
- **N3: `growCount.incrementAndGet(): Unit`** type ascription (Interner.scala:107) discards the int result of `incrementAndGet`. Standard Scala 3 idiom for "use only for side effect"; non-issue, noted only because it is an uncommon pattern.

## Verdict

CLEAN to launch Phase 8 SLOT-A. 0 BLOCKER, 2 WARN, 3 NOTE. Both WARNs are correctness-neutral; the pre-walk cost/benefit (N1) combined with the missing `*2` headroom (W1) is the main open question and is exactly what Phase 8 measurement will resolve.
