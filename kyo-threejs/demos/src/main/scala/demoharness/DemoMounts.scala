package demoharness

import demo.*
import kyo.*
import scala.scalajs.js.annotation.JSExportTopLevel

/** Client-mount entries for the pure-animation demos. Each entry builds the demo's existing scene
  * builder and runs it through `Three.runMount` IN THE BROWSER, so the per-frame `onFrame` loop
  * animates locally (no server round-trip drives the motion): the live closures mount on a real WebGL
  * canvas and the scene owns and animates itself.
  *
  * Each entry is `@JSExportTopLevel` and export-only (no main initializer), so a static page imports
  * the entry by name and calls it with the mount selector. The entries reuse the same `XScene.scene`
  * builders that the `democlient` launchers and the visual-review harness mount, so all paths render
  * the same compiled scene. Mounting runs on a detached fiber whose ambient `Scope` stays open for the page
  * lifetime (it holds the renderer, the frame loop, and every observe fiber); the loop runs until the
  * page unloads.
  *
  * The entries live outside package `kyo` because `@JSExportTopLevel` and the `Frame` macro both
  * require it: inside package `kyo` the compiler rejects `Frame` derivation.
  */
object DemoMounts:

    /** Mounts [[BouncingBallsScene]] at `selector` with the `Raf` frame loop: 24 spheres whose
      * per-ball `onFrame` integrates gravity and floor bounce, so they fall and bounce.
      */
    @JSExportTopLevel("mountBouncingBalls")
    def mountBouncingBalls(selector: String): Unit =
        runMounted {
            BouncingBallsScene.scene.map { scene =>
                Three.runMount(scene, BouncingBallsScene.camera, selector, ThreeFrames.Raf)
            }
        }

    /** Mounts [[ReactiveCubeFieldScene]] at `selector` with the `Raf` frame loop: an 8x8 grid of
      * cubes whose height and color bind to a phase signal a `Group.onFrame` advances, so a wave
      * ripples across the field.
      */
    @JSExportTopLevel("mountReactiveCubeField")
    def mountReactiveCubeField(selector: String): Unit =
        runMounted {
            ReactiveCubeFieldScene.scene.map { scene =>
                Three.runMount(scene, ReactiveCubeFieldScene.camera, selector, ThreeFrames.Raf)
            }
        }

    /** Mounts [[Snake3DScene]] at `selector` with the scene's own `ThreeFrames.Clock(150.millis)`
      * fixed-interval driver: the snake steps across the grid each tick, with keyed reconciliation
      * per body segment.
      */
    @JSExportTopLevel("mountSnake3D")
    def mountSnake3D(selector: String): Unit =
        runMounted {
            Snake3DScene.scene.map { scene =>
                Three.runMount(scene, Snake3DScene.camera, selector, Snake3DScene.frames)
            }
        }

    /** Mounts [[SolarSystemScene]] at `selector` with the `Raf` frame loop: the earth group orbits
      * the sun via `onFrame`, its rotation binding to a signal. The HUD-composing KyoApp adds a
      * selection label; the client mount renders the 3D scene alone so the orbit animates.
      */
    @JSExportTopLevel("mountSolarSystem")
    def mountSolarSystem(selector: String): Unit =
        runMounted {
            SolarSystemScene.scene.map { built =>
                val (scene, _) = built
                Three.runMount(scene, SolarSystemScene.camera, selector, ThreeFrames.Raf)
            }
        }

    /** Mounts [[GltfViewerScene]] at `selector` loading the glTF at `modelUrl`, with the `Raf` frame
      * loop: the loaded model wraps in a `Group` whose rotation advances via `onFrame`, so the model
      * spins. The model URL is an argument so the serving page can point it at a reachable fixture.
      */
    @JSExportTopLevel("mountGltfViewer")
    def mountGltfViewer(selector: String, modelUrl: String): Unit =
        runMounted {
            GltfViewerScene.scene(modelUrl).map { scene =>
                Three.runMount(scene, GltfViewerScene.camera, selector, ThreeFrames.Raf)
            }
        }

    /** Mounts the orbit-controls scene at `selector`:
      * a static (non-spinning) object plus a `Three.controls(autoRotate = true)` node, so the
      * CAMERA orbits the scene automatically. The mount binds a live `OrbitControls` over the camera and
      * canvas under the page Scope (disposed on close), and the RAF loop calls `controls.update()` each
      * frame, so the view orbits a static object (distinct from an object's own `onFrame` spin).
      */
    @JSExportTopLevel("mountControls")
    def mountControls(selector: String): Unit =
        runMounted {
            ControlsScene.scene.map { scene =>
                Three.runMount(scene, ControlsScene.camera, selector, ThreeFrames.Raf)
            }
        }

    /** Mounts the full [[EmbeddedSceneScene.ui]] kyo-ui tree (a "Focus Sun" button, the embedded 3D
      * canvas via `Three.embed`, and a HUD label) at `selector` through `UI.runMount`. The embed is
      * client-owned: kyo-ui calls the 3D host mount once the canvas is attached, running the
      * real `Three.runMount` GL pipeline inside it, so the earth's `onFrame` orbit animates LOCALLY and a
      * raycast click on the sun or earth runs its `onClick` LOCALLY, writing the shared
      * `SignalRef[String]` the HUD label observes. The button and the 3D click both drive the one shared
      * cell, proving bidirectional kyo-ui <-> 3D interop on one page with no server round-trip.
      */
    @JSExportTopLevel("mountEmbeddedScene")
    def mountEmbeddedScene(selector: String): Unit =
        runMountedUI {
            EmbeddedSceneScene.ui.map(tree => UI.runMount(tree, selector))
        }

    /** Runs a client mount on a detached fiber whose ambient `Scope` stays open for the page
      * lifetime, mirroring the page host-mount boundary: the `Scope` holds the renderer, the frame
      * loop fiber, and the observe fibers, and `Async.never` parks the fiber so the loop runs until
      * the page unloads. A mount failure (a missing canvas, no WebGL context, a glTF load error)
      * surfaces as a `Log.error` rather than an escaped throw.
      */
    private def runMounted(mount: Unit < (Async & Scope & Abort[ThreeException]))(using Frame): Unit =
        // Unsafe: the page-to-kyo boundary; launches the mount on a detached fiber whose ambient Scope
        // stays open for the frame loop. The AllowUnsafe is scoped to this entry call.
        import AllowUnsafe.embrace.danger
        val held: Unit < Async =
            Scope.run {
                Abort.run[ThreeException](mount).map {
                    case Result.Success(_) => Async.never
                    case Result.Failure(e) => Log.error(s"demo mount failed: ${e.getMessage}")
                    case Result.Panic(e) =>
                        if e.isInstanceOf[Interrupted] then Kyo.unit
                        else Log.error("demo mount panicked", e)
                }
            }
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(held).unit)
    end runMounted

    /** The kyo-ui variant of [[runMounted]] for an embed mount: `UI.runMount` carries `< (Async &
      * Scope)` (no `Abort[ThreeException]`, since the 3D host-mount failure path is handled inside the
      * kyo-ui DOM backend), so it parks the page Scope open without an `Abort.run`. A panic still logs
      * rather than escaping the boundary.
      */
    private def runMountedUI(mount: Unit < (Async & Scope))(using Frame): Unit =
        // Unsafe: the page-to-kyo boundary; launches the mount on a detached fiber whose ambient Scope
        // stays open for the frame loop. The AllowUnsafe is scoped to this entry call.
        import AllowUnsafe.embrace.danger
        val held: Unit < Async =
            Scope.run {
                Fiber.init(mount).map(_ => Async.never)
            }
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(held).unit)
    end runMountedUI

end DemoMounts
