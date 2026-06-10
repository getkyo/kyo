package kyo.test.runner.internal

import kyo.test.TestReporter

/** Scala Native stubs: file-based reporters require JVM-only APIs not available on Scala Native. */
private[runner] object ReportersPlatform:

    def makeJunitXml(path: String): kyo.Result[String, TestReporter] =
        kyo.Result.fail("--reporter=junit-xml is not supported on Scala Native")

    def makeTapFile(path: String): kyo.Result[String, TestReporter] =
        kyo.Result.fail("--reporter=tap:PATH is not supported on Scala Native")

end ReportersPlatform
