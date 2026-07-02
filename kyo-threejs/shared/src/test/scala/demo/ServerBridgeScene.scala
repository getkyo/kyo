package demo

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

end ServerBridgeScene
