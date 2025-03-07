package kyo.test

import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Paths
import kyo.*
import scala.io.Source

private[test] object TestDebug:
    private val outputDirectory                 = "target/test-reports-zio"
    private def outputFileForTask(task: String) = s"$outputDirectory/${task}_debug.txt"
    private val tasks                           = Platform.newConcurrentSet[String]()(Unsafe.unsafe)

    private def createDebugFile(fullyQualifiedTaskName: String): Unit < Any = Kyo.pure {
        if tasks.add(fullyQualifiedTaskName) then
            makeOutputDirectory()
            val file = new File(outputFileForTask(fullyQualifiedTaskName))
            if file.exists() then file.delete()
            file.createNewFile()
    }

    private def makeOutputDirectory(): Unit =
        val fp = Paths.get(outputDirectory)
        Files.createDirectories(fp.getParent)

    def deleteIfEmpty(fullyQualifiedTaskName: String): Unit < Any = Kyo.pure {
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

    def print(executionEvent: ExecutionEvent, lock: TestDebugFileLock): Unit < Any =
        executionEvent match
            case t: ExecutionEvent.TestStarted =>
                createDebugFile(t.fullyQualifiedName) *>
                    write(t.fullyQualifiedName, s"${t.labels.mkString(" - ")} STARTED\n", true, lock)

            case t: ExecutionEvent.Test[?] =>
                createDebugFile(t.fullyQualifiedName) *>
                    removeLine(t.fullyQualifiedName, t.labels.mkString(" - ") + " STARTED", lock)

            case _ => Kyo.unit

    private def write(
        fullyQualifiedTaskName: String,
        content: String,
        append: Boolean,
        lock: TestDebugFileLock
    ): Unit < Any =
        lock.updateFile(
            Kyo
                .acquireReleaseWith(
                    Kyo.attemptBlockingIO(new java.io.FileWriter(outputFileForTask(fullyQualifiedTaskName), append))
                )(f => Kyo.attemptBlocking(f.close()).orPanic) { f =>
                    Kyo.attemptBlockingIO(f.append(content))
                }
                .ignore
        )

    private def removeLine(fullyQualifiedTaskName: String, searchString: String, lock: TestDebugFileLock): Unit < Any =
        lock.updateFile {
            Kyo.pure {
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
