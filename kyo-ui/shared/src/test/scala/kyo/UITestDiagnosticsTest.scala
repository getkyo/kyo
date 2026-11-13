package kyo

import kyo.internal.Diagnostics

class UITestDiagnosticsTest extends UITest:

    private def awaitPhase[E](fiber: Fiber[Unit, Abort[E]], entered: Promise[Unit, Abort[E]])(using
        Frame
    ): Unit < (Async & Abort[E]) =
        fiber.onComplete(result => entered.completeDiscard(result.map(_ => ()))).andThen(entered.get)

    "phase waiter propagates cancellation before browser setup reaches its phase" in {
        Scope.run {
            for
                entered <- Promise.init[Unit, Abort[String]]
                fiber   <- Fiber.init(Sync.defer(cancel("controlled startup cancellation")))
                result  <- Abort.run[String](awaitPhase(fiber, entered))
            yield result match
                case Result.Panic(error: kyo.test.TestCancelled) => assert(error.reason == "controlled startup cancellation")
                case other                                       => fail(s"expected startup cancellation, got $other")
        }
    }

    "phase waiter propagates failure before browser setup reaches its phase" in {
        Scope.run {
            for
                entered <- Promise.init[Unit, Abort[String]]
                fiber   <- Fiber.init(Abort.fail("controlled startup failure"))
                result  <- Abort.run[String](awaitPhase(fiber, entered))
            yield assert(result == Result.fail("controlled startup failure"))
        }
    }

    "UI session diagnostics identify the active body and close with the session" in {
        for
            _ <- withUI(UI.div("diagnostics control")) {
                Sync.defer {
                    val snapshot = Diagnostics.dumpAll()
                    assert(snapshot.contains("UITest@"), snapshot)
                    assert(snapshot.contains("phase=browser body"), snapshot)
                    assert(snapshot.contains("UITestDiagnosticsTest.scala:"), snapshot)
                }
            }
            snapshot <- Sync.defer(Diagnostics.dumpAll())
        yield assert(!snapshot.contains("UITest@"), snapshot)
    }

    "UI session diagnostics identify a named operation and do not render input values" in {
        withUI(UI.input.value("private input contents")) {
            UITest.at("controlled operation") {
                Sync.defer {
                    val snapshot = Diagnostics.dumpAll()
                    assert(snapshot.contains("phase=controlled operation"), snapshot)
                    assert(!snapshot.contains("private input contents"), snapshot)
                }
            }
        }
    }

    "UI session diagnostics close when UI construction is interrupted" in {
        for
            entered <- Latch.init(1)
            release <- Latch.init(1)
            _       <- Scope.run {
                for
                    fiber <- Fiber.init {
                        withUI(entered.release.andThen(release.await).andThen(UI.div("unreached")))(Kyo.unit)
                    }
                    _ <- entered.await
                    _ <- Sync.defer {
                        val snapshot = Diagnostics.dumpAll()
                        assert(snapshot.contains("phase=build UI"), snapshot)
                    }
                    _ <- fiber.interrupt
                yield ()
            }
            snapshot <- Sync.defer(Diagnostics.dumpAll())
        yield assert(!snapshot.contains("UITest@"), snapshot)
    }

    "UI session diagnostics close when an operation fails" in {
        for
            result <- Abort.run[String] {
                withUI(UI.div("failure control")) {
                    UITest.at("failing operation")(Abort.fail("expected failure"))
                }
            }
            _ <- result match
                case Result.Panic(error) => Abort.panic(error)
                case _                   => Kyo.unit
            snapshot <- Sync.defer(Diagnostics.dumpAll())
        yield
            assert(result == Result.fail("expected failure"))
            assert(!snapshot.contains("UITest@"), snapshot)
    }

    "UI session diagnostics remain visible while browser cleanup is suspended" in {
        for
            entered <- Promise.init[Unit, Abort[BrowserException | HttpServerException]]
            release <- Latch.init(1)
            _       <- Scope.run {
                val operation: Unit < (Async & Abort[BrowserException | HttpServerException]) = Scope.run {
                    withUI(UI.div("cleanup control")) {
                        Scope.ensure(entered.completeDiscard(Result.succeed(())).andThen(release.await))
                    }
                }
                for
                    fiber    <- Fiber.init(operation)
                    snapshot <- Sync.ensure(release.release) {
                        awaitPhase(fiber, entered).andThen(Sync.defer(Diagnostics.dumpAll()))
                    }
                    _ <- fiber.get
                yield assert(snapshot.contains("phase=browser cleanup"), snapshot)
                end for
            }
            snapshot <- Sync.defer(Diagnostics.dumpAll())
        yield assert(!snapshot.contains("UITest@"), snapshot)
    }

    "UI session diagnostics identify an interrupted browser operation and then close" in {
        for
            entered <- Promise.init[Unit, Abort[BrowserException | HttpServerException]]
            release <- Latch.init(1)
            _       <- Scope.run {
                val operation: Unit < (Async & Abort[BrowserException | HttpServerException]) = Scope.run {
                    withUI(UI.div("interruption control")) {
                        UITest.at("waiting operation")(entered.completeDiscard(Result.succeed(())).andThen(release.await))
                    }
                }
                for
                    fiber <- Fiber.init(operation)
                    _     <- awaitPhase(fiber, entered)
                    _     <- Sync.defer {
                        val snapshot = Diagnostics.dumpAll()
                        assert(snapshot.contains("phase=waiting operation"), snapshot)
                    }
                    _ <- fiber.interrupt
                yield ()
                end for
            }
            snapshot <- Sync.defer(Diagnostics.dumpAll())
        yield assert(!snapshot.contains("UITest@"), snapshot)
    }

end UITestDiagnosticsTest
