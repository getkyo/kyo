package demo

import kyo.*
import scala.language.implicitConversions

object JavaFxDemoApp extends KyoApp with UIScope:

    import DemoStyles.*

    run {
        for
            count    <- Signal.initRef(0)
            todoText <- Signal.initRef("")
            todos    <- Signal.initRef(Chunk.empty[String])
            darkMode <- Signal.initRef(false)
            session <- new JavaFxBackend(title = "Kyo UI Demo", width = 800, height = 600).render(
                div.style(app)(
                    header.style(headerStyle)(
                        h1("Kyo UI Demo"),
                        nav.style(navStyle)(
                            a.href("#")("Home"),
                            a.href("#")("About"),
                            a.href("#")("Contact")
                        )(
                            button.style(themeToggle).onClick(darkMode.getAndUpdate(!_).unit)("Toggle Theme")
                        )
                    ),
                    main.style(content)(
                        section.style(card)(
                            h2("Welcome to Kyo UI"),
                            p("A pure, type-safe UI library for Scala")
                        ),
                        section.style(card)(
                            h3("Counter"),
                            div.style(counterRow)(
                                button.style(counterBtn)("-").onClick(count.getAndUpdate(_ - 1).unit),
                                span.style(counterValue)(count.map(_.toString)),
                                button.style(counterBtn)("+").onClick(count.getAndUpdate(_ + 1).unit)
                            )
                        ),
                        section.style(card)(
                            h3("Todo List"),
                            div.style(todoInput)(
                                input.value(todoText).onInput(todoText.set(_)).placeholder("What needs to be done?"),
                                button.style(submitBtn)("Add").onClick {
                                    for
                                        t <- todoText.get
                                        _ <-
                                            if t.nonEmpty then todos.getAndUpdate(_.append(t)).unit
                                            else ((): Unit < Sync)
                                        _ <- todoText.set("")
                                    yield ()
                                }
                            ),
                            ul.style(todoList)(
                                todos.foreachIndexed((idx, todo) =>
                                    li.style(todoItem)(
                                        span(todo),
                                        button.style(deleteBtn)("x").onClick(
                                            todos.getAndUpdate(c => c.take(idx) ++ c.drop(idx + 1)).unit
                                        )
                                    )
                                )
                            )
                        ),
                        section.style(card)(
                            h3("Data Table"),
                            table(
                                tr(th("Name"), th("Role"), th("Status")),
                                tr(td("Alice"), td("Engineer"), td("Active")),
                                tr(td("Bob"), td("Designer"), td("Away")),
                                tr(td("Charlie"), td("Manager"), td("Active"))
                            )
                        )
                    ),
                    footer.style(footerStyle)(
                        p("Built with Kyo UI")
                    )
                )
            )
            _ <- session.await
        yield ()
    }
end JavaFxDemoApp
