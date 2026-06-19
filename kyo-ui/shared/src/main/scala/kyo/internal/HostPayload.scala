package kyo.internal

import kyo.*

/** The serializable, FFI-free wire payload for a host-node delta pushed over the kyo-ui
  * WebSocket as the body of an [[HtmlOp.HostUpdate]]. Opaque to kyo-ui: the transport carries
  * it by `data-kyo-path` and never interprets it; an external host renderer (kyo-threejs)
  * encodes its own prop and structural variants and decodes them in its client island.
  *
  * Two leaves. `Prop` carries a single targeted prop push: a node id within the host subtree,
  * the prop slot name, and a typed value. `Structural` carries one keyed splice instruction the
  * host's reconciler applies. Every leaf is `Schema`-serializable and carries no `js.Dynamic`
  * and no closure, so the wire stays typed Scala (the wire never carries a function or a raw
  * three.js object).
  */
sealed private[kyo] trait HostPayload derives CanEqual, Schema

private[kyo] object HostPayload:
    final case class Prop(nodeId: String, slot: String, value: HostValue) extends HostPayload
    final case class Structural(op: StructuralOp)                         extends HostPayload
end HostPayload

/** The typed, FFI-free value union for a prop push. One leaf per value kind the bound setters
  * carry: a `Vec3` (position/rotation/scale), a `Color` (an opaque RGB `Int`), and a scalar
  * `Double` (opacity/intensity or radians). Each is a plain serializable Scala value, so the
  * wire carries no three.js object.
  */
sealed private[kyo] trait HostValue derives CanEqual, Schema

private[kyo] object HostValue:
    final case class V3(x: Double, y: Double, z: Double) extends HostValue
    final case class Col(rgb: Int)                       extends HostValue
    final case class Num(value: Double)                  extends HostValue
end HostValue

/** A keyed splice instruction for structural reactivity: the server-side diff of a keyed child
  * list, encoded as an insert, a remove, or a move. The result of diffing the declarative
  * children, never raw live nodes. `Insert` carries the declarative [[SceneDescriptor]] for the
  * new subtree; `Remove` and `Move` carry only the key.
  */
sealed private[kyo] trait StructuralOp derives CanEqual, Schema

private[kyo] object StructuralOp:
    final case class Insert(key: String, index: Int, descriptor: SceneDescriptor) extends StructuralOp
    final case class Remove(key: String)                                          extends StructuralOp
    final case class Move(key: String, toIndex: Int)                              extends StructuralOp
end StructuralOp

/** The serializable declarative form of a spliced subtree. Carries the flattened `Const` prop
  * values (geometry/material/transform resolved from any `Bound.Ref` at splice time on the
  * server), the kind tag, and children recursively. Carries no closure (`onClick`/`onFrame` stay
  * server-side), no signal ref (flattened to a value), and no `Custom`/`Reactive`/`Foreach`
  * node. FFI-free: an opaque typed shape the host island materializes through its own
  * reconciler.
  */
final private[kyo] case class SceneDescriptor(
    kind: String,
    props: Seq[(String, HostValue)],
    children: Seq[SceneDescriptor]
) derives CanEqual, Schema

/** The FFI-free wire form of a raycast-hit pointer (the typed analog of kyo-threejs's
  * `Pointer`), carried by [[UIEvent.HostPick]] from client to server. Holds the hit point in
  * world space, the camera distance, and the normalized-device-coordinate cursor, all plain
  * `Double`s; no three.js object crosses the wire.
  */
final private[kyo] case class PointerData(
    pointX: Double,
    pointY: Double,
    pointZ: Double,
    distance: Double,
    ndcX: Double,
    ndcY: Double
) derives CanEqual, Schema
