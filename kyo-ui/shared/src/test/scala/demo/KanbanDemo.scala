package demo

import kyo.*
import kyo.Style.*
import kyo.UI.*
import kyo.UI.Ast.HtmlContent

/** Trello-style Kanban board served as a server-push HTML-over-SSE app.
  *
  * One `UI` value is built per connected client and served with `UI.runHandlers`, so the board state lives on the server and every change
  * is pushed to the browser as a fine-grained DOM diff over SSE. No JavaScript build step is involved: open the printed URL and the page is
  * driven entirely by the server.
  *
  * Run via `sbt 'kyo-uiJVM/runMain demo.Kanban'` (optional port as the first argument). Add a card with the form, then walk a card across the
  * three columns with the ◀/▶ buttons or remove it with ✕.
  *
  * Demonstrates: `UI.runHandlers` server-push deployment, a single `SignalRef[Board]` as the source of truth, three derived per-column
  * `Signal[Chunk[Card]]`s via `.map`, keyed list rendering with `foreachKeyed` (so moving one card never disturbs the others), a reactive
  * per-column count, `when` empty-state placeholders, two-way `value` binding on the new-card input, and flex-row/column layout via `Style`.
  */
object KanbanDemo extends KyoApp:

    case class Card(id: String, title: String) derives CanEqual
    case class Board(todo: Chunk[Card], doing: Chunk[Card], done: Chunk[Card]) derives CanEqual

    private val seed = Board(
        todo = Chunk(Card("1", "Design the API"), Card("2", "Write the README")),
        doing = Chunk(Card("3", "Implement the transport")),
        done = Chunk(Card("4", "Set up CI"))
    )

    /** Remove a card from whichever column holds it. */
    private def removeCard(b: Board, id: String): Board =
        Board(b.todo.filterNot(_.id == id), b.doing.filterNot(_.id == id), b.done.filterNot(_.id == id))

    /** Shift a card one column toward `dir` (-1 left, +1 right), clamped to the ends. */
    private def shift(b: Board, c: Card, dir: Int): Board =
        val cols = Chunk(b.todo, b.doing, b.done)
        val idx  = cols.indexWhere(_.exists(_.id == c.id))
        if idx < 0 then b
        else
            val target = (idx + dir).max(0).min(2)
            if target == idx then b
            else
                val cleaned = cols.updated(idx, cols(idx).filterNot(_.id == c.id))
                val moved   = cleaned.updated(target, cleaned(target) :+ c)
                Board(moved(0), moved(1), moved(2))
            end if
        end if
    end shift

    private val pageStyle  = Style.padding(24.px).fontFamily(FontFamily.SansSerif).gap(16.px)
    private val barStyle   = Style.row.gap(8.px).align(Alignment.center)
    private val boardStyle = Style.row.gap(16.px)
    private val colStyle =
        Style.column.gap(8.px).padding(12.px).bg(Color.slate).rounded(10.px).flexGrow(1).flexBasis(0.px).minHeight(420.px)
    private val cardListStyle = Style.column.gap(10.px)
    private val headerStyle   = Style.row.gap(8.px).align(Alignment.center).padding(0.px, 0.px, 4.px, 0.px)
    private val titleStyle    = Style.color(Color.white).bold.fontSize(16.px).flexGrow(1)
    private val badgeStyle =
        Style.color(Color.white).bg(Color.rgba(255, 255, 255, 0.22)).rounded(999.px).padding(1.px, 8.px).fontSize(13.px)
    private val cardStyle = Style.row.gap(8.px).align(Alignment.center).padding(10.px).bg(Color.white).rounded(8.px)
        .shadow(0.px, 1.px, 2.px, 0.px, Color.rgba(0, 0, 0, 0.18))
    private val cardTextStyle = Style.flexGrow(1)
    private val actionsStyle  = Style.row.gap(4.px)

    private def cardRow(
        c: Card,
        left: Maybe[Card => Any < Async],
        right: Maybe[Card => Any < Async],
        delete: Card => Any < Async
    ): HtmlContent =
        li.style(cardStyle)(
            span(c.title).style(cardTextStyle),
            div.style(actionsStyle)(
                if left.isDefined then button("◀").id(s"left-${c.id}").onClick(left.get(c)) else UI.empty,
                if right.isDefined then button("▶").id(s"right-${c.id}").onClick(right.get(c)) else UI.empty,
                button("✕").id(s"del-${c.id}").onClick(delete(c))
            )
        )

    private def column(
        title: String,
        cards: Signal[Chunk[Card]],
        left: Maybe[Card => Any < Async],
        right: Maybe[Card => Any < Async],
        delete: Card => Any < Async
    ): HtmlContent =
        div.style(colStyle)(
            div.style(headerStyle)(
                span(title).style(titleStyle),
                cards.render(cs => span(cs.size.toString).style(badgeStyle))
            ),
            when(cards.map(_.isEmpty))(p("Nothing here").style(Style.color(Color.white).italic.fontSize(13.px))),
            ul.style(cardListStyle)(cards.foreachKeyed(_.id)(c => cardRow(c, left, right, delete)))
        )

    /** Build a fresh board UI for one client. `runHandlers` re-runs this per connection. */
    private def boardUI: UI < Async =
        for
            state  <- Signal.initRef(seed)
            draft  <- Signal.initRef("")
            nextId <- Signal.initRef(100)
            add =
                draft.get.map { raw =>
                    val title = raw.trim
                    if title.isEmpty then ()
                    else
                        nextId.getAndUpdate(_ + 1).map { id =>
                            state.updateAndGet(b => b.copy(todo = b.todo :+ Card(id.toString, title)))
                                .andThen(draft.set(""))
                        }
                    end if
                }
            moveLeft  = (c: Card) => state.updateAndGet(b => shift(b, c, -1)).unit
            moveRight = (c: Card) => state.updateAndGet(b => shift(b, c, +1)).unit
            delete    = (c: Card) => state.updateAndGet(b => removeCard(b, c.id)).unit
        yield UI.main.style(pageStyle)(
            h1("Kanban"),
            form.id("new").onSubmit(add)(
                div.style(barStyle)(
                    input.id("draft").placeholder("New card title").value(draft),
                    button("Add").id("add")
                )
            ),
            div.style(boardStyle)(
                column("To Do", state.map(_.todo), Absent, Present(moveRight), delete),
                column("In Progress", state.map(_.doing), Present(moveLeft), Present(moveRight), delete),
                column("Done", state.map(_.done), Present(moveLeft), Absent, delete)
            )
        )

    run {
        val port = args.headOption.flatMap(_.toIntOption).getOrElse(0)
        for
            handlers <- UI.runHandlers("/")(boardUI)
            server   <- HttpServer.init(port, "localhost")(handlers*)
            _        <- Console.printLine(s"Kanban running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end KanbanDemo
