package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*

/** Live HTML playground served as a server-push app, showcasing the `iframe` element.
  *
  * Type HTML in the textarea on the left; a derived `data:` URL feeds an `iframe` on the right that renders it live. The textarea is
  * two-way bound to a `SignalRef[String]`; the iframe's `src` is that signal mapped to a `data:` URL, so each edit re-points the frame.
  *
  * Run via `sbt 'kyo-ui/Test/runMain demo.Playground'` (optional port as the first argument).
  *
  * Demonstrates: the `iframe` element with a reactive `src`, two-way `textarea` binding, derived signals via `.map`, and equal-column
  * layout via `flexGrow(1).flexBasis(0.px)`.
  */
object PlaygroundDemo extends KyoApp:

    private val sampleHtml =
        """<!doctype html>
          |<body style="font-family: system-ui; padding: 16px">
          |  <h1 style="color:#3b82f6">Hello from an iframe</h1>
          |  <p>Edit the HTML on the left and this preview updates live.</p>
          |  <button onclick="this.textContent='clicked!'">Click me</button>
          |</body>""".stripMargin

    /** Encode HTML as a data: URL. Replace '+' (form-encoding space) with %20 so the data payload decodes correctly. */
    private def dataUrl(html: String): String =
        "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(html, "UTF-8").replace("+", "%20")

    private val pageStyle  = Style.padding(24.px).fontFamily(FontFamily.SansSerif).gap(12.px)
    private val subtitle   = Style.color(Color.gray).fontSize(14.px)
    private val columns    = Style.row.gap(16.px)
    private val panel      = Style.column.gap(8.px).flexGrow(1).flexBasis(0.px)
    private val panelTitle = Style.fontSize(15.px).bold
    private val editorStyle = Style.width(100.pct).height(360.px).padding(10.px).fontFamily(FontFamily.Monospace).fontSize(13.px)
        .rounded(8.px).border(1.px, Color.slate)
    private val previewStyle = Style.width(100.pct).height(360.px).rounded(8.px).border(1.px, Color.slate).bg(Color.white)

    private def playgroundUI: UI < Async =
        for html <- Signal.initRef(sampleHtml)
        yield UI.main.style(pageStyle)(
            h1("HTML Playground"),
            p("Type HTML on the left; the iframe on the right renders it live.").style(subtitle),
            div.style(columns)(
                div.style(panel)(
                    h2("HTML").style(panelTitle),
                    textarea.id("src").value(html).style(editorStyle)
                ),
                div.style(panel)(
                    h2("Preview").style(panelTitle),
                    iframe("about:blank").title("Live preview").style(previewStyle).src(html.map(dataUrl))
                )
            )
        )

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(playgroundUI)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"Playground running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end PlaygroundDemo
