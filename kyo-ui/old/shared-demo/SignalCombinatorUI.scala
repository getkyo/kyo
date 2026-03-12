package demo
import kyo.*
import kyo.UIDsl.*
import scala.language.implicitConversions

/** Tests deriving UI from multiple combined signals — ordering and race conditions. */
object SignalCombinatorUI:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val btn = Style.bg("#2563eb").color(Color.white).padding(8, 20).rounded(4)
        .borderStyle(_.none).cursor(_.pointer)
    private val tag = Style.bg("#e0e7ff").padding(4, 12).rounded(12)

    def build: UI < Async =
        for
            firstName <- Signal.initRef("")
            lastName  <- Signal.initRef("")
            quantity  <- Signal.initRef(1)
            price     <- Signal.initRef(10)
            filterA   <- Signal.initRef(true)
            filterB   <- Signal.initRef(true)
            items     <- Signal.initRef(Chunk("Alpha", "Beta", "Gamma", "Delta"))
        yield div.style(app)(
            header.style(Style.bg("#0891b2").color(Color.white).padding(16, 32))(
                h1("Signal Combinator Tests")
            ),
            main.style(content)(
                // Two signals combined into one display via nested Signal[UI] wrapping Signal[String]
                section.cls("combined-name").style(card)(
                    h3("Combined Signals: Name"),
                    p.style(Style.fontSize(13).color("#64748b"))("Two inputs drive one derived display:"),
                    div.style(Style.row.gap(8).margin(8, 0, 0, 0))(
                        input.cls("first-name").value(firstName).onInput(firstName.set(_)).placeholder("First name"),
                        input.cls("last-name").value(lastName).onInput(lastName.set(_)).placeholder("Last name")
                    ),
                    // Outer signal produces UI containing inner reactive text
                    div.cls("full-name").style(Style.padding(12).bg("#f0fdf4").rounded(8).margin(8, 0, 0, 0).bold.fontSize(18))(
                        firstName.map { f =>
                            // This returns Signal[String] → ReactiveText, wrapped in Signal[UI] → ReactiveNode
                            span(lastName.map { l =>
                                val full = s"$f $l".trim
                                if full.isEmpty then "Enter a name..." else full
                            }): UI
                        }
                    )
                ),
                // Two numeric signals combined: quantity * price
                section.cls("computed").style(card)(
                    h3("Computed Value: Quantity × Price"),
                    div.style(Style.row.gap(16).align(_.center).margin(8, 0, 0, 0))(
                        div.style(Style.gap(4))(
                            label("Quantity:"),
                            div.style(Style.row.gap(8).align(_.center))(
                                button.cls("qty-minus").style(btn).onClick(quantity.getAndUpdate(q => math.max(0, q - 1)).unit)("-"),
                                span.cls("qty-value").style(Style.bold.fontSize(18).minWidth(30).textAlign(_.center))(
                                    quantity.map(_.toString)
                                ),
                                button.cls("qty-plus").style(btn).onClick(quantity.getAndUpdate(_ + 1).unit)("+")
                            )
                        ),
                        div.style(Style.gap(4))(
                            label("Price:"),
                            div.style(Style.row.gap(8).align(_.center))(
                                button.cls("price-minus").style(btn).onClick(price.getAndUpdate(p => math.max(0, p - 5)).unit)("-5"),
                                span.cls("price-value").style(Style.bold.fontSize(18).minWidth(40).textAlign(_.center))(
                                    price.map(p => s"$$${p}")
                                ),
                                button.cls("price-plus").style(btn).onClick(price.getAndUpdate(_ + 5).unit)("+5")
                            )
                        )
                    ),
                    div.cls("total").style(Style.padding(12).bg("#fef3c7").rounded(8).margin(8, 0, 0, 0).bold.fontSize(20))(
                        quantity.map { q =>
                            span(price.map { p =>
                                s"Total: $$${q * p}"
                            }): UI
                        }
                    )
                ),
                // Two boolean filters combined — filterA is outer Signal[UI], filterB is inner
                section.cls("filters").style(card)(
                    h3("Combined Filters"),
                    p.style(Style.fontSize(13).color("#64748b"))("Two toggles filter the same list:"),
                    div.style(Style.row.gap(16).margin(8, 0, 0, 0))(
                        div.style(Style.row.gap(4).align(_.center))(
                            input.cls("filter-a").typ("checkbox").checked(filterA).onInput(_ => filterA.getAndUpdate(!_).unit),
                            label("Show A-C (Alpha, Beta, Gamma)")
                        ),
                        div.style(Style.row.gap(4).align(_.center))(
                            input.cls("filter-b").typ("checkbox").checked(filterB).onInput(_ => filterB.getAndUpdate(!_).unit),
                            label("Show D+ (Delta)")
                        )
                    ),
                    // Each item checks both filters. Outer: filterA Signal[UI], inner: filterB Signal[UI]
                    div.cls("filter-results").style(Style.row.gap(4).margin(8, 0, 0, 0))(
                        items.foreach { item =>
                            val isAC = Seq("Alpha", "Beta", "Gamma").contains(item)
                            if isAC then
                                UI.when(filterA)(span.style(tag)(item))
                            else
                                UI.when(filterB)(span.style(tag)(item))
                            end if
                        }
                    )
                ),
                // Rapid sequential updates to same signal
                section.cls("rapid").style(card)(
                    h3("Rapid Sequential Updates"),
                    p.style(Style.fontSize(13).color("#64748b"))("Click sets quantity to 0 then increments 5 times:"),
                    button.cls("rapid-btn").style(btn).onClick {
                        for
                            _ <- quantity.set(0)
                            _ <- quantity.getAndUpdate(_ + 1)
                            _ <- quantity.getAndUpdate(_ + 1)
                            _ <- quantity.getAndUpdate(_ + 1)
                            _ <- quantity.getAndUpdate(_ + 1)
                            _ <- quantity.getAndUpdate(_ + 1)
                        yield ()
                    }("Reset & Add 5"),
                    div.cls("rapid-result").style(Style.padding(8).margin(8, 0, 0, 0).bg("#f0f9ff").rounded(4))(
                        quantity.map(q => s"Quantity after rapid updates: $q")
                    )
                )
            )
        )

end SignalCombinatorUI
