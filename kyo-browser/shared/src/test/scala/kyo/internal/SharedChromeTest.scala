package kyo.internal

import kyo.*

/** [[SharedChrome.Instance]] against a fake launch: each launch counts itself, returns `ws://fake-<n>`, and records when its scope closes
  * (the point where a real launch kills Chrome and removes its user-data directory).
  */
class SharedChromeTest extends BaseBrowserTest:

    final private class FakeLaunches(failFirst: Int):
        val count    = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
        val released = AtomicRef.Unsafe.init(Chunk.empty[String])(using AllowUnsafe.embrace.danger)

        def launch(frame: Frame): String < (Async & Scope & Abort[BrowserSetupException]) =
            given Frame = frame
            Sync.Unsafe.defer(count.incrementAndGet()).map { n =>
                val url = s"ws://fake-$n"
                if n <= failFirst then Abort.fail(BrowserSetupFailedException(s"launch $n failed"))
                else Scope.ensure(Sync.Unsafe.defer(discard(released.updateAndGet(_ :+ url)))).andThen(url)
            }
        end launch

        def launches(using Frame): Int < Sync               = Sync.Unsafe.defer(count.get())
        def releasedUrls(using Frame): Chunk[String] < Sync = Sync.Unsafe.defer(released.get())
    end FakeLaunches

    private def instance(fake: FakeLaunches): SharedChrome.Instance =
        new SharedChrome.Instance(fake.launch, frame => Kyo.unit)

    "a launch failure is retried by the next caller instead of failing every later call" in {
        val fake    = new FakeLaunches(failFirst = 2)
        val shared  = instance(fake)
        val attempt = Abort.run[BrowserReadException | BrowserSetupException](shared.withUrl((url, started) => started.andThen(url)))
        for
            first    <- attempt
            second   <- attempt
            launches <- fake.launches
        yield
            assert(first.isFailure, s"both launches in the first call fail, so it should fail, got $first")
            assert(second == Result.succeed("ws://fake-3"), s"the next call should relaunch and succeed, got $second")
            assert(launches == 3)
        end for
    }

    "callers that see the same dead Chrome before their body starts relaunch it once" in {
        val fake    = new FakeLaunches(failFirst = 0)
        val shared  = instance(fake)
        val callers = 8
        for
            allSawFirst <- Latch.init(callers)
            results     <- Async.foreach(1 to callers, callers) { _ =>
                shared.withUrl { (url, started) =>
                    if url == "ws://fake-1" then
                        allSawFirst.release.andThen(allSawFirst.await).andThen(
                            Abort.fail(BrowserConnectionLostException("the shared Chrome died", Absent))
                        )
                    else started.andThen(url)
                }
            }
            launches <- fake.launches
        yield
            assert(results == Chunk.fill(callers)("ws://fake-2"), s"every caller should retry against the one relaunch, got $results")
            assert(launches == 2, s"expected one relaunch for $callers callers, got ${launches - 1}")
        end for
    }

    "the scope of a Chrome that was replaced is closed" in {
        val fake   = new FakeLaunches(failFirst = 0)
        val shared = instance(fake)
        for
            url <- shared.withUrl { (url, started) =>
                if url == "ws://fake-1" then Abort.fail(BrowserConnectionLostException("the shared Chrome died", Absent))
                else started.andThen(url)
            }
            _        <- assertEventually(fake.releasedUrls.map(_.contains("ws://fake-1")))
            released <- fake.releasedUrls
        yield
            assert(url == "ws://fake-2")
            assert(released == Chunk("ws://fake-1"), s"only the replaced Chrome should be released, got $released")
        end for
    }

    "a failure that does not mean the Chrome is gone is neither retried nor relaunched" in {
        val fake   = new FakeLaunches(failFirst = 0)
        val shared = instance(fake)
        for
            result <- Abort.run[BrowserReadException | BrowserSetupException] {
                shared.withUrl((_, _) => Abort.fail(BrowserProtocolErrorException("Page.navigate", "bad request", Absent)))
            }
            launches <- fake.launches
            again    <- shared.withUrl((url, started) => started.andThen(url))
        yield
            assert(result.isFailure)
            assert(launches == 1)
            assert(again == "ws://fake-1", s"the shared Chrome should still be the first launch, got $again")
        end for
    }

    "a body that has started is not run again when the Chrome is lost inside it" in {
        val fake   = new FakeLaunches(failFirst = 0)
        val shared = instance(fake)
        for
            runs   <- AtomicInt.init(0)
            result <- Abort.run[BrowserReadException | BrowserSetupException] {
                shared.withUrl { (_, started) =>
                    started.andThen(runs.incrementAndGet).andThen(
                        Abort.fail(BrowserConnectionLostException("the shared Chrome died inside the body", Absent))
                    )
                }
            }
            count <- runs.get
            next  <- shared.withUrl((url, started) => started.andThen(url))
        yield
            assert(count == 1, s"the body should run once, ran $count times")
            result match
                case Result.Failure(e: BrowserConnectionLostException) => assert(e.getMessage.contains("died inside the body"))
                case other                                             => fail(s"expected the body's connection loss, got $other")
            assert(next == "ws://fake-2", s"the lost Chrome should still be replaced for the next caller, got $next")
        end for
    }

end SharedChromeTest
