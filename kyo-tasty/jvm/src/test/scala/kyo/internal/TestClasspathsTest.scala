package kyo.internal

import kyo.*

/** Verifies the TestClasspaths helper itself: jar discovery, soft-fail mode, and classpath loading. */
class TestClasspathsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // Test 1: scala-library-jar-discoverable
    // Given: a JVM with java.class.path populated by sbt's test runner
    // When: TestClasspaths.scala3LibraryJar resolves the jar
    // Then: the path exists, ends in.jar, and the filename matches scala-library-3 or scala-library_3
    "scala-library jar is discoverable from java.class.path" in {
        val jar = TestClasspaths.scala3LibraryJar
        assert(jar.nonEmpty, "scala3LibraryJar was empty")
        assert(jar.endsWith(".jar"), s"Expected .jar suffix, got: $jar")
        val name = java.nio.file.Paths.get(jar).getFileName.toString
        assert(
            name.startsWith("scala-library-3") || name.startsWith("scala-library_3"),
            s"Expected scala-library-3* or scala-library_3* filename, got: $name"
        )
        assert(java.nio.file.Files.exists(java.nio.file.Paths.get(jar)), s"Jar does not exist at: $jar")
        succeed
    }

    // Test 2: soft-fail-mode-records-errors
    // Given: TestClasspaths.withClasspath invoked with a non-existent jar path in roots
    // When: load is attempted via ErrorMode.SoftFail
    // Then: the error surface is visible (either via cp.errors or via Abort[TastyError]);
    //       the error message refers to the missing path;
    //       before fix: no helper existed to make this observable in tests
    "soft-fail mode surfaces a diagnostic error for non-existent paths" in {
        val nonExistent = Seq("/no/such/path/does-not-exist.jar")
        Abort.run[TastyError](TestClasspaths.withClasspath(nonExistent)(Tasty.classpath)).map:
            case Result.Success(cp) =>
                // Some implementations accumulate the error in cp.errors rather than aborting.
                assert(
                    cp.errors.nonEmpty,
                    "Expected either an Abort or cp.errors to be non-empty for a non-existent path"
                )
            case Result.Failure(e) =>
                // Other implementations (the current one) abort immediately with FileNotFound.
                assert(
                    e.toString.toLowerCase.contains("not found") || e.toString.toLowerCase.contains("notfound"),
                    s"Expected 'not found' in error message, got: $e"
                )
            case Result.Panic(t) =>
                fail(s"Unexpected panic: ${t.getMessage}")
    }

    // Test 3: warm-load-faster-than-cold
    // Given: a freshly-built kyo-tasty classes dir plus the scala-library jar from java.class.path
    // When: invoking TestClasspaths.withClasspath twice on the same roots
    // Then: first-call elapsed > 0; both calls succeed without error
    // Note: wall-clock ordering (second < first) is not asserted due to JVM JIT variability; the
    // important invariant is that both loads succeed and produce a usable Classpath.
    "withClasspath loads successfully on two consecutive invocations" in {
        val roots = TestClasspaths.standard
        val t0    = java.lang.System.currentTimeMillis()
        TestClasspaths.withClasspath(roots)(Tasty.classpath).map: cp1 =>
            val elapsed1 = java.lang.System.currentTimeMillis() - t0
            assert(elapsed1 >= 0, "first load elapsed should be non-negative")
            assert(cp1.symbols.length > 0, "first load should produce symbols")
            val t1 = java.lang.System.currentTimeMillis()
            TestClasspaths.withClasspath(roots)(Tasty.classpath).map: cp2 =>
                val elapsed2 = java.lang.System.currentTimeMillis() - t1
                assert(elapsed2 >= 0, "second load elapsed should be non-negative")
                assert(cp2.symbols.length > 0, "second load should produce symbols")
                succeed
    }

end TestClasspathsTest
