package kyo

import kyo.Three.foreachKeyed
import kyo.internal.ReactiveUI
import kyo.internal.Reconciler
import kyo.internal.RegionKind
import kyo.internal.ThreeBackend
import kyo.internal.ThreeFacadeOps
import scala.scalajs.js as sjs

/** Tests for [[kyo.internal.ThreeBackend]]'s path<->Live index maintenance: `buildPathIndex` builds the
  * bidirectional `data-kyo-path` <-> `Reconciler.Live` map the click producer's `pathForLive` inverse
  * reads (`Reconciler.scala`'s `Mounted.pathForLive`). These leaves prove the inverse resolution for the
  * two corners the server-side `resolveOnClick` walk (`Three.scala`) depends on: a `render`/`when`
  * boundary's CURRENT content resolving at the boundary's OWN path (no extra segment), and a nested
  * `foreach` descendant resolving at `holderPath :+ key :+ index`. Headless: `Reconciler.mount` needs no
  * GL/browser context, matching the existing `ReconcilerTest` infra.
  */
class ThreeBackendTest extends ThreeTest:

    "buildPathIndex indexes a render/when boundary's CURRENT content at the boundary's OWN path, and a nested foreach descendant at holderPath :+ key :+ index" in {
        val cond        = Signal.initConst(true)
        val content     = Three.mesh(Three.Geometry.box(), Three.Material.basic())
        val reactive    = Three.when(cond)(content)
        val items       = Signal.initConst(Chunk("a"))
        val foreachNode = items.foreachKeyed(identity)(_ => Three.group(Three.mesh(Three.Geometry.box(), Three.Material.basic())))
        val scene       = Three.scene(reactive, foreachNode)
        Scope.run {
            for
                (rootLive, mounted) <- Reconciler.mount(scene)
                _                   <- Reconciler.fillReactiveRegionsOnce(mounted)
            yield
                import AllowUnsafe.embrace.danger
                val byPath = scala.collection.mutable.Map.empty[Seq[String], Reconciler.Live]
                val byLive = scala.collection.mutable.Map.empty[Reconciler.IdentityKey, Seq[String]]
                // Mirrors ThreeBackend.mount's own wiring at the real mount call site: the embed's own
                // mount path is Seq("0") (the scene root), matching how ReactiveUI descends backendChildren.
                ThreeBackend.buildPathIndex(rootLive, Seq("0"), mounted, byPath, byLive)
                val pathForLive: Reconciler.Live => Maybe[Seq[String]] =
                    live => Maybe.fromOption(byLive.get(new Reconciler.IdentityKey(live.node)))

                val Seq(reactiveLive, foreachHolderLive) = rootLive.children.toSeq: @unchecked
                val regions                              = Reconciler.reactiveRegions(mounted)
                val reactiveRegion                       = regions.find(_.holder eq reactiveLive).get
                val foreachRegion                        = regions.find(_.holder eq foreachHolderLive).get
                val contentLive                          = reactiveRegion.prevKeyed.head._2
                val groupLive                            = foreachRegion.prevKeyed.head._2
                val descendantLive                       = groupLive.children.head

                // Render/when transparency: the boundary and its CURRENT content resolve to the SAME
                // path (no "reactive"-keyed sub-path), exactly what an empty-relPath click on render
                // content needs.
                assert(pathForLive(reactiveLive) == Present(Seq("0", "0")))
                assert(pathForLive(contentLive) == Present(Seq("0", "0")))
                assert(pathForLive(reactiveLive) == pathForLive(contentLive))

                // Nested foreach descendant: holderPath :+ key :+ index, composing the keyed splice path
                // with the index-addressed descent below it.
                assert(pathForLive(foreachHolderLive) == Present(Seq("0", "1")))
                assert(pathForLive(groupLive) == Present(Seq("0", "1", "a")))
                assert(pathForLive(descendantLive) == Present(Seq("0", "1", "a", "0")))
        }
    }

    "the render camera is Embed.backendChildren(1), so the server walk discovers its lookAt boundProp at the camera's own path (the camera SetProp wire path)" in {
        val lookRef    = Signal.initConst(Three.Vec3.zero)
        val cameraNode = Three.Camera.perspective(position = Three.Vec3(0, 0, 5)).lookAt(lookRef)
        val scene      = Three.scene(Three.mesh(Three.Geometry.box(), Three.Material.basic()))
        val embed      = Three.Ast.Embed(scene, cameraNode, ThreeFrames.Raf)
        // The camera is backendChildren(1): both the server path assignment (path :+ "1") and the client
        // index (ThreeBackend.mount's byPath.update(path :+ "1", camLive)) key off that index, so they
        // agree by construction.
        val kids = embed.backendChildren
        assert(kids.size == 2)
        assert(kids(1).asInstanceOf[AnyRef] eq cameraNode.asInstanceOf[AnyRef])
        // The embed sits at page path Seq("1"); the server walk must reach the camera's lookAt boundProp
        // at the camera's own path Seq("1","1") (before this fix backendChildren was Chunk(scene) only, so
        // the camera was never discovered).
        ReactiveUI.normalize(embed, Seq("1")).map { root =>
            def collect(n: ReactiveUI): List[(Seq[String], String)] =
                val self = n.regionKind match
                    case RegionKind.Prop(key, _) => List((n.path, key))
                    case _                       => Nil
                self ++ n.children.flatMap(collect)
            end collect
            val props = collect(root)
            assert(props.exists { case (p, k) => p == Seq("1", "1", "lookAt") && k == "lookAt" })
        }
    }

    "replaceSubtree coalesces a flood to the LATEST snapshot per path (never dropping the newest) and applies the final pushed state" in {
        val seed = Chunk(FloodItem("a", 0.0))
        Signal.initRef(seed).map { items =>
            val foreachNode = items.foreachKeyed(_.k) { it =>
                Three.mesh(Three.Geometry.box(), Three.Material.basic()).position(Three.Vec3(it.x, 0, 0))
            }
            val scene = Three.scene(foreachNode)
            Scope.run {
                for
                    (rootLive, mounted) <- Reconciler.mount(scene)
                    _                   <- Reconciler.fillReactiveRegionsOnce(mounted)
                    wakeup              <- Channel.init[Unit](1)
                    prepared <- Sync.Unsafe.defer {
                        val byPath = scala.collection.mutable.Map.empty[Seq[String], Reconciler.Live]
                        val byLive = scala.collection.mutable.Map.empty[Reconciler.IdentityKey, Seq[String]]
                        ThreeBackend.buildPathIndex(rootLive, Seq("0"), mounted, byPath, byLive)
                        val pending    = scala.collection.mutable.LinkedHashMap.empty[Seq[String], String]
                        val live       = new ThreeBackend.Live(byPath, byLive, mounted, pending, wakeup)
                        val region     = Reconciler.reactiveRegions(mounted).head
                        val holderPath = region.holderPath
                        // Flood far past any bounded queue with superseded single-key snapshots for the SAME
                        // path, then one final two-key snapshot; each op is a COMPLETE snapshot.
                        (1 to 999).foreach(i =>
                            ThreeBackend.enqueueReplace(live, holderPath, Json.encode[Chunk[FloodItem]](Chunk(FloodItem("a", i.toDouble))))
                        )
                        // The final snapshot carries FRESH keys (b, c) so their materialized positions
                        // reflect it directly (a surviving key would reuse its live object verbatim, keyed
                        // reuse), making "the newest snapshot won" observable.
                        val finalChunk = Chunk(FloodItem("b", 7.0), FloodItem("c", 9.0))
                        ThreeBackend.enqueueReplace(live, holderPath, Json.encode[Chunk[FloodItem]](finalChunk))
                        val batch = ThreeBackend.takePending(live)
                        (region, batch)
                    }
                    (region, batch) = prepared
                    // Apply the coalesced batch exactly as the drain fiber does.
                    _ <- Kyo.foreachDiscard(batch) { case (p, enc) => ThreeBackend.applyReplace(p, enc, mounted) }
                    finalState <- Sync.Unsafe.defer {
                        import AllowUnsafe.embrace.danger
                        val keys = region.prevKeyed.map(_._1)
                        val xs   = region.prevKeyed.map { case (_, l) => l.obj.position.x.asInstanceOf[Double] }
                        (keys, xs)
                    }
                yield
                    val (keys, xs) = finalState
                    // The flood collapsed to ONE op (the newest), never the stale drop-newest a bounded queue caused.
                    assert(batch.size == 1)
                    // The applied state equals the FINAL pushed snapshot, not any superseded "a@i" one.
                    assert(keys == Chunk("b", "c"))
                    assert(xs == Chunk(7.0, 9.0))
            }
        }
    }

    "replaceSubtree keeps distinct paths' latest snapshots independently, in first-enqueued order" in {
        Channel.init[Unit](1).map { wakeup =>
            import AllowUnsafe.embrace.danger
            val byPath  = scala.collection.mutable.Map.empty[Seq[String], Reconciler.Live]
            val byLive  = scala.collection.mutable.Map.empty[Reconciler.IdentityKey, Seq[String]]
            val pending = scala.collection.mutable.LinkedHashMap.empty[Seq[String], String]
            val live    = new ThreeBackend.Live(byPath, byLive, new Reconciler.Mounted, pending, wakeup)
            ThreeBackend.enqueueReplace(live, Seq("a"), "1")
            ThreeBackend.enqueueReplace(live, Seq("b"), "2")
            ThreeBackend.enqueueReplace(live, Seq("a"), "3") // supersedes path "a"; keeps insertion position
            val batch = ThreeBackend.takePending(live)
            assert(batch.map(_._1) == Seq(Seq("a"), Seq("b")))
            assert(batch.map(_._2) == Seq("3", "2"))
        }
    }

    "the 12-key bound-prop vocabulary is consistent across boundPropsOf (server), applyByKey (client apply), and extractBoundRefs (client subscribe)" in {
        val canonical = Set(
            "position",
            "rotation",
            "scale",
            "material.color",
            "material.opacity",
            "material.metalness",
            "material.roughness",
            "material.emissive",
            "color",
            "groundColor",
            "intensity",
            "lookAt"
        )
        val meshColorS = Signal.initConst(Three.Color(0xff0000))
        val emissiveS  = Signal.initConst(Three.Color(0x00ff00))
        val opacityS   = Signal.initConst(Three.Normal(0.5))
        val metalS     = Signal.initConst(Three.Normal(0.25))
        val roughS     = Signal.initConst(Three.Normal(0.75))
        val posS       = Signal.initConst(Three.Vec3(1, 2, 3))
        val rotS       = Signal.initConst(Three.Vec3(0.1, 0.2, 0.3))
        val scaleS     = Signal.initConst(Three.Vec3(2, 2, 2))
        val skyS       = Signal.initConst(Three.Color(0x0000ff))
        val groundS    = Signal.initConst(Three.Color(0xffff00))
        val intenS     = Signal.initConst(2.5)
        val lookS      = Signal.initConst(Three.Vec3.zero)
        val mesh = Three.mesh(
            Three.Geometry.box(),
            Three.Material.standard().color(meshColorS).opacity(opacityS).metalness(metalS).roughness(roughS).emissive(emissiveS)
        ).position(posS).rotation(rotS).scale(scaleS)
        val hemi       = Three.Light.hemisphere().sky(skyS).ground(groundS).intensity(intenS)
        val cameraNode = Three.Camera.perspective(position = Three.Vec3(0, 0, 5)).lookAt(lookS)

        // (a) server key-set parity: boundPropsOf over the three node shapes yields EXACTLY the 12 keys.
        val serverKeys =
            (Three.Ast.boundPropsOf(mesh) ++ Three.Ast.boundPropsOf(hemi) ++ Three.Ast.boundPropsOf(cameraNode)).map(_.key).toSet
        assert(serverKeys == canonical)

        Scope.run {
            for
                (meshRoot, meshMounted) <- Reconciler.mount(Three.scene(mesh))
                (hemiRoot, hemiMounted) <- Reconciler.mount(Three.scene(hemi))
                cam                     <- ThreeFacadeOps.makeCamera(cameraNode)
            yield
                import AllowUnsafe.embrace.danger
                val meshObj = meshRoot.children(0).obj
                val hemiObj = hemiRoot.children(0).obj

                // (b) client-apply round-trip: every key boundPropsOf emits is HANDLED by applyByKey (an
                // unhandled key would be a silent no-op, the exact drift this guards).
                ThreeBackend.applyByKey(meshObj, "position", Json.encode[Three.Vec3](Three.Vec3(1, 2, 3)))
                assert(meshObj.position.x.asInstanceOf[Double] == 1.0)
                ThreeBackend.applyByKey(meshObj, "rotation", Json.encode[Three.Vec3](Three.Vec3(0.1, 0.2, 0.3)))
                assert(math.abs(meshObj.rotation.x.asInstanceOf[Double] - 0.1) < 1e-9)
                ThreeBackend.applyByKey(meshObj, "scale", Json.encode[Three.Vec3](Three.Vec3(2, 2, 2)))
                assert(meshObj.scale.x.asInstanceOf[Double] == 2.0)
                ThreeBackend.applyByKey(meshObj, "material.color", Json.encode[Int](0xff0000))
                assert(meshObj.material.color.getHex().asInstanceOf[Int] == 0xff0000)
                ThreeBackend.applyByKey(meshObj, "material.emissive", Json.encode[Int](0x00ff00))
                assert(meshObj.material.emissive.getHex().asInstanceOf[Int] == 0x00ff00)
                ThreeBackend.applyByKey(meshObj, "material.opacity", Json.encode[Double](0.5))
                assert(meshObj.material.opacity.asInstanceOf[Double] == 0.5)
                ThreeBackend.applyByKey(meshObj, "material.metalness", Json.encode[Double](0.25))
                assert(meshObj.material.metalness.asInstanceOf[Double] == 0.25)
                ThreeBackend.applyByKey(meshObj, "material.roughness", Json.encode[Double](0.75))
                assert(meshObj.material.roughness.asInstanceOf[Double] == 0.75)
                ThreeBackend.applyByKey(hemiObj, "color", Json.encode[Int](0x0000ff))
                assert(hemiObj.color.getHex().asInstanceOf[Int] == 0x0000ff)
                ThreeBackend.applyByKey(hemiObj, "groundColor", Json.encode[Int](0xffff00))
                assert(hemiObj.groundColor.getHex().asInstanceOf[Int] == 0xffff00)
                ThreeBackend.applyByKey(hemiObj, "intensity", Json.encode[Double](2.5))
                assert(hemiObj.intensity.asInstanceOf[Double] == 2.5)
                val camBeforeY = cam.rotation.y.asInstanceOf[Double]
                // camera at (0,0,5) aimed along +X: a clean -pi/2 rotation about Y, no X/Z component.
                ThreeBackend.applyByKey(cam, "lookAt", Json.encode[Three.Vec3](Three.Vec3(5, 0, 5)))
                assert(math.abs(camBeforeY) < 1e-6)
                assert(math.abs(cam.rotation.x.asInstanceOf[Double]) < 1e-3)
                assert(math.abs(cam.rotation.z.asInstanceOf[Double]) < 1e-3)
                assert(math.abs(math.abs(cam.rotation.y.asInstanceOf[Double]) - math.Pi / 2) < 1e-3)

                // (c) client-subscribe parity: extractBoundRefs (via boundRefs) yields one triple per
                // boundPropsOf key on the same node, so the third copy cannot silently miss a key.
                assert(ThreeMount.boundRefs(meshMounted).size == Three.Ast.boundPropsOf(mesh).size)
                assert(ThreeMount.boundRefs(hemiMounted).size == Three.Ast.boundPropsOf(hemi).size)
        }
    }

    "ThreeBackend.mount contains a bare (non-Embed) Three node: it logs and returns an inert Live instead of panicking, so the page mount is not aborted" in {
        val bare: UI = Three.mesh(Three.Geometry.box(), Three.Material.basic())
        // The non-Embed arm never touches `host`, so a stub element is safe in a headless Node run; the
        // point is that mount COMPLETES with an inert Live rather than panicking (which would escape
        // DomBackend.fireHostMounts and abort the whole page mount).
        val stub = sjs.Dynamic.literal().asInstanceOf[org.scalajs.dom.Element]
        Scope.run {
            ThreeBackend.mount(bare, stub, Seq("0")).map { live =>
                assert(!live.isInstanceOf[ThreeBackend.Live])
            }
        }
    }

end ThreeBackendTest

// A minimal server-drivable element for the flood/coalesce leaf: a keyed item whose x position is the
// observable that the final applied snapshot must reflect.
final case class FloodItem(k: String, x: Double) derives Schema, CanEqual
