package demo

import kyo.*

/** `Three.toImage` rendering scenes to PNGs headless: the visual-review showcase. Builds a
  * small set of scenes, renders each to a PNG via `Three.toImage` (no live mount), and writes
  * the thumbnails to disk. Demonstrates the headless render-to-image product primitive.
  *
  * `Scope.run` eliminates the inner `Scope` introduced by `Three.toImage` so the effect row
  * matches the `KyoApp` signature (`A < (Async & Scope & Abort[Any])`). The output path uses
  * `Path.writeBytes(createFolders = true)` under the hood, so `runs/thumbnails/` is created
  * automatically on first run.
  */
object ThumbnailGallery extends KyoApp:
    run {
        Scope.run {
            for
                scenes <- Sync.defer(ThumbnailGalleryScene.gallery)
                _ <- Kyo.foreach(scenes) { case (name, scene) =>
                    for
                        img <- Three.toImage(scene, ThumbnailGalleryScene.camera, 512, 512)
                        _   <- img.writeFileBinary(s"runs/thumbnails/$name.png")
                    yield ()
                }
            yield ()
        }
    }
end ThumbnailGallery

/** The scene set for [[ThumbnailGallery]], used by the `ThumbnailGallery` `KyoApp` to render each
  * scene through `Three.toImage`.
  */
object ThumbnailGalleryScene:

    /** The render camera the gallery captures each scene from, aimed at the origin. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(
            position = Three.Vec3(0, 0, 4),
            lookAt = Three.Vec3.zero
        )

    /** The named scenes the gallery renders to PNGs. */
    def gallery(using Frame): Chunk[(String, Three)] =
        Chunk(
            "red-cube" -> Three.scene(
                Three.Light.ambient(),
                Three.mesh(Three.Geometry.box(), Three.Material.standard(color = Three.Color.red))
            ),
            "blue-ball" -> Three.scene(
                Three.Light.directional(),
                Three.mesh(Three.Geometry.sphere(), Three.Material.standard(color = Three.Color.blue))
            ),
            "torus" -> Three.scene(
                Three.Light.ambient(),
                Three.Light.point(),
                Three.mesh(Three.Geometry.torus(), Three.Material.standard(color = Three.Color.magenta))
            )
        )

    /** A single representative scene the headless `toImage` path renders for the harness. */
    def representative(using Frame): Three =
        Three.scene(
            Three.Light.ambient(),
            Three.Light.point(),
            Three.mesh(Three.Geometry.torus(), Three.Material.standard(color = Three.Color.magenta))
        )
end ThumbnailGalleryScene
