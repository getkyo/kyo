# Phase 07b Audit — PerfCounters snapshot atomicization (B13)

HEAD: 53ef1d1b9
Path: kyo-tasty/audit-fixes/phase-07b-audit.md
Verdict: PASS, ready for Phase 08a.

## Focus categories

1. Snapshot immutability — PASS. `final case class Snapshot(...)` with 12 vals (3 Int, 9 Long). No mutators, no var. Immutable by construction.

2. snapshot() field-by-field semantics — PASS as designed. Each `AtomicXxx.get()` is per-field atomic (volatile read). The Snapshot is NOT a globally-linearizable view across all 12 fields, but the B13 contract is "snapshot-then-zero produces a coherent pre-reset reading"; reset() captures snapshot() BEFORE any .set(0), so every returned field is a pre-zero per-field atomic read. Test 1 asserts the only cross-field ordering invariant the writer establishes (entryReadCount >= jarOpenCount - 1) and validates the per-field-atomic guarantee suffices for the contract. No claim of stronger atomicity is made anywhere in source/tests/decisions.

3. reset() signature change Unit -> Snapshot — PASS. PerfCounters is `private[kyo]`, callers grepped exhaustively: 1 production caller (ClasspathOrchestrator.scala:168, updated to `val _ = PerfCounters.reset()`) + 2 test sites in PerfCountersTest. No external API breakage. Discard via `val _ =` matches codebase convention.

4. Cross-platform — PASS. Source lives in shared/; verify.md records JVM 2/2 + JS 2/2 + Native 2/2 green. Test file in shared/src/test (correct placement, not jvm-only).

## NOTE for Phase 08a prep

Test 1's reader uses `scala.collection.mutable.ArrayBuffer` + while-loops inside `Sync.defer`. Acceptable for in-loop micro-perf (pre-allocated cap=100), consistent with the AtomicInteger fiber-id pattern flagged in 07a. No blocker; if 08a opens an adjacent test, prefer Chunk-builder idiom over ArrayBuffer.

Overall: ready.
