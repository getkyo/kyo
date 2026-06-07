package kyo.test.runner.internal

import kyo.Chunk
import kyo.test.RunConfig
import kyo.test.internal.TestBase

/** Scala.js stub for the CLI run-suites bridge.
  *
  * On Scala.js, the CLI is not the primary entry point (the sbt test-interface [[JsFramework]] bridge is). This stub exists so that
  * [[kyo.test.runner.Cli]] compiles on JS. It prints a message and does nothing, since `java.lang.System.exit` is not available on JS.
  */
private[runner] object CliPlatform:

    def runSuites(suites: Chunk[Class[? <: TestBase[?]]], config: RunConfig): Unit =
        java.lang.System.err.println(
            "kyo-test: Cli.main is not supported on Scala.js. Use the sbt test-interface bridge instead."
        )
    end runSuites

    /** Not supported on Scala.js (no process-level exit). Throws [[UnsupportedOperationException]]. */
    def exit(code: Int): Nothing =
        throw new UnsupportedOperationException(
            s"System.exit($code) is not available on Scala.js"
        )
    end exit

end CliPlatform
