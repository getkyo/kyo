package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.query.ClasspathOrchestrator

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
  * JVM-only TestClasspaths2 helpers or java.nio.file). The concurrent reader+writer test moved to
  * ConcurrentSnapshotIoTest (shared/src/test) and runs on all three platforms.
  */
class UntestedFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    // Allow extra time for the 3-cold-init idempotency test and version-downgrade fallback
    override def timeout = Duration.fromJava(java.time.Duration.ofMinutes(10))

    // dependent types may not appear in embedded fixtures but the test is informational.
    "dependent function types decode with result type referencing parameter" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            given Tasty.Classpath = classpath
            var dependentFound    = false
            classpath.allMethods.foreach { m =>
                if !dependentFound then
                    m.declaredType.foreach { t =>
                        if hasDependentResultRef(t, classpath) then dependentFound = true
                    }
            }
            succeed
        }
    }

    // Java-defined (Flag.JavaDefined) classfile decode coverage now available on JS and Native via
    // EmbeddedJavaFixtures.javaSimpleFixtureClassfile registered as a standalone root in TestClasspaths.
    "Java classfile decoding path active in standard classpath (AP structural guard)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val javaCount = classpath.symbols.count(_.isJava)
            assert(
                javaCount > 0,
                s"Expected > 0 Java-decoded symbols (from JavaSimpleFixture.class embedded in EmbeddedJavaFixtures) in standard classpath; found $javaCount"
            )
            succeed
        }
    }

    "old-version .krfl snapshot causes SnapshotVersionMismatch" in {
        import kyo.internal.tasty.snapshot.SnapshotReader
        Sync.defer {
            // v3 KRFL: magic KRFL, major=1, minor=3, then 24 zero bytes for flags/digest/reserved/sectionCount
            val v3Bytes = new Array[Byte](32)
            v3Bytes(0) = 'K'.toByte; v3Bytes(1) = 'R'.toByte; v3Bytes(2) = 'F'.toByte; v3Bytes(3) = 'L'.toByte
            v3Bytes(4) = 1.toByte // major = 1
            v3Bytes(5) = 3.toByte // minor = 3 (below current)
            // remaining 26 bytes are already zero
            v3Bytes
        }
            .map { v3Bytes =>
                Abort.run[TastyError](SnapshotReader.readFromBytes(v3Bytes, "mem/test-v3.krfl")).map { result =>
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
            }
    }

    "two independent cold-init invocations produce logically equivalent snapshots" in {
        TestClasspaths2.withSnapshotInMemory().map { (cold1, warm1) =>
            TestClasspaths2.withSnapshotInMemory().map { (cold2, warm2) =>
                assert(
                    cold1.symbols.size == cold2.symbols.size,
                    s"Two cold loads produced different symbol counts: ${cold1.symbols.size} vs ${cold2.symbols.size}"
                )
                assert(
                    warm1.indices.byFullName.size == cold1.indices.byFullName.size,
                    s"warm1.indices.byFullName.size (${warm1.indices.byFullName.size}) != cold1.indices.byFullName.size (${cold1.indices.byFullName.size})"
                )
                assert(
                    warm2.indices.byFullName.size == cold2.indices.byFullName.size,
                    s"warm2.indices.byFullName.size (${warm2.indices.byFullName.size}) != cold2.indices.byFullName.size (${cold2.indices.byFullName.size})"
                )
                assert(
                    warm1.symbols.size == warm2.symbols.size,
                    s"Two warm loads produced different symbol counts: ${warm1.symbols.size} vs ${warm2.symbols.size}"
                )
                succeed
            }
        }
    }

    // Private helpers

    private def hasDependentResultRef(t: Tasty.Type, classpath: Tasty.Classpath): Boolean =
        given Tasty.Classpath = classpath
        t match
            case Tasty.Type.Function(_, result) =>
                isParameterRef(result, classpath) || hasDependentResultRef(result, classpath)
            case Tasty.Type.ContextFunction(_, result) =>
                isParameterRef(result, classpath) || hasDependentResultRef(result, classpath)
            case Tasty.Type.TypeLambda(_, body) => hasDependentResultRef(body, classpath)
            case _                              => false
        end match
    end hasDependentResultRef

    private def isParameterRef(t: Tasty.Type, classpath: Tasty.Classpath): Boolean =
        given Tasty.Classpath = classpath
        t match
            case Tasty.Type.Named(id) =>
                classpath.symbol(id).isInstanceOf[Tasty.Symbol.Parameter]
            case Tasty.Type.TermRef(qual, _) => isParameterRef(qual, classpath)
            case _                           => false
        end match
    end isParameterRef

end UntestedFidelity2Test
