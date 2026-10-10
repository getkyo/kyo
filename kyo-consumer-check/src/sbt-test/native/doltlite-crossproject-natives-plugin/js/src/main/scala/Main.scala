import kyo.*
import scala.scalajs.js

object Main extends KyoApp:
    run {
        Query.outcome.map { outcome =>
            Sync.defer(js.Dynamic.global.require("fs").writeFileSync("out-js.txt", s"CONSUMER js=$outcome\n"))
        }
    }
end Main
