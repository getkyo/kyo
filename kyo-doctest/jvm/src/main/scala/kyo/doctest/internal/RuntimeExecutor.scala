package kyo.doctest.internal

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import kyo.*

/** Executes compiled doctest blocks in an isolated JVM. */
private[kyo] object RuntimeExecutor:

    private val DefaultTimeout = Block.DefaultTimeout
    private val PollInterval   = 25.millis

    sealed trait Outcome derives CanEqual
    enum Progress derives CanEqual:
        case NotStarted, Started
        case Block(lineStart: Int)
    end Progress

    object Outcome:
        case object Completed                                                                   extends Outcome
        case class Threw(className: String, message: String, synthFile: String, synthLine: Int) extends Outcome
        case class TimedOut(after: Duration, progress: Progress)                                extends Outcome
        case class ProcessExited(code: Int, stderr: String, progress: Progress)                 extends Outcome
        case class LaunchFailed(message: String)                                                extends Outcome
        case class LoadFailed(message: String)                                                  extends Outcome
        case class Unsupported(message: String)                                                 extends Outcome
    end Outcome

    private enum WaitResult:
        case Exited(code: ExitCode)
        case TimedOut(after: Duration, progress: Progress)
    end WaitResult

    def execute(
        className: String,
        synthFile: kyo.Path,
        outputDir: kyo.Path,
        classpath: Chunk[kyo.Path],
        timeout: Duration = DefaultTimeout
    )(using Frame): Outcome < (Sync & Async) =
        execute(className, synthFile, outputDir, classpath, Map.empty, timeout)

    def execute(
        className: String,
        synthFile: kyo.Path,
        outputDir: kyo.Path,
        classpath: Chunk[kyo.Path],
        blockTimeouts: Map[Int, Duration],
        defaultTimeout: Duration
    )(using Frame): Outcome < (Sync & Async) =
        for
            id <- Random.uuid
            resultPath   = outputDir / s"runtime-result-$id"
            progressPath = outputDir / s"runtime-progress-$id"
            stdoutPath   = outputDir / s"runtime-stdout-$id"
            stderrPath   = outputDir / s"runtime-stderr-$id"
            result <- Abort.run[CommandException](Scope.run {
                for
                    javaHome <- System.property[String]("java.home", "")
                    javaBin = s"$javaHome${File.separator}bin${File.separator}java"
                    cp      = classpath.map(_.toString).mkString(File.pathSeparator)
                    process <- Command(
                        javaBin,
                        "-cp",
                        cp,
                        "kyo.doctest.internal.RuntimeExecutorMain",
                        resultPath.toString,
                        progressPath.toString,
                        outputDir.toString,
                        className,
                        synthFile.toString
                    ).stdoutToFile(stdoutPath).stderrToFile(stderrPath).spawn
                    progress <- readProgress(progressPath)
                    timeout = timeoutFor(progress, blockTimeouts, defaultTimeout)
                    deadline   <- Clock.deadline(timeout)
                    waitResult <- waitFor(process, progressPath, blockTimeouts, defaultTimeout, progress, timeout, deadline)
                    outcome <- waitResult match
                        case WaitResult.TimedOut(after, activeProgress) =>
                            process.destroyForcibly.andThen(Sync.defer(Outcome.TimedOut(after, activeProgress)))
                        case WaitResult.Exited(code) =>
                            readOutcome(resultPath, progressPath, stderrPath, code.toInt)
                yield outcome
            })
        yield result match
            case Result.Success(outcome) => outcome
            case Result.Failure(error)   => Outcome.LaunchFailed(error.toString)
            case Result.Panic(error)     => Outcome.LaunchFailed(formatThrowable(error))
    end execute

    private def waitFor(
        process: kyo.Process,
        progressPath: kyo.Path,
        blockTimeouts: Map[Int, Duration],
        defaultTimeout: Duration,
        progress: Progress,
        timeout: Duration,
        deadline: Clock.Deadline
    )(using Frame): WaitResult < (Sync & Async) =
        process.waitFor(PollInterval).flatMap {
            case Present(code) =>
                Sync.defer(WaitResult.Exited(code))
            case Absent =>
                readProgress(progressPath).flatMap { nextProgress =>
                    if nextProgress != progress then
                        val nextTimeout = timeoutFor(nextProgress, blockTimeouts, defaultTimeout)
                        Clock.deadline(nextTimeout).flatMap { nextDeadline =>
                            waitFor(process, progressPath, blockTimeouts, defaultTimeout, nextProgress, nextTimeout, nextDeadline)
                        }
                    else
                        deadline.isOverdue.flatMap { overdue =>
                            if overdue then Sync.defer(WaitResult.TimedOut(timeout, progress))
                            else waitFor(process, progressPath, blockTimeouts, defaultTimeout, progress, timeout, deadline)
                        }
                }
        }
    end waitFor

    private def timeoutFor(progress: Progress, blockTimeouts: Map[Int, Duration], defaultTimeout: Duration): Duration =
        progress match
            case Progress.Block(lineStart) => blockTimeouts.getOrElse(lineStart, defaultTimeout)
            case _                         => defaultTimeout

    private def readOutcome(
        resultPath: kyo.Path,
        progressPath: kyo.Path,
        stderrPath: kyo.Path,
        exitCode: Int
    )(using Frame): Outcome < Sync =
        Sync.defer {
            val resultFile = java.nio.file.Path.of(resultPath.toString)
            if Files.exists(resultFile) then
                val lines = Files.readAllLines(resultFile, StandardCharsets.UTF_8)
                if !lines.isEmpty && lines.get(0) == "completed" then Outcome.Completed
                else if lines.size() >= 5 && lines.get(0) == "threw" then
                    Outcome.Threw(
                        decode(lines.get(1)),
                        decode(lines.get(2)),
                        decode(lines.get(3)),
                        lines.get(4).toIntOption.getOrElse(0)
                    )
                else if lines.size() >= 2 && lines.get(0) == "load-failed" then
                    Outcome.LoadFailed(decode(lines.get(1)))
                else
                    Outcome.ProcessExited(
                        exitCode,
                        s"invalid runtime result: ${lines.toArray.mkString("|")}",
                        readProgressUnsafe(progressPath)
                    )
                end if
            else
                val stderrFile = java.nio.file.Path.of(stderrPath.toString)
                val stderr =
                    if Files.exists(stderrFile) then Files.readString(stderrFile, StandardCharsets.UTF_8)
                    else ""
                Outcome.ProcessExited(exitCode, stderr, readProgressUnsafe(progressPath))
            end if
        }
    end readOutcome

    private def readProgress(progressPath: kyo.Path)(using Frame): Progress < Sync =
        Sync.defer(readProgressUnsafe(progressPath))

    private def readProgressUnsafe(progressPath: kyo.Path): Progress =
        val file = java.nio.file.Path.of(progressPath.toString)
        if !Files.exists(file) then Progress.NotStarted
        else
            Files.readString(file, StandardCharsets.UTF_8).trim match
                case "started" => Progress.Started
                case value     => value.toIntOption.fold(Progress.Started)(Progress.Block(_))
        end if
    end readProgressUnsafe

    private def decode(value: String): String =
        new String(Base64.getDecoder.decode(value), StandardCharsets.UTF_8)

    private def formatThrowable(error: Throwable): String =
        val message = Option(error.getMessage).fold("")(m => s": $m")
        s"${error.getClass.getName}$message"

end RuntimeExecutor
