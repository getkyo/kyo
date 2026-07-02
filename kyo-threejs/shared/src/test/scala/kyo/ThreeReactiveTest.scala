package kyo

import kyo.internal.Reconciler

/** Tests for the reactive-region observe wiring in [[ThreeMount]].
  *
  * Every fixture runs on Node; no browser or WebGL needed. Assertions observe real three.js object
  * property values after applying the patch functions that `ThreeMount.boundRefs` generates. No
  * spy call counts, no mock objects.
  */
class ThreeReactiveTest extends ThreeTest:

    "signal position emits a patch that mutates exactly the bound object" in {
        Signal.initRef(Three.Vec3(0, 0, 0)).map { posRef =>
            val mesh  = Three.mesh(Three.Geometry.box(), Three.Material.standard()).position(posRef)
            val scene = Three.scene(mesh)
            Scope.run {
                Reconciler.mount(scene).map { case (_, mounted) =>
                    Sync.Unsafe.defer {
                        val refs = ThreeMount.boundRefs(mounted)
                        assert(refs.nonEmpty)
                        val (live, patchFn, _) = refs(0)
                        patchFn(Three.Vec3(1, 2, 3))(live.obj)
                        val x = live.obj.position.x.asInstanceOf[Double]
                        assert(x == 1.0)
                    }
                }
            }
        }
    }

    "sibling mesh with Const color yields no patch entry in boundRefs" in {
        Signal.initRef(Three.Color.white).map { colorRef =>
            val meshA = Three.mesh(
                Three.Geometry.box(),
                Three.Material.standard().color(colorRef)
            )
            val meshB = Three.mesh(
                Three.Geometry.box(),
                Three.Material.standard()
            )
            val scene = Three.scene(meshA, meshB)
            Scope.run {
                Reconciler.mount(scene).map { case (_, mounted) =>
                    Sync.Unsafe.defer {
                        val refs = ThreeMount.boundRefs(mounted)
                        assert(refs.size == 1)
                    }
                }
            }
        }
    }

    "signal patch on one mesh leaves sibling live object identity unchanged" in {
        Signal.initRef(Three.Vec3(0, 0, 0)).map { posRef =>
            val meshA = Three.mesh(Three.Geometry.box(), Three.Material.standard()).position(posRef)
            val meshB = Three.mesh(Three.Geometry.sphere(), Three.Material.standard())
            val scene = Three.scene(meshA, meshB)
            Scope.run {
                Reconciler.mount(scene).map { case (rootLive, mounted) =>
                    Sync.Unsafe.defer {
                        val siblingBefore      = rootLive.children(1).obj
                        val refs               = ThreeMount.boundRefs(mounted)
                        val (live, patchFn, _) = refs(0)
                        patchFn(Three.Vec3(5, 5, 5))(live.obj)
                        val siblingAfter = rootLive.children(1).obj
                        assert(siblingBefore eq siblingAfter)
                    }
                }
            }
        }
    }

    "signal intensity on a light yields a patch that updates the live light object" in {
        Signal.initRef(1.0).map { intensityRef =>
            val light = Three.Light.ambient().intensity(intensityRef)
            val scene = Three.scene(light)
            Scope.run {
                Reconciler.mount(scene).map { case (_, mounted) =>
                    Sync.Unsafe.defer {
                        val refs = ThreeMount.boundRefs(mounted)
                        assert(refs.nonEmpty)
                        val (live, patchFn, _) = refs(0)
                        patchFn(2.5)(live.obj)
                        val intensity = live.obj.intensity.asInstanceOf[Double]
                        assert(intensity == 2.5)
                    }
                }
            }
        }
    }

    "no full rebuild: applying a signal patch does not replace the scene root live object" in {
        Signal.initRef(Three.Vec3(0, 0, 0)).map { posRef =>
            val mesh  = Three.mesh(Three.Geometry.box(), Three.Material.standard()).position(posRef)
            val scene = Three.scene(mesh)
            Scope.run {
                Reconciler.mount(scene).map { case (rootLive, mounted) =>
                    Sync.Unsafe.defer {
                        val rootBefore         = rootLive.obj
                        val refs               = ThreeMount.boundRefs(mounted)
                        val (live, patchFn, _) = refs(0)
                        patchFn(Three.Vec3(1, 1, 1))(live.obj)
                        val rootAfter = rootLive.obj
                        assert(rootBefore eq rootAfter)
                    }
                }
            }
        }
    }

end ThreeReactiveTest
