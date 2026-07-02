package demo

import kyo.*

/** The scene-graph builder for a glTF model viewer: `loadGltf` with `Async` loads a model, the loaded
  * subtree wraps in a rotating `Group` driven by a `SignalRef[Double]`, and the orbit angle advances
  * via `onFrame` on that group. Pointer interaction attaches directly on `asset.root` (typed
  * `Three.Ast.Custom[js.Dynamic]`, which is `Interactive`) with no cast.
  *
  * Demonstrates asset loading, structured concurrency, reactive transforms over a loaded glTF subtree,
  * and direct pointer event attachment on `Asset.Gltf.root`. The model URL is a parameter so tests can
  * point it at a served fixture. The client mount in `demoharness.DemoMounts` runs this scene through
  * `Three.runMount` in the browser.
  */
object GltfViewerScene:

    /** The model the client mount loads by default. The asset is served separately from the demo page;
      * point a static file route at this path, or pass a reachable URL, to load a real model.
      */
    val defaultModelUrl: String = "/models/helmet.glb"

    /** Loads the glTF at `url`, wraps the loaded subtree in a rotating `Group` driven by a
      * `SignalRef[Double]`, and attaches pointer handlers on the loaded `Custom` root.
      */
    def scene(url: String)(using Frame): Three.Ast.Scene < (Async & Scope & Abort[ThreeException]) =
        for
            asset <- Three.loadGltf(url)
            angle <- Signal.initRef(0.0)
            root = asset.root
                .onPointerOver(_ => Log.info("pointer over model"))
                .onClick(_ => Log.info("clicked model"))
            rig = Three.group(root)
                .rotation(angle.map(a => Three.Vec3(0, a, 0)))
                .onFrame(t => angle.updateAndGet(_ + t.delta.toMillis * 0.0005))
        yield Three.scene(
            Three.Light.ambient(intensity = 0.4),
            Three.Light.directional(position = Three.Vec3(5, 5, 5)),
            rig
        )

    /** The viewing camera, framing the loaded model centered at the origin. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            position = Three.Vec3(0, 0, 4),
            lookAt = Three.Vec3.zero
        )
end GltfViewerScene
