package kyo

import fixture.ServerBridgeStructuralScene
import kyo.internal.ThreeFacade
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** The client hydrate entry point for the kyo-threejs server-push STRUCTURAL bridge browser test
  * (`ThreeStructuralBridgeBrowserTest`): rebuilds the SAME `ServerBridgeStructuralScene.ui` tree
  * client-side (so `data-kyo-path` matches the server's SSR markup by construction) and hydrates it
  * onto the ALREADY-SSR'd DOM via the public `UI.runHydrate` entry, mirroring `ServerBridgeHydrate`.
  * See that file for the package-`kyo`/`Frame.internal` rationale (unchanged here).
  *
  * Two client-only counters ride alongside the hydrated scene, installed once at hydrate time:
  *   - a `BoxGeometry.prototype.dispose` patch counting every geometry disposal into
  *     `window.disposeCount` (a dropped foreach key must dispose its GL resources EXACTLY once
  *     through the real `ThreeBackend.replaceSubtree` wire path; a kept key across a splice or a pure
  *     reorder must dispose NOTHING, proving reuse). Which keys are CURRENTLY materialized is read
  *     back through `window.bridge.pixel(x, y)` (the mounted `Three.Mount`'s public surface), not a
  *     raw scene traversal: a browser test sweeps a row of canvas coordinates and checks which of the
  *     seeded colors are present.
  *   - a wrap of the mounted backend's own `replaceSubtree` JS handle (`window.__kyoBackends[root]`,
  *     `ThreeBackend.registerJsHandle`, retained SPI) counting every call into
  *     `window.replaceSubtreeCount`. A PURE reorder of an unchanged keyed set produces NO
  *     dispose-count change at all (`diffKeyed` reuses an unchanged key's live object verbatim,
  *     `Reconciler.scala:266-271`), so without this counter a browser test could not tell "the op was
  *     received and correctly produced no change" from "the op was never sent" -- the counter makes
  *     that distinction observable.
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
            scene = ServerBridgeStructuralScene.scene(items)
            embed = Three.embed(scene, ServerBridgeStructuralScene.camera).id("stage")
            tree  = UI.div(embed)
            _     <- UI.runHydrate(tree)
            mount <- awaitPresent(embed.mounted)
            _     <- Sync.defer(installReplaceSubtreeCounter())
            _     <- ThreeInspect.install("bridge", mount)
            _     <- Async.never
        yield ()

    /** Waits for `mounted` to reach `Present`, reading the CURRENT value first so a transition that
      * already happened is not missed by an unconditional `.next`.
      */
    private def awaitPresent(mounted: Signal[Maybe[Three.Mount]])(using Frame): Three.Mount < Async =
        mounted.current.map {
            case Present(m) => m
            case Absent =>
                mounted.next.map {
                    case Present(m) => m
                    case Absent     => awaitPresent(mounted)
                }
        }

    /** Wraps the mounted backend's own `replaceSubtree` handle (`window.__kyoBackends[root]`,
      * populated by `ThreeBackend.registerJsHandle` once `UI.runHydrate` above completes) so every
      * call increments `window.replaceSubtreeCount` before delegating to the original handle -- the
      * deterministic "the op was received" signal a browser test polls for, since a pure reorder is
      * otherwise observationally silent (see the class doc). The mount root here is `"0"`
      * (`Three.embed(...)` is `UI.div`'s ONLY child); read back whichever key `window.__kyoBackends`
      * actually holds rather than hardcode one, so this stays correct regardless of the tree shape.
      */
    private def installReplaceSubtreeCounter(): Unit =
        val w = dom.window.asInstanceOf[js.Dynamic]
        w.replaceSubtreeCount = 0
        val backends = w.__kyoBackends.asInstanceOf[js.Dictionary[js.Any]]
        js.Object.keys(backends.asInstanceOf[js.Object]).headOption.flatMap(backends.get).foreach { handle =>
            val h        = handle.asInstanceOf[js.Dynamic]
            val original = h.replaceSubtree
            h.replaceSubtree = js.Any.fromFunction2 { (p: js.Array[String], encoded: String) =>
                val cur = dom.window.asInstanceOf[js.Dynamic]
                cur.replaceSubtreeCount = cur.replaceSubtreeCount.asInstanceOf[Int] + 1
                original.asInstanceOf[js.Function2[js.Array[String], String, Unit]].apply(p, encoded)
            }
        }
    end installReplaceSubtreeCounter

    /** Patches `BoxGeometry.prototype.dispose` once (idempotent, guarded by a marker property) to
      * increment `window.disposeCount` before calling the original dispose. Every keyed cube's
      * geometry is a `BoxGeometry` (`ServerBridgeStructuralScene.scene`), and each is disposed by the
      * PRODUCTION `Scope.acquireRelease(create)(_.dispose())` wiring (`ThreeFacadeOps.makeGeometry`)
      * when the reconciler's per-element scope closes on a dropped foreach key -- the same real path
      * `ReconcilerTest`'s headless dispose-once leaves exercise, here driven through the real wire.
      */
    private def installDisposeCounter(): Unit =
        val proto = ThreeFacade.BoxGeometry.prototype.asInstanceOf[js.Dynamic]
        if js.isUndefined(proto.__kyoDisposePatched) then
            val w = dom.window.asInstanceOf[js.Dynamic]
            w.disposeCount = 0
            // A prototype method, not a free closure: three.js's own dispose() reads `this.uuid`/
            // dispatches `this`-bound events internally, so the wrapper (and the delegated call to the
            // ORIGINAL dispose) must carry the real receiver through as `this`, not lose it behind a
            // plain js.Function0 (which would call the original with `this` undefined and crash it).
            val original: js.ThisFunction0[js.Dynamic, js.Any] = proto.dispose.asInstanceOf[js.ThisFunction0[js.Dynamic, js.Any]]
            val wrapped: js.ThisFunction0[js.Dynamic, js.Any] = (thiz: js.Dynamic) =>
                val cur = dom.window.asInstanceOf[js.Dynamic]
                cur.disposeCount = cur.disposeCount.asInstanceOf[Int] + 1
                original.apply(thiz)
            proto.dispose = wrapped
            proto.__kyoDisposePatched = true
        end if
    end installDisposeCounter

end ServerBridgeStructuralHydrate
