package fixture

import kyo.*

/** The live-mount HUD on a SERVER-DRIVEN page: the shape a real app takes when a server renders the
  * page (`UI.runHandlers`) and the browser brings the embedded canvas to life (`UI.runHydrate`).
  *
  * The HUD reads state that exists ONLY in the browser: a frame counter fed by `Three.Mount.renders`,
  * and a Capture button that reads the live framebuffer through `Three.Mount.readPixels`. Neither can
  * come from the server session, which has no WebGL context and so never holds a `Three.Mount`. The HUD
  * is therefore marked client-owned, so the browser subscribes its regions and runs its click handler
  * while the rest of the page stays server-driven.
  *
  * `page` is what the server renders and `ui` is what the client hydrates. Both build the same
  * `pageTree`, so `data-kyo-path` agrees by construction; `ui` additionally forks the observer that
  * advances the frame counter once the mount attaches.
  */
object MountHudScene:

    /** The viewing camera, framing the cube head-on. */
    def camera(using Frame): Three.Ast.Camera =
        Three.Camera.perspective(position = Three.Vec3(0, 0, 4), lookAt = Three.Vec3.zero)

    /** One unlit cube filling the frame: an unlit surface's rendered pixel IS its color, so a captured
      * centre pixel asserts an exact value with no lighting interaction.
      */
    def scene(using Frame): Three.Ast.Scene =
        Three.scene(Three.mesh(Three.Geometry.box(2.0, 2.0, 2.0), Three.Material.basic().color(Three.Color.red)))

    /** The client-owned HUD: a frame-count readout and a capture button, both reading the live mount. */
    private def hud(embedded: Three.Embedded, framesRef: SignalRef[String], captureRef: SignalRef[String])(using Frame): UI =
        UI.div(
            UI.p(framesRef.map(n => s"Frames rendered: $n")).id("frame-count"),
            UI.button("Capture").id("capture").onClick(capture(embedded, captureRef)),
            UI.p(captureRef).id("capture-result")
        ).id("mount-hud").clientOwned

    /** The full tree plus the embed handle the client-side builder observes.
      *
      * Frames come from a clock rather than `ThreeFrames.Raf` (the default a real app uses). The HUD rewrites
      * its frame count on every committed frame, so at RAF rates the page mutates the DOM ~60 times a second
      * and never goes quiet, and every `Browser` gate that waits for a settled DOM (a click, an assertion)
      * would time out on a page that is working exactly as intended. A clock-driven frame source commits
      * frames the same way through the same mount pipeline, and leaves a quiet gap between them. What is
      * under test is who OWNS the region, not what ticks it.
      */
    private def pageTree(framesRef: SignalRef[String], captureRef: SignalRef[String])(using Frame): (UI, Three.Embedded) =
        val embedded = Three.embed(scene, camera, ThreeFrames.Clock(150.millis)).id("stage")
        (UI.div(hud(embedded, framesRef, captureRef), embedded).id("hud-page"), embedded)
    end pageTree

    /** The server's page builder: the tree, with no live-mount observer (a `Three.Mount` never attaches
      * server-side, so `mounted` stays `Absent` here and the HUD renders its initial state).
      */
    def page(using Frame): UI < Sync =
        for
            framesRef  <- Signal.initRef("0")
            captureRef <- Signal.initRef("Not captured yet.")
        yield pageTree(framesRef, captureRef)._1

    /** The client's hydrate builder: the SAME tree, plus the observer that feeds the frame counter from
      * the mount's `renders` signal once the canvas attaches.
      */
    def ui(using Frame): UI < (Async & Scope) =
        for
            framesRef  <- Signal.initRef("0")
            captureRef <- Signal.initRef("Not captured yet.")
            (tree, embedded) = pageTree(framesRef, captureRef)
            _ <- Fiber.init(
                embedded.mounted.observe {
                    case Present(m) => Fiber.init(m.renders.observe(n => framesRef.set(n.toString))).unit
                    case Absent     => Kyo.unit
                }
            )
        yield tree

    /** Reads the live mount's pixels and reports a short summary; `Abort[ThreeException]` is handled as a
      * typed `Result`, never an exception. `Absent` reports the mount was never seen, which is what a
      * server-resolved click would report.
      */
    private def capture(embedded: Three.Embedded, captureRef: SignalRef[String])(using Frame): Unit < Async =
        embedded.mounted.current.map {
            case Absent => captureRef.set("Not mounted yet.")
            case Present(m) =>
                val width  = m.width
                val height = m.height
                Abort.run[ThreeException](m.readPixels(0, 0, width, height)).map {
                    case Result.Success(pixels) => captureRef.set(s"Captured ${pixels.size / 4} pixels.")
                    case Result.Failure(e)      => captureRef.set(s"Capture failed: ${e.getMessage}")
                    case Result.Panic(e)        => captureRef.set(s"Capture failed: ${e.getMessage}")
                }
        }

end MountHudScene
