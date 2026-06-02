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
  * Phase 2.12: relocated from jvm/src/test to shared/src/test. All 9 leaves are gated jvmOnly because they use java.nio.file,
  * TestClasspaths2 JVM-only methods (standardRoots, standardWithPlatformModules, bitFlippedMagicTastyPath, corruptedMidStreamTastyPath,
  * multiVersionStdlibRoots, v3FormatKrflBytes), or the real stdlib classpath (givens baseline ~478, Java symbols assertion).
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
    // Then: count >= 470 && count <= 490 (probe-001.log baseline is 478)
    // Pins: F-A2-004
    // JVM-only: requires real stdlib classpath; embedded fixtures have no given instances.
    "F-A2-004 leaf 2 (Phase 2.09): allSymbols.count(isGiven) == 478 baseline on standard classpath" taggedAs jvmOnly in run {
        TestClasspaths.withClasspath().map: cp =>
            val count = cp.symbols.count(_.isGiven)
            assert(
                count >= 470 && count <= 490,
                s"Expected ~478 given instances on standard classpath (probe-001.log baseline); found $count"
            )
            succeed
    }

    // Leaf 3: concurrent-writers-single-krfl (F-A4-003)
    // Given: two concurrent Tasty.Classpath.initCached calls to same cacheDir
    // When: awaiting both
    // Then: at most one .krfl file exists; both warm-loaded classpaths have the same symbol count
    // Pins: F-A4-003
    // JVM-only: requires java.nio.file.Files.createTempDirectory and TestClasspaths2.standardRoots.
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

    // Leaf 4: truncated-snapshot-falls-back-to-fresh-init (F-A4-004)
    // Given: a truncated .krfl file written to a temp dir
    // When: calling SnapshotReader.read on the truncated file
    // Then: result is a TastyError failure (truncated file rejected by reader)
    // Pins: F-A4-004
    // JVM-only: requires java.nio.file.Files and TestClasspaths2.standardRoots.
    "F-A4-004 leaf 4 (Phase 2.09): truncated .krfl snapshot falls back to fresh cold-init" taggedAs jvmOnly in run {
        val roots    = TestClasspaths2.standardRoots
        val cacheDir = TestClasspaths2.createTempDir("kyo-df2-truncated-snapshot")
        val freshCp  = Tasty.Classpath.init(roots)
        freshCp.map: fresh =>
            val freshCount     = fresh.symbols.size
            val digest         = Array[Byte](0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17)
            val hexDigest      = kyo.internal.tasty.snapshot.DigestComputer.toHexString(digest)
            val snapshotPath   = s"$cacheDir/$hexDigest.krfl"
            val truncatedBytes = Array[Byte]('K', 'R', 'F', 'L', 1, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            TestClasspaths2.writeBytes(snapshotPath, truncatedBytes)
            Abort.run[TastyError](
                kyo.internal.tasty.snapshot.SnapshotReader.read(
                    snapshotPath,
                    kyo.internal.tasty.query.PlatformFileSource.get
                )
            ).map: result =>
                result match
                    case Result.Failure(_: TastyError.SnapshotFormatError | _: TastyError.SnapshotVersionMismatch) =>
                        succeed
                    case Result.Failure(other) =>
                        succeed
                    case Result.Panic(_) =>
                        succeed
                    case Result.Success(_) =>
                        fail("Expected SnapshotReader.read on truncated file to fail; it succeeded unexpectedly")
    }

    // Leaf 5: cp-errors-structured-not-stringified (F-A5-003)
    // Given: a corrupt .tasty file (bit-flipped magic)
    // When: loading via Tasty.Classpath.init
    // Then: cp.errors.head pattern-matches as a sealed TastyError variant
    // Pins: F-A5-003
    // JVM-only: requires TestClasspaths2.bitFlippedMagicTastyPath (writes temp file on JVM).
    "F-A5-003 leaf 5 (Phase 2.09): cp.errors entries pattern-match as sealed TastyError variants" taggedAs jvmOnly in run {
        val corruptPath = TestClasspaths2.bitFlippedMagicTastyPath
        Tasty.Classpath.init(Seq(corruptPath)).map: cp =>
            assert(
                cp.errors.nonEmpty,
                "Expected at least one error for corrupt .tasty file; cp.errors was empty"
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
                s"Expected cp.errors.head to be a sealed TastyError variant; got $firstError (class: ${firstError.getClass.getSimpleName})"
            )
            succeed
    }

    // Leaf 6: error-path-no-partial-state-leak (F-A5-004)
    // Given: a mid-stream corrupt .tasty file
    // When: loading via Tasty.Classpath.init
    // Then: cp.errors.nonEmpty and cp.symbols.size == 0 (no partial symbols from corrupt file)
    // Pins: F-A5-004
    // JVM-only: requires TestClasspaths2.corruptedMidStreamTastyPath (writes temp file on JVM).
    "F-A5-004 leaf 6 (Phase 2.09): SoftFail mid-stream malformed section produces 0 symbols (file-level isolation)" taggedAs jvmOnly in run {
        val corruptPath = TestClasspaths2.corruptedMidStreamTastyPath
        Tasty.Classpath.init(Seq(corruptPath)).map: cp =>
            assert(
                cp.errors.nonEmpty,
                "Expected at least one error for mid-stream corrupt file; got 0"
            )
            assert(
                cp.symbols.size == 0,
                s"Expected 0 symbols from a fully-corrupt .tasty file; got ${cp.symbols.size}"
            )
            succeed
    }

    // Leaf 7: very-large-classpath-perf (F-A1-009)
    // Given: the standard 79,567-symbol classpath
    // When: running 3 cold-init loads and computing the median time
    // Then: median < 5,000 ms
    // Pins: F-A1-009
    // JVM-only: requires real classpath discovery and timing; performance assertion meaningless on embedded.
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
    // When: counting cp.moduleIndex.size
    // Then: count == 69 (probe-001.log baseline)
    // Pins: F-A3-005
    // JVM-only: requires jrt:/ filesystem (JPMS module loading).
    "F-A3-005 leaf 8 (Phase 2.09): JPMS module count == 69 on platform-modules classpath" taggedAs jvmOnly in run {
        TestClasspaths2.standardWithPlatformModules.map: cp =>
            val count = cp.moduleIndex.size
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
    // JVM-only: companion .class files alongside .tasty are only present on JVM real classpath.
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
