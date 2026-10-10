import kyo.*

case class Tick(symbol: String, priceCents: Long) derives CanEqual, Schema

// The aeron-natives-plugin round trip, in a test binary: the embedded driver starts and two messages published over
// IPC arrive at a subscriber. Aeron is on the test classpath only, so the round trip completes only when the test link
// got the library the artifact delivers.
class AeronTestOnlyTest extends munit.FunSuite:

    test("a test-only dependency's library is linked into the test binary") {
        import AllowUnsafe.embrace.danger
        val ticks = Chunk(Tick("AAPL", 19023), Tick("AAPL", 19045))
        val roundTrip =
            Topic.run {
                for
                    started  <- Latch.init(1)
                    fiber    <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Tick]("aeron:ipc").take(ticks.size).run))
                    _        <- started.await
                    _        <- Fiber.initUnscoped(Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096)))
                    received <- fiber.get
                yield received
            }
        val outcome = KyoApp.Unsafe.runAndBlock(60.seconds)(Abort.run[Any](roundTrip))
        assertEquals(outcome, Result.succeed(Result.succeed(ticks)))
    }
end AeronTestOnlyTest
