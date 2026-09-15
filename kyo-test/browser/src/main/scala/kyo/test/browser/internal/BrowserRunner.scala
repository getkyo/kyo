package kyo.test.browser.internal

import kyo.*
import kyo.internal.*
import kyo.internal.CdpTypes.SessionId
import kyo.net.NetException
import kyo.net.NetPlatform

/** Runs a linked Scala.js program in a page of its own chrome-headless-shell, relaying the Scala.js test adapter's com channel.
  *
  * The run serves the linked output with [[PageServer]], launches Chrome, opens a tab in a fresh browser context, connects to the com
  * port the sbt side listens on, and navigates the tab to the page. From then on:
  *   - each message the page sends through its binding is written to the com socket as a [[ComFrame]], in the order the page sent them
  *     (CDP notifications are handled one at a time, and the handler waits on the socket);
  *   - each message read from the socket is handed to the page by evaluating its receive function, one at a time, once the page reports
  *     that `scalajsCom` is installed;
  *   - console calls are written to the output, errors and warnings to its error side, as Node writes them.
  *
  * The run ends with 0 when the sbt side closes the com socket, which is how the test adapter ends a run. It ends with 1 when the page
  * throws an uncaught exception or rejects a promise with no handler (a Node run ends the same way), when a message handed to the page
  * throws, when the main module cannot be loaded, when the page's renderer crashes, or when Chrome exits. Closing the run's scope kills
  * Chrome, removes its user-data directory and stops the server.
  */
private[browser] object BrowserRunner:

    /** Where the run writes console output and its own diagnostics. */
    final case class Output(out: String => Unit < Sync, err: String => Unit < Sync)

    /** A run: the directory holding the linked output, the main module's path within it and how the page loads it, the com port, and the
      * Chrome-for-Testing version to run (`Absent` resolves the latest Stable).
      */
    final case class Config(
        dir: Path,
        module: String,
        kind: PageServer.ModuleKind,
        comPort: Int,
        chromeVersion: Maybe[String]
    ) derives CanEqual

    object Config:
        val usage: String =
            "usage: kyo.test.browser.BrowserRunnerMain --dir=<linked output directory> --module=<main module path> --kind=esmodule|script --com-port=<port> [--chrome-version=<version>]"

        /** Reads `--name=value` arguments. Every argument but `--chrome-version` is required. */
        def parse(args: Seq[String]): Result[String, Config] =
            val pairs = args.map { arg =>
                if !arg.startsWith("--") || !arg.contains('=') then Left(arg)
                else Right(arg.substring(2, arg.indexOf('=')) -> arg.substring(arg.indexOf('=') + 1))
            }
            pairs.collectFirst { case Left(arg) => arg } match
                case Some(arg) => Result.fail(s"unrecognized argument '$arg'; $usage")
                case None =>
                    val values = pairs.collect { case Right(pair) => pair }.toMap
                    val known  = Set("dir", "module", "kind", "com-port", "chrome-version")
                    def required(name: String): Result[String, String] =
                        values.get(name).filter(_.nonEmpty) match
                            case Some(value) => Result.succeed(value)
                            case None        => Result.fail(s"missing --$name; $usage")
                    values.keys.find(!known.contains(_)) match
                        case Some(unknown) => Result.fail(s"unrecognized argument '--$unknown'; $usage")
                        case None =>
                            for
                                dir     <- required("dir")
                                module  <- required("module")
                                kindArg <- required("kind")
                                kind <- kindArg match
                                    case "esmodule" => Result.succeed(PageServer.ModuleKind.ESModule)
                                    case "script"   => Result.succeed(PageServer.ModuleKind.Script)
                                    case other      => Result.fail(s"--kind must be esmodule or script, got '$other'")
                                portArg <- required("com-port")
                                port <- portArg.toIntOption.filter(p => p > 0 && p < 65536) match
                                    case Some(p) => Result.succeed(p)
                                    case None    => Result.fail(s"--com-port must be a port number, got '$portArg'")
                            yield Config(Path(dir), module, kind, port, Maybe.fromOption(values.get("chrome-version").filter(_.nonEmpty)))
                    end match
            end match
        end parse
    end Config

    /** Everything a run can fail with before the page is running; after that, failures end the run with 1. */
    type SetupError = BrowserSetupException | BrowserReadException | FileSystemException | HttpBindException | NetException

    /** Runs `config` and returns the exit status: 0 when the sbt side closed the com channel, 1 when the page or Chrome failed. */
    def run(config: Config, output: Output)(using Frame): Int < (Async & Abort[SetupError]) =
        runObserved(config, output)((_, _) => Kyo.unit)

    /** [[run]], calling `observe` with the page's CDP session and the Chrome process once navigation has started. A test seam: a test
      * crashes the renderer or ends Chrome through it to exercise the failure paths.
      */
    private[browser] def runObserved(config: Config, output: Output)(
        observe: (CdpBackend, Process) => Unit < (Async & Abort[BrowserReadException])
    )(using Frame): Int < (Async & Abort[SetupError]) =
        Scope.run {
            for
                files <- PageServer.load(config.dir)
                server <- HttpServer.init(HttpServerConfig.default.withoutAutoFilters)(PageServer.handlers(
                    PageServer.page(config.module, config.kind),
                    files
                )*)
                launch  <- Browser.chromeForTestingLaunchConfig(version = config.chromeVersion)
                chrome  <- BrowserLauncher.launchProcess(launch)
                backend <- CdpBackend.init(chrome._1, launch)
                page    <- openPage(backend)
                com     <- connect(config.comPort)
                exit    <- Promise.init[Int, Any]
                ready   <- Latch.init(1)
                _       <- routeConsole(page, output, exit)
                _       <- routeBindings(page, com, output, exit, ready)
                _       <- Fiber.init(deliverToPage(page, com, output, exit, ready))
                _       <- Fiber.init(chrome._2.waitFor.map(code => fail(output, exit, s"Chrome exited ($code) during the run")))
                _       <- page.send[NavigateParams, NavigateResult]("Page.navigate", NavigateParams(s"http://127.0.0.1:${server.port}/"))
                _       <- observe(page, chrome._2)
                code    <- exit.get
            yield code
        }

    private def openPage(backend: CdpBackend)(using Frame): CdpBackend < (Async & Scope & Abort[BrowserReadException]) =
        for
            context <- CdpBackend.createBrowserContext(backend)
            _ <- Scope.ensure(Abort.run(CdpBackend.disposeBrowserContext(
                backend,
                DisposeBrowserContextParams(context.browserContextId)
            )).unit)
            target  <- CdpBackend.createTarget(backend, CreateTargetParams("about:blank", Present(context.browserContextId)))
            session <- CdpBackend.attachToTarget(backend, AttachParams(target.targetId, flatten = true))
            page = backend.withSession(SessionId(session.sessionId))
            _ <- page.sendUnit("Runtime.enable", CdpNoParams())
            _ <- page.sendUnit("Inspector.enable", CdpNoParams())
        yield page

    private def connect(port: Int)(using Frame): kyo.net.Connection < (Async & Scope & Abort[NetException]) =
        Sync.Unsafe.defer(NetPlatform.transport.connect("127.0.0.1", port).safe.get).map { connection =>
            // Unsafe: closing the connection is the transport's own synchronous, idempotent teardown.
            Scope.ensure(Sync.Unsafe.defer(connection.close())).andThen(connection)
        }

    private def fail(output: Output, exit: Promise[Int, Any], message: String)(using Frame): Unit < Sync =
        exit.complete(Result.succeed(1)).map(first => if first then output.err(message) else Kyo.unit)

    /** Console calls to the output, uncaught exceptions and a crashed renderer to a failed run. */
    private def routeConsole(page: CdpBackend, output: Output, exit: Promise[Int, Any])(using Frame): Unit < Sync =
        val key = page.sessionId.map(_.value).getOrElse("")
        val handler: CdpEvent.Generic => Unit < Sync = event =>
            event.params match
                case call: ConsoleApiCalledWire =>
                    val text = call.args.map(_.text).mkString(" ")
                    call.`type` match
                        case "error" | "warning" | "assert" | "trace" => output.err(text)
                        case _                                        => output.out(text)
                case thrown: ExceptionThrownWire =>
                    val details = thrown.exceptionDetails
                    val text    = details.exception.map(thrown => s"${details.text} ${thrown.text}").getOrElse(details.text)
                    fail(output, exit, text)
                case _: TargetCrashedWire => fail(output, exit, "the page's renderer crashed")
                case _                    => Kyo.unit
        page.consoleEventDispatchers.updateAndGet(_.update(key, handler)).unit
    end routeConsole

    /** The page's messages to the com socket, its ready signal to the delivery fiber, and a module load failure to a failed run. */
    private def routeBindings(
        page: CdpBackend,
        com: kyo.net.Connection,
        output: Output,
        exit: Promise[Int, Any],
        ready: Latch
    )(using Frame): Unit < (Async & Abort[BrowserReadException]) =
        val key = page.sessionId.map(_.value).getOrElse("")
        val handler: CdpEvent.Generic => Unit < Async = event =>
            event.params match
                case call: BindingCalledWire if call.name == PageServer.sendBinding =>
                    PageServer.unescapeUnits(call.payload) match
                        case Result.Success(message) => Abort.run[Closed](com.outbound.safe.put(ComFrame.encode(message))).unit
                        case Result.Failure(error)   => fail(output, exit, error)
                        case Result.Panic(error)     => fail(output, exit, error.toString)
                case call: BindingCalledWire if call.name == PageServer.readyBinding => ready.release
                case call: BindingCalledWire if call.name == PageServer.failBinding  => fail(output, exit, call.payload)
                case _                                                               => Kyo.unit
        page.bindingEventDispatchers.updateAndGet(_.update(key, handler)).andThen {
            Kyo.foreachDiscard(Chunk(PageServer.sendBinding, PageServer.readyBinding, PageServer.failBinding)) { name =>
                page.sendUnit("Runtime.addBinding", AddBindingParams(name))
            }
        }
    end routeBindings

    /** Hands each message from the com socket to the page, in order, after the page is ready. The socket closing ends the run with 0. */
    private def deliverToPage(
        page: CdpBackend,
        com: kyo.net.Connection,
        output: Output,
        exit: Promise[Int, Any],
        ready: Latch
    )(using Frame): Unit < Async =
        ready.await.andThen {
            Loop(ComFrame.Decoder.empty) { decoder =>
                Abort.run[Closed](com.inbound.safe.take).map {
                    case Result.Success(bytes) =>
                        decoder.feed(bytes) match
                            case Result.Success((messages, next)) =>
                                Kyo.foreachDiscard(messages)(message => deliver(page, message, output, exit)).andThen(Loop.continue(next))
                            case Result.Failure(error) => fail(output, exit, error).andThen(Loop.done(()))
                            case Result.Panic(error)   => fail(output, exit, error.toString).andThen(Loop.done(()))
                    case _ => exit.complete(Result.succeed(0)).andThen(Loop.done(()))
                }
            }
        }
    end deliverToPage

    private def deliver(page: CdpBackend, message: String, output: Output, exit: Promise[Int, Any])(using Frame): Unit < Async =
        Abort.run[BrowserReadException] {
            CdpBackend.runtimeEvaluate(
                page,
                EvalParams(s"${PageServer.receiveFunction}(${PageServer.jsString(message)})", returnByValue = false)
            )
        }.map {
            case Result.Success(result) =>
                result.exceptionDetails match
                    case Present(details) =>
                        val description = details.exception.flatMap(_.descriptionOpt).orElse(details.text).getOrElse("an exception")
                        fail(output, exit, s"the page threw while receiving a message: $description")
                    case Absent => Kyo.unit
            case Result.Failure(error) => fail(output, exit, s"could not hand a message to the page: ${error.getMessage}")
            case Result.Panic(error)   => fail(output, exit, s"could not hand a message to the page: $error")
        }

end BrowserRunner
