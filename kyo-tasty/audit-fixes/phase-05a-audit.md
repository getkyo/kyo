# Phase 05a Audit — B14 acquireRelease atomicization

**HEAD**: `3e852b129`
**Files**: `kyo-tasty/jvm/src/main/scala/kyo/internal/tasty/query/JvmFileSource.scala`, `kyo-tasty/jvm/src/test/scala/kyo/JvmFileSourceTest.scala`

## Verdict per category

1. **`Scope.acquireRelease` covers Panic**: PASS. Verified in `kyo-core/shared/src/main/scala/kyo/Scope.scala:86-91` — `acquireRelease` delegates to `ensure(release(resource))`. `Scope.run` (lines 135-148) wraps the body with `Sync.ensure(finalizer.close)` then `Abort.run[Any]`, then calls `finalizer.close(result.error)` with the captured outcome. `Abort.run[Any]` reifies both Failure and Panic into `Result`; `Sync.ensure` covers raw JVM exceptions. So release fires on normal exit, Abort.Failure, AND Panic. B14 race window structurally closed.

2. **Reflection use**: PASS-with-NOTE. `getDeclaredField("kyo$internal$tasty$query$JvmFileSource$$$activePool")` follows the P04a-T3 precedent already in the same test file. Acceptable for now.

3. **P05a-T2 Abort failure path**: PASS. Test wraps `Abort.run[TastyError](Scope.run(failBody))`. Critical ordering: `Scope.run` returns `A < (Async & S)` where `S` retains `Abort[TastyError]`, so `Abort.run` outside catches the failure AFTER finalizers fire (because `Scope.run` internally does `Abort.run[Any]` then re-raises via `Abort.get` after `finalizer.close`). Pattern-match on `Result.Failure(TastyError.FileNotFound(msg))` is exhaustive against `Result.Success`/`Result.Panic` via the `case other => fail(...)` arm. No swallowing.

4. **Cascade**: PASS. Signature `withReadBatch[A, S](body: A < S)(using Frame): A < (S & Sync & Scope)` unchanged. No caller updates required.

## NOTE for Phase 05b prep

- Reflection on Scala-mangled field name is brittle to compiler changes; consider a test-only inspector (e.g., `private[kyo] def activePoolForTest`) during Phase 26 cleanup sweep alongside the parseCenRecords NOTE forwarded from 04c.
- `Scope.run` returns `Async & S`; P05a-T2 stays inside the test's `run` runner which discharges Async — no new platform skip.

## Overall: READY
