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
            cam <- ThreeFacadeOps.makeCamera(camera)
            _   <- ThreeMount.subscribeRegions(mounted)
            _   <- ThreeMount.subscribeReactiveRegions(mounted)
            _   <- ThreeMount.setupPointerDelegation(canvas, mounted, cam)
            _   <- ThreeMount.runLoop(mounted, rootLive, cam, renderer, frames)
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
        UI.host("canvas") { canvas =>
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
                            cam.aspect = w / h
                            val _ = cam.updateProjectionMatrix()
                        end if
                    }
                    _ <- ThreeMount.subscribeRegions(mounted)
                    _ <- ThreeMount.subscribeReactiveRegions(mounted)
                    _ <- ThreeMount.setupPointerDelegation(canvasDyn, mounted, cam)
                    _ <- Fiber.init {
                        Abort.run[ThreeException](ThreeMount.runLoop(mounted, rootLive, cam, renderer, frames)).map {
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
        }
    end embed

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
    def subscribeRegions(mounted: Reconciler.Mounted)(using Frame): Unit < (Async & Scope) =
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
    def fillBoundRefsOnce(mounted: Reconciler.Mounted)(using Frame): Unit < (Async & Scope) =
        Kyo.foreachDiscard(boundRefs(mounted)) { case (live, patch, signal) =>
            signal.current.map { value =>
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
    def subscribeReactiveRegions(mounted: Reconciler.Mounted)(using Frame): Unit < (Async & Scope) =
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
    def runLoop(
        mounted: Reconciler.Mounted,
        root: Reconciler.Live,
        camera: js.Dynamic,
        renderer: js.Dynamic,
        frames: ThreeFrames
    )(using Frame): Unit < (Async & Scope & Abort[ThreeException]) =
        for
            frameRef <- AtomicLong.init(0L)
            now0     <- Clock.now
            startRef <- AtomicRef.init(now0)
            lastRef  <- AtomicRef.init(now0)
            submit = renderSubmit(root, camera, renderer)
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

    /** The live render submit: a tight FFI call to `renderer.render`, never a fresh per-tick effect. */
    private def renderSubmit(root: Reconciler.Live, camera: js.Dynamic, renderer: js.Dynamic)(using Frame): Unit < Sync =
        // Unsafe: the per-tick render submit is a tight FFI call: no fresh effect per frame.
        Sync.Unsafe.defer { val _ = renderer.render(root.obj, camera) }

    /** A `Driver` advancing exactly one tick per `step` (the deterministic test seam). */
    private def manualDriver(tick: Unit < (Async & Abort[ThreeException]))(using Frame): Three.Driver =
        new Three.Driver:
            def step(delta: Duration)(using Frame): Unit < (Async & Abort[ThreeException]) = tick

    /** Builds the deterministic [[Three.Driver]] the `testDriver` entry point returns. Per `step`
      * the driver runs the same per-tick body as the live loop with a constructed `Tick(delta, delta,
      * 0L)`, then calls the render seam. Closures run inline so assertions in the test see the
      * mutations before `step` returns.
      */
    def makeDriver(
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
    def setupPointerDelegation(
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
