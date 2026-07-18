package kyo

import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** The test-support projection: installs one `window[name]` object over a live [[Three.Mount]] so a
  * CDP or browser driver reads the mount through a single documented shape.
  *
  * The projection reads the handle synchronously (`renders`, `contextLost`, `disposed`, `canvasToken`,
  * `signal`) and bridges its one async member (`pixel`, a `mount.readPixels(x, y, 1, 1)`) to a JS
  * thenable resolving to `[r, g, b, a]`. The `signals` map exposes the caller's own `SignalRef`s, the
  * same ones kyo-ui `.value(SignalRef)` binds, so a driver reads and writes the application's live
  * signals rather than a copy. An unknown `signal(name)` is a defined, non-throwing no-op: `get()`
  * yields "" and `set(v)` does nothing.
  *
  * The install registers a `Scope.ensure` that deletes the global on scope close, so exactly one
  * `window[name]` exists for the install's scope lifetime and none after it.
  */
object ThreeInspect:

    /** Installs the `window[name]` projection over `mount` and removes it on scope close. */
    def install(
        name: String,
        mount: Three.Mount,
        signals: Map[String, SignalRef[String]] = Map.empty
    )(using Frame): Unit < (Sync & Scope) =
        Sync.Unsafe.defer {
            // Unsafe: the page-to-kyo boundary. Each closure runs a synchronous mount read via
            // evalOrThrow (a Sync effect), or, for `pixel`, starts a detached fiber that resolves a
            // JS Promise. The ambient AllowUnsafe comes from this defer block's own context function.
            val w = dom.window.asInstanceOf[js.Dynamic]
            // A stable per-canvas token: stamped once on the canvas element and re-read, so two
            // installs over the SAME canvas yield the SAME token (a canvas-identity check).
            val token =
                Maybe(mount.canvas.getAttribute("data-kyo-inspect-token")) match
                    case Present(existing) if existing.nonEmpty => existing
                    case _ =>
                        val fresh = js.Dynamic.global.Math.random().toString()
                        mount.canvas.setAttribute("data-kyo-inspect-token", fresh)
                        fresh
            def signalAccessor(n: String): js.Dynamic =
                Maybe.fromOption(signals.get(n)) match
                    case Present(ref) => js.Dynamic.literal(
                            get = () => Sync.Unsafe.evalOrThrow(ref.current),
                            set = (v: String) => discard(Sync.Unsafe.evalOrThrow(ref.set(v)))
                        )
                    case Absent => js.Dynamic.literal(
                            get = () => "",
                            set = (_: String) => ()
                        )
            val projection = js.Dynamic.literal(
                renders = () => Sync.Unsafe.evalOrThrow(mount.renders.current).toDouble,
                contextLost = () => Sync.Unsafe.evalOrThrow(mount.renderer.contextLost),
                disposed = () => Sync.Unsafe.evalOrThrow(mount.disposed.current),
                canvasToken = () => token,
                signal = (n: String) => signalAccessor(n),
                pixel = (x: Int, y: Int) =>
                    // Unsafe: bridge the async readPixels to a JS Promise a CDP driver awaits. The
                    // read is started on a detached fiber (a JS callback cannot await an Async effect),
                    // resolving [r,g,b,a] on success and rejecting on the typed RenderFailure.
                    new js.Promise[js.Array[Int]]((resolve, reject) =>
                        discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(
                            Abort.run[ThreeException](mount.readPixels(x, y, 1, 1)).map {
                                case Result.Success(span) =>
                                    Sync.Unsafe.defer(resolve(js.Array(
                                        span(0) & 0xff,
                                        span(1) & 0xff,
                                        span(2) & 0xff,
                                        span(3) & 0xff
                                    )))
                                case Result.Failure(e) => Sync.Unsafe.defer(reject(e.getMessage))
                                case Result.Panic(e)   => Sync.Unsafe.defer(reject(e.getMessage))
                            }
                        ).unit))
                    )
            )
            w.updateDynamic(name)(projection)
        }.andThen {
            Scope.ensure(Sync.Unsafe.defer {
                // Unsafe: remove the one installed global on scope close (the paired teardown). The
                // ambient AllowUnsafe comes from this defer block's own context function.
                val w = dom.window.asInstanceOf[js.Dynamic]
                js.special.delete(w, name)
            })
        }
end ThreeInspect
