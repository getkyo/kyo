package kyo.internal

import kyo.*

/** Session-scoped client commands, the imperative complement of the reactive diff channel.
  *
  * A reactive update flows signal -> re-render -> HtmlOp; a command like scroll-into-view has no
  * signal to ride, so each runner installs a sink for the session it owns: UIServer wires the
  * WebSocket (the command becomes an [[HtmlOp]] the embedded client applies) and DomBackend wires
  * the local document. Handlers reach the sink through a [[Local]], so the same handler code works
  * under either runner; outside any session (plain SSR, render-only tests) the sink is absent and
  * the command is a no-op.
  */
private[kyo] object UICommands:

    private[kyo] val scrollSink: Local[Maybe[String => Unit < Async]] = Local.init(Absent)

    def scrollIntoView(id: String)(using Frame): Unit < Async =
        scrollSink.use {
            case Present(sink) => sink(id)
            case Absent        => ()
        }

end UICommands
