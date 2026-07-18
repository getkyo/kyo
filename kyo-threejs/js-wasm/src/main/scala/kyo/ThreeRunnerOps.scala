package kyo

import scala.scalajs.js

/** The client-only (js/wasm) runner and escape-hatch surface that `object Three` mixes in, so
  * `Three.runMount`, `Three.testDriver`, `Three.loadGltf`, `Three.texture`, `Three.toImage`, and
  * `Three.custom` resolve as companion MEMBERS, never top-level extensions (a top-level `runMount`
  * extension would clash with kyo-ui's `UI.runMount`). Every one binds live WebGL, so on jvm/native the
  * mixed-in trait is empty: there the surface is `Three.embed` plus the pure scene factories, and a
  * server constructs, SSRs, and drives the scene while the client island renders it.
  */
private[kyo] trait ThreeRunnerOps:

    /** Registers the client "three" backend before any mount-dispatch path looks it up by key. Called from
      * the shared `Three.embed` constructor; on js/wasm it touches the self-registering `ThreeBackend`.
      */
    private[kyo] def ensureBackendRegistered(): Unit = kyo.internal.ThreeBackend.ensureRegistered()

    /** The typed raw-three.js escape hatch: `build` produces the live object the reconciler inserts. */
    def custom[In](build: In => js.Dynamic)(input: In)(using Frame): Three.Ast.Custom[In] =
        Three.Ast.Custom(build, input, Three.Ast.MeshProps(), Chunk.empty)

    /** Mounts `scene` into the canvas at `selector` and runs the frame loop until the scope closes. */
    def runMount(
        scene: Three,
        camera: Three.Ast.Camera,
        selector: String,
        frames: ThreeFrames = ThreeFrames.Raf
    )(using Frame): Three.Mount < (Async & Scope & Abort[ThreeException]) =
        ThreeMount.runMount(scene, camera, selector, frames)

    /** A deterministic [[Three.Driver]] over the materialized scene, for a test that steps frames. */
    def testDriver(
        scene: Three.Ast.Scene,
        camera: Three.Ast.Camera
    )(using Frame): Three.Driver < (Async & Scope) =
        ThreeMount.testDriver(scene, camera)

    /** Loads a glTF/GLB at `url` into an [[Asset.Gltf]] subtree; Scope-managed, typed failure on load. */
    def loadGltf(url: String)(using Frame): Asset.Gltf < (Async & Scope & Abort[ThreeException]) =
        ThreeMount.loadGltf(url)

    /** Loads an image at `url` into a GPU [[Three.Ast.Texture]] handle; Scope-managed, typed failure. */
    def texture(url: String)(using Frame): Three.Ast.Texture < (Async & Scope & Abort[ThreeException]) =
        ThreeMount.texture(url)

    /** Renders `scene` from `camera` to a `width`x`height` PNG, returning the kyo-browser image. */
    def toImage(
        scene: Three,
        camera: Three.Ast.Camera,
        width: Int = 1280,
        height: Int = 720
    )(using Frame): kyo.internal.Image < (Async & Scope & Abort[ThreeException]) =
        ThreeMount.toImage(scene, camera, width, height)
end ThreeRunnerOps
