package kyo.internal

import kyo.*

/** Verifies the TestClasspaths2 helper: warning-sink wiring and standard classpath fixture. */
class TestClasspaths2Test extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

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

    "standard-classpath-includes-stdlib-kyodata-kyotasty" in {
        TestClasspaths.withClasspath(TestClasspaths2.standardRoots)(Tasty.classpath).map: cp =>
            assert(
                cp.symbols.size >= 81000,
                s"Expected >= 81,000 symbols (measured 81569), found ${cp.symbols.size}"
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
