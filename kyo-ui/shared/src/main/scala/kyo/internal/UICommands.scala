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

    enum Command derives CanEqual:
        case ScrollIntoView(id: String)
        case Navigate(path: String)

    private[kyo] val sink: Local[Maybe[Command => Unit < Async]] = Local.init(Absent)

    def scrollIntoView(id: String)(using Frame): Unit < Async =
        send(Command.ScrollIntoView(id))

    def navigate(path: String)(using Frame): Unit < Async =
        if isSameOriginPath(path) then send(Command.Navigate(path)) else ()

    /** A path the client resolves against its own origin: a leading `/` not followed by `/` or `\`, and no control character.
      *
      * Browsers read `//host` and `/\host` as a host, and strip tab and newline from a URL before parsing it, so `/\t/host` reaches
      * another origin too. The clients check the resolved origin again before assigning; this check keeps a refused path off the wire.
      */
    private[kyo] def isSameOriginPath(path: String): Boolean =
        path.nonEmpty && path.charAt(0) == '/' &&
            !(path.length > 1 && (path.charAt(1) == '/' || path.charAt(1) == '\\')) &&
            !path.exists(c => c < ' ' || c == '\u007f')

    private def send(command: Command)(using Frame): Unit < Async =
        sink.use {
            case Present(s) => s(command)
            case Absent     => ()
        }

end UICommands
