package kyo.test.runner.internal

import kyo.Chunk
import kyo.Duration as KyoDuration
import kyo.Maybe
import kyo.test.RunConfig
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestResult
import kyo.test.internal.TestBase
import scala.concurrent.Await
// Java-interop: scala.concurrent.duration.Duration is required by Await.result; this is the only permissible use of scala.concurrent.duration in this file.
import scala.concurrent.duration.Duration

/** Scala Native implementation of the CLI run-suites bridge.
  *
  * Identical to the JVM implementation: calls the pure-Kyo `kyo.test.runner.TestRunner.runToFuture` for each discovered suite and blocks
  * via `Await.result` (Scala Native supports real threads). Exits with code `1` if any suite has failures, cancellations, or timeouts.
  */
private[runner] object CliPlatform:

    def runSuites(suites: Chunk[Class[? <: TestBase[?]]], config: RunConfig): Unit =
        var anyError = false

        for suite <- suites do
            val fut = kyo.test.runner.TestRunner.runToFutureAtCliEdge(suite, config)
            val report =
                try
                    Await.result(fut, Duration.Inf)
                catch
                    case t: Throwable =>
                        java.lang.System.err.println(
                            s"kyo-test: unexpected error running suite '${suite.getName}': $t"
                        )
                        TestReport(
                            Chunk(
                                SuiteReport(
                                    suite.getSimpleName.stripSuffix("$"),
                                    Chunk(
                                        (
                                            Chunk("<constructor>"),
                                            TestResult.Failed(t.toString, Maybe(t), KyoDuration.Zero)
                                        )
                                    ),
                                    KyoDuration.Zero
                                )
                            )
                        )
            if report.failed > 0 || report.cancelled > 0 || report.timedOut > 0 then
                anyError = true
        end for

        exit(if anyError then 1 else 0)
    end runSuites

    /** Terminates the process with the given exit code. */
    def exit(code: Int): Nothing =
        java.lang.System.exit(code)
        // Unsafe: System.exit never returns; throw satisfies the Nothing return type at compile time
        throw new AssertionError("unreachable after System.exit")
    end exit

end CliPlatform
