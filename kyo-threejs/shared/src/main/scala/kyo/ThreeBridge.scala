package kyo

import kyo.internal.HostPayload
import kyo.internal.HostValue
import kyo.internal.PointerData
import kyo.internal.SceneDescriptor
import kyo.internal.StructuralOp
import scala.scalajs.js

/** The server/client bridge between a declarative [[Three]] scene and the FFI-free
  * [[kyo.internal.HostPayload]] wire format, for the server-push host channel.
  *
  * Three responsibilities, all pure over the declarative AST (no GL):
  *
  *   - flatten: turn a scene into the inline boot payload the SSR page emits ([[flattenInit]]) and
  *     observe each server-owned `Bound.Ref` prop, emitting a `HostPayload.Prop` on each emission
  *     ([[observeProps]]). Closures (`onClick`/`onFrame`), signals, and `js.Dynamic` (`Custom`) stay
  *     server-side; only the typed payload crosses the wire.
  *   - reconstitute: rebuild a client-side [[Three]] scene from the boot payload, with every prop
  *     bound to a fresh per-slot mirror `SignalRef` ([[reconstitute]]); the client reconciler observes
  *     those mirrors, so an inbound `HostPayload.Prop` write drives exactly one targeted `patchProp`.
  *   - pick: run the hit node's server-side `onClick` closure for an inbound pick ([[runPick]]).
  *
  * A node id is its depth-first index path in the scene tree (`"r"` for the root, `"r.0"` for its
  * first child, `"r.0.1"` recursively), deterministic across flatten and reconstitute so a
  * `HostPayload.Prop(nodeId, slot, value)` the server emits addresses the same node the client
  * reconstituted.
  */
private[kyo] object ThreeBridge:

    val rootId: String = "r"

    private def childId(parent: String, index: Int): String = s"$parent.$index"

    // The child nodes of any AST node (empty for a leaf). `children` lives on Three.Ast.Node, not the
    // Three base, so this matches the node taxonomy uniformly.
    private def childrenOf(node: Three): Chunk[Three] =
        node match
            case n: Three.Ast.Node => n.children
            case _                 => Chunk.empty

    // ---- Slot naming ----------------------------------------------------------------
    // Each reactive prop is named by a stable slot string shared by flatten and reconstitute. The
    // value kind (V3 / Col / Num) disambiguates how the client mirror patches the live object; the
    // slot string identifies WHICH prop of the node.

    val slotPosition  = "position"
    val slotRotation  = "rotation"
    val slotScale     = "scale"
    val slotColor     = "color"
    val slotOpacity   = "opacity"
    val slotMetalness = "metalness"
    val slotRoughness = "roughness"
    val slotEmissive  = "emissive"
    val slotIntensity = "intensity"

    // ---- Flatten (server -> wire) ---------------------------------------------------

    /** The inline boot payload for a scene: the full tree flattened to one
      * `HostPayload.Structural(Insert(rootId, 0, descriptor))`, with every `Bound.Ref` resolved to
      * its current server-side value. The island decodes it and reconstitutes the client scene.
      */
    def flattenInit(scene: Three)(using Frame): HostPayload < Sync =
        flattenNode(scene).map(d => HostPayload.Structural(StructuralOp.Insert(rootId, 0, d)))

    /** Flattens one node (and its subtree) to a [[SceneDescriptor]]: the kind tag, the resolved prop
      * values, and children recursively. Each `Bound.Ref` is resolved to its current value via
      * `signal.current`; closures and `js.Dynamic` (a `Custom` node) are dropped (they do not cross
      * the wire).
      */
    def flattenNode(node: Three)(using Frame): SceneDescriptor < Sync =
        resolveProps(node).map { props =>
            val kind = kindOf(node)
            Kyo.foreach(childrenOf(node))(flattenNode).map { kids =>
                SceneDescriptor(kind, props, kids.toSeq)
            }
        }

    private def kindOf(node: Three): String =
        node match
            case _: Three.Ast.Scene             => "scene"
            case _: Three.Ast.Group             => "group"
            case _: Three.Ast.Mesh              => "mesh"
            case _: Three.Ast.Light.Ambient     => "light.ambient"
            case _: Three.Ast.Light.Directional => "light.directional"
            case _: Three.Ast.Light.Point       => "light.point"
            case _: Three.Ast.Light.Spot        => "light.spot"
            case _: Three.Ast.Light.Hemisphere  => "light.hemisphere"
            case _                              => "group"

    // Resolves the reactive-or-const prop values of one node to a Seq[(slot, HostValue)], reading the
    // current value of any Bound.Ref. Only the prop kinds the client mirror can patch are emitted.
    private def resolveProps(node: Three)(using Frame): Seq[(String, HostValue)] < Sync =
        val acc = collectBounds(node)
        Kyo.foreach(acc) { case (slot, bound) =>
            currentValue(bound).map(hv => (slot, hv))
        }.map(_.toSeq)
    end resolveProps

    private def currentValue(bound: Bound[Any])(using Frame): HostValue < Sync =
        bound match
            case Bound.Const(v) => Sync.defer(toHostValue(v))
            case Bound.Ref(sig) => sig.current.map(toHostValue)

    private def toHostValue(v: Any): HostValue =
        v match
            case c: Vec3   => HostValue.V3(c.x, c.y, c.z)
            case col: Int  => HostValue.Col(col)
            case d: Double => HostValue.Num(d)
            case other     => HostValue.Num(other.asInstanceOf[Double])

    // Collects the (slot, Bound) pairs the channel mirrors for a node: transform vec3 slots, plus the
    // material/light color/scalar slots. Color is an opaque Int and Normal/Double a Double, so each
    // Bound[A] is read as Bound[Any] uniformly.
    private def collectBounds(node: Three): Seq[(String, Bound[Any])] =
        def t(transform: Three.Ast.Transform): Seq[(String, Bound[Any])] =
            Seq(
                transform.position.map(b => slotPosition -> b.asInstanceOf[Bound[Any]]),
                transform.rotation.map(b => slotRotation -> b.asInstanceOf[Bound[Any]]),
                transform.scale.map(b => slotScale -> b.asInstanceOf[Bound[Any]])
            ).flatMap(_.toList)

        node match
            case m: Three.Ast.Mesh =>
                t(m.props.transform) ++ materialBounds(m.material)
            case g: Three.Ast.Group =>
                t(g.props.transform)
            case l: Three.Ast.Light.Ambient =>
                Seq(slotColor -> l.color.asInstanceOf[Bound[Any]], slotIntensity -> l.intensity.asInstanceOf[Bound[Any]])
            case l: Three.Ast.Light.Directional =>
                Seq(slotColor -> l.color.asInstanceOf[Bound[Any]], slotIntensity -> l.intensity.asInstanceOf[Bound[Any]]) ++
                    t(l.props)
            case l: Three.Ast.Light.Point =>
                Seq(slotColor -> l.color.asInstanceOf[Bound[Any]], slotIntensity -> l.intensity.asInstanceOf[Bound[Any]]) ++
                    t(l.props)
            case l: Three.Ast.Light.Spot =>
                Seq(slotColor -> l.color.asInstanceOf[Bound[Any]], slotIntensity -> l.intensity.asInstanceOf[Bound[Any]]) ++
                    t(l.props)
            case l: Three.Ast.Light.Hemisphere =>
                Seq(
                    slotColor     -> l.sky.asInstanceOf[Bound[Any]],
                    slotEmissive  -> l.ground.asInstanceOf[Bound[Any]],
                    slotIntensity -> l.intensity.asInstanceOf[Bound[Any]]
                )
            case _ => Seq.empty
        end match
    end collectBounds

    private def materialBounds(material: Three.Ast.Material): Seq[(String, Bound[Any])] =
        material match
            case m: Three.Ast.Material.Basic =>
                Seq(slotColor -> m.color.asInstanceOf[Bound[Any]], slotOpacity -> m.opacity.asInstanceOf[Bound[Any]])
            case m: Three.Ast.Material.Standard =>
                Seq(
                    slotColor     -> m.color.asInstanceOf[Bound[Any]],
                    slotOpacity   -> m.opacity.asInstanceOf[Bound[Any]],
                    slotMetalness -> m.metalness.asInstanceOf[Bound[Any]],
                    slotRoughness -> m.roughness.asInstanceOf[Bound[Any]],
                    slotEmissive  -> m.emissive.asInstanceOf[Bound[Any]]
                )
            case m: Three.Ast.Material.Line =>
                Seq(slotColor -> m.color.asInstanceOf[Bound[Any]], slotOpacity -> m.opacity.asInstanceOf[Bound[Any]])
            case m: Three.Ast.Material.Points =>
                Seq(slotColor -> m.color.asInstanceOf[Bound[Any]], slotOpacity -> m.opacity.asInstanceOf[Bound[Any]])
            case _ => Seq.empty

    // ---- Server-side observation (server -> client over the channel) ----------------

    /** Forks one observe fiber per server-owned `Bound.Ref` prop in the scene: each emission encodes
      * the new value as a `HostPayload.Prop(nodeId, slot, value)` and calls `emit`. The node ids match
      * the reconstituted client scene's ids, so each emission addresses the client mirror that drives
      * one targeted patchProp. This is the prop half of `HostBridge.subscriptions`; the structural
      * half (a keyed-diff emitting `HostPayload.Structural`) is added separately.
      */
    def observeProps(scene: Three, emit: HostPayload => Unit < Async)(using Frame): Unit < (Async & Scope) =
        Kyo.foreachDiscard(mirrorRefs(rootId, scene)) { case (nodeId, slot, sig) =>
            Fiber.init {
                sig.observe { value =>
                    emit(HostPayload.Prop(nodeId, slot, toHostValue(value)))
                }
            }.unit
        }

    /** Walks a scene collecting every `(nodeId, slot, signal)` for a `Bound.Ref` prop. On the server
      * scene the signals are the server-owned ones `observeProps` subscribes; on a reconstituted
      * client scene every prop slot is a mirror `Bound.Ref`, so this recovers the channel's mirror
      * map keyed by the same node-id scheme.
      */
    private[kyo] def mirrorRefs(nodeId: String, node: Three): Seq[(String, String, Signal[Any])] =
        val here = collectBounds(node).collect {
            case (slot, Bound.Ref(sig)) => (nodeId, slot, sig.asInstanceOf[Signal[Any]])
        }
        val kids = childrenOf(node).toSeq.zipWithIndex.flatMap { case (child, i) =>
            mirrorRefs(childId(nodeId, i), child)
        }
        here ++ kids
    end mirrorRefs

    // ---- Reconstitute (wire -> client scene with mirror signals) --------------------

    /** Reconstitutes a client-side scene from the boot payload, allocating one mirror `SignalRef` per
      * prop slot seeded with the boot value, and returns the scene plus the `(nodeId, slot) -> mirror`
      * map. The client reconciler observes the mirrors; an inbound `HostPayload.Prop` write feeds them.
      * A boot payload that is not the expected `Structural(Insert(...))` reconstitutes an empty scene.
      */
    def reconstitute(payload: HostPayload)(using Frame): (Three, Map[(String, String), SignalRef[Any]]) < Sync =
        payload match
            case HostPayload.Structural(StructuralOp.Insert(_, _, descriptor)) =>
                buildNode(rootId, descriptor).map { case (node, mirrors) =>
                    (node, mirrors.toMap)
                }
            case _ =>
                Sync.defer((Three.scene(), Map.empty[(String, String), SignalRef[Any]]))

    // Builds one node from a descriptor, threading the accumulated mirror map. Each prop slot gets a
    // fresh SignalRef seeded with the descriptor's boot value; the node binds Bound.Ref(mirror) so the
    // client reconciler's observe drives the patch on a later write.
    private def buildNode(
        nodeId: String,
        d: SceneDescriptor
    )(using Frame): (Three, Seq[((String, String), SignalRef[Any])]) < Sync =
        mirrorsFor(nodeId, d.props).map { mirrors =>
            val slotRef: String => Maybe[SignalRef[Any]] =
                slot => Maybe.fromOption(mirrors.find(_._1 == (nodeId, slot)).map(_._2))
            Kyo.foreach(d.children.zipWithIndex) { case (child, i) =>
                buildNode(childId(nodeId, i), child)
            }.map { built =>
                val childNodes  = built.map(_._1)
                val childMirror = built.flatMap(_._2)
                val node        = materializeNode(d, slotRef, Chunk.from(childNodes))
                (node, mirrors ++ childMirror)
            }
        }

    // Allocates a mirror SignalRef per prop slot seeded with the boot HostValue. The mirror holds the
    // raw underlying value (Vec3 / Int / Double) so the client patch setter reads it the same way a
    // local Bound.Ref signal would.
    // The mirror holds a heterogeneous raw value (Vec3 / Color / Double), so the change-detection
    // CanEqual is the universal instance: a mirror write that differs from the prior value wakes the
    // reconciler observe, which patches the live object.
    private given CanEqual[Any, Any] = CanEqual.derived

    private def mirrorsFor(
        nodeId: String,
        props: Seq[(String, HostValue)]
    )(using Frame): Seq[((String, String), SignalRef[Any])] < Sync =
        Kyo.foreach(props) { case (slot, hv) =>
            Signal.initRef[Any](rawValue(hv)).map(ref => ((nodeId, slot), ref))
        }.map(_.toSeq)

    private def rawValue(hv: HostValue): Any =
        hv match
            case HostValue.V3(x, y, z) => Vec3(x, y, z)
            case HostValue.Col(rgb)    => Color(rgb)
            case HostValue.Num(v)      => v

    // Builds the concrete Three node for a descriptor kind, binding each known prop slot to its mirror
    // (Bound.Ref) so the client reconciler observes it. Unknown kinds fall back to an empty group.
    private def materializeNode(
        d: SceneDescriptor,
        slotRef: String => Maybe[SignalRef[Any]],
        children: Chunk[Three]
    )(using Frame): Three =
        def colorRef(slot: String, default: Color): Bound[Color] =
            slotRef(slot) match
                case Present(ref) => Bound.Ref(ref.asInstanceOf[Signal[Color]])
                case Absent       => Bound.Const(default)
        def normalRef(slot: String, default: Normal): Bound[Normal] =
            slotRef(slot) match
                case Present(ref) => Bound.Ref(ref.asInstanceOf[Signal[Normal]])
                case Absent       => Bound.Const(default)
        def doubleRef(slot: String, default: Double): Bound[Double] =
            slotRef(slot) match
                case Present(ref) => Bound.Ref(ref.asInstanceOf[Signal[Double]])
                case Absent       => Bound.Const(default)
        def transform: Three.Ast.Transform =
            Three.Ast.Transform(
                position = slotRef(slotPosition).map(r => Bound.Ref(r.asInstanceOf[Signal[Vec3]])),
                rotation = slotRef(slotRotation).map(r => Bound.Ref(r.asInstanceOf[Signal[Vec3]])),
                scale = slotRef(slotScale).map(r => Bound.Ref(r.asInstanceOf[Signal[Vec3]]))
            )

        d.kind match
            case "scene" =>
                Three.Ast.Scene(Three.Ast.SceneProps(transform = transform), children)
            case "group" =>
                Three.Ast.Group(Three.Ast.MeshProps(transform = transform), children)
            case "mesh" =>
                val material =
                    Three.Ast.Material.Standard(
                        color = colorRef(slotColor, Color(0xffffff)),
                        metalness = normalRef(slotMetalness, Normal(0.0)),
                        roughness = normalRef(slotRoughness, Normal(1.0)),
                        opacity = normalRef(slotOpacity, Normal(1.0)),
                        map = Absent,
                        emissive = colorRef(slotEmissive, Color(0x000000))
                    )
                Three.Ast.Mesh(Three.Geometry.box(), material, Three.Ast.MeshProps(transform = transform), children)
            case "light.ambient" =>
                Three.Ast.Light.Ambient(colorRef(slotColor, Color(0xffffff)), doubleRef(slotIntensity, 1.0), transform)
            case "light.directional" =>
                Three.Ast.Light.Directional(colorRef(slotColor, Color(0xffffff)), doubleRef(slotIntensity, 1.0), transform)
            case "light.point" =>
                Three.Ast.Light.Point(colorRef(slotColor, Color(0xffffff)), doubleRef(slotIntensity, 1.0), 0.0, transform)
            case "light.spot" =>
                Three.Ast.Light.Spot(
                    colorRef(slotColor, Color(0xffffff)),
                    doubleRef(slotIntensity, 1.0),
                    Radians.rad(0.5),
                    Normal(0.0),
                    transform
                )
            case "light.hemisphere" =>
                Three.Ast.Light.Hemisphere(
                    colorRef(slotColor, Color(0xffffff)),
                    colorRef(slotEmissive, Color(0x000000)),
                    doubleRef(slotIntensity, 1.0),
                    transform
                )
            case _ =>
                Three.Ast.Group(Three.Ast.MeshProps(transform = transform), children)
        end match
    end materializeNode

    // ---- Pick (client pick -> server closure) ---------------------------------------

    /** Runs the server-side `onClick` closure for an inbound pick. The pick names the hit node by id;
      * the closure runs server-side and may write a server signal (which pushes back as a
      * HostUpdate). A pick for a node with no `onClick` is a no-op.
      */
    def runPick(scene: Three, nodeId: String, pointer: PointerData)(using Frame): Unit < Async =
        nodeById(rootId, scene, nodeId) match
            case Present(i: Three.Ast.Interactive) =>
                i.meshProps.onClick match
                    case Present(f) => f(toPointer(pointer)).unit
                    case Absent     => Kyo.unit
            case _ => Kyo.unit

    private def nodeById(thisId: String, node: Three, target: String): Maybe[Three] =
        if thisId == target then Present(node)
        else
            childrenOf(node).toSeq.zipWithIndex.view
                .map { case (child, i) => nodeById(childId(thisId, i), child, target) }
                .collectFirst { case p @ Present(_) => p }
                .getOrElse(Absent)

    private def toPointer(p: PointerData): Pointer =
        Pointer(Vec3(p.pointX, p.pointY, p.pointZ), p.distance, (p.ndcX, p.ndcY), Pointer.Buttons.none)

end ThreeBridge
