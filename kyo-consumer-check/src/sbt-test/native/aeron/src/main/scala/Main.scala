import kyo.*

case class Tick(symbol: String, priceCents: Long) derives CanEqual, Schema

// Starts the embedded driver and publishes over IPC, which reaches every Aeron binding, so the link has to resolve the
// whole shim. The outcome shows whether this build linked Aeron.
object Main extends KyoApp:
    run {
        val ticks = Seq(Tick("AAPL", 19023), Tick("AAPL", 19045))
        Abort.run[Any](Topic.run(Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096)))).map { result =>
            val outcome = result match
                case Result.Success(_)     => "published"
                case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
                case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
            Console.printLine(s"CONSUMER aeron=$outcome")
        }
    }
end Main
