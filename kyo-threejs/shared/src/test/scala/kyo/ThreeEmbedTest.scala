package kyo

import kyo.*
import kyo.internal.HtmlRenderer
import kyo.internal.Reconciler
import kyo.internal.ThreeFacadeOps
import scala.scalajs.js as sjs

/** Guards the `Three.embed` adapter: compile-resolution, render-string, and lifecycle assertions;
  * no live GL.
  *
  * Tests pin that `Three.embed` returns a `UI.Ast.Host` accepted by `UI.div`, that the shared
  * renderer emits `<canvas data-kyo-path>` for an embedded host, and that the `frames` default
  * and override both compile. The pipeline-returns test (leaf 4) guards the mount-returns
  * contract: the frame loop must run in a forked fiber so the mount effect itself returns and the
  * kyo-ui bootstrap can proceed.
  *
  * Node-environment constraint on leaf 4: calling `Three.embed(...).mount.run(element)` is
  * infeasible in Node because `makeRenderer` calls `new THREE.WebGLRenderer(...)` which throws
  * `"document is not defined"` before any canvas stub reaches the GL context check. The mount
  * would panic at setup, not hang at the loop, so a Node invocation of the real embed mount cannot
  * distinguish a foreground loop from a forked loop. Leaf 4 therefore tests the same pipeline
  * structure (minus makeRenderer and setupPointerDelegation) with a stub renderer. The definitive
  * real-surface guard (the actual embed mount returns, a second sibling host fires, the kyo-ui
  * reactive bootstrap stays live) is `ThreeEmbedBrowserTest`, which drives the embed path
  * end-to-end in a real Chrome with a live GL context.
  */
class ThreeEmbedTest extends ThreeTest:

    "Three.embed returns a UI.Ast.Host usable as a UI.div child" in {
        val embedded                  = Three.embed(Three.scene(), Three.Camera.perspective())
        val _: UI.Ast.Host            = embedded
        val tree                      = UI.div(UI.span("controls"), embedded, UI.span("hud"))
        val _: Unit < (Async & Scope) = UI.runMount(tree, "#app")
        succeed
    }

    "the embedded host renders <canvas data-kyo-path> on the shared renderer" in {
        val tree = UI.div(Three.embed(Three.scene(), Three.Camera.perspective()))
        for html <- HtmlRenderer.render(tree, Seq.empty)
        yield assert(html.contains("<canvas data-kyo-path=\"0\"></canvas>"))
        end for
    }

    "Three.embed defaults frames to ThreeFrames.Raf and accepts an explicit ThreeFrames" in {
        val a: UI.Ast.Host = Three.embed(Three.scene(), Three.Camera.perspective())
        val b: UI.Ast.Host = Three.embed(Three.scene(), Three.Camera.perspective(), ThreeFrames.Clock(16.millis))
        val _              = a
        val _              = b
        succeed
    }

    // Path (b) guard: tests the pipeline structure that embed uses, with a stub renderer.
    // Cannot drive Three.embed directly in Node because makeRenderer throws before the frame
    // loop runs (see class-level scaladoc). ThreeEmbedBrowserTest covers the full surface.
    "embed pipeline: forked runLoop lets the enclosing pipeline return" in {
        // This test guards the mount-returns lifecycle contract. The key structure under test:
        // the runLoop step must be wrapped in Fiber.init so the pipeline returns immediately
        // instead of blocking on the infinite Raf/Clock loop.
        //
        // A ThreeFrames.Manual driver that parks on Async.never simulates the Raf/Clock loop that
        // runs until the page Scope closes. With a forked structure the pipeline sets
        // mountReturned and the Scope.run completes; with a foreground runLoop the pipeline
        // would never set mountReturned and the test would time out.
        //
        // The test cannot call Three.embed directly in Node: makeRenderer invokes
        // new THREE.WebGLRenderer(...) which throws "document is not defined" before the frame
        // loop is ever reached, so the foreground-vs-forked distinction is not exercisable
        // in Node without a real GL context. ThreeEmbedBrowserTest is the definitive
        // guard for the full embed surface.
        val scene  = Three.scene(Three.mesh(Three.Geometry.box(), Three.Material.standard()))
        val camera = Three.Camera.perspective()
        // A stub renderer: accepts renderer.render(scene, cam) calls without any GL context.
        val stubRenderer  = sjs.Dynamic.literal(render = (_: sjs.Dynamic, _: sjs.Dynamic) => ())
        var mountReturned = false
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (rootLive, mounted) = mountResult
                    cam <- ThreeFacadeOps.makeCamera(camera)
                    _   <- ThreeMount.subscribeRegions(mounted)
                    _   <- ThreeMount.subscribeReactiveRegions(mounted)
                    // Fork the loop with a driver that parks indefinitely (the Raf/Clock analog).
                    // A foreground runLoop would block here; a forked one returns immediately.
                    _ <- Fiber.init {
                        Abort.run[ThreeException](
                            ThreeMount.runLoop(
                                mounted,
                                rootLive,
                                cam,
                                stubRenderer,
                                ThreeFrames.Manual(_ => Async.never)
                            )
                        ).map {
                            case Result.Success(_) => (): Unit < Sync
                            case Result.Failure(e) => Log.error(s"frame loop failed: ${e.getMessage}")
                            case Result.Panic(e) =>
                                if e.isInstanceOf[Interrupted] then (): Unit < Sync
                                else Log.error("frame loop panicked", e)
                        }
                    }.unit
                    _ <- Sync.defer { mountReturned = true }
                yield ()
            }
        }.map { _ =>
            assert(mountReturned, "the pipeline must return after forking the loop fiber; a foreground loop would block here")
        }
    }

end ThreeEmbedTest
