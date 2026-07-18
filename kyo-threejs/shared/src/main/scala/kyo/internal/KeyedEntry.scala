package kyo.internal

import kyo.Three

/** One element of a `Foreach` emission: its reconciliation key, the ITEM it was rendered from, and
  * the node that item rendered to.
  *
  * The item, not the node, is what says whether the element changed. `render` is a pure function of
  * the item, so an unchanged item renders an equivalent subtree and a changed item must re-render.
  * The rendered node cannot serve as the comparison key: a render wraps a fresh `onClick` closure
  * every time, so two renders of the SAME item are never `equals`.
  *
  * Pure and cross-platform: the server-side `decodeKeyed` path (`Three.scala`) rebuilds these from a
  * Schema-decoded emission, while the client reconciler consumes them to diff live objects.
  */
final private[kyo] case class KeyedEntry(key: String, item: Any, node: Three)
