package kyo

import kyo.internal.Raycasting
import kyo.internal.Reconciler
import kyo.internal.ThreeFacadeOps

/** Tests for raycast pointer interaction: hit resolution, miss, nearest-of-two, hover tracking, and
  * handler invocation. Every assertion observes a real value on a real three.js object; nothing is
  * faked or mocked. The raycast tests run headless on Node against real three.js.
  *
  * Test geometry: a unit box at the origin, a PerspectiveCamera at (0, 0, 5) looking at the origin.
  * A ray from NDC (0, 0) travels from the camera through the viewport center, hitting the box near
  * face at approximately z=0.5, distance approximately 4.5.
  */
class ThreeInteractiveTest extends ThreeTest:

    private val baseCam = Three.Camera.perspective()

    "hit resolves to handled node when ray passes through mesh" in {
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onClick(_ => Sync.defer { () })
            .position(Vec3(0, 0, 0))
        val scene = Three.scene(mesh)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    cam    <- ThreeFacadeOps.makeCamera(baseCam)
                    result <- Raycasting.hit(mounted, cam, (0.0, 0.0))
                yield
                    result match
                        case Present((live, pointer)) =>
                            assert(live.node eq mesh)
                            assert(pointer.ndc == (0.0, 0.0))
                        case Absent =>
                            assert(false, "expected a hit but got Absent")
                    end match
                end for
            }
        }
    }

    "hit world-point and distance have concrete values for a centered ray" in {
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onClick(_ => Sync.defer { () })
            .position(Vec3(0, 0, 0))
        val scene = Three.scene(mesh)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    cam    <- ThreeFacadeOps.makeCamera(baseCam)
                    result <- Raycasting.hit(mounted, cam, (0.0, 0.0))
                yield
                    result match
                        case Present((_, pointer)) =>
                            assert(math.abs(pointer.point.z - 0.5) < 0.1, s"expected point.z ~0.5 but got ${pointer.point.z}")
                            assert(math.abs(pointer.distance - 4.5) < 0.5, s"expected distance ~4.5 but got ${pointer.distance}")
                        case Absent =>
                            assert(false, "expected a hit but got Absent")
                    end match
                end for
            }
        }
    }

    "miss returns Absent when ray does not intersect any mesh" in {
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onClick(_ => Sync.defer { () })
            .position(Vec3(10, 10, 10))
        val scene = Three.scene(mesh)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    cam    <- ThreeFacadeOps.makeCamera(baseCam)
                    result <- Raycasting.hit(mounted, cam, (0.0, 0.0))
                yield assert(result == Absent, s"expected Absent but got $result")
                end for
            }
        }
    }

    "ray through unhandled sibling returns Absent (interactive filter)" in {
        val handled = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onClick(_ => Sync.defer { () })
            .position(Vec3(5, 5, 5))
        val unhandled = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .position(Vec3(0, 0, 0))
        val scene = Three.scene(handled, unhandled)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    cam    <- ThreeFacadeOps.makeCamera(baseCam)
                    result <- Raycasting.hit(mounted, cam, (0.0, 0.0))
                yield assert(result == Absent, s"expected Absent (unhandled mesh not in interactive targets) but got $result")
                end for
            }
        }
    }

    "nearest of two overlapping meshes wins" in {
        val front = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onClick(_ => Sync.defer { () })
            .position(Vec3(0, 0, 0))
        val back = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onClick(_ => Sync.defer { () })
            .position(Vec3(0, 0, -1))
        val scene = Three.scene(front, back)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    cam    <- ThreeFacadeOps.makeCamera(baseCam)
                    result <- Raycasting.hit(mounted, cam, (0.0, 0.0))
                yield
                    result match
                        case Present((live, _)) =>
                            assert(live.node eq front, "expected nearest (front) mesh to win")
                        case Absent =>
                            assert(false, "expected a hit but got Absent")
                    end match
                end for
            }
        }
    }

    "onPointerOver handler fires when pointer enters a mesh" in {
        var overPointer = Maybe.empty[Pointer]
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onPointerOver(p => Sync.defer { overPointer = Present(p) })
            .position(Vec3(0, 0, 0))
        val scene = Three.scene(mesh)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    cam    <- ThreeFacadeOps.makeCamera(baseCam)
                    result <- Raycasting.hit(mounted, cam, (0.0, 0.0))
                    _ <- result match
                        case Present((live, pointer)) =>
                            live.node match
                                case i: Three.Ast.Interactive =>
                                    i.meshProps.onPointerOver.fold((): Unit < Async) { f => f(pointer).unit }
                                case _ => (): Unit < Async
                        case Absent => (): Unit < Async
                yield
                    assert(overPointer.isDefined, "expected onPointerOver to fire")
                    overPointer.foreach { p =>
                        assert(math.abs(p.distance - 4.5) < 0.5)
                    }
                end for
            }
        }
    }

    "onPointerOut handler fires when pointer leaves a mesh" in {
        var outFired = false
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onPointerOut(_ => Sync.defer { outFired = true })
            .position(Vec3(0, 0, 0))
        val scene = Three.scene(mesh)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    cam       <- ThreeFacadeOps.makeCamera(baseCam)
                    hitResult <- Raycasting.hit(mounted, cam, (0.0, 0.0))
                    _ <- hitResult match
                        case Present((live, pointer)) =>
                            live.node match
                                case i: Three.Ast.Interactive =>
                                    i.meshProps.onPointerOut.fold((): Unit < Async) { f => f(pointer).unit }
                                case _ => (): Unit < Async
                        case Absent => (): Unit < Async
                yield assert(outFired, "expected onPointerOut to fire when pointer leaves mesh")
                end for
            }
        }
    }

    "buttons payload is populated on the Pointer" in {
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onClick(_ => Sync.defer { () })
            .position(Vec3(0, 0, 0))
        val scene = Three.scene(mesh)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    cam    <- ThreeFacadeOps.makeCamera(baseCam)
                    result <- Raycasting.hit(mounted, cam, (0.0, 0.0))
                yield
                    result match
                        case Present((_, pointer)) =>
                            assert(pointer.buttons == Pointer.Buttons.none)
                        case Absent =>
                            assert(false, "expected a hit but got Absent")
                    end match
                end for
            }
        }
    }

    "a mesh with onClick is collected as an interactive target" in {
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onClick(_ => Sync.defer { () })
        val scene = Three.scene(mesh)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    targets      = Raycasting.interactiveTargets(mounted)
                yield
                    assert(targets.nonEmpty, "expected at least one interactive target in the mounted scene")
                    assert(targets.exists(_.node eq mesh))
                end for
            }
        }
    }

    "hover transition fires neither handler when the same mesh is re-hit" in {
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onPointerOver(_ => Sync.defer { () })
            .onPointerOut(_ => Sync.defer { () })
            .position(Vec3(0, 0, 0))
        val scene = Three.scene(mesh)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    cam  <- ThreeFacadeOps.makeCamera(baseCam)
                    hit1 <- Raycasting.hit(mounted, cam, (0.0, 0.0))
                    hit2 <- Raycasting.hit(mounted, cam, (0.01, 0.01))
                yield
                    (hit1, hit2) match
                        case (Present((live1, _)), Present((live2, _))) =>
                            // Both NDC positions hit the same underlying mesh object.
                            assert(live1.obj eq live2.obj, "both NDC positions should hit the same three.js mesh object")
                            // Same target: hoverTransition must return (Absent, Absent) -- no over or out fires.
                            val (out0, over0) = ThreeMount.hoverTransition(Present(live1), Present(live2))
                            assert(out0.isEmpty, "re-hitting the same mesh must not fire onPointerOut")
                            assert(over0.isEmpty, "re-hitting the same mesh must not fire onPointerOver")
                            // Enter from no prior hit: hoverTransition must return (Absent, Present(live1)).
                            val (out1, over1) = ThreeMount.hoverTransition(Absent, Present(live1))
                            assert(out1.isEmpty, "entering from no prior hit must not fire onPointerOut")
                            assert(over1.map(_.obj eq live1.obj).getOrElse(false), "entering must fire onPointerOver for the entered mesh")
                            // Leave to no new hit: hoverTransition must return (Present(live1), Absent).
                            val (out2, over2) = ThreeMount.hoverTransition(Present(live1), Absent)
                            assert(out2.map(_.obj eq live1.obj).getOrElse(false), "leaving must fire onPointerOut for the left mesh")
                            assert(over2.isEmpty, "leaving to no hit must not fire onPointerOver")
                        case _ =>
                            assert(false, "expected both NDC positions to produce a hit")
                    end match
                end for
            }
        }
    }

end ThreeInteractiveTest
