package kyo

import kyo.internal.GltfLoader
import kyo.internal.Image
import kyo.internal.Raycasting
import kyo.internal.Reconciler
import kyo.internal.TextureLoader
import kyo.internal.ThreeFacade
import kyo.internal.ThreeFacadeOps
import org.scalajs.dom
import scala.scalajs.js
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
    )(using Frame): Unit < (Async & Scope & Abort[ThreeException]) =
        for
            canvas      <- ThreeMount.resolveCanvas(selector)
            renderer    <- ThreeMount.makeRenderer(canvas)
            mountResult <- Reconciler.mount(scene)
            (rootLive, mounted) = mountResult
            cam      <- ThreeFacadeOps.makeCamera(camera)
            _        <- ThreeMount.subscribeRegions(mounted)
            _        <- ThreeMount.subscribeReactiveRegions(mounted)
            _        <- ThreeMount.setupPointerDelegation(canvas, mounted, cam)
            controls <- ThreeMount.setupControls(canvas, mounted, cam)
            _        <- ThreeMount.runLoop(mounted, rootLive, cam, renderer, frames, controls)
        yield ()

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
            // A headless single-frame capture fills every structural reactive region and every
            // prop-level `Bound.Ref` from its signal's current value, so a reactive scene renders
            // its current state (no live loop).
            _      <- Reconciler.fillReactiveRegionsOnce(mounted)
            _      <- ThreeMount.fillBoundRefsOnce(mounted)
            pixels <- ThreeToImage.renderToPixels(renderer, target, rootLive, cam, width, height)
            bytes  <- ThreeToImage.encodePng(pixels, width, height)
        yield Image.fromBinary(bytes)

    /** Embeds `scene` as a first-class child of a kyo-ui tree: returns a kyo-ui host node
      * whose `<canvas>` kyo-ui lays out and renders on every runner, and into which the 3D
      * scene mounts at page mount and disposes at page teardown (client-side). The renderer,
      * reconciler, GL contexts, and pointer listeners bind to the page mount Scope and are
      * released at teardown. The frame loop runs as a fiber forked under the page Scope: no
      * leaked GL context, no orphaned frame loop; the loop fiber is interrupted when the
      * page Scope closes. Shared `SignalRef`s bridge exactly as in the side-by-side path.
      * Usage: `UI.div(controls, Three.embed(scene, camera), footer)`.
      */
    private[kyo] def embed(
        scene: Three,
        camera: Three.Ast.Camera,
        frames: ThreeFrames = ThreeFrames.Raf
    )(using Frame): UI.Ast.Host =
        // The client DomHostMount closure wires the GL pipeline on the client under UI.runMount: the
        // scene builds, animates, and raycasts locally (closures intact), the same pipeline runMount runs.
        UI.host("canvas") { canvas =>
            hostMountPipeline(scene, camera, frames, canvas)
        }
    end embed

    private def hostMountPipeline(
        scene: Three,
        camera: Three.Ast.Camera,
        frames: ThreeFrames,
        canvas: org.scalajs.dom.Element,
        onMounted: (Reconciler.Live, Reconciler.Mounted) => Unit < (Async & Scope & Abort[ThreeException]) =
            (_, _) => Kyo.unit
    )(using Frame): Unit < (Async & Scope) =
        // The host hands a live dom.Element; cast to js.Dynamic exactly as resolveCanvas
        // does, then run the runMount pipeline minus resolveCanvas on it, under the ambient
        // page mount Scope. The pipeline's Abort[ThreeException] is discharged to a Log.error
        // before the effect reaches the kyo-ui seam, so the seam stays Async & Scope (the
        // kyo-ui mount callback type is dom.Element => Unit < (Async & Scope)).
        val canvasDyn = canvas.asInstanceOf[js.Dynamic]
        val pipeline: Unit < (Async & Scope & Abort[ThreeException]) =
            for
                renderer    <- ThreeMount.makeRenderer(canvasDyn)
                mountResult <- Reconciler.mount(scene)
                (rootLive, mounted) = mountResult
                cam <- ThreeFacadeOps.makeCamera(camera)
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
                _        <- ThreeMount.subscribeRegions(mounted)
                _        <- ThreeMount.subscribeReactiveRegions(mounted)
                _        <- ThreeMount.setupPointerDelegation(canvasDyn, mounted, cam)
                controls <- ThreeMount.setupControls(canvasDyn, mounted, cam)
                _        <- onMounted(rootLive, mounted)
                _ <- Fiber.init {
                    Abort.run[ThreeException](ThreeMount.runLoop(mounted, rootLive, cam, renderer, frames, controls)).map {
                        case Result.Success(_) => (): Unit < Sync
                        case Result.Failure(e) => Log.error(s"Three.embed frame loop failed: ${e.getMessage}")
                        case Result.Panic(e) =>
                            if e.isInstanceOf[Interrupted] then (): Unit < Sync
                            else Log.error("Three.embed frame loop panicked", e)
                    }
                }.unit
            yield ()
        Abort.run[ThreeException](pipeline).map {
            case Result.Success(_) => (): Unit < Sync
            case Result.Failure(e) => Log.error(s"Three.embed mount failed: ${e.getMessage}")
            case Result.Panic(e) =>
                if e.isInstanceOf[Interrupted] then (): Unit < Sync
                else Log.error("Three.embed mount panicked", e)
        }
    end hostMountPipeline

    /** The client feed receiver: wires a per-signal-id mirror into the existing inbound `HostUpdate`
      * routing. Registers a receiver on
      * `window.__kyoHostChannels[id]` (the SAME registry the inline kyo-ui clientJs routes a `HostUpdate`
      * into, `HtmlRenderer.scala:771-799`, with the same late-registration flush) that decodes an inbound
      * `HostPayload.SignalUpdate` for this `id`, decodes its `encoded` payload with `Schema[A]`, and writes
      * the value into `mirror`. The scene mount already forked a `forkBoundRef` observe fiber for `mirror`
      * (the user bound it with `.color(mirror)`/`.position(mirror)`), so the write drives exactly one
      * targeted `patchProp` on the one bound live node. A malformed payload, a non-`SignalUpdate` leaf, or
      * a decode failure is a silent no-op (the fire-and-forget feed policy). The receiver is dropped on
      * `Scope` close so a closed page leaves no stale entry.
      */
    private[kyo] def connectFeed[A: Schema](id: String, mirror: SignalRef[A])(using Frame): Unit < (Async & Scope) =
        // Unsafe: a JS-callback bridge from the inbound WS onmessage routing into the mirror SignalRef.
        // evalOrThrow runs the mirror write synchronously, the same Sync.Unsafe convention as
        // connectFeedChunk and the pointer listeners.
        import AllowUnsafe.embrace.danger
        val rx: js.Function1[js.Any, Unit] = (payload: js.Any) =>
            val json = js.JSON.stringify(payload)
            Json.decode[kyo.internal.HostPayload](json) match
                case Result.Success(kyo.internal.HostPayload.SignalUpdate(sid, encoded)) if sid == id =>
                    Json.decode[A](encoded) match
                        case Result.Success(value) => Sync.Unsafe.evalOrThrow(mirror.set(value))
                        case _                     => ()
                case _ => ()
            end match
        for
            // Unsafe: registers the receiver on the live `window.__kyoHostChannels` registry by id; a
            // synchronous DOM write deferred so it stays inside the effect.
            _ <- Sync.Unsafe.defer {
                val w = dom.window.asInstanceOf[js.Dynamic]
                if js.isUndefined(w.__kyoHostChannelRegister) then
                    if js.isUndefined(w.__kyoHostChannels) then w.__kyoHostChannels = js.Dynamic.literal()
                    w.__kyoHostChannels.asInstanceOf[js.Dictionary[js.Any]].update(id, rx)
                else
                    discard(w.__kyoHostChannelRegister(id, rx))
                end if
            }
            // Unsafe: drops the receiver from the registry on Scope close so a remount leaks no channel.
            _ <- Scope.ensure(Sync.Unsafe.defer {
                val w = dom.window.asInstanceOf[js.Dynamic]
                if !js.isUndefined(w.__kyoHostChannels) then
                    discard(w.__kyoHostChannels.asInstanceOf[js.Dictionary[js.Any]].remove(id))
            })
        yield ()
        end for
    end connectFeed

    /** The client STRUCTURAL feed receiver: the structural analog of [[connectFeed]]. Registers a receiver
      * on `window.__kyoHostChannels[id]` (the
      * SAME registry, with the same late-registration flush) that decodes an inbound
      * `HostPayload.SignalChunk` for this `id`, decodes its `encoded` payload with `Schema[Chunk[A]]`, and
      * writes the whole snapshot into `mirror`. The scene bound `mirror` with `.foreachKeyed(key)(render)`,
      * so the write drives the client's own keyed reconciler (`subscribeReactiveRegions`), which diffs the
      * snapshot locally: an unchanged key reuses its live object (GPU buffers survive), a new key
      * materializes, a dropped key disposes. A malformed payload, a non-`SignalChunk` leaf, or a decode
      * failure is a silent no-op. The receiver is dropped on `Scope` close.
      */
    private[kyo] def connectFeedChunk[A: Schema](id: String, mirror: SignalRef[Chunk[A]])(using Frame): Unit < (Async & Scope) =
        // Unsafe: a JS-callback bridge from the inbound WS onmessage routing into the mirror SignalRef.
        // evalOrThrow runs the mirror write synchronously, the same Sync.Unsafe convention as
        // connectFeed and the pointer listeners.
        import AllowUnsafe.embrace.danger
        val rx: js.Function1[js.Any, Unit] = (payload: js.Any) =>
            val json = js.JSON.stringify(payload)
            Json.decode[kyo.internal.HostPayload](json) match
                case Result.Success(kyo.internal.HostPayload.SignalChunk(sid, encoded)) if sid == id =>
                    Json.decode[Chunk[A]](encoded) match
                        case Result.Success(value) => Sync.Unsafe.evalOrThrow(mirror.set(value))
                        case _                     => ()
                case _ => ()
            end match
        for
            // Unsafe: registers the receiver on the live `window.__kyoHostChannels` registry by id; a
            // synchronous DOM write deferred so it stays inside the effect.
            _ <- Sync.Unsafe.defer {
                val w = dom.window.asInstanceOf[js.Dynamic]
                if js.isUndefined(w.__kyoHostChannelRegister) then
                    if js.isUndefined(w.__kyoHostChannels) then w.__kyoHostChannels = js.Dynamic.literal()
                    w.__kyoHostChannels.asInstanceOf[js.Dictionary[js.Any]].update(id, rx)
                else
                    discard(w.__kyoHostChannelRegister(id, rx))
                end if
            }
            // Unsafe: drops the receiver from the registry on Scope close so a remount leaks no channel.
            _ <- Scope.ensure(Sync.Unsafe.defer {
                val w = dom.window.asInstanceOf[js.Dynamic]
                if !js.isUndefined(w.__kyoHostChannels) then
                    discard(w.__kyoHostChannels.asInstanceOf[js.Dictionary[js.Any]].remove(id))
            })
        yield ()
        end for
    end connectFeedChunk

    /** The client app-event POST: the client leg of `Three.Feed.emit`. Encodes `event` with `Schema[A]`,
      * then posts a `UIEvent.AppEvent(path, id,
      * encoded)` over the page's single WS via the inline kyo-ui client helper `window.__kyoPostAppEvent`
      * (installed by the page's clientJs). When that helper is not
      * present (called outside an island feed context: no page WS bound), the effect fails with the typed
      * `ThreeException.FeedUnavailable(id)` rather than dropping the event silently. The `path` is the host
      * path segments (empty for a root host); routing is by `id` server-side.
      */
    private[kyo] def postAppEvent[A: Schema](id: String, event: A)(using Frame): Unit < (Async & Abort[ThreeException]) =
        Sync.defer(Json.encode[A](event)).map { encoded =>
            Sync.Unsafe.defer {
                // Unsafe: a one-shot read of the inline-client post helper on the live window. The helper
                // sends the AppEvent over the page's single WS (or buffers it until open). Guard the global
                // `window` itself: outside a DOM context (a server/test call) there is no window, which is
                // the no-channel-bound case, surfaced as FeedUnavailable rather than a ReferenceError.
                import AllowUnsafe.embrace.danger
                // `js.typeOf(...)` keeps the global `window` selection on the LHS of a `.`-access (the only
                // legal way to probe the global scope on Scala.js); "undefined" means no DOM context.
                if js.typeOf(js.Dynamic.global.window) == "undefined" then Maybe.empty[Unit]
                else
                    val w = js.Dynamic.global.window
                    if js.isUndefined(w.__kyoPostAppEvent) then Maybe.empty[Unit]
                    else
                        discard(w.__kyoPostAppEvent(js.Array[String](), id, encoded))
                        Present(())
                    end if
                end if
            }.map {
                case Present(_) => Kyo.unit
                case Absent     => Abort.fail(ThreeException.FeedUnavailable(id))
            }
        }
    end postAppEvent

    /** Resolves the `<canvas>` at `selector`; `CanvasNotFound` when no element matches. */
    def resolveCanvas(selector: String)(using Frame): js.Dynamic < (Sync & Abort[ThreeException]) =
        // Unsafe: a DOM query that runs once at mount; deferred so it stays inside the effect.
        Sync.Unsafe.defer(Maybe(dom.document.querySelector(selector))).map {
            case Present(el) => el.asInstanceOf[js.Dynamic]: js.Dynamic < (Sync & Abort[ThreeException])
            case Absent      => Abort.fail(ThreeException.CanvasNotFound(selector))
        }

    /** Acquires a `WebGLRenderer` into the canvas under Scope; `WebGLUnavailable` on no GL context. */
    def makeRenderer(canvas: js.Dynamic)(using Frame): js.Dynamic < (Scope & Sync & Abort[ThreeException]) =
        Scope.acquireRelease(
            // Unsafe: constructing the WebGLRenderer; if the context is null three.js throws, mapped below.
            Sync.Unsafe.defer {
                val opts = js.Dynamic.literal(canvas = canvas, antialias = true)
                js.Dynamic.newInstance(ThreeFacade.WebGLRenderer)(opts)
            }
        ) { renderer =>
            // Unsafe: release the renderer's GL resources and its WebGL context on scope close. dispose()
            // frees the renderer's own GPU resources; forceContextLoss() releases the underlying context
            // so a mount/unmount cycle does not leak contexts toward the browser's per-page WebGL limit.
            Sync.Unsafe.defer {
                discard(renderer.dispose())
                discard(renderer.forceContextLoss())
            }
        }.map { renderer =>
            // Unsafe: a null GL context surfaces as the typed WebGLUnavailable leaf, never a raw throw.
            Sync.Unsafe.defer(Maybe(renderer.getContext())).map {
                case Present(_) => renderer: js.Dynamic < (Scope & Sync & Abort[ThreeException])
                case Absent     => Abort.fail(ThreeException.WebGLUnavailable("no WebGL context for the canvas"))
            }
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
      */
    private[kyo] def subscribeReactiveRegions(mounted: Reconciler.Mounted)(using Frame): Unit < (Async & Scope) =
        // Unsafe: a synchronous write of the mount's `subscribeElement` hook field, no suspension; safe
        // because it runs once on the mount's drain fiber before any reactive region fires.
        Sync.Unsafe.defer {
            // Install the element hook so the initial fill below AND every later re-materialization
            // subscribe each reactive-region child's Bound.Ref props under the child's own scope.
            mounted.subscribeElement = (live => subscribeSubtreeBoundRefs(live))
        }.andThen {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                Reconciler.fillReactiveRegionsOnce(mounted)
            }
        }.andThen {
            Kyo.foreachDiscard(Reconciler.reactiveRegions(mounted)) { region =>
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
            }
        }

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
        controls: Chunk[js.Dynamic] = Chunk.empty
    )(using Frame): Unit < (Async & Scope & Abort[ThreeException]) =
        for
            frameRef <- AtomicLong.init(0L)
            now0     <- Clock.now
            startRef <- AtomicRef.init(now0)
            lastRef  <- AtomicRef.init(now0)
            submit = renderSubmit(root, camera, renderer, controls)
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
    private def renderSubmit(
        root: Reconciler.Live,
        camera: js.Dynamic,
        renderer: js.Dynamic,
        controls: Chunk[js.Dynamic] = Chunk.empty
    )(using Frame): Unit < Sync =
        // Unsafe: the per-tick render submit is a tight FFI call: no fresh effect per frame.
        Sync.Unsafe.defer {
            var i = 0
            while i < controls.size do
                val _ = controls(i).update()
                i += 1
            val _ = renderer.render(root.obj, camera)
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

    /** Wires capture-phase pointer listeners on the canvas: `pointerdown` dispatches `onClick`,
      * `pointermove` tracks the current hit mesh and dispatches `onPointerOver` on enter and
      * `onPointerOut` on leave. Each handler effect is enqueued on a scoped drain fiber; the
      * listeners are removed on scope close via `Scope.ensure`.
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
            clickHandler = (evt: dom.PointerEvent) =>
                // Unsafe: JS event callback crossing into Kyo effect; evalOrThrow runs the Sync effect synchronously.
                import AllowUnsafe.embrace.danger
                val ndc = ThreeMount.toNdc(canvas, evt)
                discard(Sync.Unsafe.evalOrThrow(Abort.runPartial[Closed](
                    Raycasting.hit(mounted, camera, ndc).map {
                        case Present((live, pointer)) =>
                            live.node match
                                case i: Three.Ast.Interactive =>
                                    i.meshProps.onClick.foreach(f =>
                                        discard(events.unsafe.offer(f(pointer)))
                                    )
                                case _ => ()
                        case Absent => ()
                    }
                ).unit))
            // Unsafe: register a capture-phase pointerdown listener; removed on scope close.
            _                 <- Sync.Unsafe.defer(canvas.addEventListener("pointerdown", clickHandler, true))
            _                 <- Scope.ensure(Sync.Unsafe.defer(canvas.removeEventListener("pointerdown", clickHandler, true)))
            currentHitLiveRef <- AtomicRef.init(Maybe.empty[Reconciler.Live])
            moveHandler = (evt: dom.PointerEvent) =>
                // Unsafe: JS event callback crossing into Kyo effect; evalOrThrow runs the Sync effect synchronously.
                import AllowUnsafe.embrace.danger
                val ndc = ThreeMount.toNdc(canvas, evt)
                discard(Sync.Unsafe.evalOrThrow(Abort.runPartial[Closed](
                    Raycasting.hit(mounted, camera, ndc).map { hitResult =>
                        val newLive: Maybe[Reconciler.Live] = hitResult match
                            case Present((live, _)) => Present(live)
                            case Absent             => Absent
                        currentHitLiveRef.getAndSet(newLive).map { prevLive =>
                            val pointer = hitResult match
                                case Present((_, p)) => p
                                case Absent          => Pointer(Vec3(0, 0, 0), 0.0, ndc, Pointer.Buttons.none)
                            val (fireOut, fireOver) = ThreeMount.hoverTransition(prevLive, newLive)
                            fireOut.foreach { prev =>
                                prev.node match
                                    case i: Three.Ast.Interactive =>
                                        i.meshProps.onPointerOut.foreach(f =>
                                            discard(events.unsafe.offer(f(pointer)))
                                        )
                                    case _ => ()
                            }
                            fireOver.foreach { next =>
                                next.node match
                                    case i: Three.Ast.Interactive =>
                                        i.meshProps.onPointerOver.foreach(f =>
                                            discard(events.unsafe.offer(f(pointer)))
                                        )
                                    case _ => ()
                            }
                        }
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
        Scope.acquireRelease(
            // Unsafe: constructing the OrbitControls over the live camera and canvas, applying the node's
            // flags. OrbitControls attaches its own pointer/wheel listeners on the canvas; dispose() (the
            // release below) removes them, so the mount Scope owns the listener lifecycle.
            Sync.Unsafe.defer {
                import AllowUnsafe.embrace.danger
                val controls = js.Dynamic.newInstance(ThreeFacade_OrbitControls)(camera, canvas)
                controls.enableZoom = node.enableZoom
                controls.enablePan = node.enablePan
                controls.enableRotate = node.enableRotate
                controls.autoRotate = node.autoRotate
                val t = node.target match
                    case Bound.Const(v) => v
                    case Bound.Ref(_)   => Vec3.zero
                discard(controls.target.set(t.x, t.y, t.z))
                discard(controls.update())
                controls
            }
        ) { controls =>
            // Unsafe: dispose the controls on Scope close, removing its canvas listeners (no leak).
            Sync.Unsafe.defer(discard(controls.dispose()))
        }
    end bindOneControls

    /** The `OrbitControls` constructor from the examples/jsm facade, read once at bind time. */
    private def ThreeFacade_OrbitControls: js.Dynamic =
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

        def addColor(b: Bound[Color], navigate: js.Dynamic => js.Dynamic): Unit =
            b match
                case Bound.Ref(sig) => add(
                        sig,
                        (c: Color) =>
                            (obj: js.Dynamic) =>
                                val _ = navigate(obj).set(c.packed.toDouble)
                    )
                case _ => ()

        def addNormal(b: Bound[Normal], set: (js.Dynamic, Double) => Unit): Unit =
            b match
                case Bound.Ref(sig) => add(sig, (n: Normal) => (obj: js.Dynamic) => set(obj, n.toDouble))
                case _              => ()

        def addDouble(b: Bound[Double], set: (js.Dynamic, Double) => Unit): Unit =
            b match
                case Bound.Ref(sig) => add(sig, (d: Double) => (obj: js.Dynamic) => set(obj, d))
                case _              => ()

        def addVec3(b: Maybe[Bound[Vec3]], navigate: js.Dynamic => js.Dynamic): Unit =
            b.foreach {
                case Bound.Ref(sig) =>
                    add(
                        sig,
                        (v: Vec3) =>
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
                            (v: Vec3) =>
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
                            (v: Vec3) =>
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
