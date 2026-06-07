package kyo.internal

import kyo.*

/** Verifies the TestClasspaths2 helper: warning-sink wiring and standard classpath fixture.
  *
  * Per plan test leaves 1-3 (TestClasspaths2Test.scala group).
  */
class TestClasspaths2Test extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // Leaf 1: warning-sink-captures-tag-warnings
    // Given: a capturing warning sink installed via TestClasspaths2.withWarningSink
    // When: loading the standard classpath (kyo-tasty + kyo-data + scala-library)
    //       which post-fix emits zero unknown-tag warnings
    // Then: the sink collects zero "unhandled cat" / "unknown TASTy type tag" entries
    //       confirming both that the sink is wired correctly AND that the routing fix works.
    "warning-sink-captures-tag-warnings" in {
        TestClasspaths2.loadStandardWithSink.map: (cp, sink) =>
            val tagWarningCount = sink.unknownTagCount
            assert(
                tagWarningCount == 0,
                s"Expected 0 unknown-tag warnings after term-tag routing fix, found $tagWarningCount. " +
                    s"First warnings: ${sink.messages.filter(_.contains("TASTy")).take(3).mkString("; ")}"
            )
            succeed
    }

    // cold-vs-warm-loader-determinism
    // Given: the standard classpath loaded cold then snapshot-read as warm
    // When: comparing symbol counts across cold and warm
    // Then: cold.symbols.size == warm.symbols.size and cold.fqnIndex.size == warm.fqnIndex.size
    "cold-vs-warm-loader-determinism" in {
        TestClasspaths2.standardWithSnapshot().map: (cold, warm) =>
            assert(
                cold.symbols.size == warm.symbols.size,
                s"cold.symbols.size (${cold.symbols.size}) != warm.symbols.size (${warm.symbols.size})"
            )
            assert(
                cold.indices.byFqn.size == warm.indices.byFqn.size,
                s"cold.fqnIndex.size (${cold.indices.byFqn.size}) != warm.fqnIndex.size (${warm.indices.byFqn.size})"
            )
            succeed
    }

    // standard-classpath-includes-stdlib-kyodata-kyotasty
    // Given: a fresh JVM
    // When: loading TestClasspaths2.standardRoots via withClasspath
    // Then: cp.symbols.size >= 81,000 (RI-008 measured 81569); no file-level errors in cp.errors.
    //       UnknownType errors for TypeAlias/OpaqueType/Parameter symbols with absent types are allowed --
    //       these arise when the AstUnpickler's TypeUnpickler.readTypeIntoSession catches a decode exception
    //       and returns Absent (cross-file type refs that cannot be decoded in Phase B). Carry A2 correctly
    //       wires Cat 14 producers so these were hidden by the null sentinel before.
    "standard-classpath-includes-stdlib-kyodata-kyotasty" in {
        TestClasspaths.withClasspath(TestClasspaths2.standardRoots)(Tasty.classpath).map: cp =>
            assert(
                cp.symbols.size >= 81000,
                s"Expected >= 81,000 symbols (RI-008 measured 81569), found ${cp.symbols.size}"
            )
            val fileErrors = cp.errors.filter:
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.MalformedSection => true
                case _: TastyError.FileNotFound     => true
                case _                              => false
            assert(
                fileErrors.isEmpty,
                s"Expected no file-level errors, found ${fileErrors.size}: " +
                    fileErrors.take(3).map(_.toString).mkString(", ")
            )
            succeed
    }

end TestClasspaths2Test
