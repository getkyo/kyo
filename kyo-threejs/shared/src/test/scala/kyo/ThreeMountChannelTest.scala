package kyo

import kyo.internal.HostInit
import kyo.internal.HostPayload
import kyo.internal.HostValue
import kyo.internal.Reconciler
import kyo.internal.StructuralOp

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

    "the locked 2-arg UI.runHandlers signature type-checks (no extra public symbol)" in {
        // Type-ascription guard for the one locked public addition: the 2-arg PageHead overload.
        val _: (String, UI.PageHead) => (=> UI < Async) => Frame ?=> Seq[HttpHandler[?, ?, ?]] < Sync =
            (basePath, head) => ui => UI.runHandlers(basePath, head)(ui)
        assert(true)
    }

end ThreeMountChannelTest
