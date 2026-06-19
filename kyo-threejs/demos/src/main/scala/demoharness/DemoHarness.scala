package demoharness

import demo.*
import kyo.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel
import scala.scalajs.js.annotation.JSImport

/** The browser entry points for the kyo-threejs behavioral browser tests.
  *
  * Each `@JSExportTopLevel` probe is linked as an ES module export and called from a harness page:
  * it builds a scene or kyo-ui tree, mounts it through the real kyo runtime, and records a
  * page-visible result the test reads back. This drives the ACTUAL compiled reconciler, frame loop,
  * raycast wiring, renderer teardown, and `Three.embed` host mount inside a real browser WebGL
  * context; nothing is replicated in hand-written JavaScript.
  *
  * The entries live outside package `kyo` because `@JSExportTopLevel` and the `Frame` macro both
  * require it: inside package `kyo` the compiler rejects `Frame` derivation.
  */
object DemoHarness:

    /** Mounts a one-frame ordering probe through the real `Three.runMount` and records what the FIRST
      * rendered frame actually drew at the canvas center.
      *
      * The probe's box fills the canvas center and is always rendered; it is built with a black material
      * (dark against the black background) and its `onFrame` sets the material color bright directly on
      * the live object. The mesh's three.js `onAfterRender` fires inside `renderer.render`, right after
      * the box is drawn while the GL framebuffer is still live, and (once) reads the center pixel via
      * `gl.readPixels`, recording its brightness into `window.__orderingCenterLit` and raising
      * `window.__orderingReady`. A loop that applies the tick's `onFrame` before its render submit draws
      * the bright box on the first frame (center lit); a loop that renders before applying `onFrame`
      * draws the still-black box (center dark).
      */
    @JSExportTopLevel("mountOrderingProbe")
    def mountOrderingProbe(selector: String): Unit =
        // Unsafe: the page-to-kyo boundary, mirroring `mountDemo`: launches the probe on a detached fiber
        // whose ambient Scope stays open for the frame loop; the AllowUnsafe is scoped to this entry call.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(
            Fiber.initUnscoped(Scope.run(Abort.run[ThreeException](orderingProbe(selector)))).unit
        )
    end mountOrderingProbe

    private def orderingProbe(selector: String)(using Frame): Unit < (Async & Scope & Abort[ThreeException]) =
        // The probe holds the live mesh its onFrame mutates directly: a single reference set once
        // by the custom build and read by the onFrame, both on the mount fiber.
        val liveBox = new scala.scalajs.js.Object().asInstanceOf[js.Dynamic]
        val box = Three.custom[js.Dynamic] { holder =>
            val geom = js.Dynamic.newInstance(ThreeLite.BoxGeometry)(6, 6, 6)
            // An unlit black material so the box renders every frame (onAfterRender always fires) yet
            // contributes a dark center pixel until the tick's onFrame brightens its color.
            val mat  = js.Dynamic.newInstance(ThreeLite.MeshBasicMaterial)(js.Dynamic.literal(color = 0x000000))
            val mesh = js.Dynamic.newInstance(ThreeLite.Mesh)(geom, mat)
            mesh.onAfterRender = readCenterOnce
            holder.mesh = mesh
            mesh
        }(liveBox)
            .onFrame { _ =>
                // Unsafe: brighten the live material on the frame; a direct mutation the render submit of
                // the same tick must observe.
                Sync.Unsafe.defer {
                    val mesh = liveBox.mesh.asInstanceOf[js.Dynamic]
                    val _    = mesh.material.color.set(0x33ff66.toDouble)
                }
            }
        val probe = Three.scene(box)
        Three.runMount(probe, Three.Camera.perspective(position = Vec3(0, 0, 6)), selector, ThreeFrames.Raf)
    end orderingProbe

    /** Drives the PRODUCTION `ThreeMount.makeRenderer` acquire/release through a `Scope` and reports
      * whether the WebGL context was released on scope close. The renderer is acquired and its live GL
      * context captured inside the scope; after the surrounding `Scope.run` closes, the production
      * release (`renderer.dispose()` + `forceContextLoss()`) has run, so the captured context reports
      * `isContextLost()`. A teardown that did not release the context would report `false`.
      */
    @JSExportTopLevel("mountRendererReleaseProbe")
    def mountRendererReleaseProbe(selector: String): Unit =
        // Unsafe: the page-to-kyo boundary, mirroring mountDemo: runs the probe on a detached fiber; the
        // AllowUnsafe is scoped to this entry call.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(rendererReleaseProbe(selector)).unit)
    end mountRendererReleaseProbe

    /** Mounts a pure kyo-ui tree containing a `UI.host("div")` through `UI.runMount`, driving the
      * `DomBackend.fireHostMounts` seam on a real element. The mount callback increments
      * `window.__hostMountCount` and sets `data-mounted="1"` on the host element; a sibling
      * reactive span tracks a `SignalRef[Int]` driven externally via `window.__setHostCount`.
      * Raises `window.__hostReady` when the mount callback has fired.
      */
    @JSExportTopLevel("mountHostProbe")
    def mountHostProbe(): Unit =
        // Unsafe: the page-to-kyo boundary, mirroring mountRendererReleaseProbe; runs the probe on a
        // detached fiber and the AllowUnsafe is scoped to this entry call.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(hostProbe()).unit)
    end mountHostProbe

    private def hostProbe()(using Frame): Unit < Async =
        Scope.run {
            for
                counter <- Signal.initRef(0)
                tree =
                    UI.div(
                        UI.host("div") { el =>
                            Sync.defer {
                                val w = dom.window.asInstanceOf[js.Dynamic]
                                val prev =
                                    if js.isUndefined(w.__hostMountCount) then 0
                                    else w.__hostMountCount.asInstanceOf[Int]
                                w.__hostMountCount = prev + 1
                                el.setAttribute("data-mounted", "1")
                                // Expose a setter so the browser test can drive sibling re-renders without a
                                // button, and observe that the host element identity is unchanged after each.
                                w.__setHostCount = js.Any.fromFunction1 { (n: Int) =>
                                    import AllowUnsafe.embrace.danger
                                    val _ = Sync.Unsafe.evalOrThrow(counter.set(n).unit)
                                }
                                w.__hostReady = true
                            }
                        }.id("host-stage"),
                        counter.map(n => UI.span(n.toString).id("host-count"))
                    )
                _ <- Fiber.init(UI.runMount(tree))
                _ <- Async.never
            yield ()
        }
    end hostProbe

    /** Mounts the embedded-3D kyo-ui tree through `UI.runMount` into the page, so kyo-ui's
      * `DomBackend.fireHostMounts` fires the `Three.embed` host's mount on the live canvas, holds
      * it for one rendered frame, then closes the page Scope and records whether the embedded WebGL
      * context was released. A teardown that leaked the context reports false; a frame that never
      * rendered reports the render flag false. Also increments `window.__embedMountCount` on the
      * first frame, so leaf 3 can assert exactly one mount.
      */
    @JSExportTopLevel("mountEmbedProbe")
    def mountEmbedProbe(): Unit =
        // Unsafe: the page-to-kyo boundary, mirroring mountRendererReleaseProbe; runs the probe on a
        // detached fiber and the AllowUnsafe is scoped to this entry call.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(embedProbe()).unit)
    end mountEmbedProbe

    private def embedProbe()(using Frame): Unit < Async =
        // A holder for the live GL context captured inside the scope so it can be read after close.
        val glHolder = new scala.scalajs.js.Object().asInstanceOf[js.Dynamic]
        Scope.run {
            for
                built <- EmbeddedScene.scene
                (scene, _) = built
                probeScene = scene.copy(children = scene.children :+ captureSentinel(glHolder))
                tree       = UI.div(Three.embed(probeScene, EmbeddedScene.camera).id("embed-stage"))
                _ <- Fiber.init(UI.runMount(tree))
                _ <- untilEmbedFrame
            yield ()
        }.andThen {
            // Side effect: after the page Scope closed, record the captured context's lost state and
            // the pixel count that was read while the GL context was still alive (inside onAfterRender).
            Sync.Unsafe.defer {
                val w  = dom.window.asInstanceOf[js.Dynamic]
                val gl = glHolder.gl
                val lost =
                    if js.isUndefined(gl) || gl == null then true
                    else gl.isContextLost().asInstanceOf[Boolean]
                val pixelCount =
                    if js.isUndefined(glHolder.pixelCount) then 0
                    else glHolder.pixelCount.asInstanceOf[Int]
                w.__embedContextLost = String.valueOf(lost)
                w.__embedPixelCount = pixelCount
                w.__embedReady = true
            }
        }
    end embedProbe

    /** A sentinel Mesh that fires `onAfterRender` on the first rendered frame: captures the live GL
      * context (so the probe can read its lost-state after Scope close), reads the full framebuffer
      * pixel count while it is valid, stores both in `glHolder`, and raises the embed render flag and
      * mount count. Using `onAfterRender` on a Mesh (inside `renderer.render`) guarantees the
      * framebuffer is still live when `readPixels` runs, so the pixel count reflects actual scene
      * content rather than a cleared or undefined buffer. A Group cannot be used here because
      * three.js only calls `onAfterRender` on rendered objects (Mesh, Points, Line), not on Groups.
      */
    private def captureSentinel(glHolder: js.Dynamic)(using Frame): Three =
        Three.custom { (_: Unit) =>
            val geom = js.Dynamic.newInstance(ThreeLite.BoxGeometry)(0.1, 0.1, 0.1)
            val mat  = js.Dynamic.newInstance(ThreeLite.MeshBasicMaterial)(js.Dynamic.literal(color = 0x000000))
            val mesh = js.Dynamic.newInstance(ThreeLite.Mesh)(geom, mat)
            mesh.frustumCulled = false
            mesh.onAfterRender = js.Any.fromFunction6 {
                (renderer: js.Dynamic, _: js.Dynamic, _: js.Dynamic, _: js.Dynamic, _: js.Dynamic, _: js.Dynamic) =>
                    // Side effect: one-shot pixel readback and context capture on the first rendered
                    // frame, while the GL framebuffer is still valid inside renderer.render.
                    val w = dom.window.asInstanceOf[js.Dynamic]
                    if js.isUndefined(w.__embedFrame) || !w.__embedFrame.asInstanceOf[Boolean] then
                        val gl = renderer.getContext()
                        glHolder.gl = gl
                        val canvas        = renderer.domElement
                        val width         = canvas.width.asInstanceOf[Int]
                        val height        = canvas.height.asInstanceOf[Int]
                        val buf           = new scala.scalajs.js.typedarray.Uint8Array(width * height * 4)
                        val _             = gl.readPixels(0, 0, width, height, gl.RGBA, gl.UNSIGNED_BYTE, buf)
                        var distinctCount = 0
                        val seen          = new scala.scalajs.js.Array[Int]()
                        var i             = 0
                        while i < buf.length do
                            val packed =
                                ((buf(i).toInt & 0xff) << 24) |
                                    ((buf(i + 1).toInt & 0xff) << 16) |
                                    ((buf(i + 2).toInt & 0xff) << 8) |
                                    (buf(i + 3).toInt & 0xff)
                            if !seen.asInstanceOf[js.Dynamic].includes(packed.asInstanceOf[js.Any]).asInstanceOf[Boolean]
                            then
                                val _ = seen.push(packed)
                                distinctCount += 1
                            end if
                            i += 4
                        end while
                        glHolder.pixelCount = distinctCount
                        val prev =
                            if js.isUndefined(w.__embedMountCount) then 0
                            else w.__embedMountCount.asInstanceOf[Int]
                        w.__embedMountCount = prev + 1
                        w.__embedFrame = true
                    end if
            }
            mesh
        }(())

    /** Parks the probe fiber until the first embedded frame rendered, bounding the wait so a
      * non-rendering embed fails the browser test by timeout.
      */
    private def untilEmbedFrame(using Frame): Unit < Async =
        Loop.foreach {
            Sync.defer(dom.window.asInstanceOf[js.Dynamic].__embedFrame).map { f =>
                if !js.isUndefined(f) && f.asInstanceOf[Boolean] then Loop.done
                else Async.sleep(16.millis).andThen(Loop.continue)
            }
        }

    /** Mounts the full `EmbeddedScene.ui` tree (controls + embedded canvas + HUD) through
      * `UI.runMount`, exposing `window.__setSelected(name)` so the browser test can drive the
      * shared `SignalRef[String]` directly without needing a raycast, and `window.__getSelected()`
      * to read it back. Raises `window.__interactiveReady` after the first frame.
      */
    @JSExportTopLevel("mountEmbedInteractive")
    def mountEmbedInteractive(): Unit =
        // Unsafe: the page-to-kyo boundary; runs on a detached fiber; AllowUnsafe scoped to entry.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(embedInteractiveProbe()).unit)
    end mountEmbedInteractive

    private def embedInteractiveProbe()(using Frame): Unit < Async =
        Scope.run {
            for
                built <- EmbeddedScene.scene
                (scene, selected) = built
                _ <- Sync.defer {
                    val w = dom.window.asInstanceOf[js.Dynamic]
                    // Expose a direct signal setter and getter so the browser test can drive the
                    // bidirectional SignalRef interop without a fragile raycast click.
                    w.__setSelected = js.Any.fromFunction1 { (name: String) =>
                        import AllowUnsafe.embrace.danger
                        val _ = Sync.Unsafe.evalOrThrow(selected.set(name).unit)
                    }
                    w.__getSelected = js.Any.fromFunction0 { () =>
                        import AllowUnsafe.embrace.danger
                        Sync.Unsafe.evalOrThrow(selected.get)
                    }
                    w.__interactiveReady = true
                }
                tree =
                    UI.div(
                        UI.button("Focus Sun").id("focus-sun").onClick(selected.set("Sun")),
                        Three.embed(scene, EmbeddedScene.camera).id("stage"),
                        UI.p(selected.map(s => s"Selected: $s")).id("selected-label")
                    )
                _ <- Fiber.init(UI.runMount(tree))
                _ <- Async.never
            yield ()
        }
    end embedInteractiveProbe

    /** Mounts a kyo-ui tree with an embedded canvas and a sibling reactive counter, exposing
      * `window.__setEmbedCount(n)` to drive re-renders. The canvas is stamped with a token
      * `data-host-token="1"` on mount and the mount captures the GL context into
      * `window.__siblingGl`. After N emissions the browser test reads the token and GL identity
      * to prove the canvas was never replaced and the GL context was not re-acquired.
      */
    @JSExportTopLevel("mountEmbedSiblingProbe")
    def mountEmbedSiblingProbe(): Unit =
        // Unsafe: the page-to-kyo boundary; runs on a detached fiber; AllowUnsafe scoped to entry.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(embedSiblingProbe()).unit)
    end mountEmbedSiblingProbe

    private def embedSiblingProbe()(using Frame): Unit < Async =
        Scope.run {
            for
                counter <- Signal.initRef(0)
                _ <- Sync.defer {
                    val w = dom.window.asInstanceOf[js.Dynamic]
                    // Expose a setter so the browser test can drive sibling re-renders.
                    w.__setEmbedCount = js.Any.fromFunction1 { (n: Int) =>
                        import AllowUnsafe.embrace.danger
                        val _ = Sync.Unsafe.evalOrThrow(counter.set(n).unit)
                    }
                    w.__siblingReady = false
                }
                built <- EmbeddedScene.scene
                (scene, _) = built
                probeScene = scene.copy(children = scene.children :+ siblingCaptureSentinel())
                tree = UI.div(
                    Three.embed(probeScene, EmbeddedScene.camera).id("stage"),
                    counter.map(n => UI.span(n.toString).id("count"))
                )
                _ <- Fiber.init(UI.runMount(tree))
                _ <- Async.never
            yield ()
        }
    end embedSiblingProbe

    /** A sentinel Mesh whose `onAfterRender` fires once on the first embedded frame: stamps
      * `data-host-token="1"` on the canvas and captures the live GL context into
      * `window.__siblingGl`, then marks `window.__siblingGl.__kyoSiblingMark = true` to allow the
      * browser test to verify the same context object survives sibling reactive re-renders. Sets
      * `window.__siblingReady` when done.
      */
    private def siblingCaptureSentinel()(using Frame): Three =
        Three.custom { (_: Unit) =>
            val geom = js.Dynamic.newInstance(ThreeLite.BoxGeometry)(0.1, 0.1, 0.1)
            val mat  = js.Dynamic.newInstance(ThreeLite.MeshBasicMaterial)(js.Dynamic.literal(color = 0x000000))
            val mesh = js.Dynamic.newInstance(ThreeLite.Mesh)(geom, mat)
            mesh.frustumCulled = false
            mesh.onAfterRender = js.Any.fromFunction6 {
                (renderer: js.Dynamic, _: js.Dynamic, _: js.Dynamic, _: js.Dynamic, _: js.Dynamic, _: js.Dynamic) =>
                    // Side effect: one-shot GL context capture and canvas token stamp on the first render,
                    // recorded so the test can assert context identity survives sibling re-renders.
                    val w = dom.window.asInstanceOf[js.Dynamic]
                    if js.isUndefined(w.__siblingReady) || !w.__siblingReady.asInstanceOf[Boolean] then
                        val gl     = renderer.getContext()
                        val canvas = renderer.domElement
                        val _      = canvas.setAttribute("data-host-token", "1")
                        if !js.isUndefined(gl) && gl != null then
                            w.__siblingGl = gl
                            // Stamp a unique marker on the context object so the test can verify the
                            // same object is returned after sibling re-renders (identity proof).
                            gl.__kyoSiblingMark = true
                        end if
                        w.__siblingReady = true
                    end if
            }
            mesh
        }(())

    private def rendererReleaseProbe(selector: String)(using Frame): Unit < Async =
        // Unsafe: a single holder for the live GL context captured inside the scope so it can be read
        // after the scope closes; set once and read once, both on this probe's fiber.
        val glHolder = new scala.scalajs.js.Object().asInstanceOf[js.Dynamic]
        Abort.run[ThreeException] {
            Scope.run {
                for
                    canvas   <- ThreeMount.resolveCanvas(selector)
                    renderer <- ThreeMount.makeRenderer(canvas)
                    _ <- Sync.Unsafe.defer {
                        // Capture the live context inside the scope; reading isContextLost() after the
                        // scope closes shows whether the production release freed it.
                        glHolder.gl = renderer.getContext()
                    }
                yield ()
            }
        }.map { result =>
            // Side effect: record the post-close context state for the browser test.
            Sync.Unsafe.defer {
                val w  = dom.window.asInstanceOf[js.Dynamic]
                val gl = glHolder.gl
                val lost =
                    if js.isUndefined(gl) || gl == null then true
                    else gl.isContextLost().asInstanceOf[Boolean]
                result match
                    case Result.Success(_) => ()
                    case other             => w.__releaseError = String.valueOf(other)
                w.__contextLost = String.valueOf(lost)
                w.__releaseReady = true
            }
        }
    end rendererReleaseProbe

    /** A three.js `onAfterRender` hook: on the first render, reads the framebuffer center pixel while
      * it is still live and records whether it is lit, freezing the result for the ordering test.
      */
    private val readCenterOnce: js.Function6[js.Dynamic, js.Dynamic, js.Dynamic, js.Dynamic, js.Dynamic, js.Dynamic, Unit] =
        (renderer, _, _, _, _, _) =>
            // Side effect: a one-shot center-pixel readback during the first render submit, recorded for the
            // ordering test; the GL framebuffer is valid here because we are inside renderer.render.
            val w     = dom.window.asInstanceOf[js.Dynamic]
            val ready = w.__orderingReady
            if js.isUndefined(ready) || !ready.asInstanceOf[Boolean] then
                val gl     = renderer.getContext()
                val buffer = new scala.scalajs.js.typedarray.Uint8Array(4)
                val cx     = (renderer.domElement.width.asInstanceOf[Double] / 2).toInt
                val cy     = (renderer.domElement.height.asInstanceOf[Double] / 2).toInt
                val _      = gl.readPixels(cx, cy, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, buffer)
                val r      = buffer(0).toInt & 0xff
                val g      = buffer(1).toInt & 0xff
                val b      = buffer(2).toInt & 0xff
                val lit    = (r + g + b) > 60
                w.__orderingCenterLit = lit
                w.__orderingReady = true
            end if

end DemoHarness

/** The three.js constructors the ordering probe's custom build needs, imported from the same `three`
  * module the demo bundle already links. Only the handful the probe constructs are bound.
  */
@js.native
@JSImport("three", JSImport.Namespace)
private object ThreeLite extends js.Object:
    val Mesh: js.Dynamic              = js.native
    val BoxGeometry: js.Dynamic       = js.native
    val MeshBasicMaterial: js.Dynamic = js.native
end ThreeLite
