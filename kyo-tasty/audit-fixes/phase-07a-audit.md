# Phase 07a Audit — Interner B12 widened grow synchronization

HEAD: `937e589a2`
Path: `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/symbol/Interner.scala`
Tests: `kyo-tasty/shared/src/test/scala/kyo/InternerTest.scala`

## 1. Reentrancy — PASS

`internInShard` acquires `shardRef.synchronized` (lines 84-87) and calls `growShard`, which itself acquires `shardRef.synchronized` (line 114). JVM intrinsic monitors are reentrant per-thread, so the nested acquisition by the same thread is safe. The inner `current.length() == observedLen` re-check inside `growShard` is retained as defense-in-depth (consistent with the commit message claim).

## 2. Identity check `eq table` — PASS

`shardRef` is `AtomicReference[AtomicReferenceArray[Entry]]`. `growShard` swaps the underlying reference via `shardRef.set(grown)` (line 133), publishing a brand-new `AtomicReferenceArray` instance. Reference equality (`shardRef.get() eq table`) on line 85 correctly distinguishes "still the same generation" from "a concurrent grow already replaced it". Semantics are correct.

## 3. Performance — PASS

The widened `synchronized` block is gated by the load-factor branch (`loadCounter.get() * 4 >= len * 3`), which only fires when a shard is approaching capacity. The hot insert path (existing != null, or empty slot below threshold) takes zero monitor acquisitions. No measurable happy-path slowdown is expected.

## 4. Test integrity — PASS

Both tests use `Async.foreach(..., parallelism = fiberCount)` for genuine concurrency and target a tiny configuration (2 shards / 4 slots, or 1 shard / 2 slots) so the grow path is forced repeatedly. Test 1 (8 fibers x 1000 unique keys) exercises concurrent grow + insert and verifies post-hoc reference equality on re-intern. Test 2 (4 fibers, shared `[1,2,3]` key) exercises the single-winner CAS under grow contention. The window is genuinely exercised.

## NOTE for Phase 07b prep

`AtomicInteger fiberIdCounter` in test 1 is necessary because `Async.foreach` over `Chunk.fill(N)(())` does not expose a per-element index; if 07b adds more concurrency tests, prefer `Chunk.range(0, N)` to drop the counter and stay within the `feedback_atomic_not_var` spirit (here it is a benign test-only counter and was accepted in verify).

## Overall: ready
