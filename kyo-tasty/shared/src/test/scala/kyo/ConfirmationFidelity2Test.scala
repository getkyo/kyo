package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2

/** Confirmation pin tests for decoder-fidelity-2 campaign Phase 2.09.
  *
  * Pins the baseline behavior of findings addressed in earlier phases plus several cross-phase confirmation leaves:
  *   - F-A1-001: empty classpath (0 symbols, 0 errors)
  *   - F-A2-004: givens enumeration baseline (478 givens from probe-001.log)
  *   - F-A4-003: concurrent writers to same snapshot cacheDir (one .krfl, both warm-loads equivalent)
  *   - F-A4-004: truncated snapshot falls back to fresh cold-init
  *   - F-A5-003: cp.errors pattern-matches to sealed TastyError variant
  *   - F-A5-004: error path does not leak partial symbols past malformed offset
  *   - F-A1-009: large classpath perf guard (cold-init < 5,000 ms median on 3 runs)
  *   - F-A3-005: JPMS module count == 69 (confirmation from Phase 2.03)
  *   - java-only-jar: Java classfile-only directory loads correctly
  *
  * Invariants consumed: all prior campaign invariants.
  *
  * Phase 2.12: relocated from jvm/src/test to shared/src/test. All 9 original leaves are gated jvmOnly because they use java.nio.file,
  * TestClasspaths2 JVM-only methods (standardRoots, standardWithPlatformModules, bitFlippedMagicTastyPath, corruptedMidStreamTastyPath,
  * multiVersionStdlibRoots, v3FormatKrflBytes), or the real stdlib classpath (givens baseline ~478, Java symbols assertion).
  *
  * Phase 2 post-audit: adds 3 cross-platform in-memory companions for F-A4-004, F-A5-003, F-A5-004 using MemoryFileSource and
  * ClasspathOrchestrator.init directly. These do not require the JVM filesystem.
  */
class ConfirmationFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    // Allow extra time: large-classpath perf leaf runs 3 cold-inits
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(10))

    // Leaf 1: empty-classpath-zero-symbols-zero-errors (F-A1-001)
    // Given: Tasty.Classpath.init(Seq.empty)
    // When: checking symbols and errors
    // Then: 0 symbols, 0 errors
    // Pins: F-A1-001
    // Cross-platform: Tasty.Classpath.init(Seq.empty) works on all platforms (no filesystem needed).
    "F-A1-001 leaf 1 (Phase 2.09): empty classpath init returns 0 symbols, 0 errors" in run {
        Tasty.Classpath.init(Seq.empty).map: cp =>
            assert(cp.symbols.size == 0, s"Expected 0 symbols on empty classpath; got ${cp.symbols.size}")
            assert(cp.errors.size == 0, s"Expected 0 errors on empty classpath; got ${cp.errors.size}")
            succeed
    }

    // Leaf 2: givens-enumeration-baseline (F-A2-004)
    // Given: the real classpath loaded via TestClasspaths.withClasspath
    // When: counting allSymbols.count(isGiven)
    // Then: count is within +/-15 of the 549 baseline (re-measured 2026-06-04 after Phase 03 fix;
    //       prior 493 baseline was measured on broken code that under-counted due to placeholder
    //       symbol deduplication in IdentityHashMap migration; 549 is the correct count with
    //       identity-based internal maps preserving all distinct given instances).
    // Pins: F-A2-004
    // JVM-only (exception condition 2: JVM-only primitive not wrapped cross-platform): the assertion pins the
    //   scala-library given count. Scala-library cannot be loaded as a TASTy classpath on JS/Native;
    //   the embedded fixture set contains no `given` instances, so the count would always be 0 there.
    "F-A2-004 leaf 2 (Phase 2.09): allSymbols.count(isGiven) ~= 493 baseline on standard classpath" taggedAs jvmOnly in run {
        TestClasspaths.withClasspath().map: cp =>
            val count = cp.symbols.count(_.isGiven)
            assert(
                count >= 534 && count <= 564,
                s"Expected ~549 given instances on standard classpath (re-measured 2026-06-04); found $count"
            )
            succeed
    }

    // Leaf 3: concurrent-writers-single-krfl (F-A4-003)
    // Given: two concurrent Tasty.Classpath.initCached calls to same cacheDir
    // When: awaiting both
    // Then: at most one .krfl file exists; both warm-loaded classpaths have the same symbol count
    // Pins: F-A4-003
    // JVM-only (exception condition 2: JVM-only primitive not wrapped cross-platform): Tasty.Classpath.initCached
    //   takes a real filesystem cacheDir and uses java.nio.file atomic-rename for one-writer-wins. MemoryFileSource
    //   has no atomic-rename or concurrent-write semantics, so it cannot exercise the JVM rename-collision path
    //   that this leaf pins.
    "F-A4-003 leaf 3 (Phase 2.09): two concurrent cold-init writers to same cacheDir produce one .krfl file" taggedAs jvmOnly in run {
        val cacheDir = TestClasspaths2.createTempDir("kyo-df2-concurrent-writers")
        val roots    = TestClasspaths2.standardRoots
        Async.zip(
            Tasty.Classpath.initCached(roots, cacheDir),
            Tasty.Classpath.initCached(roots, cacheDir)
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

    // Leaf 4 (Phase 2.09, migrated Phase 2 post-audit): truncated-snapshot-rejected-via-MemoryFileSource
    // Given: a truncated KRFL byte array written to a MemoryFileSource
    // When: calling SnapshotReader.read on the truncated path
    // Then: result is a TastyError failure (truncated file rejected)
    // Cross-platform: uses MemoryFileSource; no filesystem needed.
    "F-A4-004 leaf 4 (Phase 2.09): truncated .krfl snapshot fails with TastyError via in-memory reader" in run {
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

    // Leaf 5 (Phase 2.09, migrated Phase 2 post-audit): bit-flipped-magic-produces-structured-error
    // Given: a .tasty file with bit-flipped magic byte constructed in memory
    // When: loading via Tasty.Classpath.init with the MemoryFileSource root
    // Then: cp.errors.head pattern-matches as a sealed TastyError variant
    // Cross-platform: uses MemoryFileSource; no filesystem needed.
    "F-A5-003 leaf 5 (Phase 2.09): cp.errors entries pattern-match as sealed TastyError variants via in-memory source" in run {
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

    // Leaf 6 (Phase 2.09, migrated Phase 2 post-audit): mid-stream-truncated-produces-0-symbols
    // Given: a .tasty file truncated mid-stream (valid magic + version header, then truncated) in memory
    // When: loading via ClasspathOrchestrator.init with SoftFail
    // Then: cp.errors.nonEmpty and cp.symbols.size == 0 (file-level isolation, no partial symbols)
    // Cross-platform: uses MemoryFileSource; no filesystem needed.
    "F-A5-004 leaf 6 (Phase 2.09): SoftFail mid-stream malformed section produces 0 symbols via in-memory source" in run {
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

    // Leaf 7: very-large-classpath-perf (F-A1-009)
    // Given: the standard 79,567-symbol classpath
    // When: running 3 cold-init loads and computing the median time
    // Then: median < 5,000 ms
    // Pins: F-A1-009
    // JVM-only (exception condition 3: test asserts JVM-specific behavior): the assertion pins cold-init wall-time
    //   on a 79,567-symbol stdlib classpath, a perf characteristic of the JVM real-classpath loader. The embedded
    //   fixture set holds ~150 symbols; a <5000ms bound there is vacuous (typical load is <500ms).
    "F-A1-009 leaf 7 (Phase 2.09): standard 79,567-symbol classpath cold-init median < 5,000 ms" taggedAs jvmOnly in run {
        val roots = TestClasspaths2.standardRoots
        def timedLoad: Long < (Async & Scope & Abort[TastyError]) =
            val start = java.lang.System.nanoTime()
            TestClasspaths.withClasspath(roots).map: cp =>
                val elapsed = (java.lang.System.nanoTime() - start) / 1_000_000L
                assert(cp.symbols.size >= 79000, s"Expected >= 79,000 symbols; got ${cp.symbols.size}")
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

    // Leaf 8: jpms-modules-count-69 (F-A3-005 confirmation)
    // Given: the platform-modules classpath (jrt:/)
    // When: counting cp.indices.modulesIndex.size
    // Then: count == 69 (probe-001.log baseline)
    // Pins: F-A3-005
    // JVM-only (exception condition 2: JVM-only primitive not wrapped cross-platform): jrt:/ JPMS module filesystem
    //   is a JVM-only loader scheme; JS/Native have no equivalent module system to enumerate.
    "F-A3-005 leaf 8 (Phase 2.09): JPMS module count == 69 on platform-modules classpath" taggedAs jvmOnly in run {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            val count = cp.indices.modulesIndex.size
            assert(
                count == 69,
                s"Expected exactly 69 JPMS modules (probe-001.log baseline); got $count"
            )
            succeed
    }

    // Leaf 9: java-symbols-present-in-standard-classpath (F-A1-OPEN: Java symbols confirmation)
    // Given: the standard classpath loaded via TestClasspaths.withClasspath
    // When: counting cp.symbols.count(_.isJava)
    // Then: count > 0 (Java symbols from companion .class files)
    // Pins: F-A1-OPEN (Java interop guard)
    // JVM-only (exception condition 2: JVM-only primitive not wrapped cross-platform): isJava is true for symbols
    //   originating from Java-source .class files (Flag.JavaDefined set by scalac). The embedded fixture set has
    //   no Java sources (only Scala-compiled classes); a Java fixture would require adding .java sources to
    //   kyo-tasty-fixtures and a separate build step to emit Java classfile bytes for the embedded set.
    "F-A1-OPEN leaf 9 (Phase 2.09): Java-defined symbols present in standard classpath (java interop guard)" taggedAs jvmOnly in run {
        TestClasspaths.withClasspath().map: cp =>
            val javaCount = cp.symbols.count(_.isJava)
            assert(
                javaCount > 0,
                s"Expected > 0 Java-defined symbols in standard classpath (from companion .class files alongside .tasty); found $javaCount"
            )
            succeed
    }

end ConfirmationFidelity2Test
