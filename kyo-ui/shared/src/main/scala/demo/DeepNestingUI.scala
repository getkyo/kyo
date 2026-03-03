package demo

import kyo.*
import scala.language.implicitConversions

/** foreach inside foreach inside Signal[UI] — tests zombie fiber cleanup at multiple nesting levels. */
object DeepNestingUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val btn = Style.bg("#059669").color(Color.white).padding(8, 20).rounded(4)
        .borderStyle(_.none).cursor(_.pointer)
    private val btnSm = Style.padding(4, 12).rounded(4).border(1, "#ccc").cursor(_.pointer).fontSize(12)

    def build: UI < Async =
        for
            categories  <- Signal.initRef(Chunk("Fruits", "Vegetables"))
            fruitsItems <- Signal.initRef(Chunk("Apple", "Banana"))
            vegItems    <- Signal.initRef(Chunk("Carrot", "Pea"))
            showInner   <- Signal.initRef(true)
            outerMode   <- Signal.initRef("list") // "list" or "grid"
            clickCount  <- Signal.initRef(0)
        yield div.style(app)(
            header.style(Style.bg("#059669").color(Color.white).padding(16, 32))(
                h1("Deep Nesting Tests")
            ),
            main.style(content)(
                // Level 1: Signal[UI] wrapping foreach wrapping per-item signals
                section.cls("signal-foreach").style(card)(
                    h3("Signal[UI] → foreach → per-item Signal"),
                    p.style(
                        Style.fontSize(13).color("#64748b")
                    )("Outer signal controls layout mode; inner foreach renders items with reactive counts:"),
                    div.style(Style.row.gap(8).margin(8, 0, 0, 0))(
                        button.cls("mode-btn").style(btn).onClick(
                            outerMode.getAndUpdate(m => if m == "list" then "grid" else "list").unit
                        )(outerMode.map(m => s"Switch to ${if m == "list" then "Grid" else "List"}")),
                        button.cls("add-fruit-btn").style(btnSm).onClick(
                            fruitsItems.getAndUpdate(_.append("Mango")).unit
                        )("Add Fruit"),
                        button.cls("add-veg-btn").style(btnSm).onClick(
                            vegItems.getAndUpdate(_.append("Spinach")).unit
                        )("Add Veg"),
                        button.cls("inc-btn").style(btnSm).onClick(
                            clickCount.getAndUpdate(_ + 1).unit
                        )("Increment Counter")
                    ),
                    div.cls("mode-display").style(Style.margin(8, 0, 0, 0))(
                        // This is a Signal[UI] that changes structure on mode switch
                        outerMode.map { mode =>
                            val containerStyle = if mode == "grid" then
                                Style.row.gap(16)
                            else
                                Style.gap(8)
                            (div.style(containerStyle)(
                                // foreach over categories — each category renders its own foreach
                                categories.foreach { cat =>
                                    val catItems = if cat == "Fruits" then fruitsItems else vegItems
                                    div.cls("category").style(Style.padding(12).bg("#f0fdf4").rounded(8).gap(4))(
                                        h3.style(Style.fontSize(14).bold)(cat),
                                        div.style(Style.gap(2))(
                                            catItems.foreachIndexed { (idx, item) =>
                                                div.cls("nested-item").style(Style.row.gap(8).padding(4, 8).bg(
                                                    if idx % 2 == 0 then "#ecfdf5" else "#ffffff"
                                                ).rounded(4))(
                                                    span(s"${idx + 1}. $item"),
                                                    // Reactive text driven by shared signal
                                                    span.style(Style.fontSize(11).color("#94a3b8"))(
                                                        clickCount.map(c => s"(clicks: $c)")
                                                    )
                                                )
                                            }
                                        )
                                    )
                                }
                            )): UI
                        }
                    )
                ),
                // Level 2: UI.when inside foreach
                section.cls("when-in-foreach").style(card)(
                    h3("UI.when Inside foreach"),
                    p.style(Style.fontSize(13).color("#64748b"))("Toggle inner content while foreach is active:"),
                    button.cls("inner-toggle-btn").style(btn.margin(0, 0, 8, 0)).onClick(showInner.getAndUpdate(!_).unit)(
                        showInner.map(v => if v then "Hide Details" else "Show Details")
                    ),
                    div.cls("when-foreach-list").style(Style.gap(4))(
                        fruitsItems.foreachKeyed(identity) { item =>
                            div.style(Style.padding(8).bg("#fef3c7").rounded(4))(
                                span.style(Style.bold)(item),
                                UI.when(showInner)(
                                    div.cls("detail").style(Style.fontSize(12).color("#92400e").margin(4, 0, 0, 0))(
                                        s"Detail for $item — expanded"
                                    )
                                )
                            )
                        }
                    )
                ),
                // Level 3: Multiple toggle cycles
                section.cls("cycle-test").style(card)(
                    h3("Repeated Toggle Cycles"),
                    p.style(Style.fontSize(13).color("#64748b"))("Toggle visibility 6+ times to test fiber cleanup:"),
                    div.style(Style.row.gap(8).margin(8, 0, 0, 0))(
                        button.cls("cycle-btn").style(btn).onClick(showInner.getAndUpdate(!_).unit)("Toggle"),
                        span.cls("cycle-status").style(Style.bold)(
                            showInner.map(v => if v then "Visible" else "Hidden")
                        )
                    ),
                    UI.when(showInner)(
                        div.cls("cycle-content").style(Style.padding(12).bg("#ede9fe").rounded(8).margin(8, 0, 0, 0).gap(4))(
                            categories.foreach { cat =>
                                div(
                                    span.style(Style.bold)(s"$cat: "),
                                    span(clickCount.map(c => s"$c updates"))
                                )
                            }
                        )
                    )
                )
            )
        )

end DeepNestingUI
