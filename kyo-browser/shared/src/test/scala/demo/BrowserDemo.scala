package demo

import kyo.*

/** Base class for kyo-browser demos.
  *
  * Each concrete demo implements `flow` (the real browser work) and optionally overrides `validate` with a predicate on the result. The
  * base class wires up Chrome download, per-demo log file (`{demoName}.log`), step narration, and iTerm2 inline screenshots so the demo
  * bodies can focus on the interaction being demonstrated.
  *
  * Demos are dual-purpose:
  *   1. Runnable via `sbt 'kyo-browserJVM/runMain demo.XxxApp'`: prints a narrated trace and renders screenshots inline.
  *   2. Validated in tests via `DemoValidationTest`: each demo's `flow` runs against the shared test Chrome and its `validate` hook must
  *      return `Absent`.
  */
abstract class BrowserDemo[Result](val demoName: String):

    private[demo] val logFile: Path        = Path(s"$demoName.log")
    private[demo] val stepPause: Duration  = 2.seconds
    private[demo] val stepHeader: Duration = 500.millis

    /** The demo's browser work. Runs inside a `Browser.run` scope so `Browser.*` operations are available. */
    def flow(using Frame): Result < (Browser & Async & Scope & Abort[Throwable])

    /** Optional result validation. Returns `Absent` if the result is acceptable, `Present(message)` to flag a failure. */
    def validate(result: Result): Maybe[String] = Absent

    /** Full run entrypoint: download Chrome, launch, execute `flow`, validate, close. Used by the `KyoApp` entry points. */
    final def runDemo(using Frame): Result < (Async & Scope & Abort[Throwable]) =
        for
            _      <- writeOrLog(logFile.write(""), "log init failed")
            _      <- banner(s"kyo-browser demo: $demoName")
            _      <- Console.printLine(s"Log: ${logFile.toString}\n")
            _      <- log("demo starting")
            _      <- Console.printLine("Downloading / locating Chrome for Testing...")
            launch <- Browser.chromeForTestingLaunchConfig()
            _      <- Console.printLine(s"Launching: ${launch.executable}\n")
            _      <- log(s"launching ${launch.executable}")
            result <- Browser.run(launch)(flow)
            _ <- validate(result) match
                case Absent       => log("validation: OK").andThen(Console.printLine("\n✓ validation: OK"))
                case Present(msg) => log(s"validation: FAILED: $msg").andThen(Console.printLineErr(s"\n✗ validation FAILED: $msg"))
            _ <- log("demo complete")
            _ <- banner("Demo complete")
        yield result

    // --- Shared helpers ---

    private[demo] def banner(s: String)(using Frame): Unit < Sync =
        Console.printLine(s"\n═══ $s ═══\n")

    private[demo] def log(msg: String)(using Frame): Unit < Sync =
        Clock.now.map { ts =>
            writeOrLog(logFile.append(s"[$ts] $msg\n"), s"log write failed for: $msg")
        }

    /** Performs a file-write effect, logging any `FileException` failure as a warning rather than silently dropping it. */
    private def writeOrLog(write: => Unit < PathWrite, context: String)(using Frame): Unit < Sync =
        Abort.recover[FileException] { e =>
            Log.warn(s"$context: $e")
        }(Path.run(write))

    private[demo] def step(n: Int, desc: String)(using Frame): Unit < Async =
        for
            _ <- Console.printLine(s"\n[$n] $desc")
            _ <- log(s"--- step $n: $desc ---")
            _ <- Async.sleep(stepHeader)
        yield ()

    private[demo] def logState()(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        for
            u <- Browser.url
            t <- Browser.title
            _ <- log(s"  url   = $u")
            _ <- log(s"  title = $t")
        yield ()

    private[demo] def diagnostic(label: String, js: String)(using
        Frame
    ): Unit < (Browser & Abort[BrowserReadException]) =
        for
            v <- Browser.eval(js)
            _ <- log(s"  $label = $v")
        yield ()

    private[demo] def snapshot()(using Frame): Unit < (Browser & Abort[BrowserReadException]) =
        for
            img      <- Browser.screenshot()
            rendered <- img.renderToConsole(charsWidth = 80)
            _ <- rendered match
                case Present(s) => Console.printLine(s)
                case Absent =>
                    Console.printLine(
                        s"    (screenshot: ${img.binary.size} bytes; iTerm2 or Kitty required to render inline)"
                    )
            _ <- log(s"  screenshot = ${img.binary.size} bytes")
            _ <- Async.sleep(stepPause)
        yield ()

end BrowserDemo
