# Phase 07a Decisions: Widen Interner growShard synchronization

Finding: B12. `Interner.growShard` / `internInShard` double-check race window.

## Problem

`internInShard` observed the load factor via an un-synchronized `loadCounter.get()` (line 77) and then delegated to `growShard`, which serializes via `shardRef.synchronized`. Between the unsynchronized load-factor check and the lock acquisition inside `growShard`, another thread could already have completed a grow, swapping the `AtomicReference` to a larger table. The calling thread would then enter `growShard`, re-check `current.length() == observedLen` (which would be false), skip the grow correctly -- but it returned and retried anyway. The window was semantically safe due to the inner re-check, but it was a non-obvious benign race that could produce spurious grow increments and confused reasoning about shard state.

## Decision

Widened the synchronized window inside `internInShard` so the "observe table ref + grow" pair is atomic with respect to concurrent grows on the same shard. The added guard:

```scala
shardRef.synchronized {
    if shardRef.get() eq table then
        growShard(shardRef, loadCounter, len)
}
return internInShard(shardRef, loadCounter, hash, bytes, offset, length)
```

Key properties:
- `table` was captured at the top of `internInShard` before the while loop.
- `shardRef.get() eq table` inside the lock is true only if no other thread has already grown this shard since we entered the method.
- `growShard` acquires the same `shardRef` monitor; JVM intrinsic locks are reentrant so there is no deadlock from the same thread.
- `growShard` also double-checks `current.length() == observedLen` internally, so correctness is layered.
- Retrying via `return internInShard(...)` re-reads the current table unconditionally, ensuring the calling fiber always works on the post-grow table.

No changes to `growShard` itself were required.

## Approach considered and rejected

Adding only documentation explaining the "benign race" was rejected because the window is non-obvious and eliminates one of the two independent correctness guards. Widening the synchronized region is a one-liner that closes the window structurally.

## Tests added

Two new tests in `kyo-tasty/shared/src/test/scala/kyo/InternerTest.scala`:

1. `B12/Phase-07a: concurrent grow-and-insert preserves all entries under contention` -- 8 fibers, 1000 inserts each on a tiny 2-shard/4-slot interner; verifies all 8000 entries survive and re-interning returns the same Entry reference. Pins B12.

2. `B12/Phase-07a: grow during contention preserves reference equality for shared key` -- 4 fibers race to intern `[1, 2, 3]` on a 1-shard/2-slot interner; verifies all 4 fibers received the same Entry reference. Pins B12.

Both tests use `kyo.Async.foreach` for concurrency (no raw `java.lang.Thread`).

## Verification

- `sbt 'kyo-tasty/Test/compile'` -- PASS (scalafmt ran; all sources clean)
- `sbt 'project kyo-tasty' 'testOnly kyo.InternerTest'` -- 17/17 PASS
- `sbt 'kyo-tastyJS/Test/fastLinkJS'` -- PASS (no `java.lang.Thread` in shared code)
- `sbt 'kyo-tastyNative/Test/compile'` -- PASS
- HEAD: `64d4cc17d` unchanged
