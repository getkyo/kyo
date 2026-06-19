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
        // The server bridge carries the scene's server-side bindings so the server-push runner can
        // observe the host's signals and route picks. The client DomHostMount closure (below) is
        // unchanged in shape; it wires the GL pipeline on the client under UI.runMount AND, under
        // UI.runHandlers, observes the per-slot client mirror the island channel feeds.
        val host = UI.host("canvas") { canvas =>
            hostMountPipeline(scene, camera, frames, canvas)
        }
        host.withServerBridge(ThreeMount.serverBridge(scene, camera))
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
                _ <- onMounted(rootLive, mounted)
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
    end hostMountPipeline

    /** Reads the inline boot init for a host element: parses the nested
      * `<script type="application/json" data-kyo-host-init>` data island the SSR page emitted (a
      * `Json`-encoded [[kyo.internal.HostPayload]] boot payload) and reconstitutes the client scene
      * with one mirror `SignalRef` per prop slot, plus the server's actual camera (from the `Boot`
      * envelope's [[kyo.internal.CameraDescriptor]]) and frame mode. A host with no init island, a
      * malformed payload, or a legacy payload that carries no camera falls back to the default
      * perspective camera, so a malformed page mounts an empty scene rather than throwing.
      */
    private[kyo] def readHostInit(el: org.scalajs.dom.Element)(using Frame): kyo.internal.HostInit < Sync =
        // Unsafe: a one-shot DOM read of the nested init script's text content at mount.
        Sync.Unsafe.defer {
            val script = Maybe(el.querySelector("[data-kyo-host-init]"))
            script.map(_.textContent).getOrElse("")
        }.map { json =>
            val payload =
                if json.isEmpty then emptyBoot
                else
                    Json.decode[kyo.internal.HostPayload](json) match
                        case Result.Success(p) => p
                        case _                 => emptyBoot
            ThreeBridge.reconstitute(payload).map { case (scene, _) =>
                kyo.internal.HostInit(scene, bootCamera(payload), ThreeFrames.Raf)
            }
        }
    end readHostInit

    // The empty boot payload a missing or malformed init island falls back to: an empty scene with the
    // default perspective camera descriptor, so the host mounts nothing rather than throwing.
    private def emptyBoot(using Frame): kyo.internal.HostPayload =
        kyo.internal.HostPayload.Boot(
            kyo.internal.StructuralOp.Insert(ThreeBridge.rootId, 0, kyo.internal.SceneDescriptor("scene", Seq.empty, Seq.empty)),
            defaultCameraDescriptor
        )

    // The camera the boot payload carries, reconstituted via the bridge. A `Boot` envelope carries the
    // server's actual camera; any other payload shape (a bare structural insert, a legacy payload) falls
    // back to the default perspective camera.
    private def bootCamera(payload: kyo.internal.HostPayload)(using Frame): Three.Ast.Camera =
        payload match
            case kyo.internal.HostPayload.Boot(_, camera) => ThreeBridge.materializeCamera(camera)
            case _                                        => Three.Camera.perspective()

    // The serializable form of the default perspective camera, matching Three.Camera.perspective()'s
    // defaults (fov 75deg, near 0.1, far 1000, position (0,0,5), lookAt origin).
    private def defaultCameraDescriptor: kyo.internal.CameraDescriptor =
        kyo.internal.CameraDescriptor.Perspective(
            fovRadians = Radians.deg(75).toDouble,
            near = 0.1,
            far = 1000.0,
            position = kyo.internal.HostValue.V3(0.0, 0.0, 5.0),
            lookAt = kyo.internal.HostValue.V3(0.0, 0.0, 0.0)
        )

    /** The host's path segments, read from its `data-kyo-path` attribute (the same scheme the inline
      * clientJs routes a HostUpdate by). An empty attribute is the root path.
      */
    private[kyo] def hostPath(el: org.scalajs.dom.Element): Seq[String] =
        val attr = Maybe(el.getAttribute("data-kyo-path")).getOrElse("")
        if attr.isEmpty then Seq.empty else attr.split('.').toSeq

    /** Registers the per-path receiver the inline clientJs routes a HostUpdate into:
      * `window.__kyoHostChannels[path] = rx`, where `rx(payload)` decodes the JS payload to a
      * [[kyo.internal.HostPayload]] and applies it to the channel synchronously (one mirror write per
      * Prop). Idempotent per path: a re-register overwrites the same key (the mount runs once per host,
      * so this is the single registration).
      */
    private def registerChannelReceiver(
        host: org.scalajs.dom.Element,
        path: Seq[String],
        channel: HostChannel
    )(using Frame): Unit =
        // Unsafe: a JS-callback bridge from the WS onmessage handler into the channel. evalOrThrow runs
        // the channel apply synchronously, the same Sync.Unsafe convention as the pointer listeners.
        import AllowUnsafe.embrace.danger
        val rx: js.Function1[js.Any, Unit] = (payload: js.Any) =>
            val json = js.JSON.stringify(payload)
            Json.decode[kyo.internal.HostPayload](json) match
                case Result.Success(p) => Sync.Unsafe.evalOrThrow(channel.apply(p))
                case _                 => ()
        // Register through the inline clientJs helper, which sets the receiver AND flushes any
        // HostUpdate payloads the WS delivered for this path before the island registered: a host's
        // one-shot initial structure (a foreach's initial children) is pushed when the WS session
        // starts, which can precede the island mount, so a direct set would drop it. The flushing
        // register closes that startup race. Falls back to a direct set if the inline client predates
        // the helper.
        val w = dom.window.asInstanceOf[js.Dynamic]
        if js.isUndefined(w.__kyoHostChannelRegister) then
            val channels = window_kyoHostChannels()
            channels.update(path.mkString("."), rx)
        else
            discard(w.__kyoHostChannelRegister(path.mkString("."), rx))
        end if
    end registerChannelReceiver

    /** Drops the per-path receiver on scope close so a closed page leaves no stale entry. */
    private def unregisterChannelReceiver(
        host: org.scalajs.dom.Element,
        path: Seq[String]
    )(using Frame): Unit =
        // Unsafe: deletes the window.__kyoHostChannels entry for this host path on teardown.
        import AllowUnsafe.embrace.danger
        val channels = window_kyoHostChannels()
        discard(channels.remove(path.mkString(".")))
    end unregisterChannelReceiver

    // The window.__kyoHostChannels registry the inline clientJs initializes (a plain JS object the WS
    // onmessage handler reads to route a HostUpdate). Read here as a js.Dictionary so update/remove
    // by key are the Map-like Unit-returning ops, matching the inline clientJs's object access.
    private def window_kyoHostChannels()(using AllowUnsafe): js.Dictionary[js.Any] =
        // Unsafe: reads/initializes the shared window registry the inline clientJs owns.
        val w = dom.window.asInstanceOf[js.Dynamic]
        if js.isUndefined(w.__kyoHostChannels) then w.__kyoHostChannels = js.Dynamic.literal()
        w.__kyoHostChannels.asInstanceOf[js.Dictionary[js.Any]]
    end window_kyoHostChannels

    /** Wires the client raycast back-channel: registers a capture-phase pointerdown listener on the
      * host that posts a `HostPick` over the WS (via `window.__kyoPostPick`) instead of running a
      * client onClick (the reconstituted client scene carries no closures; the server owns them under
      * server-push). The pick names the host-root node and carries the pointer NDC; the server's
      * `onPick` resolves the closure. The listener is removed on scope close.
      */
    private def wirePickBackChannel(
        host: org.scalajs.dom.Element,
        path: Seq[String],
        channel: HostChannel
    )(using Frame): Unit < (Async & Scope) =
        val handler: js.Function1[dom.PointerEvent, Unit] = (evt: dom.PointerEvent) =>
            // Unsafe: a JS pointer-event callback posting a typed HostPick back over the WS.
            import AllowUnsafe.embrace.danger
            val hostDyn = host.asInstanceOf[js.Dynamic]
            val (ndcX, ndcY) =
                if js.isUndefined(hostDyn.getBoundingClientRect) then (0.0, 0.0)
                else ThreeMount.toNdc(hostDyn, evt)
            val w = dom.window.asInstanceOf[js.Dynamic]
            if !js.isUndefined(w.__kyoPostPick) then
                val pathArr = js.Array(path*)
                val pointer = js.Dynamic.literal(
                    pointX = 0.0,
                    pointY = 0.0,
                    pointZ = 0.0,
                    distance = 0.0,
                    ndcX = ndcX,
                    ndcY = ndcY
                )
                discard(w.__kyoPostPick(pathArr, ThreeBridge.rootId, pointer))
            end if
        for
            _ <- Sync.Unsafe.defer(host.addEventListener("pointerdown", handler, true))
            _ <- Scope.ensure(Sync.Unsafe.defer(host.removeEventListener("pointerdown", handler, true)))
        yield ()
        end for
    end wirePickBackChannel

    /** Builds the server-side HostBridge for a host scene: serverInit flattens the initial scene
      * to a SceneDescriptor; subscriptions observe each server-owned Signal[Chunk[A]] bound by
      * foreach/foreachKeyed and emit HostPayload.Structural by diffing the keyed children server-
      * side (pure over the declarative children + key fn, no GL), alongside the per-slot prop
      * pushes; onPick runs the user's onClick closure for the hit node. The closure stays
      * server-side; only the typed payload/event cross.
      */
    private[kyo] def serverBridge(scene: Three, camera: Three.Ast.Camera)(using Frame): UI.Ast.HostBridge =
        new UI.Ast.HostBridge:
            def serverInit(path: Seq[String]): kyo.internal.HostPayload < Sync =
                ThreeBridge.flattenInit(scene, camera)
            def subscriptions(
                path: Seq[String],
                emit: kyo.internal.HostPayload => Unit < Async
            )(using Frame): Unit < (Async & Scope) =
                ThreeBridge.observeProps(scene, emit).andThen(ThreeBridge.observeStructure(scene, emit))
            def onPick(
                path: Seq[String],
                nodeId: String,
                pointer: kyo.internal.PointerData
            )(using Frame): Unit < Async =
                ThreeBridge.runPick(scene, nodeId, pointer)
    end serverBridge

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

    /** Builds the per-host client channel: one mirror `SignalRef` per bound prop slot plus a
      * structural inbox. The client reconciler observes these mirrors (the same forkBoundRef /
      * subscribeReactiveRegions path it uses for a local signal), so an inbound HostUpdate that
      * writes a mirror drives exactly one targeted patchProp or one keyed splice. Holds for the page
      * lifetime under the ambient Scope: the mount runs once, no re-fire, and server pushes flow
      * through this channel rather than a re-mount.
      */
    private[kyo] def islandMount(
        host: org.scalajs.dom.Element,
        path: Seq[String],
        init: kyo.internal.HostInit
    )(using Frame): Unit < (Async & Scope & Abort[ThreeException]) =
        for
            channel <- HostChannel.init(init)
            // Register the per-path receiver the inline clientJs routes a HostUpdate into. Unsafe:
            // a JS-callback bridge from the WS onmessage handler into the channel; evalOrThrow runs
            // the apply synchronously (the same Sync.Unsafe convention as the pointer listeners).
            _ <- Sync.Unsafe.defer(registerChannelReceiver(host, path, channel))
            _ <- Scope.ensure(Sync.Unsafe.defer(unregisterChannelReceiver(host, path)))
            // Run the GL pipeline observing the channel's mirror signals (init seeded the scene). The
            // onMounted hook wires the channel's structural inbox to a client keyed splice once the live
            // root is materialized, so an inbound StructuralOp splices/removes/reorders a subtree on the
            // same channel without a re-mount.
            _ <- hostMountPipeline(
                init.scene,
                init.camera,
                init.frames,
                host,
                (rootLive, mounted) => subscribeStructuralInbox(channel, mounted, rootLive)
            )
            // Wire the client raycast to post a HostPick back over the WS instead of running onClick
            // locally (the server owns the closure under server-push).
            _ <- wirePickBackChannel(host, path, channel)
        yield ()
    end islandMount

    /** Resolves a host-subtree node id (`"r"`, `"r.0"`, `"r.2.1"`, the depth-first index path the
      * flatten/reconstitute scheme assigns) to its live object by walking `rootLive`'s children
      * positionally. The reconstituted client tree materializes children in AST order, so the same
      * index path that names a server node names the matching live node. A path that runs past the
      * live tree (a stale region id) resolves to `Absent`, so an op for an unknown region is a no-op.
      */
    private[kyo] def liveByNodeId(rootLive: Reconciler.Live, nodeId: String): Maybe[Reconciler.Live] =
        val parts = nodeId.split('.').toList
        parts match
            case "r" :: rest =>
                @scala.annotation.tailrec
                def walk(live: Reconciler.Live, indices: List[String]): Maybe[Reconciler.Live] =
                    indices match
                        case Nil => Present(live)
                        case head :: tail =>
                            head.toIntOption match
                                case Some(i) if i >= 0 && i < live.children.length =>
                                    walk(live.children(i), tail)
                                case _ => Absent
                walk(rootLive, rest)
            case _ => Absent
        end match
    end liveByNodeId

    /** Observes the channel's structural inbox and applies each newly-appended `(regionId, StructuralOp)`
      * to the live holder named by `regionId` in FIFO order, the client half of structural reactivity.
      * The inbox is a `SignalRef[Chunk[(regionId, StructuralOp)]]` the WS receiver appends to; this forks
      * one observe fiber under the island's ambient Scope that tracks how many ops it has already drained
      * (a single-owner cursor on the observe loop), so each op applies exactly once even as the
      * accumulated chunk grows. The ordered keyed children are tracked PER REGION, so a scene with several
      * `foreach`/`reactive` regions (and static siblings) splices each region's ops into its own holder
      * without colliding. An `Insert` reconstitutes the descriptor and materializes it under a fresh
      * per-element scope, then splices it into that region holder at the given index; a `Remove` closes
      * that key's element scope exactly once (disposing its GL resources) and a stale second `Remove` for
      * the same key is a no-op; a `Move` reuses the live node (no dispose, the GPU buffers survive) and
      * relinks the holder's children in the new order. The per-region keyed live map is single-owner on
      * this fiber. An op whose `regionId` resolves to no live holder is a logged no-op.
      */
    private[kyo] def subscribeStructuralInbox(
        channel: HostChannel,
        mounted: Reconciler.Mounted,
        rootLive: Reconciler.Live
    )(using Frame): Unit < (Async & Scope) =
        // The ordered keyed children per region, single-owner on the drain loop. A Remove drops its entry
        // (and closes its scope once); a Move reorders it; an Insert adds one. The cursor counts the ops
        // already drained so each accumulated op applies exactly once.
        //
        // The drain loop reads the inbox via `next` rather than `Signal.observe`, deliberately: observe
        // runs each value inside a fresh per-value Scope that closes when the value changes, which would
        // dispose an inserted element's per-element scope on the next op. The `next`-driven loop runs the
        // splice under the island's long-lived ambient Scope, so an inserted subtree lives until its
        // Remove closes its own per-element scope.
        AtomicRef.init(Map.empty[String, Chunk[(String, Reconciler.Live)]]).map { byRegion =>
            AtomicInt.init(0).map { drained =>
                def applyOne(regionId: String, op: kyo.internal.StructuralOp): Unit < (Async & Scope & Abort[ThreeException]) =
                    liveByNodeId(rootLive, regionId) match
                        case Present(regionLive) =>
                            byRegion.get.map { regions =>
                                val current = regions.getOrElse(regionId, Chunk.empty[(String, Reconciler.Live)])
                                val binding = Present(RegionBinding(channel, regionId))
                                applyStructuralOp(op, current, regionLive, mounted, binding).map { next =>
                                    byRegion.updateAndGet(_.updated(regionId, next)).unit
                                }
                            }
                        case Absent =>
                            // A region id with no live holder (a stale or unmatched path): log and skip,
                            // never throw into the drain loop.
                            Log.error(s"structural op for unknown region '$regionId' dropped")
                def drainOnce(ops: Chunk[(String, kyo.internal.StructuralOp)]): Unit < (Async & Scope & Abort[ThreeException]) =
                    drained.get.map { already =>
                        val pending = ops.drop(already)
                        Kyo.foreachDiscard(pending) { case (regionId, op) =>
                            applyOne(regionId, op)
                        }.andThen(drained.set(already + pending.size))
                    }
                Fiber.init {
                    Abort.run[Throwable] {
                        // Drain the current accumulation first (ops that arrived before this fiber started),
                        // then loop: wait for the next emission, then re-read `current` and drain the latest
                        // accumulated chunk. Reading `current` after each wakeup (rather than draining the
                        // value `next` yields) is lossless under a burst: several appends between two wakeups
                        // coalesce into one `next`, and re-reading `current` recovers every appended op (the
                        // cursor drops the already-drained prefix), so a boot that splices N children in a
                        // burst applies all N rather than only the one `next` happened to observe.
                        Abort.recover[ThreeException](e => Abort.panic(e)) {
                            channel.structuralInbox.current.map(drainOnce).andThen {
                                Loop.foreach {
                                    channel.structuralInbox.next
                                        .andThen(channel.structuralInbox.current)
                                        .map(drainOnce)
                                        .andThen(Loop.continue)
                                }
                            }
                        }
                    }.map { result =>
                        result.fold(
                            _ => (): Unit < Sync,
                            err => Log.error(s"structural inbox fiber failed: ${err.getMessage}"),
                            panic =>
                                if panic.isInstanceOf[Interrupted] then (): Unit < Sync
                                else Log.error("structural inbox fiber panicked", panic)
                        )
                    }
                }.unit
            }
        }
    end subscribeStructuralInbox

    /** The channel binding that gives a spliced region child its own per-slot mirrors: the host
      * `channel` whose mirror map an Insert grows and a Remove shrinks, and the `regionId` of the holder
      * the child is spliced into. The child's node ids are `s"$regionId#$key"`, the stable per-key id the
      * server emits its `HostPayload.Prop` pushes against, so a `foreach` child's bound prop (a cube's
      * index-driven position) updates over the channel exactly like a static node's. Absent on the
      * isolated test path, which splices structure without per-child prop reactivity.
      */
    final private[kyo] case class RegionBinding(channel: HostChannel, regionId: String):
        def childId(key: String): String = s"$regionId#$key"

    /** Applies one `StructuralOp` against the current ordered keyed children, returning the next ordered
      * list. `Insert` reconstitutes + materializes the descriptor under a per-element scope and splices
      * it at the index; `Remove` disposes the key's element scope exactly once (a missing key is a
      * no-op, so a stale second Remove cannot double-dispose); `Move` reorders the existing live node
      * with no dispose (the live object reference is preserved, GPU buffers survive). After each op the
      * root's children are relinked in the new order. When a `binding` is present, an Insert registers
      * the child's reconstituted mirrors on the channel (keyed by the child's `regionId#key` node id) and
      * a Remove unregisters them, so the child's bound props update over the channel; the isolated test
      * path passes `Absent` and reconstitutes the child at the host root with no channel side effect.
      */
    private[kyo] def applyStructuralOp(
        op: kyo.internal.StructuralOp,
        current: Chunk[(String, Reconciler.Live)],
        rootLive: Reconciler.Live,
        mounted: Reconciler.Mounted,
        binding: Maybe[RegionBinding] = Absent
    )(using Frame): Chunk[(String, Reconciler.Live)] < (Async & Scope & Abort[ThreeException]) =
        op match
            case kyo.internal.StructuralOp.Insert(key, index, descriptor) =>
                // Reconstitute the child under its stable per-key node id when bound (so its mirrors match
                // the ids the server pushes props against), otherwise at the host root (the test path).
                val baseId = binding.fold(ThreeBridge.rootId)(_.childId(key))
                ThreeBridge.reconstituteAt(baseId, descriptor).map { case (node, mirrors) =>
                    val register = binding.fold(Kyo.unit: Unit < Sync)(_.channel.registerMirrors(mirrors.toSeq))
                    register.andThen {
                        Reconciler.materializeInElemScope(node, mounted).map { live =>
                            val clamped = math.max(0, math.min(index, current.size))
                            val next    = current.take(clamped).appended((key, live)).concat(current.drop(clamped))
                            relinkRoot(rootLive, current, next).andThen(next)
                        }
                    }
                }
            case kyo.internal.StructuralOp.Remove(key) =>
                Maybe.fromOption(current.find(_._1 == key)) match
                    case Present((_, live)) =>
                        val unregister = binding.fold(Kyo.unit: Unit < Sync)(b => b.channel.unregisterMirrors(b.childId(key)))
                        unregister.andThen {
                            Reconciler.disposeElemScope(live, mounted).andThen {
                                val next = current.filterNot(_._1 == key)
                                relinkRoot(rootLive, current, next).andThen(next)
                            }
                        }
                    case Absent =>
                        // A stale Remove for a key already removed: no live entry, so no dispose. This is
                        // the double-dispose guard (the first Remove cleared the entry).
                        current: Chunk[(String, Reconciler.Live)] < (Async & Scope & Abort[ThreeException])
            case kyo.internal.StructuralOp.Move(key, toIndex) =>
                Maybe.fromOption(current.find(_._1 == key)) match
                    case Present(entry) =>
                        val without = current.filterNot(_._1 == key)
                        val clamped = math.max(0, math.min(toIndex, without.size))
                        val next    = without.take(clamped).appended(entry).concat(without.drop(clamped))
                        relinkRoot(rootLive, current, next).andThen(next)
                    case Absent =>
                        current: Chunk[(String, Reconciler.Live)] < (Async & Scope & Abort[ThreeException])
    end applyStructuralOp

    /** Detaches every prior-spliced child from the root and re-attaches the next keyed set in order, the
      * structural analog of the reconciler's holder relink. Detaching the PRIOR set (not the next set)
      * is what unlinks a removed child, and re-attaching the next set in order is what realizes an
      * insert position or a reorder; only the spliced children are touched, so the host root's initial
      * (boot) content is undisturbed. The live nodes (and their GPU buffers) are unchanged, so a Move
      * keeps its object identity.
      */
    private def relinkRoot(
        rootLive: Reconciler.Live,
        prior: Chunk[(String, Reconciler.Live)],
        next: Chunk[(String, Reconciler.Live)]
    )(using Frame): Unit < Sync =
        // Unsafe: a synchronous scene-graph relink on the root and its spliced children.
        Sync.Unsafe.defer {
            prior.foreach { case (_, l) => ThreeFacadeOps.detachUnsafe(rootLive.obj, l.obj) }
            next.foreach { case (_, l) => ThreeFacadeOps.attachUnsafe(rootLive.obj, l.obj) }
        }
    end relinkRoot

    /** Applies one inbound HostUpdate to the channel: a Prop writes the matching slot's mirror
      * SignalRef (the reconciler's forkBoundRef then drives one patchProp); a Structural writes the
      * structural inbox (the keyed reconciler then splices). A payload for a slot/node the channel
      * does not know is a silent no-op.
      */
    private[kyo] def applyHostUpdate(
        channel: HostChannel,
        payload: kyo.internal.HostPayload
    )(using Frame): Unit < (Async & Scope) =
        payload match
            case kyo.internal.HostPayload.Prop(nodeId, slot, value) =>
                channel.writeProp(nodeId, slot, value)
            case kyo.internal.HostPayload.Structural(op, regionId) =>
                // A keyed splice instruction for one region: appended to the structural inbox, a
                // Signal[Chunk[(regionId, StructuralOp)]] the subscribeStructuralInbox drain observes.
                // On the next drain tick an Insert materializes the descriptor under a fresh
                // per-element scope, a Remove closes exactly one element scope (disposing its GL
                // resources once), and a Move reuses the live node (GPU buffers survive); each op
                // applies into the holder named by regionId, not the host root.
                channel.writeStructural(regionId, op)
            case _: kyo.internal.HostPayload.Boot =>
                // The boot envelope is consumed once at island mount via readHostInit; it never arrives
                // over the live channel, so a stray Boot is a silent no-op.
                Kyo.unit
    end applyHostUpdate

end ThreeMount
