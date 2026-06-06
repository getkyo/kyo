package kyo.test.runner.internal

import kyo.test.TestReporter
import kyo.test.runner.JUnitXmlReporter
import kyo.test.runner.TapReporter

/** JVM implementation: constructs JVM-only reporters from path strings. */
private[runner] object ReportersPlatform:

    def makeJunitXml(path: String): kyo.Result[String, TestReporter] =
        kyo.Result.succeed(JUnitXmlReporter(java.nio.file.Paths.get(path)))

    def makeTapFile(path: String): kyo.Result[String, TestReporter] =
        kyo.Result.succeed(TapReporter(new java.io.PrintStream(new java.io.FileOutputStream(path))))

end ReportersPlatform
