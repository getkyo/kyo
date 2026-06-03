package demo

import kyo.*

/** Serves `Snake.app` in-process and captures four frames that exercise the game's state machine, so a human can
  * confirm it actually plays:
  *
  *   - `snake-1.png`: the initial board (snake centered, waiting for input).
  *   - `snake-2.png`: after steering down then right (alive; proves keyboard input drives turns and movement).
  *   - `snake-3.png`: after heading into a wall (the "Game over" state, proving collision detection).
  *   - `snake-4.png`: after pressing Space (restarted: snake recentered, back to the waiting state).
  *
  * The board container is the only focusable element (`tabIndex(0)`), so `[tabindex]` selects it; `Browser.press`
  * then dispatches keys against it. Sleeps are sized well past the 120ms tick so each transition is reached
  * regardless of scheduling jitter (e.g. the wall is < 16 ticks away; the 2.2s sleep guarantees the crash).
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

                    // Steer: head down a few cells, then turn right and run a few more ticks (alive).
                    _ <- Browser.focus(Browser.Selector.css("[tabindex]"))
                    _ <- Browser.press(Browser.Key.ArrowDown)
                    _ <- Async.sleep(600.millis)
                    _ <- Browser.press(Browser.Key.ArrowRight)
                    _ <- Async.sleep(400.millis)
                    b <- Browser.screenshot(W, H)
                    _ <- b.writeFileBinary(s"$outDir/snake-2.png")
                    _ <- Console.printLine(s"wrote $outDir/snake-2.png")

                    // Drive into the top wall: heading up from mid-board crashes within ~16 ticks; wait well past that.
                    _ <- Browser.press(Browser.Key.ArrowUp)
                    _ <- Async.sleep(2200.millis)
                    c <- Browser.screenshot(W, H)
                    _ <- c.writeFileBinary(s"$outDir/snake-3.png")
                    _ <- Console.printLine(s"wrote $outDir/snake-3.png")

                    // Restart with Enter: back to the centered, waiting state. (The headless harness encodes the
                    // spacebar's `key` as "Space" rather than the real " ", so Enter is the faithful restart key here.)
                    _ <- Browser.press(Browser.Key.Enter)
                    _ <- Async.sleep(400.millis)
                    d <- Browser.screenshot(W, H)
                    _ <- d.writeFileBinary(s"$outDir/snake-4.png")
                    _ <- Console.printLine(s"wrote $outDir/snake-4.png")
                yield ()
            }
            _ <- server.close
        yield ()
        end for
    }
end SnakeShot
