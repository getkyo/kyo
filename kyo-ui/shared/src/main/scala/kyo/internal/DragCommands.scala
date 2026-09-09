package kyo.internal

import kyo.*

/** Session-scoped drag commands shared by remote and local UI runners.
  *
  * Each runner installs a resolution sink for the session it owns. Dispatch resolves every terminal
  * drop or sort request exactly once; outside a runner resolution is a no-op.
  */
private[kyo] object DragCommands:

    private[kyo] val resolveSink: Local[Maybe[(String, Drag.Decision) => Unit < Async]] = Local.init(Absent)

    private[kyo] enum Payload:
        case Event(value: Drag.Event)
        case End(value: Drag.End)
        case Move(value: Drag.Move)
    end Payload

    private[kyo] enum DecisionState derives CanEqual:
        case None
        case Accepted
        case Rejected(value: Drag.Decision.Reject)
        case Failed(value: Drag.Decision.Reject)
    end DecisionState

    final private[kyo] case class Dispatch(payload: Payload, decisions: Maybe[AtomicRef[DecisionState]])

    private[kyo] val current: Local[Maybe[Dispatch]] = Local.init(Absent)

    def resolve(sessionId: String, decision: Drag.Decision)(using Frame): Unit < Async =
        resolveSink.use {
            case Present(sink) => sink(sessionId, decision)
            case Absent        => ()
        }

end DragCommands
