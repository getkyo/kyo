package kyo

import demo.KanbanDemo
import demo.KanbanDemo.Board
import demo.KanbanDemo.Card
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
class DragScenarioItTest extends kyo.test.Test[Any]:

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
                        Drag.Decision.Reject(Drag.Rejection.Application("Unknown lane: archive"))
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
end DragScenarioItTest
