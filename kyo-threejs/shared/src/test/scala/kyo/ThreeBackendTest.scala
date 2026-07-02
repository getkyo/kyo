package kyo

import kyo.Three.foreachKeyed
import kyo.internal.Reconciler
import kyo.internal.ThreeBackend

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

end ThreeBackendTest
