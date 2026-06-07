package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.query.TastyState

/** Confirmation pin tests for decoder-fidelity-2 campaign.
  *
  * Pins the baseline behavior of findings addressed in earlier phases plus several cross-phase confirmation leaves:
  *   -  : empty classpath (0 symbols, 0 errors)
  *   -  : givens enumeration baseline (478 givens from probe-001.log)
  *   -  : concurrent writers to same snapshot cacheDir (one .krfl, both warm-loads equivalent)
  *   -  : truncated snapshot falls back to fresh cold-init
  *   -  : cp.errors pattern-matches to sealed TastyError variant
  *   -  : error path does not leak partial symbols past malformed offset
  *   -  : large classpath perf guard (cold-init < 5,000 ms median on 3 runs)
  *   -  : JPMS module count == 69 (confirmation from)
  *   - java-only-jar: Java classfile-only directory loads correctly
  *
  * Invariants consumed: all prior campaign invariants.
  *
  * relocated from jvm/src/test to shared/src/test. All 9 original leaves are gated jvmOnly because they use java.nio.file,
  * TestClasspaths2 JVM-only methods (standardRoots, standardWithPlatformModules, bitFlippedMagicTastyPath, corruptedMidStreamTastyPath,
  * multiVersionStdlibRoots, v3FormatKrflBytes), or the real stdlib classpath (givens baseline ~478, Java symbols assertion).
  *
  * adds 3 cross-platform in-memory companions for  ,  ,   using MemoryFileSource and
  * ClasspathOrchestrator.init directly. These do not require the JVM filesystem.
  */
class ConfirmationFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    // Allow extra time: large-classpath perf leaf runs 3 cold-inits
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(10))

    // Leaf 1: empty-classpath-zero-symbols-zero-errors
    // Given: Tasty.Classpath.init(Seq.empty)
    // When: checking symbols and errors
    // Then: 0 symbols, 0 errors
    // Cross-platform: Tasty.Classpath.init(Seq.empty) works on all platforms (no filesystem needed).
    "empty classpath init returns 0 symbols, 0 errors" in {
        Tasty.withClasspath(Seq.empty)(Tasty.classpath).map: cp =>
            assert(cp.symbols.size == 0, s"Expected 0 symbols on empty classpath; got ${cp.symbols.size}")
            assert(cp.errors.size == 0, s"Expected 0 errors on empty classpath; got ${cp.errors.size}")
            succeed
    }

    // Leaf 2: givens-enumeration-baseline
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: counting allSymbols.count(isGiven)
    // Then: count is within +/-15 of the 570 baseline (re-measured 2026-06-05 after Cat 18
    //       fill-in; Cat 18 added derives Schema to RecordComponent, ParamGroup, EnclosingMethod, and
    //       all four Module sub-records (Requires, Exports, Opens, Provides), plus two explicit
    //       canEqual givens for Annotation and Annotation.Value; these 21 new given symbols raised the
    //       count from 549 to 570. The 549 baseline was set after; the 549-based assertion
    //       is superseded by this measurement. Prior 493 baseline was measured on broken code
    //       that under-counted due to placeholder symbol deduplication in IdentityHashMap migration).
    // JVM-only (exception condition 2: JVM-only primitive not wrapped cross-platform): the assertion pins the
    //   scala-library given count. Scala-library cannot be loaded as a TASTy classpath on JS/Native;
    //   the embedded fixture set contains no `given` instances, so the count would always be 0 there.
    "allSymbols.count(isGiven) ~= 493 baseline on standard classpath".onlyJvm in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val count = cp.symbols.count(_.isGiven)
            assert(
                count >= 555 && count <= 585,
                s"Expected ~570 given instances on standard classpath (re-measured 2026-06-05); found $count"
            )
            succeed
    }

    // Leaf 3: concurrent-writers-single-krfl
    // Given: two concurrent Tasty.Classpath.initCached calls to same cacheDir
    // When: awaiting both
    // Then: at most one .krfl file exists; both warm-loaded classpaths have the same symbol count
    // JVM-only (exception condition 2: JVM-only primitive not wrapped cross-platform): Tasty.Classpath.initCached
    //   takes a real filesystem cacheDir and uses java.nio.file atomic-rename for one-writer-wins. MemoryFileSource
    //   has no atomic-rename or concurrent-write semantics, so it cannot exercise the JVM rename-collision path
    //   that this leaf pins.
    "two concurrent cold-init writers to same cacheDir produce one .krfl file".onlyJvm in {
        val cacheDir = TestClasspaths2.createTempDir("kyo-df2-concurrent-writers")
        val roots    = TestClasspaths2.standardRoots
        Async.zip(
            Tasty.withClasspath(roots, Maybe.Present(cacheDir))(Tasty.classpath),
            Tasty.withClasspath(roots, Maybe.Present(cacheDir))(Tasty.classpath)
        ).map: (cp1, cp2) =>
            val krflFiles = TestClasspaths2.listFilesWithSuffix(cacheDir, ".krfl")
            assert(
                krflFiles.length == 1,
                s"Expected exactly 1 .krfl file after concurrent writes; found ${krflFiles.length}"
            )
            assert(
                cp1.symbols.size == cp2.symbols.size,
                s"Concurrent-written snapshots produce different symbol counts: ${cp1.symbols.size} vs ${cp2.symbols.size}"
            )
            assert(
                cp1.symbols.size > 0,
                s"Expected > 0 symbols after concurrent cold-init; got ${cp1.symbols.size}"
            )
            succeed
    }

    // truncated-snapshot-rejected-via-MemoryFileSource
    // Given: a truncated KRFL byte array written to a MemoryFileSource
    // When: calling SnapshotReader.read on the truncated path
    // Then: result is a TastyError failure (truncated file rejected)
    // Cross-platform: uses MemoryFileSource; no filesystem needed.
    "truncated .krfl snapshot fails with TastyError via in-memory reader" in {
        import kyo.internal.MemoryFileSource
        import kyo.internal.tasty.snapshot.SnapshotReader
        Sync.defer:
            val mem            = MemoryFileSource()
            val truncatedBytes = Array[Byte]('K', 'R', 'F', 'L', 1, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            val path           = "mem/truncated.krfl"
            mem.add(path, truncatedBytes)
            (mem, path)
        .flatMap: (mem, path) =>
            Abort.run[TastyError](SnapshotReader.read(path, mem)).map: result =>
                result match
                    case Result.Failure(_) =>
                        succeed
                    case Result.Success(_) =>
                        fail("Expected SnapshotReader.read on truncated KRFL bytes to fail; it succeeded unexpectedly")
                    case Result.Panic(t) =>
                        throw t
    }

    // bit-flipped-magic-produces-structured-error
    // Given: a .tasty file with bit-flipped magic byte constructed in memory
    // When: loading via Tasty.Classpath.init with the MemoryFileSource root
    // Then: cp.errors.head pattern-matches as a sealed TastyError variant
    // Cross-platform: uses MemoryFileSource; no filesystem needed.
    "cp.errors entries pattern-match as sealed TastyError variants via in-memory source" in {
        import kyo.internal.MemoryFileSource
        import kyo.internal.tasty.query.ClasspathOrchestrator
        Sync.defer:
            val goodMagic = kyo.fixtures.Embedded.plainClassTasty.clone()
            goodMagic(0) = (goodMagic(0) ^ 0xff.toByte).toByte // flip first magic byte
            val mem = MemoryFileSource()
            mem.add("corrupt-root/Bad.tasty", goodMagic)
            mem
        .flatMap: mem =>
            ClasspathOrchestrator.init(Seq("corrupt-root"), Tasty.ErrorMode.SoftFail, mem, 1).map: cp =>
                assert(
                    cp.errors.nonEmpty,
                    "Expected at least one error for bit-flipped .tasty file; cp.errors was empty"
                )
                val firstError = cp.errors(0)
                val matchedVariant = firstError match
                    case _: TastyError.CorruptedFile        => true
                    case _: TastyError.FileNotFound         => true
                    case _: TastyError.MalformedSection     => true
                    case _: TastyError.ClassfileFormatError => true
                    case _: TastyError.SnapshotFormatError  => true
                    case _                                  => false
                assert(
                    matchedVariant,
                    s"Expected cp.errors.head to be a sealed TastyError variant; got $firstError"
                )
                succeed
    }

    // mid-stream-truncated-produces-0-symbols
    // Given: a .tasty file truncated mid-stream (valid magic + version header, then truncated) in memory
    // When: loading via ClasspathOrchestrator.init with SoftFail
    // Then: cp.errors.nonEmpty and cp.symbols.size == 0 (file-level isolation, no partial symbols)
    // Cross-platform: uses MemoryFileSource; no filesystem needed.
    "SoftFail mid-stream malformed section produces 0 symbols via in-memory source" in {
        import kyo.internal.MemoryFileSource
        import kyo.internal.tasty.query.ClasspathOrchestrator
        Sync.defer:
            // Take a valid TASTy file and truncate it mid-stream (keep first 20 bytes: magic+version, then cut)
            val validTasty = kyo.fixtures.Embedded.plainClassTasty
            val truncated  = validTasty.take(20) // valid header then EOF
            val mem        = MemoryFileSource()
            mem.add("trunc-root/Trunc.tasty", truncated)
            mem
        .flatMap: mem =>
            ClasspathOrchestrator.init(Seq("trunc-root"), Tasty.ErrorMode.SoftFail, mem, 1).map: cp =>
                assert(
                    cp.errors.nonEmpty,
                    "Expected at least one error for mid-stream truncated .tasty file; got 0"
                )
                assert(
                    cp.symbols.size == 0,
                    s"Expected 0 symbols from a truncated .tasty file; got ${cp.symbols.size}"
                )
                succeed
    }

    // Leaf 7: very-large-classpath-perf
    // Given: the standard 79,567-symbol classpath
    // When: running 3 cold-init loads and computing the median time
    // Then: median < 5,000 ms
    // JVM-only (exception condition 3: test asserts JVM-specific behavior): the assertion pins cold-init wall-time
    //   on a 79,567-symbol stdlib classpath, a perf characteristic of the JVM real-classpath loader. The embedded
    //   fixture set holds ~150 symbols; a <5000ms bound there is vacuous (typical load is <500ms).
    "standard 81,569-symbol classpath cold-init median < 5,000 ms".onlyJvm in {
        val roots = TestClasspaths2.standardRoots
        def timedLoad: Long < (Async & Abort[TastyError]) =
            val start = java.lang.System.nanoTime()
            TestClasspaths.withClasspath(roots)(Tasty.classpath).map: cp =>
                val elapsed = (java.lang.System.nanoTime() - start) / 1_000_000L
                assert(cp.symbols.size >= 81000, s"Expected >= 81,000 symbols (RI-008 measured 81569); got ${cp.symbols.size}")
                elapsed
        end timedLoad
        timedLoad.flatMap: t1 =>
            timedLoad.flatMap: t2 =>
                timedLoad.map: t3 =>
                    val times  = Array(t1, t2, t3).sorted
                    val median = times(1)
                    assert(
                        median < 5000,
                        s"Expected cold-init median < 5,000 ms on standard classpath; got ${median} ms (runs: ${t1}, ${t2}, ${t3})"
                    )
                    succeed
    }

    // Leaf 8: jpms-modules-count-69
    // Given: the platform-modules classpath (jrt:/)
    // When: counting cp.indices.modulesIndex.size
    // Then: count == 69 (probe-001.log baseline)
    // JVM-only (exception condition 2: JVM-only primitive not wrapped cross-platform): jrt:/ JPMS module filesystem
    //   is a JVM-only loader scheme; JS/Native have no equivalent module system to enumerate.
    "JPMS module count == 69 on platform-modules classpath".onlyJvm in {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            val count = cp.indices.modulesIndex.size
            assert(
                count == 69,
                s"Expected exactly 69 JPMS modules (probe-001.log baseline); got $count"
            )
            succeed
    }

    // Leaf 9: java-symbols-present-in-standard-classpath (F-A1-OPEN: Java symbols confirmation)
    // Given: the standard classpath loaded via TestClasspaths.withClasspath (includes EmbeddedJavaFixtures.javaSimpleFixtureClassfile)
    // When: counting cp.symbols.count(_.isJava)
    // Then: count > 0 (Java symbols from JavaSimpleFixture.class embedded cross-platform)
    "F-A1-OPEN leaf 9 : Java-defined symbols present in standard classpath (java interop guard)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val javaCount = cp.symbols.count(_.isJava)
            assert(
                javaCount > 0,
                s"Expected > 0 Java-defined symbols in standard classpath (from JavaSimpleFixture.class embedded in EmbeddedJavaFixtures); found $javaCount"
            )
            succeed
    }

    // Leaf 10: round-trip findClass on embedded Java fixture via MemoryFileSource
    // Given: a MemoryFileSource with JavaSimpleFixture.class registered as a standalone root
    // When: Tasty.findClass("kyo.fixtures.JavaSimpleFixture")
    // Then: Maybe.Present(c) where c.isJava == true
    "findClass(kyo.fixtures.JavaSimpleFixture) returns Present with isJava via MemoryFileSource" in {
        import kyo.internal.MemoryFileSource
        import kyo.internal.tasty.query.Binding
        import kyo.internal.tasty.query.ClasspathOrchestrator
        import kyo.internal.tasty.query.DecodeContext
        val src = MemoryFileSource()
        src.add("kyo/fixtures/JavaSimpleFixture.class", kyo.fixtures.EmbeddedJavaFixtures.javaSimpleFixtureClassfile)
        Scope.run:
            ClasspathOrchestrator.init(Seq("kyo/fixtures/JavaSimpleFixture.class"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
                val binding = Binding(cp, Maybe.Present(DecodeContext.fresh()))
                TastyState.bindingLocal.let(Maybe.Present(binding)):
                    Tasty.findClass("kyo.fixtures.JavaSimpleFixture").map:
                        case Maybe.Present(c) =>
                            assert(c.isJava, "JavaSimpleFixture must have isJava (Flag.JavaDefined set by ClassfileUnpickler)")
                            succeed
                        case Maybe.Absent =>
                            fail("kyo.fixtures.JavaSimpleFixture not found; standalone .class root was not discovered")
    }

end ConfirmationFidelity2Test
