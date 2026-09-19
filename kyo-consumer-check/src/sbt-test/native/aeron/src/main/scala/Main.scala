import kyo.*

case class Tick(symbol: String, priceCents: Long) derives CanEqual, Schema

// The JVM fixture's round trip over IPC. Publishing and subscribing together reach every Aeron binding, so the link has
// to resolve the whole shim; the outcome shows whether this build linked Aeron.
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
