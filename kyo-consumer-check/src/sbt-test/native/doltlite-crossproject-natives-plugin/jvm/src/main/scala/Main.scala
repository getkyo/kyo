import kyo.*

object Main extends KyoApp:
    run {
        Query.outcome.map { outcome =>
            Sync.defer(java.nio.file.Files.writeString(java.nio.file.Path.of("out-jvm.txt"), s"CONSUMER jvm=$outcome\n"))
        }
    }
end Main
