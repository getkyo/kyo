package demo

import kyo.*
import scala.language.implicitConversions

object ExperimentApp extends KyoApp with UIScope:

    case class Todo(id: String, text: String, done: Boolean) derives CanEqual

    private def todoApp(
        todos: SignalRef[Chunk[Todo]],
        newTodo: SignalRef[String],
        nextId: SignalRef[Int]
    )(using Frame): UI =
        val activeCount = todos.map(_.count(!_.done))
        val doneCount   = todos.map(_.count(_.done))
        val totalCount  = todos.map(_.size)

        section.cls("todo-app")(
            h2("Filtered Todo List"),
            div.cls("input-row")(
                input.cls("new-todo")
                    .value(newTodo)
                    .placeholder("Add a todo...")
                    .onInput(newTodo.set(_)),
                button.cls("add-btn")("Add").onClick {
                    for
                        t  <- newTodo.get
                        id <- nextId.getAndUpdate(_ + 1)
                        _ <-
                            if t.nonEmpty then
                                todos.getAndUpdate(_.append(Todo(id.toString, t, false))).unit
                            else ((): Unit < Sync)
                        _ <- newTodo.set("")
                    yield ()
                }
            ),
            div.cls("stats")(
                span.cls("stat")(activeCount.map(n => s"$n active")),
                span.cls("stat")(doneCount.map(n => s"$n done")),
                span.cls("stat")(totalCount.map(n => s"$n total"))
            ),
            ul.cls("todo-list")(
                todos.foreachKeyed(_.id) { todo =>
                    li.cls("todo-item")(
                        input.typ("checkbox")
                            .checked(todo.done)
                            .onClick(
                                todos.getAndUpdate(_.map(t =>
                                    if t.id == todo.id then t.copy(done = !t.done) else t
                                )).unit
                            ),
                        span.cls(if todo.done then "done-text" else "todo-text")(todo.text),
                        button.cls("delete-btn")("x").onClick(
                            todos.getAndUpdate(_.filter(_.id != todo.id)).unit
                        )
                    )
                }
            ),
            when(doneCount.map(_ > 0))(
                button.cls("clear-btn")("Clear completed").onClick(
                    todos.getAndUpdate(_.filter(!_.done)).unit
                )
            )
        )
    end todoApp

    private def tabSection(tab: SignalRef[String])(using Frame): UI =
        section.cls("nested-reactive")(
            h2("Nested Reactivity"),
            p("Demonstrates Signal[UI] with dynamic component swapping"),
            div.cls("tabs")(
                button.cls("tab-btn").onClick(tab.set("a"))("Tab A"),
                button.cls("tab-btn").onClick(tab.set("b"))("Tab B"),
                button.cls("tab-btn").onClick(tab.set("c"))("Tab C")
            ),
            div.cls("tab-content")(
                tab.map[UI] {
                    case "a" => div(h3("Tab A"), p("Default tab content. Welcome!"))
                    case "b" => div(h3("Tab B"), p("This is the second tab with different content."))
                    case "c" => div(h3("Tab C"), p("Third tab. Each swap is a full ReactiveNode re-render."))
                    case _   => div(p("Unknown"))
                }
            )
        )

    private def dataTable(todos: Signal[Chunk[Todo]])(using Frame): UI =
        section.cls("dynamic-table")(
            h2("Reactive Data Table"),
            table(
                tr(th("ID"), th("Task"), th("Status")),
                todos.foreachIndexed { (_, todo) =>
                    tr(
                        td(todo.id),
                        td(todo.text),
                        td(if todo.done then "Done" else "Active")
                    )
                }
            )
        )

    run {
        for
            todos   <- Signal.initRef(Chunk(Todo("1", "Learn Kyo", false), Todo("2", "Build UI", true), Todo("3", "Write tests", false)))
            newTodo <- Signal.initRef("")
            nextId  <- Signal.initRef(4)
            tab     <- Signal.initRef("a")
            session <- new DomBackend().render(
                div.cls("experiment")(
                    h1("Experiment: Complex Reactivity"),
                    todoApp(todos, newTodo, nextId),
                    tabSection(tab),
                    dataTable(todos)
                )
            )
            _ <- session.await
        yield ()
    }
end ExperimentApp
