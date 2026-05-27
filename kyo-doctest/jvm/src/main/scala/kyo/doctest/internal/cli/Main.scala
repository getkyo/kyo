package kyo.doctest.internal.cli

import kyo.*
import kyo.doctest.*
import kyo.doctest.internal.ErrorReporter

/** CLI entry point forked by the sbt plugin.
  *
  * Invocation: java -cp <classpath> kyo.doctest.internal.cli.Main <config-file> <result-file>
  *
  * The config file is a JSON document produced by Runner.scala containing the Doctest.Config to run. The result file receives a JSON
  * document containing the Doctest.Report. Exits 0 on clean run (zero failures), exits 1 if any block failed or a fatal error occurred.
  * Failure messages are printed to stderr before exiting.
  */
object Main extends KyoApp:

    private given Frame = Frame.internal

    run {
        if args.length < 2 then
            Console.printLineErr("Usage: Main <config-file> <result-file>").andThen {
                Abort.fail(new IllegalArgumentException("Expected 2 arguments"))
            }
        else
            val configPathStr = args(0).toString
            val resultPathStr = args(1).toString
            for
                configJson <-
                    Abort.recover[FileReadException](e =>
                        Abort.fail(new java.io.IOException(s"failed to read config: $e"))
                    )(Path(configPathStr).read)

                config <- decodeConfig(configJson)
                report <- runDoctest(config)

                _ <-
                    Abort.recover[FileWriteException](e =>
                        Abort.fail(new java.io.IOException(s"failed to write result: $e"))
                    )(Path(resultPathStr).write(Json.encode(report)))

                _ <-
                    if report.failures.nonEmpty then
                        // useAnsi=false: output is captured by the sbt plugin, not displayed directly in a terminal.
                        Console.printLineErr(ErrorReporter.renderAll(report.failures, useAnsi = false))
                            .andThen(Sync.defer { scala.sys.exit(1) })
                    else
                        Kyo.unit
            yield ()
            end for
        end if
    }

    private def runDoctest(
        config: Doctest.Config
    )(using Frame): Doctest.Report < (Async & Scope & Sync & Abort[Throwable]) =
        Abort.run[Doctest.Error](Doctest.check(config)).flatMap { (result: Result[Doctest.Error, Doctest.Report]) =>
            result match
                case Result.Success(r) => Sync.defer(r)
                case Result.Failure(err) =>
                    Log.error(s"doctest: fatal error: $err").andThen(Sync.defer { scala.sys.exit(1) })
                case Result.Panic(t) =>
                    Log.error(
                        s"doctest: unexpected panic: ${t.getClass.getSimpleName}: ${t.getMessage}",
                        t
                    ).andThen(Sync.defer { scala.sys.exit(1) })
        }

    private def decodeConfig(json: String)(using Frame): Doctest.Config < (Sync & Abort[Throwable]) =
        Sync.defer(Json.decode[Doctest.Config](json)).flatMap {
            case Result.Success(cfg) => Sync.defer(cfg)
            case Result.Failure(err) => Abort.fail(new IllegalArgumentException(s"Failed to parse config: $err"))
            case Result.Panic(t)     => Abort.fail(t)
        }

end Main
