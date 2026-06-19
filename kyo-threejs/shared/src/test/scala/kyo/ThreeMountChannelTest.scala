package kyo

import kyo.Three.foreachKeyed
import kyo.internal.CameraDescriptor
import kyo.internal.GeometryDescriptor
import kyo.internal.HostInit
import kyo.internal.HostPayload
import kyo.internal.HostValue
import kyo.internal.MaterialKind
import kyo.internal.Reconciler
import kyo.internal.SceneDescriptor
import kyo.internal.StructuralOp
import scala.scalajs.js as sjs

/** Channel-routing tests for the server-push host channel, on Node (no WebGL, no browser).
  *
  * Drives the full server -> client -> live-object grain through the typed transport: a server-owned
  * signal change flattens and reconstitutes into a per-slot client mirror, a `HostPayload.Prop` write
  * routes through `HostChannel` to that mirror, and the reconciler's patch path applies exactly one
  * targeted mutation to the live three.js object. Every assertion observes a concrete value (a live
  * material color hex, a mirror's current value, a counter); no spy, no mock, no sleep. The live-GL
  * end-to-end is covered in `ThreeServerPushTest` (browser, js/src/test).
  */
class ThreeMountChannelTest extends ThreeTest:

    // A server scene: one mesh whose material color is bound to a server-owned signal. The node id of
    // the mesh under the reconstituted client scene is "r.0" (root scene "r", child 0).
    private def serverScene(colorSignal: Signal[Color])(using Frame): Three =
        Three.scene(
            Three.mesh(Three.Geometry.box(), Three.Material.standard().color(colorSignal))
        )

    private val meshNodeId = "r.0"

    private def liveColorHex(mounted: Reconciler.Mounted)(using Frame): Int < Sync =
        Sync.Unsafe.defer {
            mounted.live.values.flatMap { live =>
                live.node match
                    case _: Three.Ast.Mesh => Some(live.obj.material.color.getHex().asInstanceOf[Int])
                    case _                 => None
            }.headOption.getOrElse(-1)
        }

    "a server signal change reaches the client mirror as a HostUpdate and patches the live color" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    serverColor <- Signal.initRef(Color.white)
                    _           <- serverColor.set(Color.blue)
                    // Server side: flatten the scene to the inline boot payload (the SSR init island body).
                    boot <- ThreeBridge.flattenInit(serverScene(serverColor), Three.Camera.perspective())
                    // Client side: reconstitute into a scene with per-slot mirrors + build the channel.
                    rec <- ThreeBridge.reconstitute(boot)
                    (clientScene, _) = rec
                    init             = HostInit(clientScene, Three.Camera.perspective(), ThreeFrames.Raf)
                    channel <- HostChannel.init(init)
                    // Materialize the client scene; the boot color was blue, so the mirror seeds blue.
                    mountResult <- Reconciler.mount(clientScene)
                    (_, mounted) = mountResult
                    _         <- ThreeMount.fillBoundRefsOnce(mounted)
                    seededHex <- liveColorHex(mounted)
                    // A server emission arrives as a HostUpdate Prop addressed to the mesh's color slot.
                    _ <- ThreeMount.applyHostUpdate(
                        channel,
                        HostPayload.Prop(meshNodeId, ThreeBridge.slotColor, HostValue.Col(Color.green.packed))
                    )
                    _          <- ThreeMount.fillBoundRefsOnce(mounted)
                    patchedHex <- liveColorHex(mounted)
                yield
                    assert(
                        seededHex == Color.blue.packed,
                        s"boot color must seed the mirror (blue), got 0x${seededHex.toHexString}"
                    )
                    assert(
                        patchedHex == Color.green.packed,
                        s"the HostUpdate Prop must drive the live color to green, got 0x${patchedHex.toHexString}"
                    )
                end for
            }
        }
    }

    "successive prop pushes do not re-materialize the scene (live-map count is stable)" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    serverColor <- Signal.initRef(Color.white)
                    boot        <- ThreeBridge.flattenInit(serverScene(serverColor), Three.Camera.perspective())
                    rec         <- ThreeBridge.reconstitute(boot)
                    (clientScene, _) = rec
                    channel     <- HostChannel.init(init = HostInit(clientScene, Three.Camera.perspective(), ThreeFrames.Raf))
                    mountResult <- Reconciler.mount(clientScene)
                    (_, mounted) = mountResult
                    countBefore <- Sync.Unsafe.defer(mounted.live.size)
                    _ <- ThreeMount.applyHostUpdate(
                        channel,
                        HostPayload.Prop(meshNodeId, ThreeBridge.slotColor, HostValue.Col(Color.red.packed))
                    )
                    _ <- ThreeMount.applyHostUpdate(
                        channel,
                        HostPayload.Prop(meshNodeId, ThreeBridge.slotColor, HostValue.Col(Color.green.packed))
                    )
                    _ <- ThreeMount.applyHostUpdate(
                        channel,
                        HostPayload.Prop(meshNodeId, ThreeBridge.slotColor, HostValue.Col(Color.blue.packed))
                    )
                    countAfter <- Sync.Unsafe.defer(mounted.live.size)
                yield
                    assert(countBefore > 0, "the client scene must materialize at least one live node")
                    assert(
                        countAfter == countBefore,
                        s"prop pushes must not re-materialize: live count $countBefore -> $countAfter"
                    )
                end for
            }
        }
    }

    "a HostUpdate for an unknown (nodeId, slot) is a silent no-op (no throw, mirror unchanged)" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    serverColor <- Signal.initRef(Color.green)
                    boot        <- ThreeBridge.flattenInit(serverScene(serverColor), Three.Camera.perspective())
                    rec         <- ThreeBridge.reconstitute(boot)
                    (clientScene, _) = rec
                    channel     <- HostChannel.init(HostInit(clientScene, Three.Camera.perspective(), ThreeFrames.Raf))
                    mountResult <- Reconciler.mount(clientScene)
                    (_, mounted) = mountResult
                    // An update to a node the channel has no mirror for must not throw and must not move
                    // the known mesh's color.
                    _ <- ThreeMount.applyHostUpdate(
                        channel,
                        HostPayload.Prop("r.99", ThreeBridge.slotColor, HostValue.Col(Color.red.packed))
                    )
                    _   <- ThreeMount.applyHostUpdate(channel, HostPayload.Prop(meshNodeId, "nosuchslot", HostValue.Col(Color.red.packed)))
                    _   <- ThreeMount.fillBoundRefsOnce(mounted)
                    hex <- liveColorHex(mounted)
                yield assert(
                    hex == Color.green.packed,
                    s"an unknown-target HostUpdate must be a no-op; color must stay green, got 0x${hex.toHexString}"
                )
            }
        }
    }

    "the server observe bridge emits one HostPayload.Prop per server signal emission" in {
        Channel.initWith[HostPayload](16) { emitted =>
            Scope.run {
                for
                    serverColor <- Signal.initRef(Color.white)
                    // The server bridge observes serverColor and emits a HostPayload.Prop on each change.
                    _ <- ThreeBridge.observeProps(
                        serverScene(serverColor),
                        payload => Abort.runPartial[Closed](emitted.put(payload)).unit
                    )
                    // The observe registers and fires once for the current value, then once per set.
                    first  <- emitted.take
                    _      <- serverColor.set(Color.red)
                    second <- emitted.take
                yield
                    assert(
                        first == HostPayload.Prop(meshNodeId, ThreeBridge.slotColor, HostValue.Col(Color.white.packed)),
                        s"the first emission must carry the current (white) color, got $first"
                    )
                    assert(
                        second == HostPayload.Prop(meshNodeId, ThreeBridge.slotColor, HostValue.Col(Color.red.packed)),
                        s"a server set to red must emit a Prop carrying red, got $second"
                    )
                end for
            }
        }
    }

    "the server pick bridge runs the hit mesh's onClick closure server-side" in {
        Channel.initWith[Int](1) { clicked =>
            for
                clickCount <- AtomicInt.init(0)
                scene = Three.scene(
                    Three.mesh(Three.Geometry.box(), Three.Material.standard())
                        .onClick(_ => clickCount.incrementAndGet.map(_ => Abort.runPartial[Closed](clicked.put(1)).unit))
                )
                pointer = kyo.internal.PointerData(0.0, 0.0, 0.0, 1.0, 0.0, 0.0)
                _     <- ThreeBridge.runPick(scene, meshNodeId, pointer)
                _     <- clicked.take
                count <- clickCount.get
            yield assert(count == 1, s"the pick must run the mesh onClick exactly once, got $count")
        }
    }

    "writeStructural records the op on the channel's structural inbox" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    serverColor <- Signal.initRef(Color.white)
                    boot        <- ThreeBridge.flattenInit(serverScene(serverColor), Three.Camera.perspective())
                    rec         <- ThreeBridge.reconstitute(boot)
                    (clientScene, _) = rec
                    channel <- HostChannel.init(HostInit(clientScene, Three.Camera.perspective(), ThreeFrames.Raf))
                    op = StructuralOp.Remove("k1")
                    // A Structural HostUpdate with the default region id (the host root) appends a
                    // (regionId, op) pair to the inbox; the default region id is "r".
                    _     <- ThreeMount.applyHostUpdate(channel, HostPayload.Structural(op))
                    inbox <- channel.structuralInbox.current
                yield assert(
                    inbox == Chunk(("r", op)),
                    s"a Structural HostUpdate must append (regionId, op) to the inbox, got $inbox"
                )
            }
        }
    }

    // ---- Structural reactivity over the channel ------------------------------------------
    //
    // A server reactive region (foreach/foreachKeyed) splices/removes/reorders a 3D subtree on the
    // client over the same host channel, without a re-mount. These leaves drive the client splice path
    // (ThreeMount.applyStructuralOp against a materialized live root) and the server-side keyed diff
    // (ThreeBridge.observeStructure), on Node (no live GL submit). Each assertion observes a concrete
    // value: a GL dispose event count, a live Object3D reference identity, a live-child count, or the
    // exact op set the server diff emits.

    // A minimal mesh descriptor (the serializable subset materializes a Standard mesh with a Box
    // geometry). Carrying a color prop so the materialized mesh has a real material to dispose.
    private def meshDescriptor(colorRgb: Int): SceneDescriptor =
        SceneDescriptor("mesh", Seq((ThreeBridge.slotColor, HostValue.Col(colorRgb))), Seq.empty)

    // Counts the dispose events on a live mesh's geometry and material (the GL resources the per-element
    // scope owns). Returns (geomDispose, matDispose) closures incremented by the three.js dispose event.
    private def disposeCounters(live: Reconciler.Live): (() => Int, () => Int) =
        var geomCount = 0
        var matCount  = 0
        live.obj.geometry.addEventListener("dispose", (_: sjs.Any) => geomCount += 1)
        live.obj.material.addEventListener("dispose", (_: sjs.Any) => matCount += 1)
        (() => geomCount, () => matCount)
    end disposeCounters

    "a removed key disposes its GL scope exactly once" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    rootResult <- Reconciler.mount(Three.scene())
                    (rootLive, mounted) = rootResult
                    // Splice three keyed children k0,k1,k2 via the channel splice path.
                    s0 <-
                        ThreeMount.applyStructuralOp(StructuralOp.Insert("k0", 0, meshDescriptor(0xff0000)), Chunk.empty, rootLive, mounted)
                    s1 <- ThreeMount.applyStructuralOp(StructuralOp.Insert("k1", 1, meshDescriptor(0x00ff00)), s0, rootLive, mounted)
                    s2 <- ThreeMount.applyStructuralOp(StructuralOp.Insert("k2", 2, meshDescriptor(0x0000ff)), s1, rootLive, mounted)
                    // Attach dispose counters to k1's GL resources before removal.
                    k1Live = s2.find(_._1 == "k1").get._2
                    counters <- Sync.Unsafe.defer(disposeCounters(k1Live))
                    (k1Geom, k1Mat) = counters
                    childCountBefore <- Sync.Unsafe.defer(rootLive.obj.children.asInstanceOf[sjs.Array[sjs.Dynamic]].length)
                    // Remove k1 via the channel splice path; its per-element scope closes once.
                    s3              <- ThreeMount.applyStructuralOp(StructuralOp.Remove("k1"), s2, rootLive, mounted)
                    childCountAfter <- Sync.Unsafe.defer(rootLive.obj.children.asInstanceOf[sjs.Array[sjs.Dynamic]].length)
                yield
                    assert(k1Geom() == 1, s"k1 geometry must dispose exactly once, got ${k1Geom()}")
                    assert(k1Mat() == 1, s"k1 material must dispose exactly once, got ${k1Mat()}")
                    assert(s3.map(_._1) == Chunk("k0", "k2"), s"k1 must be removed from the keyed list, got ${s3.map(_._1)}")
                    assert(
                        childCountAfter == childCountBefore - 1,
                        s"the root must hold one fewer child after Remove: $childCountBefore -> $childCountAfter"
                    )
                end for
            }
        }
    }

    "a surviving node is not disposed on reorder" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    rootResult <- Reconciler.mount(Three.scene())
                    (rootLive, mounted) = rootResult
                    s0 <-
                        ThreeMount.applyStructuralOp(StructuralOp.Insert("k0", 0, meshDescriptor(0xff0000)), Chunk.empty, rootLive, mounted)
                    s1 <- ThreeMount.applyStructuralOp(StructuralOp.Insert("k1", 1, meshDescriptor(0x00ff00)), s0, rootLive, mounted)
                    s2 <- ThreeMount.applyStructuralOp(StructuralOp.Insert("k2", 2, meshDescriptor(0x0000ff)), s1, rootLive, mounted)
                    // Record the live object references and attach dispose counters to every child.
                    refK0 = s2.find(_._1 == "k0").get._2.obj
                    refK1 = s2.find(_._1 == "k1").get._2.obj
                    refK2 = s2.find(_._1 == "k2").get._2.obj
                    countersK0 <- Sync.Unsafe.defer(disposeCounters(s2.find(_._1 == "k0").get._2))
                    countersK1 <- Sync.Unsafe.defer(disposeCounters(s2.find(_._1 == "k1").get._2))
                    countersK2 <- Sync.Unsafe.defer(disposeCounters(s2.find(_._1 == "k2").get._2))
                    // Reorder: move k0 to index 2 ([k1, k2, k0]). No dispose, refs preserved.
                    s3 <- ThreeMount.applyStructuralOp(StructuralOp.Move("k0", 2), s2, rootLive, mounted)
                yield
                    assert(s3.map(_._1) == Chunk("k1", "k2", "k0"), s"Move must reorder the keyed list, got ${s3.map(_._1)}")
                    // Each key's live Object3D reference is identical before and after (no dispose/recreate).
                    assert(s3.find(_._1 == "k0").get._2.obj eq refK0, "k0's live object must be the same reference after Move")
                    assert(s3.find(_._1 == "k1").get._2.obj eq refK1, "k1's live object must be the same reference after Move")
                    assert(s3.find(_._1 == "k2").get._2.obj eq refK2, "k2's live object must be the same reference after Move")
                    // No dispose fired on any surviving node (GPU buffers reused).
                    assert(countersK0._1() == 0 && countersK0._2() == 0, "k0 must not be disposed on reorder")
                    assert(countersK1._1() == 0 && countersK1._2() == 0, "k1 must not be disposed on reorder")
                    assert(countersK2._1() == 0 && countersK2._2() == 0, "k2 must not be disposed on reorder")
                end for
            }
        }
    }

    "an inserted key materializes under its own scope" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    rootResult <- Reconciler.mount(Three.scene())
                    (rootLive, mounted) = rootResult
                    s0 <-
                        ThreeMount.applyStructuralOp(StructuralOp.Insert("k0", 0, meshDescriptor(0xff0000)), Chunk.empty, rootLive, mounted)
                    s1 <- ThreeMount.applyStructuralOp(StructuralOp.Insert("k1", 1, meshDescriptor(0x0000ff)), s0, rootLive, mounted)
                    refK0 = s1.find(_._1 == "k0").get._2.obj
                    refK1 = s1.find(_._1 == "k1").get._2.obj
                    countBefore <- Sync.Unsafe.defer(rootLive.obj.children.asInstanceOf[sjs.Array[sjs.Dynamic]].length)
                    // Insert k9 at index 1.
                    s2 <- ThreeMount.applyStructuralOp(StructuralOp.Insert("k9", 1, meshDescriptor(0x00ff00)), s1, rootLive, mounted)
                    countAfter <- Sync.Unsafe.defer(rootLive.obj.children.asInstanceOf[sjs.Array[sjs.Dynamic]].length)
                yield
                    assert(s2.map(_._1) == Chunk("k0", "k9", "k1"), s"k9 must splice at index 1, got ${s2.map(_._1)}")
                    // The new node materialized; its live object is distinct from every existing one.
                    val refK9 = s2.find(_._1 == "k9").get._2.obj
                    assert(!(refK9 eq refK0) && !(refK9 eq refK1), "the inserted node must be a fresh live object")
                    // The existing nodes' references are unchanged.
                    assert(s2.find(_._1 == "k0").get._2.obj eq refK0, "k0's reference must be unchanged by an insert")
                    assert(s2.find(_._1 == "k1").get._2.obj eq refK1, "k1's reference must be unchanged by an insert")
                    assert(countAfter == countBefore + 1, s"the live-child count must increase by exactly one: $countBefore -> $countAfter")
                end for
            }
        }
    }

    "a removed key is not disposed twice" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    rootResult <- Reconciler.mount(Three.scene())
                    (rootLive, mounted) = rootResult
                    s0 <-
                        ThreeMount.applyStructuralOp(StructuralOp.Insert("k1", 0, meshDescriptor(0x00ff00)), Chunk.empty, rootLive, mounted)
                    k1Live = s0.find(_._1 == "k1").get._2
                    counters <- Sync.Unsafe.defer(disposeCounters(k1Live))
                    (k1Geom, k1Mat) = counters
                    // First Remove disposes once and clears the entry.
                    s1 <- ThreeMount.applyStructuralOp(StructuralOp.Remove("k1"), s0, rootLive, mounted)
                    // A stale second Remove for the same key finds no entry: a no-op, no second dispose.
                    s2 <- ThreeMount.applyStructuralOp(StructuralOp.Remove("k1"), s1, rootLive, mounted)
                yield
                    assert(s1.isEmpty, s"the first Remove must clear k1, got ${s1.map(_._1)}")
                    assert(s2.isEmpty, s"the stale Remove must be a no-op, got ${s2.map(_._1)}")
                    assert(k1Geom() == 1, s"k1 geometry must dispose at most once (no double-dispose), got ${k1Geom()}")
                    assert(k1Mat() == 1, s"k1 material must dispose at most once (no double-dispose), got ${k1Mat()}")
                end for
            }
        }
    }

    "a Move or Remove for a key absent from the keyed list is a no-op (no throw, no dispose)" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    rootResult <- Reconciler.mount(Three.scene())
                    (rootLive, mounted) = rootResult
                    s0 <-
                        ThreeMount.applyStructuralOp(StructuralOp.Insert("k0", 0, meshDescriptor(0xff0000)), Chunk.empty, rootLive, mounted)
                    s1 <- ThreeMount.applyStructuralOp(StructuralOp.Insert("k1", 1, meshDescriptor(0x00ff00)), s0, rootLive, mounted)
                    // Dispose counters on both present children, so a spurious dispose on the absent-key op is caught.
                    countersK0 <- Sync.Unsafe.defer(disposeCounters(s1.find(_._1 == "k0").get._2))
                    countersK1 <- Sync.Unsafe.defer(disposeCounters(s1.find(_._1 == "k1").get._2))
                    refK0 = s1.find(_._1 == "k0").get._2.obj
                    refK1 = s1.find(_._1 == "k1").get._2.obj
                    childCountBefore <- Sync.Unsafe.defer(rootLive.obj.children.asInstanceOf[sjs.Array[sjs.Dynamic]].length)
                    // A Move for a key that was never inserted: the live list is unchanged, nothing disposes.
                    sMove <- ThreeMount.applyStructuralOp(StructuralOp.Move("missing", 0), s1, rootLive, mounted)
                    // A Remove for a key that was never inserted: same no-op posture (no entry to dispose).
                    sRemove         <- ThreeMount.applyStructuralOp(StructuralOp.Remove("missing"), sMove, rootLive, mounted)
                    childCountAfter <- Sync.Unsafe.defer(rootLive.obj.children.asInstanceOf[sjs.Array[sjs.Dynamic]].length)
                yield
                    assert(sMove.map(_._1) == Chunk("k0", "k1"), s"a Move for an absent key must not reorder, got ${sMove.map(_._1)}")
                    assert(
                        sRemove.map(_._1) == Chunk("k0", "k1"),
                        s"a Remove for an absent key must not drop a key, got ${sRemove.map(_._1)}"
                    )
                    assert(sRemove.find(_._1 == "k0").get._2.obj eq refK0, "k0's live object must be untouched by absent-key ops")
                    assert(sRemove.find(_._1 == "k1").get._2.obj eq refK1, "k1's live object must be untouched by absent-key ops")
                    assert(
                        countersK0._1() == 0 && countersK0._2() == 0,
                        s"no dispose may fire on k0: geom=${countersK0._1()} mat=${countersK0._2()}"
                    )
                    assert(
                        countersK1._1() == 0 && countersK1._2() == 0,
                        s"no dispose may fire on k1: geom=${countersK1._1()} mat=${countersK1._2()}"
                    )
                    assert(
                        childCountAfter == childCountBefore,
                        s"the root child count must be unchanged by absent-key ops: $childCountBefore -> $childCountAfter"
                    )
                end for
            }
        }
    }

    "re-applying a Prop with the live mirror's current value is idempotent (no spurious change)" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    // The boot seeds the mirror (and the live color) to blue.
                    serverColor <- Signal.initRef(Color.blue)
                    boot        <- ThreeBridge.flattenInit(serverScene(serverColor), Three.Camera.perspective())
                    rec         <- ThreeBridge.reconstitute(boot)
                    (clientScene, _) = rec
                    channel     <- HostChannel.init(HostInit(clientScene, Three.Camera.perspective(), ThreeFrames.Raf))
                    mountResult <- Reconciler.mount(clientScene)
                    (_, mounted) = mountResult
                    _           <- ThreeMount.fillBoundRefsOnce(mounted)
                    seededHex   <- liveColorHex(mounted)
                    countBefore <- Sync.Unsafe.defer(mounted.live.size)
                    // Push the SAME value the mirror already holds, twice: a same-value set is a no-op on the
                    // live object, distinct from the distinct-value pushes the live-map-stability leaf covers.
                    _ <- ThreeMount.applyHostUpdate(
                        channel,
                        HostPayload.Prop(meshNodeId, ThreeBridge.slotColor, HostValue.Col(Color.blue.packed))
                    )
                    _ <- ThreeMount.fillBoundRefsOnce(mounted)
                    _ <- ThreeMount.applyHostUpdate(
                        channel,
                        HostPayload.Prop(meshNodeId, ThreeBridge.slotColor, HostValue.Col(Color.blue.packed))
                    )
                    _          <- ThreeMount.fillBoundRefsOnce(mounted)
                    afterHex   <- liveColorHex(mounted)
                    countAfter <- Sync.Unsafe.defer(mounted.live.size)
                yield
                    assert(seededHex == Color.blue.packed, s"boot must seed the live color to blue, got 0x${seededHex.toHexString}")
                    assert(
                        afterHex == Color.blue.packed,
                        s"re-applying the current value must leave the live color at blue, got 0x${afterHex.toHexString}"
                    )
                    assert(countAfter == countBefore, s"a same-value Prop must not re-materialize: live count $countBefore -> $countAfter")
                end for
            }
        }
    }

    "a Remove interleaved with a Prop push for the same subtree disposes once and leaves a consistent map" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    // A channel over the server scene (mesh node id r.0), plus a materialized splice root.
                    serverColor <- Signal.initRef(Color.green)
                    boot        <- ThreeBridge.flattenInit(serverScene(serverColor), Three.Camera.perspective())
                    rec         <- ThreeBridge.reconstitute(boot)
                    (clientScene, _) = rec
                    channel    <- HostChannel.init(HostInit(clientScene, Three.Camera.perspective(), ThreeFrames.Raf))
                    rootResult <- Reconciler.mount(Three.scene())
                    (rootLive, mounted) = rootResult
                    // Splice one keyed element, then attach dispose counters to its GL resources.
                    s0 <-
                        ThreeMount.applyStructuralOp(StructuralOp.Insert("k1", 0, meshDescriptor(0x0000ff)), Chunk.empty, rootLive, mounted)
                    counters <- Sync.Unsafe.defer(disposeCounters(s0.find(_._1 == "k1").get._2))
                    (k1Geom, k1Mat) = counters
                    // Interleave a channel Prop push (writes the channel mirror, a separate grain) with the
                    // structural Remove of k1 and a stale second Remove: the two grains must not interfere.
                    _ <- ThreeMount.applyHostUpdate(
                        channel,
                        HostPayload.Prop(meshNodeId, ThreeBridge.slotColor, HostValue.Col(Color.red.packed))
                    )
                    s1 <- ThreeMount.applyStructuralOp(StructuralOp.Remove("k1"), s0, rootLive, mounted)
                    _ <- ThreeMount.applyHostUpdate(
                        channel,
                        HostPayload.Prop(meshNodeId, ThreeBridge.slotColor, HostValue.Col(Color.green.packed))
                    )
                    s2              <- ThreeMount.applyStructuralOp(StructuralOp.Remove("k1"), s1, rootLive, mounted)
                    childCountAfter <- Sync.Unsafe.defer(rootLive.obj.children.asInstanceOf[sjs.Array[sjs.Dynamic]].length)
                yield
                    assert(s1.isEmpty, s"the Remove must clear k1, got ${s1.map(_._1)}")
                    assert(s2.isEmpty, s"the stale Remove must stay a no-op, got ${s2.map(_._1)}")
                    assert(k1Geom() == 1, s"k1 geometry must dispose exactly once despite the interleaved Prop, got ${k1Geom()}")
                    assert(k1Mat() == 1, s"k1 material must dispose exactly once despite the interleaved Prop, got ${k1Mat()}")
                    assert(childCountAfter == 0, s"the splice root must hold no children after the Remove, got $childCountAfter")
                end for
            }
        }
    }

    "the server-side keyed diff emits the minimal op set" in {
        Channel.initWith[HostPayload](32) { emitted =>
            Scope.run {
                for
                    // A server-owned signal of keyed items; foreachKeyed renders a mesh per item.
                    items <- Signal.initRef(Chunk("a", "b", "c"))
                    scene = Three.scene(
                        items.foreachKeyed(s => s)(_ => Three.mesh(Three.Geometry.box(), Three.Material.standard()))
                    )
                    // observeStructure forks the per-region diff fiber; the first emission (current
                    // [a,b,c], prior empty) emits three Inserts. Drain those three.
                    _    <- ThreeBridge.observeStructure(scene, payload => Abort.runPartial[Closed](emitted.put(payload)).unit)
                    ins0 <- emitted.take
                    ins1 <- emitted.take
                    ins2 <- emitted.take
                    // Now change the list to [a,c,d]: b removed, c moved, d appended.
                    _   <- items.set(Chunk("a", "c", "d"))
                    op1 <- emitted.take
                    op2 <- emitted.take
                    op3 <- emitted.take
                yield
                    // The foreach is the sole scene child, so every op targets region id "r.0".
                    def regionOf(p: HostPayload): String = p match
                        case HostPayload.Structural(_, regionId) => regionId
                        case other                               => s"not-structural:$other"
                    assert(
                        List(ins0, ins1, ins2, op1, op2, op3).forall(regionOf(_) == "r.0"),
                        s"every op must target the foreach region 'r.0'; got ${List(ins0, ins1, ins2, op1, op2, op3).map(regionOf)}"
                    )
                    // The boot batch is three Inserts (a@0, b@1, c@2).
                    def insertKey(p: HostPayload): String = p match
                        case HostPayload.Structural(StructuralOp.Insert(k, _, _), _) => k
                        case other                                                   => s"not-insert:$other"
                    assert(
                        Set(insertKey(ins0), insertKey(ins1), insertKey(ins2)) == Set("a", "b", "c"),
                        s"the boot batch must insert a,b,c; got ${List(ins0, ins1, ins2)}"
                    )
                    // The diff batch is exactly Remove(b), Move(c, 1), Insert(d, 2, descriptor): the
                    // minimal op set, NOT a full re-materialize of [a,c,d].
                    val diffOps =
                        List(op1, op2, op3).map { case HostPayload.Structural(op, _) => op; case p => fail(s"non-structural: $p") }
                    assert(
                        diffOps.contains(StructuralOp.Remove("b")),
                        s"the diff must Remove b, got $diffOps"
                    )
                    assert(
                        diffOps.contains(StructuralOp.Move("c", 1)),
                        s"the diff must Move c to index 1, got $diffOps"
                    )
                    val insertD = diffOps.collect { case StructuralOp.Insert("d", idx, desc) => (idx, desc) }
                    assert(insertD.nonEmpty, s"the diff must Insert d, got $diffOps")
                    assert(insertD.head._1 == 2, s"d must insert at index 2, got ${insertD.head._1}")
                    assert(insertD.head._2.kind == "mesh", s"d's descriptor must be a mesh, got ${insertD.head._2.kind}")
                    // Exactly three ops: no full re-materialize (which would be 3 Inserts + 3 Removes).
                    assert(diffOps.size == 3, s"the diff must emit exactly 3 ops (minimal set), got ${diffOps.size}: $diffOps")
                end for
            }
        }
    }

    "a Custom/closure subtree is not flattened to a descriptor" in {
        Scope.run {
            for
                // A foreachKeyed render that produces a Three.custom node: a closure-bearing subtree the
                // serializable subset drops. Flattening it must FAIL with a typed failure, never a silent
                // empty descriptor. The failure type is internal (not a public ThreeException leaf).
                customSubtree = Three.scene(
                    Three.custom[Int](_ => sjs.Dynamic.literal())(0)
                )
                flattenResult <- Abort.run[ThreeBridge.UnserializableNode](ThreeBridge.flattenNode(customSubtree))
            yield flattenResult match
                case Result.Failure(e: ThreeBridge.UnserializableNode) =>
                    assert(e.kind == "custom", s"the failure must name the custom kind, got ${e.kind}")
                case other =>
                    fail(s"flattening a Custom subtree must yield a typed UnserializableNode failure, got $other")
            end for
        }
    }

    "flattenInit of a scene mixing static lights with a foreach preserves the lights and represents the region" in {
        // The boot regression: a foreach anywhere in the scene must NOT collapse the whole boot to an
        // empty scene. The lights survive at their positions, the foreach flattens to an empty "group"
        // holder placeholder occupying its node-id slot (its children stream over the structural channel),
        // and the deterministic node-id path is preserved so the structural ops address "r.2".
        Scope.run {
            for
                ids <- Signal.initRef(Chunk(0, 1, 2))
                cubes = ids.foreachKeyed(_.toString)(_ => Three.mesh(Three.Geometry.box(), Three.Material.standard()))
                scene = Three.scene(
                    Three.Light.ambient(intensity = 0.5),
                    Three.Light.directional(position = Vec3(4, 8, 6)),
                    cubes
                )
                boot <- ThreeBridge.flattenInit(scene, Three.Camera.perspective())
            yield boot match
                case HostPayload.Boot(StructuralOp.Insert(rootKey, idx, sceneDesc), _) =>
                    assert(rootKey == ThreeBridge.rootId, s"the boot inserts the root, got key '$rootKey'")
                    assert(idx == 0, s"the boot inserts at index 0, got $idx")
                    assert(sceneDesc.kind == "scene", s"the boot root must be a scene, got '${sceneDesc.kind}'")
                    // The scene keeps all three children: two lights AND the foreach holder. The bug
                    // dropped to a childless empty scene; the fix preserves siblings.
                    assert(
                        sceneDesc.children.map(_.kind) == Seq("light.ambient", "light.directional", "group"),
                        s"the boot must keep both lights and represent the foreach as a group holder, got ${sceneDesc.children.map(_.kind)}"
                    )
                    // The ambient light's intensity survives as a Num prop (the static sibling is intact).
                    val ambient = sceneDesc.children.head
                    assert(
                        ambient.props.contains(ThreeBridge.slotIntensity -> HostValue.Num(0.5)),
                        s"the ambient light must keep its intensity prop, got ${ambient.props}"
                    )
                    // The directional light keeps its position transform.
                    val directional = sceneDesc.children(1)
                    assert(
                        directional.props.contains(ThreeBridge.slotPosition -> HostValue.V3(4.0, 8.0, 6.0)),
                        s"the directional light must keep its position prop, got ${directional.props}"
                    )
                    // The foreach holder is an empty group: no baked children (they arrive over the channel).
                    val holder = sceneDesc.children(2)
                    assert(holder.props.isEmpty, s"the region holder carries no props, got ${holder.props}")
                    assert(holder.children.isEmpty, s"the region holder boots empty, got ${holder.children}")
                case other => fail(s"the boot must be a Boot(Insert(scene...)); got $other")
            end for
        }
    }

    "flattenNode of a reactive region yields an empty group holder, not a failure" in {
        // A Reactive (`render`/`when`/`reactive`) is serializable as an empty holder placeholder, exactly
        // like a foreach, so a scene carrying one boots its siblings rather than collapsing.
        Scope.run {
            for
                sig <- Signal.initRef(Three.mesh(Three.Geometry.sphere(), Three.Material.standard()): Three)
                reactive = Three.reactive(sig)
                desc <- Abort.run[ThreeBridge.UnserializableNode](ThreeBridge.flattenNode(reactive))
            yield desc match
                case Result.Success(d) =>
                    assert(d.kind == "group", s"a reactive region flattens to a group holder, got '${d.kind}'")
                    assert(d.children.isEmpty, s"the reactive holder boots empty, got ${d.children}")
                    assert(d.props.isEmpty, s"the reactive holder carries no props, got ${d.props}")
                case other => fail(s"a reactive region must flatten to a holder, not fail; got $other")
            end for
        }
    }

    "the structural inbox routes each op into its own region holder, not the host root" in {
        // A scene mixing two lights with a foreach: the boot materializes the lights plus an empty holder
        // at node id r.2. A structural Insert tagged regionId r.2 splices a cube INTO that holder, leaving
        // the root's static children (the two lights) untouched. This is the multi-sibling routing the bug
        // broke (splicing into the root collided with the lights).
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    ids <- Signal.initRef(Chunk(0))
                    cubes = ids.foreachKeyed(_.toString)(_ => Three.mesh(Three.Geometry.box(), Three.Material.standard()))
                    scene = Three.scene(
                        Three.Light.ambient(intensity = 0.5),
                        Three.Light.directional(position = Vec3(4, 8, 6)),
                        cubes
                    )
                    boot <- ThreeBridge.flattenInit(scene, Three.Camera.perspective())
                    rec  <- ThreeBridge.reconstitute(boot)
                    (clientScene, _) = rec
                    mountResult <- Reconciler.mount(clientScene)
                    (rootLive, mounted) = mountResult
                    // The root holds three children: ambient, directional, the holder at index 2.
                    rootChildCount <- Sync.Unsafe.defer(rootLive.obj.children.asInstanceOf[sjs.Array[sjs.Dynamic]].length)
                    holderLive = ThreeMount.liveByNodeId(rootLive, "r.2").get
                    holderChildrenBefore <- Sync.Unsafe.defer(holderLive.obj.children.asInstanceOf[sjs.Array[sjs.Dynamic]].length)
                    // Splice a cube into region r.2 via applyStructuralOp against the HOLDER live node.
                    spliced <- ThreeMount.applyStructuralOp(
                        StructuralOp.Insert("0", 0, meshDescriptor(0x00ff00)),
                        Chunk.empty,
                        holderLive,
                        mounted
                    )
                    holderChildrenAfter <- Sync.Unsafe.defer(holderLive.obj.children.asInstanceOf[sjs.Array[sjs.Dynamic]].length)
                    rootChildAfter      <- Sync.Unsafe.defer(rootLive.obj.children.asInstanceOf[sjs.Array[sjs.Dynamic]].length)
                yield
                    assert(rootChildCount == 3, s"the boot must materialize two lights plus the holder, got $rootChildCount root children")
                    assert(holderChildrenBefore == 0, s"the region holder must boot empty, got $holderChildrenBefore")
                    assert(spliced.map(_._1) == Chunk("0"), s"the cube must splice into the region, got ${spliced.map(_._1)}")
                    assert(holderChildrenAfter == 1, s"the cube must attach to the holder, got $holderChildrenAfter holder children")
                    assert(
                        rootChildAfter == 3,
                        s"splicing into the holder must NOT change the root's static child count, got $rootChildAfter"
                    )
                end for
            }
        }
    }

    // ---- Geometry / material / camera fidelity over the boot wire (D-7, D-8) -------------
    //
    // The server-push boot must reconstitute the server's ACTUAL geometry shape, material class, and
    // camera, not a hardcoded unit box + standard material + default perspective. These leaves flatten a
    // real scene/camera to the wire and reconstitute it, asserting the rebuilt AST matches the source.

    "a sphere mesh flattens and reconstitutes as a sphere with its radius, not a box" in {
        // D-7a: the geometry type + params cross the wire. A sphere of radius 2.5 must reconstitute as a
        // sphere of radius 2.5, never a box (the prior lossy behaviour rendered every mesh as a unit box).
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    serverColor <- Signal.initRef(Color.white)
                    scene = Three.scene(
                        Three.mesh(Three.Geometry.sphere(2.5, 24, 12), Three.Material.standard().color(serverColor))
                    )
                    boot <- ThreeBridge.flattenInit(scene, Three.Camera.perspective())
                    // The flattened mesh descriptor carries the typed sphere geometry, not a box.
                    flattenedGeometry = boot match
                        case HostPayload.Boot(StructuralOp.Insert(_, _, sceneDesc), _) => sceneDesc.children.head.geometry
                        case other                                                     => fail(s"expected a Boot envelope, got $other")
                    rec <- ThreeBridge.reconstitute(boot)
                    (clientScene, _) = rec
                yield
                    assert(
                        flattenedGeometry == Present(GeometryDescriptor.Sphere(2.5, 24, 12)),
                        s"the wire must carry the sphere geometry + params, got $flattenedGeometry"
                    )
                    // The reconstituted client AST is a Mesh whose geometry is a Sphere of radius 2.5.
                    val meshGeometry = clientScene match
                        case s: Three.Ast.Scene =>
                            s.children.head match
                                case m: Three.Ast.Mesh => m.geometry
                                case other             => fail(s"expected a mesh child, got $other")
                        case other => fail(s"expected a scene, got $other")
                    meshGeometry match
                        case sph: Three.Ast.Geometry.Sphere =>
                            assert(sph.radius == 2.5, s"the reconstituted sphere radius must be 2.5, got ${sph.radius}")
                            assert(sph.widthSegments == 24, s"the sphere widthSegments must survive, got ${sph.widthSegments}")
                            assert(sph.heightSegments == 12, s"the sphere heightSegments must survive, got ${sph.heightSegments}")
                        case other =>
                            fail(s"the reconstituted mesh must be a sphere, not a box; got ${other.getClass.getSimpleName}")
                    end match
                end for
            }
        }
    }

    "a Basic-material mesh reconstitutes as Basic, not Standard" in {
        // D-7b: the material CLASS crosses the wire. A Basic material must reconstitute as a Basic
        // material, never coerced to Standard (the prior lossy behaviour hardcoded Material.Standard).
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    serverColor <- Signal.initRef(Color.red)
                    scene = Three.scene(
                        Three.mesh(Three.Geometry.box(), Three.Material.basic().color(serverColor))
                    )
                    boot <- ThreeBridge.flattenInit(scene, Three.Camera.perspective())
                    flattenedMaterial = boot match
                        case HostPayload.Boot(StructuralOp.Insert(_, _, sceneDesc), _) => sceneDesc.children.head.material
                        case other                                                     => fail(s"expected a Boot envelope, got $other")
                    rec <- ThreeBridge.reconstitute(boot)
                    (clientScene, _) = rec
                yield
                    assert(
                        flattenedMaterial == Present(MaterialKind.Basic),
                        s"the wire must carry the Basic material tag, got $flattenedMaterial"
                    )
                    val meshMaterial = clientScene match
                        case s: Three.Ast.Scene =>
                            s.children.head match
                                case m: Three.Ast.Mesh => m.material
                                case other             => fail(s"expected a mesh child, got $other")
                        case other => fail(s"expected a scene, got $other")
                    meshMaterial match
                        case _: Three.Ast.Material.Basic => succeed
                        case other =>
                            fail(s"the reconstituted material must be Basic, not Standard; got ${other.getClass.getSimpleName}")
                    end match
                end for
            }
        }
    }

    "a non-default camera round-trips through the boot payload" in {
        // D-8: the embed's actual camera (fov/near/far/position/lookAt) crosses the wire, so the client
        // mounts the server's viewpoint, not a default perspective.
        Scope.run {
            for
                serverColor <- Signal.initRef(Color.white)
                scene = Three.scene(Three.mesh(Three.Geometry.box(), Three.Material.standard().color(serverColor)))
                camera = Three.Camera.perspective(
                    fov = Radians.deg(42),
                    near = 0.25,
                    far = 250.0,
                    position = Vec3(3, 7, -2),
                    lookAt = Vec3(1, 0, 1)
                )
                boot <- ThreeBridge.flattenInit(scene, camera)
            yield boot match
                case HostPayload.Boot(_, cam) =>
                    cam match
                        case CameraDescriptor.Perspective(fovRadians, near, far, position, lookAt) =>
                            assert(math.abs(fovRadians - Radians.deg(42).toDouble) < 1e-9, s"fov must survive, got $fovRadians")
                            assert(near == 0.25, s"near must survive, got $near")
                            assert(far == 250.0, s"far must survive, got $far")
                            assert(position == HostValue.V3(3.0, 7.0, -2.0), s"camera position must survive, got $position")
                            assert(lookAt == HostValue.V3(1.0, 0.0, 1.0), s"camera lookAt must survive, got $lookAt")
                        case other => fail(s"the boot camera must be a Perspective descriptor, got $other")
                    end match
                    // The reconstituted camera is a perspective at the served fov/near/far/position/lookAt.
                    val rebuilt = ThreeBridge.materializeCamera(cam)
                    rebuilt match
                        case p: Three.Ast.Camera.Perspective =>
                            assert(math.abs(p.fov.toDegrees - 42.0) < 1e-9, s"rebuilt fov must be 42deg, got ${p.fov.toDegrees}")
                            assert(p.near == 0.25, s"rebuilt near must be 0.25, got ${p.near}")
                            assert(p.far == 250.0, s"rebuilt far must be 250, got ${p.far}")
                            assert(p.lookAt == Bound.Const(Vec3(1, 0, 1)), s"rebuilt lookAt must be (1,0,1), got ${p.lookAt}")
                            assert(
                                p.transform.position.contains(Bound.Const(Vec3(3, 7, -2))),
                                s"rebuilt camera position must be (3,7,-2), got ${p.transform.position}"
                            )
                        case other => fail(s"the rebuilt camera must be a Perspective, got $other")
                    end match
                case other => fail(s"the boot must be a Boot envelope carrying the camera, got $other")
            end for
        }
    }

    "toHostValue tags a whole-number opacity as Num, not Col (slot-typed, not isInstanceOf)" in {
        // D-9: on Scala.js a whole-number Double (opacity = 1.0) boxes to an Int and satisfies
        // isInstanceOf[Int], so the prior runtime-type dispatch mis-tagged it as a Color. The slot is
        // known at flatten time, so opacity/metalness/roughness/intensity tag as Num regardless of value.
        Scope.run {
            for
                // A material with whole-number opacity (1.0) and metalness (0.0): the exact values that
                // box to Int on Scala.js and would corrupt under isInstanceOf dispatch.
                scene = Three.scene(
                    Three.mesh(
                        Three.Geometry.box(),
                        Three.Material.standard(opacity = Normal.one, metalness = Normal.zero, roughness = Normal.one)
                    )
                )
                boot <- ThreeBridge.flattenInit(scene, Three.Camera.perspective())
                meshProps = boot match
                    case HostPayload.Boot(StructuralOp.Insert(_, _, sceneDesc), _) => sceneDesc.children.head.props.toMap
                    case other                                                     => fail(s"expected a Boot envelope, got $other")
            yield
                assert(
                    meshProps.get(ThreeBridge.slotOpacity) == Some(HostValue.Num(1.0)),
                    s"a whole-number opacity (1.0) must tag as Num, not Col; got ${meshProps.get(ThreeBridge.slotOpacity)}"
                )
                assert(
                    meshProps.get(ThreeBridge.slotMetalness) == Some(HostValue.Num(0.0)),
                    s"a whole-number metalness (0.0) must tag as Num, not Col; got ${meshProps.get(ThreeBridge.slotMetalness)}"
                )
                assert(
                    meshProps.get(ThreeBridge.slotRoughness) == Some(HostValue.Num(1.0)),
                    s"a whole-number roughness (1.0) must tag as Num, not Col; got ${meshProps.get(ThreeBridge.slotRoughness)}"
                )
            end for
        }
    }

    "the locked 2-arg UI.runHandlers signature type-checks (no extra public symbol)" in {
        // Type-ascription guard for the one locked public addition: the 2-arg PageHead overload.
        val _: (String, UI.PageHead) => (=> UI < Async) => Frame ?=> Seq[HttpHandler[?, ?, ?]] < Sync =
            (basePath, head) => ui => UI.runHandlers(basePath, head)(ui)
        succeed
    }

end ThreeMountChannelTest
