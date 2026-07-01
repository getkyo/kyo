package kyo.internal

import kyo.*

/** Server → client operation. */
sealed private[kyo] trait HtmlOp derives CanEqual

private[kyo] object HtmlOp:
    case class Replace(path: Seq[String], html: String) extends HtmlOp derives Schema
    case class Remove(path: Seq[String])                extends HtmlOp derives Schema
    case class InjectCss(css: String)                   extends HtmlOp derives Schema
    // The generic path-addressed scalar prop set: set the prop `key` of the live node at `path` to
    // the Schema-encoded value `encoded`. Backend-agnostic; the client routes by path to the owning
    // backend, which decodes and applies. Replaces HostPayload.SignalUpdate. The path is Seq[String]
    // to mirror Replace/Remove; the payload is a typed String, never a js.Dynamic or a closure.
    case class SetProp(path: Seq[String], key: String, encoded: String) extends HtmlOp derives Schema
    // The generic structural feed: replace the subtree at `path` with the Schema-encoded snapshot (a
    // Chunk[A] for a foreach feed). The owning backend decodes and runs its keyed reconcile.
    // Replaces HostPayload.SignalChunk.
    case class ReplaceSubtree(path: Seq[String], encoded: String) extends HtmlOp derives Schema
end HtmlOp
