package kyo.test.runner

import kyo.Chunk
import kyo.test.runner.internal.Args

/** JVM-only: verifies that --reporter=junit-xml:path parses to Ok on JVM (where JUnitXmlReporter is available). */
class ArgsJunitXmlTest extends kyo.test.Test[Any]:

    "parses --reporter=junit-xml:path on JVM yields Ok" in {
        val result = Args.parse(Array("--reporter=junit-xml:/tmp/reports"))
        result match
            case Args.Result.Ok(parsed) =>
                assert(parsed.reporterArgs == Chunk("junit-xml:/tmp/reports")): Unit
            case other =>
                fail(s"expected Ok on JVM for junit-xml:path, got: $other")
        end match
    }

end ArgsJunitXmlTest
