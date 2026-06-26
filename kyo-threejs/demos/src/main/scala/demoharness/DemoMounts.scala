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

    /** Mounts the feed-driven scene at `selector`: a cube
      * spinning via client `onFrame` whose material color is bound to a server-fed mirror `SignalRef`.
      * The mount runs the real `Three.runMount` GL pipeline (so the spin animates locally) AND calls
      * `Three.Feed.connect(FeedClockScene.colorId, mirror)` under the SAME page Scope, which registers
      * the per-id inbound feed receiver on `window.__kyoHostChannels`. The server pushes
      * `HostPayload.SignalUpdate(colorId, encoded)` frames over the WebSocket; the existing kyo-ui inline
      * clientJs routes each to the receiver, which writes the mirror, and the scene's `forkBoundRef`
      * patch fiber steps the cube's color. The page raises `window.__mounted` once the entry returns.
      */
    @JSExportTopLevel("mountFeedClock")
    def mountFeedClock(selector: String): Unit =
        runMounted {
            FeedClockScene.sceneWithMirror.map { case (scene, mirror) =>
                for
                    // Connect the feed receiver FIRST (it registers on window.__kyoHostChannels and flushes
                    // any feeds buffered before mount), then run the mount: Three.runMount parks on the RAF
                    // loop and never returns, so the connect must precede it under the same page Scope.
                    _ <- Three.Feed.connect(FeedClockScene.colorId, mirror)
                    _ <- Three.runMount(scene, FeedClockScene.camera, selector, ThreeFrames.Raf)
                yield ()
            }
        }

    /** Mounts the structural feed scene at `selector`:
      * a `foreachKeyed` field of cubes whose count and arrangement are driven by a server-fed
      * `Chunk[Int]` item list. The mount runs the real `Three.runMount` GL pipeline (so the field spins
      * locally) AND calls `Three.Feed.connectChunk(FeedChunkScene.listId, mirror)` under the SAME page
      * Scope, registering the per-id inbound structural feed receiver on `window.__kyoHostChannels`. The
      * server pushes `HostPayload.SignalChunk(listId, encoded)` frames over the WebSocket; the inline
      * client routes each to the receiver, which writes the mirror, and the client's own keyed reconciler
      * diffs the snapshot: an add materializes a cube, a remove disposes one, a reorder reuses the live
      * cubes. The page raises `window.__mounted` once the entry returns.
      */
    @JSExportTopLevel("mountFeedChunk")
    def mountFeedChunk(selector: String): Unit =
        runMounted {
            FeedChunkScene.sceneWithMirror.map { case (scene, mirror) =>
                for
                    _ <- Three.Feed.connectChunk(FeedChunkScene.listId, mirror)
                    _ <- Three.runMount(scene, FeedChunkScene.camera, selector, ThreeFrames.Raf)
                yield ()
            }
        }

    /** Mounts the app-event scene at `selector`:
      * a cube whose `onClick` calls `Three.Feed.emit` to post a typed app event, and whose material color
      * is bound to a server-fed mirror the server's app-event handler updates. The mount runs the real
      * `Three.runMount` GL pipeline AND calls `Three.Feed.connect(FeedEmitScene.colorId, mirror)` under the
      * page Scope. A client click raycasts the cube locally, runs the `onClick` closure, and `emit`s the
      * bump event over the WS; the server handler advances a fed color and feeds it back, stepping the
      * cube's color. The page raises `window.__mounted` once the entry returns.
      */
    @JSExportTopLevel("mountFeedEmit")
    def mountFeedEmit(selector: String): Unit =
        runMounted {
            FeedEmitScene.sceneWithMirror.map { case (scene, mirror) =>
                for
                    _ <- Three.Feed.connect(FeedEmitScene.colorId, mirror)
                    _ <- Three.runMount(scene, FeedEmitScene.camera, selector, ThreeFrames.Raf)
                yield ()
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

    /** Mounts the flagship consolidated scene at `selector`: ONE cube
      * that shows ALL FOUR behaviors at once. The mount runs the real `Three.runMount` GL pipeline (so
      * the cube spins via client `onFrame` AND the camera orbits via the bound `OrbitControls`) AND
      * connects BOTH server-fed mirrors under the SAME page Scope: `Three.Feed.connect(colorId, color)`
      * for the auto-cycled palette color and `Three.Feed.connect(scaleId, scale)` for the click-driven
      * scale. The server pushes `SignalUpdate(colorId, ...)` on its ~1s schedule and
      * `SignalUpdate(scaleId, ...)` whenever a client click's `emit` reaches the server's app-event
      * handler; the existing `forkBoundRef` patch fibers step the cube's color and size. A client click
      * raycasts the cube locally, runs the `onClick` closure, and `emit`s the bump over the WS.
      */
    @JSExportTopLevel("mountFlagship")
    def mountFlagship(selector: String): Unit =
        runMounted {
            FlagshipScene.sceneWithMirrors.map { case (scene, color, scale) =>
                for
                    // Connect both feed receivers FIRST (they register on window.__kyoHostChannels and flush
                    // any feeds buffered before mount), then run the mount: Three.runMount parks on the RAF
                    // loop and never returns, so the connects must precede it under the same page Scope.
                    _ <- Three.Feed.connect(FlagshipScene.colorId, color)
                    _ <- Three.Feed.connect(FlagshipScene.scaleId, scale)
                    _ <- Three.runMount(scene, FlagshipScene.camera, selector, ThreeFrames.Raf)
                yield ()
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
      * lifetime, mirroring the island host-mount boundary: the `Scope` holds the renderer, the frame
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
