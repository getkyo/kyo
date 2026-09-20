import kyo.*

case class Tick(symbol: String, priceCents: Long) derives CanEqual, Schema

// The JVM fixture's round trip, on Native, against the library the published artifact carries: the embedded
// driver starts and two messages published over IPC arrive at a subscriber.
object Main extends KyoApp:
    run {
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
        Abort.run[Any](roundTrip).map { result =>
            val outcome = result match
                case Result.Success(received) => if received == ticks then "roundtrip" else s"mismatch:$received"
                case Result.Failure(error)    => s"failure:${error.getClass.getSimpleName}"
                case Result.Panic(error)      => s"panic:${error.getClass.getSimpleName}"
            Console.printLine(s"CONSUMER aeron=$outcome")
        }
    }
end Main
