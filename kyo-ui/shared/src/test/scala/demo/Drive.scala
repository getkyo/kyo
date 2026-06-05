package demo

import kyo.*

/** Generic browser-driver used to validate demo interactivity end to end.
  *
  * Points a headless Chrome at a running demo server and runs a sequence of steps, so a click/fill that triggers a server-push diff can be
  * verified by a follow-up assertion or screenshot. Launches at a desktop window size so screenshots reflect a real wide viewport.
  *
  * Usage: `sbt 'kyo-ui/Test/runMain demo.Drive <url> <step> <step> ...'` where each step is `verb=arg` with `|`-separated sub-args:
  *   - `fill=#selector|text`        type into an input
  *   - `click=#selector`           click an element
  *   - `wait=400`                  sleep N milliseconds (let a server-push diff settle)
  *   - `assert=#selector|text`     wait until the element's text contains `text` (fails loudly otherwise)
  *   - `shot=/path|width|height`   write a PNG screenshot
  */
object Drive extends KyoApp:

    private def runSteps(steps: List[String])(using Frame): Unit < (Browser & Async & Abort[Throwable]) =
        Kyo.foreachDiscard(steps) { step =>
            step.split("=", 2) match
                case Array("fill", rest) =>
                    val parts = rest.split('|')
                    Browser.fill(Browser.Selector.css(parts(0)), parts(1))
                case Array("type", rest) =>
                    // Type character by character via real key events (fill sets the value in one shot, which
                    // would not exercise per-keystroke caret behaviour).
                    val parts = rest.split('|')
                    val sel   = Browser.Selector.css(parts(0))
                    val text  = if parts.length > 1 then parts(1) else ""
                    Browser.click(sel).andThen(Kyo.foreachDiscard(text.toList)(ch => Browser.press(sel, Browser.Key(ch))))
                case Array("evalp", js) =>
                    Browser.eval(js).map(r => Console.printLine(s"eval[$js] = $r"))
                case Array("valeq", rest) =>
                    val parts = rest.split('|')
                    Browser.value(Browser.Selector.css(parts(0))).map { v =>
                        if v == parts(1) then Console.printLine(s"ok: ${parts(0)} value == '${parts(1)}'")
                        else Abort.fail(new RuntimeException(s"FAIL: ${parts(0)} value='$v' expected='${parts(1)}'"))
                    }
                case Array("click", sel) =>
                    Browser.click(Browser.Selector.css(sel))
                case Array("wait", ms) =>
                    Async.sleep(ms.toInt.millis)
                case Array("assert", rest) =>
                    val parts = rest.split('|')
                    Browser.waitForText(Browser.Selector.css(parts(0)), (_: String).contains(parts(1)))
                        .andThen(Console.printLine(s"ok: ${parts(0)} contains '${parts(1)}'"))
                case Array("shot", rest) =>
                    val parts = rest.split('|')
                    val w     = if parts.length > 1 then parts(1).toInt else 1100
                    val h     = if parts.length > 2 then parts(2).toInt else 800
                    Browser.screenshotRegion(0, 0, w, h).map(_.writeFileBinary(parts(0)))
                        .andThen(Console.printLine(s"wrote ${parts(0)}"))
                case _ =>
                    Console.printLine(s"unknown step: $step")
        }

    run {
        val url   = args(0)
        val steps = args.drop(1).toList
        for
            base <- Browser.chromeForTestingLaunchConfig()
            launch = base.copy(extraArgs = base.extraArgs ++ Chunk("--window-size=1440,1000", "--hide-scrollbars"))
            _ <- Browser.run(launch)(Browser.goto(url).andThen(runSteps(steps)))
        yield ()
        end for
    }
end Drive
