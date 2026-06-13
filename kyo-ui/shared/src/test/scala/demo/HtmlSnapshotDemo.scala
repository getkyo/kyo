package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*

/** Renders a `UI` to HTML with `UI.runRender`, the third runner, for SSR, snapshot export, tests, or a custom transport.
  *
  * `runRender` returns a `Stream[String, Async]` whose first element is the full server-side render of the page (subsequent elements are
  * fresh full-page renders on any signal change). This demo takes that first frame, the canonical SSR snapshot, and prints it. The same
  * `UI` value would mount to the DOM with `UI.runMount` or drive a server-push app with `UI.runHandlers`; only the runner changes.
  *
  * Run via `sbt 'kyo-uiJVM/Test/runMain demo.HtmlSnapshot'`.
  *
  * Demonstrates: `UI.runRender` for SSR, and that one `UI` value renders to plain HTML with no DOM or browser.
  */
object HtmlSnapshotDemo extends KyoApp:

    case class Product(name: String, price: String)

    private val catalog = Seq(
        Product("Mechanical Keyboard", "$89.00"),
        Product("Wireless Mouse", "$35.00"),
        Product("27\" Monitor", "$249.00")
    )

    private val page: UI =
        UI.main.style(Style.padding(24.px).fontFamily(FontFamily.SansSerif).gap(12.px))(
            UI.h1("Product Catalog"),
            UI.p("Server-side rendered by UI.runRender, no DOM and no browser."),
            UI.table(
                (UI.tr(UI.th("Product"), UI.th("Price")) +: catalog.map(p => UI.tr(UI.td(p.name), UI.td(p.price))))
                    .map(UI.Ast.HtmlChildVal.lift(_))*
            ),
            UI.footer.style(Style.color(Color.gray).fontSize(13.px))(UI.p(s"${catalog.size} products"))
        )

    run {
        for
            frame <- UI.runRender(page).take(1).run
            html = frame.headMaybe.getOrElse("(no frame)")
            _ <- Console.printLine("=== UI.runRender SSR frame ===")
            _ <- Console.printLine(html)
        yield ()
    }
end HtmlSnapshotDemo
