package demo

import kyo.*
import scala.language.implicitConversions

object JavaFxDemoApp extends KyoApp with UIScope:

    run {
        for
            count    <- Signal.initRef(0)
            todoText <- Signal.initRef("")
            todos    <- Signal.initRef(Chunk.empty[String])
            darkMode <- Signal.initRef(false)
            session <- new JavaFxBackend(title = "Kyo UI Demo", width = 800, height = 600).render(
                div.cls("app").clsWhen("dark", darkMode)(
                    header.cls("header")(
                        h1("Kyo UI Demo"),
                        nav(
                            a.href("#")("Home"),
                            a.href("#")("About"),
                            a.href("#")("Contact")
                        )(
                            button.cls("theme-toggle").onClick(darkMode.getAndUpdate(!_).unit)("Toggle Theme")
                        )
                    ),
                    main.cls("content")(
                        section.cls("hero")(
                            h2("Welcome to Kyo UI"),
                            p("A pure, type-safe UI library for Scala")
                        ),
                        section.cls("counter")(
                            h3("Counter"),
                            div.cls("counter-row")(
                                button.cls("counter-btn")("-").onClick(count.getAndUpdate(_ - 1).unit),
                                span.cls("counter-value")(count.map(_.toString)),
                                button.cls("counter-btn")("+").onClick(count.getAndUpdate(_ + 1).unit)
                            )
                        ),
                        section.cls("todo-section")(
                            h3("Todo List"),
                            div.cls("todo-input")(
                                input.value(todoText).onInput(todoText.set(_)).placeholder("What needs to be done?"),
                                button.cls("submit")("Add").onClick {
                                    for
                                        t <- todoText.get
                                        _ <-
                                            if t.nonEmpty then todos.getAndUpdate(_.append(t)).unit
                                            else ((): Unit < Sync)
                                        _ <- todoText.set("")
                                    yield ()
                                }
                            ),
                            ul.cls("todo-list")(
                                todos.foreachIndexed((idx, todo) =>
                                    li.cls("todo-item")(
                                        span(todo),
                                        button.cls("delete-btn")("x").onClick(
                                            todos.getAndUpdate(c => c.take(idx) ++ c.drop(idx + 1)).unit
                                        )
                                    )
                                )
                            )
                        ),
                        section.cls("table-demo")(
                            h3("Data Table"),
                            table(
                                tr(th("Name"), th("Role"), th("Status")),
                                tr(td("Alice"), td("Engineer"), td("Active")),
                                tr(td("Bob"), td("Designer"), td("Away")),
                                tr(td("Charlie"), td("Manager"), td("Active"))
                            )
                        )
                    ),
                    footer(
                        p("Built with Kyo UI")
                    )
                )
            )
            _ <- session.await
        yield ()
    }
end JavaFxDemoApp
