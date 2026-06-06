package kyo.test.runner

import kyo.test.runner.internal.Args

/** Scala Native: verifies that --reporter=junit-xml:path returns Error on Native (JUnitXmlReporter requires java.nio.file). */
class ArgsJunitXmlTest extends kyo.test.Test[Any]:

    "parses --reporter=junit-xml:path on Native yields Error (junit-xml not supported)" in {
        val result = Args.parse(Array("--reporter=junit-xml:/tmp/reports"))
        result match
            case Args.Result.Error(msg) =>
                assert(msg.contains("not supported"), s"expected 'not supported' in error, got: $msg"): Unit
            case other =>
                fail(s"expected Error on Native for junit-xml:path (java.nio.file unavailable), got: $other")
        end match
    }

end ArgsJunitXmlTest
