package kyo.internal

import kyo.*

private[kyo] object UISession:

    /** A live session with dispatch function, exchange channel, initial HTML, fibers, and settlement tracking. */
    case class Session(
        id: String,
        dispatch: (Seq[String], UIEvent) => Boolean < Async,
        channel: Channel[HtmlOp],
        initialHtml: String,
        fibers: Seq[Fiber[Unit, Any]],
        lastSignalChangeTime: AtomicRef[Instant],
        lastChangeTime: AtomicRef[Instant]
    )

    /** Create a new session: normalize UI, render HTML, subscribe reactive boundaries. */
    def create(ui: UI)(using Frame): Session < Async =
        for
            channel        <- Channel.initUnscoped[HtmlOp](256)
            now            <- Clock.now
            lastChangeTime <- AtomicRef.init(now)
            root           <- ReactiveUI.normalize(ui, Seq.empty)
            html           <- HtmlRenderer.render(ui, Seq.empty)
            exchange = ChannelExchange(root, channel, lastChangeTime)
            sub <- ReactiveUI.subscribe(root, exchange)
        yield Session(
            id = java.util.UUID.randomUUID().toString,
            dispatch = sub.handle,
            channel = channel,
            initialHtml = html,
            fibers = sub.fibers,
            lastSignalChangeTime = sub.lastSignalChangeTime,
            lastChangeTime = lastChangeTime
        )

    /** Dispatch an event into a session. Fire-and-forget; signal mutations trigger async Replace pushes. */
    def handleEvent(session: Session, event: UIEvent)(using Frame): Unit < Async =
        session.dispatch(event.path, event).unit

    private class ChannelExchange(root: ReactiveUI, channel: Channel[HtmlOp], lastChangeTime: AtomicRef[Instant]) extends UIExchange:
        private def svgContextAt(path: Seq[String]): Boolean =
            ReactiveUI.findNode(root, path).map(_.svgContext).getOrElse(false)

        def onChange(path: Seq[String], ui: UI)(using Frame): Unit < Async =
            HtmlRenderer.render(ui, path).map { html =>
                // Always wrap in a boundary marker so the element with data-kyo-path=path survives
                // subsequent SSE Replaces (Fragments and direct child rendering would otherwise lose the marker).
                // In SVG context use a <g> wrapper (a <span> is invalid inside <svg>).
                val pathStr   = path.mkString(".")
                val tag       = if svgContextAt(path) then "g" else "span"
                val finalHtml = s"""<$tag data-kyo-path="$pathStr" data-kyo-reactive>$html</$tag>"""
                Abort.run[Closed](channel.put(HtmlOp.Replace(path, finalHtml))).unit
            }.andThen(Clock.now.map(now => lastChangeTime.set(now)))
        end onChange
    end ChannelExchange

end UISession
