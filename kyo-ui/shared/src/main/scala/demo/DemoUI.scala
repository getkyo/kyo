package demo

import kyo.*
import scala.language.implicitConversions

object DemoUI extends UIScope:

    import DemoStyles.*

    def build: UI < Async =
        for
            count    <- Signal.initRef(0)
            todoText <- Signal.initRef("")
            todos    <- Signal.initRef(Chunk.empty[String])
            darkMode <- Signal.initRef(false)
        yield div.cls("app").clsWhen("dark", darkMode).style(app)(
            header.style(headerStyle)(
                h1("Kyo UI Demo"),
                nav.style(navStyle)(
                    a.href("#")("Home"),
                    a.href("#")("About"),
                    a.href("#")("Contact")
                )(
                    button.cls("theme-toggle").style(themeToggle).onClick(darkMode.getAndUpdate(!_).unit)("Toggle Theme")
                )
            ),
            main.style(content)(
                section.cls("hero").style(card)(
                    h2("Welcome to Kyo UI"),
                    p("A pure, type-safe UI library for Scala")
                ),
                section.style(card)(
                    h3("Counter"),
                    div.cls("counter-row").style(counterRow)(
                        button.cls("counter-btn").style(counterBtn)("-").onClick(count.getAndUpdate(_ - 1).unit),
                        span.cls("counter-value").style(counterValue)(count.map(_.toString)),
                        button.cls("counter-btn").style(counterBtn)("+").onClick(count.getAndUpdate(_ + 1).unit)
                    )
                ),
                section.style(card)(
                    h3("Todo List"),
                    div.cls("todo-input").style(todoInput)(
                        input.value(todoText).onInput(todoText.set(_)).placeholder("What needs to be done?"),
                        button.cls("submit").style(submitBtn)("Add").onClick {
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
                            li.cls("todo-item").style(todoItem)(
                                span(todo),
                                button.cls("delete-btn").style(deleteBtn)("x").onClick(
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
                ),
                section.style(card)(
                    h3("Form Example"),
                    label("Name"),
                    input.placeholder("Enter your name"),
                    label("Email"),
                    input.placeholder("you@example.com"),
                    button.style(submitBtn)("Submit")
                )
            ),
            footer.style(footerStyle)(
                p("Built with Kyo UI")
            )
        )

end DemoUI
