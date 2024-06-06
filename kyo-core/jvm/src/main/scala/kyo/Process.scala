package kyo

import java.io.*
import java.lang.Process as JProcess
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kyo.*
import kyo.internal.Trace
import scala.jdk.CollectionConverters.*

sealed trait ProcessCommand:
    self =>
    import ProcessCommand.*

    /** Spawns a new process executing this command
      */
    def spawn(using Trace): Process < IOs

    /** Spawns a new process and returns its output as a String
      */
    def text(using Trace): String < IOs =
        stream.map { inputStream =>
            new String(inputStream.readAllBytes())
        }

    /** Spawns a new process and returns its output as a Stream
      */
    def stream(using Trace): InputStream < IOs =
        spawn.map(_.stdout)

    def exitValue(using Trace): Int < IOs =
        spawn.map(_.exitValue)

    def waitFor(using Trace): Int < IOs =
        spawn.map(_.waitFor)

    def waitFor(timeout: Long, unit: TimeUnit)(using Trace): Boolean < IOs =
        spawn.map(_.waitFor(timeout, unit))

    def pipe(that: ProcessCommand): ProcessCommand =
        ProcessCommand.Piped(self.flatten ++ that.flatten).simplify

    def andThen(that: ProcessCommand): ProcessCommand =
        self.pipe(that)

    def flatten: List[ProcessCommand.Simple]

    def +(that: ProcessCommand): ProcessCommand =
        val cs = self.flatten ++ that.flatten
        if cs.length > 1 then ProcessCommand.Piped(cs) else cs.head

    def map(f: ProcessCommand.Simple => ProcessCommand): ProcessCommand

    def simplify: ProcessCommand =
        self match
            case ProcessCommand.Piped(cs) if cs.length == 1 => cs.head
            case c                                          => c

    /** Modifies the current working directory of the command
      */
    def cwd(newCwd: Path): ProcessCommand

    /** Modifies the environment variables of the command
      */
    def env(newEnv: Map[String, String]): ProcessCommand

    /** Modifies the `stdin` of the command
      */
    def stdin(newStdin: ProcessInput): ProcessCommand

    /** Modifies the `stdout` of the command
      */
    def stdout(newStdout: ProcessOutput): ProcessCommand

    /** Modifies the `stderr` of the command
      */
    def stderr(newStderr: ProcessOutput): ProcessCommand

    /** Changes `redirectErrorStream` property
      */
    def redirectErrorStream(value: Boolean): ProcessCommand

end ProcessCommand

object ProcessCommand:

    def apply(commands: String*): ProcessCommand.Simple =
        ProcessCommand.Simple(commands.toList)

    case class Simple(
        command: List[String],
        cwd: Option[Path] = None,
        env: Map[String, String] = Map.empty,
        stdin: ProcessInput = ProcessInput.Inherit,
        stdout: ProcessOutput = ProcessOutput.Pipe,
        stderr: ProcessOutput = ProcessOutput.Pipe,
        redirectErrorStream: Boolean = false
    ) extends ProcessCommand:
        self =>
        override def spawn(using Trace): Process < IOs =
            for
                process <- IOs {
                    val builder = new ProcessBuilder(command*)

                    builder.redirectErrorStream(redirectErrorStream)
                    cwd.map(p => builder.directory(p.toFile()))

                    if env.nonEmpty then
                        builder.environment().putAll(env.asJava)

                    stdin match
                        case ProcessInput.Stream(_) => ()
                        case _                      => builder.redirectInput(ProcessInput.redirect(stdin))
                    builder.redirectOutput(ProcessOutput.redirect(stdout))
                    builder.redirectError(ProcessOutput.redirect(stderr))

                    Process(builder.start())
                }
                _ <- stdin match
                    case ProcessInput.Stream(stream) =>
                        val resources = Resources.acquireRelease((stream, process.stdin)) { streams =>
                            streams._1.close()
                            streams._2.close()
                        }.map { streams =>
                            streams._1.transferTo(streams._2)
                            ()
                        }
                        for
                            _ <- Fibers.init(Resources.run(resources))
                        yield ()
                    case _ => IOs.unit
            yield process

        override def cwd(newCwd: Path): ProcessCommand                   = self.copy(cwd = Some(newCwd))
        override def env(newEnv: Map[String, String]): ProcessCommand    = self.copy(env = newEnv)
        override def stdin(newStdin: ProcessInput): ProcessCommand       = self.copy(stdin = newStdin)
        override def stdout(newStdout: ProcessOutput): ProcessCommand    = self.copy(stdout = newStdout)
        override def stderr(newStderr: ProcessOutput): ProcessCommand    = self.copy(stderr = newStderr)
        override def redirectErrorStream(value: Boolean): ProcessCommand = self.copy(redirectErrorStream = value)

        override def flatten: List[ProcessCommand.Simple]                            = List(self)
        override def map(f: ProcessCommand.Simple => ProcessCommand): ProcessCommand = f(self)

    end Simple

    case class Piped(commands: List[ProcessCommand.Simple]) extends ProcessCommand:
        self =>
        def spawnAll(using Trace): List[Process] < IOs =
            if commands.isEmpty then IOs(List.empty)
            else
                commands.tail.foldLeft(commands.head.spawn.map(p => (p :: Nil, p.stdout))) { case (acc, nextCommand) =>
                    for
                        a <- acc
                        (processes, lastStdout) = a
                        nextProcess <- nextCommand.stdin(ProcessInput.Stream(lastStdout)).spawn
                    yield (processes ++ List(nextProcess), nextProcess.stdout)
                }.map(_._1)
        override def spawn(using Trace): Process < IOs =
            spawnAll.map(_.last)

        override def cwd(newCwd: Path): ProcessCommand                   = self.map(_.copy(cwd = Some(newCwd)))
        override def env(newEnv: Map[String, String]): ProcessCommand    = self.map(_.copy(env = newEnv))
        override def stdin(newStdin: ProcessInput): ProcessCommand       = self.map(_.copy(stdin = newStdin))
        override def stdout(newStdout: ProcessOutput): ProcessCommand    = self.map(_.copy(stdout = newStdout))
        def stderr(newStderr: ProcessOutput): ProcessCommand             = self.map(_.copy(stderr = newStderr))
        override def redirectErrorStream(value: Boolean): ProcessCommand = self.map(_.copy(redirectErrorStream = value))
        override def flatten: List[ProcessCommand.Simple]                = commands
        override def map(f: ProcessCommand.Simple => ProcessCommand): ProcessCommand =
            ProcessCommand.Piped(commands.map(f).map(_.flatten).flatten)
    end Piped
end ProcessCommand

case class Process(private val process: JProcess):
    def stdin: OutputStream                                                = process.getOutputStream
    def stdout: InputStream                                                = process.getInputStream
    def stderr: InputStream                                                = process.getErrorStream
    def waitFor(using Trace): Int < IOs                                    = IOs(process.waitFor())
    def waitFor(timeout: Long, unit: TimeUnit)(using Trace): Boolean < IOs = IOs(process.waitFor(timeout, unit))
    def exitValue(using Trace): Int < IOs                                  = IOs(process.exitValue())
    def destroy(using Trace): Unit < IOs                                   = IOs(process.destroy())
    def destroyForcibly(using Trace): JProcess < IOs                       = IOs(process.destroyForcibly())
    def isAlive(using Trace): Boolean < IOs                                = IOs(process.isAlive())
end Process

sealed trait ProcessOutput derives CanEqual
object ProcessOutput:
    case class FileRedirect(file: File)       extends ProcessOutput
    case class FileAppendRedirect(file: File) extends ProcessOutput
    case object Inherit                       extends ProcessOutput
    case object Pipe                          extends ProcessOutput
    private[kyo] def redirect(std: ProcessOutput): Redirect =
        std match
            case ProcessOutput.FileRedirect(file)       => Redirect.to(file)
            case ProcessOutput.FileAppendRedirect(file) => Redirect.appendTo(file)
            case ProcessOutput.Inherit                  => Redirect.INHERIT
            case ProcessOutput.Pipe                     => Redirect.PIPE
end ProcessOutput

sealed trait ProcessInput derives CanEqual
object ProcessInput:
    case object Inherit                    extends ProcessInput
    case object Pipe                       extends ProcessInput
    case class Stream(stream: InputStream) extends ProcessInput
    private[kyo] def redirect(std: ProcessInput): Redirect =
        std match
            case ProcessInput.Inherit => Redirect.INHERIT
            case ProcessInput.Pipe    => Redirect.PIPE
            case _                    => null
    def fromString(s: String): ProcessInput                   = fromString(s, StandardCharsets.UTF_8)
    def fromString(s: String, charset: Charset): ProcessInput = Stream(new ByteArrayInputStream(s.getBytes(charset)))
end ProcessInput
