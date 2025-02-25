package kyo.test.results

import java.io.IOException
import kyo.*
import scala.io.Source

private[test] trait ResultFileOps:
    def write(content: => String, append: Boolean): Unit < (IO & Abort[IOException])

private[test] object ResultFileOps:
    val live: Layer[ResultFileOps, Any] =
        Layer.scoped(
            Json.apply
        )

    private[test] case class Json(resultPath: String, lock: Var[Unit]) extends ResultFileOps:
        def write(content: => String, append: Boolean): Unit < (IO & Abort[IOException]) =
            lock.updateKyo(_ =>
                Kyo
                    .acquireReleaseWith(Kyo.attemptBlockingIO(new java.io.FileWriter(resultPath, append)))(f =>
                        Kyo.attemptBlocking(f.close()).orDie
                    ) { f =>
                        Kyo.attemptBlockingIO(f.append(content))
                    }
                    .ignore
            )

        private val makeOutputDirectory = Kyo.attempt {
            import java.nio.file.{Files, Paths}

            val fp = Paths.get(resultPath)
            Files.createDirectories(fp.getParent)
        }.unit

        private def closeJson: Unit < (Env[Scope] & Abort[Throwable]) =
            removeLastComma *>
                write("\n  ]\n}", append = true).orDie

        private def writeJsonPreamble: Unit < IO =
            write(
                """|{
           |  "results": [""".stripMargin,
                append = false
            ).orDie

        private val removeLastComma =
            for
                source <- Kyo.pure(Source.fromFile(resultPath))
                updatedLines =
                    val lines = source.getLines().toList
                    if lines.nonEmpty && lines.last.endsWith(",") then
                        val lastLine    = lines.last
                        val newLastLine = lastLine.dropRight(1)
                        lines.init :+ newLastLine
                    else
                        lines
                    end if
                _ <- Kyo.when(updatedLines.nonEmpty) {
                    val firstLine :: rest = updatedLines
                    for
                        _ <- write(firstLine + "\n", append = false)
                        _ <- Kyo.foreach(rest)(line => write(line + "\n", append = true))
                        _ <- Kyo.addFinalizer(Kyo.attempt(source.close()).orDie)
                    yield ()
                    end for
                }
            yield ()
    end Json

    object Json:
        def apply: Json < (Env[Scope] & Abort[Nothing]) =
            Kyo.acquireRelease(
                for
                    fileLock <- AtomicRef.init[Unit](())
                    instance = Json("target/test-reports-zio/output.json", fileLock)
                    _ <- instance.makeOutputDirectory.orDie
                    _ <- instance.writeJsonPreamble
                yield instance
            )(instance => instance.closeJson.orDie)
    end Json
end ResultFileOps
