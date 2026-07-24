package kyo

import kyo.Browser.*

/** Behavior tests for [[kyo.UI.boundary]] (EXPERIMENTAL) — pending aggregation, eager-behind-fallback,
  * once-only reveal, and typed error selection over mounted descendants.
  */
class BoundaryTest extends UITest:

    final class DataError(msg: String) extends RuntimeException(msg)

    "boundary shows one fallback while ANY mounted child is pending, then reveals the whole area" in {
        val setup =
            for
                gateA <- Promise.init[Unit, Any]
                gateB <- Promise.init[Unit, Any]
            yield (
                gateA,
                gateB,
                UI.div(
                    UI.boundary.fallback(UI.span("area-loading").id("fb"))(
                        UI.div(
                            UI.mounted(gateA.get.andThen(Kyo.lift(UI.span("a-ready").id("a")))),
                            UI.mounted(gateB.get.andThen(Kyo.lift(UI.span("b-ready").id("b"))))
                        )
                    )
                )
            )
        setup.map { (gateA, gateB, ui) =>
            withUI(Kyo.lift(ui)) {
                for
                    _ <- Browser.assertText(Selector.id("fb"), "area-loading")
                    _ <- gateA.completeUnitDiscard
                    // one of two still pending: the fallback must still be up
                    _ <- Browser.assertText(Selector.id("fb"), "area-loading")
                    _ <- gateB.completeUnitDiscard
                    _ <- Browser.assertText(Selector.id("a"), "a-ready")
                    _ <- Browser.assertText(Selector.id("b"), "b-ready")
                yield ()
            }
        }
    }

    "mount effects start EAGERLY behind the fallback (no waterfall)" in {
        val setup =
            for
                started <- AtomicInt.init(0)
                gate    <- Promise.init[Unit, Any]
            yield (
                started,
                gate,
                UI.div(
                    UI.boundary.fallback(UI.span("loading").id("fb"))(
                        UI.div(
                            UI.mounted(started.incrementAndGet.andThen(gate.get).andThen(Kyo.lift(UI.span("x").id("x")))),
                            UI.mounted(started.incrementAndGet.andThen(gate.get).andThen(Kyo.lift(UI.span("y").id("y"))))
                        )
                    )
                )
            )
        setup.map { (started, gate, ui) =>
            withUI(Kyo.lift(ui)) {
                for
                    _ <- Browser.assertText(Selector.id("fb"), "loading")
                    n <- started.get
                    _ <- gate.completeUnitDiscard
                    _ <- Browser.assertText(Selector.id("x"), "x")
                yield assert(n == 2) // both effects were already running while the fallback showed
            }
        }
    }

    "boundary without pending mounts reveals immediately (no fallback flash for sync content)" in {
        val app: UI < Async =
            Kyo.lift(UI.div(
                UI.boundary.fallback(UI.span("never-shown").id("fb"))(
                    UI.div(UI.span("sync-content").id("content"))
                )
            ))
        withUI(app) {
            Browser.assertText(Selector.id("content"), "sync-content")
        }
    }

    "typed error selection: matching error renders at the boundary" in {
        val app: UI < Async =
            Kyo.lift(UI.div(
                UI.boundary
                    .fallback(UI.span("loading").id("fb"))
                    .onError { case e: DataError => UI.span(s"boundary-caught:${e.getMessage}").id("berr") }(
                        UI.div(
                            UI.span("sibling-in-area").id("sib"),
                            UI.mounted(Abort.fail(new DataError("nope")))
                        )
                    )
            ))
        withUI(app) {
            Browser.assertText(Selector.id("berr"), "boundary-caught:nope")
        }
    }

    "non-matching error falls back to node-local default and the boundary reveals normally" in {
        val app: UI < Async =
            Kyo.lift(UI.div(
                UI.boundary
                    .fallback(UI.span("loading").id("fb"))
                    .onError { case e: DataError => UI.span("boundary-caught").id("berr") }(
                        UI.div(
                            UI.span("alive").id("sib"),
                            UI.mounted(Abort.fail(new RuntimeException("other-error")))
                                .onError(t => UI.span(s"node-error:${t.getMessage}").id("nerr"))
                        )
                    )
            ))
        withUI(app) {
            for
                // node-local .onError wins; the failure still settles the boundary count, so the area reveals (sibling intact)
                _ <- Browser.assertText(Selector.id("sib"), "alive")
                _ <- Browser.assertText(Selector.id("nerr"), "node-error:other-error")
            yield ()
        }
    }

    "a supervised tail failure routes to the boundary post-reveal and replaces the revealed content" in {
        val setup =
            for gate <- Promise.init[Unit, Any]
            yield (
                gate,
                UI.div(
                    UI.boundary
                        .fallback(UI.span("loading").id("fb"))
                        .onError { case e: DataError => UI.span(s"boundary-caught:${e.getMessage}").id("berr") }(
                            UI.div(
                                UI.span("revealed-sibling").id("sib"),
                                UI.mounted(
                                    Fiber.init(gate.get.andThen(Abort.fail(new DataError("tail-nope"))))
                                        .map(_ => UI.span("live").id("content"))
                                ).keyed("feed")
                            )
                        )
                )
            )
        setup.map { (gate, ui) =>
            withUI(Kyo.lift(ui)) {
                for
                    _ <- Browser.assertText(Selector.id("sib"), "revealed-sibling")
                    _ <- Browser.assertText(Selector.id("content"), "live")
                    // feed dies AFTER reveal: no node-local .onError, so the tail failure still walks to the boundary
                    _ <- gate.completeUnitDiscard
                    _ <- Browser.assertText(Selector.id("berr"), "boundary-caught:tail-nope")
                yield ()
            }
        }
    }

end BoundaryTest
