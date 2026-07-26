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
    // Contracts for these imperative ops live on UI.Commands (requestMeasure / command / *ById).
    case class RequestMeasure(path: Seq[String])        extends HtmlOp derives Schema
    case class Command(path: Seq[String], verb: String) extends HtmlOp derives Schema
    // Id-addressed twins: the client resolves the target by getElementById(id) instead of the data-kyo-path querySelector.
    case class CommandById(id: String, verb: String) extends HtmlOp derives Schema
    case class RequestMeasureById(id: String)        extends HtmlOp derives Schema
end HtmlOp
