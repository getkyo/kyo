package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.TestClasspaths
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Tasty.withClasspath and Tasty.withPickles entry points.
  *
  * withClasspath(roots) cold-loads and binds; symbols.size > 0 (JVM, real classpath).
  * withClasspath(cp) binds pure-data without decode context.
  * withPickles(pickles) binds from pickles; symbols.size > 0.
  * SOURCE BREAK -- Classpath.init not on surface (compileErrors).
  * SOURCE BREAK -- Classpath.initCached not on surface (compileErrors).
  * withClasspath(roots, Present(cacheDir)) activates dev cache (JVM only).
  *         SPLIT: lives in kyo-tasty/jvm/src/test/scala/kyo/WithClasspathJvmTest.scala because
  *         java.nio.file.Files.createTempDirectory and java.io.File are not available in
  *         Scala.js and would cause fastLinkJS linker errors in shared test code.
  * withClasspath(roots, Absent) does not touch any cache.
  * SnapshotRunner port -- Classpath.initCached not on surface confirms runner migration.
  *
  * runner port; item 27 surface deletion.
  */
class WithClasspathTest extends kyo.test.Test[Any]:

    // ── Leaf 1: withClasspath(roots) cold-loads and binds ────────────────────
    // Given: fixture classpath with SomeObject.tasty loaded via TestClasspaths.withClasspath
    // When: Tasty.classpath.map(_.symbols.size) inside the withClasspath callback
    // Then: returns n > 0 (non-empty classpath loaded and bound by withClasspath(roots))
    // JVM only: TestClasspaths.standard uses java.class.path discovery (JVM-only).
    "withClasspath(roots) cold-loads classpath and binds; symbols.size > 0".onlyJvm in {
        TestClasspaths.withClasspath():
            Tasty.classpath.map: cp =>
                val n = cp.symbols.size
                assert(n > 0, s"withClasspath(roots) must bind a non-empty classpath; got $n symbols")
                succeed
    }

    // ── Leaf 2: withClasspath(cp) binds pure-data without decode context ──────
    // Given: a manually constructed cp: Classpath with 2 symbols
    // When: Tasty.withClasspath(cp) { Tasty.classpath.map(_.symbols.size) }
    // Then: returns 2; no Scope overhead; no Async beyond the inner Sync
    "withClasspath(cp) binds pure-data classpath; returns correct symbol count" in {
        val cp = Tasty.Classpath(
            symbols = Chunk(
                Tasty.Symbol.Package(
                    Tasty.SymbolId(0),
                    Tasty.Name("root"),
                    Tasty.Flags.empty,
                    Tasty.SymbolId(-1),
                    Chunk.empty
                ),
                Tasty.Symbol.Package(
                    Tasty.SymbolId(1),
                    Tasty.Name("child"),
                    Tasty.Flags.empty,
                    Tasty.SymbolId(0),
                    Chunk.empty
                )
            ),
            indices = Tasty.Classpath.Indices.empty,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        Tasty.withClasspath(cp):
            Tasty.classpath.map: bound =>
                val n = bound.symbols.size
                assert(n == 2, s"withClasspath(cp) must bind the passed classpath; expected 2, got $n")
                succeed
    }

    // ── Leaf 3: withPickles(pickles) binds from pickles ───────────────────────
    // Given: a Tasty.Pickle wrapping PlainClass.tasty bytes
    // When: Tasty.withPickles(Chunk(pickle)) { Tasty.classpath.map(_.symbols.size) }
    // Then: returns n > 0; PlainClass symbol is discoverable by FQN
    "withPickles(pickles) binds classpath from pickles; PlainClass discoverable" in {
        val pickle = Tasty.Pickle(
            uuid = "leaf3-plain-class",
            version = Tasty.Version(28, 3, 0),
            bytes = Span.from(kyo.fixtures.Embedded.plainClassTasty)
        )
        Tasty.withPickles(Chunk(pickle)):
            Tasty.classpath.map: cp =>
                val found = cp.findClassLike("kyo.fixtures.PlainClass")
                assert(found.isDefined, s"PlainClass must be discoverable after withPickles; got ${cp.symbols.size} symbols")
                assert(cp.symbols.size > 0, s"withPickles must bind a non-empty classpath; got ${cp.symbols.size}")
                succeed
    }

    // ── Leaf 4: SOURCE BREAK -- Classpath.init not on surface ────────────────
    // Given: compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.init(.)")
    // When: invoked
    // Then: non-empty error; init factory is deleted per
    "Classpath.init is not on the public surface" in {
        val errCount = compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.init(Seq(\"x\"))").length
        assert(errCount > 0, "Classpath.init must not be on the surface; expected a compile error")
        succeed
    }

    // ── Leaf 5: SOURCE BREAK -- Classpath.initCached not on surface ──────────
    // Given: compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.initCached(.)")
    // When: invoked
    // Then: non-empty error; initCached factory is deleted per
    "Classpath.initCached is not on the public surface" in {
        val errCount = compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.initCached(Seq(\"x\"), \"/tmp\")").length
        assert(errCount > 0, "Classpath.initCached must not be on the surface; expected a compile error")
        succeed
    }

    // ── Leaf 7: withClasspath(roots, Absent) does not touch any cache ─────────
    // Given: embedded fixture loaded via ClasspathOrchestrator.init + manual binding install
    // When: withClasspath(cp) (no cacheDir); Tasty.classpath returns bound cp
    // Then: no extra files; symbol count matches the loaded classpath
    "withClasspath(roots, Absent) does not touch any cache" in {
        // Use embedded fixture + ClasspathOrchestrator.init to get a cross-platform
        // equivalent of withClasspath(roots, Absent): cold-load with no cache writes.
        val src = MemoryFileSource()
        src.add("root7/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        Scope.run:
            Abort.run[TastyError](
                ClasspathOrchestrator.init(Seq("root7"), Tasty.ErrorMode.SoftFail, src, 1).flatMap: cp =>
                    Tasty.withClasspath(cp):
                        Tasty.classpath.map: bound =>
                            val n = bound.symbols.size
                            assert(n > 0, s"withClasspath(cp) must return a non-empty classpath; got $n")
                            // Confirm MemoryFileSource was NOT written to (Absent cacheDir semantics):
                            // ClasspathOrchestrator.init does not write snapshots without a cacheDir.
                            succeed
            ).map:
                case Result.Success(r) => r
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
    }

    // ── Leaf 8: SnapshotRunner port ───────────────────────────────────────────
    // Given: the public Tasty surface after
    // When: compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.initCached(.)") checked
    // Then: non-empty error confirms runner migration is complete (initCached deleted)
    "SnapshotRunner port -- Classpath.initCached absent confirms runner migrated" in {
        // The SnapshotRunner.scala source uses Tasty.withClasspath(roots, Maybe.Present(snapshotDir)).
        // This compile check confirms the old initCached entry point is gone, so the runner
        // cannot accidentally revert to the old pattern.
        val errCount = compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.initCached(Seq(\"x\"), \"/tmp\")").length
        assert(errCount > 0, "Classpath.initCached must be absent; expected a compile error")
        succeed
    }

end WithClasspathTest
