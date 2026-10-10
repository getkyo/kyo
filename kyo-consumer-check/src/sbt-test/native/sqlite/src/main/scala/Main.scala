import kyo.*

// Runs a query that SQLite's math extension answers, so the result proves the engine ran with the compile options the
// artifact declares, not only that it linked.
object Main extends KyoApp:
    run {
        Abort.run[Any](DB.run("sqlite://:memory:")(sql"SELECT CAST(power(2, 10) AS INTEGER)".as[Int].run)).map { result =>
            val outcome = result match
                case Result.Success(rows)  => rows.mkString(",")
                case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
                case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
            Console.printLine(s"CONSUMER sqlite=$outcome")
        }
    }
end Main
