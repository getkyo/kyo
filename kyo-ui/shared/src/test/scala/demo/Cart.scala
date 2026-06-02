package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*

/** Shopping cart served as a server-push app.
  *
  * A fixed catalog on the left, a live cart on the right. Adding an item, changing a quantity, or removing a line updates a single
  * `SignalRef[Map[String, Int]]` on the server; the cart lines and the running total are derived signals, so the server pushes just the
  * affected DOM diffs over SSE.
  *
  * Run via `sbt 'kyo-ui/Test/runMain demo.Cart'` (optional port as the first argument).
  *
  * Demonstrates: a single source-of-truth `SignalRef`, derived `Signal`s via `.map` (cart lines and total), keyed list rendering with
  * `foreachKeyed`, per-row +/- steppers, `when` empty-state, and equal-column layout via `flexGrow(1).flexBasis(0.px)`.
  */
object Cart extends KyoApp:

    case class Product(id: String, name: String, cents: Int) derives CanEqual

    private val catalog = Chunk(
        Product("kbd", "Mechanical Keyboard", 8900),
        Product("mouse", "Wireless Mouse", 3500),
        Product("mon", "27\" Monitor", 24900),
        Product("dock", "USB-C Dock", 12900),
        Product("cam", "1080p Webcam", 5900)
    )

    private def priceOf(id: String): Int  = catalog.find(_.id == id).map(_.cents).getOrElse(0)
    private def money(cents: Int): String = f"$$${cents / 100.0}%.2f"

    private val pageStyle  = Style.padding(24.px).fontFamily(FontFamily.SansSerif).gap(16.px)
    private val columns    = Style.row.gap(16.px)
    private val panel      = Style.column.gap(10.px).padding(16.px).bg(Color.slate).rounded(10.px).flexGrow(1).flexBasis(0.px)
    private val rowStyle   = Style.row.gap(10.px).align(Alignment.center).padding(10.px).bg(Color.white).rounded(8.px)
    private val grow       = Style.flexGrow(1)
    private val mutedWhite = Style.color(Color.white)
    private val totalStyle = Style.row.gap(10.px).align(Alignment.center).padding(12.px).bg(Color.white).rounded(8.px)
    private val totalValue = Style.color(Color.green).bold.fontSize(22.px).flexGrow(1)
    private val stepBtn    = Style.padding(2.px, 10.px)

    private def catalogRow(p: Product, add: Product => Any < Async): UI.Ast.Li =
        li.style(rowStyle)(
            span(p.name).style(grow),
            span(money(p.cents)).style(Style.color(Color.gray)),
            button("Add").id(s"add-${p.id}").onClick(add(p))
        )

    private def cartRow(p: Product, qty: Int, setQty: (String, Int) => Any < Async): UI =
        li.style(rowStyle)(
            span(p.name).style(grow),
            button("-").id(s"dec-${p.id}").style(stepBtn).onClick(setQty(p.id, qty - 1)),
            span(qty.toString),
            button("+").id(s"inc-${p.id}").style(stepBtn).onClick(setQty(p.id, qty + 1)),
            span(money(p.cents * qty)).style(Style.color(Color.gray)),
            button("✕").id(s"rm-${p.id}").onClick(setQty(p.id, 0))
        )

    private def cartUI: UI < Async =
        for cart <- Signal.initRef(Map.empty[String, Int])
        yield
            // Set a product's quantity, dropping it from the cart at zero or below.
            val setQty = (id: String, qty: Int) =>
                cart.updateAndGet(c => if qty <= 0 then c - id else c.updated(id, qty)).unit
            val add = (p: Product) => cart.updateAndGet(c => c.updated(p.id, c.getOrElse(p.id, 0) + 1)).unit

            val lines = cart.map(c => catalog.filter(p => c.contains(p.id)).map(p => (p, c(p.id))))
            val total = cart.map(c => c.toSeq.map((id, qty) => priceOf(id) * qty).sum)

            UI.main.style(pageStyle)(
                h1("Shop"),
                div.style(columns)(
                    div.style(panel)(
                        h2("Catalog").style(mutedWhite),
                        ul.style(Style.column.gap(8.px))(
                            catalog.map(p => catalogRow(p, add))*
                        )
                    ),
                    div.style(panel)(
                        h2("Cart").style(mutedWhite),
                        when(lines.map(_.isEmpty))(p("Your cart is empty.").style(mutedWhite)),
                        ul.style(Style.column.gap(8.px))(
                            lines.foreachKeyed(_._1.id) { case (p, qty) => cartRow(p, qty, setQty) }
                        ),
                        div.style(totalStyle)(
                            span("Total").style(grow),
                            total.render(c => span(money(c)).style(totalValue))
                        )
                    )
                )
            )

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(cartUI)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"Cart running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end Cart
