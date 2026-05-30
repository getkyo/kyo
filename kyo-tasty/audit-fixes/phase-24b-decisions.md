# Phase 24b Decisions

## Decision 1: Pool API surface - no maxPoolSize or activeCount

The current `JarMappedReaderPool` is unbounded (ConcurrentHashMap, no size limit).
The plan mentions `maxPoolSize=2` and `activeCount` but those fields do not exist in the
implementation. Per the impl prompt's guidance ("Plan precision is less important than
EXERCISING the close-after-use error path"), the pool exhaustion test was adapted to:
- Use `Async.foreach(50 fibers, concurrency=50)` so all fibers contend on the pool concurrently.
- Assert correctness of every fiber's read result.
- Assert `activePool.get()` returns null after scope exit (via reflection on the private field).

This exercises the pool-under-load and release-on-scope-exit invariants without requiring
a size-limited pool API that does not exist.

Time: 2026-05-30

## Decision 2: Concurrency pattern for pool test - Async.foreach

`Async.foreach(fibers, concurrency = fiberCount) { _ => JvmFileSource.read(fullPath) }` is
the canonical kyo cross-platform concurrency primitive (per Phase 24a decisions). All 50
fibers run at full concurrency. The test is jvmOnly because it uses JAR I/O and reflection
on `JvmFileSource.activePool`.

Time: 2026-05-30

## Decision 3: close-during-body test uses explicit serialization (not a race)

The plan offered two strategies: a race-condition-based test, or an explicit serialization.
The serialization strategy was chosen:
1. Open classpath inside Scope.
2. Find a symbol with a non-zero body slice.
3. Close the classpath manually via InternalClasspath.close(rawCp).
4. Call sym.body.
5. Assert ClasspathClosed.

This is deterministic, does not rely on scheduler timing, and directly exercises the
isClosed guard in Symbol.body (Tasty.scala lines 824-825).

Time: 2026-05-30

## Decision 4: classpath-close test placement - ClasspathOrchestratorPipelineTest.scala

Test P24b-T2 (classpath close during body decode) uses the in-memory MemFileSource that
already exists in ClasspathOrchestratorPipelineTest. This is cross-platform (JVM, JS,
Native). The test was added to ClasspathOrchestratorPipelineTest rather than creating a new
file, because the existing openFixtureClasspath helper and MemFileSource are already present
in that file and the test is thematically consistent with the pipeline lifecycle tests.

Time: 2026-05-30

## Decision 5: mmap arena close test placement - JvmFileSourceTest.scala

Test P24b-T3 (mmap arena close during Symbol.body) uses a local MemSrc (not real mmap) but
is tagged jvmOnly and placed in JvmFileSourceTest because:
- The test exercises the same code path as a real mmap-backed classpath (the isClosed guard
  in Symbol.body and the IllegalStateException mapping).
- JvmFileSource is the JVM-specific entry point; co-locating the test avoids creating a new
  test file.
- The MemSrc helper uses the in-memory pattern from TreeUnpicklerTest, which runs cleanly
  on JVM. The jvmOnly tag prevents it from running on JS/Native where the MemSrc would also
  work but the test is categorized as JVM-specific per the plan.

Time: 2026-05-30

## Decision 6: AllowUnsafe provision for allSymbols in JvmFileSourceTest

`rawCp.allSymbols` and `InternalClasspath.close(rawCp)` require AllowUnsafe. In
JvmFileSourceTest (no class-level import AllowUnsafe), AllowUnsafe is provided via
Sync.Unsafe.defer which implicitly provides AllowUnsafe per Sync.scala:138-141. The
`// Unsafe:` comment is present above Sync.Unsafe.defer call sites per coding conventions.

Time: 2026-05-30

## Platform test counts

- JVM: 473 tests (16 in JvmFileSourceTest including 2 new, 9 in ClasspathOrchestratorPipelineTest including 1 new)
- JS: 388 tests passed, 48 ignored (jvmOnly tests)
- Native: 390 tests passed, 49 ignored (jvmOnly tests)
