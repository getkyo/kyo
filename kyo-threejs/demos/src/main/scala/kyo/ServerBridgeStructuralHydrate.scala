package kyo

import demo.ServerBridgeStructuralScene
import demo.ServerBridgeStructuralScene.Item
import kyo.internal.DomBackend
import kyo.internal.ThreeFacade
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** The client hydrate entry point for the kyo-threejs server-push STRUCTURAL bridge browser test
  * (`ThreeStructuralBridgeBrowserTest`): rebuilds the SAME
  * `ServerBridgeStructuralScene.ui` tree client-side (so `data-kyo-path` matches the server's SSR
  * markup by construction) and hydrates it onto the ALREADY-SSR'd DOM via
  * `DomBackend.hydrateBackendNodes`, mirroring `ServerBridgeHydrate`. See that file for the package-
  * `kyo`/`Frame.internal` rationale (unchanged here).
  *
  * Three client-only probes ride alongside the hydrated scene, all installed once at hydrate time:
  *   - a `BoxGeometry.prototype.dispose` patch counting every geometry disposal into
  *     `window.__disposeCount` (a dropped foreach key must dispose
  *     its GL resources EXACTLY once through the real `ThreeBackend.replaceSubtree` wire path; a
  *     kept key across a splice or a pure reorder must dispose NOTHING, proving reuse).
  *   - a per-frame sentinel that reads back every live cube's `position.x` via `scene.traverse`
  *     (the keyed cubes materialize under the `Foreach` region's own holder `Group`, not directly
  *     under the scene, so a shallow `scene.children` read would miss them) and republishes the
  *     sorted set on `window.__stagePositions`, so a browser test can read back WHICH keys are
  *     currently present with no extra client-side tagging (each `Item`'s `x` is unique by
  *     construction, `ServerBridgeStructuralScene`).
  *   - a wrap of the mounted backend's own `replaceSubtree` JS handle (`window.__kyoBackends[root]`,
  *     `ThreeBackend.registerJsHandle`) counting every call into `window.__replaceSubtreeCount`. A
  *     PURE reorder of an unchanged keyed set produces NO position/dispose-count change at all
  *     (`diffKeyed` reuses an unchanged key's live object verbatim, `Reconciler.scala:266-271`), so
  *     without this counter a browser test could not tell "the op was received and correctly
  *     produced no change" from "the op was never sent" -- the counter makes that distinction
  *     observable.
  */
object ServerBridgeStructuralHydrate:

    @JSExportTopLevel("hydrateStructuralBridge")
    def hydrateStructuralBridge(): Unit =
        given Frame = Frame.internal
        // Unsafe: the page-to-kyo boundary; runs hydration on a detached fiber whose ambient Scope
        // stays open for the mount's frame loop and observe fibers, mirroring ServerBridgeHydrate.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(Scope.run(hydrateAndWait)).unit)
    end hydrateStructuralBridge

    private def hydrateAndWait(using Frame): Unit < (Async & Scope) =
        for
            // The initial keyed set MUST match the server's SSR seed exactly (the same reasoning as
            // ServerBridgeHydrate's label/color seeds): `data-kyo-path` and the foreach's initial
            // materialize both derive from this same starting Chunk.
            items <- Signal.initRef(ServerBridgeStructuralScene.seedItems)
            _     <- Sync.defer(installDisposeCounter())
            baseScene  = ServerBridgeStructuralScene.scene(items)
            probeScene = baseScene.copy(children = baseScene.children :+ positionSentinel())
            tree       = UI.div(Three.embed(probeScene, ServerBridgeStructuralScene.camera).id("stage"))
            _ <- DomBackend.hydrateBackendNodes(tree)
            _ <- Sync.defer {
                installReplaceSubtreeCounter()
                dom.window.asInstanceOf[js.Dynamic].__bridgeReady = true
            }
            _ <- Async.never
        yield ()

    /** Wraps the mounted backend's own `replaceSubtree` handle (`window.__kyoBackends[root]`,
      * populated by `ThreeBackend.registerJsHandle` once `hydrateBackendNodes` above completes) so
      * every call increments `window.__replaceSubtreeCount` before delegating to the original handle
      * -- the deterministic "the op was received" signal a browser test polls for, since a pure
      * reorder is otherwise observationally silent (see the class doc). The mount root here is `"0"`
      * (`Three.embed(...)` is `UI.div`'s ONLY child, unlike the scalar bridge's tree where a sibling
      * label span makes it `"1"`); read back whichever key `window.__kyoBackends` actually holds
      * rather than hardcode one, so this stays correct regardless of the tree shape.
      */
    private def installReplaceSubtreeCounter(): Unit =
        val w = dom.window.asInstanceOf[js.Dynamic]
        w.__replaceSubtreeCount = 0
        val backends = w.__kyoBackends.asInstanceOf[js.Dictionary[js.Any]]
        js.Object.keys(backends.asInstanceOf[js.Object]).headOption.flatMap(backends.get).foreach { handle =>
            val h        = handle.asInstanceOf[js.Dynamic]
            val original = h.replaceSubtree
            h.replaceSubtree = js.Any.fromFunction2 { (p: js.Array[String], encoded: String) =>
                val cur = dom.window.asInstanceOf[js.Dynamic]
                cur.__replaceSubtreeCount = cur.__replaceSubtreeCount.asInstanceOf[Int] + 1
                original.asInstanceOf[js.Function2[js.Array[String], String, Unit]].apply(p, encoded)
            }
        }
    end installReplaceSubtreeCounter

    /** Patches `BoxGeometry.prototype.dispose` once (idempotent, guarded by a marker property) to
      * increment `window.__disposeCount` before calling the original dispose. Every keyed cube's
      * geometry is a `BoxGeometry` (`ServerBridgeStructuralScene.scene`), and each is disposed by the
      * PRODUCTION `Scope.acquireRelease(create)(_.dispose())` wiring (`ThreeFacadeOps.makeGeometry`)
      * when the reconciler's per-element scope closes on a dropped foreach key -- the same real path
      * `ReconcilerTest`'s headless dispose-once leaves exercise, here driven through the real wire.
      */
    private def installDisposeCounter(): Unit =
        val proto = ThreeFacade.BoxGeometry.prototype.asInstanceOf[js.Dynamic]
        if js.isUndefined(proto.__kyoDisposePatched) then
            val w = dom.window.asInstanceOf[js.Dynamic]
            w.__disposeCount = 0
            // A prototype method, not a free closure: three.js's own dispose() reads `this.uuid`/
            // dispatches `this`-bound events internally, so the wrapper (and the delegated call to the
            // ORIGINAL dispose) must carry the real receiver through as `this`, not lose it behind a
            // plain js.Function0 (which would call the original with `this` undefined and crash it).
            val original: js.ThisFunction0[js.Dynamic, js.Any] = proto.dispose.asInstanceOf[js.ThisFunction0[js.Dynamic, js.Any]]
            val wrapped: js.ThisFunction0[js.Dynamic, js.Any] = (thiz: js.Dynamic) =>
                val cur = dom.window.asInstanceOf[js.Dynamic]
                cur.__disposeCount = cur.__disposeCount.asInstanceOf[Int] + 1
                original.apply(thiz)
            proto.dispose = wrapped
            proto.__kyoDisposePatched = true
        end if
    end installDisposeCounter

    /** An invisible sentinel mesh (mirroring `ServerBridgeHydrate.pixelSentinel`'s every-frame
      * pattern) whose `onAfterRender` receives the live `scene` as its second argument and
      * `traverse`s it for every OTHER mesh's `position.x`, publishing the sorted set as a JSON array
      * on `window.__stagePositions`. The keyed cubes sit under the `Foreach` region's own holder
      * `Group`, several levels below `scene.children`, so a recursive `traverse` (not a shallow
      * child read) is required to reach them.
      */
    private def positionSentinel()(using Frame): Three =
        Three.custom { (_: Unit) =>
            val geom = js.Dynamic.newInstance(ThreeFacade.BoxGeometry)(0.01, 0.01, 0.01)
            val mat  = js.Dynamic.newInstance(ThreeFacade.MeshBasicMaterial)(js.Dynamic.literal(color = 0x000000))
            val self = js.Dynamic.newInstance(ThreeFacade.Mesh)(geom, mat)
            self.frustumCulled = false
            self.onAfterRender = js.Any.fromFunction6 {
                (_: js.Dynamic, scene: js.Dynamic, _: js.Dynamic, _: js.Dynamic, _: js.Dynamic, _: js.Dynamic) =>
                    try
                        val xs = new js.Array[Double]()
                        val visit: js.Function1[js.Dynamic, Unit] = (obj: js.Dynamic) =>
                            // A Group/Object3D holder (the Foreach region's own holder, and every ancestor
                            // traverse() visits) has `isMesh` as `undefined`, not `false`: only an actual
                            // Mesh sets it `true`. asInstanceOf[Boolean] on `undefined` throws under
                            // Scala.js's strict linker checks, so check via boolean-typeOf first.
                            if js.typeOf(obj.isMesh) == "boolean" && obj.isMesh.asInstanceOf[Boolean] && !(obj eq self) then
                                discard(xs.push(obj.position.x.asInstanceOf[Double]))
                        discard(scene.traverse(visit))
                        val sorted = xs.asInstanceOf[js.Array[Double]].sort((a, b) => if a < b then -1 else if a > b then 1 else 0)
                        dom.window.asInstanceOf[js.Dynamic].__stagePositions = js.JSON.stringify(sorted)
                    catch
                        case e: Throwable =>
                            // A test-fixture-only diagnostic: an exception here would otherwise be silently
                            // swallowed by three.js's own onAfterRender dispatch, leaving __stagePositions
                            // stuck undefined with no clue why.
                            dom.window.asInstanceOf[js.Dynamic].__stagePositionsError = e.toString
            }
            self
        }(())

end ServerBridgeStructuralHydrate
