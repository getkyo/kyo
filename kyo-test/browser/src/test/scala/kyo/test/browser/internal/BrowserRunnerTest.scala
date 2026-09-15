package kyo.test.browser.internal

import kyo.*
import kyo.internal.CdpNoParams
import kyo.internal.ChromeDownloader
import kyo.net.Connection
import kyo.net.NetPlatform

/** [[BrowserRunner]] against a real chrome-headless-shell, with the test playing the sbt side of the com channel: it listens on a port,
  * accepts the runner's connection, and exchanges frames with the page.
  *
  * Leaves run one at a time, each with a Chrome of its own. On a platform with no chrome-headless-shell build the Chrome leaves cancel.
  */
class BrowserRunnerTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    override def config  = super.config.sequential
    override def timeout = 3.minutes

    // --- Config.parse ---

    private val validArgs = Seq("--dir=/tmp/out", "--module=main.js", "--kind=esmodule", "--com-port=4242")

    "Config.parse reads every argument" in {
        assert(BrowserRunner.Config.parse(validArgs :+ "--chrome-version=140.0.7339.80") ==
            Result.succeed(BrowserRunner.Config(
                Path("/tmp/out"),
                "main.js",
                PageServer.ModuleKind.ESModule,
                4242,
                Present("140.0.7339.80")
            )))
    }

    "Config.parse leaves the Chrome version to the latest Stable when it is not given" in {
        assert(BrowserRunner.Config.parse(validArgs).map(_.chromeVersion) == Result.succeed(Absent))
        assert(BrowserRunner.Config.parse(validArgs.updated(
            2,
            "--kind=script"
        )).map(_.kind) == Result.succeed(PageServer.ModuleKind.Script))
    }

    "Config.parse rejects a missing, unknown, or malformed argument" in {
        def error(args: Seq[String]): String = BrowserRunner.Config.parse(args) match
            case Result.Failure(message) => message
            case other                   => s"parsed: $other"
        assert(error(validArgs.filterNot(_.startsWith("--module"))).startsWith("missing --module"))
        assert(error(validArgs :+ "--headed=true").startsWith("unrecognized argument '--headed'"))
        assert(error(validArgs :+ "stray").startsWith("unrecognized argument 'stray'"))
        assert(error(validArgs.updated(2, "--kind=commonjs")) == "--kind must be esmodule or script, got 'commonjs'")
        assert(error(validArgs.updated(3, "--com-port=http")) == "--com-port must be a port number, got 'http'")
        assert(error(validArgs.updated(3, "--com-port=70000")) == "--com-port must be a port number, got '70000'")
    }

    // --- runs in Chrome ---

    /** The sbt side of one run: frames to and from the page, and what the run wrote. */
    final private class Session(connection: Connection, decoder: AtomicRef[ComFrame.Decoder], inbox: AtomicRef[Chunk[String]]):
        def send(message: String)(using Frame): Unit < (Async & Abort[Closed]) =
            connection.outbound.safe.put(ComFrame.encode(message))

        /** The next message from the page. */
        def receive(using Frame): String < (Async & Abort[Closed]) =
            inbox.get.map { queued =>
                queued.headMaybe match
                    case Present(message) => inbox.set(queued.drop(1)).andThen(message)
                    case Absent =>
                        connection.inbound.safe.take.map { bytes =>
                            decoder.get.map { current =>
                                current.feed(bytes) match
                                    case Result.Success((messages, next)) => decoder.set(next).andThen(inbox.set(messages)).andThen(receive)
                                    case other => Abort.panic(new IllegalStateException(s"bad frame from the runner: $other"))
                            }
                        }
                end match
            }

        def close(using Frame): Unit < Sync = Sync.defer(connection.close())
    end Session

    final private case class Outcome(code: Int, out: Chunk[String], err: Chunk[String])

    private val unsupportedPlatform: Maybe[String] =
        Sync.Unsafe.evalOrThrow {
            for
                os      <- System.operatingSystem
                arch    <- System.architecture
                outcome <- Abort.run[BrowserSetupException](ChromeDownloader.resolvePlatform(os, arch))
            yield outcome match
                case Result.Success(_) => Absent
                case other             => Present(s"no chrome-headless-shell for this platform: $other")
        }

    /** Writes `files` to a directory, runs the runner on it with `module`, and hands the test the session once the runner connects. */
    private def withRun(
        files: Map[String, String],
        module: String = "main.js",
        kind: PageServer.ModuleKind = PageServer.ModuleKind.ESModule,
        observe: (kyo.internal.CdpBackend, Process) => Unit < (Async & Abort[BrowserReadException]) = (_, _) => Kyo.unit
    )(test: Session => Unit < (Async & Abort[Closed]))(using Frame): Outcome < (Async & Abort[Any] & Scope) =
        unsupportedPlatform match
            case Present(reason) => Sync.defer(cancel(reason))
            case Absent =>
                for
                    dir      <- Path.run(Path.tempDir("kyo-test-browser-run-"))
                    _        <- Kyo.foreachDiscard(files.toSeq)((name, content) => Path.run((dir / name).write(content)))
                    accepted <- Promise.init[Connection, Any]
                    listener <- NetPlatform.transport.listen("127.0.0.1", 0, 1)(conn =>
                        discard(accepted.unsafe.complete(Result.succeed(conn)))
                    ).safe.get
                    _   <- Scope.ensure(Sync.defer(listener.close()))
                    out <- AtomicRef.init(Chunk.empty[String])
                    err <- AtomicRef.init(Chunk.empty[String])
                    output =
                        BrowserRunner.Output(line => out.updateAndGet(_.append(line)).unit, line => err.updateAndGet(_.append(line)).unit)
                    config = BrowserRunner.Config(dir, module, kind, listener.port, Absent)
                    run <- Fiber.init(BrowserRunner.runObserved(config, output)(observe))
                    // The race interrupts its loser, and waiting on a fiber links the waiter's interrupt to that fiber, so the run is
                    // raced through a masked view: a run that fails before connecting still ends the wait, and one that connects keeps going.
                    runView    <- run.mask
                    connection <- Async.race(accepted.get, runView.get.andThen(Async.never))
                    _          <- Scope.ensure(Sync.defer(connection.close()))
                    decoder    <- AtomicRef.init(ComFrame.Decoder.empty)
                    inbox      <- AtomicRef.init(Chunk.empty[String])
                    _          <- Abort.run[Closed](test(Session(connection, decoder, inbox)))
                    code       <- run.get
                    outLines   <- out.get
                    errLines   <- err.get
                yield Outcome(code, outLines, errLines)
        end match
    end withRun

    private val echoPage =
        """scalajsCom.init(function (message) { scalajsCom.send("echo:" + message); });
          |scalajsCom.send("ready");
          |""".stripMargin

    "messages flow both ways in order, and the run ends with 0 when the com channel closes" in {
        withRun(Map("main.js" -> echoPage)) { session =>
            val messages = Chunk.from((1 to 100).map(i => s"message $i"))
            for
                ready  <- session.receive
                _      <- Kyo.foreachDiscard(messages)(session.send)
                echoes <- Kyo.fill(messages.size)(session.receive)
                _      <- session.close
            yield
                assert(ready == "ready")
                assert(echoes == messages.map("echo:" + _))
            end for
        }.map(outcome => assert(outcome.code == 0, s"expected exit 0, got $outcome"))
    }

    "messages the page sends in one burst arrive in order" in {
        val page =
            """scalajsCom.init(function (message) {
              |  for (var i = 1; i <= 500; i++) scalajsCom.send(message + " " + i);
              |});
              |scalajsCom.send("ready");
              |""".stripMargin
        withRun(Map("main.js" -> page)) { session =>
            for
                _        <- session.receive
                _        <- session.send("burst")
                received <- Kyo.fill(500)(session.receive)
                _        <- session.close
            yield assert(received == Chunk.from((1 to 500).map(i => s"burst $i")))
        }.map(outcome => assert(outcome.code == 0, s"expected exit 0, got $outcome"))
    }

    "messages that arrive before scalajsCom.init reach the callback after it, in order" in {
        val page =
            """scalajsCom.send("ready");
              |setTimeout(function () {
              |  scalajsCom.init(function (message) { scalajsCom.send("late:" + message); });
              |}, 200);
              |""".stripMargin
        withRun(Map("main.js" -> page)) { session =>
            for
                _        <- session.receive
                _        <- Kyo.foreachDiscard(Chunk("a", "b", "c"))(session.send)
                received <- Kyo.fill(3)(session.receive)
                _        <- session.close
            yield assert(received == Chunk("late:a", "late:b", "late:c"))
        }.map(outcome => assert(outcome.code == 0, s"expected exit 0, got $outcome"))
    }

    "unicode, including unpaired surrogates, survives the trip through the page" in {
        withRun(Map("main.js" -> echoPage)) { session =>
            val message =
                "héllo " + 0xd83d.toChar + 0xde00.toChar + " " + 0xd800.toChar + " " + 0x2028.toChar + " </script> \"quoted\" \\back"
            for
                _    <- session.receive
                _    <- session.send(message)
                echo <- session.receive
                _    <- session.close
            yield assert(
                echo == "echo:" + message,
                s"expected the code units ${("echo:" + message).map(_.toInt)}, got ${echo.map(_.toInt)}"
            )
            end for
        }.map(outcome => assert(outcome.code == 0, s"expected exit 0, got $outcome"))
    }

    "console output reaches the run's output, errors and warnings its error side" in {
        val page =
            """console.log("hello", 42, true, null);
              |console.info("info line");
              |console.warn("careful");
              |console.error("bad");
              |scalajsCom.init(function () {});
              |scalajsCom.send("logged");
              |""".stripMargin
        withRun(Map("main.js" -> page)) { session =>
            session.receive.andThen(session.close)
        }.map { outcome =>
            assert(outcome.code == 0, s"expected exit 0, got $outcome")
            assert(outcome.out == Chunk("hello 42 true null", "info line"), s"unexpected output: $outcome")
            assert(outcome.err == Chunk("careful", "bad"), s"unexpected error output: $outcome")
        }
    }

    "a classic script main module runs" in {
        withRun(Map("main.js" -> echoPage), kind = PageServer.ModuleKind.Script) { session =>
            session.receive.map(ready => assert(ready == "ready")).andThen(session.close)
        }.map(outcome => assert(outcome.code == 0, s"expected exit 0, got $outcome"))
    }

    "an ESModule main module can import a sibling module" in {
        val page =
            """import { greeting } from "./greeting.js";
              |scalajsCom.init(function () {});
              |scalajsCom.send(greeting);
              |""".stripMargin
        withRun(Map("main.js" -> page, "greeting.js" -> "export const greeting = \"imported\";")) { session =>
            session.receive.map(message => assert(message == "imported")).andThen(session.close)
        }.map(outcome => assert(outcome.code == 0, s"expected exit 0, got $outcome"))
    }

    "an uncaught exception ends the run with 1" in {
        val page =
            """scalajsCom.init(function () {});
              |scalajsCom.send("ready");
              |setTimeout(function () { throw new Error("boom from the page"); }, 0);
              |""".stripMargin
        withRun(Map("main.js" -> page))(session => session.receive.unit).map { outcome =>
            assert(outcome.code == 1, s"expected exit 1, got $outcome")
            assert(outcome.err.exists(_.contains("boom from the page")), s"expected the exception on the error side, got $outcome")
        }
    }

    "an unhandled promise rejection ends the run with 1" in {
        val page =
            """scalajsCom.init(function () {});
              |scalajsCom.send("ready");
              |Promise.reject(new Error("rejected in the page"));
              |""".stripMargin
        withRun(Map("main.js" -> page))(session => session.receive.unit).map { outcome =>
            assert(outcome.code == 1, s"expected exit 1, got $outcome")
            assert(outcome.err.exists(_.contains("rejected in the page")), s"expected the rejection on the error side, got $outcome")
        }
    }

    "a message the page throws on ends the run with 1" in {
        val page =
            """scalajsCom.init(function (message) { throw new Error("cannot take " + message); });
              |scalajsCom.send("ready");
              |""".stripMargin
        withRun(Map("main.js" -> page)) { session =>
            session.receive.andThen(session.send("this"))
        }.map { outcome =>
            assert(outcome.code == 1, s"expected exit 1, got $outcome")
            assert(outcome.err.exists(_.contains("cannot take this")), s"expected the handler's exception on the error side, got $outcome")
        }
    }

    "a main module that cannot be loaded ends the run with 1" in {
        withRun(Map("main.js" -> echoPage), module = "missing.js")(_ => Kyo.unit).map { outcome =>
            assert(outcome.code == 1, s"expected exit 1, got $outcome")
            assert(outcome.err.exists(_.contains("could not load the main module missing.js")), s"expected the load failure, got $outcome")
        }
    }

    "a crashed renderer ends the run with 1" in {
        withRun(
            Map("main.js" -> echoPage),
            observe = (page, _) => Abort.run[BrowserReadException](page.sendUnit("Page.crash", CdpNoParams())).unit
        ) {
            _ => Kyo.unit
        }.map { outcome =>
            assert(outcome.code == 1, s"expected exit 1, got $outcome")
            assert(outcome.err.exists(_.contains("renderer crashed")), s"expected the crash on the error side, got $outcome")
        }
    }

    "Chrome exiting ends the run with 1" in {
        withRun(Map("main.js" -> echoPage), observe = (_, chrome) => Sync.defer(discard(chrome.unsafe.destroyForcibly()))) {
            _ => Kyo.unit
        }.map { outcome =>
            assert(outcome.code == 1, s"expected exit 1, got $outcome")
            assert(outcome.err.exists(_.contains("Chrome exited")), s"expected Chrome's exit on the error side, got $outcome")
        }
    }

end BrowserRunnerTest
