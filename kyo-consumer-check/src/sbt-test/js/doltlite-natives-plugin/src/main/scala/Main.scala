import kyo.*
import scala.scalajs.js

// Opens an in-memory database and runs one query on Node. koffi loads the engine from the filesystem rather than the
// classpath, so the answer shows whether kyo-natives-plugin put the library where the loader looks.
object Main extends KyoApp:
    run {
        Abort.run[Any](DB.run("doltlite://:memory:")(sql"SELECT 1 + 1".as[Int].run)).map { result =>
            val outcome = result match
                case Result.Success(rows)  => rows.mkString(",")
                case Result.Failure(error) => s"failure:${error.getClass.getSimpleName}"
                case Result.Panic(error)   => s"panic:${error.getClass.getSimpleName}"
            Sync.defer(js.Dynamic.global.require("fs").writeFileSync("out.txt", s"CONSUMER doltlite=$outcome\n"))
        }
    }
end Main
