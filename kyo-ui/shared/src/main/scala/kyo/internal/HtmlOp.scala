package kyo.internal

import kyo.*

/** Server → client operation. */
sealed private[kyo] trait HtmlOp derives CanEqual

private[kyo] object HtmlOp:
    // `html` is the region's bare content fragment (zero..n roots, no markers: the live comment
    // markers stay in the DOM and are never re-sent). `mount = true` marks a keyless mount's own
    // paint: the client stamps the `m` flag onto the live start marker so parent morphs treat the
    // span as opaque.
    case class Replace(path: Seq[String], html: String, mount: Boolean = false) extends HtmlOp derives Schema
    case class Remove(path: Seq[String])                                        extends HtmlOp derives Schema
    case class InjectCss(css: String)                                           extends HtmlOp derives Schema
    case class ScrollIntoView(id: String)                                       extends HtmlOp derives Schema
end HtmlOp
