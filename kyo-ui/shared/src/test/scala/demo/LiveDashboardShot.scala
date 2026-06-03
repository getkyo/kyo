package demo

import kyo.*

/** Serves `LiveDashboard.app` in-process and captures TWO frames a few seconds apart so a human can confirm the data
  * actually moves: the first screenshot ~1.5s after load, the second after a further 3s.
  *
  * One process (server + browser together) so it never contends with a separate sbt run for the project lock.
  *
  * Usage: `sbt 'kyo-ui/Test/runMain demo.LiveDashboardShot [outDir]'` (default outDir `/tmp`).
  */
object LiveDashboardShot extends KyoApp:

    private val W = 1200
    private val H = 800

    run {
        val outDir = if args.nonEmpty then args(0) else "/tmp"
        for
            handlers <- UI.runHandlers("/")(LiveDashboard.app)
            server   <- HttpServer.init(0, "localhost")(handlers*)
            url = s"http://localhost:${server.port}/"
            _    <- Console.printLine(s"LiveDashboardShot serving $url")
            base <- Browser.chromeForTestingLaunchConfig()
            launch = base.copy(extraArgs = base.extraArgs ++ Chunk("--window-size=1200,800", "--hide-scrollbars"))
            _ <- Browser.run(launch) {
                for
                    _ <- Browser.goto(url)
                    _ <- Async.sleep(1500.millis) // initial render + one or two engine ticks
                    a <- Browser.screenshot(W, H)
                    _ <- a.writeFileBinary(s"$outDir/dashboard-1.png")
                    _ <- Console.printLine(s"wrote $outDir/dashboard-1.png")

                    _ <- Async.sleep(3.seconds) // let the engine random-walk the metrics
                    b <- Browser.screenshot(W, H)
                    _ <- b.writeFileBinary(s"$outDir/dashboard-2.png")
                    _ <- Console.printLine(s"wrote $outDir/dashboard-2.png")
                yield ()
            }
            _ <- server.close
        yield ()
        end for
    }
end LiveDashboardShot
