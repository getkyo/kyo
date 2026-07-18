package fixture

import kyo.*

/** The shared scene+page builder for the server-push bridge browser tests (the scalar prop
  * reactivity proof, and its structural sibling): a DOM label bound to a
  * server `Signal[String]` alongside an embedded cube whose `material.color` binds to a server
  * `Signal[Three.Color]`. ONE function so the server (`UI.runHandlers`) and the client hydrate
  * island rebuild the IDENTICAL tree from the SAME shared `SignalRef`s, so `data-kyo-path` agrees by
  * construction and a later server `.set(...)` patches the client's live scene through the
  * ordinary `boundProps`/`PropRegion` wire path, no bespoke feed mechanism.
  */
object ServerBridgeScene:

    /** The viewing camera, framing the cube head-on. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(position = Three.Vec3(0, 0, 4), lookAt = Three.Vec3.zero)

    /** The scene: one cube filling the frame, its material color bound to `color`. The material is
      * `basic` (unlit) deliberately, not `standard`: an unlit surface's rendered pixel IS its color
      * with no lighting interaction, so a browser test's pixel sampler can assert an EXACT color
      * match rather than a lit, angle- and light-dependent shade. Exposed separately from `ui` so a
      * browser test's client-side mount can append an invisible pixel-sampling sentinel as an EXTRA
      * trailing child (mirroring `DemoHarness`'s own `captureSentinel` pattern) without touching the
      * cube's own index-0 position, so its `material.color` boundProp path is unaffected.
      */
    def scene(color: Signal[Three.Color])(using Frame): Three.Ast.Scene =
        Three.scene(Three.mesh(Three.Geometry.box(2.0, 2.0, 2.0), Three.Material.basic().color(color)))

    /** Builds the page body: a labeled DOM span bound to `label`, and an embedded cube (via
      * `scene`) whose material color binds to `color`. Both signals are server-owned `SignalRef`s
      * the test drives directly via `.set(...)`; the framework's ordinary bound-prop discovery
      * pushes each change to the connected client over the ONE `/_kyo/ws` socket.
      */
    def ui(label: Signal[String], color: Signal[Three.Color])(using Frame): UI =
        UI.div(
            label.map(v => UI.span(v).id("label")),
            Three.embed(scene(color), camera).id("stage")
        )
    end ui

    /** The scale that widens the cube across the whole canvas. X and Y only: the box is 2 deep and the
      * camera sits at z=4, so scaling z as well would push the front face through the camera.
      */
    val wideScale: Three.Vec3 = Three.Vec3(5, 5, 1)

    /** The pre-registration variant of [[scene]]: the same unlit cube, with a SECOND bound prop, `scale`,
      * alongside `material.color`.
      *
      * Two bound props, because the client's startup buffer holds ONE SLOT PER BOUND PROP, and a second op
      * for a slot replaces the first (only the newest value of a prop means anything). One bound prop
      * therefore cannot show that more than one buffered op survives the flush, however many times it is
      * driven: all of its ops collapse into its single slot. Two distinct props give the flush two distinct
      * slots, so it has something to lose.
      *
      * `scale` is the second prop because it is separately visible from `color` in the rendered frame. At
      * scale 1 the cube covers only the middle of the canvas (a 2-unit box at z=1, seen from z=4 through a
      * 75-degree vertical fov, spans about 43% of the half-height), so a pixel near the top edge reads the
      * clear color; at [[wideScale]] the cube covers that pixel too. So one pixel sees `color` and another
      * sees `scale`, and neither can stand in for the other.
      */
    def preRegisterScene(color: Signal[Three.Color], scale: Signal[Three.Vec3])(using Frame): Three.Ast.Scene =
        Three.scene(
            Three.mesh(Three.Geometry.box(2.0, 2.0, 2.0), Three.Material.basic().color(color)).scale(scale)
        )

    /** The page body for the pre-registration test: [[ui]]'s shape over [[preRegisterScene]], so the SSR
      * page and the hydrating client build the IDENTICAL tree and `data-kyo-path` agrees by construction.
      */
    def preRegisterUi(label: Signal[String], color: Signal[Three.Color], scale: Signal[Three.Vec3])(using Frame): UI =
        UI.div(
            label.map(v => UI.span(v).id("label")),
            Three.embed(preRegisterScene(color, scale), camera).id("stage")
        )
    end preRegisterUi

end ServerBridgeScene
