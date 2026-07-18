package kyo.internal

import kyo.*

/** Which pointer interaction a [[PointerWire]] carries.
  *
  * The wire needs this because the three interactions travel the SAME channel (a `UIEvent.BackendEvent`
  * addressed to one path) and resolve to three DIFFERENT handlers on the node at that path. Without a
  * kind, a hover and a click arriving at the same object are indistinguishable, and the server can only
  * ever run one of them.
  */
private[kyo] enum PointerKind derives CanEqual, Schema:
    case Click, Over, Out

/** A `private[kyo]` all-primitive wire projection of the public [[Three.Pointer]] (`Vec3` flattened
  * to three `Double`s) plus the [[PointerKind]] that says which handler it addresses, so it derives
  * `Schema` with no dependency on `Pointer`/`Vec3` gaining a public `Schema` of their own; the public
  * `Pointer` surface stays unchanged.
  */
final private[kyo] case class PointerWire(
    kind: PointerKind,
    px: Double,
    py: Double,
    pz: Double,
    distance: Double,
    ndcX: Double,
    ndcY: Double,
    left: Boolean,
    right: Boolean,
    middle: Boolean
) derives Schema

private[kyo] object PointerWire:
    def encode(kind: PointerKind, p: Three.Pointer)(using Frame): String = Json.encode[PointerWire](project(kind, p))

    def decode(s: String)(using Frame): Maybe[(PointerKind, Three.Pointer)] =
        Json.decode[PointerWire](s).toMaybe.map(w => (w.kind, rebuild(w)))

    private def project(kind: PointerKind, p: Three.Pointer): PointerWire =
        PointerWire(
            kind = kind,
            px = p.point.x,
            py = p.point.y,
            pz = p.point.z,
            distance = p.distance,
            ndcX = p.ndc._1,
            ndcY = p.ndc._2,
            left = p.buttons.left,
            right = p.buttons.right,
            middle = p.buttons.middle
        )

    private def rebuild(w: PointerWire): Three.Pointer =
        Three.Pointer(
            point = Three.Vec3(w.px, w.py, w.pz),
            distance = w.distance,
            ndc = (w.ndcX, w.ndcY),
            buttons = Three.Pointer.Buttons(left = w.left, right = w.right, middle = w.middle)
        )
end PointerWire
