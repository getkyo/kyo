package kyo.test.sbt

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.FileSystems
import java.nio.file.Path
import org.scalajs.jsenv._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

/** A Scala.js `JSEnv` that runs a linked test suite in Chrome. Build it with `kyoTestBrowserEnv` (see [[KyoTestJsPlugin]]).
  *
  * Each run starts a JVM running `kyo.test.browser.BrowserRunnerMain` from kyo-test-browser, which serves the linked output, loads it in
  * a chrome-headless-shell of its own and relays the test adapter's com messages over CDP. This side listens on a loopback port and
  * speaks the same com framing as scalajs-env-nodejs (a big-endian int count of UTF-16 code units, then the units), so the adapter sees
  * the runner exactly as it sees Node. The runner's standard output and error reach the adapter through `ExternalJSRun`.
  *
  * Only runs with a com channel are supported: a page has no end of its own, where a Node program ends once its event loop drains, so a
  * run without the test adapter's channel would never finish. The linked output must be a script (`ModuleKind.NoModule`) or an ES module
  * (`ModuleKind.ESModule`, which a WebAssembly link also produces); a page cannot load CommonJS.
  *
  * @param java
  *   the `java` executable for the runner
  * @param classpath
  *   kyo-test-browser's runtime classpath
  * @param jvmOptions
  *   options for the runner's JVM
  * @param chromeVersion
  *   the chrome-headless-shell version; `None` resolves the latest Stable
  */
final class KyoTestBrowserJSEnv(java: String, classpath: Seq[File], jvmOptions: Seq[String], chromeVersion: Option[String]) extends JSEnv {

    val name: String = "Chrome (kyo-test-browser)"

    def start(input: Seq[Input], config: RunConfig): JSRun =
        JSRun.failed(new UnsupportedOperationException(
            "KyoTestBrowserJSEnv runs Scala.js tests only: a page never ends on its own, so a run needs the test adapter's com channel"
        ))

    def startWithCom(input: Seq[Input], config: RunConfig, onMessage: String => Unit): JSComRun =
        try {
            KyoTestBrowserJSEnv.validator.validate(config)
            val (module, kind) = input match {
                case Seq(Input.ESModule(path)) => (path, "esmodule")
                case Seq(Input.Script(path))   => (path, "script")
                case Seq(Input.CommonJSModule(_)) =>
                    throw new UnsupportedInputException(
                        "a page cannot load a CommonJS module; link the tests with ModuleKind.NoModule or ModuleKind.ESModule"
                    )
                case _ => throw new UnsupportedInputException(input)
            }
            if (module.getFileSystem != FileSystems.getDefault)
                throw new UnsupportedInputException(s"KyoTestBrowserJSEnv serves linked files from disk; $module is not on the default file system")
            val server = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
            try {
                val command =
                    List(java) ++ jvmOptions ++ List(
                        "-cp",
                        classpath.map(_.getAbsolutePath).mkString(File.pathSeparator),
                        "kyo.test.browser.BrowserRunnerMain",
                        s"--dir=${module.toAbsolutePath.getParent}",
                        s"--module=${module.getFileName}",
                        s"--kind=$kind",
                        s"--com-port=${server.getLocalPort}"
                    ) ++ chromeVersion.map(v => s"--chrome-version=$v")
                val run = ExternalJSRun.start(command, ExternalJSRun.Config().withRunConfig(config))(_.close())
                new KyoTestBrowserJSEnv.ComRun(run, onMessage, server)
            } catch {
                case NonFatal(t) =>
                    server.close()
                    throw t
            }
        } catch {
            case NonFatal(t) => JSComRun.failed(t)
        }
}

object KyoTestBrowserJSEnv {

    private val validator = ExternalJSRun.supports(RunConfig.Validator())

    // The com channel's states: messages queue until the runner connects, then go straight to the socket until the run closes.
    private sealed trait State
    private final case class AwaitingConnection(queued: List[String]) extends State
    private final case class Connected(socket: Socket, out: DataOutputStream, in: DataInputStream) extends State
    private case object Closing extends State

    /** The sbt side of a run's com channel.
      *
      * The runner connects once; messages sent before it does are queued and written, in order, on connection. A message is read on a
      * dedicated thread and handed to `onMessage`. Closing the run closes the socket, after which the runner tears Chrome down and exits on
      * its own; the run completes once the process has ended, so closing never fails a run that was going to succeed. The runner ending
      * first (the page failed, or Chrome died) closes the channel from its side and completes the run with the process's outcome.
      */
    private final class ComRun(run: JSRun, onMessage: String => Unit, server: ServerSocket) extends JSComRun {

        private[this] val completion = Promise[Unit]()

        @volatile private[this] var state: State = AwaitingConnection(Nil)

        run.future.onComplete {
            case Failure(t) => forceClose(t)
            case Success(_) =>
                close()
                server.close()
        }

        private[this] val receiver = new Thread {
            setName("KyoTestBrowserJSEnv com receiver")
            setDaemon(true)

            override def run(): Unit =
                try {
                    try {
                        awaitConnection()
                        var reading = true
                        while (reading)
                            state match {
                                case Connected(_, _, in) =>
                                    try {
                                        val length = in.readInt()
                                        val units  = new Array[Char](length)
                                        var i      = 0
                                        while (i < length) {
                                            units(i) = in.readChar()
                                            i += 1
                                        }
                                        onMessage(new String(units))
                                    } catch {
                                        case _: EOFException =>
                                            close()
                                            reading = false
                                    }
                                case Closing                 => reading = false
                                case s: AwaitingConnection   => throw new IllegalStateException(s"unexpected state $s")
                            }
                    } catch {
                        case _: IOException if state == Closing => ()
                    }
                    ComRun.this.run.future.onComplete { result =>
                        ComRun.this.run.close()
                        result match {
                            case Success(_) => completion.trySuccess(())
                            case Failure(t) => completion.tryFailure(t)
                        }
                    }
                } catch {
                    case t: Throwable =>
                        forceClose(t)
                        if (!NonFatal(t)) throw t
                }
        }
        receiver.start()

        def future: Future[Unit] = completion.future

        def send(message: String): Unit = synchronized {
            state match {
                case AwaitingConnection(queued) => state = AwaitingConnection(message :: queued)
                case Connected(_, out, _) =>
                    try {
                        write(out, message)
                        out.flush()
                    } catch {
                        case NonFatal(t) => forceClose(t)
                    }
                case Closing => ()
            }
        }

        def close(): Unit = synchronized {
            val previous = state
            state = Closing
            previous match {
                case Connected(socket, out, in) => closeAll(socket, out, in)
                case _                          => ()
            }
        }

        private def forceClose(cause: Throwable): Unit = {
            completion.tryFailure(cause)
            close()
            run.close()
            server.close()
        }

        private def awaitConnection(): Unit = {
            val socket = server.accept()
            server.close()
            val out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream))
            val in  = new DataInputStream(new BufferedInputStream(socket.getInputStream))
            synchronized {
                state match {
                    case AwaitingConnection(queued) =>
                        queued.reverse.foreach(write(out, _))
                        out.flush()
                        state = Connected(socket, out, in)
                    case Closing =>
                        closeAll(socket, out, in)
                    case s: Connected =>
                        throw new IllegalStateException(s"unexpected state $s")
                }
            }
        }

        private def write(out: DataOutputStream, message: String): Unit = {
            out.writeInt(message.length)
            out.writeChars(message)
        }

        private def closeAll(resources: Closeable*): Unit =
            resources.foreach { resource =>
                try resource.close()
                catch { case _: IOException => () }
            }
    }
}
