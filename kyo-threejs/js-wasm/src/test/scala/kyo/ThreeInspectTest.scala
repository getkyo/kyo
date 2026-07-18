package kyo

import org.scalajs.dom
import scala.scalajs.js as sjs

/** Tests for [[ThreeInspect]]'s `window[name]` projection: install/remove lifecycle, the signal
  * accessor's known/unknown-name behavior, and the synchronous reads. Every leaf runs on Node with no
  * real DOM: `window` is a plain FFI object this file installs itself (Scala.js Node tests carry no
  * jsdom), and the mount handle is a stub [[Three.Mount]] backed by real `SignalRef`s.
  */
class ThreeInspectTest extends ThreeTest:

    /** The REAL global object, reached via an indirect `eval` (running outside the CommonJS module
      * wrapper). `dom.window`'s native global lookup resolves against this same object, so a property
      * set here is what makes `window` observable from `ThreeInspect.install`'s own `dom.window` read.
      */
    private def trueGlobal(using Frame): sjs.Dynamic < Sync =
        Sync.Unsafe.defer(sjs.eval("globalThis").asInstanceOf[sjs.Dynamic])

    /** Replaces the global `window` with a fresh empty object so each leaf starts from a clean slate,
      * returning the SAME object for reading the installed projection back.
      */
    private def freshWindow(using Frame): sjs.Dynamic < Sync =
        for
            g <- trueGlobal
            w <- Sync.Unsafe.defer {
                val w = sjs.Dynamic.literal()
                g.window = w
                w
            }
        yield w

    /** A minimal [[Three.Mount]] test double: `renders`/`disposed` are real `SignalRef`s (so the
      * projection's synchronous reads observe real emissions), `canvas` is a detached FFI stub with a
      * settable attribute map (the token round-trip `ThreeInspect.install` reads and writes), and
      * `renderer` is a stub [[Three.Renderer]] whose `contextLost` always reads false.
      */
    private def stubMount(rendersVal: Long = 0L, disposedVal: Boolean = false)(using Frame): Three.Mount < Sync =
        for
            rendersRef  <- Signal.initRef(rendersVal)
            disposedRef <- Signal.initRef(disposedVal)
            canvasDyn <- Sync.Unsafe.defer {
                val attrs = scala.collection.mutable.Map.empty[String, String]
                sjs.Dynamic.literal(
                    getAttribute = (name: String) => attrs.getOrElse(name, null),
                    setAttribute = (name: String, value: String) =>
                        attrs.update(name, value); ()
                )
            }
        yield new Three.Mount with MountCanvas:
            def renders: Signal[Long]         = rendersRef
            def disposed: Signal[Boolean]     = disposedRef
            def canvas: dom.HTMLCanvasElement = canvasDyn.asInstanceOf[dom.HTMLCanvasElement]
            def width: Int                    = 0
            def height: Int                   = 0
            def renderer: Three.Renderer =
                new Three.Renderer:
                    def contextLost(using Frame): Boolean < Sync = Sync.defer(false)
                    def unsafe: sjs.Dynamic                      = sjs.Dynamic.literal()
            def readPixels(x: Int, y: Int, width: Int, height: Int)(using Frame): Span[Byte] < (Async & Abort[ThreeException]) =
                Abort.fail(ThreeException.RenderFailure("not exercised by this stub", new Exception("stub")))

    "install registers exactly one window[name]" in {
        for
            w     <- freshWindow
            mount <- stubMount()
            name = "kyoInspectInstallTest"
            result <- Scope.run {
                ThreeInspect.install(name, mount).andThen(
                    Sync.Unsafe.defer {
                        val keyCount = sjs.Object.keys(w.asInstanceOf[sjs.Object]).length
                        (sjs.typeOf(w.selectDynamic(name)), keyCount)
                    }
                )
            }
        yield
            val (typeOf, keyCount) = result
            assert(typeOf == "object", s"window[$name] must be a defined object while installed, got typeOf=$typeOf")
            assert(keyCount == 1, s"install must add exactly one global key, got $keyCount keys on window")
    }

    "close removes the window[name] global" in {
        for
            w     <- freshWindow
            mount <- stubMount()
            name = "kyoInspectCloseTest"
            presentWhileOpen <- Scope.run {
                ThreeInspect.install(name, mount).andThen(
                    Sync.Unsafe.defer(sjs.typeOf(w.selectDynamic(name)) != "undefined")
                )
            }
            presentAfterClose <- Sync.Unsafe.defer(sjs.typeOf(w.selectDynamic(name)) != "undefined")
        yield
            assert(presentWhileOpen, "window[name] must be defined while the install scope is open")
            assert(!presentAfterClose, "window[name] must be removed once the install scope closes")
    }

    "an unknown signal name is a defined non-throwing no-op" in {
        for
            w     <- freshWindow
            mount <- stubMount()
            name = "kyoInspectUnknownSignalTest"
            result <- Scope.run {
                ThreeInspect.install(name, mount).andThen(
                    Sync.Unsafe.defer {
                        val signal = w.selectDynamic(name).signal("missing")
                        val before = signal.get()
                        signal.set("x")
                        (before, signal.get())
                    }
                )
            }
        yield
            val (before, after) = result
            assert(before.asInstanceOf[String] == "", s"an unknown signal's get() must default to the empty string, got $before")
            assert(after.asInstanceOf[String] == "", s"an unknown signal's set() must be a no-op, get() must stay empty, got $after")
    }

    "a known signal name round-trips the caller SignalRef" in {
        for
            w     <- freshWindow
            mount <- stubMount()
            r     <- Signal.initRef("a")
            name = "kyoInspectKnownSignalTest"
            projected <- Scope.run {
                ThreeInspect.install(name, mount, Map("sel" -> r)).andThen(
                    Sync.Unsafe.defer {
                        val signal = w.selectDynamic(name).signal("sel")
                        signal.set("b")
                        signal.get()
                    }
                )
            }
            current <- r.current
        yield
            assert(projected.asInstanceOf[String] == "b", s"the projection's signal(\"sel\").get() must read back \"b\", got $projected")
            assert(current == "b", s"the caller's own SignalRef must observe the projection's write, got $current")
    }

    "a second install over the SAME canvas yields the SAME canvasToken (re-reads the stamped attribute)" in {
        for
            w     <- freshWindow
            mount <- stubMount()
            nameA = "kyoInspectTokenTestA"
            nameB = "kyoInspectTokenTestB"
            tokens <- Scope.run {
                ThreeInspect.install(nameA, mount).andThen(
                    ThreeInspect.install(nameB, mount).andThen(
                        Sync.Unsafe.defer((w.selectDynamic(nameA).canvasToken(), w.selectDynamic(nameB).canvasToken()))
                    )
                )
            }
        yield
            val (tokenA, tokenB) = tokens
            val tokenAStr        = tokenA.asInstanceOf[String]
            val tokenBStr        = tokenB.asInstanceOf[String]
            assert(tokenAStr.nonEmpty, "the first install must stamp a non-empty token onto the canvas")
            assert(
                tokenAStr == tokenBStr,
                s"a second install over the SAME canvas must re-read the SAME stamped token, got $tokenAStr then $tokenBStr"
            )
    }

    "synchronous projection reads reflect the handle" in {
        for
            w     <- freshWindow
            mount <- stubMount(rendersVal = 3L, disposedVal = false)
            name = "kyoInspectReadsTest"
            result <- Scope.run {
                ThreeInspect.install(name, mount).andThen(
                    Sync.Unsafe.defer {
                        val p = w.selectDynamic(name)
                        (p.renders(), p.disposed(), p.canvasToken(), p.canvasToken())
                    }
                )
            }
        yield
            val (renders, disposed, tokenA, tokenB) = result
            assert(renders.asInstanceOf[Double] == 3.0, s"renders() must reflect the handle's renders count, got $renders")
            assert(!disposed.asInstanceOf[Boolean], s"disposed() must reflect the handle's disposed state, got $disposed")
            val tokenAStr = tokenA.asInstanceOf[String]
            assert(tokenAStr.nonEmpty, s"canvasToken() must be a non-empty string, got '$tokenAStr'")
            assert(tokenAStr == tokenB.asInstanceOf[String], s"canvasToken() must be stable across reads, got $tokenA then $tokenB")
    }

end ThreeInspectTest
