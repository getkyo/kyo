package demo

import kyo.*

/** A glTF model viewer: `loadGltf` with `Async` loads a model, the loaded subtree wraps in a
  * rotating `Group` driven by a `SignalRef[Double]`, and the orbit angle advances via `onFrame`
  * on that group. Pointer interaction attaches directly on `asset.root` (typed
  * `Three.Ast.Custom[js.Dynamic]`, which is `Interactive`) with no cast.
  *
  * Demonstrates asset loading, structured concurrency, reactive transforms over a loaded glTF
  * subtree, and direct pointer event attachment on `Asset.Gltf.root`.
  */
object GltfViewer extends KyoApp:
    run {
        val port = args.headMaybe.flatMap(s => Maybe.fromOption(s.toIntOption)).getOrElse(0)
        for
            scene <- GltfViewerScene.scene(GltfViewerScene.defaultModelUrl)
            ui = UI.div(Three.embed(scene, GltfViewerScene.camera).id("app"))
            handlers <- UI.runHandlers("/", DemoServe.head)(ui)
            server   <- HttpServer.init(port, "localhost")((handlers :+ DemoServe.islandHandler)*)
            _        <- Console.printLine(s"GltfViewer running on http://localhost:${server.port}/")
            _        <- server.await
        yield ()
        end for
    }
end GltfViewer

/** The scene-graph builder for [[GltfViewer]], used by the `GltfViewer` `KyoApp` to load and mount
  * the compiled scene. The model URL is a parameter so tests can point it at a served fixture while
  * the `KyoApp` uses the default.
  */
object GltfViewerScene:

    /** The model the live `KyoApp` loads by default. The asset is served separately from the demo
      * page (the demo's `HttpServer` does not bundle a model); point a static file route at this
      * path, or pass a reachable URL, to load a real model.
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
                .rotation(angle.map(a => Vec3(0, a, 0)))
                .onFrame(t => angle.updateAndGet(_ + t.delta.toMillis * 0.0005))
        yield Three.scene(
            Three.Light.ambient(intensity = 0.4),
            Three.Light.directional(position = Vec3(5, 5, 5)),
            rig
        )

    /** The viewing camera, framing the loaded model centered at the origin. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            position = Vec3(0, 0, 4),
            lookAt = Vec3.zero
        )
end GltfViewerScene
