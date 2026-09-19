import kyo.*

// The same query the Native fixture runs, so the two platforms are held to one answer: SQLite's math extension is
// part of the engine every platform ships.
object Main extends KyoApp:
    run {
        Abort.run[Any](DB.run("sqlite://:memory:")(sql"SELECT CAST(power(2, 10) AS INTEGER)".as[Int].run)).map { result =>
            val outcome = result match
                case Result.Success(rows)  => rows.mkString(",")
                case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
                case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
            Sync.defer(java.nio.file.Files.writeString(java.nio.file.Path.of("out.txt"), s"CONSUMER sqlite=$outcome\n"))
        }
    }
end Main
