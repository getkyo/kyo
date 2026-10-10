import kyo.*
import scala.scalajs.js

// Runs a query SQLite's math extension answers, so the result shows the library koffi opened is the engine built with
// the options the artifact declares, not only a library that loaded.
object Main extends KyoApp:
    run {
        Abort.run[Any](DB.run("sqlite://:memory:")(sql"SELECT CAST(power(2, 10) AS INTEGER)".as[Int].run)).map { result =>
            val outcome = result match
                case Result.Success(rows)  => rows.mkString(",")
                case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
                case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
            Sync.defer(js.Dynamic.global.require("fs").writeFileSync("out.txt", s"CONSUMER sqlite=$outcome\n"))
        }
    }
end Main
