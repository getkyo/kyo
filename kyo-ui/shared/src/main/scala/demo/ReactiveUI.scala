package demo

import kyo.*
import scala.language.implicitConversions

object ReactiveUI extends UIScope:

    import DemoStyles.app
    import DemoStyles.card
    import DemoStyles.content

    private val tagStyle = Style.bg("#e0e7ff").padding(4, 12).rounded(12).margin(0, 4)

    def build: UI < Async =
        for
            showPanel <- Signal.initRef(true)
            isHidden  <- Signal.initRef(false)
            dynClass  <- Signal.initRef("style-a")
            items     <- Signal.initRef(Chunk("Apple", "Banana", "Cherry"))
            newItem   <- Signal.initRef("")
            viewMode  <- Signal.initRef(true) // true = list, false = grid
        yield div.style(app)(
            header.style(Style.bg("#059669").color(Color.white).padding(16, 32))(
                h1("Reactive Showcase")
            ),
            main.style(content)(
                // Conditional render with UI.when
                section.style(card)(
                    h3("Conditional Rendering (UI.when)"),
                    button.style(
                        Style.bg("#2563eb").color(Color.white).padding(8, 20).rounded(4)
                            .borderStyle(_.none).cursor(_.pointer)
                    ).onClick(showPanel.getAndUpdate(!_).unit)(
                        showPanel.map(v => if v then "Hide Panel" else "Show Panel")
                    ),
                    UI.when(showPanel)(
                        div.style(Style.bg("#dbeafe").padding(16).rounded(8).margin(12, 0, 0, 0))(
                            p("This panel is conditionally rendered!"),
                            p.style(Style.color("#2563eb").bold)("It appears and disappears.")
                        )
                    )
                ),
                // Hidden toggle
                section.style(card)(
                    h3("Visibility Toggle (hidden)"),
                    button.style(
                        Style.bg("#7c3aed").color(Color.white).padding(8, 20).rounded(4)
                            .borderStyle(_.none).cursor(_.pointer)
                    ).onClick(isHidden.getAndUpdate(!_).unit)(
                        isHidden.map(h => if h then "Show" else "Hide")
                    ),
                    div.style(Style.bg("#f3e8ff").padding(16).rounded(8).margin(12, 0, 0, 0))
                        .hidden(isHidden)(
                            p("This element uses hidden(signal) to toggle visibility.")
                        )
                ),
                // Dynamic class
                section.style(card)(
                    h3("Dynamic Class"),
                    div.style(Style.row.gap(8))(
                        button.style(
                            Style.padding(8, 16).rounded(4).border(1, "#ccc").cursor(_.pointer)
                        ).onClick(dynClass.set("style-a"))("Style A"),
                        button.style(
                            Style.padding(8, 16).rounded(4).border(1, "#ccc").cursor(_.pointer)
                        ).onClick(dynClass.set("style-b"))("Style B"),
                        button.style(
                            Style.padding(8, 16).rounded(4).border(1, "#ccc").cursor(_.pointer)
                        ).onClick(dynClass.set("style-c"))("Style C")
                    ),
                    div.cls(dynClass).style(Style.padding(16).rounded(8).margin(12, 0, 0, 0).bg("#f3f4f6"))(
                        p(dynClass.map(c => s"Current class: $c"))
                    )
                ),
                // foreach (simple)
                section.style(card)(
                    h3("Simple foreach"),
                    div.style(Style.row.gap(8).margin(0, 0, 12, 0))(
                        input.value(newItem).onInput(newItem.set(_)).placeholder("New item..."),
                        button.style(DemoStyles.submitBtn).onClick {
                            for
                                v <- newItem.get
                                _ <- if v.nonEmpty then items.getAndUpdate(_.append(v)).unit
                                else ((): Unit < Sync)
                                _ <- newItem.set("")
                            yield ()
                        }("Add")
                    ),
                    div.style(Style.row.gap(4))(
                        items.foreach(item => span.style(tagStyle)(item))
                    )
                ),
                // foreachKeyed
                section.style(card)(
                    h3("Keyed List (foreachKeyed)"),
                    ul(
                        items.foreachKeyed(identity)(item =>
                            li.style(Style.padding(4, 0))(item)
                        )
                    )
                ),
                // View mode swap (Signal[UI] via map)
                section.style(card)(
                    h3("View Mode Toggle"),
                    button.style(
                        Style.bg("#ea580c").color(Color.white).padding(8, 20).rounded(4)
                            .borderStyle(_.none).cursor(_.pointer)
                    ).onClick(viewMode.getAndUpdate(!_).unit)(
                        viewMode.map(v => if v then "Switch to Grid" else "Switch to List")
                    ),
                    viewMode.map { isList =>
                        if isList then
                            ul(
                                li("Item Alpha"),
                                li("Item Beta"),
                                li("Item Gamma")
                            )
                        else
                            div.style(Style.row.gap(8).margin(12, 0, 0, 0))(
                                div.style(Style.bg("#fed7aa").padding(16).rounded(8))("Alpha"),
                                div.style(Style.bg("#fed7aa").padding(16).rounded(8))("Beta"),
                                div.style(Style.bg("#fed7aa").padding(16).rounded(8))("Gamma")
                            )
                    }: Signal[UI]
                )
            )
        )

end ReactiveUI
