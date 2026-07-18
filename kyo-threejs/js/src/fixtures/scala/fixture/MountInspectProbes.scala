package fixture

import kyo.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** The `@JSExportTopLevel` entries a browser test loads from the compiled fixtures bundle (`/main.js`).
  *
  * Each entry mounts a scene through the real production path and installs [[ThreeInspect]] over the
  * resulting [[Three.Mount]], so the page exposes ONE documented `window[name]` shape
  * (`renders`, `contextLost`, `disposed`, `canvasToken`, `pixel`, `signal`) that the driver reads over
  * CDP. A test therefore observes the mount through its public handle rather than through globals the
  * probe wrote by hand.
  *
  * Two patterns cover every entry here. A TEARDOWN probe captures the mount handle in an outer scope
  * that outlives the mount's own scope, so the projection survives to report the torn-down state
  * (`disposed`, `contextLost`) once the mount scope closes. A LIVE probe installs the projection
  * directly in the mount's own scope and holds it open (`Async.never` or a driver-triggered close), so
  * the driver can read a running mount's state at its own pace.
  *
  * The entries live outside package `kyo` because `@JSExportTopLevel` and the `Frame` macro both refuse
  * to derive inside it.
  *
  * A probe that cannot mount records the reason on `window.<name>Error` and installs nothing, so a driver
  * waiting on `window[name]` fails with a readable cause instead of timing out blind.
  *
  * This is TEST infrastructure and links into the `kyo-threejs-fixtures` bundle, never the demo bundle:
  * a demo is an application a reader runs, and the six of them carry nothing a test needs.
  */
object MountInspectProbes:

    /** Waits for `mounted` to reach `Present`, reading the CURRENT value first so a transition that
      * already happened is not missed by an unconditional `.next` (the one-shot Absent->Present flip).
      */
    private def awaitPresent(mounted: Signal[Maybe[Three.Mount]])(using Frame): Three.Mount < Async =
        mounted.current.map {
            case Present(m) => m
            case Absent =>
                mounted.next.map {
                    case Present(m) => m
                    case Absent     => awaitPresent(mounted)
                }
        }

    /** Records why a probe could not install its projection, so a waiting driver sees a cause. */
    private def reportError(name: String, message: String)(using Frame): Unit < Sync =
        // Unsafe: a one-shot write on the live window at the page-to-kyo boundary.
        Sync.Unsafe.defer {
            val w = dom.window.asInstanceOf[js.Dynamic]
            w.updateDynamic(s"${name}Error")(message)
        }

    /** Adds one extra, documented field to an ALREADY-installed `window[name]` projection: the fixed
      * `ThreeInspect` shape covers the common case, and a leaf whose assertion genuinely cannot be
      * expressed through that shape (a structural fact about `Embedded.mounted`, a driver-triggered
      * close) gets exactly one additional named field here, never a second ad-hoc global.
      */
    private def addField(name: String, key: String, value: js.Any)(using Frame): Unit < Sync =
        // Unsafe: mutates the already-installed projection object at the page-to-kyo boundary.
        Sync.Unsafe.defer {
            val w = dom.window.asInstanceOf[js.Dynamic]
            w.selectDynamic(name).updateDynamic(key)(value)
        }

    // ---- ThreeMountBrowserTest ------------------------------------------------------------

    /** Mounts a scene, holds it for one committed frame, then CLOSES the mount scope and installs the
      * projection over the torn-down handle.
      *
      * The install deliberately happens in a scope that OUTLIVES the mount scope: the projection removes
      * itself when its own scope closes, so installing it inside the mount scope would delete the very
      * window object the driver needs to read the teardown's outcome. Installed outside, `disposed()` and
      * `contextLost()` report the mount's final state after the production release has run.
      */
    @JSExportTopLevel("mountRendererInspect")
    def mountRendererInspect(selector: String): Unit =
        // Unsafe: the page-to-kyo boundary; the entry starts a detached fiber and returns to the page.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(rendererInspect(selector)).unit)
    end mountRendererInspect

    private def rendererInspect(selector: String)(using Frame): Unit < Async =
        val scene  = Three.scene(Three.mesh(Three.Geometry.box(), Three.Material.basic()))
        val camera = Three.Camera.perspective()
        Scope.run {
            for
                captured <- AtomicRef.init(Maybe.empty[Three.Mount])
                outcome <- Abort.run[ThreeException] {
                    // The mount's OWN scope. Closing it is the event under test: the production release
                    // runs dispose() then forceContextLoss(), which fires `disposed` and loses the context.
                    Scope.run {
                        for
                            mount <- Three.runMount(scene, camera, selector, ThreeFrames.Raf)
                            _     <- captured.set(Present(mount))
                            _     <- mount.renders.next
                        yield ()
                    }
                }
                _ <- outcome match
                    case Result.Success(_) =>
                        captured.get.map {
                            case Present(mount) => ThreeInspect.install("inspect", mount)
                            case Absent         => reportError("inspect", "the mount handle was never captured")
                        }
                    case Result.Failure(e) => reportError("inspect", s"mount failed: ${e.getMessage}")
                    case Result.Panic(e)   => reportError("inspect", s"mount panicked: ${e.getMessage}")
                // Hold this scope open so the projection survives for the driver to read.
                _ <- Async.never
            yield ()
        }
    end rendererInspect

    /** Mounts a lit scene and installs the projection LIVE (inside the mount's own scope, held open
      * with `Async.never`), so a driver reads real `pixel` bytes from a running mount at its own pace:
      * the released `mountRendererInspect` handle above is already torn down and would typed-reject
      * every read, which serves the disposed-read leaf but cannot serve a live-pixel or a live
      * out-of-bounds leaf. A single fixed bright-blue box fills the canvas center, giving a known,
      * non-black color to sample.
      */
    @JSExportTopLevel("mountRendererLiveInspect")
    def mountRendererLiveInspect(selector: String): Unit =
        // Unsafe: the page-to-kyo boundary; the entry starts a detached fiber and returns to the page.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(rendererLiveInspect(selector)).unit)
    end mountRendererLiveInspect

    private def rendererLiveInspect(selector: String)(using Frame): Unit < Async =
        val scene  = Three.scene(Three.mesh(Three.Geometry.box(), Three.Material.basic(color = Three.Color(0x3377ff))))
        val camera = Three.Camera.perspective()
        Scope.run {
            Abort.run[ThreeException](Three.runMount(scene, camera, selector, ThreeFrames.Raf)).map {
                case Result.Success(mount) => ThreeInspect.install("inspectLive", mount).andThen(Async.never)
                case Result.Failure(e)     => reportError("inspectLive", s"mount failed: ${e.getMessage}")
                case Result.Panic(e)       => reportError("inspectLive", s"mount panicked: ${e.getMessage}")
            }
        }
    end rendererLiveInspect

    // ---- ThreeMountOrderingBrowserTest -----------------------------------------------------

    /** The three.js constructors the ordering probe's custom build needs, imported from the same
      * `three` module the demo bundle already links. `Three.custom` is the sanctioned raw-three.js escape
      * hatch: the probe captures the live object it builds and mutates it directly from `onFrame`, exactly
      * as `ThreeMountTest`'s own "live runLoop" fixture does against the reconciler's live object. Every
      * pixel it reads goes through the public `Three.Mount` handle, never a render hook or a GL context.
      */
    @js.native
    @js.annotation.JSImport("three", js.annotation.JSImport.Namespace)
    private object ThreeLite extends js.Object:
        val Mesh: js.Dynamic              = js.native
        val BoxGeometry: js.Dynamic       = js.native
        val MeshBasicMaterial: js.Dynamic = js.native
    end ThreeLite

    /** Mounts a scene whose mesh is built with an unlit BLACK material and whose `onFrame` brightens
      * it directly on the live object, then proves ordering by enqueuing a `readPixels` at the exact
      * center BEFORE the first frame commits, stepping ONE deterministic frame, and reporting whether
      * the drained frame shows the post-`onFrame` bright color.
      *
      * The enqueue-before-step ordering is not a race: `driver.step` is FORKED (scheduled, not yet
      * run) before `mount.readPixels` is called directly (composed, not forked) on this fiber. A
      * direct, non-forked call runs its synchronous prefix (the capture enqueue) immediately, with no
      * scheduler round-trip; it then suspends on the pending read's promise, and ONLY AT THAT
      * suspension does the scheduler run the already-forked step, whose render submit drains the
      * capture that is by then guaranteed to be enqueued. If the loop applies a tick's `onFrame` before
      * that tick's render submit, the drained frame is the post-`onFrame` bright color; if it renders
      * first, the drained frame is still the seed black.
      */
    @JSExportTopLevel("mountOrderingInspect")
    def mountOrderingInspect(selector: String): Unit =
        // Unsafe: the page-to-kyo boundary; the entry starts a detached fiber and returns to the page.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(orderingInspect(selector)).unit)
    end mountOrderingInspect

    private def orderingInspect(selector: String)(using Frame): Unit < Async =
        val liveBox = new js.Object().asInstanceOf[js.Dynamic]
        val box = Three.custom[js.Dynamic] { holder =>
            val geom = js.Dynamic.newInstance(ThreeLite.BoxGeometry)(6, 6, 6)
            // An unlit black material so the box renders every frame yet contributes a dark center
            // pixel until the tick's onFrame brightens its color.
            val mat  = js.Dynamic.newInstance(ThreeLite.MeshBasicMaterial)(js.Dynamic.literal(color = 0x000000))
            val mesh = js.Dynamic.newInstance(ThreeLite.Mesh)(geom, mat)
            holder.mesh = mesh
            mesh
        }(liveBox).onFrame { _ =>
            // Unsafe: brighten the live material on the frame; the render submit of the SAME tick, if
            // ordering holds, must observe this mutation.
            Sync.Unsafe.defer {
                val mesh = liveBox.mesh.asInstanceOf[js.Dynamic]
                val _    = mesh.material.color.set(0x33ff66.toDouble)
            }
        }
        val scene  = Three.scene(box)
        val camera = Three.Camera.perspective(position = Three.Vec3(0, 0, 6))
        val cx     = 128
        val cy     = 128
        Scope.run {
            for
                // The Manual callback runs on the mount's OWN forked loop fiber (hostMountPipelineTyped
                // forks the loop before runMount returns); it hands its Driver out through this promise
                // and then parks, so the caller's fiber (which holds `mount` once runMount returns)
                // drives every step itself, in the exact order this leaf needs.
                driverPromise <- Promise.init[Three.Driver, Any]
                outcome <- Abort.run[ThreeException] {
                    Three.runMount(
                        scene,
                        camera,
                        selector,
                        ThreeFrames.Manual { driver => driverPromise.complete(Result.Success(driver)).andThen(Async.never) }
                    )
                }
                _ <- outcome match
                    case Result.Success(mount) =>
                        for
                            driver <- driverPromise.get
                            // `driver.step` is FORKED (scheduled, not yet run) BEFORE `mount.readPixels` is
                            // called directly on this fiber: the direct call's synchronous prefix (the
                            // capture enqueue) runs immediately, then suspends on the pending read's
                            // promise, and only at that suspension does the scheduler run the already-
                            // forked step, whose render submit drains a capture guaranteed to be enqueued.
                            stepFiber   <- Fiber.initUnscoped(Abort.run[ThreeException](driver.step(16.millis)))
                            readOutcome <- Abort.run[ThreeException](mount.readPixels(cx, cy, 1, 1))
                            _           <- stepFiber.get
                            _ <- readOutcome match
                                case Result.Success(bytes) =>
                                    val r   = bytes(0) & 0xff
                                    val g   = bytes(1) & 0xff
                                    val b   = bytes(2) & 0xff
                                    val lit = (r + g + b) > 60
                                    ThreeInspect.install("ordering", mount).andThen(
                                        addField("ordering", "centerLit", lit)
                                    )
                                case Result.Failure(e) => reportError("ordering", s"readPixels failed: ${e.getMessage}")
                                case Result.Panic(e)   => reportError("ordering", s"readPixels panicked: ${e.getMessage}")
                        yield ()
                    case Result.Failure(e) => reportError("ordering", s"mount failed: ${e.getMessage}")
                    case Result.Panic(e)   => reportError("ordering", s"mount panicked: ${e.getMessage}")
                _ <- Async.never
            yield ()
        }
    end orderingInspect

    // ---- ThreeMountFailureBrowserTest ------------------------------------------------------

    /** Runs [[Three.runMount]] over a canvas already committed to a 2D context (so `makeRenderer` fails
      * typed) and projects the OUTCOME rather than a live handle: there is no mount to install
      * `ThreeInspect` over. Records `window.failureOutcome` ("typed:<ExceptionClass>" / "success" /
      * "panic:<message>") and `window.failureError` on an unexpected throw.
      */
    @JSExportTopLevel("mountFailureInspect")
    def mountFailureInspect(selector: String): Unit =
        // Unsafe: the page-to-kyo boundary; the entry starts a detached fiber and returns to the page.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(failureInspect(selector)).unit)
    end mountFailureInspect

    private def failureInspect(selector: String)(using Frame): Unit < Async =
        val scene  = Three.scene(Three.mesh(Three.Geometry.box(), Three.Material.basic()))
        val camera = Three.Camera.perspective()
        Scope.run {
            // Manual frames with a never-stepped driver: on the failure path makeRenderer fails before any
            // frame, so the frame source is irrelevant; a parked driver avoids a live loop if a context
            // were somehow available.
            Abort.run[ThreeException](Three.runMount(scene, camera, selector, ThreeFrames.Manual(_ => Async.never))).map { result =>
                Sync.Unsafe.defer {
                    val w = dom.window.asInstanceOf[js.Dynamic]
                    w.failureOutcome = result match
                        case Result.Success(_) => "success"
                        case Result.Failure(e) => s"typed:${e.getClass.getSimpleName}"
                        case Result.Panic(e)   => s"panic:${Option(e.getMessage).getOrElse(e.getClass.getSimpleName)}"
                }
            }
        }
    end failureInspect

    // ---- ThreeEmbedBrowserTest --------------------------------------------------------------

    /** Mounts [[EmbedInspectScene]] via `Three.embed`, reading `Embedded.mounted` BEFORE the
      * page mount (Absent) and current-first-then-next AFTER it (Present), then installs `ThreeInspect`
      * over that SAME handle. A second `.id()` copy of the SAME pre-.id() reference proves the id()
      * setter preserves the one shared `mountedRef` (no `.id()` copy holds a distinct dead ref):
      * `window.embedInspect.mountedAbsentBefore` and `window.embedInspect.sameHandleAsIdCopy` are the
      * two extra fields this leaf's structural assertions need, beyond the fixed `ThreeInspect` shape.
      */
    @JSExportTopLevel("mountEmbedInspect")
    def mountEmbedInspect(): Unit =
        // Unsafe: the page-to-kyo boundary; the entry starts a detached fiber and returns to the page.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(embedInspect()).unit)
    end mountEmbedInspect

    private def embedInspect()(using Frame): Unit < Async =
        Scope.run {
            for
                built <- EmbedInspectScene.scene
                (scene, _) = built
                e          = Three.embed(scene, EmbedInspectScene.camera)
                absentBefore <- e.mounted.current.map {
                    case Absent => true
                    case _      => false
                }
                idCopy = e.id("embed-inspect-stage")
                tree   = UI.div(idCopy)
                _              <- Fiber.init(UI.runMount(tree))
                mountViaERef   <- awaitPresent(e.mounted)
                mountViaIdCopy <- awaitPresent(idCopy.mounted)
                sameHandle = mountViaERef eq mountViaIdCopy
                _ <- ThreeInspect.install("embedInspect", mountViaERef)
                _ <- addField("embedInspect", "mountedAbsentBefore", absentBefore)
                _ <- addField("embedInspect", "sameHandleAsIdCopy", sameHandle)
                _ <- Async.never
            yield ()
        }
    end embedInspect

    /** Mounts [[EmbedInspectScene]] with a HUD label bound to the shared selection signal, holds
      * for one committed frame, installs `ThreeInspect` with `signals = Map("selected" -> selected)` so a
      * click's raycast `onClick` (which writes `selected`) is readable via `window.embedInteractive
      * .signal("selected")`, and exposes a driver-triggered `close()` (a `Promise` gate, never a sleep)
      * so the SAME mount can also be observed torn down: the driver reads the live signal/pixel/
      * contextLost/canvasToken first, then calls `close()`, then reads `disposed()`.
      */
    @JSExportTopLevel("mountEmbedInteractiveInspect")
    def mountEmbedInteractiveInspect(): Unit =
        // Unsafe: the page-to-kyo boundary; the entry starts a detached fiber and returns to the page.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(embedInteractiveInspect()).unit)
    end mountEmbedInteractiveInspect

    private def embedInteractiveInspect()(using Frame): Unit < Async =
        Scope.run {
            for
                mountSignal <- Promise.init[Three.Mount, Any]
                closeSignal <- Promise.init[Unit, Any]
                built       <- EmbedInspectScene.scene
                (scene, selected) = built
                embed             = Three.embed(scene, EmbedInspectScene.camera).id("embed-interactive-stage")
                hud               = UI.p(selected.map(s => s"Selected: $s")).id("selected-label")
                tree              = UI.div(embed, hud)
                // The mount's own scope runs on a FORKED fiber that outlives this flow's next step:
                // installing ThreeInspect must never sequence behind this fiber's own completion, since
                // it only completes once close() is called, and close() is reachable only through the
                // installed projection (mirrors kyo.ServerBridgeHydrate's same handoff).
                _ <- Fiber.init(Scope.run {
                    for
                        _     <- Fiber.init(UI.runMount(tree))
                        mount <- awaitPresent(embed.mounted)
                        _     <- mount.renders.next
                        _     <- mountSignal.complete(Result.Success(mount))
                        _     <- closeSignal.get
                    yield ()
                })
                mount <- mountSignal.get
                _ <- ThreeInspect.install("embedInteractive", mount, signals = Map("selected" -> selected))
                    .andThen(addField(
                        "embedInteractive",
                        "close",
                        (() =>
                            import AllowUnsafe.embrace.danger
                            val _ = Sync.Unsafe.evalOrThrow(closeSignal.complete(Result.Success(())).unit)
                        ): js.Function0[Unit]
                    ))
                _ <- Async.never
            yield ()
        }
    end embedInteractiveInspect

    /** Mounts [[EmbedInspectScene]] alongside a reactive counter SIBLING (not part of the 3D
      * scene), captures the mount handle, drives the sibling counter through several emissions, then
      * re-reads the SAME `Embedded.mounted` signal and compares handle identity: a signal-driven
      * re-render of the SURROUNDING kyo-ui tree must never re-issue the mount. Also installs
      * `ThreeInspect` so `renders`/`contextLost` stay observable through the same re-renders.
      */
    @JSExportTopLevel("mountEmbedSiblingInspect")
    def mountEmbedSiblingInspect(): Unit =
        // Unsafe: the page-to-kyo boundary; the entry starts a detached fiber and returns to the page.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(embedSiblingInspect()).unit)
    end mountEmbedSiblingInspect

    private def embedSiblingInspect()(using Frame): Unit < Async =
        Scope.run {
            for
                counter <- Signal.initRef(0)
                built   <- EmbedInspectScene.scene
                (scene, _) = built
                embed      = Three.embed(scene, EmbedInspectScene.camera).id("embed-sibling-stage")
                tree       = UI.div(embed, counter.map(n => UI.span(n.toString).id("sibling-count")))
                _  <- Fiber.init(UI.runMount(tree))
                m1 <- awaitPresent(embed.mounted)
                _  <- counter.set(1)
                _  <- counter.set(2)
                _  <- counter.set(3)
                m2 <- awaitPresent(embed.mounted)
                sameHandle = m1 eq m2
                _ <- ThreeInspect.install("embedSibling", m1)
                _ <- addField("embedSibling", "mountStableAcrossRerender", sameHandle)
                _ <- Async.never
            yield ()
        }
    end embedSiblingInspect

    // ---- ThreeControlsBrowserTest -----------------------------------------------------------

    /** Mounts [[ControlsInspectScene]] (a static cube cluster plus `Three.controls(autoRotate =
      * true)`) and installs `ThreeInspect` LIVE, held open with `Async.never`, so the driver samples
      * `pixel` across several commits while the auto-rotating camera orbits the static object.
      */
    @JSExportTopLevel("mountControlsInspect")
    def mountControlsInspect(selector: String): Unit =
        // Unsafe: the page-to-kyo boundary; the entry starts a detached fiber and returns to the page.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(controlsInspect(selector)).unit)
    end mountControlsInspect

    private def controlsInspect(selector: String)(using Frame): Unit < Async =
        Scope.run {
            for
                scene <- ControlsInspectScene.scene
                outcome <- Abort.run[ThreeException](
                    Three.runMount(scene, ControlsInspectScene.camera, selector, ThreeFrames.Raf)
                )
                _ <- outcome match
                    case Result.Success(mount) => ThreeInspect.install("controls", mount)
                    case Result.Failure(e)     => reportError("controls", s"mount failed: ${e.getMessage}")
                    case Result.Panic(e)       => reportError("controls", s"mount panicked: ${e.getMessage}")
                _ <- Async.never
            yield ()
        }
    end controlsInspect

    /** Mounts [[ControlsInspectScene.reactiveScene]] (the same static cluster with a REACTIVE `autoRotate`,
      * orbit OFF) and installs `ThreeInspect` exposing the `"autoRotate"` text ref through its `signals`
      * projection, so the driver flips `window.controls.signal("autoRotate").set("true")` and proves the
      * live `OrbitControls` starts rotating only after the toggle.
      */
    @JSExportTopLevel("mountControlsReactive")
    def mountControlsReactive(selector: String): Unit =
        // Unsafe: the page-to-kyo boundary; the entry starts a detached fiber and returns to the page.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(controlsReactive(selector)).unit)
    end mountControlsReactive

    private def controlsReactive(selector: String)(using Frame): Unit < Async =
        Scope.run {
            for
                built <- ControlsInspectScene.reactiveScene
                (scene, autoRotateText) = built
                outcome <- Abort.run[ThreeException](
                    Three.runMount(scene, ControlsInspectScene.camera, selector, ThreeFrames.Raf)
                )
                _ <- outcome match
                    case Result.Success(mount) =>
                        ThreeInspect.install("controls", mount, Map("autoRotate" -> autoRotateText))
                    case Result.Failure(e) => reportError("controls", s"mount failed: ${e.getMessage}")
                    case Result.Panic(e)   => reportError("controls", s"mount panicked: ${e.getMessage}")
                _ <- Async.never
            yield ()
        }
    end controlsReactive

    // ---- ThreeToImageBrowserTest ------------------------------------------------------------

    /** Runs `Three.toImage` (a headless capture, no live loop and no `Three.Mount`) for a lit or an
      * empty scene, and projects the PNG bytes as a raw `Uint8Array` on `window.toImageBytes` so the
      * driver decodes it with the browser's OWN native PNG decoder (a `Blob` + `<img>.decode()` +
      * `2d` canvas readback), never a hand-rolled decoder on either side. `toImage` has no mount to
      * install `ThreeInspect` over, mirroring `mountFailureInspect`'s outcome-projection shape.
      */
    @JSExportTopLevel("toImageInspect")
    def toImageInspect(lit: Boolean, width: Int, height: Int): Unit =
        // Unsafe: the page-to-kyo boundary; the entry starts a detached fiber and returns to the page.
        import AllowUnsafe.embrace.danger
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(toImageInspectRun(lit, width, height)).unit)
    end toImageInspect

    private def toImageInspectRun(lit: Boolean, width: Int, height: Int)(using Frame): Unit < Async =
        val scene =
            if lit then Three.scene(Three.mesh(Three.Geometry.box(), Three.Material.basic(color = Three.Color(0x3377ff))))
            else Three.scene()
        val camera = Three.Camera.perspective()
        Scope.run {
            Abort.run[ThreeException](Three.toImage(scene, camera, width, height)).map {
                case Result.Success(image) =>
                    Sync.Unsafe.defer {
                        val bytes = image.binary
                        val arr   = new js.typedarray.Uint8Array(bytes.size)
                        Chunk.from(0 until bytes.size).foreach(i => arr(i) = (bytes(i) & 0xff).toShort)
                        val w = dom.window.asInstanceOf[js.Dynamic]
                        w.toImageBytes = arr
                    }
                case Result.Failure(e) => reportError("toImageResult", s"toImage failed: ${e.getMessage}")
                case Result.Panic(e)   => reportError("toImageResult", s"toImage panicked: ${e.getMessage}")
            }
        }
    end toImageInspectRun

end MountInspectProbes
