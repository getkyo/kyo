package kyo.internal

import kyo.*

/** Verifies the TestClasspaths2 helper: warning-sink wiring and standard classpath fixture.
  *
  * Per plan Phase 2.01 test leaves 1-3 (TestClasspaths2Test.scala group).
  */
class TestClasspaths2Test extends kyo.Test:

    import AllowUnsafe.embrace.danger

    // Leaf 1 (Phase 2.01): warning-sink-captures-tag-warnings
    // Given: a capturing warning sink installed via TestClasspaths2.withWarningSink
    // When: loading the standard classpath (kyo-tasty + kyo-data + scala-library)
    //       which post-fix emits zero unknown-tag warnings (F-A2-001 fix)
    // Then: the sink collects zero "unhandled cat" / "unknown TASTy type tag" entries
    //       confirming both that the sink is wired correctly AND that the routing fix works.
    // Pins: HARD RULE 1 (real-classpath fixture infra must capture observable side effects)
    "warning-sink-captures-tag-warnings" in run {
        TestClasspaths2.loadStandardWithSink.map: (cp, sink) =>
            val tagWarningCount = sink.unknownTagCount
            assert(
                tagWarningCount == 0,
                s"Expected 0 unknown-tag warnings after F-A2-001 routing fix, found $tagWarningCount. " +
                    s"First warnings: ${sink.messages.filter(_.contains("TASTy")).take(3).mkString("; ")}"
            )
            succeed
    }

    // Leaf 2 (Phase 2.02 ACTIVE): cold-vs-warm-loader-determinism
    // Given: the standard classpath loaded cold then snapshot-read as warm
    // When: comparing symbol counts across cold and warm
    // Then: cold.symbols.size == warm.symbols.size and cold.fqnIndex.size == warm.fqnIndex.size
    // Pins: INV-101-DF2 producer-consumer link
    "cold-vs-warm-loader-determinism" in run {
        TestClasspaths2.standardWithSnapshot().map: (cold, warm) =>
            assert(
                cold.symbols.size == warm.symbols.size,
                s"cold.symbols.size (${cold.symbols.size}) != warm.symbols.size (${warm.symbols.size})"
            )
            assert(
                cold.fqnIndex.size == warm.fqnIndex.size,
                s"cold.fqnIndex.size (${cold.fqnIndex.size}) != warm.fqnIndex.size (${warm.fqnIndex.size})"
            )
            succeed
    }

    // Leaf 3 (Phase 2.01): standard-classpath-includes-stdlib-kyodata-kyotasty
    // Given: a fresh JVM
    // When: loading TestClasspaths2.standardRoots via withClasspath
    // Then: cp.symbols.size >= 79,000 (probe-001.log line 13934 baseline 79,567) and cp.errors.size == 0
    // Pins: HARD RULE 1 (real-classpath fixture)
    "standard-classpath-includes-stdlib-kyodata-kyotasty" in run {
        TestClasspaths.withClasspath(TestClasspaths2.standardRoots).map: cp =>
            assert(
                cp.symbols.size >= 79000,
                s"Expected >= 79,000 symbols (baseline 79,567), found ${cp.symbols.size}"
            )
            assert(
                cp.errors.isEmpty,
                s"Expected 0 cp.errors after routing fix, found ${cp.errors.size}: " +
                    cp.errors.take(3).map(_.toString).mkString(", ")
            )
            succeed
    }

end TestClasspaths2Test
