package kyo

import kyo.*

/** Guards that kyo-threejs's `Three.runMount` and kyo-ui's `UI.runMount` coexist. `Three.runMount`
  * is a public member of `object Three` (reachable via `import kyo.*`) and kyo-ui's `UI.runMount`
  * is a top-level extension in `package kyo` (also reachable via `import kyo.*`). Distinct
  * receiver types (`Three.type` vs `UI.type`) keep them unambiguous in the same scope.
  *
  * The first three fixtures pin the kyo-threejs side (`Three.runMount` reachable via `import kyo.*`,
  * with and without the frames argument); the fourth pins the cross-module composition:
  * `UI.runMount` and `Three.runMount` both resolve and compose in one file with a single import.
  */
class MountCompositionTest extends ThreeTest:

    "Three.runMount resolves via import kyo.* (no frames arg)" in {
        val scene                                             = Three.scene()
        val camera                                            = Three.Camera.perspective()
        val _: Unit < (Async & Scope & Abort[ThreeException]) = Three.runMount(scene, camera, "#canvas")
        succeed
    }

    "Three.runMount resolves via import kyo.* (with ThreeFrames.Raf)" in {
        val scene  = Three.scene()
        val camera = Three.Camera.perspective()
        val _: Unit < (Async & Scope & Abort[ThreeException]) =
            Three.runMount(scene, camera, "#canvas", ThreeFrames.Raf)
        succeed
    }

    "Three.runMount is a public member of object Three" in {
        // The type-ascription compiles because runMount is a regular member of object Three,
        // brought into scope by import kyo.*. Distinct receiver types (Three vs UI) keep
        // Three.runMount and UI.runMount unambiguous in the same file.
        val scene                                             = Three.scene()
        val camera                                            = Three.Camera.perspective()
        val _: Unit < (Async & Scope & Abort[ThreeException]) = Three.runMount(scene, camera, "#canvas")
        succeed
    }

    "UI.runMount and Three.runMount compose in one file with import kyo.*" in {
        // The load-bearing guard: UI.runMount (top-level UI.type extension) and
        // Three.runMount (member of object Three) both resolve with a single import kyo.*
        // and compose in one for-comprehension; distinct receiver types keep them unambiguous.
        val composed: Unit < (Async & Scope & Abort[ThreeException]) =
            for
                _ <- UI.runMount(UI.div(), "#hud")
                _ <- Three.runMount(Three.scene(), Three.Camera.perspective(), "#app")
            yield ()
        val _ = composed
        succeed
    }

    "BouncingBalls is a self-serving KyoApp" in {
        // The converted demo is an object extends KyoApp: ascribing it to KyoApp compiles only
        // because it self-serves (no Three.runMount client-only entry remains). It links
        // browser-clean on JS+Wasm as part of Test/compile.
        val app: KyoApp = demo.BouncingBalls
        assert(app eq demo.BouncingBalls)
    }

    "SolarSystem is a self-serving KyoApp composing the HUD beside the embed" in {
        val app: KyoApp = demo.SolarSystem
        assert(app eq demo.SolarSystem)
    }

    "ReactiveCubeField is a self-serving KyoApp" in {
        val app: KyoApp = demo.ReactiveCubeField
        assert(app eq demo.ReactiveCubeField)
    }

    "Snake3D is a self-serving KyoApp keeping its ThreeFrames.Clock driver" in {
        val app: KyoApp = demo.Snake3D
        // The Clock driver the converted embed threads through is the fixed-interval source.
        assert(demo.Snake3DScene.frames == ThreeFrames.Clock(150.millis))
        assert(app eq demo.Snake3D)
    }

    "GltfViewer is a self-serving KyoApp loading through the existing loadGltf path" in {
        val app: KyoApp = demo.GltfViewer
        // The scene builder carries Abort[ThreeException], absorbed by KyoApp.run's Abort[Any].
        val _: Three.Ast.Scene < (Async & Scope & Abort[ThreeException]) =
            demo.GltfViewerScene.scene(demo.GltfViewerScene.defaultModelUrl)
        assert(app eq demo.GltfViewer)
    }

    "EmbeddedScene is a self-serving KyoApp wrapping its controls + embed + HUD tree" in {
        val app: KyoApp = demo.EmbeddedScene
        // The builder companion (renamed to mirror the XScene pattern) still exposes the composed
        // ui the KyoApp serves; building it is a plain Sync value (the page tree, no server bind).
        val _: UI < Sync = demo.EmbeddedSceneScene.ui
        assert(app eq demo.EmbeddedScene)
    }

    "the island handler serves the demos bundle bytes with the right shape and head link" in {
        // The head links the island at the route the handler serves, and the handler is the
        // binary route serving those bytes (Content-Type set on the response when the bundle is
        // present; a 500 naming the link command when it is absent).
        assert(demo.DemoServe.head.moduleScript == Present(demo.DemoServe.islandPath))
        assert(demo.DemoServe.islandPath == "/_kyo/island.js")
        val _: HttpHandler[Any, "body" ~ Span[Byte], Nothing] = demo.DemoServe.islandHandler
        succeed
    }

    "ThumbnailGallery renders PNGs via toImage with no Browser in the row" in {
        val app: KyoApp = demo.ThumbnailGallery
        // The gallery render path is the headless toImage: its row is Async & Scope &
        // Abort[ThreeException], with no Browser effect (it never mounts a live scene).
        val _: kyo.internal.Image < (Async & Scope & Abort[ThreeException]) =
            Three.toImage(demo.ThumbnailGalleryScene.representative, demo.ThumbnailGalleryScene.camera, 512, 512)
        assert(app eq demo.ThumbnailGallery)
    }

    "no god dispatcher: each demo is its own KyoApp, not one shared entry" in {
        // The demos are seven distinct KyoApp singletons, each its own independent entry; there is
        // no single object that mounts or dispatches all of them.
        val apps: Seq[KyoApp] = Seq(
            demo.BouncingBalls,
            demo.SolarSystem,
            demo.ReactiveCubeField,
            demo.Snake3D,
            demo.GltfViewer,
            demo.EmbeddedScene,
            demo.ThumbnailGallery
        )
        assert(apps.distinct.length == apps.length)
        assert(apps.length == 7)
    }

end MountCompositionTest
