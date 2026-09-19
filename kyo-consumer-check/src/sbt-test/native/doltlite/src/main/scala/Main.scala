import kyo.*

// Opens an in-memory database and runs one query. The outcome shows whether this build linked the DoltLite engine.
object Main extends KyoApp:
    run {
        Abort.run[Any](DB.run("doltlite://:memory:")(sql"SELECT 1 + 1".as[Int].run)).map { result =>
            val outcome = result match
                case Result.Success(rows)  => rows.mkString(",")
                case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
                case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
            Console.printLine(s"CONSUMER doltlite=$outcome")
        }
    }
end Main
