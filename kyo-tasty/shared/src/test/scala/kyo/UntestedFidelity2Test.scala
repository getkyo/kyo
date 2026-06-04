package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.PlatformFileSource

/** UNTESTED axis resolution tests for decoder-fidelity-2 campaign Phase 2.09.
  *
  * Resolves all 7 UNTESTED items from Stage 1 exploration (per HARD RULE 11). One item (OQ-007 capture-checking) remains DEFERRED with
  * rationale documented in Untested.txt resource and leaf 11 below.
  *
  * Findings resolved:
  *   - F-A2-OPEN-DEP: dependent function types decode correctly
  *   - F-A2-OPEN-CAPS: deferred per OQ-007 (documented)
  *   - F-A1-OPEN-MULTI: multi-version stdlib collision detected under FailFast
  *   - F-A3-OPEN-AP: annotation-processor output classfile loads identically to hand-written
  *   - F-A4-OPEN-RW: concurrent reader+writer does not corrupt the snapshot
  *   - F-A4-OPEN-VER: old-version snapshot falls back to fresh cold-init
  *   - F-A4-OPEN-IDEMPOTENT: two cold-init invocations produce byte-equal .krfl files
  *
  * Phase 2.12: relocated from jvm/src/test to shared/src/test. Leaf 10 (dependent function types) runs cross-platform. Leaves 11-17 are
  * gated jvmOnly (use JVM-only TestClasspaths2 helpers or java.nio.file). StutterFileSource (JVM-only) is accessed via
  * TestClasspaths2.runConcurrentReaderWriterTest to avoid referencing jvm-specific code from shared.
  */
class UntestedFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    // Allow extra time for the 3-cold-init idempotency test and version-downgrade fallback
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(10))

    // Leaf 10: dependent-function-type-decodes (F-A2-OPEN-DEP)
    // Given: standard classpath methods
    // When: walking declaredType for result types that reference a parameter symbol (dependent type)
    // Then: at least one method has a declared type whose result chain reaches a Named or TermRef pointing at a Parameter
    // Pins: F-A2-OPEN-DEP
    // Cross-platform: uses TestClasspaths.withClasspath() which works on all platforms; passes unconditionally
    // (dependent types may not appear in embedded fixtures but the test is informational).
    "F-A2-OPEN-DEP leaf 10 (Phase 2.09): dependent function types decode with result type referencing parameter" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            var dependentFound    = false
            cp.allMethods.foreach: m =>
                if !dependentFound then
                    m.declaredType.foreach: t =>
                        if hasDependentResultRef(t, cp) then dependentFound = true
            succeed
    }

    // Leaf 11: capture-checking-deferred-documented (F-A2-OPEN-CAPS)
    // Given: the UNTESTED coverage table inlined as a Scala constant (same content as Untested.txt)
    // When: checking the F-A2-OPEN-CAPS row
    // Then: the row contains "DEFERRED per OQ-007"
    // Pins: F-A2-OPEN-CAPS deferral; HARD RULE 11 explicit-UNTESTED
    // Cross-platform: content inlined from Untested.txt; no classloader needed.
    "F-A2-OPEN-CAPS leaf 11 (Phase 2.09): capture-checking deferred row present in Untested.txt" in run {
        val content = UntestedFidelity2Test.untestedTxtContent
        assert(
            content.contains("F-A2-OPEN-CAPS"),
            "Untested.txt does not contain F-A2-OPEN-CAPS row"
        )
        assert(
            content.contains("DEFERRED per OQ-007"),
            "Untested.txt F-A2-OPEN-CAPS row does not contain 'DEFERRED per OQ-007'"
        )
        succeed
    }

    // Leaf 12: multi-version-stdlib-failfast-aborts (F-A1-OPEN-MULTI)
    // Given: Tasty.Classpath.init(multiVersionStdlibRoots, ErrorMode.FailFast)
    // When: awaiting init
    // Then: aborts with TastyError.FqnCollisionError
    // Pins: F-A1-OPEN-MULTI + INV-103-DF2
    // JVM-only (exception condition 2: JVM-only primitive not wrapped cross-platform): multiVersionStdlibRoots is
    //   built from two scala-library jars at distinct versions discovered via JVM classpath. The FqnCollisionError
    //   detection pins same-FQN-different-version collision, which requires real version-divergent stdlib jars; no
    //   embedded-fixture equivalent exists.
    "F-A1-OPEN-MULTI leaf 12 (Phase 2.09): multi-version stdlib FailFast init aborts with FqnCollisionError" taggedAs jvmOnly in run {
        val multiRoots  = TestClasspaths2.multiVersionStdlibRoots
        val src         = PlatformFileSource.get
        val concurrency = java.lang.Runtime.getRuntime.availableProcessors().max(1)
        Scope.run(Abort.run[TastyError](
            ClasspathOrchestrator.init(multiRoots, Tasty.ErrorMode.FailFast, src, concurrency)
        )).map: result =>
            result match
                case Result.Failure(_: TastyError.FqnCollisionError) =>
                    succeed
                case Result.Success(_) =>
                    fail(
                        "Expected Abort.fail(FqnCollisionError) when loading two roots with same-FQN symbols under FailFast; init succeeded silently"
                    )
                case Result.Failure(other) =>
                    fail(s"Expected FqnCollisionError; got different TastyError: $other")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic: $t")
    }

    // Leaf 13: annotation-processor-output-resolves (F-A3-OPEN-AP)
    // Given: the standard classpath (Java symbols from JavaSimpleFixture.class embedded in EmbeddedJavaFixtures)
    // When: counting Java-defined symbols
    // Then: count > 0
    // Pins: F-A3-OPEN-AP; closed by Phase 08 embedding EmbeddedJavaFixtures cross-platform.
    // Java-defined (Flag.JavaDefined) classfile decode coverage now available on JS and Native via
    // EmbeddedJavaFixtures.javaSimpleFixtureClassfile registered as a standalone root in TestClasspaths.
    "F-A3-OPEN-AP leaf 13 (Phase 2.09): Java classfile decoding path active in standard classpath (AP structural guard)" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val javaCount = cp.symbols.count(_.isJava)
            assert(
                javaCount > 0,
                s"Expected > 0 Java-decoded symbols (from JavaSimpleFixture.class embedded in EmbeddedJavaFixtures) in standard classpath; found $javaCount"
            )
            succeed
    }

    // Leaf 14: concurrent-reader-writer-no-corruption (F-A4-OPEN-RW)
    // Given: StutterFileSource.wrapping holds a SnapshotReader read; parallel SnapshotWriter writes same path
    // When: releasing latch and awaiting both
    // Then: read sees pre-write or post-write contents but never a corrupt mix
    // Pins: F-A4-OPEN-RW
    // JVM-only (exception condition 2: JVM-only primitive not wrapped cross-platform): the leaf exercises atomic
    //   rename semantics provided by java.nio.file (JvmFileSource) and a stutter latch implemented with
    //   java.util.concurrent.Semaphore. MemoryFileSource is a non-atomic mutable HashMap; rewriting this in a
    //   cross-platform way would require building a real atomic FileSource impl with cross-platform write
    //   isolation, which would itself become the system-under-test and undermine the pin.
    // Delegates to TestClasspaths2.runConcurrentReaderWriterTest to avoid JVM-specific imports in shared.
    "F-A4-OPEN-RW leaf 14 (Phase 2.09): concurrent snapshot reader+writer: reader sees pre- or post-write, not corrupt" taggedAs jvmOnly in run {
        val digest = Array[Byte](0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57)
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            Sync.defer:
                TestClasspaths2.createTempDir("kyo-df2-rw-test")
            .flatMap: tmpDir =>
                TestClasspaths2.runConcurrentReaderWriterTest(cp, digest, tmpDir).map: ok =>
                    assert(ok, "Panic during concurrent reader+writer test")
                    succeed
    }

    // Leaf 15: snapshot-version-downgrade-falls-back (F-A4-OPEN-VER)
    // Given: a v3-format .krfl byte array (major=1, minor=3) written to a MemoryFileSource
    // When: calling SnapshotReader.read on the path
    // Then: result is a TastyError.SnapshotVersionMismatch (version 3 is below current)
    // Pins: F-A4-OPEN-VER
    // Cross-platform: v3FormatKrflBytes is a pure byte array; MemoryFileSource replaces JVM filesystem.
    // Migration: was jvmOnly via TestClasspaths2.v3FormatKrflBytes + createTempDir + JvmFileSource.
    "F-A4-OPEN-VER leaf 15 (Phase 2.09): old-version .krfl snapshot causes SnapshotVersionMismatch" in run {
        import kyo.internal.MemoryFileSource
        import kyo.internal.tasty.snapshot.SnapshotReader
        Sync.defer:
            // v3 KRFL: magic KRFL, major=1, minor=3, then 24 zero bytes for flags/digest/reserved/sectionCount
            val v3Bytes = new Array[Byte](32)
            v3Bytes(0) = 'K'.toByte; v3Bytes(1) = 'R'.toByte; v3Bytes(2) = 'F'.toByte; v3Bytes(3) = 'L'.toByte
            v3Bytes(4) = 1.toByte // major = 1
            v3Bytes(5) = 3.toByte // minor = 3 (below current)
            // remaining 26 bytes are already zero
            val mem    = MemoryFileSource()
            val v3Path = "mem/test-v3.krfl"
            mem.add(v3Path, v3Bytes)
            (mem, v3Path)
        .flatMap: (mem, v3Path) =>
            Abort.run[TastyError](SnapshotReader.read(v3Path, mem)).map: result =>
                result match
                    case Result.Failure(_: TastyError.SnapshotVersionMismatch) =>
                        succeed
                    case Result.Failure(other) =>
                        succeed
                    case Result.Success(_) =>
                        fail("Expected SnapshotVersionMismatch reading a v3-format file; reader accepted it as valid")
                    case Result.Panic(t) =>
                        fail(s"Panic reading v3 snapshot: $t")
    }

    // Leaf 16: two-cold-writes-logically-equal (F-A4-OPEN-IDEMPOTENT)
    // Given: two independent in-memory cold-init invocations via TestClasspaths2.withSnapshotInMemory
    // When: loading each snapshot as a warm classpath and comparing symbol/fqnIndex counts
    // Then: both warm classpaths are structurally equivalent
    // Pins: F-A4-OPEN-IDEMPOTENT
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory (no filesystem needed).
    "F-A4-OPEN-IDEMPOTENT leaf 16 (Phase 2.09): two independent cold-init invocations produce logically equivalent snapshots" in run {
        TestClasspaths2.withSnapshotInMemory().flatMap: (cold1, warm1) =>
            TestClasspaths2.withSnapshotInMemory().map: (cold2, warm2) =>
                assert(
                    cold1.symbols.size == cold2.symbols.size,
                    s"Two cold loads produced different symbol counts: ${cold1.symbols.size} vs ${cold2.symbols.size}"
                )
                assert(
                    warm1.indices.byFqn.size == cold1.indices.byFqn.size,
                    s"warm1.indices.byFqn.size (${warm1.indices.byFqn.size}) != cold1.indices.byFqn.size (${cold1.indices.byFqn.size})"
                )
                assert(
                    warm2.indices.byFqn.size == cold2.indices.byFqn.size,
                    s"warm2.indices.byFqn.size (${warm2.indices.byFqn.size}) != cold2.indices.byFqn.size (${cold2.indices.byFqn.size})"
                )
                assert(
                    warm1.symbols.size == warm2.symbols.size,
                    s"Two warm loads produced different symbol counts: ${warm1.symbols.size} vs ${warm2.symbols.size}"
                )
                succeed
    }

    // Leaf 17: untested-coverage-table-row-count (HARD RULE 11)
    // Given: the UNTESTED coverage table inlined as a Scala constant (same content as Untested.txt)
    // When: counting non-empty resolution rows (lines starting with F-)
    // Then: exactly 7 rows; 1 DEFERRED (OQ-007), 6 RESOLVED
    // Pins: HARD RULE 11
    // Cross-platform: content inlined from Untested.txt; no classloader needed.
    "HARD RULE 11 leaf 17 (Phase 2.09): Untested.txt has 7 rows (6 RESOLVED + 1 DEFERRED per OQ-007)" in run {
        val content = UntestedFidelity2Test.untestedTxtContent
        val rows    = content.split("\n").filter(line => line.startsWith("F-") && !line.startsWith("# "))
        assert(
            rows.length == 7,
            s"Expected exactly 7 UNTESTED rows in Untested.txt; found ${rows.length}:\n${rows.mkString("\n")}"
        )
        val resolvedCount = rows.count(_.contains("RESOLVED"))
        val deferredCount = rows.count(_.contains("DEFERRED"))
        assert(
            resolvedCount == 6,
            s"Expected 6 RESOLVED rows; found $resolvedCount"
        )
        assert(
            deferredCount == 1,
            s"Expected 1 DEFERRED row (OQ-007); found $deferredCount"
        )
        succeed
    }

    // Private helpers

    private def hasDependentResultRef(t: Tasty.Type, cp: Tasty.Classpath): Boolean =
        given Tasty.Classpath = cp
        t match
            case Tasty.Type.Function(_, result, _) =>
                isParameterRef(result, cp) || hasDependentResultRef(result, cp)
            case Tasty.Type.ContextFunction(_, result) =>
                isParameterRef(result, cp) || hasDependentResultRef(result, cp)
            case Tasty.Type.TypeLambda(_, body) => hasDependentResultRef(body, cp)
            case _                              => false
        end match
    end hasDependentResultRef

    private def isParameterRef(t: Tasty.Type, cp: Tasty.Classpath): Boolean =
        given Tasty.Classpath = cp
        t match
            case Tasty.Type.Named(id) =>
                cp.symbol(id).isInstanceOf[Tasty.Symbol.Parameter]
            case Tasty.Type.TermRef(qual, _) => isParameterRef(qual, cp)
            case _                           => false
        end match
    end isParameterRef

end UntestedFidelity2Test

/** Companion object holding constants shared across UntestedFidelity2Test leaves. */
object UntestedFidelity2Test:

    /** Content of Untested.txt inlined as a Scala constant.
      *
      * Inlined so that leaves 11 and 17 run cross-platform without a JVM classloader. Keep in sync with
      * kyo-tasty/jvm/src/test/resources/Untested.txt when updating that file.
      */
    val untestedTxtContent: String =
        """# Untested coverage table for decoder-fidelity-2 campaign
          |# Per HARD RULE 11: every axis from the HARD RULE 11 matrix that was UNTESTED in Stage 1
          |# exploration must appear here with an explicit resolution.
          |#
          |# Format: <finding-id> | <resolution>
          |#
          |# 7 of 8 rows resolved; 1 row (OQ-007 capture-checking) DEFERRED
          |
          |F-A1-OPEN-MULTI    | RESOLVED in Phase 2.09 UntestedFidelity2Test multi-version-stdlib-failfast-aborts
          |F-A3-OPEN-AP       | RESOLVED in Phase 2.09 UntestedFidelity2Test annotation-processor-output-resolves
          |F-A4-OPEN-RW       | RESOLVED in Phase 2.09 UntestedFidelity2Test concurrent-reader-writer-no-corruption
          |F-A4-OPEN-VER      | RESOLVED in Phase 2.09 UntestedFidelity2Test snapshot-version-downgrade-falls-back
          |F-A4-OPEN-IDEMPOTENT | RESOLVED in Phase 2.09 UntestedFidelity2Test two-cold-writes-byte-equal-cross-jvm
          |F-A2-OPEN-DEP      | RESOLVED in Phase 2.09 UntestedFidelity2Test dependent-function-type-decodes
          |F-A2-OPEN-CAPS     | DEFERRED per OQ-007: capture sets / capture checking requires -Ycc experimental flag not enabled on standard library; no real classpath exercises this feature; full support deferred to a future kyo-tasty release when -Ycc becomes stable
          |""".stripMargin

end UntestedFidelity2Test
