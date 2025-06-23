package kyo

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.Process as JProcess
import java.lang.ProcessBuilder.Redirect
import java.lang.System as JSystem
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kyo.*
import scala.jdk.CollectionConverters.*

case class Process(private val process: JProcess):
    def stdin: OutputStream                                                 = process.getOutputStream
    def stdout: InputStream                                                 = process.getInputStream
    def stderr: InputStream                                                 = process.getErrorStream
    def waitFor(using Frame): Int < Sync                                    = Sync(process.waitFor())
    def waitFor(timeout: Long, unit: TimeUnit)(using Frame): Boolean < Sync = Sync(process.waitFor(timeout, unit))
    def exitValue(using Frame): Int < Sync                                  = Sync(process.exitValue())
    def destroy(using Frame): Unit < Sync                                   = Sync(process.destroy())
    def destroyForcibly(using Frame): JProcess < Sync                       = Sync(process.destroyForcibly())
    def isAlive(using Frame): Boolean < Sync                                = Sync(process.isAlive())
end Process

object Process:

    object jvm:
        /** Executes a class with given args for the JVM.
          */
        def spawn(clazz: Class[?], args: List[String] = Nil)(using Frame): Process < Sync =
            command(clazz, args).map(_.spawn)

        /** Returns a `Process.Command` representing the execution of the `clazz` Class in a new JVM process. To finally execute the
          * command, use `spawn` or use directly `jvm.spawn`.
          */
        def command(clazz: Class[?], args: List[String] = Nil)(using Frame): Process.Command < Sync =
            Sync {
                val javaHome  = JSystem.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
                val classPath = JSystem.getProperty("java.class.path")
                val command   = javaHome :: "-cp" :: classPath :: clazz.getName().init :: args

                Process.Command(command*)
            }
    end jvm

    sealed trait Output derives CanEqual
    object Output:
        case class FileRedirect(file: File)       extends Output
        case class FileAppendRedirect(file: File) extends Output
        case object Inherit                       extends Output
        case object Pipe                          extends Output
        private[kyo] def redirect(std: Output): Redirect =
            std match
                case Output.FileRedirect(file)       => Redirect.to(file)
                case Output.FileAppendRedirect(file) => Redirect.appendTo(file)
                case Output.Inherit                  => Redirect.INHERIT
                case Output.Pipe                     => Redirect.PIPE
    end Output

    sealed trait Input derives CanEqual
    object Input:
        case object Inherit                    extends Input
        case object Pipe                       extends Input
        case class Stream(stream: InputStream) extends Input
        private[kyo] def redirect(std: Input): Redirect =
            std match
                case Input.Inherit => Redirect.INHERIT
                case Input.Pipe    => Redirect.PIPE
                case _             => null
        def fromString(s: String): Input                   = fromString(s, StandardCharsets.UTF_8)
        def fromString(s: String, charset: Charset): Input = Stream(new ByteArrayInputStream(s.getBytes(charset)))
    end Input

    sealed trait Command:
        self =>
        import Command.*

        /** Spawns a new process executing this command
          */
        def spawn(using Frame): Process < Sync

        /** Spawns a new process and returns its output as a String
          */
        def text(using Frame): String < Sync =
            stream.map { inputStream =>
                new String(inputStream.readAllBytes())
            }

        /** Spawns a new process and returns its output as a Stream
          */
        def stream(using Frame): InputStream < Sync =
            spawn.map(_.stdout)

        def exitValue(using Frame): Int < Sync =
            spawn.map(_.exitValue)

        def waitFor(using Frame): Int < Sync =
            spawn.map(_.waitFor)

        def waitFor(timeout: Long, unit: TimeUnit)(using Frame): Boolean < Sync =
            spawn.map(_.waitFor(timeout, unit))

        def pipe(that: Command): Command =
            Piped(self.flatten ++ that.flatten).simplify

        def andThen(that: Command): Command =
            self.pipe(that)

        def flatten: List[Simple]

        def +(that: Command): Command =
            val cs = self.flatten ++ that.flatten
            if cs.length > 1 then Piped(cs) else cs.head

        def map(f: Simple => Command): Command

        def simplify: Command =
            self match
                case Piped(cs) if cs.length == 1 => cs.head
                case c                           => c

        /** Modifies the current working directory of the command
          */
        def cwd(newCwd: Path): Command

        /** Modifies the environment variables of the command
          */
        def env(newEnv: Map[String, String]): Command

        /** Modifies the `stdin` of the command
          */
        def stdin(newStdin: Input): Command

        /** Modifies the `stdout` of the command
          */
        def stdout(newStdout: Output): Command

        /** Modifies the `stderr` of the command
          */
        def stderr(newStderr: Output): Command

        /** Changes `redirectErrorStream` property
          */
        def redirectErrorStream(value: Boolean): Command

    end Command

    object Command:

        def apply(commands: String*): Simple =
            Simple(commands.toList)

        case class Simple(
            command: List[String],
            cwd: Option[Path] = None,
            env: Map[String, String] = Map.empty,
            stdin: Input = Input.Inherit,
            stdout: Output = Output.Pipe,
            stderr: Output = Output.Pipe,
            redirectErrorStream: Boolean = false
        ) extends Command:
            self =>
            override def spawn(using Frame): Process < Sync =
                for
                    process <- Sync {
                        val builder = new ProcessBuilder(command*)

                        builder.redirectErrorStream(redirectErrorStream)
                        cwd.foreach(p => builder.directory(p.toFile()))

                        if env.nonEmpty then
                            builder.environment().putAll(env.asJava)

                        stdin match
                            case Input.Stream(_) => ()
                            case _               => builder.redirectInput(Input.redirect(stdin))
                        builder.redirectOutput(Output.redirect(stdout))
                        builder.redirectError(Output.redirect(stderr))

                        Process(builder.start())
                    }
                    _ <- stdin match
                        case Input.Stream(stream) =>
                            val resources = Resource.acquireRelease((stream, process.stdin)) { streams =>
                                streams._1.close()
                                streams._2.close()
                            }.map { streams =>
                                streams._1.transferTo(streams._2)
                                ()
                            }
                            for
                                _ <- Async.run(Resource.run(resources))
                            yield ()
                        case _ => Kyo.unit
                yield process

            override def cwd(newCwd: Path)                   = self.copy(cwd = Some(newCwd))
            override def env(newEnv: Map[String, String])    = self.copy(env = newEnv)
            override def stdin(newStdin: Input)              = self.copy(stdin = newStdin)
            override def stdout(newStdout: Output)           = self.copy(stdout = newStdout)
            override def stderr(newStderr: Output)           = self.copy(stderr = newStderr)
            override def redirectErrorStream(value: Boolean) = self.copy(redirectErrorStream = value)

            override def flatten: List[Simple]              = List(self)
            override def map(f: Simple => Command): Command = f(self)

        end Simple

        case class Piped(commands: List[Simple]) extends Command:
            self =>
            def spawnAll(using Frame): List[Process] < Sync =
                if commands.isEmpty then Sync(List.empty)
                else
                    commands.tail.foldLeft(commands.head.spawn.map(p => (p :: Nil, p.stdout))) { case (acc, nextCommand) =>
                        for
                            a <- acc
                            (processes, lastStdout) = a
                            nextProcess <- nextCommand.stdin(Input.Stream(lastStdout)).spawn
                        yield (processes ++ List(nextProcess), nextProcess.stdout)
                    }.map(_._1)
            override def spawn(using Frame): Process < Sync =
                spawnAll.map(_.last)

            override def cwd(newCwd: Path)                   = self.map(_.copy(cwd = Some(newCwd)))
            override def env(newEnv: Map[String, String])    = self.map(_.copy(env = newEnv))
            override def stdin(newStdin: Input)              = self.map(_.copy(stdin = newStdin))
            override def stdout(newStdout: Output)           = self.map(_.copy(stdout = newStdout))
            def stderr(newStderr: Output)                    = self.map(_.copy(stderr = newStderr))
            override def redirectErrorStream(value: Boolean) = self.map(_.copy(redirectErrorStream = value))
            override def flatten: List[Simple]               = commands
            override def map(f: Simple => Command)           = Piped(commands.map(f).map(_.flatten).flatten)
        end Piped
    end Command
end Process
