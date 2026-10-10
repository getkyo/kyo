import kyo.*

// The same query the Native fixture runs, answered here by the engine the artifact ships.
object Main extends KyoApp:
    run {
        Abort.run[Any](DB.run("doltlite://:memory:")(sql"SELECT 1 + 1".as[Int].run)).map { result =>
            val outcome = result match
                case Result.Success(rows)  => rows.mkString(",")
                case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
                case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
            Sync.defer(java.nio.file.Files.writeString(java.nio.file.Path.of("out.txt"), s"CONSUMER doltlite=$outcome\n"))
        }
    }
end Main
