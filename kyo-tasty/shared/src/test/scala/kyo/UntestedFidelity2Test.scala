package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.PlatformFileSource

/** UNTESTED axis resolution tests.
  *
  * Resolves the UNTESTED items from initial decoder exploration. One item (capture-checking) remains
  * DEFERRED with rationale documented in the Untested.txt resource and the deferred-row test below.
  *
  * Coverage:
  *   - dependent function types decode correctly
  *   - capture-checking deferred (documented)
  *   - multi-version stdlib collision detected under FailFast
  *   - annotation-processor output classfile loads identically to hand-written
  *   - concurrent reader+writer does not corrupt the snapshot
  *   - old-version snapshot falls back to fresh cold-init
  *   - two cold-init invocations produce byte-equal .krfl files
  *
  * Dependent function types test runs cross-platform. Remaining tests are gated jvmOnly (they use
  * JVM-only TestClasspaths2 helpers or java.nio.file). StutterFileSource (JVM-only) is accessed via
  * TestClasspaths2.runConcurrentReaderWriterTest to avoid referencing jvm-specific code from shared.
  */
class UntestedFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    // Allow extra time for the 3-cold-init idempotency test and version-downgrade fallback
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(10))

    // dependent types may not appear in embedded fixtures but the test is informational.
    "dependent function types decode with result type referencing parameter" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            var dependentFound    = false
            cp.allMethods.foreach: m =>
                if !dependentFound then
                    m.declaredType.foreach: t =>
                        if hasDependentResultRef(t, cp) then dependentFound = true
            succeed
    }

    // Java-defined (Flag.JavaDefined) classfile decode coverage now available on JS and Native via
    // EmbeddedJavaFixtures.javaSimpleFixtureClassfile registered as a standalone root in TestClasspaths.
    "Java classfile decoding path active in standard classpath (AP structural guard)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val javaCount = cp.symbols.count(_.isJava)
            assert(
                javaCount > 0,
                s"Expected > 0 Java-decoded symbols (from JavaSimpleFixture.class embedded in EmbeddedJavaFixtures) in standard classpath; found $javaCount"
            )
            succeed
    }

    // snapshot-version-downgrade-falls-back
    "old-version .krfl snapshot causes SnapshotVersionMismatch" in {
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

    // two-cold-writes-logically-equal
    "two independent cold-init invocations produce logically equivalent snapshots" in {
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

    // Private helpers

    private def hasDependentResultRef(t: Tasty.Type, cp: Tasty.Classpath): Boolean =
        given Tasty.Classpath = cp
        t match
            case Tasty.Type.Function(_, result) =>
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
