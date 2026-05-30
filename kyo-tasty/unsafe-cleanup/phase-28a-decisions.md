# Phase 28a decisions: PerfCounters -> TastyPerfStats

## Naming choice: TastyPerfStats vs coalescing into TastyStat

Kept `TastyPerfStats` as a separate object. Reason: `TastyStat` holds the module-level `Stat` scope
for tracing spans (used by `ClasspathOrchestrator`). Coalescing the 12 per-stage counters into
`TastyStat` would have mixed two distinct concerns (span tracing vs. cold-load aggregate counters)
and inflated that file. `TastyPerfStats` mirrors `TastyStat` in naming convention but carries only
performance counters.

## reset() handling

`PerfCounters.reset()` had a single callsite: `ClasspathOrchestrator.runPhaseAB` at line 168, called
once per `openInto` invocation to zero the counters before the pipeline run. With `UnsafeCounter`,
`get()` calls `sumThenReset()` internally, so every read of a counter automatically drains it. The
explicit reset call was dropped entirely. The log block in `runPhaseAB` reads each counter exactly
once immediately after the pipeline completes, consuming the accumulated value for that load.
`PerfCountersTest` tested the `reset()` behavior and the `Snapshot` struct; both are gone with
`PerfCounters`.

## AllowUnsafe propagation paths

1. `JvmFileSource.read()` -- changed `Sync.defer` to `Sync.Unsafe.defer`. This provides `AllowUnsafe`
   to the entire try/catch body, including the `readJarEntry` call. Rationale: `read()` is already a
   suspension boundary (returns `Array[Byte] < (Sync & Abort[TastyError])`); upgrading to
   `Sync.Unsafe.defer` is correct because the body performs both Java I/O side effects and counter
   increments that require `AllowUnsafe`.

2. `JvmFileSource.readJarEntry()` -- added `(using AllowUnsafe)`. The method is private, never
   user-facing. All callers are within `Sync.Unsafe.defer` blocks after this change.

3. `ClasspathOrchestrator.timed()` -- changed signature from `(counter: AtomicLong)` to
   `(counter: UnsafeCounter)(using Frame, AllowUnsafe)`. The `.map` closure calls `counter.add()`,
   which requires `AllowUnsafe`. All callers are within `decodeTastyBytes` which already holds
   `import AllowUnsafe.embrace.danger` (pre-existing, for `Symbol.fullName`).

4. `ClasspathOrchestrator.runPhaseAB` timing log block -- changed inner `Sync.defer` to
   `Sync.Unsafe.defer`. This is a cold path (only fires when `-Dkyo.reflect.timing=true`). Each of
   the 12 `TastyPerfStats.*.get()` calls requires `AllowUnsafe`. No new
   `import AllowUnsafe.embrace.danger` was introduced.

5. `ClasspathOrchestrator.decodeOneEntry` and `readAndDecodeTastyFile` entry-count increments --
   wrapped the two-line `entryReads.inc() / bytesRead.add()` blocks with `Sync.Unsafe.defer { }.andThen(...)`.
   These are called inside `.flatMap` continuations that are not already in an `AllowUnsafe` scope.
   Using `Sync.Unsafe.defer` here is the steering-approved option (b) for effectful contexts that
   are not inner-loop hot paths. The actual inner-loop hot path (timed unpicklers in `decodeTastyBytes`)
   uses propagation, not wrapping.

## Signature changes that ripple to other files

None. `readJarEntry` is private to `JvmFileSource`. `timed` is private to `ClasspathOrchestrator`.
`TastyPerfStats` vals are `private[kyo]` and only accessed from `JvmFileSource` and
`ClasspathOrchestrator`. No public or cross-module signatures changed.

## UnsafeCounter.get() semantics note

`UnsafeCounter.get()` is `sumThenReset()`, not a peek. This means the timing log block consumes all
counter values when it reads them. For the intended use case (one summary line per `openInto` call)
this is correct. Tests that previously relied on `PerfCounters.snapshot()` returning stable values
across multiple reads are gone (the test file was deleted).
