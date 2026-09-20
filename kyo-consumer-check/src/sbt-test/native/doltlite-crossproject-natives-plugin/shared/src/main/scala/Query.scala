import kyo.*

// The one query every leg runs. Its answer is what says the engine the plugin delivered to that leg was opened,
// rather than the driver reporting it unavailable.
object Query:
    def outcome(using Frame): String < Async =
        Abort.run[Any](DB.run("doltlite://:memory:")(sql"SELECT 1 + 1".as[Int].run)).map {
            case Result.Success(rows)  => rows.mkString(",")
            case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
            case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
        }
end Query
