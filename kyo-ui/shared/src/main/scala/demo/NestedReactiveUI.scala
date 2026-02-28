package demo

import kyo.*
import scala.language.implicitConversions

object NestedReactiveUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val btn = Style.bg("#2563eb").color(Color.white).padding(8, 20).rounded(4)
        .borderStyle(_.none).cursor(_.pointer)
    private val btnSm = Style.padding(4, 12).rounded(4).border(1, "#ccc").cursor(_.pointer)

    def build: UI < Async =
        for
            outerShow  <- Signal.initRef(true)
            innerShow  <- Signal.initRef(true)
            items      <- Signal.initRef(Chunk("Alpha", "Beta", "Gamma"))
            selected   <- Signal.initRef("")
            counter    <- Signal.initRef(0)
            filterOn   <- Signal.initRef(false)
            nestedMode <- Signal.initRef(true) // true = mode A, false = mode B
        yield div.style(app)(
            header.style(Style.bg("#be123c").color(Color.white).padding(16, 32))(
                h1("Nested Reactive Showcase")
            ),
            main.style(content)(
                // Nested when: outer controls inner visibility
                section.style(card)(
                    h3("Nested when() inside when()"),
                    div.style(Style.row.gap(8))(
                        button.style(btn).onClick(outerShow.getAndUpdate(!_).unit)(
                            outerShow.map(v => if v then "Hide Outer" else "Show Outer")
                        ),
                        button.style(btn).onClick(innerShow.getAndUpdate(!_).unit)(
                            innerShow.map(v => if v then "Hide Inner" else "Show Inner")
                        )
                    ),
                    UI.when(outerShow)(
                        div.style(Style.bg("#fef2f2").padding(16).rounded(8).margin(12, 0, 0, 0))(
                            p("Outer panel visible"),
                            UI.when(innerShow)(
                                div.style(Style.bg("#fee2e2").padding(12).rounded(6).margin(8, 0, 0, 0).border(1, "#fca5a5"))(
                                    p.style(Style.bold)("Inner panel visible"),
                                    p("Both conditions must be true to see this.")
                                )
                            )
                        )
                    )
                ),
                // foreach with reactive children (each item has its own signal-driven content)
                section.style(card)(
                    h3("foreach with Signal children"),
                    p.style(Style.fontSize(13).color("#64748b"))("Each item has a click counter inside foreach:"),
                    div.style(Style.row.gap(8).margin(8, 0, 0, 0))(
                        button.style(btnSm).onClick(counter.getAndUpdate(_ + 1).unit)("Increment All"),
                        span.style(Style.padding(4, 8))(counter.map(c => s"Global count: $c"))
                    ),
                    div.style(Style.gap(8).margin(8, 0, 0, 0))(
                        items.foreach { item =>
                            div.style(Style.row.gap(8).align(_.center).bg("#f8fafc").padding(8).rounded(4))(
                                span.style(Style.bold)(item),
                                span(" — global: "),
                                span(counter.map(_.toString))
                            )
                        }
                    )
                ),
                // foreachKeyed with selection state
                section.style(card)(
                    h3("foreachKeyed with selection"),
                    p.style(Style.fontSize(13).color("#64748b"))("Click an item to select it:"),
                    div.style(Style.gap(4).margin(8, 0, 0, 0))(
                        items.foreachKeyed(identity) { item =>
                            div.style(Style.padding(8, 12).rounded(4).border(1, "#e2e8f0").cursor(_.pointer))
                                .onClick(selected.set(item))(
                                    span(item),
                                    UI.when(selected.map(_ == item))(
                                        span.style(Style.color("#2563eb").bold.margin(0, 0, 0, 8))(" (selected)")
                                    )
                                )
                        }
                    ),
                    div.style(Style.margin(8, 0, 0, 0).padding(8).bg("#f0fdf4").rounded(4))(
                        p(selected.map(s => if s.isEmpty then "Nothing selected" else s"Selected: $s"))
                    )
                ),
                // Signal[UI] inside foreach-driven container
                section.style(card)(
                    h3("Signal[UI] nested in collection"),
                    div.style(Style.row.gap(8))(
                        button.style(btn).onClick(nestedMode.getAndUpdate(!_).unit)(
                            nestedMode.map(v => if v then "Switch to Tags" else "Switch to List")
                        )
                    ),
                    div.style(Style.margin(8, 0, 0, 0))(
                        nestedMode.map { isList =>
                            if isList then
                                ul(
                                    items.foreach(item => li.style(Style.padding(4, 0))(item))
                                )
                            else
                                div.style(Style.row.gap(4))(
                                    items.foreach(item =>
                                        span.style(Style.bg("#e0e7ff").padding(4, 12).rounded(12))(item)
                                    )
                                )
                        }: Signal[UI]
                    )
                ),
                // Conditional rendering with filter
                section.style(card)(
                    h3("Filtered collection"),
                    div.style(Style.row.gap(8).align(_.center))(
                        button.style(btnSm).onClick(filterOn.getAndUpdate(!_).unit)(
                            filterOn.map(v => if v then "Show All" else "Filter (A only)")
                        ),
                        span.style(Style.fontSize(13).color("#64748b"))(
                            filterOn.map(v => if v then "Showing items starting with 'A'" else "Showing all")
                        )
                    ),
                    ul.style(Style.margin(8, 0, 0, 0))(
                        filterOn.map { on =>
                            if on then
                                items.foreach { item =>
                                    // Simple filter: only show items starting with "A"
                                    if item.startsWith("A") then
                                        li.style(Style.padding(4, 0))(item)
                                    else
                                        empty
                                }
                            else
                                items.foreach(item => li.style(Style.padding(4, 0))(item))
                        }: Signal[UI]
                    )
                )
            )
        )

end NestedReactiveUI
