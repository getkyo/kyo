package demo

import kyo.*

/** Serves `BenchViz.app` in-process and drives one headless Chrome through its features, writing a PNG per state.
  *
  * One process (server + browser together) so it never contends with a separate sbt run for the project lock.
  *
  * Usage: `sbt 'kyo-ui/Test/runMain demo.BenchVizShot [outDir]'` (default outDir `/tmp`).
  */
object BenchVizShot extends KyoApp:

    private val W = 1440
    private val H = 1000

    run {
        val outDir = if args.nonEmpty then args(0) else "/tmp"
        for
            handlers <- UI.runHandlers("/")(BenchViz.app)
            server   <- HttpServer.init(0, "localhost")(handlers*)
            url = s"http://localhost:${server.port}/"
            _    <- Console.printLine(s"BenchVizShot serving $url")
            base <- Browser.chromeForTestingLaunchConfig()
            launch = base.copy(extraArgs = base.extraArgs ++ Chunk("--window-size=1440,1000", "--hide-scrollbars"))
            _ <- Browser.run(launch) {
                for
                    _ <- Browser.goto(url)
                    _ <- Async.sleep(2.seconds) // initial render + jmh.morethan.io iframe load
                    a <- Browser.screenshot(W, H)
                    _ <- a.writeFileBinary(s"$outDir/benchviz-1-initial.png")
                    _ <- Console.printLine(s"wrote $outDir/benchviz-1-initial.png")

                    // Multi-select: add a second gist run (click the non-link Message cell so it toggles the row,
                    // not the View link). Both gist sources are CORS-friendly, so jmh.morethan.io compares them.
                    _ <- Browser.click(Browser.Selector.id("msg-3a94bff"))
                    _ <- Async.sleep(2.seconds)
                    b <- Browser.screenshot(W, H)
                    _ <- b.writeFileBinary(s"$outDir/benchviz-2-multiselect.png")
                    _ <- Console.printLine(s"wrote $outDir/benchviz-2-multiselect.png")

                    // Flamegraph modal for the default run (gist-hosted flamegraphs).
                    _ <- Browser.click(Browser.Selector.id("view-5b21637"))
                    _ <- Async.sleep(600.millis)
                    c <- Browser.screenshot(W, H)
                    _ <- c.writeFileBinary(s"$outDir/benchviz-3-flamegraph-dialog.png")
                    _ <- Console.printLine(s"wrote $outDir/benchviz-3-flamegraph-dialog.png")

                    // Open a flamegraph; the dialog closes and the iframe loads the flamegraph HTML.
                    _ <- Browser.click(Browser.Selector.id("open-BroadFlatMapBench-forkKyo"))
                    _ <- Async.sleep(2.seconds)
                    d <- Browser.screenshot(W, H)
                    _ <- d.writeFileBinary(s"$outDir/benchviz-4-flamegraph.png")
                    _ <- Console.printLine(s"wrote $outDir/benchviz-4-flamegraph.png")
                yield ()
            }
            _ <- server.close
        yield ()
        end for
    }
end BenchVizShot
