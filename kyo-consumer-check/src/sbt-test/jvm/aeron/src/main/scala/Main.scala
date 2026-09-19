import kyo.*

case class Tick(symbol: String, priceCents: Long) derives CanEqual, Schema

// Starts the embedded driver and publishes over IPC through the natives the artifact ships.
object Main extends KyoApp:
    run {
        val ticks = Seq(Tick("AAPL", 19023), Tick("AAPL", 19045))
        Abort.run[Any](Topic.run(Topic.publish[Tick]("aeron:ipc")(Stream.init(ticks, 4096)))).map { result =>
            val outcome = result match
                case Result.Success(_)     => "published"
                case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
                case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
            Sync.defer(java.nio.file.Files.writeString(java.nio.file.Path.of("out.txt"), s"CONSUMER aeron=$outcome\n"))
        }
    }
end Main
