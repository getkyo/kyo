package kyo

import kyo.internal.GltfLoader
import kyo.internal.Image
import kyo.internal.PointerKind
import kyo.internal.PointerWire
import kyo.internal.Raycasting
import kyo.internal.Reconciler
import kyo.internal.TextureLoader
import kyo.internal.ThreeFacade
import kyo.internal.ThreeFacadeOps
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.scalajs.js.typedarray.Uint8Array

/** Internal implementation home for the Three scene runner and frame loop. The six public
  * runner methods (`runMount`, `testDriver`, `loadGltf`, `texture`, `toImage`, `embed`) are
  * delegating members of `object Three` (reachable via `import kyo.*`), which forward here.
  * Keeping the bodies in `object ThreeMount` avoids any package-level symbol clash with kyo-ui:
  * `object Three` members are not top-level package symbols, so `Three.runMount` and
  * `UI.runMount` coexist with no conflict.
  */
object ThreeMount:

    /** Mounts `scene` into the canvas at `selector` and runs the frame loop until the scope
      * closes.
      */
    private[kyo] def runMount(
        scene: Three,
        camera: Three.Ast.Camera,
        selector: String,
        frames: ThreeFrames = ThreeFrames.Raf
    )(using Frame): Three.Mount < (Async & Scope & Abort[ThreeException]) =
        // Resolves the canvas inline (a missing selector surfaces CanvasNotFound), then runs the TYPED
        // mount pipeline, which keeps its Abort[ThreeException] channel: an absent WebGL context, a
        // materialize failure, or a texture/glTF load failure surfaces as a typed ThreeException to this
        // method's caller. The swallow-and-log discharge lives ONLY at the kyo-ui Backend SPI seam
        // (hostMountPipeline), whose mount-callback row cannot carry Abort; the direct runMount entry
        // bypasses that seam so its declared typed channel is honored. This entry is client-local, so it
        // needs neither the wire path->Live index nor the JS-handle registration the registry path builds.
        for
            canvas <- ThreeMount.resolveCanvas(selector)
            mount  <- ThreeMount.hostMountPipelineTyped(scene, camera, frames, canvas.asInstanceOf[dom.Element])
        yield mount

    /** Yields a deterministic [[Three.Driver]] over the materialized scene, the same driver the
      * `ThreeFrames.Manual` path yields; a test steps frames without any sleep.
      */
    private[kyo] def testDriver(
        scene: Three.Ast.Scene,
        camera: Three.Ast.Camera
    )(using Frame): Three.Driver < (Async & Scope) =
        // The headless materialize constructs the AST through the facade-ops (each (Scope & Sync),
        // no WebGL/canvas), so no typed ThreeException is reachable here; the (unreachable) Abort the
        // Reconciler declares is converted to a panic at this boundary so the row matches the declared
        // (Async & Scope). A reached failure here would be a reconciler bug, not a recoverable case.
        Abort.recover[ThreeException](e => Abort.panic(e)) {
            for
                mountResult <- Reconciler.mount(scene)
                (rootLive, mounted) = mountResult
                cam    <- ThreeFacadeOps.makeCamera(camera)
                _      <- ThreeMount.recordCamera(mounted, camera, cam)
                _      <- ThreeMount.subscribeRegions(mounted)
                _      <- ThreeMount.subscribeReactiveRegions(mounted)
                driver <- ThreeMount.makeDriver(mounted, rootLive, cam)
            yield driver
        }

    /** Loads a glTF/GLB at `url` into a [[Three]] subtree; Scope-managed, typed failure on
      * load error.
      */
    private[kyo] def loadGltf(url: String)(using Frame): Asset.Gltf < (Async & Scope & Abort[ThreeException]) =
        GltfLoader.load(url)

    /** Loads an image at `url` into a GPU [[Three.Ast.Texture]] handle for a material `map`;
      * Scope-managed (the texture disposes on scope close), typed failure on load error.
      */
    private[kyo] def texture(url: String)(using Frame): Three.Ast.Texture < (Async & Scope & Abort[ThreeException]) =
        TextureLoader.load(url)

    /** Renders `scene` from `camera` to a `width`x`height` PNG, returning the kyo-browser
      * [[kyo.internal.Image]].
      */
    private[kyo] def toImage(
        scene: Three,
        camera: Three.Ast.Camera,
        width: Int = 1280,
        height: Int = 720
    )(using Frame): Image < (Async & Scope & Abort[ThreeException]) =
        for
            renderer    <- ThreeToImage.makeRenderer(width, height)
            target      <- ThreeToImage.makeRenderTarget(width, height)
            mountResult <- Reconciler.mount(scene)
            (rootLive, mounted) = mountResult
            cam <- ThreeFacadeOps.makeCamera(camera)
            _   <- ThreeMount.recordCamera(mounted, camera, cam)
            // A headless single-frame capture fills every structural reactive region and every
            // prop-level `Bound.Ref` from its signal's current value, so a reactive scene renders
            // its current state (no live loop).
            _      <- Reconciler.fillReactiveRegionsOnce(mounted)
            _      <- ThreeMount.fillBoundRefsOnce(mounted)
            pixels <- ThreeToImage.renderToPixels(renderer, target, rootLive, cam, width, height)
            bytes  <- ThreeToImage.encodePng(pixels, width, height)
        yield Image.fromBinary(bytes)

    // private[kyo], not private: ThreeBackend.mount (kyo.internal) calls this directly.
    // The kyo-ui Backend SPI seam: the mount callback type is dom.Element => Unit < (Async & Scope), a
    // row that cannot carry Abort[ThreeException]. This wrapper discharges the typed pipeline's failure
    // to Log.error (swallow-and-log) so the effect reaching the seam is (Async & Scope). The DIRECT
    // public entry Three.runMount calls hostMountPipelineTyped instead, keeping the typed Abort channel.
    private[kyo] def hostMountPipeline(
        scene: Three,
        camera: Three.Ast.Camera,
        frames: ThreeFrames,
        canvas: org.scalajs.dom.Element,
        onMounted: (Reconciler.Live, Reconciler.Mounted) => Unit < (Async & Scope & Abort[ThreeException]) =
            (_, _) => Kyo.unit,
        onMountHandle: Three.Mount => Unit < (Async & Scope) = _ => Kyo.unit
    )(using Frame): Unit < (Async & Scope) =
        Abort.run[ThreeException](hostMountPipelineTyped(scene, camera, frames, canvas, onMounted, onMountHandle)).map {
            case Result.Success(_) => (): Unit < Sync
            case Result.Failure(e) => Log.error(s"Three.embed mount failed: ${e.getMessage}")
            case Result.Panic(e) =>
                if e.isInstanceOf[Interrupted] then (): Unit < Sync
                else Log.error("Three.embed mount panicked", e)
        }
    end hostMountPipeline

    // The typed mount pipeline shared by the direct entry (Three.runMount, which keeps the typed Abort)
    // and the SPI seam wrapper (hostMountPipeline, which discharges it). The host hands a live
    // dom.Element; cast to js.Dynamic exactly as resolveCanvas does, then run the full mount on it under
    // the ambient page mount Scope. A WebGL/materialize/texture failure surfaces typed through the
    // returned Abort[ThreeException].
    private[kyo] def hostMountPipelineTyped(
        scene: Three,
        camera: Three.Ast.Camera,
        frames: ThreeFrames,
        canvas: org.scalajs.dom.Element,
        onMounted: (Reconciler.Live, Reconciler.Mounted) => Unit < (Async & Scope & Abort[ThreeException]) =
            (_, _) => Kyo.unit,
        onMountHandle: Three.Mount => Unit < (Async & Scope) = _ => Kyo.unit
    )(using Frame): Three.Mount < (Async & Scope & Abort[ThreeException]) =
        val canvasDyn = canvas.asInstanceOf[js.Dynamic]
        val pipeline: Three.Mount < (Async & Scope & Abort[ThreeException]) =
            for
                rendersRef  <- Signal.initRef(0L)
                disposedRef <- Signal.initRef(false)
                captures    <- Sync.Unsafe.defer(MountCaptureQueue.init)
                // On scope close, complete any pixel capture still pending as RenderFailure so an
                // awaiting readPixels never hangs past teardown. Registered here (not inside the
                // renderer release) because it touches no GL state, so it needs no ordering against
                // the GL dispose.
                _ <- Scope.ensure(Sync.Unsafe.defer {
                    // Unsafe: the ambient AllowUnsafe comes from this defer block's own context function.
                    captures.failAll("mount scope closed before the pixel readback committed")
                })
                renderer    <- ThreeMount.makeRenderer(canvasDyn, disposedRef)
                mountResult <- Reconciler.mount(scene)
                (rootLive, mounted) = mountResult
                cam <- ThreeFacadeOps.makeCamera(camera)
                _   <- ThreeMount.recordCamera(mounted, camera, cam)
                _ <- Sync.Unsafe.defer {
                    // Unsafe: reads the live canvas layout dimensions and mutates the renderer/camera
                    // synchronously at mount; deferred so the FFI sizing stays inside the effect.
                    // Size the renderer and camera to the actual canvas layout dimensions so the
                    // projection aspect matches the non-square canvas kyo-ui lays out. clientWidth
                    // and clientHeight reflect CSS-pixel dimensions after layout; fall back to the
                    // canvas width/height attributes if the element is not yet laid out (both are
                    // zero only in headless/test contexts where sizing is not required).
                    // updateStyle=false leaves the element CSS untouched: kyo-ui owns canvas styling.
                    // The renderer is sized once at mount and is not re-aspected on later window resizes.
                    val cw = canvasDyn.clientWidth.asInstanceOf[Double]
                    val ch = canvasDyn.clientHeight.asInstanceOf[Double]
                    val w  = if cw > 0 then cw else canvasDyn.width.asInstanceOf[Double]
                    val h  = if ch > 0 then ch else canvasDyn.height.asInstanceOf[Double]
                    if w > 0 && h > 0 then
                        val _ = renderer.setSize(w, h, false)
                        // Aspect-correction applies only to a perspective camera; an orthographic embed
                        // camera has no `aspect`, so the write would be an ineffective property set and
                        // updateProjectionMatrix would recompute an unchanged frustum. Guard on `aspect`
                        // being a number (present only on PerspectiveCamera). An orthographic embed keeps
                        // its symmetric frustum and is not aspect-corrected to a non-square host canvas.
                        if js.typeOf(cam.aspect) == "number" then
                            cam.aspect = w / h
                            val _ = cam.updateProjectionMatrix()
                        end if
                    end if
                }
                serverDriven <- ThreeMount.serverSeamPresent
                _            <- ThreeMount.subscribeRegions(mounted)
                _            <- ThreeMount.subscribeReactiveRegions(mounted, serverDriven)
                _            <- ThreeMount.setupPointerDelegation(canvasDyn, mounted, cam)
                controls     <- ThreeMount.setupControls(canvasDyn, mounted, cam)
                _            <- onMounted(rootLive, mounted)
                mount = new MountImpl(
                    rendersRef,
                    disposedRef,
                    canvasDyn.asInstanceOf[dom.HTMLCanvasElement],
                    new RendererImpl(renderer),
                    captures
                )
                _ <- onMountHandle(mount)
                _ <- Fiber.init {
                    Abort.run[ThreeException](ThreeMount.runLoop(
                        mounted,
                        rootLive,
                        cam,
                        renderer,
                        frames,
                        rendersRef,
                        captures,
                        controls
                    )).map {
                        case Result.Success(_) => (): Unit < Sync
                        case Result.Failure(e) => Log.error(s"Three.embed frame loop failed: ${e.getMessage}")
                        case Result.Panic(e) =>
                            if e.isInstanceOf[Interrupted] then (): Unit < Sync
                            else Log.error("Three.embed frame loop panicked", e)
                    }
                }.unit
            yield mount
        pipeline
    end hostMountPipelineTyped

    // The live-mount handle implementations. PLAIN (non-sealed) traits, impls in-file here so the
    // renderer/canvas locals never leak past the surface. private[kyo]: users receive, never construct.
    final private[kyo] class MountImpl(
        val renders: Signal[Long],
        val disposed: Signal[Boolean],
        val canvas: dom.HTMLCanvasElement,
        val renderer: Three.Renderer,
        private val captures: MountCaptureQueue
    ) extends Three.Mount, MountCanvas:
        def width: Int  = canvas.width
        def height: Int = canvas.height
        def readPixels(x: Int, y: Int, width: Int, height: Int)(using Frame): Span[Byte] < (Async & Abort[ThreeException]) =
            disposed.current.map {
                case true =>
                    Abort.fail(ThreeException.RenderFailure(
                        "readPixels on a disposed mount",
                        new Exception("mount disposed")
                    ))
                case false =>
                    Promise.init[Span[Byte], Abort[ThreeException]].map { done =>
                        Sync.Unsafe.defer {
                            // Unsafe: enqueue the one-shot capture on the queue the render submit drains;
                            // the ambient AllowUnsafe comes from Sync.Unsafe.defer's own context function.
                            captures.enqueue(MountCaptureQueue.Capture(x, y, width, height, done))
                        }.andThen(done.get)
                    }
            }
    end MountImpl

    final private[kyo] class RendererImpl(rendererDyn: js.Dynamic) extends Three.Renderer:
        def contextLost(using Frame): Boolean < Sync =
            // Unsafe: a synchronous FFI read of the live GL context state; a null context after
            // forceContextLoss() reads as lost. Lifted through Maybe, matching makeRenderer's own
            // nullable-FFI-read idiom (Maybe(renderer.getContext())).
            Sync.Unsafe.defer(Maybe(rendererDyn.getContext())).map {
                case Absent      => true
                case Present(gl) => gl.isContextLost().asInstanceOf[Boolean]
            }
        def unsafe: js.Dynamic = rendererDyn
    end RendererImpl

    // A one-shot pixel-capture queue drained by renderSubmit immediately after renderer.render,
    // because the default framebuffer is valid only inside the render task
    // (preserveDrawingBuffer:false). The single atomic carries Maybe[Chunk[Capture]]: Present is a live
    // queue, Absent is the terminal CLOSED state that teardown sets. Enqueue and close both transition
    // that ONE atomic, so a capture arriving after close cannot be stranded on a queue nothing will
    // drain: it observes the closed state and fails fast instead of awaiting forever. The AtomicRef.Unsafe
    // field is built by the companion init factory under the CALLER's propagated AllowUnsafe, never
    // embrace.danger at field-init scope, so construction is not callable from a pure position.
    final private[kyo] class MountCaptureQueue private (
        private val pending: AtomicRef.Unsafe[Maybe[Chunk[MountCaptureQueue.Capture]]]
    ):

        /** Enqueues one pending capture request (called by readPixels under the mount fiber). On a CLOSED
          * queue nothing is enqueued: the capture is completed `RenderFailure` at once, so a readPixels
          * racing teardown fails fast rather than awaiting a drain that will never come.
          */
        def enqueue(c: MountCaptureQueue.Capture)(using Frame, AllowUnsafe): Unit =
            val previous = pending.getAndUpdate {
                case Present(batch) => Present(batch.appended(c))
                case Absent         => Absent
            }
            previous match
                case Present(_) => ()
                case Absent =>
                    discard(Sync.Unsafe.evalOrThrow(c.done.completeDiscard(
                        Result.fail(ThreeException.RenderFailure(
                            MountCaptureQueue.closedMessage,
                            new Exception(MountCaptureQueue.closedMessage)
                        ))
                    )))
            end match
        end enqueue

        /** Drains every pending capture, reading the LIVE default framebuffer through the renderer's
          * GL context; each completes its promise with the RGBA `Span`, or `RenderFailure` on a GL or
          * out-of-bounds error (never a throw, never a garbage buffer). Called inside renderSubmit's
          * existing Sync.Unsafe.defer, strictly after renderer.render.
          */
        def drain(renderer: js.Dynamic)(using Frame, AllowUnsafe): Unit =
            val batch = pending.getAndUpdate {
                case Present(_) => Present(Chunk.empty)
                case Absent     => Absent
            }.getOrElse(Chunk.empty)
            batch.foreach { c =>
                try
                    val gl = renderer.getContext()
                    // A GL readPixels does NOT throw on an out-of-bounds region: GL queues errors for a
                    // later getError, and pixels outside the framebuffer are simply undefined, so an
                    // unchecked read hands back a zero-filled buffer that looks like a successful capture.
                    // Reject the region against the live drawing buffer first, so an out-of-bounds request
                    // completes as a typed failure instead of silent garbage.
                    // The bounds must be KNOWN to validate against. A context that reports no drawing
                    // buffer cannot be checked, and an unchecked read is the very thing that hands back
                    // garbage, so an unknown extent rejects rather than reads (`x > undefined` is false in
                    // JS, so an unknown bound would otherwise silently pass every extent test).
                    val widthDyn  = gl.drawingBufferWidth
                    val heightDyn = gl.drawingBufferHeight
                    val boundsKnown =
                        !js.isUndefined(widthDyn) && !js.isUndefined(heightDyn)
                    val bufferWidth  = if boundsKnown then widthDyn.asInstanceOf[Int] else 0
                    val bufferHeight = if boundsKnown then heightDyn.asInstanceOf[Int] else 0
                    val outOfBounds =
                        !boundsKnown || c.w <= 0 || c.h <= 0 || c.x < 0 || c.y < 0 ||
                            c.x + c.w > bufferWidth || c.y + c.h > bufferHeight
                    if outOfBounds then
                        val extent = if boundsKnown then s"${bufferWidth}x${bufferHeight}" else "unknown"
                        val message =
                            s"readPixels region (${c.x}, ${c.y}, ${c.w}x${c.h}) lies outside the " +
                                s"$extent drawing buffer"
                        discard(Sync.Unsafe.evalOrThrow(c.done.completeDiscard(
                            Result.fail(ThreeException.RenderFailure(message, new Exception(message)))
                        )))
                    else
                        val buf = new scala.scalajs.js.typedarray.Uint8Array(c.w * c.h * 4)
                        discard(gl.readPixels(c.x, c.y, c.w, c.h, gl.RGBA, gl.UNSIGNED_BYTE, buf))
                        val bytes = new Array[Byte](c.w * c.h * 4)
                        var i     = 0
                        // hot-path: a per-pixel byte copy inside the render submit's existing FFI block;
                        // no fresh per-tick effect allocation.
                        while i < bytes.length do
                            bytes(i) = (buf(i).toInt & 0xff).toByte
                            i += 1
                        discard(Sync.Unsafe.evalOrThrow(c.done.completeDiscard(Result.succeed(Span.from(bytes)))))
                    end if
                catch
                    case scala.util.control.NonFatal(e) =>
                        discard(Sync.Unsafe.evalOrThrow(c.done.completeDiscard(
                            Result.fail(ThreeException.RenderFailure("readPixels failed (GL error)", e))
                        )))
            }
        end drain

        /** CLOSES the queue and completes every still-pending capture as `RenderFailure`; called from the
          * mount's scope-close finalizer. The close is terminal: once shut, `enqueue` fails a late capture
          * on arrival rather than queueing it, so no readPixels can await a drain that will never run.
          */
        def failAll(reason: String)(using Frame, AllowUnsafe): Unit =
            val batch = pending.getAndSet(Absent).getOrElse(Chunk.empty)
            batch.foreach { c =>
                discard(Sync.Unsafe.evalOrThrow(c.done.completeDiscard(
                    Result.fail(ThreeException.RenderFailure(reason, new Exception(reason)))
                )))
            }
        end failAll

        /** The number of captures still pending; 0 once the queue is closed. A read-only seam the
          * deterministic teardown-race test reads to confirm a readPixels capture is enqueued before it
          * closes the mount scope.
          */
        def pendingCount(using AllowUnsafe): Int =
            pending.get().fold(0)(_.size)

        /** Whether the queue has been closed by teardown. */
        def isClosed(using AllowUnsafe): Boolean =
            pending.get().isEmpty
    end MountCaptureQueue

    private[kyo] object MountCaptureQueue:
        final case class Capture(x: Int, y: Int, w: Int, h: Int, done: Promise[Span[Byte], Abort[ThreeException]])

        /** The failure a capture receives when it reaches an already-closed queue. */
        private[kyo] val closedMessage: String =
            "readPixels on a closed mount: the render loop has stopped, so no capture can be drained"

        /** Builds a fresh, open, empty queue under the caller's propagated `AllowUnsafe`; the sanctioned
          * construction seam, never `embrace.danger` at field-init scope.
          */
        def init(using AllowUnsafe): MountCaptureQueue =
            new MountCaptureQueue(AtomicRef.Unsafe.init(Present(Chunk.empty[Capture])))
    end MountCaptureQueue

    /** Resolves the `<canvas>` at `selector`; `CanvasNotFound` when no element matches. */
    private[kyo] def resolveCanvas(selector: String)(using Frame): js.Dynamic < (Sync & Abort[ThreeException]) =
        // Unsafe: a DOM query that runs once at mount; deferred so it stays inside the effect.
        Sync.Unsafe.defer(Maybe(dom.document.querySelector(selector))).map {
            case Present(el) => el.asInstanceOf[js.Dynamic]: js.Dynamic < (Sync & Abort[ThreeException])
            case Absent      => Abort.fail(ThreeException.CanvasNotFound(selector))
        }

    /** Acquires a `WebGLRenderer` into the canvas under Scope; `WebGLUnavailable` on no GL context. */
    private[kyo] def makeRenderer(canvas: js.Dynamic, disposedRef: SignalRef[Boolean])(using
        Frame
    ): js.Dynamic < (Scope & Sync & Abort[ThreeException]) =
        Scope.acquireRelease(
            // Unsafe: constructing the WebGLRenderer. three.js signals an unavailable GL context two ways:
            // its constructor throws ("Error creating WebGL context") when no context can be created, OR it
            // returns a renderer whose getContext() is null. Catch the constructor throw so the acquire
            // yields a Maybe the map below turns into the typed WebGLUnavailable leaf (never a raw throw the
            // caller's Abort[ThreeException] row cannot see), and the release disposes only a constructed one.
            Sync.Unsafe.defer {
                val opts = js.Dynamic.literal(canvas = canvas, antialias = true)
                try Maybe(js.Dynamic.newInstance(ThreeFacade.WebGLRenderer)(opts))
                catch case scala.util.control.NonFatal(_) => Absent
            }
        ) { rendererMaybe =>
            // Unsafe: release the renderer's GL resources and its WebGL context on scope close. dispose()
            // frees the renderer's own GPU resources; forceContextLoss() releases the underlying context
            // so a mount/unmount cycle does not leak contexts toward the browser's per-page WebGL limit.
            Sync.Unsafe.defer {
                rendererMaybe.foreach { renderer =>
                    discard(renderer.dispose())
                    discard(renderer.forceContextLoss())
                }
                // disposed fires EXACTLY once here, from this single renderer release, strictly after
                // the real GL release and in the same block, so the ordering holds regardless of how
                // the enclosing scope schedules its finalizers.
                disposedRef.unsafe.set(true)
            }
        }.map {
            case Absent            => Abort.fail(ThreeException.WebGLUnavailable("could not create a WebGL context for the canvas"))
            case Present(renderer) =>
                // Unsafe: a null GL context surfaces as the typed WebGLUnavailable leaf, never a raw throw.
                Sync.Unsafe.defer(Maybe(renderer.getContext())).map {
                    case Present(_) => renderer: js.Dynamic < (Scope & Sync & Abort[ThreeException])
                    case Absent     => Abort.fail(ThreeException.WebGLUnavailable("no WebGL context for the canvas"))
                }
        }

    /** Records the render `camera`'s live object against its AST node in the mount's live map so the
      * bound-prop subscription (`subscribeRegions` live, `fillBoundRefsOnce` headless) sees its
      * signal-bound `lookAt`/`position` and re-aims the live camera on each emission, and a path-indexing
      * backend can address it for a server-driven camera SetProp. The camera drives the view but is not
      * part of the rendered scene graph, so it is recorded here rather than by the reconciler's scene walk.
      */
    private[kyo] def recordCamera(mounted: Reconciler.Mounted, camera: Three.Ast.Camera, cam: js.Dynamic)(using Frame): Unit < Sync =
        // Unsafe: a synchronous write of the mount's live map; safe because it runs once at mount on the
        // mount's own fiber before any bound-prop observe fiber fires.
        Sync.Unsafe.defer(mounted.live.update(new Reconciler.IdentityKey(camera), new Reconciler.Live(cam, camera, Chunk.empty)))

    /** True on a server-driven (hydrated) page, where the inline client installs the `__kyoPostBackendEvent`
      * post seam and the server re-materializes structural regions over the wire; false for a client-local
      * `runMount`/`embed` (no page WS) or a headless test. The reactive-region subscription reads this to
      * keep the server the SINGLE writer of a server-driven region (no racing local watcher).
      */
    private[kyo] def serverSeamPresent(using Frame): Boolean < Sync =
        // Unsafe: a one-shot read of the inline-client post seam on the live window; deferred so the FFI
        // read stays inside the effect.
        Sync.Unsafe.defer {
            import AllowUnsafe.embrace.danger
            js.typeOf(js.Dynamic.global.window) != "undefined" &&
            !js.isUndefined(js.Dynamic.global.window.__kyoPostBackendEvent)
        }

    /** Forks one observe fiber per `Bound.Ref` region: each emission patches exactly the one bound
      * object; a targeted patch, scoped to the ambient Scope so teardown interrupts it. A failed or
      * panicking observe fiber surfaces as a `Log.error` (except `Interrupted`, which signals normal
      * scope close).
      */
    private[kyo] def subscribeRegions(mounted: Reconciler.Mounted)(using Frame): Unit < (Async & Scope) =
        Kyo.foreachDiscard(boundRefs(mounted))(forkBoundRef)

    /** Subscribes every `Bound.Ref` prop on a freshly-materialized subtree: the element-materialized
      * hook the reconciler runs for each reactive/foreach child, under that element's scope. Forks
      * one observe fiber per triple so a reactive-region child's reactive props update on emission,
      * and the fibers dispose with the element. This is what makes the prop-level and structural
      * reactivity grains compose: a `Bound.Ref` on a node inside a `reactive`/`foreach` region binds
      * like one at the root.
      */
    private[kyo] def subscribeSubtreeBoundRefs(live: Reconciler.Live)(using Frame): Unit < (Async & Scope) =
        Kyo.foreachDiscard(subtreeBoundRefs(live))(forkBoundRef)

    /** Forks one observe fiber for a single `(live, patch, signal)` triple under the ambient scope:
      * each emission applies the targeted patch on the bound live object. A failed or panicking fiber
      * surfaces as a `Log.error` (except `Interrupted`, which signals normal scope close).
      */
    private def forkBoundRef(
        triple: (Reconciler.Live, Any => js.Dynamic => Unit, Signal[Any])
    )(using Frame): Unit < (Async & Scope) =
        val (live, patch, signal) = triple
        Fiber.init {
            Abort.run[Throwable] {
                // Unsafe: `patchProp` mutates the one bound live three.js object synchronously with no
                // suspension; `Sync.Unsafe.defer` lifts that FFI write into the observe callback's row. Safe
                // because each triple's fiber owns its own live object and applies exactly one targeted patch.
                signal.observe(value => Sync.Unsafe.defer(Reconciler.patchProp(live, patch(value)(_))))
            }.map { result =>
                result.fold(
                    _ => (): Unit < Sync,
                    err => Log.error(s"Reactive region fiber failed: ${err.getMessage}"),
                    panic =>
                        if panic.isInstanceOf[Interrupted] then (): Unit < Sync
                        else Log.error(s"Reactive region fiber panicked", panic)
                )
            }
        }.unit
    end forkBoundRef

    /** Applies every `Bound.Ref` prop's current signal value once, the one-shot analog of
      * [[subscribeRegions]] for a headless single-frame capture: reads each region's signal current
      * value and patches exactly the one bound live object, so a captured frame shows each reactive
      * prop at its current value rather than its materialize seed.
      */
    private[kyo] def fillBoundRefsOnce(mounted: Reconciler.Mounted)(using Frame): Unit < (Async & Scope) =
        Kyo.foreachDiscard(boundRefs(mounted)) { case (live, patch, signal) =>
            signal.current.map { value =>
                // Unsafe: a synchronous one-shot FFI write of the current signal value onto the one bound
                // live object, no suspension; safe because it patches exactly that object on this fiber.
                Sync.Unsafe.defer(Reconciler.patchProp(live, patch(value)(_)))
            }
        }

    /** Fills every structural reactive region (`Three.reactive`/`render` and
      * `Three.foreach`/`foreachKeyed`) from its signal's current value, then forks one watcher fiber
      * per region that re-reconciles on every subsequent change. The synchronous initial fill
      * guarantees the first rendered frame is already populated; a `Reactive` region swaps its one
      * subtree on a change and a `Foreach` region diffs by key so an unchanged segment reuses its
      * live object (the GPU buffers survive). New live objects materialize under the mount scope so
      * the mount close disposes them; each watcher is forked under the ambient Scope so teardown
      * interrupts it. A typed reconcile failure converts to a panic at this boundary so the row
      * matches the declared `(Async & Scope)`; a reached failure here is a reconciler bug, not a
      * recoverable case.
      *
      * `serverDriven` marks a mount whose structural regions the server re-materializes over the wire
      * (a hydrated `Three.embed` page): a region that carries a server drive is then owned by the
      * server's `ReplaceSubtree` via the backend drain, the SINGLE writer of that region's live state.
      * Forking a local watcher for it too would make the local reconcile fiber a SECOND writer racing
      * the drain (both dispose and re-materialize the same subtree, corrupting the path index), so it is
      * skipped. A region without a server drive (`Three.reactive(Signal[Three])`, unserializable) still
      * forks its local watcher even in a server-driven mount: the server cannot drive it.
      */
    private[kyo] def subscribeReactiveRegions(
        mounted: Reconciler.Mounted,
        serverDriven: Boolean = false
    )(using Frame): Unit < (Async & Scope) =
        // Unsafe: a synchronous write of the mount's hook fields and a read of the regions the root scene
        // declared, no suspension; safe because it runs once on the mount's drain fiber before any reactive
        // region fires.
        Sync.Unsafe.defer {
            // Install the element hook so the initial fill below AND every later re-materialization
            // subscribe each reactive-region child's Bound.Ref props under the child's own scope.
            mounted.subscribeElement = (live => subscribeSubtreeBoundRefs(live))
            // Install the region hook so a region materialized INSIDE another region's content is watched
            // the moment the reconciler fills it, under that element's own scope.
            mounted.subscribeRegion = (region => forkRegionWatcher(region, mounted, serverDriven))
            // The root scene's own regions, read BEFORE the fill: filling them registers whatever regions
            // their content declares, and those go through the hook above. Snapshotting first is what keeps
            // a nested region from being forked twice, once by its hook and once by this loop.
            Reconciler.reactiveRegions(mounted)
        }.map { rootRegions =>
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                // Root and nested regions take the SAME fill-then-subscribe path, so there is no second way
                // for a region to come alive and no shape of scene where one of them is only half-started.
                Kyo.foreachDiscard(rootRegions) { region =>
                    Reconciler.fillRegionOnce(region, mounted)
                        .andThen(forkRegionWatcher(region, mounted, serverDriven))
                }
            }
        }

    /** Forks the watcher fiber for one region under the ambient scope, unless the SERVER owns it.
      *
      * A server-driven mount re-materializes its structural regions over the wire, so a region carrying a
      * server drive already has exactly one writer: the backend's drain. Forking a local watcher for it too
      * would make the local reconcile fiber a SECOND writer racing that drain (both disposing and
      * re-materializing the same subtree, corrupting the path index). A region with no server drive
      * (`Three.reactive(Signal[Three])`, which is unserializable) still forks its watcher even on a
      * server-driven mount, because the server cannot drive it.
      */
    private def forkRegionWatcher(
        region: Reconciler.ReactiveRegion,
        mounted: Reconciler.Mounted,
        serverDriven: Boolean
    )(using Frame): Unit < (Async & Scope) =
        val serverOwned = serverDriven && (region.node match
            case r: Three.Ast.Reactive   => r.serverDrive.isDefined
            case _: Three.Ast.Foreach[?] => true
            case _                       => false)
        if serverOwned then Kyo.unit
        else
            Fiber.init {
                Abort.run[Throwable](Reconciler.runReactiveRegion(region, mounted)).map { result =>
                    result.fold(
                        _ => (): Unit < Sync,
                        err => Log.error(s"Reactive region fiber failed: ${err.getMessage}"),
                        panic =>
                            if panic.isInstanceOf[Interrupted] then (): Unit < Sync
                            else Log.error(s"Reactive region fiber panicked", panic)
                    )
                }
            }.unit
        end if
    end forkRegionWatcher

    /** Runs the frame loop: per tick advance the `Tick`, run every `onFrame` closure inline, then
      * render once per tick. Each tick applies its closures' mutations before the render submit, so
      * the frame reflects this tick's state. The render submit is a tight FFI call, not a per-tick
      * effect allocation.
      */
    private[kyo] def runLoop(
        mounted: Reconciler.Mounted,
        root: Reconciler.Live,
        camera: js.Dynamic,
        renderer: js.Dynamic,
        frames: ThreeFrames,
        rendersRef: SignalRef[Long],
        captures: MountCaptureQueue,
        controls: Chunk[js.Dynamic] = Chunk.empty
    )(using Frame): Unit < (Async & Scope & Abort[ThreeException]) =
        for
            frameRef <- AtomicLong.init(0L)
            now0     <- Clock.now
            startRef <- AtomicRef.init(now0)
            lastRef  <- AtomicRef.init(now0)
            submit = renderSubmit(root, camera, renderer, rendersRef, captures, controls)
            tick   = oneTick(mounted, frameRef, startRef, lastRef, submit)
            _ <- frames match
                case ThreeFrames.Raf                => rafLoop(tick)
                case ThreeFrames.Clock(interval)    => Clock.repeatAtInterval(interval)(tick).map(_.get)
                case ThreeFrames.Manual(withDriver) => withDriver(manualDriver(tick)).unit
        yield ()

    /** Executes one frame from the live clock: advance the `Tick`, run every `onFrame` closure once
      * inline, then submit once. The closures complete before `submit` runs, so the rendered frame
      * observes this tick's mutations.
      */
    private def oneTick(
        mounted: Reconciler.Mounted,
        frameRef: AtomicLong,
        startRef: AtomicRef[Instant],
        lastRef: AtomicRef[Instant],
        submit: Unit < Sync
    )(using Frame): Unit < (Async & Abort[ThreeException]) =
        for
            now   <- Clock.now
            start <- startRef.get
            last  <- lastRef.getAndSet(now)
            frame <- frameRef.getAndIncrement
            tick = Three.Tick(now - start, now - last, frame)
            _ <- runFrame(mounted, tick, submit)
        yield ()

    /** The one per-tick body shared by the live loop and the deterministic driver: run every
      * `onFrame` closure once inline and awaited, then submit once. Running inline before the submit
      * guarantees the frame reflects this tick's mutations.
      */
    private def runFrame(
        mounted: Reconciler.Mounted,
        tick: Three.Tick,
        submit: Unit < Sync
    )(using Frame): Unit < (Async & Abort[ThreeException]) =
        Kyo.foreachDiscard(onFrameClosures(mounted))(f => f(tick).unit).andThen(submit)

    /** The live render submit: a tight FFI call to `renderer.render`, never a fresh per-tick effect. When
      * the mount bound any `OrbitControls`, each one's `update()` runs first (required for `autoRotate` and
      * damping), then the single render submit reflects the updated camera.
      */
    private[kyo] def renderSubmit(
        root: Reconciler.Live,
        camera: js.Dynamic,
        renderer: js.Dynamic,
        rendersRef: SignalRef[Long],
        captures: MountCaptureQueue,
        controls: Chunk[js.Dynamic] = Chunk.empty
    )(using Frame): Unit < Sync =
        // Unsafe: the per-tick render submit is a tight FFI call, so it allocates no fresh effect per
        // frame. The renders increment and the capture drain fold into this one block and run strictly
        // after renderer.render: the frame is committed first, then the count rises, then the pending
        // captures read the framebuffer that commit just produced. The per-tick closures run before it.
        Sync.Unsafe.defer {
            var i = 0
            while i < controls.size do
                val _ = controls(i).update()
                i += 1
            val _ = renderer.render(root.obj, camera)
            val _ = rendersRef.unsafe.getAndUpdate(_ + 1L)
            captures.drain(renderer)
        }

    /** A `Driver` advancing exactly one tick per `step` (the deterministic test seam). */
    private def manualDriver(tick: Unit < (Async & Abort[ThreeException]))(using Frame): Three.Driver =
        new Three.Driver:
            def step(delta: Duration)(using Frame): Unit < (Async & Abort[ThreeException]) = tick

    /** Builds the deterministic [[Three.Driver]] the `testDriver` entry point returns. Per `step`
      * the driver runs the same per-tick body as the live loop with a constructed `Tick(delta, delta,
      * 0L)`, then calls the render seam. Closures run inline so assertions in the test see the
      * mutations before `step` returns.
      */
    private[kyo] def makeDriver(
        mounted: Reconciler.Mounted,
        root: Reconciler.Live,
        camera: js.Dynamic
    )(using Frame): Three.Driver < (Async & Scope) =
        new Three.Driver:
            def step(delta: Duration)(using Frame): Unit < (Async & Abort[ThreeException]) =
                runFrame(mounted, Three.Tick(delta, delta, 0L), ThreeMount.submitSeam(root, camera))

    /** The render-submit seam the per-tick tests observe (a no-op stand-in the real submit replaces). */
    private[kyo] def submitSeam(root: Reconciler.Live, camera: js.Dynamic)(using Frame): Unit < Sync =
        // No-op render seam: tests observe or count this call; the live path uses renderSubmit instead.
        Sync.defer(())

    /** What a raycast hit means in SERVER mode: where the pointer event goes, or why it goes nowhere.
      *
      * The three cases are distinguishable, rather than a judgment call, because of one fact:
      * `Raycasting.interactiveTargets` casts ONLY against nodes that declare a pointer handler. A light, a
      * group, a plain mesh, an orbit-controls holder: none of them is a raycast target at all, so none can
      * reach here. Every hit already carries `onClick`, `onPointerOver`, or `onPointerOut`. That is what
      * separates an object that merely has nothing to run for THIS kind of event from one that has
      * something to run and cannot be reached.
      */
    private[kyo] enum PointerRoute derives CanEqual:
        /** The hit object declares a handler for this event kind and has an indexed path: post it. */
        case Post(path: Seq[String])

        /** The hit object declares no handler for THIS kind. It is a legitimate raycast target (it declares
          * some other pointer handler, which is why the ray hit it at all) and the server has nothing to run
          * for this event, so dropping it is exactly right and posting it would be a wasted round trip. This
          * is the ordinary case: a click on a hover-only object, a hover over a click-only one.
          */
        case NoHandler

        /** The hit object declares a handler for this kind and NO path resolves to it, so the event cannot
          * be addressed and the object can never respond. The user clicks or hovers a live, interactive
          * thing and nothing happens, forever, while the rest of the scene keeps working: the pointer-path
          * twin of a frozen prop on an animating object. A defect, and the only case here that must speak.
          */
        case Unaddressable
    end PointerRoute

    /** Routes a raycast hit in server mode. See [[PointerRoute]] for why the cases are decidable.
      *
      * The handler check comes FIRST, and that is what keeps the two modes honest: the client-local branch
      * runs a handler only if the node declares one, so the server branch posts only what a declared handler
      * would answer. An indexed object with no handler for this kind routes to `NoHandler`, not to a post
      * the server would silently discard.
      */
    private[kyo] def pointerRoute(mounted: Reconciler.Mounted, live: Reconciler.Live, kind: PointerKind): PointerRoute =
        val declared = live.node match
            case i: Three.Ast.Interactive => Three.handlerFor(i, kind).nonEmpty
            case _                        => false
        if !declared then PointerRoute.NoHandler
        else
            mounted.pathForLive(live) match
                case Present(path) => PointerRoute.Post(path)
                case Absent        => PointerRoute.Unaddressable
        end if
    end pointerRoute

    /** Reports a pointer event that landed on an object which declares a handler for it but has no indexed
      * path.
      *
      * Names the SOURCE POSITION the node was declared at, because that is the only handle a reader has:
      * the object has no path (that is the bug) and no id. A page's index covers the scene it mounted, so
      * an object missing from it was materialized into a region the index never learned about.
      *
      * Reported once per MOUNT, declaration site and event kind (the set lives on `Reconciler.Mounted`). A
      * dead object is re-hit on every click and every drag across it, so a warning that repeated would teach
      * people to ignore the log; but a set that outlived the mount would silence the NEXT mount's genuine
      * drop at a site this one already reported, and pages re-hydrate and suites mount scene after scene.
      */
    private[kyo] def reportUnaddressablePointer(mounted: Reconciler.Mounted, node: Three, kind: PointerKind)(using
        Frame
    ): Unit < Sync =
        val site = node.frame.position.show
        val what = kind match
            case PointerKind.Click => "a click"
            case PointerKind.Over  => "a pointer-over"
            case PointerKind.Out   => "a pointer-out"
        Sync.defer(mounted.reportedPointerSites.add(s"$site|$kind")).map { first =>
            if first then
                Log.warn(
                    s"kyo-threejs dropped $what on the object declared at $site: it declares a handler for that " +
                        "event but no path resolves to it, so the event cannot reach the server and this object will " +
                        "never respond to one. Every other object in the scene keeps working, which is what makes " +
                        "this invisible."
                )
            else Kyo.unit
        }
    end reportUnaddressablePointer

    /** The inline client's post seam, present only on a server-driven (hydrated) page. `Absent` selects the
      * client-local branch: a `runMount`, an embed with no page WS, or a headless test.
      */
    private[kyo] def postSeam(using Frame): Maybe[js.Dynamic] < Sync =
        // Unsafe: a one-shot read of the inline-client post seam on the live window; "undefined" outside a
        // DOM/island-server context.
        Sync.Unsafe.defer {
            import AllowUnsafe.embrace.danger
            (if js.typeOf(js.Dynamic.global.window) != "undefined"
                 && !js.isUndefined(js.Dynamic.global.window.__kyoPostBackendEvent)
             then Present(js.Dynamic.global.window)
             else Absent): Maybe[js.Dynamic]
        }

    /** True when `host` sits inside an `Element.clientOwned` boundary, read from the `data-kyo-client-owned`
      * marker kyo-ui stamps on the boundary element. The same marker the inline client's own guard walks for,
      * read the same way (up the ancestors), so the two halves of the page cannot disagree about who owns a
      * subtree.
      */
    private[kyo] def hostIsClientOwned(host: js.Dynamic)(using AllowUnsafe): Boolean =
        // Unsafe: a read-only DOM ancestor walk on the live host element. `closest` is absent on a stub
        // element (a headless test) and on a detached node, and both mean "no boundary above me".
        !js.isUndefined(host.closest) &&
            host.closest("[data-kyo-client-owned]") != null

    /** Where this mount's pointer events go: `Present(window)` posts them to the session, `Absent` runs the
      * handler locally.
      *
      * Decided by OWNERSHIP, not merely by the presence of the post seam. A page can be server-driven (the
      * seam is installed) and still hand THIS subtree to the browser via `Element.clientOwned`, and then the
      * session neither subscribes the region nor renders from the signals its own handler would write. Posting
      * there means the click runs against signals nothing renders, while the local closure never runs at all:
      * the object is live and interactive and answers nothing, on either side, with no log. So a client-owned
      * host runs its handlers locally, which is the whole point of marking it client-owned.
      *
      * Fixed for the life of the mount: the boundary is static markup above the host, so this is read once.
      */
    private[kyo] def pointerSink(host: js.Dynamic)(using Frame): Maybe[js.Dynamic] < Sync =
        postSeam.map { seam =>
            Sync.Unsafe.defer {
                seam match
                    case Present(w) if !hostIsClientOwned(host) => Present(w)
                    case _                                      => Absent
            }
        }

    /** Coalesces the hover stream the SESSION hears down to at most one transition per animation frame.
      *
      * A pointermove fires up to a thousand times a second on a high-rate pointer, and while
      * [[hoverTransition]] already suppresses moves WITHIN one object, the pointer resting on an object's
      * silhouette genuinely alternates hit and miss with sub-pixel jitter: every one of those is a real
      * enter or leave, so a time-based throttle would DROP true transitions. Coalescing per frame cannot:
      * it holds the LATEST target and emits the transition from what the session was last told to what the
      * pointer is actually on when the frame ends. Intermediate targets crossed and left within a single
      * frame were never visible to the user and never reach the wire, and the state the session ends up
      * holding is exactly the state on screen.
      *
      * ONLY the server sink coalesces, and that asymmetry is a decision, not an oversight. Client-local mode
      * has no wire to protect, so it dispatches every transition inline as it always has. The consequence is
      * that an object entered and left INSIDE one frame runs its handlers locally and never reaches the
      * session. Making the two modes symmetric would mean either flooding the socket or silently dropping
      * local handler calls that work today, and both are worse than an asymmetry that is invisible at the
      * frame rate the user actually sees.
      *
      * Single-owner: touched only from the pointer listener and its own frame flush, both on the JS event
      * loop thread.
      */
    final private[kyo] class HoverCoalescer:
        // The latest raycast result, overwritten by every move; the frame flush reads whatever is here.
        private var latest: Maybe[(Reconciler.Live, Three.Pointer)] = Absent
        // What the SESSION was last told the pointer is on. The flush diffs against this, not against the
        // previous move, so a target entered and left inside one frame cancels out.
        private var posted: Maybe[Reconciler.Live] = Absent

        /** Records the newest hit; the next flush will carry it. */
        private[kyo] def record(hit: Maybe[(Reconciler.Live, Three.Pointer)]): Unit = latest = hit

        /** The (leave, enter) pair the session must hear now, and the pointer to carry, advancing the posted
          * state. Returns no events when the pointer ended the frame on the same object the session already
          * has, which is the common case for a move that never left an object.
          */
        private[kyo] def flush(): (Maybe[Reconciler.Live], Maybe[Reconciler.Live], Three.Pointer) =
            val target              = latest.map(_._1)
            val (fireOut, fireOver) = hoverTransition(posted, target)
            val pointer = latest.map(_._2).getOrElse(Three.Pointer(Three.Vec3.zero, 0.0, (0.0, 0.0), Three.Pointer.Buttons.none))
            posted = target
            (fireOut, fireOver, pointer)
        end flush
    end HoverCoalescer

    /** Dispatches ONE resolved pointer hit, in whichever mode the page is in. Every pointer event, click and
      * hover alike, goes through here, and that is the point.
      *
      * A server-driven page re-evaluates the builder per connection, so the client's tree holds DIFFERENT
      * `Signal` instances from the session's. A handler run locally there would write a signal nothing
      * renders: the user acts, nothing happens, and nothing is logged. So in server mode the event is
      * addressed to the session by path, and the session's own handler runs against the signals it actually
      * renders from. Client-local mode has no session and its tree is the only tree, so the closure runs in
      * place. A handler that only one of the two modes reaches is the bug this seam exists to make
      * impossible.
      *
      * `seam` is an argument rather than a read of the ambient global, so the mode is decided once per event
      * at the listener boundary and both modes are drivable from a test.
      */
    private[kyo] def dispatchPointer(
        mounted: Reconciler.Mounted,
        events: Channel[Any < Async],
        live: Reconciler.Live,
        pointer: Three.Pointer,
        kind: PointerKind,
        seam: Maybe[js.Dynamic]
    )(using Frame): Unit < Sync =
        seam match
            case Present(w) =>
                ThreeMount.pointerRoute(mounted, live, kind) match
                    case PointerRoute.Post(path) =>
                        // Unsafe: hands the encoded event to the inline client's post seam.
                        Sync.Unsafe.defer(discard(w.__kyoPostBackendEvent(path.toJSArray, PointerWire.encode(kind, pointer))))
                    case PointerRoute.NoHandler     => Kyo.unit
                    case PointerRoute.Unaddressable => ThreeMount.reportUnaddressablePointer(mounted, live.node, kind)
            case Absent =>
                // Unsafe: enqueues the local closure's effect on the scoped drain fiber; the offer is a
                // synchronous non-suspending write to a bounded channel.
                Sync.Unsafe.defer {
                    live.node match
                        case i: Three.Ast.Interactive =>
                            // A node with no handler for THIS kind is still a legitimate hit (it declares some
                            // other pointer handler, which is why the ray hit it), and doing nothing is right.
                            Three.handlerFor(i, kind).foreach(f => discard(events.unsafe.offer(f(pointer))))
                        // Unreachable: `Raycasting.interactiveTargets` casts only against `Interactive` nodes,
                        // so a hit never resolves to anything else. Kept for the total match, not as a case
                        // that can drop an event.
                        case _ => ()
                }
    end dispatchPointer

    /** Wires capture-phase pointer listeners on the canvas: `pointerdown` dispatches `onClick`,
      * `pointermove` tracks the current hit mesh and dispatches `onPointerOver` on enter and
      * `onPointerOut` on leave. All three take the same [[dispatchPointer]] seam, so a hover behaves on a
      * server-driven page exactly as a click does. Each client-local handler effect is enqueued on a scoped
      * drain fiber; the listeners are removed on scope close via `Scope.ensure`.
      *
      * The sink is resolved ONCE, from the host's ownership (see [[pointerSink]]), not per event from the
      * ambient post seam: whether this subtree belongs to the session or to the browser is a property of the
      * markup above the canvas and cannot change while the mount lives.
      */
    private[kyo] def setupPointerDelegation(
        canvas: js.Dynamic,
        mounted: Reconciler.Mounted,
        camera: js.Dynamic
    )(using Frame): Unit < (Async & Scope) =
        for
            events <- Channel.init[Any < Async](256)
            _ <- Fiber.init(Loop.foreach(Abort.runPartial[Closed](events.take).map {
                case Result.Success(eff) => eff.andThen(Loop.continue)
                case Result.Failure(_)   => Loop.done
            }))
            sink <- ThreeMount.pointerSink(canvas)
            clickHandler = (evt: dom.PointerEvent) =>
                // Unsafe: JS event callback crossing into Kyo effect; evalOrThrow runs the Sync effect synchronously.
                import AllowUnsafe.embrace.danger
                val ndc = ThreeMount.toNdc(canvas, evt)
                discard(Sync.Unsafe.evalOrThrow(Abort.runPartial[Closed](
                    Raycasting.hit(mounted, camera, ndc).map {
                        case Present((live, pointer)) =>
                            ThreeMount.dispatchPointer(mounted, events, live, pointer, PointerKind.Click, sink)
                        // The ray hit nothing: the user clicked empty space. Silence is the whole point here.
                        case Absent => Kyo.unit
                    }
                ).unit))
            // Unsafe: register a capture-phase pointerdown listener; removed on scope close.
            _ <- Sync.Unsafe.defer(canvas.addEventListener("pointerdown", clickHandler, true))
            _ <- Scope.ensure(Sync.Unsafe.defer(canvas.removeEventListener("pointerdown", clickHandler, true)))
            // The client-local hover cursor: the target of the PREVIOUS move. Server mode does not use it,
            // because there the transition is diffed against what the session was last told, not against the
            // last move (see [[HoverCoalescer]]).
            currentHitLiveRef <- AtomicRef.init(Maybe.empty[Reconciler.Live])
            hover = new ThreeMount.HoverCoalescer
            // The flush the frame schedules in SERVER mode: emit at most one hover transition per frame,
            // carrying whatever the pointer ended the frame on.
            flushHover = () =>
                // Unsafe: the frame callback crossing back into Kyo; evalOrThrow runs the Sync effect inline.
                import AllowUnsafe.embrace.danger
                discard(Sync.Unsafe.evalOrThrow(
                    Sync.defer(hover.flush()).map { (fireOut, fireOver, pointer) =>
                        val out = fireOut match
                            case Present(prev) =>
                                ThreeMount.dispatchPointer(mounted, events, prev, pointer, PointerKind.Out, sink)
                            case Absent => Kyo.unit
                        val over = fireOver match
                            case Present(next) =>
                                ThreeMount.dispatchPointer(mounted, events, next, pointer, PointerKind.Over, sink)
                            case Absent => Kyo.unit
                        // Leave fires before enter, so a handler that reads "what am I on now" sees the
                        // object the pointer is on, not the one it just left.
                        out.andThen(over)
                    }
                ))
            pendingFlush <- AtomicRef.init(false)
            moveHandler = (evt: dom.PointerEvent) =>
                // Unsafe: JS event callback crossing into Kyo effect; evalOrThrow runs the Sync effect synchronously.
                import AllowUnsafe.embrace.danger
                val ndc = ThreeMount.toNdc(canvas, evt)
                discard(Sync.Unsafe.evalOrThrow(Abort.runPartial[Closed](
                    Raycasting.hit(mounted, camera, ndc).map { hitResult =>
                        sink match
                            case Present(_) =>
                                // SERVER mode: record the newest hit and make sure a flush is scheduled for the
                                // next frame. Every move overwrites the target, so a burst of moves inside one
                                // frame costs ONE transition on the wire and not one per move.
                                Sync.defer(hover.record(hitResult)).andThen {
                                    pendingFlush.getAndSet(true).map { alreadyScheduled =>
                                        if alreadyScheduled then Kyo.unit
                                        else
                                            Sync.Unsafe.defer {
                                                discard(dom.window.requestAnimationFrame { (_: Double) =>
                                                    Sync.Unsafe.evalOrThrow(pendingFlush.set(false))
                                                    flushHover()
                                                })
                                            }
                                    }
                                }
                            case Absent =>
                                // CLIENT-LOCAL mode: no wire, so no reason to defer. Run the transition inline,
                                // exactly as it always has, against the previous move's target.
                                val newLive: Maybe[Reconciler.Live] = hitResult.map(_._1)
                                currentHitLiveRef.getAndSet(newLive).map { prevLive =>
                                    val pointer = hitResult match
                                        case Present((_, p)) => p
                                        case Absent          => Three.Pointer(Three.Vec3(0, 0, 0), 0.0, ndc, Three.Pointer.Buttons.none)
                                    val (fireOut, fireOver) = ThreeMount.hoverTransition(prevLive, newLive)
                                    val out = fireOut match
                                        case Present(prev) =>
                                            ThreeMount.dispatchPointer(mounted, events, prev, pointer, PointerKind.Out, Absent)
                                        case Absent => Kyo.unit
                                    val over = fireOver match
                                        case Present(next) =>
                                            ThreeMount.dispatchPointer(mounted, events, next, pointer, PointerKind.Over, Absent)
                                        case Absent => Kyo.unit
                                    out.andThen(over)
                                }
                        end match
                    }
                ).unit))
            // Unsafe: register a capture-phase pointermove listener for hover tracking; removed on scope close.
            _ <- Sync.Unsafe.defer(canvas.addEventListener("pointermove", moveHandler, true))
            _ <- Scope.ensure(Sync.Unsafe.defer(canvas.removeEventListener("pointermove", moveHandler, true)))
        yield ()

    /** Binds a live three.js `OrbitControls` instance for each `Three.Ast.Controls` node in the mounted
      * scene: `new OrbitControls(camera, canvas)` over the live camera
      * and the mount canvas, applies the node's `enableZoom`/`enablePan`/`enableRotate`/`autoRotate`/
      * `target` fields, and registers `controls.dispose()` on `Scope` close (the same Scope the renderer
      * and listeners bind to), so a mount/unmount cycle leaks no controls listener. Returns the live
      * controls objects so the frame loop calls `controls.update()` once per frame (required for
      * `autoRotate` and for damping). A scene with no `Controls` node binds nothing and returns empty.
      *
      * One camera drives the view, so if a scene declares more than one `controls` node the first binds and
      * the rest are logged and skipped; the guard keeps a misuse from stacking conflicting controls on one
      * camera.
      */
    private[kyo] def setupControls(
        canvas: js.Dynamic,
        mounted: Reconciler.Mounted,
        camera: js.Dynamic
    )(using Frame): Chunk[js.Dynamic] < (Async & Scope) =
        val nodes = controlsNodes(mounted)
        if nodes.isEmpty then (Chunk.empty[js.Dynamic]: Chunk[js.Dynamic] < (Async & Scope))
        else
            val first = nodes.head
            if nodes.size > 1 then
                Log.warn(s"Three.controls: ${nodes.size} controls nodes in one scene; binding the first, ignoring the rest")
                    .andThen(bindOneControls(canvas, camera, first).map(Chunk(_)))
            else
                bindOneControls(canvas, camera, first).map(Chunk(_))
            end if
        end if
    end setupControls

    /** Collects every `Three.Ast.Controls` node from the mounted live map (in no particular order; one
      * controls node per scene is the supported shape, the guard in [[setupControls]] handles more).
      */
    private def controlsNodes(mounted: Reconciler.Mounted): Chunk[Three.Ast.Controls] =
        var buf = Chunk.empty[Three.Ast.Controls]
        mounted.live.values.foreach { live =>
            live.node match
                case c: Three.Ast.Controls => buf = buf.appended(c)
                case _                     => ()
        }
        buf
    end controlsNodes

    /** Constructs and configures one live `OrbitControls` over `camera` and `canvas` from the `Controls`
      * AST node, registering its dispose on `Scope` close. The `target` (a `Bound.Const` from the
      * `Three.controls` factory) seeds the orbit center.
      */
    private def bindOneControls(
        canvas: js.Dynamic,
        camera: js.Dynamic,
        node: Three.Ast.Controls
    )(using Frame): js.Dynamic < (Async & Scope) =
        for
            controls <- Scope.acquireRelease(
                // Unsafe: constructing the OrbitControls over the live camera and canvas, applying the node's
                // flags. OrbitControls attaches its own pointer/wheel listeners on the canvas; dispose() (the
                // release below) removes them, so the mount Scope owns the listener lifecycle.
                Sync.Unsafe.defer {
                    import AllowUnsafe.embrace.danger
                    val controls = js.Dynamic.newInstance(orbitControlsCtor)(camera, canvas)
                    controls.enableZoom = node.enableZoom
                    controls.enablePan = node.enablePan
                    controls.enableRotate = node.enableRotate
                    // A reactive autoRotate seeds `false`; the subscription forked below applies the signal's
                    // current value immediately, then each subsequent emission.
                    controls.autoRotate = node.autoRotate match
                        case Bound.Const(v) => v
                        case Bound.Ref(_)   => false
                    val t = node.target match
                        case Bound.Const(v) => v
                        case Bound.Ref(_)   => Three.Vec3.zero
                    discard(controls.target.set(t.x, t.y, t.z))
                    discard(controls.update())
                    controls
                }
            ) { controls =>
                // Unsafe: dispose the controls on Scope close, removing its canvas listeners (no leak).
                Sync.Unsafe.defer(discard(controls.dispose()))
            }
            _ <- node.autoRotate match
                case Bound.Ref(signal) => forkAutoRotate(controls, signal)
                case Bound.Const(_)    => Kyo.unit
        yield controls
    end bindOneControls

    /** Forks one observe fiber mirroring a reactive `autoRotate` signal onto the live `OrbitControls`: each
      * emission sets `controls.autoRotate`, and the frame loop's per-frame `update()` applies it, so toggling
      * the signal turns the camera orbit on and off with no scene rebuild. Scoped to the ambient (mount)
      * Scope so teardown interrupts it; a failed or panicking fiber surfaces as a `Log.error` (except
      * `Interrupted`, which signals normal scope close).
      */
    private def forkAutoRotate(controls: js.Dynamic, signal: Signal[Boolean])(using Frame): Unit < (Async & Scope) =
        Fiber.init {
            Abort.run[Throwable] {
                // Unsafe: a synchronous FFI write of the emitted flag onto the one bound controls object, no
                // suspension; the frame loop's update() reads it on the next frame. Safe because this fiber
                // owns the write to this one controls object.
                signal.observe(on => Sync.Unsafe.defer(controls.autoRotate = on))
            }.map { result =>
                result.fold(
                    _ => (): Unit < Sync,
                    err => Log.error(s"Controls autoRotate fiber failed: ${err.getMessage}"),
                    panic =>
                        if panic.isInstanceOf[Interrupted] then (): Unit < Sync
                        else Log.error(s"Controls autoRotate fiber panicked", panic)
                )
            }
        }.unit
    end forkAutoRotate

    /** The `OrbitControls` constructor from the examples/jsm facade, read once at bind time. */
    private def orbitControlsCtor: js.Dynamic =
        kyo.internal.OrbitControlsFacade.OrbitControls

    /** Decides the hover transition between two consecutive pointer hits. Returns `(fireOut,
      * fireOver)`: the live object to dispatch `onPointerOut` on (the one left) and the one to
      * dispatch `onPointerOver` on (the one entered). Compares the underlying three.js object
      * identity, not the `Maybe` wrapper, so re-hitting the same mesh on a later move is not a
      * crossing and fires neither handler.
      */
    private[kyo] def hoverTransition(
        prev: Maybe[Reconciler.Live],
        next: Maybe[Reconciler.Live]
    ): (Maybe[Reconciler.Live], Maybe[Reconciler.Live]) =
        val sameTarget = (prev, next) match
            case (Present(p), Present(n)) => p.obj eq n.obj
            case (Absent, Absent)         => true
            case _                        => false
        if sameTarget then (Absent, Absent) else (prev, next)
    end hoverTransition

    /** Converts a pointer event to normalized device coordinates (-1..1 on each axis) for the
      * canvas.
      */
    private[kyo] def toNdc(canvas: js.Dynamic, evt: dom.PointerEvent)(using AllowUnsafe): (Double, Double) =
        // Unsafe: reading the canvas bounding rect to map client coords into NDC.
        val rect = canvas.getBoundingClientRect()
        val x    = (evt.clientX - rect.left.asInstanceOf[Double]) / rect.width.asInstanceOf[Double] * 2 - 1
        val y    = -((evt.clientY - rect.top.asInstanceOf[Double]) / rect.height.asInstanceOf[Double]) * 2 + 1
        (x, y)
    end toNdc

    private def rafLoop(tick: Unit < (Async & Abort[ThreeException]))(using Frame): Unit < (Async & Abort[ThreeException]) =
        Loop.foreach {
            tick.andThen(rafYield).map(_ => Loop.continue)
        }

    private def rafYield(using Frame): Unit < Async =
        // Unsafe: requestAnimationFrame schedules the next tick; bridged to a fiber-completing callback.
        Promise.init[Unit, Any].map { p =>
            val _ = dom.window.requestAnimationFrame { (_: Double) =>
                import AllowUnsafe.embrace.danger
                p.unsafe.completeUnitDiscard()
            }
            p.get
        }

    /** Walks the materialized live map and collects every `Bound.Ref` prop as a `(live, patchFn,
      * signal)` triple. The patch function closes over the property navigation so each emission
      * applies exactly one targeted FFI setter on the bound live object (targeted mutation, no scene
      * rebuild).
      */
    private[kyo] def boundRefs(mounted: Reconciler.Mounted): Chunk[(Reconciler.Live, Any => js.Dynamic => Unit, Signal[Any])] =
        var buf = Chunk.empty[(Reconciler.Live, Any => js.Dynamic => Unit, Signal[Any])]
        mounted.live.values.foreach { live =>
            buf = buf.concat(extractBoundRefs(live))
        }
        buf
    end boundRefs

    /** Collects every `Bound.Ref` triple on a live subtree (the node plus its descendants), for the
      * element-materialized subscription hook walking one freshly-materialized reactive-region element.
      */
    private def subtreeBoundRefs(
        live: Reconciler.Live
    ): Chunk[(Reconciler.Live, Any => js.Dynamic => Unit, Signal[Any])] =
        extractBoundRefs(live).concat(live.children.flatMap(subtreeBoundRefs))

    /** Walks the materialized live map and collects the `onFrame` hook for every `Animated` node. */
    private[kyo] def onFrameClosures(mounted: Reconciler.Mounted): Chunk[Three.Tick => Any < Async] =
        var buf = Chunk.empty[Three.Tick => Any < Async]
        mounted.live.values.foreach { live =>
            live.node match
                case m: Three.Ast.Mesh =>
                    m.props.onFrame.foreach(f => buf = buf.appended(f))
                case c: Three.Ast.Custom[?] =>
                    c.props.onFrame.foreach(f => buf = buf.appended(f))
                case g: Three.Ast.Group =>
                    g.props.onFrame.foreach(f => buf = buf.appended(f))
                case _ => ()
        }
        buf
    end onFrameClosures

    /** Extracts `(live, patchFn, signal)` triples for each `Bound.Ref` prop on a single live node. */
    private def extractBoundRefs(live: Reconciler.Live): Chunk[(Reconciler.Live, Any => js.Dynamic => Unit, Signal[Any])] =
        var buf = Chunk.empty[(Reconciler.Live, Any => js.Dynamic => Unit, Signal[Any])]

        def add[A](signal: Signal[A], patch: A => js.Dynamic => Unit): Unit =
            buf = buf.appended((live, patch.asInstanceOf[Any => js.Dynamic => Unit], signal.asInstanceOf[Signal[Any]]))

        def addColor(b: Bound[Three.Color], navigate: js.Dynamic => js.Dynamic): Unit =
            b match
                case Bound.Ref(sig) => add(
                        sig,
                        (c: Three.Color) =>
                            (obj: js.Dynamic) =>
                                val _ = navigate(obj).set(c.packed.toDouble)
                    )
                case _ => ()

        def addNormal(b: Bound[Three.Normal], set: (js.Dynamic, Double) => Unit): Unit =
            b match
                case Bound.Ref(sig) => add(sig, (n: Three.Normal) => (obj: js.Dynamic) => set(obj, n.toDouble))
                case _              => ()

        def addDouble(b: Bound[Double], set: (js.Dynamic, Double) => Unit): Unit =
            b match
                case Bound.Ref(sig) => add(sig, (d: Double) => (obj: js.Dynamic) => set(obj, d))
                case _              => ()

        def addVec3(b: Maybe[Bound[Three.Vec3]], navigate: js.Dynamic => js.Dynamic): Unit =
            b.foreach {
                case Bound.Ref(sig) =>
                    add(
                        sig,
                        (v: Three.Vec3) =>
                            (obj: js.Dynamic) =>
                                val _ = navigate(obj).set(v.x, v.y, v.z)
                    )
                case _ => ()
            }

        live.node match
            case m: Three.Ast.Mesh =>
                addVec3(m.props.transform.position, _.position)
                addVec3(m.props.transform.rotation, _.rotation)
                addVec3(m.props.transform.scale, _.scale)
                m.material match
                    case mat: Three.Ast.Material.Basic =>
                        addColor(mat.color, _.material.color)
                        addNormal(
                            mat.opacity,
                            (obj, v) =>
                                obj.material.opacity = v; obj.material.transparent = v < 1.0
                        )
                    case mat: Three.Ast.Material.Standard =>
                        addColor(mat.color, _.material.color)
                        addNormal(
                            mat.opacity,
                            (obj, v) =>
                                obj.material.opacity = v; obj.material.transparent = v < 1.0
                        )
                        addNormal(mat.metalness, (obj, v) => obj.material.metalness = v)
                        addNormal(mat.roughness, (obj, v) => obj.material.roughness = v)
                        addColor(mat.emissive, _.material.emissive)
                    case mat: Three.Ast.Material.Line =>
                        addColor(mat.color, _.material.color)
                        addNormal(
                            mat.opacity,
                            (obj, v) =>
                                obj.material.opacity = v; obj.material.transparent = v < 1.0
                        )
                    case mat: Three.Ast.Material.Points =>
                        addColor(mat.color, _.material.color)
                        addNormal(
                            mat.opacity,
                            (obj, v) =>
                                obj.material.opacity = v; obj.material.transparent = v < 1.0
                        )
                    case _ => ()
                end match
            case c: Three.Ast.Custom[?] =>
                addVec3(c.props.transform.position, _.position)
                addVec3(c.props.transform.rotation, _.rotation)
                addVec3(c.props.transform.scale, _.scale)
            case l: Three.Ast.Light.Ambient =>
                addColor(l.color, _.color)
                addDouble(l.intensity, (obj, v) => obj.intensity = v)
            case l: Three.Ast.Light.Directional =>
                addColor(l.color, _.color)
                addDouble(l.intensity, (obj, v) => obj.intensity = v)
                addVec3(l.props.position, _.position)
            case l: Three.Ast.Light.Point =>
                addColor(l.color, _.color)
                addDouble(l.intensity, (obj, v) => obj.intensity = v)
                addVec3(l.props.position, _.position)
            case l: Three.Ast.Light.Spot =>
                addColor(l.color, _.color)
                addDouble(l.intensity, (obj, v) => obj.intensity = v)
                addVec3(l.props.position, _.position)
            case l: Three.Ast.Light.Hemisphere =>
                addColor(l.sky, _.color)
                addColor(l.ground, _.groundColor)
                addDouble(l.intensity, (obj, v) => obj.intensity = v)
            case g: Three.Ast.Group =>
                addVec3(g.props.transform.position, _.position)
                addVec3(g.props.transform.rotation, _.rotation)
                addVec3(g.props.transform.scale, _.scale)
            case cam: Three.Ast.Camera.Perspective =>
                // The factory stores the position in the camera's transform, so a factory
                // position = Bound.Ref(...) and an explicit .position() setter both bind on
                // the same field makeCamera reads to seed the live camera.
                addVec3(cam.transform.position, _.position)
                cam.lookAt match
                    case Bound.Ref(sig) =>
                        add(
                            sig,
                            (v: Three.Vec3) =>
                                (obj: js.Dynamic) =>
                                    // Unsafe: re-aiming the camera toward a reactive lookAt target; called after
                                    // every position update so orientation stays correct.
                                    val _ = obj.lookAt(v.x, v.y, v.z)
                        )
                    case _ => ()
                end match
            case cam: Three.Ast.Camera.Orthographic =>
                // Same as Perspective: cam.transform.position covers both the factory position
                // param and any explicit .position() setter.
                addVec3(cam.transform.position, _.position)
                cam.lookAt match
                    case Bound.Ref(sig) =>
                        add(
                            sig,
                            (v: Three.Vec3) =>
                                (obj: js.Dynamic) =>
                                    // Unsafe: re-aiming the orthographic camera toward a reactive lookAt target.
                                    val _ = obj.lookAt(v.x, v.y, v.z)
                        )
                    case _ => ()
                end match
            case _ => ()
        end match

        buf
    end extractBoundRefs

end ThreeMount
