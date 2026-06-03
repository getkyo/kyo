package demo

import kyo.*

/** Serves `Snake.app` in-process and captures two frames: the initial board (snake centered, waiting for input)
  * and the board after a few steered moves, so a human can confirm the game renders and responds to the keyboard.
  *
  * The board container is the only focusable element (`tabIndex(0)`), so `[tabindex]` selects it; `Browser.press`
  * then dispatches arrow keys against it.
  *
  * Usage: `sbt 'kyo-ui/Test/runMain demo.SnakeShot [outDir]'` (default outDir `/tmp`).
  */
object SnakeShot extends KyoApp:

    private val W = 480
    private val H = 580

    run {
        val outDir = if args.nonEmpty then args(0) else "/tmp"
        for
            handlers <- UI.runHandlers("/")(Snake.app)
            server   <- HttpServer.init(0, "localhost")(handlers*)
            url = s"http://localhost:${server.port}/"
            _    <- Console.printLine(s"SnakeShot serving $url")
            base <- Browser.chromeForTestingLaunchConfig()
            launch = base.copy(extraArgs = base.extraArgs ++ Chunk("--window-size=480,580", "--hide-scrollbars"))
            _ <- Browser.run(launch) {
                for
                    _ <- Browser.goto(url)
                    _ <- Async.sleep(700.millis) // initial render settle
                    a <- Browser.screenshot(W, H)
                    _ <- a.writeFileBinary(s"$outDir/snake-1.png")
                    _ <- Console.printLine(s"wrote $outDir/snake-1.png")

                    // Focus the board and steer: head down a few cells, then turn right and run a few more ticks.
                    _ <- Browser.focus(Browser.Selector.css("[tabindex]"))
                    _ <- Browser.press(Browser.Key.ArrowDown)
                    _ <- Async.sleep(700.millis)
                    _ <- Browser.press(Browser.Key.ArrowRight)
                    _ <- Async.sleep(500.millis)
                    b <- Browser.screenshot(W, H)
                    _ <- b.writeFileBinary(s"$outDir/snake-2.png")
                    _ <- Console.printLine(s"wrote $outDir/snake-2.png")
                yield ()
            }
            _ <- server.close
        yield ()
        end for
    }
end SnakeShot
