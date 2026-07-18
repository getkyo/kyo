package fixture

import kyo.*

/** The shared scene+camera+page builder for the server-push CAMERA bridge browser test (the
  * server-bound `lookAt` re-aim proof): two unlit planes fixed in world space, viewed by a
  * perspective camera whose `lookAt` binds to a server `Signal[Three.Vec3]`. ONE set of builders so
  * the server (`UI.runHandlers`) and the client hydrate island rebuild the IDENTICAL tree from the
  * SAME shared `SignalRef`, so `data-kyo-path` agrees by construction and a later server `.set(...)`
  * re-aims the client's live camera through the ordinary `boundProps`/`PropRegion` wire path
  * (`SetProp`), no bespoke feed mechanism.
  *
  * Aiming the camera at a plane's center projects that plane to the exact frame center, so a browser
  * test's sampled center pixel IS that plane's color; a purely horizontal re-aim from `redTarget` to
  * `greenTarget` swaps which plane is centered. The planes are `basic` (unlit) so the rendered pixel
  * is the exact material color with no lighting interaction, and they sit far enough apart that
  * neither plane, nor the origin between them, ever covers the other's aim center.
  */
object ServerBridgeCameraScene:

    /** The RED plane's center: the initial camera aim, so the initial center pixel is red. */
    val redTarget: Three.Vec3 = Three.Vec3(-1.5, 0, 0)

    /** The GREEN plane's center: the server-driven re-aim target, so a re-aim here centers green. */
    val greenTarget: Three.Vec3 = Three.Vec3(1.5, 0, 0)

    /** The viewing camera, head-on at z=6 with its `lookAt` bound to `target`. A server `.set(...)`
      * re-aims the live camera on the client through the `lookAt` bound-prop wire path, swapping which
      * plane the center pixel samples.
      */
    def camera(target: Signal[Three.Vec3])(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(position = Three.Vec3(0, 0, 6)).lookAt(target)

    /** The scene: two unlit planes offset symmetrically about the origin with a gap, RED at
      * `redTarget` and GREEN at `greenTarget`. Each plane is `basic` (unlit) deliberately, not
      * `standard`: an unlit surface's rendered pixel IS its color with no lighting interaction, so a
      * browser test's pixel sampler asserts an EXACT color match rather than a lit, angle- and
      * light-dependent shade. The planes are static; only the camera's `lookAt` is reactive, so
      * `scene` takes no signal (mirroring `ServerBridgeScene.scene`, whose reactive signal drove the
      * material instead). Exposed separately from `ui` so a browser test's client-side mount can append
      * an invisible pixel-sampling sentinel as an EXTRA trailing child without shifting either plane's
      * index.
      */
    def scene(using Frame): Three.Ast.Scene =
        Three.scene(
            Three.mesh(Three.Geometry.plane(2.0, 2.0), Three.Material.basic().color(Three.Color.red)).position(redTarget),
            Three.mesh(Three.Geometry.plane(2.0, 2.0), Three.Material.basic().color(Three.Color.green)).position(greenTarget)
        )

    /** Builds the page body: the two planes (via `scene`) viewed by the `lookAt`-bound camera (via
      * `camera`). `target` is the server-owned `SignalRef` the test drives directly via `.set(...)`;
      * the framework's ordinary bound-prop discovery pushes each re-aim to the connected client over
      * the ONE `/_kyo/ws` socket.
      */
    def ui(target: Signal[Three.Vec3])(using Frame): UI =
        UI.div(Three.embed(scene, camera(target)).id("stage"))

end ServerBridgeCameraScene
