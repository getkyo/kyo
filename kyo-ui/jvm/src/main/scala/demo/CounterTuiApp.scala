package demo

import kyo.*
import scala.language.implicitConversions

object CounterTuiApp extends KyoApp with UIScope:

    run {
        for
            count   <- Signal.initRef(0)
            text    <- Signal.initRef("")
            items   <- Signal.initRef(Chunk.empty[String])
            tab     <- Signal.initRef(0)
            session <- TuiBackend.render(ui(count, text, items, tab))
            _       <- session.await
        yield ()
    }

    private def ui(
        count: SignalRef[Int],
        text: SignalRef[String],
        items: SignalRef[Chunk[String]],
        tab: SignalRef[Int]
    )(using Frame): UI =
        div(
            // 1. Heading
            h1("Kyo UI Feature Demo"),
            hr,

            // 2. Inline layout (nav = row)
            h3("Counter"),
            nav(
                button("-").onClick(count.getAndUpdate(_ - 1).unit),
                span.style(Style.bold.padding(0, 8))(count.map(_.toString)),
                button("+").onClick(count.getAndUpdate(_ + 1).unit)
            ),
            br,

            // 3. Text input
            h3("Text Input"),
            nav(
                input.value(text).placeholder("Type something..."),
                span(" => "),
                span(text.map(v => if v.isEmpty then "(empty)" else v))
            ),
            br,

            // 4. Todo list
            h3("Todo List"),
            nav(
                input.value(text).placeholder("Add item..."),
                button("Add").onClick {
                    for
                        t <- text.get
                        _ <-
                            if t.nonEmpty then items.getAndUpdate(_.append(t)).unit
                            else ((): Unit < Sync)
                        _ <- text.set("")
                    yield ()
                }
            ),
            ul(
                items.foreachIndexed((idx, item) =>
                    li(
                        nav(
                            span(item),
                            button("x").onClick(
                                items.getAndUpdate(c => c.take(idx) ++ c.drop(idx + 1)).unit
                            )
                        )
                    )
                )
            ),
            br,

            // 5. Styles: colors, bold, italic, underline
            h3("Styles"),
            p.style(Style.bold)("Bold text"),
            p.style(Style.italic)("Italic text"),
            p.style(Style.underline)("Underlined text"),
            p.style(Style.color("#ff0000"))("Red text"),
            p.style(Style.bg("#0000ff").color("#ffffff"))("White on blue"),
            br,

            // 6. Table
            h3("Table"),
            table(
                tr(th("Name"), th(" Age"), th(" City")),
                tr(td("Alice"), td(" 30"), td(" NYC")),
                tr(td("Bob"), td(" 25"), td(" SF"))
            ),
            br,

            // 7. Borders
            h3("Borders"),
            nav(
                div.style(
                    Style.border(8, Style.BorderStyle.solid, "#888").padding(8).rounded(8)
                )("Rounded"),
                span(" "),
                div.style(
                    Style.border(8, Style.BorderStyle.dashed, "#888").padding(8)
                )("Dashed")
            ),
            br,

            // 8. Tabs (reactive visibility)
            h3("Tabs"),
            nav(
                button("Tab 1").onClick(tab.set(0)),
                button("Tab 2").onClick(tab.set(1)),
                button("Tab 3").onClick(tab.set(2))
            ),
            UI.when(tab.map(_ == 0))(p("Content of tab 1: Hello!")),
            UI.when(tab.map(_ == 1))(p("Content of tab 2: World!")),
            UI.when(tab.map(_ == 2))(p("Content of tab 3: Kyo UI!"))
        )

end CounterTuiApp
