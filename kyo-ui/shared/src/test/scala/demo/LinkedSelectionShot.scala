package demo

import kyo.*

/** Serves `LinkedSelection.app` in-process and captures the linked-views interaction as two PNGs.
  *
  * `linked-1.png` is the initial state (no selection: the detail panel reads "Click a bar to drill in").
  * Then it clicks one bar in the left chart and writes `linked-2.png`, where the detail line and title now
  * show that category. Comparing the two frames shows the shared `SignalRef` driving the second chart.
  *
  * A clickable bar is a `<rect>` carrying `data-kyo-ev="click"` (the only rects with a handler are the bars;
  * the background rect has none), so `rect[data-kyo-ev]` selects the first bar.
  *
  * Usage: `sbt 'kyo-ui/Test/runMain demo.LinkedSelectionShot [outDir]'` (default outDir `/tmp`).
  */
object LinkedSelectionShot extends KyoApp:

    private val W = 960
    private val H = 460

    run {
        val outDir = if args.nonEmpty then args(0) else "/tmp"
        for
            handlers <- UI.runHandlers("/")(LinkedSelection.app)
            server   <- HttpServer.init(0, "localhost")(handlers*)
            url = s"http://localhost:${server.port}/"
            _    <- Console.printLine(s"LinkedSelectionShot serving $url")
            base <- Browser.chromeForTestingLaunchConfig()
            launch = base.copy(extraArgs = base.extraArgs ++ Chunk("--window-size=960,460", "--hide-scrollbars"))
            _ <- Browser.run(launch) {
                for
                    _ <- Browser.goto(url)
                    _ <- Async.sleep(1.second) // initial render settle
                    a <- Browser.screenshot(W, H)
                    _ <- a.writeFileBinary(s"$outDir/linked-1.png")
                    _ <- Console.printLine(s"wrote $outDir/linked-1.png")

                    // Click the first bar; .onSelect writes that Cat into the shared ref and the detail chart reads it.
                    _ <- Browser.click(Browser.Selector.css("rect[data-kyo-ev]"))
                    _ <- Async.sleep(600.millis)
                    b <- Browser.screenshot(W, H)
                    _ <- b.writeFileBinary(s"$outDir/linked-2.png")
                    _ <- Console.printLine(s"wrote $outDir/linked-2.png")
                yield ()
            }
            _ <- server.close
        yield ()
        end for
    }
end LinkedSelectionShot
