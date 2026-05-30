# Phase 07b Decisions

## Finding B13: PerfCounters.reset partial-reset race

### Decision: snapshot-then-zero pattern

Added `PerfCounters.Snapshot` final case class holding all 12 counter values. Added `snapshot()` which reads each counter individually via `.get()` and returns a frozen `Snapshot`. Changed `reset()` return type from `Unit` to `Snapshot`: it calls `snapshot()` to capture the pre-reset state, then zeroes each counter via `.set(0)` / `.set(0L)`, and returns the captured snapshot.

This is the simplest correct fix. The snapshot and the zero sequence are not globally atomic, but `reset()` now returns a coherent pre-reset view regardless. Callers that need a point-in-time reading can use the returned snapshot rather than reading individual counters after zeroing.

### Caller update

`ClasspathOrchestrator.scala` line 168 called `PerfCounters.reset()` discarding the `Unit` result. Now that `reset()` returns `Snapshot`, the discard is explicit via `val _ = PerfCounters.reset()` to suppress the unused-value warning.

### Tests added

`kyo-tasty/shared/src/test/scala/kyo/PerfCountersTest.scala` (new, 2 tests):

1. Concurrent coherence (B13): writer fiber increments `jarOpenCount` then `entryReadCount` 200 times; reader fiber collects 100 snapshots concurrently via `Fiber.initUnscoped`. Every snapshot must satisfy `entryReadCount >= jarOpenCount - 1` (tolerating the 1-increment in-flight window between the two increments).

2. Reset pre-capture (B13): sets all 12 counters to known values, calls `reset()`, asserts the returned `Snapshot` holds all 12 original values, and asserts all counters are zero post-reset.

### Verification

- JVM Test/compile: PASS
- JVM testOnly kyo.PerfCountersTest: 2/2 PASS
- JS Test/compile: PASS
- Native Test/compile: PASS
- HEAD: 937e589a2 (unchanged, no commit)
