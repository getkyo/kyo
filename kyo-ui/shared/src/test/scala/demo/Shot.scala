package demo

import kyo.*

/** Generic screenshot tool used to visually validate the demos.
  *
  * Points a headless Chrome (via kyo-browser) at a running demo server, waits for the initial render to settle, and writes a PNG.
  *
  * Usage: `sbt 'kyo-ui/Test/runMain demo.Shot <url> <outPath> [width] [height] [settleMillis]'`.
  */
object Shot extends KyoApp:
    run {
        val url    = args(0)
        val out    = args(1)
        val width  = if args.length > 2 then args(2).toInt else 1200
        val height = if args.length > 3 then args(3).toInt else 900
        val settle = if args.length > 4 then args(4).toInt else 800
        Browser.run {
            for
                _   <- Browser.goto(url)
                _   <- Async.sleep(settle.millis)
                img <- Browser.screenshot(width, height)
                _   <- img.writeFileBinary(out)
                _   <- Console.printLine(s"wrote $out (${width}x$height)")
            yield ()
        }
    }
end Shot
