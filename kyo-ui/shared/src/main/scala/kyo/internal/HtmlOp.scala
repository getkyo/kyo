package kyo.internal

import kyo.*

/** Server → client operation. */
sealed private[kyo] trait HtmlOp derives CanEqual

private[kyo] object HtmlOp:
    case class Replace(path: Seq[String], html: String) extends HtmlOp derives Schema
    case class Remove(path: Seq[String])                extends HtmlOp derives Schema
    case class InjectCss(css: String)                   extends HtmlOp derives Schema
    // A host-node delta addressed by the same data-kyo-path scheme as Replace/Remove: the
    // server emits it for a host subtree whose bound signal emitted; the client routes it by
    // path to the host's live channel. The payload is opaque typed Scala (no js.Dynamic).
    case class HostUpdate(path: Seq[String], payload: HostPayload) extends HtmlOp derives Schema
end HtmlOp
