package kyo

import kyo.internal.DomBackend
import kyo.kernel.ContextEffect
import org.scalajs.dom
import scala.collection.mutable
import scala.scalajs.js as scalajs

class DomBackendAnimationsTest extends kyo.test.Test[Any]:

    DomTestEnv.install

    override def config = super.config.sequential

    final private class Frames:
        private val callbacks = mutable.ArrayBuffer.empty[(Int, scalajs.Function1[Double, Unit])]
        private var nextId    = 0

        def install(using Frame): Unit < (Sync & Scope) =
            for
                original <- Sync.defer {
                    // Native function properties are replaced only inside this scoped DOM fixture.
                    val window = dom.window.asInstanceOf[scalajs.Dynamic]
                    (window.requestAnimationFrame, window.cancelAnimationFrame)
                }
                _ <- Scope.ensure(Sync.defer {
                    val window = dom.window.asInstanceOf[scalajs.Dynamic]
                    window.requestAnimationFrame = original._1
                    window.cancelAnimationFrame = original._2
                })
                _ <- Sync.defer {
                    val window = dom.window.asInstanceOf[scalajs.Dynamic]
                    window.requestAnimationFrame = ((callback: scalajs.Function1[Double, Unit]) =>
                        nextId += 1
                        callbacks += ((nextId, callback))
                        nextId
                    ): scalajs.Function1[scalajs.Function1[Double, Unit], Int]
                    window.cancelAnimationFrame = ((id: Int) =>
                        callbacks.filterInPlace(_._1 != id)
                        ()
                    ): scalajs.Function1[Int, Unit]
                }
            yield ()

        def flush(using Frame): Unit < Sync = Sync.defer {
            val batch = callbacks.toVector
            callbacks.clear()
            batch.foreach((_, callback) => callback(0))
        }
    end Frames

    final private class Mounted(fiber: Fiber[Unit, Any], owner: AtomicRef[Maybe[Scope.Finalizer]]):
        def close(using Frame): Unit < Async =
            fiber.interrupt.andThen(fiber.getResult).andThen {
                owner.get.map {
                    case Present(scope) => scope.await
                    case Absent         => Kyo.unit
                }
            }
    end Mounted

    private def mount(ui: UI, selector: String)(using Frame, kyo.test.AssertScope): Mounted < (Async & Scope) =
        for
            owner <- AtomicRef.init[Maybe[Scope.Finalizer]](Absent)
            ready = new DomTestEnv.MountReady
            fiber <- Fiber.initUnscoped(Scope.run {
                ContextEffect.suspendWith(Tag[Scope]) { scope =>
                    owner.set(Present(scope)).andThen(DomBackend.mount(ui, selector, ready))
                }
            })
            mounted = new Mounted(fiber, owner)
            _ <- Scope.ensure(mounted.close)
            _ <- assertEventually(Sync.defer(ready.installed))
        yield mounted

    private def animation(id: String)(using Frame): UI =
        UI.div(Svg.svg.width(100).height(100)(
            Svg.circle.r(10)(Svg.animate.id(id).attributeName("r").from(10.0).to(20.0).dur("10s").begin("indefinite"))
        ))

    "an open local mount starts its animation on the queued frame" in {
        Scope.run {
            for
                frames <- Sync.defer(new Frames)
                _      <- frames.install
                _      <- Sync.defer(dom.document.body.innerHTML = "<div id='target'></div>")
                _      <- mount(animation("open-animation"), "#target")
                calls  <- Sync.defer(new mutable.ArrayBuffer[String])
                _      <- Sync.defer {
                    // jsdom has DOM nodes but no SMIL implementation; observe only the native call boundary here.
                    val element = dom.document.getElementById("open-animation").asInstanceOf[scalajs.Dynamic]
                    element.beginElement = (() =>
                        calls += "open"; ()
                    ): scalajs.Function0[Unit]
                }
                _      <- frames.flush
                actual <- Sync.defer(Chunk.from(calls))
            yield assert(actual == Chunk("open"))
        }
    }

    "closing one local mount cancels its pending animation without affecting another mount" in {
        Scope.run {
            for
                frames <- Sync.defer(new Frames)
                _      <- frames.install
                _      <- Sync.defer(dom.document.body.innerHTML = "<div id='left'></div><div id='right'></div>")
                left   <- mount(animation("left-animation"), "#left")
                _      <- mount(animation("right-animation"), "#right")
                calls  <- Sync.defer(new mutable.ArrayBuffer[String])
                _      <- Sync.defer {
                    val leftElement  = dom.document.getElementById("left-animation").asInstanceOf[scalajs.Dynamic]
                    val rightElement = dom.document.getElementById("right-animation").asInstanceOf[scalajs.Dynamic]
                    leftElement.beginElement = (() =>
                        calls += "left"; ()
                    ): scalajs.Function0[Unit]
                    rightElement.beginElement = (() =>
                        calls += "right"; ()
                    ): scalajs.Function0[Unit]
                }
                _         <- left.close
                connected <- Sync.defer(dom.document.getElementById("left-animation").isConnected)
                _         <- frames.flush
                actual    <- Sync.defer(Chunk.from(calls))
            yield
                assert(connected)
                assert(actual == Chunk("right"), s"native starts: $actual")
        }
    }

    "reactive HTML and SVG updates start animations only while their local mount is open" in {
        Kyo.foreach(Chunk(false, true)) { svgBoundary =>
            Scope.run {
                for
                    frames   <- Sync.defer(new Frames)
                    _        <- frames.install
                    _        <- Sync.defer(dom.document.body.innerHTML = "<div id='target'></div>")
                    versions <- Signal.initRef(0)
                    ui =
                        if svgBoundary then
                            UI.div(Svg.svg.width(100).height(100)(versions.map { version =>
                                Svg.circle.r(10)(
                                    Svg.animate.id(s"step-$version").attributeName("r").from(10.0).to(20.0).dur("10s").begin("indefinite")
                                )
                            }))
                        else UI.div(versions.map(version => animation(s"step-$version")))
                    mounted <- mount(ui, "#target")
                    calls   <- Sync.defer(new mutable.ArrayBuffer[String])
                    _       <- Sync.defer {
                        val element = dom.document.getElementById("step-0").asInstanceOf[scalajs.Dynamic]
                        element.beginElement = (() =>
                            calls += "initial"; ()
                        ): scalajs.Function0[Unit]
                    }
                    _ <- frames.flush
                    _ <- versions.set(1)
                    // Replacement and frame registration share one publication. Node presence is an independent barrier.
                    _ <- assertEventually(Sync.defer(dom.document.getElementById("step-1") != null))
                    _ <- Sync.defer {
                        val element = dom.document.getElementById("step-1").asInstanceOf[scalajs.Dynamic]
                        element.beginElement = (() =>
                            calls += "updated"; ()
                        ): scalajs.Function0[Unit]
                    }
                    _ <- frames.flush
                    _ <- versions.set(2)
                    _ <- assertEventually(Sync.defer(dom.document.getElementById("step-2") != null))
                    _ <- Sync.defer {
                        val element = dom.document.getElementById("step-2").asInstanceOf[scalajs.Dynamic]
                        element.beginElement = (() =>
                            calls += "closed"; ()
                        ): scalajs.Function0[Unit]
                    }
                    _      <- mounted.close
                    _      <- frames.flush
                    actual <- Sync.defer(Chunk.from(calls))
                yield assert(actual == Chunk("initial", "updated"), s"SVG boundary: $svgBoundary, native starts: $actual")
            }
        }.unit
    }

end DomBackendAnimationsTest
