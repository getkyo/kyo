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
  * Run via `sbt 'kyo-uiJVM/Test/runMain demo.KanbanDemo'` (optional port as the first argument). Add a card with the form, then drag a card
  * between the three columns, walk it with the ◀/▶ buttons, or remove it with ✕. Ticking the checkbox on several cards moves them together
  * on the next drag or arrow press, in board-visible order.
  *
  * Demonstrates: `dragSource`/`dropTarget`/`onSortMove` sortable wiring over one `SignalRef[Board]` source of truth, a single total
  * [[applyMove]] reducer built on [[kyo.Sortable.move]] (drag, keyboard, and the arrow fallback controls all route through it),
  * a `SignalRef[Set[String]]` multi-selection, keyed list rendering with `foreachKeyed`, reactive per-column counts, `when` empty-state
  * placeholders, and two-way `value` binding on the new-card input.
  */
object KanbanDemo extends KyoApp:

    case class Card(id: String, title: String) derives CanEqual
    case class Board(todo: Chunk[Card], doing: Chunk[Card], done: Chunk[Card]) derives CanEqual

    /** Lane ids double as the sortable collection names carried by [[Drag.Location]]. */
    val TodoLane  = "todo"
    val DoingLane = "doing"
    val DoneLane  = "done"

    private val laneIds = Chunk(TodoLane, DoingLane, DoneLane)

    private val textPlain        = Drag.MediaType.parse("text/plain").get
    private val textPlainPattern = Drag.MediaTypePattern.exact(textPlain)

    private val seed = Board(
        todo = Chunk(Card("1", "Design the API"), Card("2", "Write the README")),
        doing = Chunk(Card("3", "Implement the transport")),
        done = Chunk(Card("4", "Set up CI"))
    )

    /** Cards of one lane, empty for an unknown lane id. */
    def laneCards(b: Board, lane: String): Chunk[Card] =
        lane match
            case TodoLane  => b.todo
            case DoingLane => b.doing
            case DoneLane  => b.done
            case _         => Chunk.empty

    private def withLanes(cardsById: Map[String, Card], lanes: Map[String, Chunk[String]]): Board =
        def cards(lane: String): Chunk[Card] = lanes(lane).map(cardsById)
        Board(cards(TodoLane), cards(DoingLane), cards(DoneLane))

    private def visibleCards(b: Board): Chunk[Card] =
        b.todo.concat(b.doing).concat(b.done)

    private def reject(reason: String): Result[Drag.Rejection, Board] =
        Result.fail(Drag.Rejection.Application(reason))

    /** The one total move reducer: drag, keyboard, and the arrow controls all commit through it.
      *
      * When every dragged key is selected, the whole selection moves in board-visible order. Cards are located by key
      * across all lanes, and each per-lane group is applied through [[kyo.Sortable.move]], threading the anchor so a
      * selection spanning lanes lands contiguously at the destination. Invalid moves return a typed rejection and the
      * board is untouched.
      */
    def applyMove(board: Board, selected: Set[String], move: Drag.Move): Result[Drag.Rejection, Board] =
        val destLane = move.destination.collection
        if !laneIds.contains(destLane) then reject(s"Unknown lane: $destLane")
        else if move.operation != Drag.Operation.Move then reject("Kanban cards only move.")
        else
            val visible = visibleCards(board).map(_.id)
            val effective =
                if move.keys.nonEmpty && move.keys.forall(selected.contains) then visible.filter(selected.contains)
                else move.keys
            if effective.isEmpty then reject("At least one item must move.")
            else if !effective.forall(visible.contains) then reject("Every moving card must exist on the board.")
            else if move.anchor.exists(effective.contains) then reject("The destination is part of the moving selection.")
            else
                val cardsById = visibleCards(board).map(c => c.id -> c).toMap
                val moving    = effective.toSet
                val groups    = laneIds.map(l => l -> laneCards(board, l).map(_.id).filter(moving.contains)).filter(_._2.nonEmpty)
                val start     = laneIds.map(l => l -> laneCards(board, l).map(_.id)).toMap
                val applied = groups.foldLeft(
                    Result.succeed[Drag.Rejection, (Map[String, Chunk[String]], Maybe[String], Drag.Position)](
                        (start, move.anchor, move.position)
                    )
                ) { case (acc, (lane, keys)) =>
                    acc.flatMap { (lanes, anchor, position) =>
                        val group = Drag.Move(keys, Drag.Location(lane), Drag.Location(destLane), anchor, position, Drag.Operation.Move)
                        Sortable.move(lanes(lane), lanes(destLane), group).map { (src, dst) =>
                            val updated =
                                if lane == destLane then lanes.updated(lane, src)
                                else lanes.updated(lane, src).updated(destLane, dst)
                            (updated, Present(keys.last), Drag.Position.After)
                        }
                    }
                }
                applied.map((lanes, _, _) => withLanes(cardsById, lanes))
            end if
        end if
    end applyMove

    /** The [[Drag.Move]] the ◀/▶ controls issue: one lane toward `dir` (-1 left, +1 right), `Absent` when already at the end. */
    def shiftMove(board: Board, id: String, dir: Int): Maybe[Drag.Move] =
        val idx = laneIds.indexWhere(lane => laneCards(board, lane).exists(_.id == id))
        if idx < 0 then Absent
        else
            val target = (idx + dir).max(0).min(laneIds.size - 1)
            if target == idx then Absent
            else
                Present(Drag.Move(
                    Chunk(id),
                    Drag.Location(laneIds(idx)),
                    Drag.Location(laneIds(target)),
                    Absent,
                    Drag.Position.After,
                    Drag.Operation.Move
                ))
            end if
        end if
    end shiftMove

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
        selection: SignalRef[Set[String]],
        left: Maybe[Card => Any < Async],
        right: Maybe[Card => Any < Async],
        delete: Card => Any < Async
    ): HtmlContent =
        li.style(cardStyle)
            .dragSource(Drag.Source(
                c.id,
                Chunk(Drag.Item.Text(Map(textPlain -> c.title))),
                label = Present(c.title)
            ))(
                checkbox.id(s"select-${c.id}")
                    .onChange(on => selection.updateAndGet(s => if on then s + c.id else s - c.id).unit)
                    .checked(selection.map(_.contains(c.id))),
                span(c.title).style(cardTextStyle),
                div.style(actionsStyle)(
                    if left.isDefined then button("◀").id(s"left-${c.id}").onClick(left.get(c)) else UI.empty,
                    if right.isDefined then button("▶").id(s"right-${c.id}").onClick(right.get(c)) else UI.empty,
                    button("✕").id(s"del-${c.id}").onClick(delete(c))
                )
            )

    private def column(
        lane: String,
        title: String,
        cards: Signal[Chunk[Card]],
        selection: SignalRef[Set[String]],
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
            ul.style(cardListStyle)
                .dropTarget(Drag.Target(lane, Drag.Accept.types(textPlainPattern), Present(title)))(
                    cards.foreachKeyed(_.id)(c => cardRow(c, selection, left, right, delete))
                )
        )

    /** The reusable sortable board view over the two state refs; `boardUI` and the drag scenario tests share it. */
    def boardView(state: SignalRef[Board], selection: SignalRef[Set[String]])(using Frame): UI =
        val commit: Drag.Move => Drag.Decision < Async = move =>
            for
                sel     <- selection.get
                current <- state.get
                decided: (Drag.Decision < Async) = applyMove(current, sel, move) match
                    case Result.Success(next)     => state.set(next).andThen(Drag.Decision.Accept)
                    case Result.Failure(rejected) => Drag.Decision.Reject(rejected)
                    case _: Result.Panic          => Drag.Decision.Reject(Drag.Rejection.Application("Kanban move failed."))
                decision <- decided
            yield decision
        val shiftVia = (dir: Int) =>
            (c: Card) =>
                for
                    sel     <- selection.get
                    current <- state.get
                    committed: (Unit < Async) = shiftMove(current, c.id, dir) match
                        case Present(move) =>
                            applyMove(current, sel, move).fold(next => state.set(next).unit, _ => (), _ => ())
                        case Absent => ()
                    _ <- committed
                yield ()
        val delete = (c: Card) =>
            state.updateAndGet(b =>
                Board(
                    b.todo.filterNot(_.id == c.id),
                    b.doing.filterNot(_.id == c.id),
                    b.done.filterNot(_.id == c.id)
                )
            ).andThen(selection.updateAndGet(_ - c.id)).unit
        div.style(boardStyle).onSortMove(commit)(
            column(TodoLane, "To Do", state.map(_.todo), selection, Absent, Present(shiftVia(1)), delete),
            column(DoingLane, "In Progress", state.map(_.doing), selection, Present(shiftVia(-1)), Present(shiftVia(1)), delete),
            column(DoneLane, "Done", state.map(_.done), selection, Present(shiftVia(-1)), Absent, delete)
        )
    end boardView

    /** Build a fresh board UI for one client. `runHandlers` re-runs this per connection. */
    private def boardUI: UI < Async =
        for
            state     <- Signal.initRef(seed)
            selection <- Signal.initRef(Set.empty[String])
            draft     <- Signal.initRef("")
            nextId    <- Signal.initRef(100)
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
        yield UI.main.style(pageStyle)(
            h1("Kanban"),
            form.id("new").onSubmit(add)(
                div.style(barStyle)(
                    input.id("draft").placeholder("New card title").value(draft),
                    button("Add").id("add")
                )
            ),
            boardView(state, selection)
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
