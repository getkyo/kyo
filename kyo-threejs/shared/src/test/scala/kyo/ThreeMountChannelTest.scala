package kyo

import kyo.Three.foreachKeyed
import kyo.internal.HostInit
import kyo.internal.HostPayload
import kyo.internal.HostValue
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
                    boot <- ThreeBridge.flattenInit(serverScene(serverColor))
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
                    boot        <- ThreeBridge.flattenInit(serverScene(serverColor))
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
                    boot        <- ThreeBridge.flattenInit(serverScene(serverColor))
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
                    boot        <- ThreeBridge.flattenInit(serverScene(serverColor))
                    rec         <- ThreeBridge.reconstitute(boot)
                    (clientScene, _) = rec
                    channel <- HostChannel.init(HostInit(clientScene, Three.Camera.perspective(), ThreeFrames.Raf))
                    op = StructuralOp.Remove("k1")
                    _     <- ThreeMount.applyHostUpdate(channel, HostPayload.Structural(op))
                    inbox <- channel.structuralInbox.current
                yield assert(
                    inbox == Chunk(op),
                    s"a Structural HostUpdate must append to the inbox, got $inbox"
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
                    // The boot batch is three Inserts (a@0, b@1, c@2).
                    def insertKey(p: HostPayload): String = p match
                        case HostPayload.Structural(StructuralOp.Insert(k, _, _)) => k
                        case other                                                => s"not-insert:$other"
                    assert(
                        Set(insertKey(ins0), insertKey(ins1), insertKey(ins2)) == Set("a", "b", "c"),
                        s"the boot batch must insert a,b,c; got ${List(ins0, ins1, ins2)}"
                    )
                    // The diff batch is exactly Remove(b), Move(c, 1), Insert(d, 2, descriptor): the
                    // minimal op set, NOT a full re-materialize of [a,c,d].
                    val diffOps = List(op1, op2, op3).map { case HostPayload.Structural(op) => op; case p => fail(s"non-structural: $p") }
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

    "the locked 2-arg UI.runHandlers signature type-checks (no extra public symbol)" in {
        // Type-ascription guard for the one locked public addition: the 2-arg PageHead overload.
        val _: (String, UI.PageHead) => (=> UI < Async) => Frame ?=> Seq[HttpHandler[?, ?, ?]] < Sync =
            (basePath, head) => ui => UI.runHandlers(basePath, head)(ui)
        succeed
    }

end ThreeMountChannelTest
