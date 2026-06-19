package kyo.internal

import kyo.*

/** The serializable, FFI-free wire payload for a host-node delta pushed over the kyo-ui
  * WebSocket as the body of an [[HtmlOp.HostUpdate]]. Opaque to kyo-ui: the transport carries
  * it by `data-kyo-path` and never interprets it; an external host renderer (kyo-threejs)
  * encodes its own prop and structural variants and decodes them in its client island.
  *
  * Three leaves. `Prop` carries a single targeted prop push: a node id within the host subtree,
  * the prop slot name, and a typed value. `Structural` carries one keyed splice instruction the
  * host's reconciler applies, plus the `regionId` node id of the host-subtree region (a
  * `foreach`/`reactive` holder) the splice targets, so a scene that mixes static siblings with
  * several reactive regions routes each op to the right holder rather than the host root. The
  * `regionId` defaults to the host root (`"r"`) so a single-region-at-root scene carries the
  * same wire shape as before. `Boot` is the page-load boot envelope: it carries the scene's
  * root [[StructuralOp.Insert]] alongside the [[CameraDescriptor]] for the embed's camera, so the
  * client reconstitutes the server's actual viewpoint rather than a default one. Every leaf is
  * `Schema`-serializable and carries no `js.Dynamic` and no closure, so the wire stays typed Scala
  * (the wire never carries a function or a raw three.js object).
  */
sealed private[kyo] trait HostPayload derives CanEqual, Schema

private[kyo] object HostPayload:
    final case class Prop(nodeId: String, slot: String, value: HostValue)        extends HostPayload
    final case class Structural(op: StructuralOp, regionId: String = "r")        extends HostPayload
    final case class Boot(insert: StructuralOp.Insert, camera: CameraDescriptor) extends HostPayload
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
  * server), the kind tag, and children recursively. For a `mesh` kind it also carries the typed
  * [[GeometryDescriptor]] (the geometry shape and its numeric params) and the [[MaterialKind]]
  * tag (which material class to rebuild), so the client reconstitutes the server's actual sphere/
  * torus/cylinder and its actual basic/standard/line/points material rather than a hardcoded box
  * with a standard material. Both default to `Absent` so a non-mesh descriptor (a scene, a group,
  * a light, a region holder) keeps the prior wire shape. Carries no closure (`onClick`/`onFrame`
  * stay server-side), no signal ref (flattened to a value), and no `Custom`/`Reactive`/`Foreach`
  * node (a `Custom` geometry or material drops at flatten time). FFI-free: an opaque typed shape
  * the host island materializes through its own reconciler.
  */
final private[kyo] case class SceneDescriptor(
    kind: String,
    props: Seq[(String, HostValue)],
    children: Seq[SceneDescriptor],
    geometry: Maybe[GeometryDescriptor] = Absent,
    material: Maybe[MaterialKind] = Absent
) derives CanEqual, Schema

/** The serializable, FFI-free shape of a non-`Custom` geometry: the geometry kind plus its numeric
  * construction params, one leaf per `Three.Geometry.*` factory the AST supports. A `Custom`
  * geometry (a closure producing a raw three.js `BufferGeometry`) cannot cross the wire and is the
  * one exception that stays server-side, so it has no leaf here; flattening a `Custom` mesh fails
  * with the host bridge's typed unserializable failure. Each leaf carries exactly the params the
  * matching `Three.Geometry.*` factory takes, so the client rebuilds the identical geometry.
  */
sealed private[kyo] trait GeometryDescriptor derives CanEqual, Schema

private[kyo] object GeometryDescriptor:
    final case class Box(width: Double, height: Double, depth: Double)                                      extends GeometryDescriptor
    final case class Sphere(radius: Double, widthSegments: Int, heightSegments: Int)                        extends GeometryDescriptor
    final case class Plane(width: Double, height: Double)                                                   extends GeometryDescriptor
    final case class Cylinder(radiusTop: Double, radiusBottom: Double, height: Double, radialSegments: Int) extends GeometryDescriptor
    final case class Cone(radius: Double, height: Double, radialSegments: Int)                              extends GeometryDescriptor
    final case class Torus(radius: Double, tube: Double, radialSegments: Int, tubularSegments: Int)         extends GeometryDescriptor
end GeometryDescriptor

/** The serializable, FFI-free tag for a non-`Custom` material class, one leaf per `Three.Material.*`
  * factory the AST supports. The material's prop VALUES (color/opacity/metalness/roughness/emissive)
  * cross as [[HostValue]] props on the descriptor; this tag carries only which material CLASS to
  * rebuild, plus the one non-bound numeric param a `Points` material adds (its point `size`). A
  * `Custom` material (a closure producing a raw three.js material) cannot cross the wire and is the
  * one exception that stays server-side, so it has no leaf here.
  */
sealed private[kyo] trait MaterialKind derives CanEqual, Schema

private[kyo] object MaterialKind:
    case object Basic                     extends MaterialKind
    case object Standard                  extends MaterialKind
    case object Line                      extends MaterialKind
    final case class Points(size: Double) extends MaterialKind
end MaterialKind

/** The serializable, FFI-free form of the embed's camera, carried in the [[HostPayload.Boot]]
  * envelope so the client reconstitutes the server's actual viewpoint (position, lookAt, fov/
  * viewSize, near/far) rather than a hardcoded default. One leaf per `Three.Camera.*` factory. The
  * angle is a plain `Double` of radians and the points plain `Double` triples, so no opaque type or
  * three.js object crosses the wire.
  */
sealed private[kyo] trait CameraDescriptor derives CanEqual, Schema

private[kyo] object CameraDescriptor:
    final case class Perspective(
        fovRadians: Double,
        near: Double,
        far: Double,
        position: HostValue.V3,
        lookAt: HostValue.V3
    ) extends CameraDescriptor
    final case class Orthographic(
        viewSize: Double,
        near: Double,
        far: Double,
        position: HostValue.V3,
        lookAt: HostValue.V3
    ) extends CameraDescriptor
end CameraDescriptor

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
