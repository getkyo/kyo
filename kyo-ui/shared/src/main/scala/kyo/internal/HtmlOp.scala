package kyo.internal

import kyo.*

/** Server → client operation. */
sealed private[kyo] trait HtmlOp derives CanEqual

private[kyo] object HtmlOp:

    // --- Rendering operations ---

    case class Replace(path: Seq[String], html: String) extends HtmlOp derives Schema
    case class Remove(path: Seq[String])                extends HtmlOp derives Schema
    case class InjectCss(css: String)                   extends HtmlOp derives Schema
    case class ScrollIntoView(id: String)               extends HtmlOp derives Schema

    // --- Drag operations ---

    /** Requests a bounded byte range from a dropped browser file. */
    final case class ReadDropFile(requestId: String, token: String, offset: ByteSize, maxSize: ByteSize)
        extends HtmlOp derives Schema

    /** Requests a bounded page of metadata from a dropped browser directory. */
    final case class ReadDropDirectory(requestId: String, token: String, cursor: Maybe[String], maxEntries: Int)
        extends HtmlOp derives Schema

    /** Cancels an outstanding dropped file or directory read. */
    final case class CancelDropRead(requestId: String) extends HtmlOp derives Schema

    /** Resolves a browser drag session with the server's acceptance decision. */
    final case class ResolveDrag(sessionId: String, decision: Drag.Decision) extends HtmlOp derives Schema
end HtmlOp
