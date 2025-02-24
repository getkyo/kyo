package zio.test

import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Paths
import scala.io.Source
import zio.Unsafe
import zio.ZIO
import zio.internal.Platform

private[test] object TestDebug:
    private val outputDirectory                 = "target/test-reports-zio"
    private def outputFileForTask(task: String) = s"$outputDirectory/${task}_debug.txt"
    private val tasks                           = Platform.newConcurrentSet[String]()(Unsafe.unsafe)

    private def createDebugFile(fullyQualifiedTaskName: String): ZIO[Any, Nothing, Unit] = ZIO.succeed {
        if tasks.add(fullyQualifiedTaskName) then
            makeOutputDirectory()
            val file = new File(outputFileForTask(fullyQualifiedTaskName))
            if file.exists() then file.delete()
            file.createNewFile()
    }

    private def makeOutputDirectory() =
        val fp = Paths.get(outputDirectory)
        Files.createDirectories(fp.getParent)

    def deleteIfEmpty(fullyQualifiedTaskName: String): ZIO[Any, Nothing, Unit] = ZIO.succeed {
        if tasks.remove(fullyQualifiedTaskName) then
            val file = new File(outputFileForTask(fullyQualifiedTaskName))
            if file.exists() then
                val source        = Source.fromFile(file)
                val nonBlankLines = source.getLines.filterNot(isBlank).toList
                source.close()
                if nonBlankLines.isEmpty then
                    file.delete()
            end if
    }

    private def isBlank(input: String): Boolean =
        input.toCharArray.forall(Character.isWhitespace(_))

    def print(executionEvent: ExecutionEvent, lock: TestDebugFileLock) =
        executionEvent match
            case t: ExecutionEvent.TestStarted =>
                createDebugFile(t.fullyQualifiedName) *>
                    write(t.fullyQualifiedName, s"${t.labels.mkString(" - ")} STARTED\n", true, lock)

            case t: ExecutionEvent.Test[?] =>
                createDebugFile(t.fullyQualifiedName) *>
                    removeLine(t.fullyQualifiedName, t.labels.mkString(" - ") + " STARTED", lock)

            case _ => ZIO.unit

    private def write(
        fullyQualifiedTaskName: String,
        content: String,
        append: Boolean,
        lock: TestDebugFileLock
    ): ZIO[Any, Nothing, Unit] =
        lock.updateFile(
            ZIO
                .acquireReleaseWith(
                    ZIO.attemptBlockingIO(new java.io.FileWriter(outputFileForTask(fullyQualifiedTaskName), append))
                )(f => ZIO.attemptBlocking(f.close()).orDie) { f =>
                    ZIO.attemptBlockingIO(f.append(content))
                }
                .ignore
        )

    private def removeLine(fullyQualifiedTaskName: String, searchString: String, lock: TestDebugFileLock) =
        lock.updateFile {
            ZIO.succeed {
                val file = new File(outputFileForTask(fullyQualifiedTaskName))
                if file.exists() then
                    val source = Source.fromFile(file)

                    val remainingLines =
                        source.getLines.filterNot(_.contains(searchString)).toList

                    val pw = new PrintWriter(outputFileForTask(fullyQualifiedTaskName))
                    pw.write(remainingLines.mkString("\n") + "\n")
                    pw.close()
                    source.close()
                end if
            }
        }
end TestDebug
