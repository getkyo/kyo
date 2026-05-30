# Phase 05a Decisions

## Finding B14: withReadBatch pool registration atomicity

### Problem

`withReadBatch` had a two-step sequence:
1. `activePool.set(pool)` (installs pool into the AtomicReference)
2. `Scope.ensure(...)` (registers the cleanup finalizer)

An exception or interrupt between step 1 and step 2 leaves `activePool` pointing at a live
`JarMappedReaderPool` with no finalizer registered. The pool's `closeAll()` never runs,
stranding any mapped buffers for the lifetime of the object.

### Fix: reorder via Scope.acquireRelease

Replaced the two-step pattern with a single `Scope.acquireRelease` call. The acquire
lambda allocates the pool and calls `activePool.set(pool)` atomically from the Kyo runtime's
perspective: the release is registered as part of the acquire handshake. If the acquire
itself throws before completing, nothing is registered and nothing leaks. If the acquire
succeeds, the release is guaranteed to run on any exit (success, Abort failure, or Panic).

The release calls `pool.closeAll()` then `activePool.set(null)`, preserving the same
cleanup semantics as before.

### Decision: Scope.acquireRelease over try/finally

The plan's AFTER snippet uses `Scope.acquireRelease`. A try/finally alternative would
require bridging back into Kyo effects (since `Scope.ensure` is effectful), making it
more complex. `Scope.acquireRelease` is the canonical Kyo idiom and directly expresses the
acquire-release contract. Chosen.

### Decision: activePool.set(pool) inside acquire lambda

`activePool.set(pool)` remains inside the acquire lambda (not moved after it). The pool
must be visible to `readJarEntry` before the body runs, and the acquire lambda runs to
completion before the body is entered. This preserves the install-before-use ordering.

### Test approach: reflection for activePool

`activePool` is a private field with a Scala-mangled JVM name. Tests access it via
`getDeclaredField("kyo$internal$tasty$query$JvmFileSource$$$activePool")`, consistent with
the existing P04a-T3 precedent in the same test file. The field is declared on the module
class (`JvmFileSource$`), accessed through `JvmFileSource.getClass`.

### Tests added (JvmFileSourceTest.scala)

- P05a-T1: normal exit path. Runs `Scope.run(JvmFileSource.withReadBatch(body))` where
  body succeeds. Asserts `activePool` is null after `Scope.run` completes.

- P05a-T2: Abort failure path. Runs `withReadBatch` where the body raises
  `Abort.fail[TastyError]`. The `Scope.acquireRelease` release still fires (confirmed by
  Kyo's `Scope.run` semantics: it runs `Abort.run[Any]` internally before closing
  finalizers). Asserts `activePool` is null after the run.

Both tests pass (12/12 total).

### Invariants consumed

- INV-001 (resource lifecycle: all acquired resources must be released on any exit).
