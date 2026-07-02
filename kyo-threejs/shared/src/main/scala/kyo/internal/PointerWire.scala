package kyo.internal

import kyo.*

/** A `private[kyo]` all-primitive wire projection of the public [[Three.Pointer]] (`Vec3` flattened
  * to three `Double`s) so it derives `Schema` with no dependency on `Pointer`/`Vec3` gaining a public
  * `Schema` of their own; the public `Pointer` surface stays unchanged.
  */
final private[kyo] case class PointerWire(
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
    def encode(p: Three.Pointer)(using Frame): String = Json.encode[PointerWire](project(p))

    def decode(s: String)(using Frame): Maybe[Three.Pointer] = Json.decode[PointerWire](s).toMaybe.map(rebuild)

    private def project(p: Three.Pointer): PointerWire =
        PointerWire(
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
