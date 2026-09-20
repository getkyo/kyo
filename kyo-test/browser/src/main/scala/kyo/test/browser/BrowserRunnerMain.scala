package kyo.test.browser

import kyo.*
import kyo.test.browser.internal.BrowserRunner

/** The process `kyo.test.sbt.KyoTestBrowserJSEnv` (sbt-kyo-test) starts for each Scala.js test run in a browser. See `kyo.test.browser.internal.BrowserRunner`.
  *
  * Exits with the run's status, and with 2 when the arguments do not parse. Console output goes to standard output and error, which the
  * sbt side relays to the test log.
  */
object BrowserRunnerMain:

    // A process entry point has no caller Frame to propagate.
    private given Frame = Frame.internal

    def main(args: Array[String]): Unit =
        val status = BrowserRunner.Config.parse(args.toSeq) match
            case Result.Success(config) =>
                import AllowUnsafe.embrace.danger
                // Unsafe: a process entry point, below any effect handler; the run blocks the main thread until it ends.
                Sync.Unsafe.evalOrThrow {
                    Abort.run[Any](KyoApp.runAndBlock(Duration.Infinity)(BrowserRunner.run(config, output))).map {
                        case Result.Success(code)  => code
                        case Result.Failure(error) =>
                            java.lang.System.err.println(s"browser test run failed: $error")
                            1
                        case Result.Panic(error) =>
                            java.lang.System.err.println(s"browser test run failed: $error")
                            error.printStackTrace()
                            1
                    }
                }
            case Result.Failure(message) =>
                java.lang.System.err.println(message)
                2
            case Result.Panic(error) =>
                java.lang.System.err.println(s"browser test run failed: $error")
                2
        java.lang.System.exit(status)
    end main

    private def output: BrowserRunner.Output =
        BrowserRunner.Output(
            line => Sync.defer(java.lang.System.out.println(line)),
            line => Sync.defer(java.lang.System.err.println(line))
        )

end BrowserRunnerMain
