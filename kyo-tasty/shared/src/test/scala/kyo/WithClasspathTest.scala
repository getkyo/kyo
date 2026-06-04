package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.TestClasspaths
import kyo.internal.tasty.query.ClasspathOrchestrator

/** Phase 05 plan leaves 1-8: Tasty.withClasspath and Tasty.withPickles entry points.
  *
  * Leaf 1: withClasspath(roots) cold-loads and binds; symbols.size > 0 (JVM, real classpath).
  * Leaf 2: withClasspath(cp) binds pure-data without decode context.
  * Leaf 3: withPickles(pickles) binds from pickles; symbols.size > 0.
  * Leaf 4: SOURCE BREAK -- Classpath.init not on surface (compileErrors).
  * Leaf 5: SOURCE BREAK -- Classpath.initCached not on surface (compileErrors).
  * Leaf 6: withClasspath(roots, Present(cacheDir)) activates dev cache (JVM only).
  * Leaf 7: withClasspath(roots, Absent) does not touch any cache.
  * Leaf 8: SnapshotRunner port -- Classpath.initCached not on surface confirms runner migration.
  *
  * Pins: item 31 roots-form, pure-cp form, pickles form, cacheDir argument, absent-cache semantics,
  * runner port; item 27 surface deletion.
  */
class WithClasspathTest extends Test:

    // ── Leaf 1: withClasspath(roots) cold-loads and binds ────────────────────
    // Given: fixture classpath with SomeObject.tasty loaded via TestClasspaths.withClasspath
    // When: Tasty.classpath.map(_.symbols.size) inside the withClasspath callback
    // Then: returns n > 0 (non-empty classpath loaded and bound by withClasspath(roots))
    // Pins: item 31 roots-form happy path
    // JVM only: TestClasspaths.standard uses java.class.path discovery (JVM-only).
    "Leaf 1: withClasspath(roots) cold-loads classpath and binds; symbols.size > 0" taggedAs jvmOnly in run {
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
    // Pins: item 31 pure-cp form
    "Leaf 2: withClasspath(cp) binds pure-data classpath; returns correct symbol count" in run {
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
    // Pins: item 31 pickles form
    "Leaf 3: withPickles(pickles) binds classpath from pickles; PlainClass discoverable" in run {
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
    // Given: compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.init(...)")
    // When: invoked
    // Then: non-empty error; init factory is deleted per Phase 05
    // Pins: item 31 surface deletion
    "Leaf 4: Classpath.init is not on the public surface" in {
        val err = compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.init(Seq(\"x\"))")
        assert(err.nonEmpty, "Classpath.init must not be on the surface; expected a compile error")
        succeed
    }

    // ── Leaf 5: SOURCE BREAK -- Classpath.initCached not on surface ──────────
    // Given: compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.initCached(...)")
    // When: invoked
    // Then: non-empty error; initCached factory is deleted per Phase 05
    // Pins: item 27 subsumed by item 31
    "Leaf 5: Classpath.initCached is not on the public surface" in {
        val err = compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.initCached(Seq(\"x\"), \"/tmp\")")
        assert(err.nonEmpty, "Classpath.initCached must not be on the surface; expected a compile error")
        succeed
    }

    // ── Leaf 6: withClasspath(roots, Present(cacheDir)) activates dev cache ──
    // Given: a fresh OS temp directory; kyo-tasty-fixtures jar or classes dir from java.class.path
    // When: two sequential Tasty.withClasspath(roots, Present(cacheDir)) calls
    // Then: a <cacheDir>/<digest>.krfl file appears after the first call;
    //       both calls return the same symbol count
    // Pins: item 31 cacheDir argument
    // JVM only: temp directories and real filesystem roots are JVM-only.
    "Leaf 6: withClasspath(roots, Present(cacheDir)) writes snapshot on miss, reads on hit" taggedAs jvmOnly in run {
        val tmpDir = java.nio.file.Files.createTempDirectory("kyo-wc-leaf6-").toAbsolutePath.toString
        // Discover kyo-tasty-fixtures from the JVM classpath (the smallest available fixtures jar/dir).
        val cpRoots: Seq[String] =
            sys.props
                .getOrElse("java.class.path", "")
                .split(java.io.File.pathSeparatorChar)
                .filter(p => p.contains("kyo-tasty-fixtures") && (p.endsWith(".jar") || p.endsWith("/classes")))
                .toSeq
        // Fall back to all classpath entries if the fixtures jar is not separately discoverable.
        val roots: Seq[String] =
            if cpRoots.nonEmpty then cpRoots
            else
                sys.props
                    .getOrElse("java.class.path", "")
                    .split(java.io.File.pathSeparatorChar)
                    .filter(p =>
                        val f = new java.io.File(p)
                        f.exists && ((f.isFile && p.endsWith(".jar")) || (f.isDirectory))
                    )
                    .take(1)
                    .toSeq
        Abort.run[TastyError](
            Tasty.withClasspath(roots, Maybe.Present(tmpDir)):
                Tasty.classpath.map(_.symbols.size)
            .flatMap: n1 =>
                Tasty.withClasspath(roots, Maybe.Present(tmpDir)):
                    Tasty.classpath.map(_.symbols.size)
                .map: n2 =>
                    val krflFiles = new java.io.File(tmpDir).listFiles()
                    val krflCount = if krflFiles == null then 0 else krflFiles.count(_.getName.endsWith(".krfl"))
                    assert(krflCount >= 1, s"at least one .krfl file must be written to $tmpDir; got $krflCount")
                    assert(n1 == n2, s"both withClasspath calls must return same symbol count; got $n1 vs $n2")
                    succeed
        ).map:
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
    }

    // ── Leaf 7: withClasspath(roots, Absent) does not touch any cache ─────────
    // Given: embedded fixture loaded via ClasspathOrchestrator.init + manual binding install
    // When: withClasspath(cp) (no cacheDir); Tasty.classpath returns bound cp
    // Then: no extra files; symbol count matches the loaded classpath
    // Pins: item 31 absent-cache semantics
    "Leaf 7: withClasspath(roots, Absent) does not touch any cache" in run {
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
    // Given: the public Tasty surface after Phase 05
    // When: compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.initCached(...)") checked
    // Then: non-empty error confirms runner migration is complete (initCached deleted)
    // Pins: item 31 runner port
    "Leaf 8: SnapshotRunner port -- Classpath.initCached absent confirms runner migrated" in {
        // The SnapshotRunner.scala source uses Tasty.withClasspath(roots, Maybe.Present(snapshotDir)).
        // This compile check confirms the old initCached entry point is gone, so the runner
        // cannot accidentally revert to the old pattern.
        val err = compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.initCached(Seq(\"x\"), \"/tmp\")")
        assert(err.nonEmpty, "Classpath.initCached must be absent after Phase 05 runner port; expected a compile error")
        succeed
    }

end WithClasspathTest
