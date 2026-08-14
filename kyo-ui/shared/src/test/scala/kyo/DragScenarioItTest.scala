package kyo

import demo.InventoryGridDemo
import demo.InventoryGridDemo.Column
import demo.InventoryGridDemo.Inventory
import demo.InventoryGridDemo.Row
import demo.KanbanDemo
import demo.KanbanDemo.Board
import demo.KanbanDemo.Card
import kyo.UI.foreachKeyed
import kyo.internal.DragCommands
import kyo.internal.DragProtocol
import kyo.internal.ReactiveRegion
import kyo.internal.ReactiveUI
import kyo.internal.UIEvent
import kyo.internal.UIExchange

/** End-to-end drag scenarios against the real demo views.
  *
  * These tests do NOT use a browser. They normalize the demo board view, subscribe it, dispatch drag wire events
  * directly through the handler returned by ReactiveUI.subscribe, and assert on the demo's own state refs plus the
  * decisions resolved through DragCommands. The pointer and keyboard sensors both emit the same wire events, so the
  * scenarios cover every sensor path the server can observe.
  */
class DragScenarioItTest extends UITest:

    override def config = super.config.sequential

    /** Minimal UIExchange stub that discards onChange notifications. */
    private class NoopExchange extends UIExchange:
        def onChange(
            region: ReactiveRegion,
            path: Seq[String],
            context: ReactiveRegion.RegionIdentity,
            parentContext: ReactiveRegion.ParentContext,
            ui: UI
        )(using
            Frame
        ): Unit < Async = ()
    end NoopExchange

    private def startEvent(sessionId: String, sourceKey: String, title: String): UIEvent.DragStart =
        UIEvent.DragStart(
            Seq.empty,
            DragProtocol.StartData(
                sessionId,
                Chunk(DragProtocol.ItemData.Text(Map("text/plain" -> title))),
                Drag.Operation.Move,
                Present(sourceKey),
                Drag.Point(0, 0),
                UI.Modifiers.none
            )
        )

    private def endEvent(sessionId: String, cancelled: Boolean): UIEvent.DragEnd =
        UIEvent.DragEnd(Seq.empty, DragProtocol.EndData(sessionId, Drag.Operation.Move, cancelled))

    private def sortEvent(sessionId: String, move: Drag.Move): UIEvent.SortMove =
        UIEvent.SortMove(Seq.empty, sessionId, move)

    private def move(
        keys: Chunk[String],
        source: String,
        destination: String,
        anchor: Maybe[String] = Absent,
        position: Drag.Position = Drag.Position.After
    ): Drag.Move =
        Drag.Move(keys, Drag.Location(source), Drag.Location(destination), anchor, position, Drag.Operation.Move)

    // --- Kanban scenarios ---

    private val board = Board(
        todo = Chunk(Card("1", "Design the API"), Card("2", "Write the README")),
        doing = Chunk(Card("3", "Implement the transport")),
        done = Chunk(Card("4", "Set up CI"))
    )

    private def withBoard[A](initial: Board, selected: Set[String])(
        f: (SignalRef[Board], (Seq[String], UIEvent) => Boolean < Async, AtomicRef[Chunk[Drag.Decision]]) => A < (Async & Scope)
    )(using Frame): A < Async =
        Scope.run {
            for
                state     <- Signal.initRef(initial)
                selection <- Signal.initRef(selected)
                decisions <- AtomicRef.init(Chunk.empty[Drag.Decision])
                root      <- ReactiveUI.normalize(KanbanDemo.boardView(state, selection), Seq.empty)
                result <- DragCommands.resolveSink.let(
                    Present((_, decision) => decisions.getAndUpdate(_.append(decision)).unit)
                ) {
                    ReactiveUI.subscribe(root, new NoopExchange).map(sub => f(state, sub.handle, decisions))
                }
            yield result
        }

    "kanban" - {

        "pointer reorder within To Do places the card before the anchor" in {
            withBoard(board, Set.empty) { (state, dispatch, decisions) =>
                for
                    _ <- dispatch(Seq.empty, startEvent("kanban-1", "2", "Write the README"))
                    _ <- dispatch(Seq.empty, sortEvent("kanban-1", move(Chunk("2"), "todo", "todo", Present("1"), Drag.Position.Before)))
                    updated  <- state.get
                    resolved <- decisions.get
                yield
                    assert(updated.todo.map(_.id) == Chunk("2", "1"))
                    assert(updated.doing.map(_.id) == Chunk("3"))
                    assert(updated.done.map(_.id) == Chunk("4"))
                    assert(resolved == Chunk(Drag.Decision.Accept))
            }
        }

        "moving one card to In Progress removes it from To Do" in {
            withBoard(board, Set.empty) { (state, dispatch, decisions) =>
                for
                    _        <- dispatch(Seq.empty, startEvent("kanban-2", "1", "Design the API"))
                    _        <- dispatch(Seq.empty, sortEvent("kanban-2", move(Chunk("1"), "todo", "doing")))
                    updated  <- state.get
                    resolved <- decisions.get
                yield
                    assert(updated.todo.map(_.id) == Chunk("2"))
                    assert(updated.doing.map(_.id) == Chunk("3", "1"))
                    assert(resolved == Chunk(Drag.Decision.Accept))
            }
        }

        "two selected cards move together in board-visible order" in {
            withBoard(board, Set("1", "2")) { (state, dispatch, decisions) =>
                for
                    _        <- dispatch(Seq.empty, startEvent("kanban-3", "2", "Write the README"))
                    _        <- dispatch(Seq.empty, sortEvent("kanban-3", move(Chunk("2"), "todo", "doing")))
                    updated  <- state.get
                    resolved <- decisions.get
                yield
                    assert(updated.todo.isEmpty)
                    assert(updated.doing.map(_.id) == Chunk("3", "1", "2"))
                    assert(resolved == Chunk(Drag.Decision.Accept))
            }
        }

        "a selection spanning lanes lands contiguously at the destination anchor" in {
            withBoard(board, Set("2", "3")) { (state, dispatch, decisions) =>
                for
                    _ <- dispatch(Seq.empty, startEvent("kanban-4", "3", "Implement the transport"))
                    _ <- dispatch(Seq.empty, sortEvent("kanban-4", move(Chunk("3"), "doing", "done", Present("4"), Drag.Position.Before)))
                    updated  <- state.get
                    resolved <- decisions.get
                yield
                    assert(updated.todo.map(_.id) == Chunk("1"))
                    assert(updated.doing.isEmpty)
                    assert(updated.done.map(_.id) == Chunk("2", "3", "4"))
                    assert(resolved == Chunk(Drag.Decision.Accept))
            }
        }

        "keyboard-originated sort move reaches Done through the same wire event" in {
            withBoard(board, Set.empty) { (state, dispatch, decisions) =>
                for
                    _        <- dispatch(Seq.empty, startEvent("kanban-5", "1", "Design the API"))
                    _        <- dispatch(Seq.empty, sortEvent("kanban-5", move(Chunk("1"), "todo", "done")))
                    updated  <- state.get
                    resolved <- decisions.get
                yield
                    assert(updated.todo.map(_.id) == Chunk("2"))
                    assert(updated.done.map(_.id) == Chunk("4", "1"))
                    assert(resolved == Chunk(Drag.Decision.Accept))
            }
        }

        "cancelled drag leaves the board unchanged and resolves no accept" in {
            withBoard(board, Set.empty) { (state, dispatch, decisions) =>
                for
                    _        <- dispatch(Seq.empty, startEvent("kanban-6", "1", "Design the API"))
                    _        <- dispatch(Seq.empty, endEvent("kanban-6", cancelled = true))
                    _        <- dispatch(Seq.empty, sortEvent("kanban-6", move(Chunk("1"), "todo", "done")))
                    updated  <- state.get
                    resolved <- decisions.get
                yield
                    assert(updated == board)
                    assert(resolved == Chunk(
                        Drag.Decision.Reject(Drag.Rejection.Application("No sort handler accepted the move."))
                    ))
            }
        }

        "a move to an unknown lane resolves Reject without mutation" in {
            withBoard(board, Set.empty) { (state, dispatch, decisions) =>
                for
                    _        <- dispatch(Seq.empty, startEvent("kanban-7", "1", "Design the API"))
                    _        <- dispatch(Seq.empty, sortEvent("kanban-7", move(Chunk("1"), "todo", "archive")))
                    updated  <- state.get
                    resolved <- decisions.get
                yield
                    assert(updated == board)
                    assert(resolved == Chunk(
                        Drag.Decision.Reject(Drag.Rejection.Application("Unknown collection: archive"))
                    ))
            }
        }

        "fallback arrow controls produce the same final board as the equivalent drag" in {
            val viaControl =
                KanbanDemo.shiftMove(board, "1", 1) match
                    case Present(m) => KanbanDemo.applyMove(board, Set.empty, m)
                    case Absent     => fail("Expected a shift move for card 1")
            val viaDrag = KanbanDemo.applyMove(board, Set.empty, move(Chunk("1"), "todo", "doing"))
            assert(viaControl == viaDrag)
            assert(viaControl.map(_.doing.map(_.id)) == Result.succeed(Chunk("3", "1")))
        }

        "applyMove rejects an anchor inside the moving selection without mutation" in {
            val result = KanbanDemo.applyMove(board, Set("1", "2"), move(Chunk("1"), "todo", "doing", Present("2"), Drag.Position.Before))
            assert(result == Result.fail(Drag.Rejection.Application("The destination is part of the moving selection.")))
        }

        "applyMove rejects copy and link operations" in {
            val copy =
                Drag.Move(Chunk("1"), Drag.Location("todo"), Drag.Location("doing"), Absent, Drag.Position.After, Drag.Operation.Copy)
            val link =
                Drag.Move(Chunk("1"), Drag.Location("todo"), Drag.Location("doing"), Absent, Drag.Position.After, Drag.Operation.Link)
            assert(KanbanDemo.applyMove(board, Set.empty, copy) == Result.fail(Drag.Rejection.Application("Kanban cards only move.")))
            assert(KanbanDemo.applyMove(board, Set.empty, link) == Result.fail(Drag.Rejection.Application("Kanban cards only move.")))
        }
    }

    // --- Inventory scenarios ---

    private val inventory = Inventory(
        columns = Chunk(
            Column("sku", "SKU"),
            Column("name", "Name"),
            Column("qty", "Quantity"),
            Column("price", "Price")
        ),
        rows = Chunk(
            Row("r1", Map("sku" -> "A-100", "name" -> "Anvil", "qty" -> "3", "price" -> "49.00")),
            Row("r2", Map("sku" -> "B-200", "name" -> "Rope", "qty" -> "12", "price" -> "9.50")),
            Row("r3", Map("sku" -> "C-300", "name" -> "Magnet", "qty" -> "7", "price" -> "19.95"))
        )
    )

    private def withGrid[A](initial: Inventory, selected: Set[String])(
        f: (SignalRef[Inventory], (Seq[String], UIEvent) => Boolean < Async, AtomicRef[Chunk[Drag.Decision]]) => A < (Async & Scope)
    )(using Frame): A < Async =
        Scope.run {
            for
                state     <- Signal.initRef(initial)
                selection <- Signal.initRef(selected)
                decisions <- AtomicRef.init(Chunk.empty[Drag.Decision])
                root      <- ReactiveUI.normalize(InventoryGridDemo.gridView(state, selection), Seq.empty)
                result <- DragCommands.resolveSink.let(
                    Present((_, decision) => decisions.getAndUpdate(_.append(decision)).unit)
                ) {
                    ReactiveUI.subscribe(root, new NoopExchange).map(sub => f(state, sub.handle, decisions))
                }
            yield result
        }

    "inventory" - {

        val Rows    = InventoryGridDemo.RowsCollection
        val Columns = InventoryGridDemo.ColumnsCollection

        "whole row moves vertically before the anchor row" in {
            withGrid(inventory, Set.empty) { (state, dispatch, decisions) =>
                for
                    _        <- dispatch(Seq.empty, startEvent("grid-1", "r3", "Magnet"))
                    _        <- dispatch(Seq.empty, sortEvent("grid-1", move(Chunk("r3"), Rows, Rows, Present("r1"), Drag.Position.Before)))
                    updated  <- state.get
                    resolved <- decisions.get
                yield
                    assert(updated.rows.map(_.id) == Chunk("r3", "r1", "r2"))
                    assert(updated.columns.map(_.id) == Chunk("sku", "name", "qty", "price"))
                    assert(resolved == Chunk(Drag.Decision.Accept))
            }
        }

        "selected rows move together in visible order" in {
            withGrid(inventory, Set("r1", "r2")) { (state, dispatch, decisions) =>
                for
                    _        <- dispatch(Seq.empty, startEvent("grid-2", "r2", "Rope"))
                    _        <- dispatch(Seq.empty, sortEvent("grid-2", move(Chunk("r2"), Rows, Rows, Present("r3"), Drag.Position.After)))
                    updated  <- state.get
                    resolved <- decisions.get
                yield
                    assert(updated.rows.map(_.id) == Chunk("r3", "r1", "r2"))
                    assert(resolved == Chunk(Drag.Decision.Accept))
            }
        }

        "whole column moves horizontally and cells follow the column order" in {
            withGrid(inventory, Set.empty) { (state, dispatch, decisions) =>
                for
                    _ <- dispatch(Seq.empty, startEvent("grid-3", "qty", "Quantity"))
                    _ <- dispatch(
                        Seq.empty,
                        sortEvent("grid-3", move(Chunk("qty"), Columns, Columns, Present("name"), Drag.Position.Before))
                    )
                    updated  <- state.get
                    resolved <- decisions.get
                yield
                    assert(updated.columns.map(_.id) == Chunk("sku", "qty", "name", "price"))
                    assert(updated.rows.map(_.id) == Chunk("r1", "r2", "r3"))
                    assert(resolved == Chunk(Drag.Decision.Accept))
            }
        }

        "a row dragged onto the column collection resolves Reject without mutation" in {
            withGrid(inventory, Set.empty) { (state, dispatch, decisions) =>
                for
                    _ <- dispatch(Seq.empty, startEvent("grid-4", "r1", "Anvil"))
                    _ <- dispatch(Seq.empty, sortEvent("grid-4", move(Chunk("r1"), Rows, Columns, Present("name"), Drag.Position.After)))
                    updated  <- state.get
                    resolved <- decisions.get
                yield
                    assert(updated == inventory)
                    assert(resolved == Chunk(
                        Drag.Decision.Reject(Drag.Rejection.Application("Rows and columns cannot be mixed."))
                    ))
            }
        }

        "the locked SKU column can neither move nor anchor a move" in {
            val movedLocked =
                InventoryGridDemo.applyMove(inventory, Set.empty, move(Chunk("sku"), Columns, Columns, Present("price")))
            val anchoredOnIt =
                InventoryGridDemo.applyMove(
                    inventory,
                    Set.empty,
                    move(Chunk("name"), Columns, Columns, Present("sku"), Drag.Position.Before)
                )
            assert(movedLocked == Result.fail(Drag.Rejection.Application("Locked keys cannot move or anchor a move.")))
            assert(anchoredOnIt == Result.fail(Drag.Rejection.Application("Locked keys cannot move or anchor a move.")))
        }

        "keyboard-originated column move commits through the same wire event" in {
            withGrid(inventory, Set.empty) { (state, dispatch, decisions) =>
                for
                    _ <- dispatch(Seq.empty, startEvent("grid-5", "price", "Price"))
                    _ <- dispatch(
                        Seq.empty,
                        sortEvent("grid-5", move(Chunk("price"), Columns, Columns, Present("name"), Drag.Position.Before))
                    )
                    updated  <- state.get
                    resolved <- decisions.get
                yield
                    assert(updated.columns.map(_.id) == Chunk("sku", "price", "name", "qty"))
                    assert(resolved == Chunk(Drag.Decision.Accept))
            }
        }

        "programmatic movement uses the same Drag.Move reducer" in {
            val programmatic =
                InventoryGridDemo.applyMove(inventory, Set.empty, move(Chunk("r2"), Rows, Rows, Present("r1"), Drag.Position.Before))
            assert(programmatic.map(_.rows.map(_.id)) == Result.succeed(Chunk("r2", "r1", "r3")))
            assert(programmatic.map(_.columns) == Result.succeed(inventory.columns))
        }
    }

    "100 wire session iterations converge with alternating accept, reject, and cancel" in {
        withBoard(board, Set.empty) { (state, dispatch, decisions) =>
            for
                _ <- Kyo.foreachDiscard(Chunk.from(0 until 100)) { iteration =>
                    val session = s"stress-$iteration"
                    for
                        _ <- dispatch(Seq.empty, startEvent(session, "1", "Design the API"))
                        _ <- iteration % 3 match
                            case 0 =>
                                // Accepted round trip there and back keeps the board convergent.
                                dispatch(Seq.empty, sortEvent(session, move(Chunk("1"), "todo", "doing")))
                                    .andThen(dispatch(Seq.empty, endEvent(session, cancelled = false)))
                                    .andThen(dispatch(Seq.empty, startEvent(s"$session-back", "1", "Design the API")))
                                    .andThen(dispatch(
                                        Seq.empty,
                                        sortEvent(s"$session-back", move(Chunk("1"), "doing", "todo", Present("2"), Drag.Position.Before))
                                    ))
                                    .andThen(dispatch(Seq.empty, endEvent(s"$session-back", cancelled = false))).unit
                            case 1 =>
                                // Rejected move leaves the board untouched.
                                dispatch(Seq.empty, sortEvent(session, move(Chunk("1"), "todo", "archive")))
                                    .andThen(dispatch(Seq.empty, endEvent(session, cancelled = false))).unit
                            case _ =>
                                // Cancelled drag never mutates.
                                dispatch(Seq.empty, endEvent(session, cancelled = true)).unit
                    yield ()
                    end for
                }
                updated  <- state.get
                resolved <- decisions.get
            yield
                assert(updated == board)
                assert(resolved.count(_ == Drag.Decision.Accept) == 68)
                assert(resolved.count {
                    case Drag.Decision.Reject(_) => true
                    case _                       => false
                } == 33)
        }
    }

    // --- Embedded server-push runtime, real Chrome ---

    private def sortableApp(state: SignalRef[Chunk[String]])(using Frame): UI =
        val commit: Drag.Move => Drag.Decision < Async = m =>
            state.get.map { current =>
                Drag.Decision.fromResult(Sortable.move(current, current, m).map(_._1))(updated => state.set(updated))
            }
        UI.div.onSortMove(commit)(
            UI.ul.id("list").dropTarget(Drag.Target.sortable("list"))(
                state.map(identity).foreachKeyed(identity)(k =>
                    UI.li(k).id(s"it-$k").tabIndex(0).dragSource(Drag.Source.sortable(k, Present(k)))
                )
            ),
            state.map(items => UI.span(items.mkString(",")).id("order"))
        )
    end sortableApp

    private val pointerDragJs =
        """(function(){
          |function fire(el,t,x,y){el.dispatchEvent(new PointerEvent(t,{bubbles:true,cancelable:true,clientX:x,clientY:y,pointerId:1}));}
          |var a=document.getElementById("it-a");
          |var c=document.getElementById("it-c");
          |var ar=a.getBoundingClientRect();var cr=c.getBoundingClientRect();
          |fire(a,"pointerdown",ar.left+4,ar.top+4);
          |fire(a,"pointermove",ar.left+4,ar.top+24);
          |fire(a,"pointermove",cr.left+4,cr.top+cr.height*0.8);
          |return true;})()""".stripMargin

    private val pointerDropJs =
        """(function(){
          |function fire(el,t,x,y){el.dispatchEvent(new PointerEvent(t,{bubbles:true,cancelable:true,clientX:x,clientY:y,pointerId:1}));}
          |var c=document.getElementById("it-c");var cr=c.getBoundingClientRect();
          |fire(c,"pointerup",cr.left+4,cr.top+cr.height*0.8);
          |return true;})()""".stripMargin

    "embedded pointer drag reorders the served list" in {
        for
            state <- Signal.initRef(Chunk("a", "b", "c"))
            _ <- withUI(sortableApp(state)) {
                for
                    _ <- Browser.evalBoolean(pointerDragJs)
                    // The runtime coalesces moves on an animation frame; the CDP round trip between the two
                    // evals guarantees at least one frame has run before the drop.
                    _ <- Browser.evalBoolean(pointerDropJs)
                    _ <- Browser.assertText(Browser.Selector.id("order"), "b,c,a")
                yield ()
            }
            order <- state.get
        yield assert(order == Chunk("b", "c", "a"))
    }

    "embedded keyboard drag matches the pointer result" in {
        for
            state <- Signal.initRef(Chunk("a", "b", "c"))
            _ <- withUI(sortableApp(state)) {
                for
                    _ <- Browser.click(Browser.Selector.id("it-a"))
                    _ <- Browser.press(Browser.Selector.id("it-a"), Browser.Key.Enter)
                    _ <- Browser.press(Browser.Selector.id("it-a"), Browser.Key.ArrowDown)
                    _ <- Browser.press(Browser.Selector.id("it-a"), Browser.Key.ArrowDown)
                    _ <- Browser.press(Browser.Selector.id("it-a"), Browser.Key.Enter)
                    _ <- Browser.assertText(Browser.Selector.id("order"), "b,c,a")
                yield ()
            }
            order <- state.get
        yield assert(order == Chunk("b", "c", "a"))
    }

    "embedded Escape leaves the server state unchanged" in {
        for
            state <- Signal.initRef(Chunk("a", "b", "c"))
            _ <- withUI(sortableApp(state)) {
                for
                    _ <- Browser.click(Browser.Selector.id("it-a"))
                    _ <- Browser.press(Browser.Selector.id("it-a"), Browser.Key.Enter)
                    _ <- Browser.press(Browser.Selector.id("it-a"), Browser.Key.ArrowDown)
                    _ <- Browser.press(Browser.Selector.id("it-a"), Browser.Key.Escape)
                    _ <- Browser.assertText(Browser.Selector.id("order"), "a,b,c")
                yield ()
            }
            order <- state.get
        yield assert(order == Chunk("a", "b", "c"))
    }
end DragScenarioItTest
