package demo

import kyo.*
import kyo.Length.*
import kyo.UI.foreachKeyed
import kyo.UI.render
import kyo.internal.tui.JvmTerminalIO
import kyo.internal.tui.pipeline.*
import scala.language.implicitConversions

object TuiDemo extends KyoApp:

    // ---- Data types ----

    case class Todo(id: Int, text: String, done: Boolean) derives CanEqual
    case class FormEntry(name: String, email: String, role: String) derives CanEqual

    // ---- Tab bar ----

    def tabBar(activeTab: SignalRef[Int])(using Frame): UI =
        UI.div.style(Style.row.gap(1.px).padding(0.px, 0.px, 1.px, 0.px))(
            tabButton(activeTab, 0, "1:Todo"),
            tabButton(activeTab, 1, "2:Form"),
            tabButton(activeTab, 2, "3:Calc"),
            tabButton(activeTab, 3, "4:Snake"),
            tabButton(activeTab, 4, "5:Files")
        )

    def tabButton(activeTab: SignalRef[Int], idx: Int, label: String)(using Frame): UI =
        UI.button.onClick(activeTab.set(idx))(label)

    // ---- Tab 1: Todo App ----

    def todoTab(using Frame): UI < Sync =
        for
            todosRef  <- Signal.initRef(Chunk.empty[Todo])
            inputRef  <- Signal.initRef("")
            filterRef <- Signal.initRef("all")
            nextId    <- Signal.initRef(1)
        yield
            val addTodo: Unit < Async =
                for
                    text <- inputRef.get
                    id   <- nextId.get
                    prev <- todosRef.get
                    _ <-
                        if text.nonEmpty then
                            todosRef.set(prev.append(Todo(id, text, false)))
                                .andThen(inputRef.set(""))
                                .andThen(nextId.set(id + 1))
                        else Kyo.lift(())
                yield ()

            UI.div(
                UI.h2("Todo List"),
                UI.form.onSubmit(addTodo)(
                    UI.div.style(Style.row.gap(1.px))(
                        UI.input.value(inputRef).placeholder("What needs to be done?"),
                        UI.button("Add")
                    )
                ),
                UI.div(
                    todosRef.foreachKeyed(t => t.id.toString) { todo =>
                        UI.div.style(Style.row)(
                            UI.checkbox.checked(todo.done).onChange { newDone =>
                                todosRef.get.map { todos =>
                                    todosRef.set(todos.map(t => if t.id == todo.id then t.copy(done = newDone) else t))
                                }
                            },
                            UI.span(s" ${todo.text}")
                        )
                    }
                ),
                todosRef.render { todos =>
                    val active = todos.count(!_.done)
                    UI.span(s"$active item${if active != 1 then "s" else ""} left")
                }
            )
    end todoTab

    // ---- Tab 2: Form + Table ----

    def formTab(using Frame): UI < Sync =
        for
            nameRef    <- Signal.initRef("")
            emailRef   <- Signal.initRef("")
            roleRef    <- Signal.initRef("developer")
            entriesRef <- Signal.initRef(Chunk.empty[FormEntry])
        yield UI.div.style(Style.row.gap(2.px))(
            UI.div.style(Style.width(35.px))(
                UI.h2("New Entry"),
                UI.form.onSubmit {
                    for
                        name  <- nameRef.get
                        email <- emailRef.get
                        role  <- roleRef.get
                        prev  <- entriesRef.get
                        _     <- entriesRef.set(prev.append(FormEntry(name, email, role)))
                        _     <- nameRef.set("")
                        _     <- emailRef.set("")
                    yield ()
                }(
                    UI.div(UI.label("Name:"), UI.input.value(nameRef).placeholder("John Doe")),
                    UI.div(UI.label("Email:"), UI.email.value(emailRef).placeholder("john@example.com")),
                    UI.div(
                        UI.label("Role:"),
                        UI.select.value(roleRef)(
                            UI.option.value("developer")("Developer"),
                            UI.option.value("designer")("Designer"),
                            UI.option.value("manager")("Manager")
                        )
                    ),
                    UI.button("Submit")
                )
            ),
            UI.div.style(Style.width(40.px))(
                UI.h2("Submissions"),
                UI.table(
                    UI.tr(UI.th("Name"), UI.th("Email"), UI.th("Role")),
                    entriesRef.foreachKeyed(e => e.name + e.email) { entry =>
                        UI.tr(UI.td(entry.name), UI.td(entry.email), UI.td(entry.role))
                    }
                )
            )
        )
    end formTab

    // ---- Tab 3: Calculator ----

    def calcTab(using Frame): UI < Sync =
        for
            displayRef <- Signal.initRef("0")
            accumRef   <- Signal.initRef(0.0)
            opRef      <- Signal.initRef("")
            freshRef   <- Signal.initRef(true)
        yield
            def pressDigit(d: String): Unit < Async =
                for
                    fresh   <- freshRef.get
                    current <- displayRef.get
                    next = if fresh || current == "0" then d else current + d
                    _ <- displayRef.set(next)
                    _ <- freshRef.set(false)
                yield ()

            def pressOp(op: String): Unit < Async =
                for
                    current <- displayRef.get
                    _       <- accumRef.set(current.toDoubleOption.getOrElse(0.0))
                    _       <- opRef.set(op)
                    _       <- freshRef.set(true)
                yield ()

            def pressEquals: Unit < Async =
                for
                    current <- displayRef.get
                    accum   <- accumRef.get
                    op      <- opRef.get
                    value = current.toDoubleOption.getOrElse(0.0)
                    result = op match
                        case "+" => accum + value
                        case "-" => accum - value
                        case "*" => accum * value
                        case "/" => if value != 0 then accum / value else 0.0
                        case _   => value
                    display = if result == result.toLong.toDouble then result.toLong.toString else f"$result%.4f"
                    _ <- displayRef.set(display)
                    _ <- opRef.set("")
                    _ <- freshRef.set(true)
                yield ()

            def pressClear: Unit < Async =
                displayRef.set("0")
                    .andThen(accumRef.set(0.0))
                    .andThen(opRef.set(""))
                    .andThen(freshRef.set(true))

            def calcBtn(label: String, action: => Unit < Async): UI =
                UI.button.onClick(action)(label)

            UI.div.style(Style.width(24.px))(
                UI.h2("Calculator"),
                displayRef.render(v => UI.div.style(Style.padding(0.px, 1.px))(v)),
                UI.hr,
                UI.div.style(Style.row)(
                    calcBtn("7", pressDigit("7")),
                    calcBtn("8", pressDigit("8")),
                    calcBtn("9", pressDigit("9")),
                    calcBtn("/", pressOp("/"))
                ),
                UI.div.style(Style.row)(
                    calcBtn("4", pressDigit("4")),
                    calcBtn("5", pressDigit("5")),
                    calcBtn("6", pressDigit("6")),
                    calcBtn("*", pressOp("*"))
                ),
                UI.div.style(Style.row)(
                    calcBtn("1", pressDigit("1")),
                    calcBtn("2", pressDigit("2")),
                    calcBtn("3", pressDigit("3")),
                    calcBtn("-", pressOp("-"))
                ),
                UI.div.style(Style.row)(
                    calcBtn("0", pressDigit("0")),
                    calcBtn(".", pressDigit(".")),
                    calcBtn("=", pressEquals),
                    calcBtn("+", pressOp("+"))
                ),
                UI.div(calcBtn("C", pressClear))
            )
    end calcTab

    // ---- Tab 4: Snake Game ----

    case class SnakeGame(
        snake: Chunk[(Int, Int)],
        food: (Int, Int),
        dir: (Int, Int),
        score: Int,
        alive: Boolean
    ) derives CanEqual

    private val gridW = 20
    private val gridH = 12

    def snakeTab(using Frame): (UI, Unit < (Async & Scope)) < Sync =
        val initial = SnakeGame(
            snake = Chunk((5, 5), (4, 5), (3, 5)),
            food = (10, 8),
            dir = (1, 0),
            score = 0,
            alive = true
        )
        for
            gameRef <- Signal.initRef(initial)
        yield

            def step: Unit < Async =
                gameRef.get.map { g =>
                    if !g.alive then gameRef.set(initial) // restart on step when dead
                    else
                        val head    = g.snake(0)
                        val newHead = ((head._1 + g.dir._1 + gridW) % gridW, (head._2 + g.dir._2 + gridH) % gridH)
                        val ate     = newHead == g.food
                        val newSnake =
                            if ate then Chunk(newHead).concat(g.snake)
                            else Chunk(newHead).concat(g.snake.take(g.snake.size - 1))
                        val hitSelf = newSnake.drop(1).exists(_ == newHead)
                        if hitSelf then gameRef.set(g.copy(alive = false))
                        else
                            val newFood  = if ate then ((g.food._1 + 7) % gridW, (g.food._2 + 3) % gridH) else g.food
                            val newScore = if ate then g.score + 1 else g.score
                            gameRef.set(g.copy(snake = newSnake, food = newFood, score = newScore))
                        end if
                }

            def moveDir(dx: Int, dy: Int): Unit < Async =
                gameRef.get.map { g =>
                    gameRef.set(g.copy(dir = (dx, dy)))
                }.andThen(step)

            // Background game loop: auto-advance snake every 200ms
            val gameLoop: Unit < (Async & Scope) =
                Fiber.init {
                    Loop.forever {
                        Async.sleep(200.millis).andThen(step)
                    }
                }.unit

            // The game div is focusable and captures arrow keys
            val ui = UI.div.tabIndex(0).onKeyDown { ke =>
                ke.key match
                    case UI.Keyboard.ArrowUp    => gameRef.get.map(g => gameRef.set(g.copy(dir = (0, -1))))
                    case UI.Keyboard.ArrowDown  => gameRef.get.map(g => gameRef.set(g.copy(dir = (0, 1))))
                    case UI.Keyboard.ArrowLeft  => gameRef.get.map(g => gameRef.set(g.copy(dir = (-1, 0))))
                    case UI.Keyboard.ArrowRight => gameRef.get.map(g => gameRef.set(g.copy(dir = (1, 0))))
                    case _                      => ()
            }(
                gameRef.render { g =>
                    val status = if g.alive then s"Score: ${g.score}" else s"Score: ${g.score}  GAME OVER"
                    val grid = (0 until gridH).map { y =>
                        val row = (0 until gridW).map { x =>
                            if g.snake.exists(_ == (x, y)) then "█"
                            else if g.food == (x, y) then "●"
                            else "·"
                        }.mkString
                        UI.div(row): UI
                    }
                    UI.div(
                        UI.div(status),
                        UI.div(grid*),
                        UI.div("Arrow keys: change direction  Ctrl+C: quit")
                    )
                }
            )

            (ui, gameLoop)
        end for
    end snakeTab

    // ---- Tab 5: File Explorer ----

    def filesTab(using Frame): UI < Sync =
        for
            selectedRef <- Signal.initRef("")
            expandedRef <- Signal.initRef(Chunk("src", "src/main"))
        yield
            case class FileNode(name: String, path: String, children: Chunk[FileNode] = Chunk.empty):
                def isDir: Boolean = children.nonEmpty
            end FileNode

            val tree = FileNode(
                "project",
                "project",
                Chunk(
                    FileNode(
                        "src",
                        "src",
                        Chunk(
                            FileNode(
                                "main",
                                "src/main",
                                Chunk(
                                    FileNode("App.scala", "src/main/App.scala"),
                                    FileNode("Config.scala", "src/main/Config.scala"),
                                    FileNode("Server.scala", "src/main/Server.scala")
                                )
                            ),
                            FileNode(
                                "test",
                                "src/test",
                                Chunk(
                                    FileNode("AppTest.scala", "src/test/AppTest.scala")
                                )
                            )
                        )
                    ),
                    FileNode("build.sbt", "build.sbt"),
                    FileNode("README.md", "README.md")
                )
            )

            def renderNode(node: FileNode, depth: Int, expanded: Chunk[String]): UI =
                val indent     = "  " * depth
                val icon       = if node.isDir then "📁 " else "📄 "
                val prefix     = indent + icon
                val isExpanded = expanded.exists(_ == node.path)

                UI.div(
                    UI.div.style(Style.row)(
                        UI.span.onClick {
                            if node.isDir then
                                expandedRef.get.map { exp =>
                                    if exp.exists(_ == node.path) then expandedRef.set(exp.filter(_ != node.path))
                                    else expandedRef.set(exp.append(node.path))
                                }
                            else selectedRef.set(node.path)
                        }(s"$prefix${node.name}")
                    ),
                    if node.isDir && isExpanded then
                        UI.div(node.children.map(c => renderNode(c, depth + 1, expanded))*)
                    else UI.div
                )
            end renderNode

            UI.div.style(Style.row.gap(2.px))(
                UI.div.style(Style.width(30.px))(
                    UI.h2("Files"),
                    expandedRef.render { exp =>
                        renderNode(tree, 0, exp)
                    }
                ),
                UI.div.style(Style.width(30.px))(
                    UI.h2("Details"),
                    selectedRef.render { path =>
                        if path.isEmpty then UI.span("Select a file")
                        else
                            UI.div(
                                UI.div(s"Path: $path"),
                                UI.div(s"Type: ${if path.endsWith(".scala") then "Scala" else "Other"}"),
                                UI.div(s"Size: ${(path.hashCode.abs % 1000) + 100} bytes")
                            )
                    }
                )
            )
    end filesTab

    // ---- Main ----

    run {
        for
            snakeResult <- snakeTab
            (snakeUi, gameLoop) = snakeResult

            terminal = new JvmTerminalIO

            // Start background game loop (auto-advances snake every 200ms)
            _ <- gameLoop

            session <- TuiBackend.render(
                terminal,
                UI.div(
                    UI.h1("Snake Game"),
                    UI.hr,
                    snakeUi
                ),
                tickInterval = Maybe(100.millis) // re-render at ~10fps to pick up game state changes
            )
            _ <- session.await
        yield ()
    }

end TuiDemo
