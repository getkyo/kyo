package kyo.test.runner

import kyo.Frame
import kyo.test.runner.internal.Args
import kyo.test.runner.internal.CliPlatform
import kyo.test.runner.internal.SuiteDiscoveryPlatform

/** Thrown by [[Cli.main]] in test environments instead of calling `java.lang.System.exit`.
  *
  * Tests intercept this exception to observe the exit code without terminating the JVM.
  *
  * @param code
  *   the exit code that would have been passed to `java.lang.System.exit`
  */
final class SystemExitException(val code: Int)(using Frame) extends kyo.KyoException(s"System.exit($code)")

/** Command-line entry point for kyo-test.
  *
  * Discovers `kyo.test.Test` subclasses via the platform-specific service-loader mechanism (on JVM: reads `META-INF/services/kyo.test.Test`
  * files from the classpath; on JS/Native: returns empty, since service-loader is a JVM concept). Parses command-line arguments via
  * [[Args]], builds a [[RunConfig]], and delegates execution to the pure-Kyo `kyo.test.runner.TestRunner`.
  *
  * Exit codes:
  *   - `0`: all leaves passed (or no leaves ran, or no suites discovered)
  *   - `1`: at least one leaf failed, was cancelled, or timed out
  *   - `2`: argument parse error (unknown flag, malformed value, etc.)
  *
  * Invoke via:
  * {{{
  *   java -cp <classpath> kyo.test.runner.Cli [options]
  * }}}
  *
  * Use `--help` for the full option reference.
  *
  * @see
  *   [[kyo.test.runner.TestRunner]] the runner that Cli delegates execution to
  * @see
  *   [[kyo.test.RunConfig]] the configuration object assembled from parsed arguments
  * @see
  *   [[kyo.test.TestFilter]] populated from `--include`, `--exclude`, and `--tag` flags
  */
object Cli:

    /** Exit function. Defaults to [[CliPlatform.exit]].
      *
      * The stub is scoped to the calling thread. Concurrent callers on different threads each see the real exit function unless they also
      * call [[withTestExit]].
      */
    private val doExitLocal: ThreadLocal[Int => Nothing] =
        new ThreadLocal[Int => Nothing]:
            override def initialValue(): Int => Nothing = (code: Int) => CliPlatform.exit(code)

    /** Run `body` with [[SystemExitException]] thrown instead of calling `java.lang.System.exit`.
      *
      * Used by tests to intercept exit calls without terminating the JVM. The original exit function is saved and restored via try/finally
      * so the stub is never leaked to the calling thread after the block completes.
      *
      * The stub is scoped to the calling thread. Concurrent callers on different threads each see the real exit function unless they also
      * call `withTestExit`.
      */
    def withTestExit[A](body: => A)(using Frame): A =
        val saved = doExitLocal.get()
        doExitLocal.set(code => throw new SystemExitException(code))
        try body
        finally doExitLocal.set(saved)
    end withTestExit

    /** Invoke the thread-local exit function with `code`. Accessible to tests in `kyo.test.runner` for exit-code contract verification.
      *
      * Callers must wrap this call in [[withTestExit]] to intercept the exit without terminating the process.
      */
    private[runner] def exitForTest(code: Int): Nothing = doExitLocal.get()(code)

    def main(args: Array[String]): Unit =
        Args.parse(args) match
            case Args.Result.Help =>
                java.lang.System.out.println(Args.usage)
                doExitLocal.get()(0)

            case Args.Result.Error(msg) =>
                java.lang.System.err.println(s"kyo-test: $msg")
                java.lang.System.err.println("Run with --help for usage.")
                doExitLocal.get()(2)

            case Args.Result.Ok(parsed) =>
                val config = parsed.config
                val suites = SuiteDiscoveryPlatform.discover()

                if suites.isEmpty then
                    java.lang.System.out.println(
                        "kyo-test: no test suites discovered. " +
                            "Ensure META-INF/services/kyo.test.Test is present on the classpath."
                    )
                    doExitLocal.get()(0)
                end if

                CliPlatform.runSuites(suites, config)
    end main

end Cli
