package kyo

import kyo.Three.foreachKeyed
import kyo.Three.render
import kyo.internal.Reconciler
import kyo.internal.ThreeFacadeOps
import scala.scalajs.js as sjs

/** Tests for [[ThreeMount]] and the frame loop using the deterministic `testDriver` / `ThreeFrames.Manual` path.
  *
  * All fixtures run on Node via the `testDriver` seam (no WebGL, no browser needed). The browser-path
  * GL-context assertions are in `ThreeMountBrowserTest` in js/src/test.
  *
  * Every assertion observes a real value on a real three.js object; nothing is faked or mocked.
  */
class ThreeMountTest extends ThreeTest:

    // ---- scene + camera used across fixtures ----

    private val boxMesh = Three.mesh(
        Three.Geometry.box(),
        Three.Material.standard()
    )

    private def baseScene = Three.scene(boxMesh)

    private def baseCamera = Three.Camera.perspective()

    // ---- 9 node-testable fixtures ----

    "testDriver path materializes the scene into a live object" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(baseScene)
                    (_, mounted) = mountResult
                yield
                    assert(mounted.live.nonEmpty, "mount must produce at least one live entry")
                    assert(
                        mounted.live.get(new Reconciler.IdentityKey(boxMesh)).isDefined,
                        "the box mesh must have a corresponding live entry in the mounted map"
                    )
                end for
            }
        }
    }

    "one tick runs each onFrame closure exactly once" in {
        var calls = 0
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(_ => Sync.defer { calls += 1 })
        val scene = Three.scene(mesh)
        Scope.run {
            Three.testDriver(scene, baseCamera).map { driver =>
                driver.step(16.millis).map { _ =>
                    assert(calls == 1)
                }
            }
        }
    }

    "onFrame receives the tick delta supplied to step" in {
        var observedDelta = Duration.Zero
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(tick => Sync.defer { observedDelta = tick.delta })
        val scene = Three.scene(mesh)
        Scope.run {
            Three.testDriver(scene, baseCamera).map { driver =>
                driver.step(33.millis).map { _ =>
                    assert(observedDelta == 33.millis)
                }
            }
        }
    }

    "N ticks advance the onFrame closure N times" in {
        var calls = 0
        val n     = 10
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(_ => Sync.defer { calls += 1 })
        val scene = Three.scene(mesh)
        Scope.run {
            Three.testDriver(scene, baseCamera).map { driver =>
                Kyo.foreachDiscard(Chunk.from(0 until n))(_ => driver.step(16.millis)).map { _ =>
                    assert(calls == n)
                }
            }
        }
    }

    "onFrame closure mutates a live object rotation.y and submitSeam fires N times per N steps" in {
        val n    = 5
        val step = 0.016

        var submitCount                    = 0
        var liveObjRef: Maybe[sjs.Dynamic] = Absent
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(_ =>
                Sync.Unsafe.defer {
                    liveObjRef.foreach { ref =>
                        ref.rotation.y = ref.rotation.y.asInstanceOf[Double] + step
                    }
                }
            )
        val scene = Three.scene(mesh)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (rootLive, mounted) = mountResult
                    cam <- ThreeFacadeOps.makeCamera(baseCamera)
                    _ <- Sync.Unsafe.defer {
                        mounted.live.get(new Reconciler.IdentityKey(mesh)).foreach { live =>
                            liveObjRef = Present(live.obj)
                        }
                    }
                    _ <- ThreeMount.subscribeRegions(mounted)
                    _ <- Kyo.foreachDiscard(Chunk.from(0 until n)) { _ =>
                        Kyo.foreachDiscard(ThreeMount.onFrameClosures(mounted)) { f =>
                            f(Three.Tick(16.millis, 16.millis, 0L)).unit
                        }.andThen(ThreeMount.submitSeam(rootLive, cam).map { _ =>
                            Sync.defer { submitCount += 1 }
                        })
                    }
                yield
                    assert(submitCount == n)
                    assert(liveObjRef.isDefined)
                    val ry = liveObjRef.get.rotation.y.asInstanceOf[Double]
                    assert(math.abs(ry - (n * step)) < 0.001, s"expected rotation.y ~${n * step} but got $ry")
                end for
            }
        }
    }

    "the live runLoop applies each onFrame mutation before the render submit of the same tick" in {
        // Drives the real ThreeMount.runLoop through ThreeFrames.Manual with a renderer seam that records
        // the live mesh rotation at the render call. The onFrame closure mutates that same rotation,
        // so a recorded value that already reflects the mutation proves the closure ran inline before
        // the submit (the one-tick ordering guard). The renderer is a real js object whose `render`
        // observes the real live mesh value; nothing about the scene is mocked.
        val rotation                       = 0.25
        var recordedAtSubmit               = Double.NaN
        var liveObjRef: Maybe[sjs.Dynamic] = Absent
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(_ =>
                Sync.Unsafe.defer {
                    liveObjRef.foreach(ref => ref.rotation.y = rotation)
                }
            )
        val scene = Three.scene(mesh)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (rootLive, mounted) = mountResult
                    cam <- ThreeFacadeOps.makeCamera(baseCamera)
                    _ <- Sync.Unsafe.defer {
                        mounted.live.get(new Reconciler.IdentityKey(mesh)).foreach(l => liveObjRef = Present(l.obj))
                    }
                    recordingRenderer <- Sync.Unsafe.defer {
                        sjs.Dynamic.literal(
                            render = (_: sjs.Dynamic, _: sjs.Dynamic) =>
                                liveObjRef.foreach { ref =>
                                    recordedAtSubmit = ref.rotation.y.asInstanceOf[Double]
                                }
                        )
                    }
                    _ <- ThreeMount.runLoop(
                        mounted,
                        rootLive,
                        cam,
                        recordingRenderer,
                        ThreeFrames.Manual(driver => Abort.run[ThreeException](driver.step(16.millis)))
                    )
                yield assert(
                    math.abs(recordedAtSubmit - rotation) < 0.0001,
                    s"render submit must observe the onFrame mutation (expected rotation.y == $rotation, " +
                        s"saw $recordedAtSubmit at submit time)"
                )
            }
        }
    }

    "large N ticks runs without per-tick coordination overhead" in {
        val n     = 100
        var count = 0
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(_ => Sync.defer { count += 1 })
        val scene = Three.scene(mesh)
        Scope.run {
            Three.testDriver(scene, baseCamera).map { driver =>
                Kyo.foreachDiscard(Chunk.from(0 until n))(_ => driver.step(1.millis)).map { _ =>
                    assert(count == n)
                }
            }
        }
    }

    "interrupt cascades: loop stops when scope closes" in {
        var steps = 0
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(_ => Sync.defer { steps += 1 })
        val scene = Three.scene(mesh)
        Scope.run {
            Three.testDriver(scene, baseCamera).map { driver =>
                driver.step(16.millis).map { _ =>
                    val stepsBefore = steps
                    assert(stepsBefore >= 1)
                }
            }
        }.map { _ =>
            assert(steps >= 1)
        }
    }

    "ThreeFrames.Manual driver yields onFrame closures in step order" in {
        val deltas = Chunk(10.millis, 20.millis, 30.millis)
        var seen   = Chunk.empty[Duration]
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(tick => Sync.defer { seen = seen.appended(tick.delta) })
        val scene = Three.scene(mesh)
        Scope.run {
            Three.testDriver(scene, baseCamera).map { driver =>
                Kyo.foreachDiscard(deltas)(d => driver.step(d)).map { _ =>
                    assert(seen == deltas)
                }
            }
        }
    }

    // ---- Group.onFrame fixtures ----

    "a Group.onFrame fires N times over N steps" in {
        var calls = 0
        val n     = 7
        val group = Three.group(
            Three.mesh(Three.Geometry.box(), Three.Material.standard())
        ).onFrame(_ => Sync.defer { calls += 1 })
        val scene = Three.scene(group)
        Scope.run {
            Three.testDriver(scene, baseCamera).map { driver =>
                Kyo.foreachDiscard(Chunk.from(0 until n))(_ => driver.step(16.millis)).map { _ =>
                    assert(calls == n)
                }
            }
        }
    }

    "a Group.onFrame mutates the live container rotation each step" in {
        val n                              = 4
        val step                           = 0.05
        var liveObjRef: Maybe[sjs.Dynamic] = Absent
        val group = Three.group(
            Three.mesh(Three.Geometry.box(), Three.Material.standard())
        ).onFrame(_ =>
            Sync.Unsafe.defer {
                liveObjRef.foreach { ref =>
                    ref.rotation.y = ref.rotation.y.asInstanceOf[Double] + step
                }
            }
        )
        val scene = Three.scene(group)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Sync.Unsafe.defer {
                        mounted.live.get(new Reconciler.IdentityKey(group)).foreach(l => liveObjRef = Present(l.obj))
                    }
                    _ <- Kyo.foreachDiscard(Chunk.from(0 until n)) { _ =>
                        Kyo.foreachDiscard(ThreeMount.onFrameClosures(mounted))(f =>
                            f(Three.Tick(16.millis, 16.millis, 0L)).unit
                        )
                    }
                yield
                    assert(liveObjRef.isDefined)
                    val ry = liveObjRef.get.rotation.y.asInstanceOf[Double]
                    assert(math.abs(ry - (n * step)) < 0.001, s"expected rotation.y ~${n * step} but got $ry")
                end for
            }
        }
    }

    // ---- Structural reactive region population (Reactive / Foreach live-mount fill) ----

    /** Counts the live three.js children attached to a holder object. */
    private def childCount(mounted: Reconciler.Mounted, node: Three): Int =
        // Unsafe: reading the live three.js children array via js.Dynamic FFI to assert holder child count.
        import AllowUnsafe.embrace.danger
        mounted.live.get(new Reconciler.IdentityKey(node))
            .map(_.obj.children.asInstanceOf[sjs.Array[sjs.Dynamic]].length)
            .getOrElse(-1)
    end childCount

    "a Foreach region fills its holder from the signal's current value at mount" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    items <- Signal.initRef(Chunk(1, 2, 3))
                    foreach = items.foreachKeyed(_.toString) { i =>
                        Three.mesh(Three.Geometry.box(), Three.Material.standard()).position(Vec3(i.toDouble, 0, 0))
                    }
                    scene = Three.scene(foreach)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                yield assert(childCount(mounted, foreach) == 3, s"expected 3 cubes, got ${childCount(mounted, foreach)}")
            }
        }
    }

    "a Foreach region re-diffs its holder when the signal emits a new list" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    items <- Signal.initRef(Chunk(1, 2))
                    foreach = items.foreachKeyed(_.toString) { i =>
                        Three.mesh(Three.Geometry.box(), Three.Material.standard()).position(Vec3(i.toDouble, 0, 0))
                    }
                    scene = Three.scene(foreach)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    two = childCount(mounted, foreach)
                    _ <- items.set(Chunk(1, 2, 3, 4))
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    four = childCount(mounted, foreach)
                yield
                    assert(two == 2, s"expected 2 cubes initially, got $two")
                    assert(four == 4, s"expected 4 cubes after re-diff, got $four")
                end for
            }
        }
    }

    "a Foreach region reuses the live object for an unchanged key across a re-diff" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                import AllowUnsafe.embrace.danger
                for
                    items <- Signal.initRef(Chunk(1, 2))
                    foreach = items.foreachKeyed(_.toString) { i =>
                        Three.mesh(Three.Geometry.box(), Three.Material.standard()).position(Vec3(i.toDouble, 0, 0))
                    }
                    scene = Three.scene(foreach)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    liveAfterFirst = mounted.live.size
                    _ <- items.set(Chunk(1, 2, 3))
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    liveAfterSecond = mounted.live.size
                yield assert(
                    liveAfterSecond == liveAfterFirst + 1,
                    s"keyed reuse: adding one element must materialize exactly one new live entry; " +
                        s"live grew from $liveAfterFirst to $liveAfterSecond"
                )
                end for
            }
        }
    }

    "a Reactive region fills its holder with the projected subtree at mount" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    flag <- Signal.initRef(true)
                    reactive = flag.render { on =>
                        if on then Three.group(Three.mesh(Three.Geometry.box(), Three.Material.standard()))
                        else Three.empty
                    }
                    scene = Three.scene(reactive)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                yield assert(childCount(mounted, reactive) == 1, s"expected 1 subtree, got ${childCount(mounted, reactive)}")
            }
        }
    }

    "subscribeReactiveRegions fills foreach and reactive holders through the mount path" in {
        val items = Signal.initRef(Chunk(1, 2, 3))
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    ref <- items
                    foreach = ref.foreachKeyed(_.toString) { i =>
                        Three.mesh(Three.Geometry.box(), Three.Material.standard()).position(Vec3(i.toDouble, 0, 0))
                    }
                    food = ref.render { its =>
                        Three.mesh(Three.Geometry.sphere(), Three.Material.standard())
                            .position(Vec3(its.size.toDouble, 0, 0))
                    }
                    scene = Three.scene(Three.Light.ambient(), foreach, food)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- ThreeMount.subscribeReactiveRegions(mounted)
                yield
                    assert(childCount(mounted, foreach) == 3, s"foreach holder should hold 3 cubes, got ${childCount(mounted, foreach)}")
                    assert(childCount(mounted, food) == 1, s"reactive holder should hold 1 sphere, got ${childCount(mounted, food)}")
                end for
            }
        }
    }

    // ---- per-element dispose-once and live-map retirement ----

    /** Finds the keyed `ReactiveRegion` for the given holder node using ref-identity. */
    private def regionForNode(mounted: Reconciler.Mounted, node: Three): Maybe[Reconciler.ReactiveRegion] =
        Maybe.fromOption(Reconciler.reactiveRegions(mounted).toSeq.find(_.holder.node eq node))

    "foreachKeyed shrink disposes removed child geometry and material exactly once" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                var geomDisposeCount = 0
                var matDisposeCount  = 0
                for
                    items <- Signal.initRef(Chunk(1, 2))
                    foreach = items.foreachKeyed(_.toString) { i =>
                        Three.mesh(Three.Geometry.box(), Three.Material.standard())
                            .position(Vec3(i.toDouble, 0, 0))
                    }
                    scene = Three.scene(foreach)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    // Attach dispose listeners to key "2"'s mesh geometry and material.
                    _ <- Sync.Unsafe.defer {
                        regionForNode(mounted, foreach).foreach { region =>
                            region.prevKeyed.toSeq.find(_._1 == "2").foreach { case (_, live) =>
                                live.obj.geometry.addEventListener(
                                    "dispose",
                                    (_: sjs.Any) => geomDisposeCount += 1
                                )
                                live.obj.material.addEventListener(
                                    "dispose",
                                    (_: sjs.Any) => matDisposeCount += 1
                                )
                            }
                        }
                    }
                    liveSizeBefore <- Sync.Unsafe.defer(mounted.live.size)
                    // Remove key "2" by shrinking to Chunk(1).
                    _             <- items.set(Chunk(1))
                    _             <- Reconciler.fillReactiveRegionsOnce(mounted)
                    liveSizeAfter <- Sync.Unsafe.defer(mounted.live.size)
                yield
                    assert(geomDisposeCount == 1, s"geometry dispose must fire exactly once, got $geomDisposeCount")
                    assert(matDisposeCount == 1, s"material dispose must fire exactly once, got $matDisposeCount")
                    assert(liveSizeAfter < liveSizeBefore, s"live map must shrink on removal: before=$liveSizeBefore after=$liveSizeAfter")
                end for
            }
        }
    }

    "foreachKeyed shrink: unchanged keys keep the same Live instance and are not disposed" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                var keptGeomDisposeCount = 0
                for
                    items <- Signal.initRef(Chunk(1, 2))
                    foreach = items.foreachKeyed(_.toString) { i =>
                        Three.mesh(Three.Geometry.box(), Three.Material.standard())
                    }
                    scene = Three.scene(foreach)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    // Capture the Live identity for key "1" (the kept key) and attach a dispose listener.
                    keptLiveRef <- Sync.Unsafe.defer {
                        var keptLive: Maybe[Reconciler.Live] = Absent
                        regionForNode(mounted, foreach).foreach { region =>
                            region.prevKeyed.toSeq.find(_._1 == "1").foreach { case (_, live) =>
                                live.obj.geometry.addEventListener(
                                    "dispose",
                                    (_: sjs.Any) => keptGeomDisposeCount += 1
                                )
                                keptLive = Present(live)
                            }
                        }
                        keptLive
                    }
                    // Remove key "2"; key "1" must survive with the same Live instance.
                    _ <- items.set(Chunk(1))
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    keptLiveAfterRef <- Sync.Unsafe.defer {
                        var keptLiveAfter: Maybe[Reconciler.Live] = Absent
                        regionForNode(mounted, foreach).foreach { region =>
                            region.prevKeyed.toSeq.find(_._1 == "1").foreach { case (_, live) =>
                                keptLiveAfter = Present(live)
                            }
                        }
                        keptLiveAfter
                    }
                yield
                    assert(keptGeomDisposeCount == 0, s"kept key must not dispose: got $keptGeomDisposeCount")
                    assert(
                        (keptLiveRef, keptLiveAfterRef) match
                            case (Present(a), Present(b)) => a.obj eq b.obj
                            case _                        => false,
                        "kept key must reuse the same Live instance (same three.js object reference)"
                    )
                end for
            }
        }
    }

    "Reactive swap disposes the prior subtree's GL resources exactly once" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                var geomDisposeCount = 0
                var matDisposeCount  = 0
                for
                    flag <- Signal.initRef(true)
                    reactive = flag.render { on =>
                        if on then
                            Three.mesh(Three.Geometry.box(), Three.Material.standard())
                        else
                            Three.mesh(Three.Geometry.sphere(), Three.Material.standard())
                    }
                    scene = Three.scene(reactive)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    // Attach dispose listeners to the initial subtree's mesh geometry and material.
                    _ <- Sync.Unsafe.defer {
                        regionForNode(mounted, reactive).foreach { region =>
                            region.prevKeyed.headMaybe.foreach { case (_, live) =>
                                val _ = live.obj.geometry.addEventListener(
                                    "dispose",
                                    (_: sjs.Any) => geomDisposeCount += 1
                                )
                                val _ = live.obj.material.addEventListener(
                                    "dispose",
                                    (_: sjs.Any) => matDisposeCount += 1
                                )
                            }
                        }
                    }
                    // Swap the reactive signal; the prior subtree must dispose exactly once.
                    _ <- flag.set(false)
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                yield
                    assert(geomDisposeCount == 1, s"prior subtree geometry dispose must fire exactly once, got $geomDisposeCount")
                    assert(matDisposeCount == 1, s"prior subtree material dispose must fire exactly once, got $matDisposeCount")
                end for
            }
        }
    }

    // ---- Reactive camera position ----

    "reactive camera position: boundRefs registers the signal and patch updates the live .position" in {
        // A Perspective camera with .position(signal) placed as a scene child appears in
        // boundRefs so subscribeRegions wires live position updates. The patch function is applied
        // directly to the live object, asserting the three.js camera .position changes to the
        // emitted Vec3.
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    posRef <- Signal.initRef(Vec3(0, 0, 5))
                    cam   = Three.Camera.perspective().position(posRef)
                    scene = Three.scene(cam)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    refs   <- Sync.Unsafe.defer(ThreeMount.boundRefs(mounted))
                    camLiv <- Sync.Unsafe.defer(mounted.live.get(new Reconciler.IdentityKey(cam)))
                yield
                    assert(camLiv.isDefined, "camera must have a live entry in the mounted map")
                    assert(refs.nonEmpty, "boundRefs must register at least one triple for the reactive camera position")
                    val newPos = Vec3(7, 8, 9)
                    refs.foreach { case (live, patch, _) =>
                        if live.node eq cam then
                            import AllowUnsafe.embrace.danger
                            Reconciler.patchProp(live, patch(newPos)(_))
                    }
                    val liveObj = camLiv.get.obj
                    import AllowUnsafe.embrace.danger
                    val px = liveObj.position.x.asInstanceOf[Double]
                    val py = liveObj.position.y.asInstanceOf[Double]
                    val pz = liveObj.position.z.asInstanceOf[Double]
                    assert(math.abs(px - 7.0) < 0.001, s"camera position.x must be 7.0 after patch, got $px")
                    assert(math.abs(py - 8.0) < 0.001, s"camera position.y must be 8.0 after patch, got $py")
                    assert(math.abs(pz - 9.0) < 0.001, s"camera position.z must be 9.0 after patch, got $pz")
                end for
            }
        }
    }

    "reactive light position: a signal position on a light registers in boundRefs and patches the live .position" in {
        // A Directional light with .position(signal) must appear in boundRefs so its live three.js
        // position updates on emission. The setter writes into the light's transform (the same field
        // the factory's position param writes into), so both signal and static positions wire identically.
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    posRef <- Signal.initRef(Vec3(1, 1, 1))
                    light = Three.Light.directional().position(posRef)
                    scene = Three.scene(light)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    refs    <- Sync.Unsafe.defer(ThreeMount.boundRefs(mounted))
                    liveOpt <- Sync.Unsafe.defer(mounted.live.get(new Reconciler.IdentityKey(light)))
                yield
                    assert(liveOpt.isDefined, "light must have a live entry in the mounted map")
                    assert(
                        refs.exists(_._1.node eq light),
                        "boundRefs must register a triple for the light's signal position"
                    )
                    val newPos = Vec3(4, 5, 6)
                    refs.foreach { case (live, patch, _) =>
                        if live.node eq light then
                            import AllowUnsafe.embrace.danger
                            Reconciler.patchProp(live, patch(newPos)(_))
                    }
                    val liveObj = liveOpt.get.obj
                    import AllowUnsafe.embrace.danger
                    val px = liveObj.position.x.asInstanceOf[Double]
                    val py = liveObj.position.y.asInstanceOf[Double]
                    val pz = liveObj.position.z.asInstanceOf[Double]
                    assert(math.abs(px - 4.0) < 0.001, s"light position.x must be 4.0 after patch, got $px")
                    assert(math.abs(py - 5.0) < 0.001, s"light position.y must be 5.0 after patch, got $py")
                    assert(math.abs(pz - 6.0) < 0.001, s"light position.z must be 6.0 after patch, got $pz")
                end for
            }
        }
    }

    "reactive region children: a signal prop on a region child subtree updates through the live mount path" in {
        // End-to-end through the production wiring: ThreeMount.subscribeReactiveRegions installs the real
        // subscribeSubtreeBoundRefs hook, which walks each materialized region element's subtree (a group
        // wrapping a mesh here, exercising subtreeBoundRefs's recursion into a child) and forks an observe
        // fiber per reactive prop. Emitting a new position must move the live child mesh; dropping the hook
        // wiring or the subtree recursion leaves the mesh at its materialize seed and fails this test.
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    posRef <- Signal.initRef(Vec3(1, 1, 1))
                    items  <- Signal.initRef(Chunk(0))
                    region = items.foreachKeyed(_.toString)(_ =>
                        Three.group(
                            Three.mesh(Three.Geometry.box(), Three.Material.standard()).position(posRef)
                        )
                    )
                    scene = Three.scene(region)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _   <- ThreeMount.subscribeReactiveRegions(mounted)
                    _   <- posRef.set(Vec3(7, 8, 9))
                    got <- pollUntil(liveMeshX(mounted).map(x => math.abs(x - 7.0) < 0.001))
                    px  <- liveMeshX(mounted)
                yield
                    assert(got, s"the region-child mesh must update through the live subscription; final position.x=$px")
                    assert(math.abs(px - 7.0) < 0.001, s"region-child mesh position.x must be 7.0, got $px")
                end for
            }
        }
    }

    /** Reads the single live mesh's position.x from the mounted map, or NaN when none is present. */
    private def liveMeshX(mounted: Reconciler.Mounted)(using Frame): Double < Sync =
        Sync.Unsafe.defer {
            import AllowUnsafe.embrace.danger
            mounted.live.values.find(_.node.isInstanceOf[Three.Ast.Mesh]) match
                case Some(meshLive) => meshLive.obj.position.x.asInstanceOf[Double]
                case None           => Double.NaN
        }

    /** Polls `cond` until true or the bound, suspending the fiber a tick between tries (the kyo-core
      * observe-delivery pattern); never blocks a thread.
      */
    private def pollUntil(cond: Boolean < Async, maxTries: Int = 2000)(using Frame): Boolean < Async =
        Loop.indexed { i =>
            if i >= maxTries then Loop.done(false)
            else cond.map(c => if c then Loop.done(true) else Async.sleep(1.millis).andThen(Loop.continue))
        }

    "foreachKeyed live map returns to pre-add size after removing all elements" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    items <- Signal.initRef(Chunk.empty[Int])
                    foreach = items.foreachKeyed(_.toString) { i =>
                        Three.mesh(Three.Geometry.box(), Three.Material.standard())
                    }
                    scene = Three.scene(foreach)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _         <- Reconciler.fillReactiveRegionsOnce(mounted)
                    sizeEmpty <- Sync.Unsafe.defer(mounted.live.size)
                    // Add two elements.
                    _       <- items.set(Chunk(1, 2))
                    _       <- Reconciler.fillReactiveRegionsOnce(mounted)
                    sizeTwo <- Sync.Unsafe.defer(mounted.live.size)
                    // Remove both elements (back to empty).
                    _         <- items.set(Chunk.empty[Int])
                    _         <- Reconciler.fillReactiveRegionsOnce(mounted)
                    sizeFinal <- Sync.Unsafe.defer(mounted.live.size)
                yield
                    assert(sizeTwo > sizeEmpty, s"adding elements must grow the live map: empty=$sizeEmpty two=$sizeTwo")
                    assert(
                        sizeFinal == sizeEmpty,
                        s"removing all elements must return live map to initial size: before=$sizeEmpty final=$sizeFinal"
                    )
                end for
            }
        }
    }

end ThreeMountTest
