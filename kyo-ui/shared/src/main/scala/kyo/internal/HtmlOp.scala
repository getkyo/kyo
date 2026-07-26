package kyo.internal

import kyo.*

/** Server → client operation. */
sealed private[kyo] trait HtmlOp derives CanEqual

private[kyo] object HtmlOp:
    case class Replace(path: Seq[String], html: String) extends HtmlOp derives Schema
    case class Remove(path: Seq[String])                extends HtmlOp derives Schema
    case class InjectCss(css: String)                   extends HtmlOp derives Schema
    case class ScrollIntoView(id: String)               extends HtmlOp derives Schema
end HtmlOp
