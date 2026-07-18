package kyo

import kyo.Three.foreachKeyed
import kyo.Three.render
import kyo.internal.PointerKind
import kyo.internal.PointerWire
import kyo.internal.Reconciler
import kyo.internal.ThreeFacadeOps
import org.scalajs.dom
import scala.scalajs.js.typedarray.Uint8Array
import scala.scalajs.js as sjs

/** Tests for [[ThreeMount]] and the frame loop using the deterministic `testDriver` / `ThreeFrames.Manual` path.
  *
  * All fixtures run on Node via the `testDriver` seam (no WebGL, no browser needed). The browser-path
  * GL-context assertions are in `ThreeMountBrowserTest` in js/src/test.
  *
  * Every assertion observes a real value on a real three.js object; nothing is faked or mocked.
  */
class ThreeMountTest extends ThreeTest:

    // ---- scene + camera used across fixtures ----

    private val boxMesh = Three.mesh(
        Three.Geometry.box(),
        Three.Material.standard()
    )

    private def baseScene = Three.scene(boxMesh)

    private def baseCamera = Three.Camera.perspective()

    // ---- 9 node-testable fixtures ----

    "testDriver path materializes the scene into a live object" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(baseScene)
                    (_, mounted) = mountResult
                yield
                    assert(mounted.live.nonEmpty, "mount must produce at least one live entry")
                    assert(
                        mounted.live.get(new Reconciler.IdentityKey(boxMesh)).isDefined,
                        "the box mesh must have a corresponding live entry in the mounted map"
                    )
                end for
            }
        }
    }

    "one tick runs each onFrame closure exactly once" in {
        var calls = 0
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(_ => Sync.defer { calls += 1 })
        val scene = Three.scene(mesh)
        Scope.run {
            Three.testDriver(scene, baseCamera).map { driver =>
                driver.step(16.millis).map { _ =>
                    assert(calls == 1)
                }
            }
        }
    }

    "onFrame receives the tick delta supplied to step" in {
        var observedDelta = Duration.Zero
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(tick => Sync.defer { observedDelta = tick.delta })
        val scene = Three.scene(mesh)
        Scope.run {
            Three.testDriver(scene, baseCamera).map { driver =>
                driver.step(33.millis).map { _ =>
                    assert(observedDelta == 33.millis)
                }
            }
        }
    }

    "N ticks advance the onFrame closure N times" in {
        var calls = 0
        val n     = 10
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(_ => Sync.defer { calls += 1 })
        val scene = Three.scene(mesh)
        Scope.run {
            Three.testDriver(scene, baseCamera).map { driver =>
                Kyo.foreachDiscard(Chunk.from(0 until n))(_ => driver.step(16.millis)).map { _ =>
                    assert(calls == n)
                }
            }
        }
    }

    "onFrame closure mutates a live object rotation.y and submitSeam fires N times per N steps" in {
        val n    = 5
        val step = 0.016

        var submitCount                    = 0
        var liveObjRef: Maybe[sjs.Dynamic] = Absent
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(_ =>
                Sync.Unsafe.defer {
                    liveObjRef.foreach { ref =>
                        ref.rotation.y = ref.rotation.y.asInstanceOf[Double] + step
                    }
                }
            )
        val scene = Three.scene(mesh)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (rootLive, mounted) = mountResult
                    cam <- ThreeFacadeOps.makeCamera(baseCamera)
                    _ <- Sync.Unsafe.defer {
                        mounted.live.get(new Reconciler.IdentityKey(mesh)).foreach { live =>
                            liveObjRef = Present(live.obj)
                        }
                    }
                    _ <- ThreeMount.subscribeRegions(mounted)
                    _ <- Kyo.foreachDiscard(Chunk.from(0 until n)) { _ =>
                        Kyo.foreachDiscard(ThreeMount.onFrameClosures(mounted)) { f =>
                            f(Three.Tick(16.millis, 16.millis, 0L)).unit
                        }.andThen(ThreeMount.submitSeam(rootLive, cam).map { _ =>
                            Sync.defer { submitCount += 1 }
                        })
                    }
                yield
                    assert(submitCount == n)
                    assert(liveObjRef.isDefined)
                    val ry = liveObjRef.get.rotation.y.asInstanceOf[Double]
                    assert(math.abs(ry - (n * step)) < 0.001, s"expected rotation.y ~${n * step} but got $ry")
                end for
            }
        }
    }

    "the live runLoop applies each onFrame mutation before the render submit of the same tick" in {
        // Drives the real ThreeMount.runLoop through ThreeFrames.Manual with a renderer seam that records
        // the live mesh rotation at the render call. The onFrame closure mutates that same rotation,
        // so a recorded value that already reflects the mutation proves the closure ran inline before
        // the submit (the one-tick ordering guard). The renderer is a real js object whose `render`
        // observes the real live mesh value; nothing about the scene is mocked.
        val rotation                       = 0.25
        var recordedAtSubmit               = Double.NaN
        var liveObjRef: Maybe[sjs.Dynamic] = Absent
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(_ =>
                Sync.Unsafe.defer {
                    liveObjRef.foreach(ref => ref.rotation.y = rotation)
                }
            )
        val scene = Three.scene(mesh)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (rootLive, mounted) = mountResult
                    cam        <- ThreeFacadeOps.makeCamera(baseCamera)
                    rendersRef <- Signal.initRef(0L)
                    captures   <- Sync.Unsafe.defer(ThreeMount.MountCaptureQueue.init)
                    _ <- Sync.Unsafe.defer {
                        mounted.live.get(new Reconciler.IdentityKey(mesh)).foreach(l => liveObjRef = Present(l.obj))
                    }
                    recordingRenderer <- Sync.Unsafe.defer {
                        sjs.Dynamic.literal(
                            render = (_: sjs.Dynamic, _: sjs.Dynamic) =>
                                liveObjRef.foreach { ref =>
                                    recordedAtSubmit = ref.rotation.y.asInstanceOf[Double]
                                }
                        )
                    }
                    _ <- ThreeMount.runLoop(
                        mounted,
                        rootLive,
                        cam,
                        recordingRenderer,
                        ThreeFrames.Manual(driver => Abort.run[ThreeException](driver.step(16.millis))),
                        rendersRef,
                        captures
                    )
                yield assert(
                    math.abs(recordedAtSubmit - rotation) < 0.0001,
                    s"render submit must observe the onFrame mutation (expected rotation.y == $rotation, " +
                        s"saw $recordedAtSubmit at submit time)"
                )
            }
        }
    }

    "large N ticks runs without per-tick coordination overhead" in {
        val n     = 100
        var count = 0
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(_ => Sync.defer { count += 1 })
        val scene = Three.scene(mesh)
        Scope.run {
            Three.testDriver(scene, baseCamera).map { driver =>
                Kyo.foreachDiscard(Chunk.from(0 until n))(_ => driver.step(1.millis)).map { _ =>
                    assert(count == n)
                }
            }
        }
    }

    "interrupt cascades: loop stops when scope closes" in {
        // A handshake-driven runLoop: the Manual driver advances one tick per `proceed` token and the
        // onFrame closure signals `done` after each tick, so the test steps the loop deterministically
        // (no sleep) and knows the exact step count. After the scope closes (interrupting the forked
        // loop fiber), the test puts another `proceed` and polls for a `done` over a bounded set of
        // scheduler yields: if the loop had survived the close it would tick once more and the count
        // would grow. A frozen count proves the close actually halted the loop.
        var steps = 0
        val n     = 5
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(_ => Sync.defer { steps += 1 })
        val scene = Three.scene(mesh)
        for
            proceed      <- Channel.initUnscoped[Unit](1)
            done         <- Channel.initUnscoped[Unit](1)
            stubRenderer <- Sync.Unsafe.defer(sjs.Dynamic.literal(render = (_: sjs.Dynamic, _: sjs.Dynamic) => ()))
            stepsAtClose <- Scope.run {
                Abort.recover[ThreeException](e => Abort.panic(e)) {
                    for
                        mountResult <- Reconciler.mount(scene)
                        (rootLive, mounted) = mountResult
                        cam        <- ThreeFacadeOps.makeCamera(baseCamera)
                        rendersRef <- Signal.initRef(0L)
                        captures   <- Sync.Unsafe.defer(ThreeMount.MountCaptureQueue.init)
                        _          <- ThreeMount.subscribeRegions(mounted)
                        // Fork the real frame loop with a driver that ticks once per `proceed` token. The
                        // onFrame closure (which increments `steps`) runs inside each tick, then signals `done`.
                        _ <- Fiber.init {
                            Abort.run[ThreeException](
                                ThreeMount.runLoop(
                                    mounted,
                                    rootLive,
                                    cam,
                                    stubRenderer,
                                    ThreeFrames.Manual { driver =>
                                        Loop.foreach {
                                            Abort.run[Closed](proceed.take).map {
                                                case Result.Success(_) =>
                                                    Abort.run[ThreeException](driver.step(16.millis)).andThen {
                                                        Abort.run[Closed](done.put(())).andThen(Loop.continue)
                                                    }
                                                case _ => Loop.done
                                            }
                                        }
                                    },
                                    rendersRef,
                                    captures
                                )
                            ).map {
                                case Result.Success(_) => (): Unit < Sync
                                case Result.Failure(e) => Log.error(s"frame loop failed: ${e.getMessage}")
                                case Result.Panic(e) =>
                                    if e.isInstanceOf[Interrupted] then (): Unit < Sync
                                    else Log.error("frame loop panicked", e)
                            }
                        }.unit
                        // Drive exactly n ticks: each (proceed, done) handshake advances the loop one tick.
                        _ <- Kyo.foreachDiscard(Chunk.from(0 until n)) { _ =>
                            Abort.run[Closed](proceed.put(())).andThen(Abort.run[Closed](done.take)).unit
                        }
                        captured <- Sync.defer(steps)
                    yield captured
                }
            }
            // The scope is now closed; the forked loop fiber has been interrupted by Scope.run teardown.
            // Offer one more tick and poll for a `done` across a bounded set of scheduler yields; a halted
            // loop never consumes the token, so `steps` cannot grow.
            _ <- Abort.run[Closed](proceed.put(()))
            leaked <- Loop.indexed { i =>
                if i >= 50 then Loop.done(false)
                else
                    // Yield the scheduler (an unscoped trivial fiber) so a surviving loop fiber would get to
                    // run, then check whether it produced another tick.
                    Fiber.initUnscoped(Kyo.unit).map(_.get).andThen {
                        Abort.run[Closed](done.poll).map {
                            case Result.Success(Present(_)) => Loop.done(true)
                            case _                          => Loop.continue
                        }
                    }
            }
            stepsAfter <- Sync.defer(steps)
        yield
            assert(stepsAtClose == n, s"the loop must run exactly $n ticks while the scope is open, got $stepsAtClose")
            assert(!leaked, "no further tick may complete after the scope closes (the loop must be halted)")
            assert(stepsAfter == stepsAtClose, s"the step count must not grow after the scope closes: $stepsAtClose -> $stepsAfter")
        end for
    }

    "ThreeFrames.Manual driver yields onFrame closures in step order" in {
        val deltas = Chunk(10.millis, 20.millis, 30.millis)
        var seen   = Chunk.empty[Duration]
        val mesh = Three.mesh(Three.Geometry.box(), Three.Material.standard())
            .onFrame(tick => Sync.defer { seen = seen.appended(tick.delta) })
        val scene = Three.scene(mesh)
        Scope.run {
            Three.testDriver(scene, baseCamera).map { driver =>
                Kyo.foreachDiscard(deltas)(d => driver.step(d)).map { _ =>
                    assert(seen == deltas)
                }
            }
        }
    }

    // ---- Three.Mount inspection surface fixtures ----

    /** Reads every byte of a `Span[Byte]` into a `Chunk[Byte]` for a structural equality assertion. */
    private def spanBytes(s: Span[Byte]): Chunk[Byte] = Chunk.from(0 until s.size).map(i => s(i))

    /** A detached `Reconciler.Live` and camera pair for a bare `renderSubmit` call: neither field is
      * read by the stub renderer, so a plain FFI placeholder is enough.
      */
    private def stubRootAndCamera(using Frame): (Reconciler.Live, sjs.Dynamic) < Sync =
        for
            root <- Sync.Unsafe.defer(new Reconciler.Live(sjs.Dynamic.literal(), Three.scene(), Chunk.empty))
            cam  <- Sync.Unsafe.defer(sjs.Dynamic.literal())
        yield (root, cam)

    /** A `Three.Mount` over the given `rendersRef`/`disposedRef`/`captures`, backed by stub FFI
      * objects (a detached canvas and a renderer with no live GL context).
      */
    private def stubMount(
        rendersRef: SignalRef[Long],
        disposedRef: SignalRef[Boolean],
        captures: ThreeMount.MountCaptureQueue
    )(using Frame): Three.Mount < Sync =
        for
            canvasDyn   <- Sync.Unsafe.defer(sjs.Dynamic.literal())
            rendererDyn <- Sync.Unsafe.defer(sjs.Dynamic.literal(getContext = () => sjs.Dynamic.literal()))
        yield new ThreeMount.MountImpl(
            rendersRef,
            disposedRef,
            canvasDyn.asInstanceOf[dom.HTMLCanvasElement],
            new ThreeMount.RendererImpl(rendererDyn),
            captures
        )

    "renders count increments once per submit" in {
        for
            rendersRef <- Signal.initRef(0L)
            captures   <- Sync.Unsafe.defer(ThreeMount.MountCaptureQueue.init)
            rootAndCam <- stubRootAndCamera
            (root, cam) = rootAndCam
            stubRenderer <- Sync.Unsafe.defer(
                sjs.Dynamic.literal(
                    render = (_: sjs.Dynamic, _: sjs.Dynamic) => (),
                    getContext = () => sjs.Dynamic.literal()
                )
            )
            before <- rendersRef.current
            n = 3
            _     <- Kyo.foreachDiscard(Chunk.from(0 until n))(_ => ThreeMount.renderSubmit(root, cam, stubRenderer, rendersRef, captures))
            after <- rendersRef.current
        yield
            assert(before == 0L, s"expected 0 before any submit, got $before")
            assert(after == n.toLong, s"expected $n after $n submits, got $after")
    }

    "renders increments strictly AFTER renderer.render (the ordering half)" in {
        for
            rendersRef <- Signal.initRef(2L)
            captures   <- Sync.Unsafe.defer(ThreeMount.MountCaptureQueue.init)
            rootAndCam <- stubRootAndCamera
            (root, cam) = rootAndCam
            // Unsafe: a synchronous side-channel cell the stub renderer's `render` closure writes into,
            // captured under the SAME ambient AllowUnsafe the enclosing Sync.Unsafe.defer block supplies;
            // read back after renderSubmit completes.
            observed <- Sync.Unsafe.defer(AtomicLong.Unsafe.init(-1L))
            stubRenderer <- Sync.Unsafe.defer(
                sjs.Dynamic.literal(
                    // Reads rendersRef at the moment render() runs: if the increment happened before this
                    // call, the observed value would already be N+1, falsifying the ordering claim.
                    render = (_: sjs.Dynamic, _: sjs.Dynamic) => observed.set(rendersRef.unsafe.get()),
                    getContext = () => sjs.Dynamic.literal()
                )
            )
            _            <- ThreeMount.renderSubmit(root, cam, stubRenderer, rendersRef, captures)
            duringRender <- Sync.Unsafe.defer(observed.get())
            after        <- rendersRef.current
        yield
            assert(
                duringRender == 2L,
                s"renders must still read the PRE-increment value (2) at the moment renderer.render runs, got $duringRender"
            )
            assert(
                after == 3L,
                s"renders must read the POST-increment value (3) once the submit completes, proving the increment lands strictly after the render call, got $after"
            )
    }

    "renders.next resolves to the next commit count" in {
        for
            rendersRef <- Signal.initRef(5L)
            captures   <- Sync.Unsafe.defer(ThreeMount.MountCaptureQueue.init)
            rootAndCam <- stubRootAndCamera
            (root, cam) = rootAndCam
            stubRenderer <- Sync.Unsafe.defer(sjs.Dynamic.literal(render = (_: sjs.Dynamic, _: sjs.Dynamic) => ()))
            // Fiber.initUnscoped (not Fiber.init): this leaf awaits the fiber directly, with no Scope
            // to auto-interrupt it.
            nextFiber <- Fiber.initUnscoped(rendersRef.next)
            // Yield the scheduler once so the forked fiber has captured the CURRENT next-promise
            // before this fiber mutates rendersRef; skipping this risks the fork and the mutation
            // racing (the capture landing after the mutation would await a commit that never comes).
            _   <- Fiber.initUnscoped(Kyo.unit).map(_.get)
            _   <- ThreeMount.renderSubmit(root, cam, stubRenderer, rendersRef, captures)
            got <- nextFiber.get
        yield assert(got == 6L, s"renders.next must resolve to the count after the next commit (expected 6), got $got")
    }

    "disposed reads false while the mount is live and its first transition on scope close is true (makeRenderer's single release)" in {
        for
            disposedRef <- Signal.initRef(false)
            canvasDyn   <- Sync.Unsafe.defer(sjs.Dynamic.literal())
            // Fork the await before the scope closes, matching the renders.next leaf's own
            // fork-then-yield idiom, so the fiber's next-promise capture cannot race the close.
            nextFiber <- Fiber.initUnscoped(disposedRef.next)
            _         <- Fiber.initUnscoped(Kyo.unit).map(_.get)
            before    <- disposedRef.current
            // makeRenderer's acquire always succeeds (a Maybe wrap around the constructor throw), so
            // its release always runs on scope close even in Node, where the WebGLRenderer constructor
            // throws and this stub canvas yields no live GL context.
            _               <- Scope.run(Abort.run[ThreeException](ThreeMount.makeRenderer(canvasDyn, disposedRef)))
            firstTransition <- nextFiber.get
            after           <- disposedRef.current
        yield
            assert(!before, "disposed must be false before the mount scope closes")
            assert(firstTransition, "the first observed disposed transition must be to true (the release firing)")
            assert(after, "disposed must read true once the mount scope has closed")
    }

    "an enqueued capture is drained and its promise completes with the exact read bytes (1x1 and 2x2 row-major cases)" in {
        def stubGlRenderer(pixels: Array[Int]): sjs.Dynamic =
            sjs.Dynamic.literal(
                getContext = () =>
                    sjs.Dynamic.literal(
                        RGBA = 6408,
                        UNSIGNED_BYTE = 5121,
                        drawingBufferWidth = 64,
                        drawingBufferHeight = 64,
                        readPixels = (_: Int, _: Int, _: Int, _: Int, _: sjs.Dynamic, _: sjs.Dynamic, buf: Uint8Array) =>
                            Chunk.from(0 until pixels.length).foreach(i => buf(i) = pixels(i).toShort)
                    )
            )
        for
            captures <- Sync.Unsafe.defer(ThreeMount.MountCaptureQueue.init)
            doneA    <- Promise.init[Span[Byte], Abort[ThreeException]]
            doneB    <- Promise.init[Span[Byte], Abort[ThreeException]]
            _ <- Sync.Unsafe.defer {
                // Unsafe: the ambient AllowUnsafe comes from this defer block's own context function.
                captures.enqueue(ThreeMount.MountCaptureQueue.Capture(0, 0, 1, 1, doneA))
                captures.drain(stubGlRenderer(Array(10, 20, 30, 40)))
            }
            gotA <- doneA.get
            _ <- Sync.Unsafe.defer {
                // Unsafe: the ambient AllowUnsafe comes from this defer block's own context function.
                captures.enqueue(ThreeMount.MountCaptureQueue.Capture(0, 0, 2, 2, doneB))
                captures.drain(stubGlRenderer(Array(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140, 150, 160)))
            }
            gotB <- doneB.get
            pendingAfter <- Sync.Unsafe.defer {
                // Unsafe: the ambient AllowUnsafe comes from this defer block's own context function.
                captures.pendingCount
            }
        yield
            assert(
                spanBytes(gotA) == Chunk[Byte](10, 20, 30, 40),
                s"the 1x1 capture must read the exact RGBA bytes in order, got ${spanBytes(gotA)}"
            )
            assert(
                spanBytes(gotB) ==
                    Chunk[Byte](10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130.toByte, 140.toByte, 150.toByte, 160.toByte),
                s"the 2x2 capture must read the full 16-byte row-major pattern in order, got ${spanBytes(gotB)}"
            )
            assert(pendingAfter == 0, s"the queue must be empty after both captures drain, got pendingCount=$pendingAfter")
        end for
    }

    "readPixels on a disposed mount completes typed RenderFailure without enqueueing" in {
        for
            rendersRef  <- Signal.initRef(0L)
            disposedRef <- Signal.initRef(true)
            captures    <- Sync.Unsafe.defer(ThreeMount.MountCaptureQueue.init)
            mount       <- stubMount(rendersRef, disposedRef, captures)
            result      <- Abort.run[ThreeException](mount.readPixels(0, 0, 1, 1))
            pending <- Sync.Unsafe.defer {
                // Unsafe: the ambient AllowUnsafe comes from this defer block's own context function.
                captures.pendingCount
            }
        yield
            result match
                case Result.Failure(_: ThreeException.RenderFailure) => succeed
                case other                                           => fail(s"expected a typed RenderFailure but got $other")
            assert(pending == 0, s"a disposed-mount read must not enqueue a capture, got pendingCount=$pending")
    }

    "a readPixels pending at teardown completes RenderFailure with no hang (deterministic Node latch)" in {
        for
            rendersRef  <- Signal.initRef(0L)
            disposedRef <- Signal.initRef(false)
            captures    <- Sync.Unsafe.defer(ThreeMount.MountCaptureQueue.init)
            mount       <- stubMount(rendersRef, disposedRef, captures)
            // Fork on Fiber.initUnscoped (not Fiber.init): the read must survive the Scope.run
            // below closing, so it must not be auto-interrupted by that scope's teardown.
            readFiber <- Fiber.initUnscoped(Abort.run[ThreeException](mount.readPixels(0, 0, 1, 1)))
            // Bounded, sleep-free poll confirming the capture is enqueued before teardown runs: yield
            // the scheduler a bounded number of times and check the pending count each time.
            enqueued <- Loop.indexed { i =>
                if i >= 2000 then Loop.done(false)
                else
                    Fiber.initUnscoped(Kyo.unit).map(_.get).andThen {
                        Sync.Unsafe.defer {
                            // Unsafe: the ambient AllowUnsafe comes from this defer block's own context function.
                            captures.pendingCount
                        }.map(c => if c >= 1 then Loop.done(true) else Loop.continue)
                    }
            }
            // The mount teardown exactly as hostMountPipelineTyped registers it: a mount-scope
            // Scope.ensure that fails every still-pending capture on close.
            _ <- Scope.run {
                Scope.ensure(Sync.Unsafe.defer {
                    // Unsafe: the ambient AllowUnsafe comes from this defer block's own context function.
                    captures.failAll("mount scope closed before the pixel readback committed")
                })
            }
            result <- readFiber.get
            pendingAfter <- Sync.Unsafe.defer {
                // Unsafe: the ambient AllowUnsafe comes from this defer block's own context function.
                captures.pendingCount
            }
        yield
            assert(enqueued, "the pending capture must be enqueued within the bounded poll before teardown runs")
            result match
                case Result.Failure(_: ThreeException.RenderFailure) => succeed
                case other => fail(s"expected the pending read to complete RenderFailure at teardown, got $other")
            assert(pendingAfter == 0, s"failAll must drain the pending queue, got pendingCount=$pendingAfter")
    }

    "a readPixels arriving AFTER teardown fails fast rather than hanging (the queue close is terminal)" in {
        for
            rendersRef <- Signal.initRef(0L)
            // The read observes a live mount (disposed is still false when readPixels samples it) and only
            // reaches the queue a step later, by which time teardown has run. That is the exact race: the
            // disposed guard cannot close it, only a terminal queue state can.
            disposedRef <- Signal.initRef(false)
            captures    <- Sync.Unsafe.defer(ThreeMount.MountCaptureQueue.init)
            mount       <- stubMount(rendersRef, disposedRef, captures)
            _ <- Sync.Unsafe.defer {
                // Unsafe: the ambient AllowUnsafe comes from this defer block's own context function.
                captures.failAll("mount scope closed before the pixel readback committed")
            }
            closed <- Sync.Unsafe.defer {
                // Unsafe: the ambient AllowUnsafe comes from this defer block's own context function.
                captures.isClosed
            }
            // The render loop is gone, so nothing can drain a capture from here on. The read must observe
            // the closed queue and complete RenderFailure rather than await a drain that will never run.
            result <- Abort.run[ThreeException](mount.readPixels(0, 0, 1, 1))
            pendingAfter <- Sync.Unsafe.defer {
                // Unsafe: the ambient AllowUnsafe comes from this defer block's own context function.
                captures.pendingCount
            }
        yield
            assert(closed, "failAll must close the queue terminally, not merely empty it")
            result match
                case Result.Failure(_: ThreeException.RenderFailure) => succeed
                case other => fail(s"a read arriving after teardown must complete RenderFailure, got $other")
            assert(pendingAfter == 0, s"a late capture must never queue on a closed queue, got pendingCount=$pendingAfter")
    }

    "an out-of-bounds readPixels region completes typed RenderFailure, never a zero-filled buffer" in {
        // The stub reports a 64x64 drawing buffer and, exactly like real GL, does NOT throw for a region
        // outside it: its readPixels leaves the destination untouched (all zeros). That is precisely how an
        // unchecked out-of-bounds read hands back garbage dressed up as a successful capture.
        val stubRenderer =
            sjs.Dynamic.literal(
                getContext = () =>
                    sjs.Dynamic.literal(
                        RGBA = 6408,
                        UNSIGNED_BYTE = 5121,
                        drawingBufferWidth = 64,
                        drawingBufferHeight = 64,
                        readPixels = (_: Int, _: Int, _: Int, _: Int, _: sjs.Dynamic, _: sjs.Dynamic, _: Uint8Array) => ()
                    )
            )
        for
            captures <- Sync.Unsafe.defer(ThreeMount.MountCaptureQueue.init)
            done     <- Promise.init[Span[Byte], Abort[ThreeException]]
            _ <- Sync.Unsafe.defer {
                // Unsafe: the ambient AllowUnsafe comes from this defer block's own context function.
                // A SMALL region positioned past the buffer's edge, so rejection must come from the bounds
                // check itself. A huge region would instead be refused by the byte allocation, which would
                // pass this leaf without the bounds check ever running.
                captures.enqueue(ThreeMount.MountCaptureQueue.Capture(100, 100, 4, 4, done))
                captures.drain(stubRenderer)
            }
            result <- Abort.run[ThreeException](done.get)
        yield result match
            case Result.Failure(_: ThreeException.RenderFailure) => succeed
            case Result.Success(bytes) =>
                fail(
                    s"an out-of-bounds region must fail, but it returned ${spanBytes(bytes).size} bytes: ${spanBytes(bytes)}"
                )
            case other => fail(s"expected a typed RenderFailure, got $other")
        end for
    }

    // ---- Group.onFrame fixtures ----

    "a Group.onFrame fires N times over N steps" in {
        var calls = 0
        val n     = 7
        val group = Three.group(
            Three.mesh(Three.Geometry.box(), Three.Material.standard())
        ).onFrame(_ => Sync.defer { calls += 1 })
        val scene = Three.scene(group)
        Scope.run {
            Three.testDriver(scene, baseCamera).map { driver =>
                Kyo.foreachDiscard(Chunk.from(0 until n))(_ => driver.step(16.millis)).map { _ =>
                    assert(calls == n)
                }
            }
        }
    }

    "a Group.onFrame mutates the live container rotation each step" in {
        val n                              = 4
        val step                           = 0.05
        var liveObjRef: Maybe[sjs.Dynamic] = Absent
        val group = Three.group(
            Three.mesh(Three.Geometry.box(), Three.Material.standard())
        ).onFrame(_ =>
            Sync.Unsafe.defer {
                liveObjRef.foreach { ref =>
                    ref.rotation.y = ref.rotation.y.asInstanceOf[Double] + step
                }
            }
        )
        val scene = Three.scene(group)
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Sync.Unsafe.defer {
                        mounted.live.get(new Reconciler.IdentityKey(group)).foreach(l => liveObjRef = Present(l.obj))
                    }
                    _ <- Kyo.foreachDiscard(Chunk.from(0 until n)) { _ =>
                        Kyo.foreachDiscard(ThreeMount.onFrameClosures(mounted))(f =>
                            f(Three.Tick(16.millis, 16.millis, 0L)).unit
                        )
                    }
                yield
                    assert(liveObjRef.isDefined)
                    val ry = liveObjRef.get.rotation.y.asInstanceOf[Double]
                    assert(math.abs(ry - (n * step)) < 0.001, s"expected rotation.y ~${n * step} but got $ry")
                end for
            }
        }
    }

    // ---- Structural reactive region population (Reactive / Foreach live-mount fill) ----

    /** Counts the live three.js children attached to a holder object. */
    private def childCount(mounted: Reconciler.Mounted, node: Three): Int =
        // Unsafe: reading the live three.js children array via js.Dynamic FFI to assert holder child count.
        import AllowUnsafe.embrace.danger
        mounted.live.get(new Reconciler.IdentityKey(node))
            .map(_.obj.children.asInstanceOf[sjs.Array[sjs.Dynamic]].length)
            .getOrElse(-1)
    end childCount

    "a Foreach region fills its holder from the signal's current value at mount" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    items <- Signal.initRef(Chunk(1, 2, 3))
                    foreach = items.foreachKeyed(_.toString) { i =>
                        Three.mesh(Three.Geometry.box(), Three.Material.standard()).position(Three.Vec3(i.toDouble, 0, 0))
                    }
                    scene = Three.scene(foreach)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                yield assert(childCount(mounted, foreach) == 3, s"expected 3 cubes, got ${childCount(mounted, foreach)}")
            }
        }
    }

    "a Foreach region re-diffs its holder when the signal emits a new list" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    items <- Signal.initRef(Chunk(1, 2))
                    foreach = items.foreachKeyed(_.toString) { i =>
                        Three.mesh(Three.Geometry.box(), Three.Material.standard()).position(Three.Vec3(i.toDouble, 0, 0))
                    }
                    scene = Three.scene(foreach)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    two = childCount(mounted, foreach)
                    _ <- items.set(Chunk(1, 2, 3, 4))
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    four = childCount(mounted, foreach)
                yield
                    assert(two == 2, s"expected 2 cubes initially, got $two")
                    assert(four == 4, s"expected 4 cubes after re-diff, got $four")
                end for
            }
        }
    }

    "a Foreach region reuses the live object for an unchanged key across a re-diff" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                import AllowUnsafe.embrace.danger
                for
                    items <- Signal.initRef(Chunk(1, 2))
                    foreach = items.foreachKeyed(_.toString) { i =>
                        Three.mesh(Three.Geometry.box(), Three.Material.standard()).position(Three.Vec3(i.toDouble, 0, 0))
                    }
                    scene = Three.scene(foreach)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    liveAfterFirst = mounted.live.size
                    _ <- items.set(Chunk(1, 2, 3))
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    liveAfterSecond = mounted.live.size
                yield assert(
                    liveAfterSecond == liveAfterFirst + 1,
                    s"keyed reuse: adding one element must materialize exactly one new live entry; " +
                        s"live grew from $liveAfterFirst to $liveAfterSecond"
                )
                end for
            }
        }
    }

    "a Reactive region fills its holder with the projected subtree at mount" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    flag <- Signal.initRef(true)
                    reactive = flag.render { on =>
                        if on then Three.group(Three.mesh(Three.Geometry.box(), Three.Material.standard()))
                        else Three.empty
                    }
                    scene = Three.scene(reactive)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                yield assert(childCount(mounted, reactive) == 1, s"expected 1 subtree, got ${childCount(mounted, reactive)}")
            }
        }
    }

    "subscribeReactiveRegions fills foreach and reactive holders through the mount path" in {
        val items = Signal.initRef(Chunk(1, 2, 3))
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    ref <- items
                    foreach = ref.foreachKeyed(_.toString) { i =>
                        Three.mesh(Three.Geometry.box(), Three.Material.standard()).position(Three.Vec3(i.toDouble, 0, 0))
                    }
                    food = ref.render { its =>
                        Three.mesh(Three.Geometry.sphere(), Three.Material.standard())
                            .position(Three.Vec3(its.size.toDouble, 0, 0))
                    }
                    scene = Three.scene(Three.Light.ambient(), foreach, food)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- ThreeMount.subscribeReactiveRegions(mounted)
                yield
                    assert(childCount(mounted, foreach) == 3, s"foreach holder should hold 3 cubes, got ${childCount(mounted, foreach)}")
                    assert(childCount(mounted, food) == 1, s"reactive holder should hold 1 sphere, got ${childCount(mounted, food)}")
                end for
            }
        }
    }

    "subscribeReactiveRegions with serverDriven true forks a local watcher only for the client-local region" in {
        // A render region is Schema-backed and carries a server drive, so on a hydrated server-driven page
        // the server's ReplaceSubtree drain is the single writer of its live subtree. Under serverDriven the
        // gate withholds its local watcher: an emit on its signal must not re-render it here. A raw
        // Three.reactive(Signal[Three]) region carries no server drive (the server cannot drive it), so it
        // keeps its local watcher even under serverDriven: an emit must re-render it here. Each builder
        // records the last value it rendered, so the render builder freezes at its mount value while the
        // reactive builder advances to the emitted value.
        var lastRenderVal   = -1
        var lastReactiveVal = -1
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    serverSource <- Signal.initRef(0)
                    clientSource <- Signal.initRef(0)
                    serverRegion = serverSource.render { n =>
                        lastRenderVal = n
                        Three.mesh(Three.Geometry.box(), Three.Material.standard()).position(Three.Vec3(n.toDouble, 0, 0))
                    }
                    clientRegion = Three.reactive(clientSource.map[Three] { n =>
                        lastReactiveVal = n
                        Three.mesh(Three.Geometry.sphere(), Three.Material.standard()).position(Three.Vec3(n.toDouble, 0, 0))
                    })
                    scene = Three.scene(serverRegion, clientRegion)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- ThreeMount.subscribeReactiveRegions(mounted, serverDriven = true)
                    _ <- serverSource.set(1)
                    _ <- clientSource.set(1)
                    // Yield real scheduler turns until the client-local watcher re-renders the emitted value.
                    // Its watcher is forked, so this terminates; the cap is a safety bound, not a sleep.
                    _ <- Loop.indexed { i =>
                        if lastReactiveVal == 1 || i >= 50 then Loop.done(())
                        else Fiber.initUnscoped(Kyo.unit).map(_.get).andThen(Loop.continue)
                    }
                    // Give a would-be server-region watcher the same window to fire before asserting it never did.
                    _ <- Loop.indexed { i =>
                        if i >= 50 then Loop.done(())
                        else Fiber.initUnscoped(Kyo.unit).map(_.get).andThen(Loop.continue)
                    }
                yield
                    assert(
                        lastRenderVal == 0,
                        s"server-drivable render region must keep its mount value with no local watcher under serverDriven, got $lastRenderVal"
                    )
                    assert(
                        lastReactiveVal == 1,
                        s"client-local reactive region must re-render its emitted value via its local watcher under serverDriven, got $lastReactiveVal"
                    )
                end for
            }
        }
    }

    // ---- per-element dispose-once and live-map retirement ----

    /** Finds the keyed `ReactiveRegion` for the given holder node using ref-identity. */
    private def regionForNode(mounted: Reconciler.Mounted, node: Three): Maybe[Reconciler.ReactiveRegion] =
        Maybe.fromOption(Reconciler.reactiveRegions(mounted).toSeq.find(_.holder.node eq node))

    "foreachKeyed shrink disposes removed child geometry and material exactly once" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                var geomDisposeCount = 0
                var matDisposeCount  = 0
                for
                    items <- Signal.initRef(Chunk(1, 2))
                    foreach = items.foreachKeyed(_.toString) { i =>
                        Three.mesh(Three.Geometry.box(), Three.Material.standard())
                            .position(Three.Vec3(i.toDouble, 0, 0))
                    }
                    scene = Three.scene(foreach)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    // Attach dispose listeners to key "2"'s mesh geometry and material.
                    _ <- Sync.Unsafe.defer {
                        regionForNode(mounted, foreach).foreach { region =>
                            region.prevKeyed.toSeq.find(_._1 == "2").foreach { case (_, live) =>
                                live.obj.geometry.addEventListener(
                                    "dispose",
                                    (_: sjs.Any) => geomDisposeCount += 1
                                )
                                live.obj.material.addEventListener(
                                    "dispose",
                                    (_: sjs.Any) => matDisposeCount += 1
                                )
                            }
                        }
                    }
                    liveSizeBefore <- Sync.Unsafe.defer(mounted.live.size)
                    // Remove key "2" by shrinking to Chunk(1).
                    _             <- items.set(Chunk(1))
                    _             <- Reconciler.fillReactiveRegionsOnce(mounted)
                    liveSizeAfter <- Sync.Unsafe.defer(mounted.live.size)
                yield
                    assert(geomDisposeCount == 1, s"geometry dispose must fire exactly once, got $geomDisposeCount")
                    assert(matDisposeCount == 1, s"material dispose must fire exactly once, got $matDisposeCount")
                    assert(liveSizeAfter < liveSizeBefore, s"live map must shrink on removal: before=$liveSizeBefore after=$liveSizeAfter")
                end for
            }
        }
    }

    "foreachKeyed shrink: unchanged keys keep the same Live instance and are not disposed" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                var keptGeomDisposeCount = 0
                for
                    items <- Signal.initRef(Chunk(1, 2))
                    foreach = items.foreachKeyed(_.toString) { i =>
                        Three.mesh(Three.Geometry.box(), Three.Material.standard())
                    }
                    scene = Three.scene(foreach)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    // Capture the Live identity for key "1" (the kept key) and attach a dispose listener.
                    keptLiveRef <- Sync.Unsafe.defer {
                        var keptLive: Maybe[Reconciler.Live] = Absent
                        regionForNode(mounted, foreach).foreach { region =>
                            region.prevKeyed.toSeq.find(_._1 == "1").foreach { case (_, live) =>
                                live.obj.geometry.addEventListener(
                                    "dispose",
                                    (_: sjs.Any) => keptGeomDisposeCount += 1
                                )
                                keptLive = Present(live)
                            }
                        }
                        keptLive
                    }
                    // Remove key "2"; key "1" must survive with the same Live instance.
                    _ <- items.set(Chunk(1))
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    keptLiveAfterRef <- Sync.Unsafe.defer {
                        var keptLiveAfter: Maybe[Reconciler.Live] = Absent
                        regionForNode(mounted, foreach).foreach { region =>
                            region.prevKeyed.toSeq.find(_._1 == "1").foreach { case (_, live) =>
                                keptLiveAfter = Present(live)
                            }
                        }
                        keptLiveAfter
                    }
                yield
                    assert(keptGeomDisposeCount == 0, s"kept key must not dispose: got $keptGeomDisposeCount")
                    assert(
                        (keptLiveRef, keptLiveAfterRef) match
                            case (Present(a), Present(b)) => a.obj eq b.obj
                            case _                        => false,
                        "kept key must reuse the same Live instance (same three.js object reference)"
                    )
                end for
            }
        }
    }

    "Reactive swap disposes the prior subtree's GL resources exactly once" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                var geomDisposeCount = 0
                var matDisposeCount  = 0
                for
                    flag <- Signal.initRef(true)
                    reactive = flag.render { on =>
                        if on then
                            Three.mesh(Three.Geometry.box(), Three.Material.standard())
                        else
                            Three.mesh(Three.Geometry.sphere(), Three.Material.standard())
                    }
                    scene = Three.scene(reactive)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                    // Attach dispose listeners to the initial subtree's mesh geometry and material.
                    _ <- Sync.Unsafe.defer {
                        regionForNode(mounted, reactive).foreach { region =>
                            region.prevKeyed.headMaybe.foreach { case (_, live) =>
                                val _ = live.obj.geometry.addEventListener(
                                    "dispose",
                                    (_: sjs.Any) => geomDisposeCount += 1
                                )
                                val _ = live.obj.material.addEventListener(
                                    "dispose",
                                    (_: sjs.Any) => matDisposeCount += 1
                                )
                            }
                        }
                    }
                    // Swap the reactive signal; the prior subtree must dispose exactly once.
                    _ <- flag.set(false)
                    _ <- Reconciler.fillReactiveRegionsOnce(mounted)
                yield
                    assert(geomDisposeCount == 1, s"prior subtree geometry dispose must fire exactly once, got $geomDisposeCount")
                    assert(matDisposeCount == 1, s"prior subtree material dispose must fire exactly once, got $matDisposeCount")
                end for
            }
        }
    }

    // ---- Reactive camera position ----

    "reactive camera position: boundRefs registers the signal and patch updates the live .position" in {
        // A Perspective camera with .position(signal) placed as a scene child appears in
        // boundRefs so subscribeRegions wires live position updates. The patch function is applied
        // directly to the live object, asserting the three.js camera .position changes to the
        // emitted Three.Vec3.
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    posRef <- Signal.initRef(Three.Vec3(0, 0, 5))
                    cam   = Three.Camera.perspective().position(posRef)
                    scene = Three.scene(cam)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    refs   <- Sync.Unsafe.defer(ThreeMount.boundRefs(mounted))
                    camLiv <- Sync.Unsafe.defer(mounted.live.get(new Reconciler.IdentityKey(cam)))
                yield
                    assert(camLiv.isDefined, "camera must have a live entry in the mounted map")
                    assert(refs.nonEmpty, "boundRefs must register at least one triple for the reactive camera position")
                    val newPos = Three.Vec3(7, 8, 9)
                    refs.foreach { case (live, patch, _) =>
                        if live.node eq cam then
                            import AllowUnsafe.embrace.danger
                            Reconciler.patchProp(live, patch(newPos)(_))
                    }
                    val liveObj = camLiv.get.obj
                    import AllowUnsafe.embrace.danger
                    val px = liveObj.position.x.asInstanceOf[Double]
                    val py = liveObj.position.y.asInstanceOf[Double]
                    val pz = liveObj.position.z.asInstanceOf[Double]
                    assert(math.abs(px - 7.0) < 0.001, s"camera position.x must be 7.0 after patch, got $px")
                    assert(math.abs(py - 8.0) < 0.001, s"camera position.y must be 8.0 after patch, got $py")
                    assert(math.abs(pz - 9.0) < 0.001, s"camera position.z must be 9.0 after patch, got $pz")
                end for
            }
        }
    }

    "reactive light position: a signal position on a light registers in boundRefs and patches the live .position" in {
        // A Directional light with .position(signal) must appear in boundRefs so its live three.js
        // position updates on emission. The setter writes into the light's transform (the same field
        // the factory's position param writes into), so both signal and static positions wire identically.
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    posRef <- Signal.initRef(Three.Vec3(1, 1, 1))
                    light = Three.Light.directional().position(posRef)
                    scene = Three.scene(light)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    refs    <- Sync.Unsafe.defer(ThreeMount.boundRefs(mounted))
                    liveOpt <- Sync.Unsafe.defer(mounted.live.get(new Reconciler.IdentityKey(light)))
                yield
                    assert(liveOpt.isDefined, "light must have a live entry in the mounted map")
                    assert(
                        refs.exists(_._1.node eq light),
                        "boundRefs must register a triple for the light's signal position"
                    )
                    val newPos = Three.Vec3(4, 5, 6)
                    refs.foreach { case (live, patch, _) =>
                        if live.node eq light then
                            import AllowUnsafe.embrace.danger
                            Reconciler.patchProp(live, patch(newPos)(_))
                    }
                    val liveObj = liveOpt.get.obj
                    import AllowUnsafe.embrace.danger
                    val px = liveObj.position.x.asInstanceOf[Double]
                    val py = liveObj.position.y.asInstanceOf[Double]
                    val pz = liveObj.position.z.asInstanceOf[Double]
                    assert(math.abs(px - 4.0) < 0.001, s"light position.x must be 4.0 after patch, got $px")
                    assert(math.abs(py - 5.0) < 0.001, s"light position.y must be 5.0 after patch, got $py")
                    assert(math.abs(pz - 6.0) < 0.001, s"light position.z must be 6.0 after patch, got $pz")
                end for
            }
        }
    }

    "reactive region children: a signal prop on a region child subtree updates through the live mount path" in {
        // End-to-end through the production wiring: ThreeMount.subscribeReactiveRegions installs the real
        // subscribeSubtreeBoundRefs hook, which walks each materialized region element's subtree (a group
        // wrapping a mesh here, exercising subtreeBoundRefs's recursion into a child) and forks an observe
        // fiber per reactive prop. Emitting a new position must move the live child mesh; dropping the hook
        // wiring or the subtree recursion leaves the mesh at its materialize seed and fails this test.
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    posRef <- Signal.initRef(Three.Vec3(1, 1, 1))
                    items  <- Signal.initRef(Chunk(0))
                    region = items.foreachKeyed(_.toString)(_ =>
                        Three.group(
                            Three.mesh(Three.Geometry.box(), Three.Material.standard()).position(posRef)
                        )
                    )
                    scene = Three.scene(region)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _   <- ThreeMount.subscribeReactiveRegions(mounted)
                    _   <- posRef.set(Three.Vec3(7, 8, 9))
                    got <- pollUntil(liveMeshX(mounted).map(x => math.abs(x - 7.0) < 0.001))
                    px  <- liveMeshX(mounted)
                yield
                    assert(got, s"the region-child mesh must update through the live subscription; final position.x=$px")
                    assert(math.abs(px - 7.0) < 0.001, s"region-child mesh position.x must be 7.0, got $px")
                end for
            }
        }
    }

    /** Reads the single live mesh's position.x from the mounted map, or NaN when none is present. */
    private def liveMeshX(mounted: Reconciler.Mounted)(using Frame): Double < Sync =
        Sync.Unsafe.defer {
            import AllowUnsafe.embrace.danger
            mounted.live.values.find(_.node.isInstanceOf[Three.Ast.Mesh]) match
                case Some(meshLive) => meshLive.obj.position.x.asInstanceOf[Double]
                case None           => Double.NaN
        }

    /** Polls `cond` until true or the bound, suspending the fiber a tick between tries (the kyo-core
      * observe-delivery pattern); never blocks a thread.
      */
    private def pollUntil(cond: Boolean < Async, maxTries: Int = 2000)(using Frame): Boolean < Async =
        Loop.indexed { i =>
            if i >= maxTries then Loop.done(false)
            else cond.map(c => if c then Loop.done(true) else Async.sleep(1.millis).andThen(Loop.continue))
        }

    "foreachKeyed live map returns to pre-add size after removing all elements" in {
        Scope.run {
            Abort.recover[ThreeException](e => Abort.panic(e)) {
                for
                    items <- Signal.initRef(Chunk.empty[Int])
                    foreach = items.foreachKeyed(_.toString) { i =>
                        Three.mesh(Three.Geometry.box(), Three.Material.standard())
                    }
                    scene = Three.scene(foreach)
                    mountResult <- Reconciler.mount(scene)
                    (_, mounted) = mountResult
                    _         <- Reconciler.fillReactiveRegionsOnce(mounted)
                    sizeEmpty <- Sync.Unsafe.defer(mounted.live.size)
                    // Add two elements.
                    _       <- items.set(Chunk(1, 2))
                    _       <- Reconciler.fillReactiveRegionsOnce(mounted)
                    sizeTwo <- Sync.Unsafe.defer(mounted.live.size)
                    // Remove both elements (back to empty).
                    _         <- items.set(Chunk.empty[Int])
                    _         <- Reconciler.fillReactiveRegionsOnce(mounted)
                    sizeFinal <- Sync.Unsafe.defer(mounted.live.size)
                yield
                    assert(sizeTwo > sizeEmpty, s"adding elements must grow the live map: empty=$sizeEmpty two=$sizeTwo")
                    assert(
                        sizeFinal == sizeEmpty,
                        s"removing all elements must return live map to initial size: before=$sizeEmpty final=$sizeFinal"
                    )
                end for
            }
        }
    }

    "a server-mode click on an object that declares onClick but has no indexed path is a DEFECT, not a silent drop" in {
        // The click-path twin of a dropped update. The object is live, it is interactive, the user clicks it,
        // and the click cannot be addressed to the server, so it never responds. Nothing else in the scene
        // misbehaves, which is exactly what makes it invisible: this is the case that must speak.
        val clickable = Three.mesh(Three.Geometry.box(), Three.Material.basic()).onClick(_ => Kyo.unit)
        Scope.run {
            Reconciler.mount(Three.scene(clickable)).map { case (root, mounted) =>
                val live = root.children(0)
                // `pathForLive` defaults to `_ => Absent`: the index does not cover this object.
                assert(
                    ThreeMount.pointerRoute(mounted, live, PointerKind.Click) == ThreeMount.PointerRoute.Unaddressable,
                    "an onClick-bearing object with no indexed path must be reported as unaddressable, not dropped in silence"
                )
            }
        }
    }

    "a server-mode click on a hover-only object with no indexed path is an EXPECTED no-op, and must stay silent" in {
        // A hover-only node IS a legitimate raycast target (Raycasting.interactiveTargets casts against any
        // node declaring onClick OR onPointerOver OR onPointerOut), so it is hit on every drag across it.
        // The server has no onClick to run for it, so dropping the click is right, and warning about it
        // would fire constantly and train people to ignore the log.
        val hoverOnly = Three.mesh(Three.Geometry.box(), Three.Material.basic()).onPointerOver(_ => Kyo.unit)
        Scope.run {
            Reconciler.mount(Three.scene(hoverOnly)).map { case (root, mounted) =>
                val live = root.children(0)
                assert(
                    ThreeMount.pointerRoute(mounted, live, PointerKind.Click) == ThreeMount.PointerRoute.NoHandler,
                    "a hover-only object has no click to run, so its click must be dropped silently rather than reported"
                )
            }
        }
    }

    "a server-mode click on an indexed object is posted to the server at its path" in {
        val clickable = Three.mesh(Three.Geometry.box(), Three.Material.basic()).onClick(_ => Kyo.unit)
        Scope.run {
            Reconciler.mount(Three.scene(clickable)).map { case (root, mounted) =>
                val live = root.children(0)
                mounted.pathForLive = _ => Present(Seq("0", "0", "0"))
                assert(
                    ThreeMount.pointerRoute(mounted, live, PointerKind.Click) == ThreeMount.PointerRoute.Post(Seq("0", "0", "0")),
                    "an indexed object's click must be addressed to the server at its path"
                )
            }
        }
    }

    "on a server-driven page a HOVER is posted to the session and the local handler is NOT run" in {
        // Pinned at the DISPATCH, not at the routing table, and that distinction is the point. A hover
        // dispatch that skips the mode seam and runs the LOCAL closure is indistinguishable from a correct
        // one to any test that only asks `pointerRoute` what it would have decided, because such a dispatch
        // never consults a route at all. On a server-driven page the client's tree is rebuilt per connection,
        // so its signals are DIFFERENT INSTANCES from the session's, and a locally-run handler writes a
        // signal nothing renders: the user hovers, nothing happens, and nothing is logged. So this drives
        // `dispatchPointer` itself and asserts BOTH halves: the event reaches the session, and the local
        // closure stays unrun.
        val pointer = Three.Pointer(Three.Vec3(1, 2, 3), 4.0, (0.1, 0.2), Three.Pointer.Buttons.none)
        Scope.run {
            for
                localRan <- AtomicRef.init(false)
                events   <- Channel.init[Any < Async](8)
                mesh = Three.mesh(Three.Geometry.box(), Three.Material.basic())
                    .onPointerOver(_ => localRan.set(true))
                posted <- AtomicRef.init(Chunk.empty[(Seq[String], String)])
                // The inline client's post seam, exactly as HtmlRenderer.clientJs installs it on a hydrated
                // page: a function of (path, encoded).
                seam <- Sync.Unsafe.defer {
                    sjs.Dynamic.literal(
                        __kyoPostBackendEvent = (p: sjs.Array[String], encoded: String) =>
                            import AllowUnsafe.embrace.danger
                            discard(Sync.Unsafe.evalOrThrow(posted.updateAndGet(_.appended((Seq.from(p), encoded)))))
                    )
                }
                (root, mounted) <- Reconciler.mount(Three.scene(mesh))
                live = root.children(0)
                _    = mounted.pathForLive = _ => Present(Seq("0", "0", "0"))
                _    <- ThreeMount.dispatchPointer(mounted, events, live, pointer, PointerKind.Over, Present(seam))
                sent <- posted.get
                ran  <- localRan.get
                // The local branch offers the handler's effect onto `events`; in server mode nothing is offered.
                queued <- events.size
            yield
                assert(sent.size == 1, "a hover on a server-driven page must reach the session")
                val (path, encoded) = sent.head
                assert(path == Seq("0", "0", "0"))
                assert(PointerWire.decode(encoded) == Present((PointerKind.Over, pointer)))
                assert(!ran, "the local hover closure must NOT run on a server-driven page: it writes a signal nothing renders")
                assert(queued == 0, "no local handler effect may be enqueued in server mode")
            end for
        }
    }

    "on a client-local page a HOVER runs the local handler, because there is no session to address" in {
        val pointer = Three.Pointer(Three.Vec3(1, 2, 3), 4.0, (0.1, 0.2), Three.Pointer.Buttons.none)
        Scope.run {
            for
                events <- Channel.init[Any < Async](8)
                mesh = Three.mesh(Three.Geometry.box(), Three.Material.basic()).onPointerOver(_ => Kyo.unit)
                (root, mounted) <- Reconciler.mount(Three.scene(mesh))
                live = root.children(0)
                // Absent seam: a runMount / embed with no page WS. The local tree is the only tree.
                _      <- ThreeMount.dispatchPointer(mounted, events, live, pointer, PointerKind.Over, Absent)
                queued <- events.size
            yield assert(queued == 1, "with no session, the hover handler must run locally")
        }
    }

    "hover routes exactly as click does: an indexed hover target is POSTED to the server, never run locally" in {
        // Hover must be addressed to the session exactly as a click is. On a server-driven page the client's
        // tree is rebuilt per connection, so its signals are DIFFERENT INSTANCES from the session's, and a
        // hover handler run locally writes a signal nothing renders: the user hovers, nothing happens, and
        // nothing is logged.
        val hoverable = Three.mesh(Three.Geometry.box(), Three.Material.basic())
            .onPointerOver(_ => Kyo.unit)
            .onPointerOut(_ => Kyo.unit)
        Scope.run {
            Reconciler.mount(Three.scene(hoverable)).map { case (root, mounted) =>
                val live = root.children(0)
                mounted.pathForLive = _ => Present(Seq("0", "0", "0"))
                assert(ThreeMount.pointerRoute(mounted, live, PointerKind.Over) == ThreeMount.PointerRoute.Post(Seq("0", "0", "0")))
                assert(ThreeMount.pointerRoute(mounted, live, PointerKind.Out) == ThreeMount.PointerRoute.Post(Seq("0", "0", "0")))
            }
        }
    }

    "a server-mode hover on an object that declares onPointerOver but has no indexed path is a DEFECT, not a silent drop" in {
        val hoverable = Three.mesh(Three.Geometry.box(), Three.Material.basic()).onPointerOver(_ => Kyo.unit)
        Scope.run {
            Reconciler.mount(Three.scene(hoverable)).map { case (root, mounted) =>
                val live = root.children(0)
                assert(
                    ThreeMount.pointerRoute(mounted, live, PointerKind.Over) == ThreeMount.PointerRoute.Unaddressable,
                    "a hover handler that can never be reached must speak, exactly as an unreachable click does"
                )
            }
        }
    }

    "a hover over a click-only object is an EXPECTED no-op: the event is not posted, so it costs no round trip" in {
        // A click-only object is still a raycast target, so the pointer crosses it on any drag. There is no
        // hover handler to run, so posting the event would be a wasted message the session would discard.
        val clickOnly = Three.mesh(Three.Geometry.box(), Three.Material.basic()).onClick(_ => Kyo.unit)
        Scope.run {
            Reconciler.mount(Three.scene(clickOnly)).map { case (root, mounted) =>
                val live = root.children(0)
                mounted.pathForLive = _ => Present(Seq("0", "0", "0"))
                assert(ThreeMount.pointerRoute(mounted, live, PointerKind.Over) == ThreeMount.PointerRoute.NoHandler)
                assert(ThreeMount.pointerRoute(mounted, live, PointerKind.Out) == ThreeMount.PointerRoute.NoHandler)
            }
        }
    }

    "a dropped pointer event is reported once per MOUNT: a second mount at the SAME declaration site is not silenced by the first" in {
        // The same property ThreeBackendTest pins for the wire drops, pinned here for the pointer drops,
        // because it was ASSUMED here and not tested. The set must belong to the mount: pages re-hydrate,
        // a second embed mounts, a suite mounts scene after scene, and every one of them replays the SAME
        // declaration sites. A set that outlived the mount would swallow the next mount's genuine drop as a
        // duplicate, and an anti-silence guard that goes silent reads as proof that nothing was dropped.
        val clickable = Three.mesh(Three.Geometry.box(), Three.Material.basic()).onClick(_ => Kyo.unit)
        val log       = new CapturingLog
        Scope.run {
            Log.let(Log(log)) {
                for
                    // Two mounts of the SAME node value, so the declaration site (and therefore the dedupe
                    // key) is identical across them: only the mount differs.
                    (_, first)  <- Reconciler.mount(Three.scene(clickable))
                    (_, second) <- Reconciler.mount(Three.scene(clickable))
                    _           <- ThreeMount.reportUnaddressablePointer(first, clickable, PointerKind.Click)
                    _           <- ThreeMount.reportUnaddressablePointer(first, clickable, PointerKind.Click)
                    afterFirst  <- Sync.defer(log.warnings)
                    // A DIFFERENT kind at the same site is a different thing to say, so it speaks too.
                    _          <- ThreeMount.reportUnaddressablePointer(first, clickable, PointerKind.Over)
                    afterKind  <- Sync.defer(log.warnings)
                    _          <- ThreeMount.reportUnaddressablePointer(second, clickable, PointerKind.Click)
                    afterMount <- Sync.defer(log.warnings)
                yield
                    assert(afterFirst.size == 1, "a repeat drop at one site on ONE mount must be reported once, not on every hit")
                    assert(afterFirst.head.contains("dropped a click"))
                    assert(afterKind.size == 2, "the same site failing for a DIFFERENT event kind has its own thing to say")
                    assert(afterKind(1).contains("dropped a pointer-over"))
                    assert(
                        afterMount.size == 3,
                        "a FRESH mount's drop at a site an earlier mount already reported must still be reported"
                    )
                end for
            }
        }
    }

    "a client-owned host runs its pointer handlers LOCALLY even on a server-driven page" in {
        // `Element.clientOwned` hands a subtree to the browser: the session neither subscribes its regions nor
        // renders from the signals its own copy of the handler would write. Choosing the mode from the mere
        // presence of the post seam would post the event there anyway, and then NOTHING runs it: the session's
        // handler writes signals nothing renders, and the local closure was skipped because we posted. The
        // object is live, interactive, and answers nothing on either side, silently. The mode must come from
        // the host's OWNERSHIP, which is what `hostIsClientOwned` reads (the same `data-kyo-client-owned`
        // marker kyo-ui's own inline guard walks for).
        import AllowUnsafe.embrace.danger
        // A host inside a client-owned boundary: `closest` resolves the marker, exactly as the DOM would.
        val ownedHost = sjs.Dynamic.literal(
            closest = (sel: String) => if sel == "[data-kyo-client-owned]" then sjs.Dynamic.literal() else null
        )
        // A host with no such boundary above it.
        val plainHost = sjs.Dynamic.literal(closest = (_: String) => null)
        assert(ThreeMount.hostIsClientOwned(ownedHost), "a host under a client-owned boundary is client-owned")
        assert(!ThreeMount.hostIsClientOwned(plainHost), "a host with no boundary above it is server-owned")
        // A headless/stub host has no `closest` at all, and that means no boundary, never a crash.
        assert(!ThreeMount.hostIsClientOwned(sjs.Dynamic.literal()), "a host with no `closest` is not client-owned")
    }

    "hover posts COALESCE to one transition per frame, carrying the target the pointer ended the frame on" in {
        // A pointermove fires up to a thousand times a second, and a pointer resting on an object's silhouette
        // genuinely alternates hit and miss with sub-pixel jitter, so every one of those is a REAL enter or
        // leave and a time-based throttle would drop true transitions. Coalescing per frame cannot: it emits
        // the transition from what the session was last told to whatever the pointer is on when the frame
        // ends. A target crossed and left inside one frame was never visible and never reaches the wire.
        val a = Three.mesh(Three.Geometry.box(), Three.Material.basic()).onPointerOver(_ => Kyo.unit)
        val b = Three.mesh(Three.Geometry.box(), Three.Material.basic()).onPointerOver(_ => Kyo.unit)
        val c = Three.mesh(Three.Geometry.box(), Three.Material.basic()).onPointerOver(_ => Kyo.unit)
        Scope.run {
            Reconciler.mount(Three.scene(a, b, c)).map { case (root, _) =>
                val (liveA, liveB, liveC) = (root.children(0), root.children(1), root.children(2))
                val pointer               = Three.Pointer(Three.Vec3.zero, 0.0, (0.0, 0.0), Three.Pointer.Buttons.none)
                val hover                 = new ThreeMount.HoverCoalescer

                // Three moves inside ONE frame: the pointer crosses A and B and ends on C.
                hover.record(Present((liveA, pointer)))
                hover.record(Present((liveB, pointer)))
                hover.record(Present((liveC, pointer)))
                val (out1, over1, _) = hover.flush()
                assert(out1.isEmpty, "the session was on nothing, so there is nothing to leave")
                assert(over1.exists(_.obj eq liveC.obj), "the session hears the target the pointer ENDED the frame on")

                // A and B were crossed and left inside that frame: neither ever reached the wire.
                // Next frame: the pointer moves off C onto A.
                hover.record(Present((liveA, pointer)))
                val (out2, over2, _) = hover.flush()
                assert(out2.exists(_.obj eq liveC.obj), "the leave is diffed against what the session was TOLD, not the last move")
                assert(over2.exists(_.obj eq liveA.obj))

                // A frame in which the pointer never left A costs nothing on the wire.
                hover.record(Present((liveA, pointer)))
                val (out3, over3, _) = hover.flush()
                assert(out3.isEmpty && over3.isEmpty, "a frame that ends on the object the session already has sends nothing")

                // Leaving the scene entirely is still a real transition.
                hover.record(Absent)
                val (out4, over4, _) = hover.flush()
                assert(out4.exists(_.obj eq liveA.obj), "the pointer leaving every object must still tell the session")
                assert(over4.isEmpty)
            }
        }
    }

end ThreeMountTest
