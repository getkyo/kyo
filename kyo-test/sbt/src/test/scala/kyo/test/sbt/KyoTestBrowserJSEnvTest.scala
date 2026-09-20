package kyo.test.browser {

    import java.io.BufferedInputStream
    import java.io.BufferedOutputStream
    import java.io.DataInputStream
    import java.io.DataOutputStream
    import java.io.EOFException
    import java.net.Socket

    /** Stands in for kyo-test-browser's runner in [[kyo.test.sbt.KyoTestBrowserJSEnvTest]]: the environment starts this class by name, and
      * the module name picks what it does, so each test drives the environment's side of the channel against a runner whose behavior it
      * knows.
      */
    object BrowserRunnerMain {

        def main(args: Array[String]): Unit = {
            val values = args.toList.collect {
                case arg if arg.startsWith("--") && arg.contains("=") =>
                    arg.substring(2, arg.indexOf('=')) -> arg.substring(arg.indexOf('=') + 1)
            }.toMap
            values("module") match {
                case "exit-without-connecting.js" => System.exit(3)
                case "echo.js"                    => withChannel(values)((in, out) => echo(in, out))
                case "arguments.js"               =>
                    withChannel(values) { (_, out) =>
                        (args.toList :+ s"probe=${System.getProperty("kyo.test.probe")}").foreach(write(out, _))
                    }
                case other => sys.error(s"unknown scenario $other")
            }
        }

        private def withChannel(values: Map[String, String])(f: (DataInputStream, DataOutputStream) => Unit): Unit = {
            val socket = new Socket("127.0.0.1", values("com-port").toInt)
            val in     = new DataInputStream(new BufferedInputStream(socket.getInputStream))
            val out    = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream))
            f(in, out)
            out.flush()
            socket.close()
        }

        private def echo(in: DataInputStream, out: DataOutputStream): Unit =
            try {
                while (true) {
                    val units = Array.fill(in.readInt())(0.toChar)
                    units.indices.foreach(i => units(i) = in.readChar())
                    write(out, "echo:" + new String(units))
                    out.flush()
                }
            } catch {
                case _: EOFException => ()
            }

        private def write(out: DataOutputStream, message: String): Unit = {
            out.writeInt(message.length)
            out.writeChars(message)
        }
    }
}

package kyo.test.sbt {

    import java.io.File
    import java.nio.file.Paths
    import java.util.concurrent.ConcurrentLinkedQueue
    import org.scalajs.jsenv._
    import org.scalatest.freespec.AsyncFreeSpec
    import scala.collection.JavaConverters._
    import scala.concurrent.Future
    import scala.concurrent.Promise

    // ScalaTest bootstrap: sbt plugins build on Scala 2.12, where kyo-test is not available.
    class KyoTestBrowserJSEnvTest extends AsyncFreeSpec {

        private val java      = new File(new File(sys.props("java.home"), "bin"), if (scala.util.Properties.isWin) "java.exe" else "java")
        private val classpath = sys.props("java.class.path").split(File.pathSeparator).toSeq.map(new File(_))
        private val directory = Paths.get(sys.props("java.io.tmpdir")).toAbsolutePath

        private def env(jvmOptions: Seq[String] = Nil, chromeVersion: Option[String] = None) =
            new KyoTestBrowserJSEnv(java.getAbsolutePath, classpath, jvmOptions, chromeVersion)

        /** Starts a run of `scenario` and completes with the first `count` messages the runner sends. */
        private def start(
            environment: KyoTestBrowserJSEnv,
            input: String => Input,
            scenario: String,
            count: Int
        ): (JSComRun, Future[List[String]]) = {
            val received = new ConcurrentLinkedQueue[String]
            val done     = Promise[List[String]]()
            val run      = environment.startWithCom(
                Seq(input(directory.resolve(scenario).toString)),
                RunConfig(),
                { message =>
                    received.add(message)
                    if (received.size == count) done.trySuccess(received.asScala.toList)
                }
            )
            run.future.failed.foreach(done.tryFailure)
            (run, done.future)
        }

        private def esModule(path: String): Input = Input.ESModule(Paths.get(path))

        "messages sent before the runner connects arrive in order, and its replies reach onMessage in order" in {
            val unpaired       = "x" + 0xd800.toChar
            val (run, replies) = start(env(), esModule, "echo.js", 3)
            run.send("a")
            run.send("b")
            run.send(unpaired)
            for {
                messages <- replies
                _ = run.close()
                _ <- run.future
            } yield assert(messages == List("echo:a", "echo:b", "echo:" + unpaired))
        }

        "closing the run before the runner connects ends the run successfully" in {
            val (run, _) = start(env(), esModule, "echo.js", 1)
            run.close()
            run.future.map(_ => succeed)
        }

        "a runner that closes the channel and exits ends the run with its exit status" in {
            val (run, messages) = start(env(), esModule, "arguments.js", 1)
            for {
                _ <- messages
                _ <- run.future
            } yield succeed
        }

        "a runner that exits before connecting fails the run" in {
            val (run, _) = start(env(), esModule, "exit-without-connecting.js", 1)
            run.future.failed.map(error => assert(error == ExternalJSRun.NonZeroExitException(3)))
        }

        "the runner receives the module, its kind, the com port, the Chrome version, and the JVM options" in {
            val (run, messages)             = start(env(Seq("-Dkyo.test.probe=yes"), Some("151.0.7922.76")), esModule, "arguments.js", 6)
            val (scriptRun, scriptMessages) = start(env(), path => Input.Script(Paths.get(path)), "arguments.js", 5)
            for {
                arguments       <- messages
                scriptArguments <- scriptMessages
                _               <- run.future
                _               <- scriptRun.future
            } yield {
                assert(arguments.head == s"--dir=$directory")
                assert(arguments(1) == "--module=arguments.js")
                assert(arguments(2) == "--kind=esmodule")
                assert(arguments(3).matches("--com-port=[0-9]+"))
                assert(arguments(4) == "--chrome-version=151.0.7922.76")
                assert(arguments(5) == "probe=yes")
                assert(scriptArguments(2) == "--kind=script")
                assert(scriptArguments(4) == "probe=null")
            }
        }

        "a CommonJS module fails the run with the module kinds a page can load" in {
            val (run, _) = start(env(), path => Input.CommonJSModule(Paths.get(path)), "echo.js", 1)
            run.future.failed.map { error =>
                assert(error.isInstanceOf[UnsupportedInputException])
                assert(error.getMessage.contains("ModuleKind.NoModule or ModuleKind.ESModule"), error.getMessage)
            }
        }

        "a run without a com channel fails, since a page never ends on its own" in {
            env().start(Seq(esModule(directory.resolve("echo.js").toString)), RunConfig()).future.failed.map { error =>
                assert(error.isInstanceOf[UnsupportedOperationException])
                assert(error.getMessage.contains("runs Scala.js tests only"), error.getMessage)
            }
        }
    }
}
