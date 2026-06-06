package kyo.test.runner.internal

import kyo.Maybe
import kyo.test.runner.CombinedReporter

/** JVM-only: verifies that --reporter=tap,junit-xml:PATH yields a CombinedReporter.
  *
  * Requires java.nio.file.Files.createTempDirectory (JVM-only); the platform-neutral Args tests live in shared/ArgsParserTest.
  */
class ArgsParserJUnitXmlTest extends kyo.test.Test[Any]:

    // ── Test 4: --reporter=tap,junit-xml:PATH yields CombinedReporter ───────

    "test-4: --reporter=tap,junit-xml:PATH yields CombinedReporter(TapReporter, JUnitXmlReporter)" in {
        val tmpDir = java.nio.file.Files.createTempDirectory("kyo-test-junit-").toString
        val result = Args.parse(Array(s"--reporter=tap,junit-xml:$tmpDir"))
        result match
            case Args.Result.Ok(parsed) =>
                parsed.config.reporter match
                    case Maybe.Present(cr: CombinedReporter) =>
                        assert(cr.isInstanceOf[CombinedReporter]): Unit
                    case Maybe.Present(other) =>
                        fail(s"expected CombinedReporter, got ${other.getClass.getSimpleName}")
                    case Maybe.Absent =>
                        fail("expected Maybe.Present(CombinedReporter), got Absent")
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

end ArgsParserJUnitXmlTest
