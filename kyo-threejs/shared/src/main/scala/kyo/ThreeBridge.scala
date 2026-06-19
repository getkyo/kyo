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

    /** The typed failure raised when a node that cannot cross the wire (a `Custom` carrying a closure or
      * a raw three.js object, or a `Reactive`/`Foreach` carrying a server-side signal) is reached while
      * flattening a subtree to the serializable wire form. Internal to the host bridge: the public
      * module surface is unchanged, and the flatten/diff seam carries this as the `Abort` leaf rather
      * than silently shipping an empty descriptor. `NonFatal`, so a containment boundary can recover it.
      */
    final private[kyo] class UnserializableNode(val kind: String)
        extends Exception(s"Cannot flatten a $kind node to the serializable wire form")

    private def childId(parent: String, index: Int): String = s"$parent.$index"

    // The child nodes of any AST node (empty for a leaf). `children` lives on Three.Ast.Node, not the
    // Three base, so this matches the node taxonomy uniformly.
    private def childrenOf(node: Three): Chunk[Three] =
        node match
            case n: Three.Ast.Node => n.children
            case null              => Chunk.empty

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
      * its current server-side value. The island decodes it and reconstitutes the client scene. The
      * boot path is the documented-drop side of the closure-drop boundary: an unserializable node
      * reached while flattening is logged and the boot falls back to an empty scene, so a malformed
      * scene boots nothing rather than throwing into the SSR render; the hard-failing side is the
      * structural Insert diff, which propagates the typed failure.
      */
    def flattenInit(scene: Three)(using Frame): HostPayload < Sync =
        Abort.run[UnserializableNode](flattenNode(scene)).map {
            case Result.Success(d) => HostPayload.Structural(StructuralOp.Insert(rootId, 0, d))
            case Result.Failure(e) =>
                Log.error(s"host boot scene carries an unserializable node: ${e.getMessage}").andThen {
                    HostPayload.Structural(StructuralOp.Insert(rootId, 0, SceneDescriptor("scene", Seq.empty, Seq.empty)))
                }
            case Result.Panic(e) => Abort.panic(e)
        }

    // The serializable placeholder a structural reactive region (Foreach/Reactive) flattens to: an
    // empty "group" holder occupying the region's deterministic node-id position. The client
    // reconstitutes the same empty holder, and the region's children arrive over the structural
    // channel (observeStructure's initial diff splices them into this holder). This mirrors the
    // client-direct reconciler, which materializes a Foreach/Reactive as an empty holder Group at the
    // node's position, so the boot preserves the region's siblings (lights, static meshes) and the
    // node-id path stays identical across flatten, reconstitute, and the structural ops' region ids.
    private val regionPlaceholder: SceneDescriptor = SceneDescriptor("group", Seq.empty, Seq.empty)

    /** Flattens one node (and its subtree) to a [[SceneDescriptor]]: the kind tag, the resolved prop
      * values, and children recursively. Each `Bound.Ref` is resolved to its current value via
      * `signal.current`. A `Reactive`/`Foreach` flattens to an empty "group" placeholder holding the
      * region's node-id position (its children arrive over the structural channel). A `Custom` carrying
      * a closure or a raw three.js object cannot cross the wire and fails with a typed
      * [[UnserializableNode]] rather than a silently empty descriptor, so a Custom subtree spliced
      * server-side is caught at flatten time (the closure-drop boundary).
      */
    def flattenNode(node: Three)(using Frame): SceneDescriptor < (Sync & Abort[UnserializableNode]) =
        unserializableKind(node) match
            case Present(kind) => Abort.fail(new UnserializableNode(kind))
            case Absent =>
                node match
                    case _: Three.Ast.Reactive | _: Three.Ast.Foreach[?] =>
                        // A structural reactive region: a position-preserving empty holder placeholder.
                        regionPlaceholder: SceneDescriptor < (Sync & Abort[UnserializableNode])
                    case _ =>
                        resolveProps(node).map { props =>
                            val kind = kindOf(node)
                            Kyo.foreach(childrenOf(node))(flattenNode).map { kids =>
                                SceneDescriptor(kind, props, kids.toSeq)
                            }
                        }

    // The kind tag of a node that cannot cross the wire (a closure or a js.Dynamic), or Absent when the
    // node serializes. Only `Custom` is dropped: a `Reactive`/`Foreach` is serializable as an empty
    // holder placeholder (its children stream over the structural channel), handled in flattenNode.
    private def unserializableKind(node: Three): Maybe[String] =
        node match
            case _: Three.Ast.Custom[?] => Present("custom")
            case _                      => Absent

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
            // Normal and Radians are opaque Double wrappers; at runtime they erase to Double.
            case other => HostValue.Num(other.asInstanceOf[Double])

    // Collects the (slot, Bound) pairs the channel mirrors for a node: transform vec3 slots, plus the
    // material/light color/scalar slots. Color is an opaque Int and Normal/Double a Double, so each
    // Bound[A] is read as Bound[Any] uniformly.
    // Type erasure: Bound[A] -> Bound[Any] casts below are safe because the homogeneous Seq[(String,
    // Bound[Any])] is only ever read via toHostValue, which matches on the value inside Any.
    private def collectBounds(node: Three): Seq[(String, Bound[Any])] =
        def t(transform: Three.Ast.Transform): Seq[(String, Bound[Any])] =
            Seq(
                transform.position.map(b => slotPosition -> b.asInstanceOf[Bound[Any]]),
                transform.rotation.map(b => slotRotation -> b.asInstanceOf[Bound[Any]]),
                transform.scale.map(b => slotScale -> b.asInstanceOf[Bound[Any]])
            ).flatMap(_.toChunk)

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

    /** Forks one observe fiber per server-owned structural region of the scene, both
      * `Three.Ast.Foreach` (`foreach`/`foreachKeyed`) and `Three.Ast.Reactive` (`reactive`/`render`/
      * `when`). Each emitted op is a `HostPayload.Structural(op, regionId)` whose `regionId` is the
      * region holder's deterministic node id (the same index path flatten and reconstitute assign), so
      * the client splices into the matching holder rather than the host root, and a scene that mixes a
      * region with static siblings (lights, meshes) routes each op to the right place.
      *
      * A `Foreach` region diffs its `Signal[Chunk[A]]` by key against the prior emission and emits the
      * minimal op set (`Remove`/`Move`/`Insert`). A `Reactive` region holds a `Signal[Three]`; it
      * flattens the new subtree and, when the descriptor changed, emits `Remove` of the prior child and
      * `Insert` of the new one under a stable single key (the swap-on-change semantics the client-direct
      * reconciler's `reactiveStep` mirrors). An `Insert` flattens the new subtree to a `SceneDescriptor`
      * (resolving every `Bound.Ref` to its current server-side value); `Remove` and `Move` carry only the
      * key. The diff is pure over the declarative children and the key function; only the typed
      * structural op crosses the wire. This is the structural half of `HostBridge.subscriptions`; the
      * prop half is `observeProps`.
      */
    def observeStructure(scene: Three, emit: HostPayload => Unit < Async)(using Frame): Unit < (Async & Scope) =
        Kyo.foreachDiscard(structuralRegions(rootId, scene)) { regionEntry =>
            val (regionId, region) = regionEntry
            region match
                case foreach: ForeachRegion[Any] @unchecked => observeForeachRegion(regionId, foreach, emit)
                case reactive: ReactiveRegion               => observeReactiveRegion(regionId, reactive, emit)
        }

    // Observes one Foreach region: the prior keyed snapshot is single-owner on this region's observe
    // loop. It carries the flattened descriptors so the diff emits a Move for a surviving key whose index
    // changed and an Insert for a new key, plus a Prop push for any surviving child whose flattened prop
    // value changed (a cube's index-driven position, addressed by the child's stable `regionId#key` node
    // id, so the client patches the live object without re-materializing it). Each structural op is
    // tagged with the region's node id; each Prop carries the child node id.
    private def observeForeachRegion(
        regionId: String,
        region: ForeachRegion[Any],
        emit: HostPayload => Unit < Async
    )(using Frame): Unit < (Async & Scope) =
        AtomicRef.init(Chunk.empty[(String, SceneDescriptor)]).map { prior =>
            Fiber.init {
                region.signal.observe { items =>
                    prior.get.map { snapshot =>
                        // A Custom/closure subtree rendered into the region aborts the flatten with a
                        // typed UnserializableNode (the closure-drop boundary); on the live observe path
                        // it is logged and the snapshot is left unchanged, the documented-drop side of the
                        // boundary. The hard-failing side is the flatten/diff seam tests assert against.
                        Abort.run[UnserializableNode](diffKeyedServer(regionId, region, snapshot, items)).map {
                            case Result.Success((ops, propPushes, nextSnapshot)) =>
                                prior.set(nextSnapshot).andThen {
                                    Kyo.foreachDiscard(ops)(op => emit(HostPayload.Structural(op, regionId)))
                                        .andThen(Kyo.foreachDiscard(propPushes)(emit))
                                }
                            case Result.Failure(e) =>
                                Log.error(s"structural diff dropped an unserializable subtree: ${e.getMessage}")
                            case Result.Panic(e) =>
                                Log.error("structural diff panicked", e)
                        }
                    }
                }
            }.unit
        }

    // The stable single key a Reactive region's one child is splice-keyed by on the client. A Reactive
    // holds at most one rendered subtree, so a swap is Remove(reactiveKey) + Insert(reactiveKey, 0, new).
    private val reactiveKey = "reactive"

    // Observes one Reactive region: flatten the emitted subtree and, when the descriptor changed since
    // the prior emission, emit Remove of the prior child (if any) then Insert of the new one under the
    // stable single key, both tagged with the region's node id. An identical re-emission is a no-op, so
    // a Reactive whose value does not change after boot splices once and never thrashes.
    private def observeReactiveRegion(
        regionId: String,
        region: ReactiveRegion,
        emit: HostPayload => Unit < Async
    )(using Frame): Unit < (Async & Scope) =
        AtomicRef.init(Maybe.empty[SceneDescriptor]).map { prior =>
            Fiber.init {
                region.signal.observe { node =>
                    prior.get.map { priorDesc =>
                        // A Custom/closure subtree rendered into the region aborts the flatten with a
                        // typed UnserializableNode (the closure-drop boundary); logged and skipped on the
                        // live observe path, leaving the prior child in place.
                        Abort.run[UnserializableNode](flattenNode(node)).map {
                            case Result.Success(desc) =>
                                if priorDesc.contains(desc) then Kyo.unit
                                else
                                    val removeOp: Chunk[StructuralOp] =
                                        if priorDesc.isDefined then Chunk(StructuralOp.Remove(reactiveKey)) else Chunk.empty
                                    val ops = removeOp.appended(StructuralOp.Insert(reactiveKey, 0, desc))
                                    prior.set(Present(desc)).andThen {
                                        Kyo.foreachDiscard(ops)(op => emit(HostPayload.Structural(op, regionId)))
                                    }
                            case Result.Failure(e) =>
                                Log.error(s"reactive region dropped an unserializable subtree: ${e.getMessage}")
                            case Result.Panic(e) =>
                                Log.error("reactive region flatten panicked", e)
                        }
                    }
                }
            }.unit
        }

    // A server-side structural region: a Foreach node wrapped to read its declarative children
    // (the key fn + the per-element render) without exposing the existential element type at the
    // diff site. Each region's signal drives one observe loop.
    final private class ForeachRegion[A](node: Three.Ast.Foreach[A]):
        val signal: Signal[Chunk[A]]             = node.signal
        def keyAt(index: Int, item: A): String   = node.key.fold(index.toString)(_(item))
        def renderAt(index: Int, item: A): Three = node.render(index, item)
        def keyedNodes(items: Chunk[A]): Chunk[(String, Three)] =
            items.zipWithIndex.map { case (item, i) => (keyAt(i, item), renderAt(i, item)) }
    end ForeachRegion

    // A server-side Reactive region: the node's Signal[Three], whose every emission is the one rendered
    // subtree to flatten and swap into the region holder.
    final private class ReactiveRegion(node: Three.Ast.Reactive):
        val signal: Signal[Three] = node.signal
    end ReactiveRegion

    // Walks the scene assigning each node its deterministic node id (the same depth-first index path
    // flatten/reconstitute use) and collecting every structural reactive region (Foreach or Reactive)
    // paired with that id. A Custom node carries a closure that stays server-side, so it is not a
    // structural region. A region's own (empty) AST children are not traversed: its rendered subtree
    // streams over the structural channel, not the boot tree.
    private def structuralRegions(nodeId: String, node: Three): Chunk[(String, ForeachRegion[Any] | ReactiveRegion)] =
        node match
            // Existential erasure: the type variable [a] is captured locally; the homogeneous element is
            // only traversed via the region's own typed methods.
            case f: Three.Ast.Foreach[a] => Chunk((nodeId, new ForeachRegion[a](f).asInstanceOf[ForeachRegion[Any]]))
            case r: Three.Ast.Reactive   => Chunk((nodeId, new ReactiveRegion(r)))
            case _ =>
                Chunk.from(childrenOf(node).toSeq.zipWithIndex).flatMap { case (child, i) =>
                    structuralRegions(childId(nodeId, i), child)
                }
    end structuralRegions

    // The pure server-side keyed diff for one structural region: given the region id, the prior keyed
    // snapshot, and the next item list, returns (structural ops, prop pushes, next snapshot).
    //
    //   - structural ops: each removed key once, each surviving key whose index changed as a Move, each
    //     new key as an Insert carrying its freshly flattened SceneDescriptor.
    //   - prop pushes: for each surviving key whose re-flattened descriptor's prop values changed, one
    //     HostPayload.Prop per changed slot, addressed by the child's stable `regionId#key` node id, so a
    //     cube's index-driven position slides on a reorder without re-materializing the live object.
    //
    // Every keyed child is re-flattened (resolving each Bound.Ref to its current server value), so a
    // surviving child's prop change is observed; the structural op set stays minimal (a Move only when
    // the index actually changed, never a Remove+Insert for a survivor). The next snapshot threads the
    // freshly flattened descriptors forward so the following emission diffs against the current props.
    private def diffKeyedServer[A](
        regionId: String,
        region: ForeachRegion[A],
        prior: Chunk[(String, SceneDescriptor)],
        items: Chunk[A]
    )(using Frame): (Chunk[StructuralOp], Chunk[HostPayload.Prop], Chunk[(String, SceneDescriptor)]) < (Sync & Abort[UnserializableNode]) =
        val keyedNodes = region.keyedNodes(items)
        val priorIndex = prior.zipWithIndex.map { case ((k, d), i) => (k, (i, d)) }.toMap
        val nextKeys   = keyedNodes.map(_._1)
        val nextKeySet = nextKeys.toSet
        // Removed: present in prior, absent from next. One Remove per removed key.
        val removes: Chunk[StructuralOp] =
            prior.collect { case (k, _) if !nextKeySet.contains(k) => StructuralOp.Remove(k) }
        // For each next entry, re-flatten its current subtree. A surviving key emits a Move (if its index
        // changed) plus a Prop per changed slot; a new key emits an Insert. Threads the next snapshot in
        // order so the prior for the following emission is the descriptors of the current children.
        Kyo.foreach(keyedNodes.zipWithIndex) { case ((key, node), nextIdx) =>
            flattenNode(node).map { desc =>
                Maybe.fromOption(priorIndex.get(key)) match
                    case Present((priorIdx, priorDesc)) =>
                        val moveOp =
                            if priorIdx == nextIdx then Chunk.empty[StructuralOp]
                            else Chunk(StructuralOp.Move(key, nextIdx))
                        val propPushes = descPropDeltas(s"$regionId#$key", priorDesc, desc)
                        (moveOp, propPushes, (key, desc))
                    case Absent =>
                        (Chunk(StructuralOp.Insert(key, nextIdx, desc)), Chunk.empty[HostPayload.Prop], (key, desc))
            }
        }.map { perKey =>
            val moveAndInsertOps = perKey.flatMap(_._1)
            val propPushes       = perKey.flatMap(_._2)
            val nextSnapshot     = perKey.map(_._3)
            (removes.concat(moveAndInsertOps), propPushes, nextSnapshot)
        }
    end diffKeyedServer

    // Walks two descriptors of the same node positionally, emitting one HostPayload.Prop per slot whose
    // value changed (present in next with a different value than prior, or newly present). The node id
    // scheme matches reconstituteAt/mirrorRefs: `baseId` for the node, `baseId.i` for child i, so each
    // Prop addresses the client mirror the spliced child allocated. A structurally divergent descriptor
    // (different kind or child count) emits no deltas: the structural op set handles that shape change.
    private def descPropDeltas(baseId: String, prior: SceneDescriptor, next: SceneDescriptor): Chunk[HostPayload.Prop] =
        if prior.kind != next.kind || prior.children.length != next.children.length then Chunk.empty
        else
            val priorProps = prior.props.toMap
            val here: Chunk[HostPayload.Prop] =
                Chunk.from(next.props).collect {
                    case (slot, value) if !priorProps.get(slot).contains(value) => HostPayload.Prop(baseId, slot, value)
                }
            val kids =
                Chunk.from(prior.children.zip(next.children).zipWithIndex).flatMap { case ((p, n), i) =>
                    descPropDeltas(childId(baseId, i), p, n)
                }
            here.concat(kids)
    end descPropDeltas

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
            case HostPayload.Structural(StructuralOp.Insert(_, _, descriptor), _) =>
                buildNode(rootId, descriptor).map { case (node, mirrors) =>
                    (node, mirrors.toMap)
                }
            case _ =>
                Sync.defer((Three.scene(), Map.empty[(String, String), SignalRef[Any]]))

    /** Reconstitutes one descriptor as a client node whose mirror node ids are rooted at `baseId`
      * instead of the host root, for a region child spliced by `applyStructuralOp`. A `foreach` child is
      * built under `baseId = s"$regionId#$key"` (the stable per-key id the server emits its prop pushes
      * against), so the child's bound props (a cube's index-driven position) get mirrors the channel can
      * route a later `HostPayload.Prop` to. Returns the node plus its `(nodeId, slot) -> mirror` map.
      */
    def reconstituteAt(baseId: String, descriptor: SceneDescriptor)(using
        Frame
    ): (Three, Map[(String, String), SignalRef[Any]]) < Sync =
        buildNode(baseId, descriptor).map { case (node, mirrors) => (node, mirrors.toMap) }

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
